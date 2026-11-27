package app.vitranslator.phone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.vitranslator.phone.Prefs
import app.vitranslator.phone.R
import app.vitranslator.phone.xbox.XboxActivity
import app.vitranslator.phone.xbox.XboxAuth
import app.vitranslator.phone.xbox.XboxConsole
import app.vitranslator.phone.xbox.XboxHome

/**
 * Setup's Xbox: sign in to Microsoft with a code (typed at microsoft.com/link on any device: no
 * password in the app), choose the console once, and the stream's quality. Start translating then
 * goes straight to that console, with the phone's screen on or off. Microsoft's web player (the old
 * way) stays as a choice.
 */
class XboxLink(private val h: Host, private val redraw: () -> Unit) {
    private val k get() = h.k

    // the sign-in in progress: its code (null when none), and whether it was given up
    @Volatile
    private var code: XboxAuth.DeviceCode? = null
    private var codeError: String? = null

    @Volatile
    private var cancelled = false

    // the consoles on the account (null until asked), and what went wrong asking
    private var consoles: List<XboxConsole>? = null
    private var listing = false
    private var listError: String? = null
    private var choosing = false

    private fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(top) }

    fun build(into: LinearLayout) {
        val prefs = Prefs(h.act)
        // which client: the app's own (the default) or Microsoft's web player (the old way)
        into.addView(k.label(T("Xbox player")).apply { setPadding(0, k.dp(14), 0, k.dp(2)) })
        into.addView(clientChoice(false, T("VI Translator's player (recommended)"),
            T("Sign in with a code and choose the console once. The phone's screen can be off."), !prefs.xboxWebPlayer))
        into.addView(clientChoice(true, T("Microsoft's web player"),
            T("xbox.com's own player in the app: sign in and choose the console there. The phone's screen stays on."), prefs.xboxWebPlayer))
        if (prefs.xboxWebPlayer) {
            webPlayer(into)
        } else {
            val signed = XboxAuth.signedIn(h.act)
            val saved = XboxConsole.saved(h.act)
            into.addView(step(1, T("Sign in to Microsoft"), signed, true, signInStep(signed)))
            into.addView(step(2, T("Choose your Xbox"), signed && saved != null && !choosing, signed, consoleStep(saved)))
            into.addView(k.field(T("Stream quality"), k.select(listOf("1080" to "1080p", "720" to "720p"), prefs.xboxResolution.toString()) { prefs.xboxResolution = it.toInt() },
                T("1080p reads small text best; 720p for a slower home network.")).apply { layoutParams = lp(12) })
            into.addView(k.label(T("On the Xbox, once")).apply { setPadding(0, k.dp(14), 0, k.dp(4)) })
            into.addView(k.muted("1. " + T("Settings › Devices & connections › Remote features: turn on Enable remote features.")))
            into.addView(k.muted("2. " + T("Settings › General › Power options: choose Sleep, so the player can wake the console.")))
            into.addView(k.muted("3. " + T("Sign in on the Xbox with the same Microsoft account as in the player.")))
            into.addView(k.callout(T("The phone streams the Xbox's picture only to read it (no sound, no controller: keep playing with your controller on the Xbox). The phone's screen can be off.")).apply { layoutParams = lp(12) })
        }
    }

    /** One of the two Xbox clients, as a card to tap (as Setup's console choice). */
    private fun clientChoice(web: Boolean, name: String, about: String, on: Boolean): View = k.column(
        k.row(k.grow(k.text(name, k.base, k.text, k.bold)), k.icon(R.drawable.ic_check, if (on) k.accentUi else android.graphics.Color.TRANSPARENT, 18), gap = 10),
        k.muted(about, small = true).apply { setPadding(0, k.dp(4), 0, 0) },
    ).apply {
        background = k.round(if (on) k.accentSoft else k.panel, 14, if (on) k.accentUi else k.line, if (on) 1.5 else 1)
        setPadding(k.dp(14), k.dp(10), k.dp(14), k.dp(10))
        layoutParams = lp(8)
        setOnClickListener {
            if (Prefs(h.act).xboxWebPlayer != web) {
                // (a stream running with the other client stops: the next Start uses this one)
                if (app.vitranslator.phone.run.Live.state in listOf(app.vitranslator.phone.run.Live.State.STARTING, app.vitranslator.phone.run.Live.State.CONNECTING, app.vitranslator.phone.run.Live.State.READING)) {
                    app.vitranslator.phone.run.TranslateService.stop(h.act)
                }
                Prefs(h.act).xboxWebPlayer = web
                redraw()
            }
        }
    }

    // ---- the frame of a step (as the PS5's)

    private fun step(n: Int, title: String, done: Boolean, open: Boolean, content: View): View {
        val badge = k.text(if (done) "✓" else n.toString(), k.base - 1f, if (done || open) android.graphics.Color.WHITE else k.muted, k.bold).apply {
            gravity = Gravity.CENTER
            background = k.round(if (done) k.ok else if (open) k.accentUi else k.panel2, 12)
            layoutParams = LinearLayout.LayoutParams(k.dp(24), k.dp(24))
        }
        val c = k.column(k.row(badge, k.grow(k.text(title, k.base + 0.5f, if (open) k.text else k.muted, k.bold)), gap = 10))
        if (open) c.addView(content.apply { setPadding(k.dp(34), k.dp(6), 0, 0) })
        return c.apply {
            background = k.round(k.panel, 14, k.line)
            setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12))
            layoutParams = lp(10)
        }
    }

    // ---- 1. sign in with a code

    private fun signInStep(signed: Boolean): View {
        val c = k.column()
        if (signed) {
            c.addView(k.row(
                k.grow(k.text(T("Signed in to Microsoft."), k.base, k.text)),
                k.link(T("Sign out")) {
                    k.ask(T("Sign out of Microsoft for the Xbox?"), T("Sign out")) {
                        XboxAuth.signOut(h.act)
                        consoles = null
                        redraw()
                    }
                },
                gap = 10,
            ))
            return c
        }
        val dc = code
        if (dc == null) {
            c.addView(k.muted(T("You'll get a short code to enter at microsoft.com/link, on this phone or any other device. No password is typed in this app.")))
            codeError?.let { c.addView(k.callout(it, k.warn).apply { layoutParams = lp(8) }) }
            c.addView(k.button(T("Get a sign-in code"), primary = true) { getCode() }.apply { layoutParams = lp(10) })
            return c
        }
        c.addView(k.muted(T("Go to {url} and enter this code, then sign in with the Microsoft account of your Xbox:", "url" to dc.verificationUri.removePrefix("https://"))))
        c.addView(k.text(dc.userCode, k.base + 12f, k.text, k.bold).apply {
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            letterSpacing = 0.12f
            setPadding(0, k.dp(10), 0, k.dp(10))
            setTextIsSelectable(true)
        })
        c.addView(k.row(
            k.grow(k.button(T("Open microsoft.com/link"), primary = true) {
                copy(dc.userCode)
                try {
                    h.act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUri)))
                } catch (e: Exception) {
                    // (no browser: the code is still there to type elsewhere)
                }
            }),
            k.button(T("Copy code")) { copy(dc.userCode) },
            gap = 8,
        ))
        c.addView(k.muted(T("Waiting for you to sign in…"), small = true).apply { setPadding(0, k.dp(8), 0, 0) })
        c.addView(k.link(T("Cancel")) {
            cancelled = true
            code = null
            redraw()
        })
        return c
    }

    private fun copy(s: String) {
        h.act.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("code", s))
    }

    private fun getCode() {
        codeError = null
        cancelled = false
        h.io {
            try {
                val dc = XboxAuth.requestCode()
                h.ui {
                    code = dc
                    redraw()
                }
                // (a newer code, or leaving Setup, ends this wait)
                val ok = XboxAuth.waitForSignIn(h.act, dc) { cancelled || code !== dc }
                h.ui {
                    if (code === dc) {
                        code = null
                        if (!ok) codeError = T("The code expired or the sign-in was declined. Get a new code.")
                        redraw()
                        if (ok) listConsoles()
                    }
                }
            } catch (e: Exception) {
                h.ui {
                    codeError = T("Couldn't reach Microsoft: {error}", "error" to (e.message ?: e.toString()))
                    redraw()
                }
            }
        }
    }

    // ---- 2. the console

    private fun consoleStep(saved: XboxConsole?): View {
        val c = k.column()
        if (saved != null && !choosing) {
            c.addView(k.row(
                k.icon(R.drawable.ic_tv, k.ok, 20),
                k.grow(k.text(saved.name.ifEmpty { saved.serverId }, k.base, k.text, k.bold)),
                k.link(T("Change")) {
                    choosing = true
                    if (consoles == null) listConsoles()
                    redraw()
                },
                gap = 10,
            ))
            return c
        }
        val list = consoles
        when {
            listing -> c.addView(k.muted(T("Looking for your consoles…")))
            listError != null -> c.addView(k.callout(listError ?: "", k.warn))
            list == null -> Unit
            list.isEmpty() -> c.addView(k.callout(T("No Xbox on this Microsoft account allows Remote Play. Turn on remote features on the Xbox (below), then look again."), k.warn))
            else -> for (x in list) {
                c.addView(k.row(
                    k.icon(R.drawable.ic_tv, k.muted, 20),
                    k.grow(k.column(
                        k.text(x.name.ifEmpty { x.serverId }, k.base, k.text, k.bold),
                        k.muted(listOf(x.type, x.powerState).filter { it.isNotEmpty() }.joinToString(" · "), small = true),
                    )),
                    k.button(T("Use this one"), primary = true) {
                        XboxConsole.save(h.act, x)
                        choosing = false
                        redraw()
                    },
                    gap = 10,
                ).apply { layoutParams = lp(8) })
            }
        }
        c.addView(k.link(if (list == null && !listing) T("Find my consoles") else T("Look again")) { listConsoles() }.apply { layoutParams = lp(8) })
        return c
    }

    private fun listConsoles() {
        if (listing) return
        listing = true
        listError = null
        redraw()
        h.io {
            val result = try {
                Result.success(XboxHome(XboxAuth.streaming(h.act), 1080).consoles())
            } catch (e: Exception) {
                Result.failure(e)
            }
            h.ui {
                listing = false
                result.onSuccess { list ->
                    consoles = list
                    // (just one: it's the one)
                    if (list.size == 1 && XboxConsole.saved(h.act) == null) {
                        XboxConsole.save(h.act, list[0])
                        choosing = false
                    }
                }.onFailure { e ->
                    listError = if (e is XboxAuth.SignedOut) T("Signed out of Microsoft. Sign in again.") else T("Couldn't get your consoles: {error}", "error" to (e.message ?: e.toString()))
                }
                redraw()
            }
        }
    }

    // ---- Microsoft's web player (the old way)

    private fun webPlayer(into: LinearLayout) {
        val signed = XboxActivity.signedIn()
        into.addView(k.callout(
            if (signed) T("Signed in to Microsoft in the Xbox player.") else T("Not signed in to the Xbox player yet."),
            if (signed) k.ok else null,
        ).apply { layoutParams = lp(12) })
        into.addView(k.muted(T("Open the player, sign in with your Microsoft account and choose your Xbox under Remote play. The sign-in stays in the app: next time, Start translating goes straight to the game.")).apply { layoutParams = lp(10) })
        into.addView(k.row(
            k.grow(k.button(T("Open the Xbox player"), primary = true, icon = R.drawable.ic_tv) { XboxActivity.openForSetup(h.act) }),
            gap = 8,
        ).apply { layoutParams = lp(12) })
        if (signed) into.addView(k.link(T("Sign out of Microsoft")) {
            k.ask(T("Sign out of Microsoft in the Xbox player?"), T("Sign out")) {
                XboxActivity.signOut()
                redraw()
            }
        })
        into.addView(k.callout(T("While translating, the app copies the player's picture to read it. If the copies come out black on your phone, the player offers to capture the screen instead (Android asks you to allow it).")).apply { layoutParams = lp(12) })
    }

    fun close() {
        cancelled = true
        code = null
    }
}
