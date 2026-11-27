package app.vitranslator.phone.card

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import app.vitranslator.phone.ui.MainActivity

/**
 * Opened by Android when a USB video device (a capture card) is plugged in and the user chooses
 * the app for it: that choice also lets the app use the card. Brings the app up (the capture card's
 * setup sees the card change by itself) and closes.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val d = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (d != null) Log.i("UsbCards", "plugged in: ${UsbCards.nameOf(d)} video=${UsbCards.isVideo(d)} ${UsbCards.transferOf(d) ?: ""}")
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: Exception) {
            Log.w("UsbCards", "open the app", e)
        }
        finish()
    }
}
