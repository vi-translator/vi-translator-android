package app.vitranslator.phone.card

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * A capture card as Android's USB host sees it, apart from the camera API: CardStream reads a card
 * only once Android offers it as an external camera (UVC), and many phones (several Samsung Galaxy
 * models among them) don't, though they see the card over USB (apps like "USB Camera" read it
 * there with their own USB video driver, which this app doesn't have). So a card is looked for in
 * both places, to say which step fails instead of "not supported": the camera, the USB device, the
 * permission to use it.
 */
object UsbCards {
    private const val TAG = "UsbCards"
    const val ACTION_PERMISSION = "app.vitranslator.phone.USB_PERMISSION"

    /** where a card is, from the best case down */
    enum class Kind {
        /** Android offers it as a camera: it can be read */
        CAMERA,
        /** a USB video device is plugged in, the app may not use it yet (Android asks) */
        USB_NEEDS_PERMISSION,
        /** a USB video device, the app may use it, but Android doesn't offer it as a camera */
        USB_NOT_CAMERA,
        /** a USB device is plugged in, but not a video one (a hub, a card without enough power...) */
        USB_OTHER,
        /** no USB device at all */
        NOTHING,
        /** no USB device, and the phone doesn't say it can host one (USB OTG) */
        NO_HOST,
    }

    data class Status(val kind: Kind, val device: UsbDevice? = null, val cameraId: String? = null) {
        val name get() = device?.let { nameOf(it) }
    }

    fun hostSupported(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    private fun manager(ctx: Context): UsbManager? = ctx.getSystemService(UsbManager::class.java)

    /** the USB devices plugged in now */
    fun devices(ctx: Context): List<UsbDevice> = try {
        manager(ctx)?.deviceList?.values?.toList().orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "devices", e)
        emptyList()
    }

    /** a USB video device (UVC: class 0x0E, on the device or, most often, on its interfaces) */
    fun isVideo(d: UsbDevice): Boolean =
        d.deviceClass == UsbConstants.USB_CLASS_VIDEO || (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }

    fun nameOf(d: UsbDevice): String {
        val n = try {
            listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").trim()
        } catch (_: Exception) {
            ""
        }
        return n.ifEmpty { "USB %04x:%04x".format(d.vendorId, d.productId) }
    }

    /** How the card sends its picture (for the log and a report): "isochronous", "bulk", or null. */
    fun transferOf(d: UsbDevice): String? {
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            // (video streaming: subclass 2)
            if (itf.interfaceClass != UsbConstants.USB_CLASS_VIDEO || itf.interfaceSubclass != 2) continue
            for (e in 0 until itf.endpointCount) {
                when (itf.getEndpoint(e).type) {
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> return "isochronous"
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> return "bulk"
                }
            }
        }
        return null
    }

    fun hasPermission(ctx: Context, d: UsbDevice) = try {
        manager(ctx)?.hasPermission(d) == true
    } catch (e: Exception) {
        false
    }

    /** Where the card is now: as a camera, or only over USB (and with or without the permission), or not at all. */
    fun check(ctx: Context): Status {
        CardStream.find(ctx)?.let { return Status(Kind.CAMERA, cameraId = it) }
        val all = devices(ctx)
        val video = all.firstOrNull { isVideo(it) }
        return when {
            video != null -> Status(if (hasPermission(ctx, video)) Kind.USB_NOT_CAMERA else Kind.USB_NEEDS_PERMISSION, video)
            all.isNotEmpty() -> Status(Kind.USB_OTHER, all.first())
            !hostSupported(ctx) -> Status(Kind.NO_HOST)
            else -> Status(Kind.NOTHING)
        }
    }

    /**
     * Asks Android to let the app use [d] (its dialog; asked again each time the card is plugged in,
     * unless "always" is chosen when Android offers to open the app for it). [done] on the main thread.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun requestPermission(ctx: Context, d: UsbDevice, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val um = manager(app) ?: return done(false)
        if (um.hasPermission(d)) return done(true)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != ACTION_PERMISSION) return
                try {
                    app.unregisterReceiver(this)
                } catch (_: IllegalArgumentException) {
                }
                val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) || um.hasPermission(d)
                done(granted)
            }
        }
        val filter = IntentFilter(ACTION_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        // (mutable: Android adds whether it was granted; explicit, to this app, as Android 14 requires)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), flags)
        try {
            um.requestPermission(d, pi)
        } catch (e: Exception) {
            Log.w(TAG, "permission", e)
            try {
                app.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
            done(false)
        }
    }

    /**
     * Calls [changed] (on the main thread) when a USB device is plugged in or out, or a camera comes
     * or goes (Android lists a card as a camera a moment after it is plugged in). Returns the call
     * that stops watching.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun watch(ctx: Context, changed: () -> Unit): () -> Unit {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        // (once for a burst: registering lists every camera at once, and plugging in comes with several)
        val once = Runnable { changed() }
        val soon = { main.removeCallbacks(once); main.postDelayed(once, 400) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                soon()
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // (the system's broadcasts reach a receiver that isn't exported)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        val cm = app.getSystemService(CameraManager::class.java)
        val cameras = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                soon()
            }

            override fun onCameraUnavailable(cameraId: String) {
                soon()
            }
        }
        try {
            cm?.registerAvailabilityCallback(cameras, main)
        } catch (e: Exception) {
            Log.w(TAG, "cameras", e)
        }
        return {
            main.removeCallbacks(once)
            try {
                app.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
            try {
                cm?.unregisterAvailabilityCallback(cameras)
            } catch (_: Exception) {
            }
        }
    }
}
