package app.vitranslator.phone.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.vitranslator.phone.Prefs
import app.vitranslator.phone.R
import app.vitranslator.phone.ps.Ps5
import app.vitranslator.phone.ps.Psn
import app.vitranslator.phone.ps.PsnLoginActivity
import app.vitranslator.phone.ps.chiaki.Chiaki
import app.vitranslator.phone.ps.chiaki.DiscoveryHost
import app.vitranslator.phone.ps.chiaki.DiscoveryService
import app.vitranslator.phone.ps.chiaki.Regist

/**
 * Linking a PS5, once, in three steps, each done before the next opens: sign in to PSN with a second
 * account (in the app, or in a browser when Sony's check refuses the app), find it on the home network
 * (searched at once; or its address typed), then the PIN the PS5 shows, and Link. Linked: the
 * console, with Wake, the stream's quality and Unlink.
 */
class Ps5Link(private val h: Host, private val redraw: () -> Unit) {
    private val k get() = h.k
    private var discovery: DiscoveryService? = null
    private var regist: Regist? = null
    private var searched = false
    private var hosts: List<DiscoveryHost> = emptyList()
    private var searching = false

    /** the address chosen (a console found, or typed) */
    private var address: String
        get() = h.act.getSharedPreferences("ps5", Activity.MODE_PRIVATE).getString("address", "") ?: ""
        set(v) = h.act.getSharedPreferences("ps5", Activity.MODE_PRIVATE).edit().putString("address", v.trim()).apply()

    /** the console's name, when it was found */
    private var name: String
        get() = h.act.getSharedPreferences("ps5", Activity.MODE_PRIVATE).getString("name", "") ?: ""
        set(v) = h.act.getSharedPreferences("ps5", Activity.MODE_PRIVATE).edit().putString("name", v).apply()

    private var editingAddress = false
    private var editingAccount = false
    /** what was typed in the PIN and address fields (kept when the panel is drawn again: it is, often) */
    private var pinTyped = ""
    private var addressTyped: String? = null
    private var addressError: String? = null
    /** the last linking's message, and whether it is an error */
    private var linkMsg: String? = null
    private var linkErr = false
    private var linking = false
    private var closed = false

