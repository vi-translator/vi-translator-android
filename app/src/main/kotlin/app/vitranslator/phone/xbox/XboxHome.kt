package app.vitranslator.phone.xbox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/** An Xbox the account can stream from. */
data class XboxConsole(val serverId: String, val name: String, val type: String, val powerState: String) {
    val asleep get() = powerState.equals("ConnectedStandby", true) || powerState.equals("Off", true)

    companion object {
        /** The console chosen in Setup (kept with the sign-in; signing out forgets it). */
        fun saved(ctx: Context): XboxConsole? {
            val p = ctx.getSharedPreferences("xbox", Context.MODE_PRIVATE)
            val id = p.getString("console_id", null) ?: return null
            return XboxConsole(id, p.getString("console_name", "") ?: "", p.getString("console_type", "") ?: "", "")
        }

        fun save(ctx: Context, c: XboxConsole) {
            ctx.getSharedPreferences("xbox", Context.MODE_PRIVATE).edit()
                .putString("console_id", c.serverId).putString("console_name", c.name).putString("console_type", c.type).apply()
        }
    }
}

/**
 * Xbox Remote Play's web API ("xHome" on Microsoft's gssv servers), as xbox.com's player uses it:
 * the consoles, and a session's steps (start, wait for the console, the WebRTC offer and network
 * addresses, keep-alive, stop). Blocking calls, off the main thread.
 */
class XboxHome(private val login: Login, private val resolution: Int) {
    /** Remote Play's token and its regions' addresses (the default first). */
    data class Login(val gsToken: String, val baseUris: List<String>)

    /** The region whose console list wasn't empty (else the default). */
    var baseUri: String = login.baseUris.first()
        private set

    private var sessionPath: String? = null

    // Microsoft picks the stream's size by the client's system: "android" 720p, "windows" 1080p
    private val osName get() = if (resolution >= 1080) "windows" else "android"

    private val deviceInfo: String
        get() = JSONObject()
            .put("appInfo", JSONObject().put("env", JSONObject()
                .put("clientAppId", "www.xbox.com").put("clientAppType", "browser").put("clientAppVersion", "29.9.35")
                .put("clientSdkVersion", "10.6.8").put("httpEnvironment", "prod").put("sdkInstallId", "")))
            .put("dev", JSONObject()
                .put("hw", JSONObject().put("make", "Microsoft").put("model", "unknown").put("platformType", "desktop").put("sdktype", "web"))
                .put("os", JSONObject().put("name", osName).put("ver", "22631.2715").put("platform", "desktop"))
                .put("displayInfo", JSONObject()
                    .put("dimensions", JSONObject().put("widthInPixels", 1920).put("heightInPixels", 1080))
                    .put("pixelDensity", JSONObject().put("dpiX", 1).put("dpiY", 1)))
                .put("browser", JSONObject().put("browserName", "edge").put("browserVersion", "140.0.3485.66")))
            .toString()

    private fun headers(extra: Map<String, String> = emptyMap()) =
        mapOf("Authorization" to "Bearer ${login.gsToken}", "X-MS-Device-Info" to deviceInfo) + extra

    private fun get(path: String, base: String = baseUri) = XboxHttp.call("GET", "$base/$path", null, headers())

    private fun post(path: String, body: JSONObject) = XboxHttp.call("POST", "$baseUri/$path", body.toString(), headers())

    /** The consoles, from the first region that has any. */
    fun consoles(): List<XboxConsole> {
        for (base in login.baseUris) {
            val a = try {
                JSONObject(get("v6/servers/home?mr=50", base)).optJSONArray("results")
            } catch (e: HttpError) {
                if (base == login.baseUris.last()) throw e
                null
            } ?: continue
            if (a.length() == 0) continue
            baseUri = base
            return (0 until a.length()).map { a.getJSONObject(it) }.map {
                XboxConsole(it.getString("serverId"), it.optString("deviceName"), it.optString("consoleType"), it.optString("powerState"))
            }
        }
        return emptyList()
    }

    /** Start a session with the console (it then gets ready: [state]). */
    fun play(serverId: String) {
        val tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
        val body = JSONObject()
            .put("clientSessionId", "").put("titleId", "").put("systemUpdateGroup", "")
            .put("settings", JSONObject()
                .put("nanoVersion", "V3;WebrtcTransport.dll").put("enableTextToSpeech", false).put("highContrast", 0)
                .put("locale", "en-US").put("useIceConnection", false).put("timezoneOffsetMinutes", tz)
                .put("sdkType", "web").put("osName", osName))
            .put("serverId", serverId).put("fallbackRegionNames", JSONArray())
        val o = JSONObject(post("v5/sessions/home/play", body))
        sessionPath = o.getString("sessionPath").trimStart('/')
    }

    private val session get() = sessionPath ?: throw IllegalStateException("no session")

    /** The session's state ("Provisioning", "ReadyToConnect", "Provisioned", "Failed") and its error text. */
    fun state(): Pair<String, String?> {
        val o = JSONObject(get("$session/state"))
        val err = o.optJSONObject("errorDetails")?.let { e -> e.optString("message").takeIf { !e.isNull("message") && it.isNotEmpty() } }
        return o.optString("state") to err
    }

    /** The console asks for the user's transfer token (state ReadyToConnect). */
    fun connect(userToken: String) {
        post("$session/connect", JSONObject().put("userToken", userToken))
    }

