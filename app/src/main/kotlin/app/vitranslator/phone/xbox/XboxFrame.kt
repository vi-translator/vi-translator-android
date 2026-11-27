package app.vitranslator.phone.xbox

import android.graphics.Rect
import app.vitranslator.phone.read.Frame
import app.vitranslator.phone.read.Snapshot
import app.vitranslator.phone.read.YuvSnapshot
import org.webrtc.VideoFrame

/**
 * A frame of the Xbox stream, as WebRTC decoded it (a GPU texture with the phone's decoder). The
 * planes are made (I420) and copied only when the reader takes the frame; the colours are
 * converted later, on the reading thread, as the PS5's are (YuvSnapshot).
 */
class XboxFrame(private val frame: VideoFrame) : Frame {
    override fun snapshot(): Snapshot {
        val i420 = frame.buffer.toI420() ?: throw IllegalStateException("no I420 picture")
        try {
            val w = i420.width
            val h = i420.height
            return YuvSnapshot(
                Rect(0, 0, w, h),
                bytes(i420.dataY, i420.strideY * h), i420.strideY, 1,
                bytes(i420.dataU, i420.strideU * ((h + 1) / 2)), bytes(i420.dataV, i420.strideV * ((h + 1) / 2)), i420.strideU, 1,
            )
        } finally {
            i420.release()
        }
    }

    private fun bytes(b: java.nio.ByteBuffer, size: Int): ByteArray {
        val d = b.duplicate()
        d.rewind()
        return ByteArray(minOf(size, d.remaining())).also { d.get(it) }
    }
}
