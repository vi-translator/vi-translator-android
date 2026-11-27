package app.vitranslator.phone.ps

import android.util.Log
import app.vitranslator.phone.ps.chiaki.CantDisplayEvent
import app.vitranslator.phone.ps.chiaki.Codec
import app.vitranslator.phone.ps.chiaki.ConnectInfo
import app.vitranslator.phone.ps.chiaki.ConnectVideoProfile
import app.vitranslator.phone.ps.chiaki.ConnectedEvent
import app.vitranslator.phone.ps.chiaki.CreateError
import app.vitranslator.phone.ps.chiaki.LoginPinRequestEvent
import app.vitranslator.phone.ps.chiaki.QuitEvent
import app.vitranslator.phone.ps.chiaki.RumbleEvent
import app.vitranslator.phone.ps.chiaki.Session
import app.vitranslator.phone.ps.chiaki.VideoFPSPreset
import app.vitranslator.phone.ps.chiaki.VideoResolutionPreset
import app.vitranslator.phone.read.Reader

/**
 * PS Remote Play from the PS5, for reading only: chiaki-ng's session decodes the video with
 * MediaCodec straight into a GPU texture (GlFrames: no screen), no sound. Every frame is taken at
 * once (so the decoder never waits); about 4 a second are copied and read.
 */
class PsStream(
    private val console: PsConsole,
    private val reader: Reader,
    private val listener: Listener,
    private val logFile: String?,
) {
    interface Listener {
        fun onState(state: State, detail: String?)

        /** The PS5 asks for the user's login passcode (the PSN account has one). */
        fun onLoginPin(incorrect: Boolean)

        /** The PS5 can't stream what it shows (true), or can again (false); the stream stays up. On chiaki's thread. */
        fun onCantDisplay(cantDisplay: Boolean) {}
    }

    enum class State { CONNECTING, STREAMING, ENDED, FAILED }

    private var gl: GlFrames? = null
    private var session: Session? = null

    /** frames the decoder gave (all of them, read or not) */
    @Volatile
    var frames = 0L
        private set

    fun start(resolution: VideoResolutionPreset = VideoResolutionPreset.RES_1080P) {
        val profile = ConnectVideoProfile.preset(resolution, VideoFPSPreset.FPS_30, Codec.CODEC_H264)
        // (through the GPU: the decoder's own buffers can't be read by the CPU on every phone, see GlFrames)
        val glFrames = GlFrames(profile.width, profile.height) { frame ->
            this.frames++
            try {
                reader.offer(frame)
            } catch (e: RuntimeException) {
                Log.w(TAG, "frame", e)
            }
        }
        val surface = if (glFrames.start()) glFrames.surface else null
        if (surface == null) {
            glFrames.stop()
            listener.onState(State.FAILED, "video: the phone's GPU could not be used")
            return
        }
        gl = glFrames

        val s = try {
            Session(ConnectInfo(true, console.host, console.registKey, console.rpKey, profile), logFile, false)
        } catch (e: CreateError) {
            listener.onState(State.FAILED, e.errorCode.toString())
            return
        }
        s.eventCallback = { ev ->
            when (ev) {
                ConnectedEvent -> listener.onState(State.STREAMING, null)
                is LoginPinRequestEvent -> listener.onLoginPin(ev.pinIncorrect)
                is QuitEvent -> listener.onState(if (ev.reason.isError) State.FAILED else State.ENDED, ev.reasonString ?: ev.reason.toString())
                is RumbleEvent -> Unit
                is CantDisplayEvent -> listener.onCantDisplay(ev.cantDisplay)
            }
        }
        s.setSurface(surface)
        session = s
        listener.onState(State.CONNECTING, null)
        val err = s.start()
        if (!err.isSuccess) listener.onState(State.FAILED, err.toString())
    }

    fun setLoginPin(pin: String) {
        session?.setLoginPin(pin)
    }

    fun stop() {
        val s = session
        session = null
        if (s != null) {
            s.stop()
            s.dispose()
        }
        gl?.stop()
        gl = null
    }

    companion object {
        private const val TAG = "PsStream"
    }
}
