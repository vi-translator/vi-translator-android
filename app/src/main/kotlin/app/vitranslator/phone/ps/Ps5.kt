package app.vitranslator.phone.ps

import android.content.Context
import java.util.Base64
import android.util.Log
import app.vitranslator.phone.ps.chiaki.ChiakiLog
import app.vitranslator.phone.ps.chiaki.CreateError
import app.vitranslator.phone.ps.chiaki.DiscoveryHost
import app.vitranslator.phone.ps.chiaki.DiscoveryService
import app.vitranslator.phone.ps.chiaki.DiscoveryServiceOptions
import app.vitranslator.phone.ps.chiaki.Regist
import app.vitranslator.phone.ps.chiaki.RegistEventCanceled
import app.vitranslator.phone.ps.chiaki.RegistEventFailed
import app.vitranslator.phone.ps.chiaki.RegistEventSuccess
import app.vitranslator.phone.ps.chiaki.RegistInfo
import app.vitranslator.phone.ps.chiaki.Target
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/** The PS5 this phone is registered with (Remote Play's keys for it), kept across runs. */
data class PsConsole(
    val host: String,
    val nickname: String,
    val mac: ByteArray,
    val registKey: ByteArray,
    val rpKey: ByteArray,
) {
    fun toJson(): String = JSONObject()
        .put("host", host).put("nickname", nickname)
        .put("mac", b64(mac)).put("regist_key", b64(registKey)).put("rp_key", b64(rpKey)).toString()

    /** The number the PS5 wakes up for (its registration key, written in hex). */
    val credential: ULong?
        get() {
            val end = registKey.indexOfFirst { it == 0.toByte() }.let { if (it >= 0) it else registKey.size }
            return try {
                registKey.copyOfRange(0, end).toString(StandardCharsets.UTF_8).toULong(16)
            } catch (e: NumberFormatException) {
                null
            }
        }

    companion object {
        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
        private fun unb64(s: String) = Base64.getDecoder().decode(s)

        fun fromJson(s: String): PsConsole? = try {
            val o = JSONObject(s)
            PsConsole(o.getString("host"), o.optString("nickname"), unb64(o.getString("mac")), unb64(o.getString("regist_key")), unb64(o.getString("rp_key")))
        } catch (e: Exception) {
            null
        }
    }
}

object Ps5 {
    private const val TAG = "Ps5"
    private const val DISCOVERY_PORT = 987

    fun saved(ctx: Context): PsConsole? =
        ctx.getSharedPreferences("ps5", Context.MODE_PRIVATE).getString("console", null)?.let { PsConsole.fromJson(it) }

    fun save(ctx: Context, c: PsConsole?) {
        ctx.getSharedPreferences("ps5", Context.MODE_PRIVATE).edit().apply {
            if (c == null) remove("console") else putString("console", c.toJson())
        }.apply()
    }

    /** The PSN account ID from the sign-in (base64 of 8 bytes), kept for registering again. */
    fun accountId(ctx: Context): String = ctx.getSharedPreferences("ps5", Context.MODE_PRIVATE).getString("account_id", "") ?: ""

    fun saveAccountId(ctx: Context, id: String) {
        ctx.getSharedPreferences("ps5", Context.MODE_PRIVATE).edit().putString("account_id", id).apply()
    }

    /** Looks for PlayStations on the home network while open; [found] runs on chiaki's thread. */
    fun discover(found: (List<DiscoveryHost>) -> Unit): DiscoveryService? = try {
        DiscoveryService(DiscoveryServiceOptions(4U, 3U, 500U, InetSocketAddress("255.255.255.255", DISCOVERY_PORT))) { hosts ->
            found(hosts.filter { it.isPS5 })
        }
    } catch (e: CreateError) {
        Log.e(TAG, "discovery failed: $e")
        null
    }

    /** Wake a PS5 in rest mode. */
    fun wakeup(c: PsConsole) {
        val cred = c.credential ?: return
        DiscoveryService.wakeup(null, c.host, cred, true)
    }