    /** Seconds between keep-alives (from the session's configuration). */
    fun keepAliveSeconds(): Int = try {
        JSONObject(get("$session/configuration")).optInt("keepAlivePulseInSeconds", 20).coerceIn(5, 300)
    } catch (e: Exception) {
        20
    }

    /** Send the WebRTC offer; the console's answer SDP (waits up to [timeoutMs]). */
    fun exchangeSdp(offer: String, timeoutMs: Long = 30_000): String {
        val cfg = JSONObject()
            .put("chatConfiguration", JSONObject().put("bytesPerSample", 2).put("expectedClipDurationMs", 20)
                .put("format", JSONObject().put("codec", "opus").put("container", "webm")).put("numChannels", 1).put("sampleFrequencyHz", 24000))
            .put("chat", range(1, 1)).put("control", range(1, 3)).put("input", range(1, 8)).put("message", range(1, 1))
        post("$session/sdp", JSONObject().put("messageType", "offer").put("sdp", offer).put("configuration", cfg))
        val answer = JSONObject(poll("$session/sdp", timeoutMs))
        if (answer.optString("status").let { it.isNotEmpty() && it != "success" }) throw java.io.IOException("the console refused the stream: ${answer.optString("status")}")
        return answer.getString("sdp")
    }

    /** Send this phone's network candidates; the console's (each candidate, its mid and line index). */
    fun exchangeIce(candidates: List<Triple<String, String, Int>>, timeoutMs: Long = 30_000): List<Triple<String, String, Int>> {
        val a = JSONArray()
        for ((cand, mid, index) in candidates) {
            a.put(JSONObject().put("candidate", cand).put("messageType", "iceCandidate").put("sdpMLineIndex", index.toString()).put("sdpMid", mid))
        }
        post("$session/ice", JSONObject().put("messageType", "iceCandidate").put("candidate", a))
        val got = JSONArray(poll("$session/ice", timeoutMs))
        return (0 until got.length()).map { got.getJSONObject(it) }.map {
            Triple(it.optString("candidate"), it.optString("sdpMid", "0"), it.optString("sdpMLineIndex", "0").toIntOrNull() ?: 0)
        }
    }

    /** GET [path] each second until it carries an exchangeResponse (a JSON string), and return that. */
    private fun poll(path: String, timeoutMs: Long): String {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val body = get(path)
            if (body.isNotBlank()) {
                val o = JSONObject(body)
                val r = o.optString("exchangeResponse")
                if (r.isNotEmpty()) return r
                o.optJSONObject("errorDetails")?.let { e -> if (!e.isNull("message")) throw java.io.IOException(e.optString("message")) }
            }
            Thread.sleep(1000)
        }
        throw java.io.IOException("the console didn't answer")
    }

    fun keepAlive() {
        post("$session/keepalive", JSONObject())
    }

    /** End the session (quietly: the console may have ended it already). */
    fun stop() {
        val s = sessionPath ?: return
        sessionPath = null
        try {
            XboxHttp.call("DELETE", "$baseUri/$s", null, headers())
        } catch (e: Exception) {
            // (gone already)
        }
    }

    private fun range(min: Int, max: Int) = JSONObject().put("minVersion", min).put("maxVersion", max)

    companion object {
        /**
         * The console's network candidates, with the extra ones its Teredo (IPv6 over IPv4) address
         * stands for: its IPv4 addresses, at its port and at 3074, as xbox.com's player adds them.
         */
        fun withTeredo(c: List<Triple<String, String, Int>>): List<Triple<String, String, Int>> {
            val out = ArrayList<Triple<String, String, Int>>()
            for (t in c) {
                out += t
                val parts = t.first.removePrefix("a=").split(' ')
                if (parts.size < 6) continue
                val ip = parts[4]
                val port = parts[5].toIntOrNull() ?: 9002
                val h = teredo(ip) ?: continue
                val (server4, client4, udpPort) = h
                val adds = mutableListOf(client4 to udpPort, server4 to port, client4 to port)
                if (port != 3074) adds += listOf(server4 to 3074, client4 to 3074)
                var n = 100
                for ((a, p) in adds) out += Triple("a=candidate:${n++} 1 UDP 100 $a $p typ host", t.second, t.third)
            }
            return out
        }

        /** A Teredo address (2001:0::/32): its server's IPv4, the client's IPv4, and the client's port. */
        fun teredo(ip: String): Triple<String, String, Int>? {
            if (!ip.contains(':')) return null
            val hx = expand(ip) ?: return null
            if (hx[0] != 0x2001 || hx[1] != 0) return null
            val server = "${hx[2] shr 8}.${hx[2] and 255}.${hx[3] shr 8}.${hx[3] and 255}"
            val c6 = hx[6] xor 0xffff
            val c7 = hx[7] xor 0xffff
            val client = "${c6 shr 8}.${c6 and 255}.${c7 shr 8}.${c7 and 255}"
            return Triple(server, client, hx[5] xor 0xffff)
        }

        private fun expand(ip: String): IntArray? {
            val s = ip.substringBefore('%').trim('[', ']')
            val halves = s.split("::")
            if (halves.size > 2) return null
            val head = halves[0].split(':').filter { it.isNotEmpty() }
            val tail = if (halves.size == 2) halves[1].split(':').filter { it.isNotEmpty() } else emptyList()
            val zeros = 8 - head.size - tail.size
            if (zeros < 0 || (halves.size == 1 && zeros != 0)) return null
            return try {
                (head + List(zeros) { "0" } + tail).map { it.toInt(16) }.toIntArray()
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