    private fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(top) }

    fun build(into: LinearLayout) {
        if (!Chiaki.available) {
            into.addView(k.callout(T("PS Remote Play isn't in this build of the app."), k.err).apply { layoutParams = lp(12) })
            return
        }
        val saved = Ps5.saved(h.act)
        if (saved != null && !showSteps) {
            linked(into, saved)
            return
        }
        if (!searched) search()
        val addressOk = validAddress(address)
        val accountOk = validAccount(Ps5.accountId(h.act))
        // (PSN first, as Sony's own app: the step that may take a few tries; finding the PS5 next, then
        // its PIN at once, which lasts only about 5 minutes)
        into.addView(step(1, T("Sign in to PSN"), accountOk && !editingAccount, true, signInStep(accountOk)))
        into.addView(step(2, T("Find your PS5"), addressOk && !editingAddress, accountOk && !editingAccount, findStep(addressOk)))
        into.addView(step(3, T("Enter the PIN from the PS5"), false, addressOk && accountOk && !editingAddress && !editingAccount, pinStep()))
    }

    // ---- the frame of a step: its number (or ✓), its title, then what it asks

    private fun step(n: Int, title: String, done: Boolean, open: Boolean, content: View): View {
        val badge = k.text(if (done) "✓" else n.toString(), k.base, if (done || open) Color.WHITE else k.muted, k.bold).apply {
            gravity = Gravity.CENTER
            background = when {
                done -> k.round(k.ok, 999)
                open -> k.sunset(999)
                else -> k.round(k.panel3, 999)
            }
            layoutParams = LinearLayout.LayoutParams(k.dp(30), k.dp(30))
        }
        val head = k.row(badge, k.grow(k.text(title, k.base + 1f, if (open || done) k.text else k.muted, k.bold)), gap = 12)
        val card = k.card(14, head)
        card.layoutParams = lp(12)
        if (!open && !done) {
            card.alpha = 0.6f
            return card
        }
        card.addView(content, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(10) })
        if (open && !done) card.background = k.round(k.panel, 14, k.accentUi, 1.5)
        return card
    }

    // ---- 1. find it

    private fun findStep(addressOk: Boolean): View {
        val c = k.column()
        if (addressOk && !editingAddress) {
            c.addView(k.row(
                k.icon(R.drawable.ic_source, k.ok, 20),
                k.grow(k.column(
                    k.text(name.ifEmpty { "PS5" }, k.base, k.text, k.bold),
                    k.muted(address, small = true).apply { textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START },
                )),
                k.link(T("Change")) {
                    editingAddress = true
                    redraw()
                },
                gap = 10,
            ))
            return c
        }
        c.addView(k.muted(T("The PS5 must be on (or in rest mode) and on the same network as this phone."), small = true))
        // the consoles found
        if (searching) c.addView(k.row(
            android.widget.ProgressBar(h.act).apply { layoutParams = LinearLayout.LayoutParams(k.dp(20), k.dp(20)) },
            k.muted(T("Looking for PlayStation consoles…"), small = true),
            gap = 10,
        ).apply { layoutParams = lp(10) })
        for (x in hosts) {
            val addr = x.hostAddr ?: continue
            val standby = x.state == DiscoveryHost.State.STANDBY
            c.addView(k.row(
                k.icon(R.drawable.ic_source, k.accentUi, 22),
                k.grow(k.column(
                    k.text(x.hostName ?: "PS5", k.base, k.text, k.bold),
                    k.muted("$addr · " + T(if (standby) "rest mode" else "on"), small = true).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START },
                )),
                k.button(T("Choose"), primary = true) {
                    address = addr
                    name = x.hostName ?: "PS5"
                    editingAddress = false
                    redraw()
                },
                gap = 10,
            ).apply {
                background = k.round(k.panel2, 12, k.line)
                setPadding(k.dp(12), k.dp(8), k.dp(8), k.dp(8))
                layoutParams = lp(8)
            })
        }
        if (!searching && hosts.isEmpty()) c.addView(k.callout(T("No PS5 found yet. Is it on, with Remote Play allowed (Settings › System › Remote Play)? You can also type its address below.")).apply { layoutParams = lp(10) })
        if (!searching) c.addView(k.link(T("Search again")) { search() })
        // or typed
        val typed = k.input(addressTyped ?: address, "192.168.1.20", ltr = true) { addressTyped = it }.apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        c.addView(k.field(T("Or type its address"), typed, T("On the PS5: Settings › Network › Connection Status › View Connection Status, IPv4 address. Not the PIN: that comes in the next step.")))
        addressError?.let { c.addView(k.text(it, k.base - 0.5f, k.err).apply { setPadding(0, k.dp(6), 0, 0) }) }
        c.addView(k.button(T("Use this address")) {
            val a = typed.text.toString().trim()
            addressError = addressProblem(a)
            if (addressError != null) {
                redraw()
                return@button
            }
            address = a
            addressTyped = null
            name = hosts.firstOrNull { it.hostAddr == a }?.hostName ?: ""
            editingAddress = false
            redraw()
        }.apply { layoutParams = lp(10) })
        return c
    }

    /** What is wrong with a typed address (the PIN or the TV's code typed there, most often), or null. */
    private fun addressProblem(a: String): String? = when (addressKind(a)) {
        AddressKind.OK -> null
        AddressKind.PIN -> T("That's the 8-digit PIN, not the PS5's address. Choose the PS5 found above, or type its IP address (like 192.168.1.20); the PIN goes in the next step.")
        AddressKind.TV_CODE -> T("That looks like the TV's 6-digit pairing code. Here, type the PS5's IP address (like 192.168.1.20).")
        AddressKind.BAD -> T("That isn't an address like 192.168.1.20.")
    }

    private fun search() {
        searched = true
        stop()
        searching = true
        hosts = emptyList()
        discovery = Ps5.discover { found -> h.ui { hosts = found; if (found.isNotEmpty()) { searching = false; stop() }; redraw() } }
        h.act.window.decorView.postDelayed({
            if (searching) {
                searching = false
                stop()
                redraw()
            }
        }, 8000)
    }

    // ---- 2. PSN

    private fun signInStep(accountOk: Boolean): View {
        val c = k.column()
        if (accountOk && !editingAccount) {
            c.addView(k.row(
                k.icon(R.drawable.ic_check, k.ok, 20),
                k.grow(k.column(
                    k.text(T("Signed in: the account ID is ready"), k.base, k.text, k.bold),
                    k.muted(Ps5.accountId(h.act), small = true).apply { textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START },
                )),
                k.link(T("Change")) {
                    editingAccount = true
                    redraw()
                },
                gap = 10,
            ))
            return c
        }
        c.addView(k.callout(T("Sign in with a second PSN account, not the one you play with: Remote Play signs it in on the PS5. Only its account ID is kept."), k.warn))
        c.addView(k.button(T("Sign in to PSN"), primary = true) {
            @Suppress("DEPRECATION")
            h.act.startActivityForResult(Intent(h.act, PsnLoginActivity::class.java), REQ_PSN)
        }.apply { layoutParams = lp(10) })
        c.addView(k.muted(T("If Sony says it can't connect, wait a few seconds and press Sign In again; or sign in with a browser."), small = true).apply { setPadding(0, k.dp(8), 0, 0) })
        c.addView(k.link(T("Sign in with a browser")) { pasteAddress() })
        // (an account ID already known: typed or pasted)
        val typed = k.input(Ps5.accountId(h.act), "", ltr = true) {}
        c.addView(k.field(T("Or type the account ID"), typed))
        c.addView(k.button(T("Use this account ID")) {
            val v = typed.text.toString().trim()
            if (!validAccount(v)) {
                android.widget.Toast.makeText(h.act, T("That isn't an account ID (12 letters and digits, ending in =)."), android.widget.Toast.LENGTH_LONG).show()
                return@button
            }
            Ps5.saveAccountId(h.act, v)
            editingAccount = false
            redraw()
        }.apply { layoutParams = lp(8) })
        return c
    }

    fun result(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PSN) return
        val id = data?.getStringExtra(PsnLoginActivity.EXTRA_ACCOUNT_ID)
        if (resultCode == Activity.RESULT_OK && id != null) {
            Ps5.saveAccountId(h.act, id)
            editingAccount = false
            redraw()
        } else data?.getStringExtra(PsnLoginActivity.EXTRA_ERROR)?.let {
            android.widget.Toast.makeText(h.act, T("PSN sign-in didn't work: {e}", "e" to it), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Sony's sign-in in a browser instead of in the app (its bot check sometimes refuses the app's):
     * the browser ends on remoteplay.dl.playstation.net/remoteplay/redirect?code=…; that address
     * pasted here gives the account ID (the code is used once, within a few minutes).
     */
    private fun pasteAddress() {
        val e = k.input("", "https://remoteplay.dl.playstation.net/remoteplay/redirect?code=…", ltr = true) {}
        val msg = k.muted("", small = true)
        val link = Psn.loginUrl
        var d: android.app.Dialog? = null
        d = k.dialog(k.card(18,
            k.text(T("Sign in with a browser"), k.base + 0.5f, k.text, k.bold),
            k.muted(T("1. Open Sony's sign-in in a browser (on this phone or a PC) and sign in with your second PSN account."), small = true).apply { setPadding(0, k.dp(8), 0, 0) },
            k.row(
                k.grow(k.button(T("Open in the browser")) {
                    try {
                        h.act.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)))
                    } catch (ex: Exception) {
                        msg.text = ex.message
                    }
                }),
                k.grow(k.button(T("Copy the link")) {
                    h.act.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("PSN", link))
                    msg.text = T("Copied.")
                }),
            ).apply { setPadding(0, k.dp(8), 0, 0) },
            k.muted(T("2. It ends on a page whose address starts with https://remoteplay.dl.playstation.net (the page itself may be blank). Copy that address and paste it here:"), small = true).apply { setPadding(0, k.dp(12), 0, k.dp(6)) },
            e, msg, k.gap(10),
            k.button(T("Get the account ID"), primary = true) {
                val code = Regex("[?&]code=([^&#\\s]+)").find(e.text.toString().trim())?.groupValues?.get(1)
                if (code == null) {
                    msg.text = T("That address has no sign-in code: copy the whole address of the page you ended on.")
                    return@button
                }
                msg.text = T("Getting the account ID…")
                h.io {
                    val r = try {
                        Psn.accountId(android.net.Uri.decode(code)) to null
                    } catch (ex: Exception) {
                        null to (ex.message ?: ex.toString())
                    }
                    h.ui {
                        val id = r.first
                        if (id != null) {
                            Ps5.saveAccountId(h.act, id)
                            editingAccount = false
                            d?.dismiss()
                            redraw()
                        } else {
                            msg.text = T("PSN sign-in didn't work: {e}", "e" to (r.second ?: "")) + "\n" + T("The code works once and only for a few minutes: sign in again and paste the new address.")
                        }
                    }
                }
            },
        ))
        d.show()
    }

    // ---- 3. the PIN, and Link

    private fun pinStep(): View {
        val c = k.column()
        c.addView(k.muted(T("On the PS5: Settings › System › Remote Play › Link Device. It shows an 8-digit PIN, for about 5 minutes."), small = true))
        c.addView(k.muted(T("Only the PIN from that PS5 screen: not the TV's pairing code, not the PS5's address."), small = true).apply { setPadding(0, k.dp(4), 0, 0) })
        val p = k.input(pinTyped, "0000 0000", ltr = true) { pinTyped = it }.apply {
            // (digits only, 8 of them: a phone keypad, no decimal point or sign)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = k.base + 12f
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
            typeface = k.bold
            filters = arrayOf(InputFilter { src, start, end, _, _, _ ->
                val digits = src.subSequence(start, end).filter { it in '0'..'9' }
                if (digits.length == end - start) null else digits
            }, InputFilter.LengthFilter(8))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    link()
                    true
                } else false
            }
            isEnabled = !linking
        }
        c.addView(p, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(10) })
        val msg = if (linking) T("Linking…") else linkMsg
        if (msg != null) c.addView(k.text(msg, k.base - 0.5f, if (linkErr && !linking) k.err else k.muted).apply { setPadding(0, k.dp(8), 0, 0) })
        c.addView(k.button(T("Link"), primary = true) { link() }.apply {
            layoutParams = lp(8)
            alpha = if (linking) 0.5f else 1f
        })
        return c
    }

    private fun showLink(message: String?, err: Boolean) {
        linkMsg = message
        linkErr = err
        redraw()
    }

    private fun link() {
        if (linking) return
        val account = Ps5.accountId(h.act)
        if (!validAccount(account)) {
            editingAccount = true
            showLink(T("The PSN account isn't set: sign in to PSN first (step 1)."), true)
            return
        }
        if (!validAddress(address)) {
            editingAddress = true
            showLink(T("The PS5's address isn't set: find your PS5 first (step 2)."), true)
            return
        }
        val digits = pinTyped.filter { it.isDigit() }
        val pin = Ps5.pinOf(pinTyped)
        if (pin == null) {
            showLink(if (digits.length == 6) T("That's 6 digits, like the TV's pairing code. The PS5's PIN has 8 digits: Settings › System › Remote Play › Link Device.") else T("The PIN has 8 digits."), true)
            return
        }
        linking = true
        showLink(null, false)
        // (an earlier try is over: its thread is ended off the UI thread, as dispose waits for it)
        regist?.let { old -> h.io { old.dispose() } }
        regist = null
        regist = Ps5.register(address, account, pin, { line -> android.util.Log.i("Regist", line) }) { result, console ->
            // (on chiaki's thread, or here at once when linking can't start: the rest on the UI thread)
            h.ui { linked(result, console) }
        }
    }

    /** Linking ended (on the UI thread). */
    private fun linked(result: Ps5.LinkResult, console: app.vitranslator.phone.ps.PsConsole?) {
        linking = false
        if (closed) {
            // (the screen was left meanwhile: a console linked is still kept)
            if (console != null) Ps5.save(h.act, console)
            return
        }
        when (result) {
            Ps5.LinkResult.LINKED -> {
                if (console != null) {
                    Ps5.save(h.act, console)
                    pinTyped = ""
                    stop()
                    showLink(null, false)
                    return
                }
                showLink(T("Linking didn't work. Try again."), true)
            }
            Ps5.LinkResult.WRONG_PIN -> {
                pinTyped = ""
                showLink(T("The PS5 refused this PIN: it's wrong or no longer valid. The PIN changes each time Link Device is opened on the PS5: type the one it shows now."), true)
            }
            Ps5.LinkResult.SCREEN_CLOSED -> showLink(T("The PS5 isn't showing the Link Device screen. Open it again (Settings › System › Remote Play › Link Device) and type the new PIN it shows."), true)
            Ps5.LinkResult.NOT_FOUND -> showLink(T("The PS5 didn't answer at {address}. Is it on (or in rest mode), on the same network as this phone, with Remote Play on? Check its address in step 2.", "address" to address), true)
            Ps5.LinkResult.ACCOUNT -> {
                editingAccount = true
                showLink(T("The PS5 refused the PSN account: sign in to PSN again (step 1)."), true)
            }
            Ps5.LinkResult.CANCELED -> showLink(null, false)
            Ps5.LinkResult.FAILED -> showLink(T("Linking didn't work. Try again."), true)
        }
    }

    // ---- linked

    private fun linked(into: LinearLayout, c: app.vitranslator.phone.ps.PsConsole) {
        val icon = k.icon(R.drawable.ic_source, k.ok, 26).apply {
            background = k.round(0x2228c08a, 12)
            setPadding(k.dp(10), k.dp(10), k.dp(10), k.dp(10))
            layoutParams = LinearLayout.LayoutParams(k.dp(48), k.dp(48))
            scaleType = android.widget.ImageView.ScaleType.CENTER
        }
        val card = k.card(14,
            k.row(icon, k.grow(k.column(
                k.text(c.nickname.ifEmpty { "PS5" }, k.base + 1.5f, k.text, k.bold),
                k.text("✓ " + T("Linked") + " · " + c.host, k.base - 1f, k.ok, k.bold).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START },
            )), gap = 12),
            k.muted(T("Start translating connects to it with Remote Play. If it is in rest mode, Wake turns it on."), small = true).apply { setPadding(0, k.dp(10), 0, 0) },
            k.row(
                k.grow(k.button(T("Wake the PS5")) {
                    Ps5.wakeup(c)
                    android.widget.Toast.makeText(h.act, T("Waking {name}…", "name" to c.nickname.ifEmpty { "PS5" }), android.widget.Toast.LENGTH_SHORT).show()
                }),
                k.grow(k.button(T("Unlink"), danger = true) {
                    k.ask(T("Unlink {name}? To use it again, link it with a new PIN.", "name" to c.nickname.ifEmpty { "PS5" }), T("Unlink")) {
                        Ps5.save(h.act, null)
                        searched = false
                        redraw()
                    }
                }),
            ).apply { layoutParams = lp(12) },
        )
        card.layoutParams = lp(12)
        into.addView(card)
        val prefs = Prefs(h.act)
        into.addView(k.field(T("Stream quality"), k.select(listOf("1080" to "1080p", "720" to "720p"), prefs.psResolution.toString()) { prefs.psResolution = it.toInt() },
            T("1080p reads small text best; 720p for a slower home network.")))
    }

    private fun stop() {
        discovery?.dispose()
        discovery = null
    }

    fun close() {
        closed = true
        stop()
        // (stop() ends a linking under way at once; dispose() then waits for chiaki's thread, so not
        // on the UI thread: it would freeze the app until the PS5 answers or times out)
        regist?.let { r ->
            r.stop()
            h.io { r.dispose() }
        }
        regist = null
    }

    companion object {
        const val REQ_PSN = 11

        /** debug builds: the linking steps shown even with a PS5 linked (to look at them) */
        @Volatile
        var showSteps = false

        enum class AddressKind { OK, PIN, TV_CODE, BAD }

        /** A typed address: an IPv4 address, or what was typed there by mistake (the PIN, the TV's code). */
        fun addressKind(a: String): AddressKind {
            val t = a.trim()
            val digits = t.filter { !it.isWhitespace() && it != '-' }
            return when {
                validAddress(t) -> AddressKind.OK
                digits.length == 8 && digits.all { it in '0'..'9' } -> AddressKind.PIN
                digits.length == 6 && digits.all { it in '0'..'9' } -> AddressKind.TV_CODE
                else -> AddressKind.BAD
            }
        }

        fun validAddress(a: String) = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(a.trim())?.groupValues?.drop(1)?.all { it.toInt() <= 255 } == true

        /** a PSN account ID: 8 bytes in base64 (12 characters) */
        fun validAccount(a: String) = try {
            java.util.Base64.getDecoder().decode(a.trim()).size == 8
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