    /** How linking ended. */
    enum class LinkResult {
        LINKED,
        /** the PS5 answered but refused the PIN (wrong, or an old one: it changes each time Link Device opens) */
        WRONG_PIN,
        /** the PS5 isn't on its Link Device screen (closed, or timed out) */
        SCREEN_CLOSED,
        /** no PS5 answered at that address (off, another network, a wrong address) */
        NOT_FOUND,
        /** no PSN account ID, or the PS5 refused it */
        ACCOUNT,
        /** stopped by the app */
        CANCELED,
        /** something else (the log has it) */
        FAILED,
    }

    /** An 8-digit PIN, as the PS5 shows it ("1234 5678"): its digits, or null. */
    fun pinOf(typed: String): Int? {
        val digits = typed.filter { !it.isWhitespace() && it != '-' }
        if (digits.length != 8 || !digits.all { it in '0'..'9' }) return null
        return digits.toInt()
    }

    /** Why linking failed, from chiaki's log of it (its only event for a failure is "failed"). */
    fun failureOf(log: List<String>): LinkResult {
        val all = log.joinToString("\n")
        fun has(vararg s: String) = s.any { all.contains(it, ignoreCase = true) }
        return when {
            // RP-Application-Reason 0x80108b09: "Regist failed, probably invalid PIN"
            has("0x80108b09", "invalid PIN") -> LinkResult.WRONG_PIN
            // 0x80108b02: "Invalid PSN ID"
            has("0x80108b02", "Invalid PSN ID") -> LinkResult.ACCOUNT
            has("refused the registration search") -> LinkResult.SCREEN_CLOSED
            has("getaddrinfo", "timed out waiting for search response", "failed to connect", "connect failed", "Regist search failed", "failed to send search") -> LinkResult.NOT_FOUND
            // (any other HTTP refusal once the PS5 answered: most often the PIN)
            has("Regist received HTTP code") -> LinkResult.WRONG_PIN
            else -> LinkResult.FAILED
        }
    }

    /**
     * Register with the PS5 at [host], with the PSN account ID (base64) and the 8-digit PIN the PS5
     * shows (Settings, System, Remote Play, Link Device). [done] runs once, on chiaki's thread (or
     * at once, when linking can't start), with the console (LINKED) or why it failed; the caller
     * goes to the UI thread itself. Stop the Regist with stop() then dispose() (not on the UI thread:
     * dispose waits for chiaki's thread).
     */
    fun register(host: String, accountIdB64: String, pin: Int, log: (String) -> Unit, done: (LinkResult, PsConsole?) -> Unit): Regist? {
        val id = try {
            Base64.getDecoder().decode(accountIdB64.trim())
        } catch (e: IllegalArgumentException) {
            null
        }
        if (id == null || id.size != RegistInfo.ACCOUNT_ID_SIZE) {
            log("bad account ID")
            done(LinkResult.ACCOUNT, null)
            return null
        }
        val info = RegistInfo(Target.PS5_1, host, false, null, id, pin)
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val chiakiLog = ChiakiLog(ChiakiLog.Level.ALL.value and ChiakiLog.Level.VERBOSE.value.inv()) { level, text ->
            val line = ChiakiLog.formatLog(level, text)
            lines.add(line)
            log(line)
        }
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(r: LinkResult, c: PsConsole?) {
            if (once.compareAndSet(false, true)) done(r, c)
        }
        return try {
            Regist(info, chiakiLog) { ev ->
                when (ev) {
                    is RegistEventSuccess -> {
                        val h = ev.host
                        val console = try {
                            PsConsole(host, (h.serverNickname as String?) ?: "", h.serverMac, h.rpRegistKey, h.rpKey)
                        } catch (e: Exception) {
                            Log.e(TAG, "linked, but the answer is incomplete", e)
                            null
                        }
                        if (console != null) finish(LinkResult.LINKED, console) else finish(LinkResult.FAILED, null)
                    }
                    RegistEventCanceled -> finish(LinkResult.CANCELED, null)
                    RegistEventFailed -> finish(failureOf(lines), null)
                }
            }
        } catch (e: CreateError) {
            log("registration failed to start: $e")
            finish(LinkResult.FAILED, null)
            null
        }
    }
}
