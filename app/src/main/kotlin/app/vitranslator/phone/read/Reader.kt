package app.vitranslator.phone.read

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import app.vitranslator.phone.core.Core
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the game: of the frames offered (as fast as they come), takes one whenever the core is
 * ready for the next (it sets the pace, about 4 a second), and gives it to the core, which crops
 * its areas and has ML Kit read them. A frame offered while the last one is still being read, or
 * too early, is skipped at once, so the stream never waits for the reading.
 */
class Reader(private val core: Core) {
    /** The system's power service, for its thermal status (set by the service that reads). */
    @Volatile
    var power: android.os.PowerManager? = null

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "reader").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)

    @Volatile
    private var nextAt = 0L

    /** For the screen: frames seen, frames read, and how long the last reading took. */
    @Volatile
    var offered = 0L
        private set

    @Volatile
    var read = 0L
        private set

    @Volatile
    var lastMs = 0L
        private set

    @Volatile
    var frameSize = ""
        private set

    /** The last frame read, whole (RGBA, width, height): what a snapshot translates. */
    @Volatile
    var last: Triple<ByteArray, Int, Int>? = null
        private set

    /** The screen's preview, while it shows one: gets a small copy of each frame read (on the reading thread). */
    @Volatile
    var preview: ((Bitmap) -> Unit)? = null

    /**
     * A new frame (on the producer's thread). True when it was taken: it was copied before this
     * returns, so the producer can let the frame go either way.
     */
    fun offer(frame: Frame): Boolean {
        offered++
        val now = SystemClock.elapsedRealtime()
        if (now < nextAt) return false
        if (!busy.compareAndSet(false, true)) return false
        val snap = try {
            frame.snapshot()
        } catch (e: RuntimeException) {
            Log.w(TAG, "copy failed", e)
            busy.set(false)
            return false
        }
        worker.execute {
            try {
                val start = SystemClock.elapsedRealtime()
                frameSize = "${snap.width}x${snap.height}"
                val rgba = snap.rgba()
                last = Triple(rgba, snap.width, snap.height)
                preview?.let { show -> small(rgba, snap.width, snap.height)?.let(show) }
                val wait = core.processFrame(rgba, snap.width, snap.height)
                lastMs = SystemClock.elapsedRealtime() - start
                // (a hot phone reads less often: see heatFactor)
                nextAt = start + (wait.coerceIn(0, 2000) * heatFactor()).coerceAtMost(4000)
                read++
            } catch (e: Exception) {
                Log.w(TAG, "reading failed", e)
                nextAt = SystemClock.elapsedRealtime() + 1000
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    /**
     * How much longer to wait between readings while the phone is hot (Android 10+): the system's
     * thermal status, 1 when cool, 2 when warm, 4 when it's throttling hard. Reading slower lets
     * it cool down instead of the system slowing everything (the stream too).
     */
    private fun heatFactor(): Long {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return 1
        val status = power?.currentThermalStatus ?: return 1
        return when {
            status >= android.os.PowerManager.THERMAL_STATUS_SEVERE -> 4
            status >= android.os.PowerManager.THERMAL_STATUS_MODERATE -> 2
            else -> 1
        }
    }

    /** The picture stopped (the stream ended). */
    fun noPicture() {
        worker.execute {
            try {
                core.noPicture()
            } catch (e: Exception) {
                Log.w(TAG, "noPicture", e)
            }
        }
    }

    fun close() {
        noPicture()
        worker.shutdown()
    }

    companion object {
        private const val TAG = "Reader"

        /** The frame at most 960 pixels wide, for the preview. */
        fun small(rgba: ByteArray, w: Int, h: Int): Bitmap? = try {
            val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            if (w <= 960) full else Bitmap.createScaledBitmap(full, 960, 960 * h / w, true).also { full.recycle() }
        } catch (e: RuntimeException) {
            null
        }
    }
}
