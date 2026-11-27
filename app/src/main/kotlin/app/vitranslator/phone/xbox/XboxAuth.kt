package app.vitranslator.phone.xbox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** An HTTP answer that wasn't 2xx: its status and body. */
class HttpError(val code: Int, val body: String, url: String) : IOException("HTTP $code from ${URL(url).host}: ${body.take(300)}")

/** Blocking HTTP for the Xbox services (JSON or a form in, the body as text out). */
internal object XboxHttp {
    fun call(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap(), form: Boolean = false): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("Accept", "application/json")
            if (body != null) c.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, text, url)
            return text
        } finally {
            c.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun formOf(vararg p: Pair<String, String>) = p.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
}

/**
 * Signing in to Xbox without a password page in the app: Microsoft's device code (the user types a
 * short code at microsoft.com/link on any device), as xbox.com's own client and the open-source
 * Xbox players (XStreaming, Greenlight) do. The app keeps only Microsoft's refresh token (in its
 * private settings); from it come the Xbox tokens for Remote Play when a stream starts.
 */
object XboxAuth {
    // xbox.com's web client
    private const val CLIENT_ID = "1f907974-e22b-4810-a9de-d9647380c97e"
    private const val SCOPE = "xboxlive.signin openid profile offline_access"
    private const val AAD = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private const val PREFS = "xbox"

    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val intervalS: Int, val expiresAt: Long)

    /** A new code for the user to type (blocking). */
    fun requestCode(): DeviceCode {
        val o = JSONObject(XboxHttp.call("POST", "$AAD/devicecode", XboxHttp.formOf("client_id" to CLIENT_ID, "scope" to SCOPE), form = true))
        return DeviceCode(
            o.getString("device_code"), o.getString("user_code"),
            o.optString("verification_uri", "https://www.microsoft.com/link"),
            o.optInt("interval", 5).coerceIn(1, 30),
            System.currentTimeMillis() + o.optLong("expires_in", 900) * 1000,
        )
    }

    /**
     * Waits for the user to enter [dc] (blocking, polling as Microsoft asks): true once signed in,
     * false if the code expired, was declined, or [cancelled] said stop.
     */
    fun waitForSignIn(ctx: Context, dc: DeviceCode, cancelled: () -> Boolean): Boolean {
        var interval = dc.intervalS
        while (!cancelled() && System.currentTimeMillis() < dc.expiresAt) {
            Thread.sleep(interval * 1000L)
            if (cancelled()) return false
            try {
                val o = JSONObject(XboxHttp.call("POST", "$AAD/token", XboxHttp.formOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to CLIENT_ID, "device_code" to dc.deviceCode,
                ), form = true))
                save(ctx, o)
                return true
            } catch (e: HttpError) {
                val err = try { JSONObject(e.body).optString("error") } catch (x: Exception) { "" }
                when (err) {
                    "authorization_pending" -> Unit
                    "slow_down" -> interval += 5
                    else -> return false
                }
            } catch (e: IOException) {
                // (no network for a moment: keep waiting)
            }
        }
        return false
    }

    fun signedIn(ctx: Context): Boolean = prefs(ctx).getString("refresh", null) != null

    /** Forget the sign-in (and the console chosen with it). */
    fun signOut(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun save(ctx: Context, o: JSONObject) {
        prefs(ctx).edit()
            .putString("access", o.getString("access_token"))
            .putString("refresh", o.optString("refresh_token").ifEmpty { prefs(ctx).getString("refresh", null) })
            .putLong("expires", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000)
            .apply()
    }

    /** Signed out by Microsoft (the refresh token no longer works): sign in again. */
    class SignedOut : IOException("signed out of Microsoft")

    /** A current Microsoft access token, refreshed when it has under a minute left (blocking). */
    @Synchronized
    fun accessToken(ctx: Context): String {
        val p = prefs(ctx)
        val refresh = p.getString("refresh", null) ?: throw SignedOut()
        val access = p.getString("access", null)
        if (access != null && p.getLong("expires", 0) - System.currentTimeMillis() > 60_000) return access
        val o = try {
            JSONObject(XboxHttp.call("POST", "$AAD/token", XboxHttp.formOf(
                "client_id" to CLIENT_ID, "grant_type" to "refresh_token", "refresh_token" to refresh, "scope" to SCOPE,
            ), form = true, headers = mapOf("Cache-Control" to "no-store, must-revalidate, no-cache")))
        } catch (e: HttpError) {
            if (e.code == 400 || e.code == 401) {
                signOut(ctx)
                throw SignedOut()
            }
            throw e
        }
        save(ctx, o)
        return o.getString("access_token")
    }

    /**
     * The token the console's /connect step asks for (Microsoft's "transfer token", from
     * login.live.com with the same refresh token). Blocking.
     */
    fun transferToken(ctx: Context): String {
        accessToken(ctx)
        val refresh = prefs(ctx).getString("refresh", null) ?: throw SignedOut()
        val o = JSONObject(XboxHttp.call("POST", "https://login.live.com/oauth20_token.srf", XboxHttp.formOf(
            "client_id" to CLIENT_ID,
            "scope" to "service::http://Passport.NET/purpose::PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN",
            "grant_type" to "refresh_token", "refresh_token" to refresh,
        ), form = true))
        return o.getString("access_token")
    }

    private val xblHeaders = mapOf(
        "x-xbl-contract-version" to "1", "Cache-Control" to "no-cache",
        "Origin" to "https://www.xbox.com", "Referer" to "https://www.xbox.com/",
    )

    /** An Xbox Live token for [relyingParty] and the user hash (uhs) that goes with it. Blocking. */
    fun xsts(ctx: Context, relyingParty: String): Pair<String, String> {
        val user = JSONObject(XboxHttp.call("POST", "https://user.auth.xboxlive.com/user/authenticate", JSONObject()
            .put("Properties", JSONObject().put("AuthMethod", "RPS").put("RpsTicket", "d=" + accessToken(ctx)).put("SiteName", "user.auth.xboxlive.com"))
            .put("RelyingParty", "http://auth.xboxlive.com").put("TokenType", "JWT").toString(), xblHeaders)).getString("Token")
        val x = JSONObject(XboxHttp.call("POST", "https://xsts.auth.xboxlive.com/xsts/authorize", JSONObject()
            .put("Properties", JSONObject().put("SandboxId", "RETAIL").put("UserTokens", JSONArray().put(user)))
            .put("RelyingParty", relyingParty).put("TokenType", "JWT").toString(), xblHeaders))
        val uhs = x.optJSONObject("DisplayClaims")?.optJSONArray("xui")?.optJSONObject(0)?.optString("uhs") ?: ""
        return x.getString("Token") to uhs
    }

    /** Remote Play's own token and the regions it offers (the default first). Blocking. */
    fun streaming(ctx: Context): XboxHome.Login {
        val (gssv, _) = xsts(ctx, "http://gssv.xboxlive.com/")
        val o = JSONObject(XboxHttp.call("POST", "https://xhome.gssv-play-prod.xboxlive.com/v2/login/user",
            JSONObject().put("token", gssv).put("offeringId", "xhome").toString(),
            mapOf("Cache-Control" to "no-store, must-revalidate, no-cache", "x-gssv-client" to "XboxComBrowser")))
        val regions = o.getJSONObject("offeringSettings").getJSONArray("regions")
        val uris = (0 until regions.length()).map { regions.getJSONObject(it) }
            .sortedByDescending { it.optBoolean("isDefault") }
            .map { it.getString("baseUri").trimEnd('/') }
        return XboxHome.Login(o.getString("gsToken"), uris)
    }

    /** Wake the console from sleep (Xbox's own remote power-on). Blocking. */
    fun wake(ctx: Context, serverId: String) {
        val (token, uhs) = xsts(ctx, "http://xboxlive.com")
        XboxHttp.call("POST", "https://xccs.xboxlive.com/commands", JSONObject()
            .put("destination", "Xbox").put("type", "Power").put("command", "WakeUp")
            .put("sessionId", java.util.UUID.randomUUID().toString()).put("sourceId", "com.microsoft.smartglass")
            .put("parameters", JSONArray()).put("linkedXboxId", serverId).toString(),
            mapOf(
                "Authorization" to "XBL3.0 x=$uhs;$token", "skillplatform" to "RemoteManagement",
                "x-xbl-contract-version" to "4", "x-xbl-client-name" to "XboxApp", "x-xbl-client-type" to "UWA",
                "x-xbl-client-version" to "39.39.22001.0", "Accept-Language" to "en-US",
            ))
    }
}
