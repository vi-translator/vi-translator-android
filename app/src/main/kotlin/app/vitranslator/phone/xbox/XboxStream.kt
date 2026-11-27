package app.vitranslator.phone.xbox

import android.content.Context
import android.util.Log
import app.vitranslator.phone.read.Reader
import app.vitranslator.phone.ui.T
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Xbox Remote Play, for reading only, without Microsoft's web player: the app asks Xbox's servers
 * for a session with the console chosen in Setup, then receives its picture over WebRTC (the
 * phone's video decoder; no screen, so it keeps going with the screen off, as the PS5's stream
 * does). No sound, and no controller: the player's own controller stays on the console.
 *
 * The steps are those of xbox.com's player (see the open-source XStreaming and Greenlight): start
 * the session, wait for the console, swap the WebRTC offer and answer and the network candidates
 * through the servers, then the data channels' greetings (message, control, input) and a
 * keep-alive.
 */
class XboxStream(
    private val ctx: Context,
    private val console: XboxConsole,
    private val resolution: Int,
    private val reader: Reader,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(state: State, detail: String?)
    }

    enum class State { CONNECTING, STREAMING, ENDED, FAILED }

    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "xbox-stream").apply { isDaemon = true } }

    @Volatile
    private var stopped = false

    @Volatile
    private var streaming = false
    private var home: XboxHome? = null
    private var egl: EglBase? = null
    private var adm: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var video: VideoTrack? = null
    private val channels = HashMap<String, DataChannel>()
    private var cv = 0
    private var inputSeq = 0
    private val started = System.nanoTime()
    private val gathered = CountDownLatch(1)
    private val localCandidates = ArrayList<IceCandidate>()

    /** frames the decoder gave (all of them, read or not) */
    @Volatile
    var frames = 0L
        private set
    private var framesAtCheck = 0L

    fun start() {
        listener.onState(State.CONNECTING, null)
        worker.execute {
            try {
                connect()
            } catch (e: Exception) {
                Log.w(TAG, "start", e)
                fail(describe(e))
            }
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        worker.execute { close() }
        worker.shutdown()
    }

    // ---- the session

    private fun connect() {
        val login = XboxAuth.streaming(ctx)
        val h = XboxHome(login, resolution)
        home = h
        // the console's state now (and the region that has it): asleep, it's woken first
        val now = h.consoles().firstOrNull { it.serverId == console.serverId }
            ?: throw IOException(T("This Xbox isn't on your Microsoft account's list any more. Choose it again in Setup."))
        if (now.asleep) wake(h)
        if (stopped) return

        h.play(console.serverId)
        waitReady(h)
        if (stopped) return
        val keepAlive = h.keepAliveSeconds()

        val offer = makePeer()
        val answer = h.exchangeSdp(offer)
        awaitSdp { pc!!.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, answer)) }
        // (this phone's candidates: all of them, gathered within 5 s)
        gathered.await(5, TimeUnit.SECONDS)
        val mine = synchronized(localCandidates) { localCandidates.toList() }
            .filterNot { it.sdp.contains("UDP", true) && it.sdp.contains("tcptype") }
            .map { Triple(it.sdp.removePrefix("a="), it.sdpMid ?: "0", it.sdpMLineIndex) }
        val theirs = XboxHome.withTeredo(h.exchangeIce(mine))
        for ((cand, mid, index) in theirs) {
            if (cand.isBlank() || cand.contains("end-of-candidates")) continue
            if (cand.contains("UDP", true) && cand.contains("tcptype")) continue
            pc?.addIceCandidate(IceCandidate(mid, index, cand.removePrefix("a=")))
        }

        worker.scheduleWithFixedDelay({
            try {
                if (!stopped) h.keepAlive()
            } catch (e: Exception) {
                Log.w(TAG, "keepalive", e)
            }
        }, keepAlive.toLong(), keepAlive.toLong(), TimeUnit.SECONDS)
        // no picture within 30 s of connecting: tell the user rather than wait for ever
        worker.schedule({ if (!stopped && frames == 0L) fail(T("The Xbox sent no picture. Check that Remote Play works on this network, then try again.")) }, 30, TimeUnit.SECONDS)
    }

    private fun wake(h: XboxHome) {
        try {
            XboxAuth.wake(ctx, console.serverId)
        } catch (e: Exception) {
            Log.w(TAG, "wake", e)
        }
        // (up to 45 s for it to come on)
        val until = System.currentTimeMillis() + 45_000
        while (!stopped && System.currentTimeMillis() < until) {
            Thread.sleep(3000)
            val c = try {
                h.consoles().firstOrNull { it.serverId == console.serverId }
            } catch (e: IOException) {
                null
            }
            if (c != null && !c.asleep) return
        }
    }

    private fun waitReady(h: XboxHome) {
        var connected = false
        val until = System.currentTimeMillis() + 90_000
        while (!stopped && System.currentTimeMillis() < until) {
            val (state, error) = h.state()
            when (state) {
                "Provisioned" -> return
                "ReadyToConnect" -> if (!connected) {
                    h.connect(XboxAuth.transferToken(ctx))
                    connected = true
                }
                "Failed" -> throw IOException(failure(error))
            }
            Thread.sleep(1000)
        }
        if (!stopped) throw IOException(T("The Xbox didn't get ready to stream. Check that it's on or asleep (not off), then try again."))
    }

    /** What the console's error means for the user. */
    private fun failure(error: String?): String = when {
        error == null -> T("The Xbox refused the stream.")
        error.contains("disabled streaming", true) -> T("Remote features are off on the Xbox: Settings › Devices & connections › Remote features.")
        error.contains("WaitingForServerToRegister", true) -> T("The Xbox isn't connected to Xbox services. Restart the Xbox (hold its power button for 10 s), then try again.")
        error.contains("xboxstreaminghelper", true) -> T("The Xbox's streaming stopped working. Restart the Xbox (hold its power button for 10 s), then try again.")
        else -> T("The Xbox refused the stream.") + " ($error)"
    }

    private fun describe(e: Exception): String = when (e) {
        is XboxAuth.SignedOut -> T("Signed out of Microsoft. Sign in again in Setup.")
        is HttpError -> if (e.code == 401 || e.code == 403) T("Microsoft refused the sign-in for Remote Play. Sign in again in Setup.") else e.message ?: "HTTP ${e.code}"
        else -> e.message ?: e.toString()
    }

    // ---- WebRTC

    /** The peer, its channels and its offer (set as the local description). */
    private fun makePeer(): String {
        initOnce(ctx)
        val e = EglBase.create()
        egl = e
        // (no sound: the speaker and the microphone stay muted; the console's sound isn't wanted)
        val a = JavaAudioDeviceModule.builder(ctx).setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false).createAudioDeviceModule()
        a.setSpeakerMute(true)
        a.setMicrophoneMute(true)
        adm = a
        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(a)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, false, false))
            .createPeerConnectionFactory()
        factory = f
        val cfg = PeerConnection.RTCConfiguration(STUN.map { PeerConnection.IceServer.builder(it).createIceServer() }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val p = f.createPeerConnection(cfg, observer) ?: throw IOException("WebRTC: no peer connection")
        pc = p

        val videoT = p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
        // H.264 only, High profile first (as xbox.com's player orders them)
        try {
            val h264 = f.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
                .filter { it.name.equals("H264", true) }
                .sortedBy { c -> h264Rank(c.parameters?.get("profile-level-id")) }
            if (h264.isNotEmpty()) videoT.setCodecPreferences(h264)
        } catch (x: Exception) {
            Log.w(TAG, "codec preferences", x)
        }

        channel(p, "input", "1.0", true)
        channel(p, "chat", "chatV1", false)
        channel(p, "control", "controlV1", false)
        channel(p, "message", "messageV1", false)

        var offer: SessionDescription? = null
        val done = CountDownLatch(1)
        var error: String? = null
        p.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(d: SessionDescription) {
                offer = d
                done.countDown()
            }

            override fun onCreateFailure(s: String?) {
                error = s
                done.countDown()
            }
        }, MediaConstraints())
        done.await(10, TimeUnit.SECONDS)
        val o = offer ?: throw IOException("WebRTC offer: ${error ?: "no answer"}")
        // (the console's sound in stereo, as the web player asks)
        val sdp = o.description.replace("useinbandfec=1", "useinbandfec=1;stereo=1")
        awaitSdp { p.setLocalDescription(it, SessionDescription(SessionDescription.Type.OFFER, sdp)) }
        return sdp
    }

    private fun h264Rank(profile: String?): Int = when {
        profile == null -> 3
        profile.startsWith("4d", true) -> 0
        profile.startsWith("42e", true) -> 1
        profile.startsWith("420", true) -> 2
        else -> 3
    }

    private fun channel(p: PeerConnection, label: String, protocol: String, ordered: Boolean) {
        val init = DataChannel.Init().apply {
            this.ordered = ordered
            this.protocol = protocol
        }
        val dc = p.createDataChannel(label, init) ?: return
        channels[label] = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}

            override fun onStateChange() {
                if (dc.state() == DataChannel.State.OPEN) worker.execute { opened(label) }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (label != "message" || buffer.binary) return
                val b = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                val text = String(b)
                worker.execute { message(text) }
            }
        })
    }

    private fun opened(label: String) {
        if (stopped) return
        when (label) {
            "message" -> sendText("message", JSONObject().put("type", "Handshake").put("version", "messageV1").put("id", uuid()).put("cv", nextCv()))
            // (who this client is: no touch screen)
            "input" -> sendInput(clientMetadata())
        }
    }

    /** The console's messages: after its HandshakeAck, the greetings that start the picture. */
    private fun message(text: String) {
        if (stopped) return
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        if (o.optString("type") != "HandshakeAck") return
        // control: allowed in, and a controller slot (as the web player does; the real controller stays on the console)
        sendText("control", JSONObject().put("message", "authorizationRequest").put("accessKey", "4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E"))
        sendText("control", JSONObject().put("message", "gamepadChanged").put("gamepadIndex", 0).put("wasAdded", false))
        worker.schedule({ sendText("control", JSONObject().put("message", "gamepadChanged").put("gamepadIndex", 0).put("wasAdded", true)) }, 500, TimeUnit.MILLISECONDS)
        val first = listOf(
            "/streaming/systemUi/configuration" to JSONObject().put("version", org.json.JSONArray(listOf(0, 2, 0))).put("systemUis", org.json.JSONArray(listOf(10, 19, 31, 27, 32, -44, 40, 41, -43))),
            "/streaming/properties/clientappinstallidchanged" to JSONObject().put("clientAppInstallId", uuid()),
            "/streaming/characteristics/orientationchanged" to JSONObject().put("orientation", 0),
            "/streaming/characteristics/touchinputenabledchanged" to JSONObject().put("touchInputEnabled", false),
            "/streaming/characteristics/clientdevicecapabilities" to JSONObject(),
            "/streaming/characteristics/dimensionschanged" to JSONObject()
                .put("horizontal", 1920).put("vertical", 1080).put("preferredWidth", 1920).put("preferredHeight", 1080)
                .put("safeAreaLeft", 0).put("safeAreaTop", 0).put("safeAreaRight", 1920).put("safeAreaBottom", 1080)
                .put("supportsCustomResolution", true),
        )
        for ((target, content) in first) {
            sendText("message", JSONObject().put("type", "Message").put("content", content.toString()).put("id", uuid()).put("target", target).put("cv", nextCv()))
        }
        keyframe()
        // every 10 s: the input channel's sign of life, and a whole picture again if none came
        worker.scheduleWithFixedDelay({
            if (stopped) return@scheduleWithFixedDelay
            sendInput(metadata())
            if (frames == framesAtCheck) keyframe()
            framesAtCheck = frames
        }, 2, 10, TimeUnit.SECONDS)
    }

    private fun keyframe() {
        sendText("control", JSONObject().put("message", "videoKeyframeRequested").put("ifrRequested", true))
    }

    private fun sendText(label: String, o: JSONObject) {
        val dc = channels[label] ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(o.toString().toByteArray()), false))
    }

    private fun sendInput(b: ByteArray) {
        val dc = channels["input"] ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(b), true))
    }

    private fun header(type: Int, extra: Int): ByteBuffer = ByteBuffer.allocate(14 + extra).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(type.toShort())
        putInt(inputSeq++)
        putDouble((System.nanoTime() - started) / 1e6)
    }

    private fun clientMetadata(): ByteArray = header(8, 1).put(0.toByte()).array()

    /** One frame's timings (made up: this client draws nothing), which the console takes as a sign of life. */
    private fun metadata(): ByteArray {
        val now = ((System.nanoTime() - started) / 1_000_000).toInt()
        return header(1, 1 + 28).apply {
            put(1.toByte())
            putInt(System.currentTimeMillis().toInt())
            repeat(5) { putInt(now) }
            putInt(System.currentTimeMillis().toInt())
        }.array()
    }

    private fun nextCv(): String = (++cv).toString()

    private fun uuid() = UUID.randomUUID().toString()

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}

        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.countDown()
        }

        override fun onIceCandidate(c: IceCandidate) {
            synchronized(localCandidates) { localCandidates += c }
        }

        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(dc: DataChannel?) {}
        override fun onRenegotiationNeeded() {}

        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            when (s) {
                PeerConnection.PeerConnectionState.FAILED ->
                    fail(T("Couldn't reach the Xbox over the network (Remote Play needs the router to allow it: UPnP, or the Xbox's ports open)."))
                PeerConnection.PeerConnectionState.CLOSED -> if (!stopped) end(T("The Xbox ended the stream."))
                else -> Unit
            }
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>?) {
            when (val t = receiver.track()) {
                is VideoTrack -> {
                    video = t
                    t.addSink { frame ->
                        frames++
                        if (!streaming) {
                            streaming = true
                            listener.onState(State.STREAMING, null)
                        }
                        try {
                            reader.offer(XboxFrame(frame))
                        } catch (e: RuntimeException) {
                            Log.w(TAG, "frame", e)
                        }
                    }
                }
                // (the console's sound isn't played)
                else -> t?.setEnabled(false)
            }
        }
    }

    private fun awaitSdp(call: (SdpObserver) -> Unit) {
        val done = CountDownLatch(1)
        var error: String? = null
        call(object : SdpAdapter() {
            override fun onSetSuccess() = done.countDown()
            override fun onSetFailure(s: String?) {
                error = s ?: "failed"
                done.countDown()
            }
        })
        if (!done.await(10, TimeUnit.SECONDS)) throw IOException("WebRTC: no answer")
        error?.let { throw IOException("WebRTC: $it") }
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(s: String?) {}
        override fun onSetFailure(s: String?) {}
    }

    // ---- the end

    private fun fail(detail: String) {
        if (stopped) return
        listener.onState(State.FAILED, detail)
        stop()
    }

    private fun end(detail: String?) {
        if (stopped) return
        listener.onState(State.ENDED, detail)
        stop()
    }

    private fun close() {
        try {
            home?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop session", e)
        }
        try {
            channels.values.forEach { it.unregisterObserver(); it.close(); it.dispose() }
            channels.clear()
            pc?.close()
            pc?.dispose()
            pc = null
            factory?.dispose()
            factory = null
            adm?.release()
            adm = null
            egl?.release()
            egl = null
        } catch (e: Exception) {
            Log.w(TAG, "close", e)
        }
        // (the reader is told there's no picture when the service closes it)
    }

    companion object {
        private const val TAG = "XboxStream"

        private val STUN = listOf("stun:worldaz.relay.teams.microsoft.com:3478", "stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")

        @Volatile
        private var initialized = false

        @Synchronized
        private fun initOnce(ctx: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
            initialized = true
        }
    }
}
