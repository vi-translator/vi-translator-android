package app.vitranslator.phone.ui

import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import app.vitranslator.phone.App
import app.vitranslator.phone.Prefs
import app.vitranslator.phone.R
import app.vitranslator.phone.core.PairResult
import app.vitranslator.phone.core.Tv
import app.vitranslator.phone.ps.Ps5
import app.vitranslator.phone.ps.PsnLoginActivity
import app.vitranslator.phone.ps.chiaki.Chiaki
import app.vitranslator.phone.ps.chiaki.DiscoveryHost
import app.vitranslator.phone.ps.chiaki.DiscoveryService
import app.vitranslator.phone.ps.chiaki.Regist
import org.json.JSONObject

// ------------------------------------------------------------------------------------- Source

/**
 * Source: where the picture comes from. The desktop picks a window, a capture card or OBS; the phone
 * reads the console itself: a PS5 (PS Remote Play, set up once: find it, sign in to PSN for the
 * account ID, the PIN it shows) or an Xbox (Xbox Remote Play: a Microsoft sign-in by code, the console chosen once). Then the desktop's Timing.
 */
class SourcePanel(h: Host) : Panel(h) {
    private val ps5 = Ps5Link(h) { refresh() }
    private val xbox = XboxLink(h) { refresh() }

    override fun refresh() {
        view.removeAllViews()
        val prefs = Prefs(h.act)
        add(k.h3(T("Picture source")))
        add(k.muted(T("The phone reads the game from your console over the home network; the TV keeps showing the console as usual. Only the translations go to the TV.")))
        add(k.gap(10))
        add(consoleChoice("ps5", "PlayStation 5", T("PS Remote Play: the phone streams the PS5's picture to read it (no sound). Works with the phone's screen off."), prefs.console == "ps5"))
        add(k.gap(8))
        add(consoleChoice("xbox", "Xbox", if (prefs.xboxWebPlayer) T("Microsoft's Remote Play player, in the app. Keep the phone's screen on while playing.") else T("Xbox Remote Play: the phone streams the Xbox's picture to read it (no sound). Works with the phone's screen off."), prefs.console == "xbox"))
        add(k.gap(8))
        add(consoleChoice("card", T("Capture card"), T("Any console: its HDMI goes into a USB capture card plugged into the phone. Works with the phone's screen off."), prefs.console == "card"))
        if (prefs.console == "ps5") ps5Setup()
        if (prefs.console == "xbox") xboxSetup()
        if (prefs.console == "card") cardSetup()
        add(k.h3(T("Timing")))
        timing()
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(top) }

    private fun consoleChoice(id: String, name: String, about: String, on: Boolean): View {
        val c = k.column(
            k.row(k.icon(when (id) { "ps5" -> R.drawable.ic_source; "card" -> R.drawable.ic_camera; else -> R.drawable.ic_tv }, if (on) k.accentUi else k.muted, 20), k.grow(k.text(name, k.base + 0.5f, k.text, k.bold)),
                k.icon(R.drawable.ic_check, if (on) k.accentUi else Color.TRANSPARENT, 18), gap = 10),
            k.muted(about, small = true).apply { setPadding(0, k.dp(4), 0, 0) },
        ).apply {
            background = k.round(if (on) k.accentSoft else k.panel, 14, if (on) k.accentUi else k.line, if (on) 1.5 else 1)
            setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12))
            setOnClickListener {
                Prefs(h.act).console = id
                refresh()
            }
        }
        return c
    }

    /**
     * The capture card: how to plug it in, whether this phone can use one (Android lists USB
     * cameras on the phones that support them), whether one is plugged in now, and the 4K picture.
     */
    private fun cardSetup() {
        add(k.h3(T("Capture card")))
        add(k.muted("1. " + T("Plug the console's HDMI into the card's HDMI in, and the card's HDMI out into the TV (if it has one).")))
        add(k.muted("2. " + T("Plug the card's USB into the phone (USB-C, or with a USB-C adapter or hub).")))
        add(k.muted("3. " + T("Press Start translating in Play.")))
        val supported = app.vitranslator.phone.card.CardStream.supported(h.act)
        val found = app.vitranslator.phone.card.CardStream.find(h.act) != null
        val status = when {
            !supported && !found -> T("This phone doesn't support USB cameras, so it can't use a capture card. Use PS5 or Xbox Remote Play instead.")
            found -> T("A capture card is plugged in.")
            else -> T("No capture card found. Plug it in, then check again.")
        }
        add(k.row(k.grow(k.text(status, k.base - 1f, if (found) k.text else k.muted, k.bold)),
            k.button(T("Check again")) { refresh() }, gap = 10).apply { layoutParams = lp(10) })
        add(k.check(T("4K picture"), Prefs(h.act).card4k) { Prefs(h.act).card4k = it })
        add(k.muted(T("Off: 1080p, which is plenty for reading text and what most cards send. On: the card's largest picture, up to 4K (slower to read)."), small = true))
        add(k.gap(14))
    }

    private fun ps5Setup() {
        ps5.build(view)
    }

    /** The Xbox: a Microsoft sign-in by code and the console, once (XboxLink). */
    private fun xboxSetup() {
        xbox.build(view)
    }

    override fun tick() {
        // (back from the player: show whether the sign-in worked)
        if (Prefs(h.act).console == "xbox" && Prefs(h.act).xboxWebPlayer) {
            val now = app.vitranslator.phone.xbox.XboxActivity.signedIn()
            if (now != lastSigned) {
                lastSigned = now
                refresh()
            }
        }
    }

    private var lastSigned: Boolean? = null

    override fun result(requestCode: Int, resultCode: Int, data: Intent?) {
        ps5.result(requestCode, resultCode, data)
    }

    /** The desktop's Timing form: what is read how often, and how long text must settle. */
    private fun timing() {
        val fields = listOf(
            listOf("capture_fps", "Frames captured for reading text (per second)", 1, 30, 1, "Full-size frames used for reading text, game pages and saved frames. Also how often the pause menu checks whether its text changed. 12 is plenty; more costs CPU, especially at 4K. From OBS, about 12 is the most it can deliver."),
            listOf("interval_ms", "How often areas are read (ms)", 100, 1000, 50, "Lower = faster reaction, more CPU."),
            listOf("stable_ms", "Wait for text to settle before translating (ms)", 0, 1500, 50, "Avoids translating half-faded lines."),
            listOf("clear_after_ms", "Treat the English as gone after (ms)", 200, 3000, 100, "Ignores short OCR blinks. The translation then stays as long as the English was on screen, and switches to the next line as soon as it is ready."),
        )
        // the last three: the game profile's timing unless the player chose their own (as the desktop)
        val timed = listOf("interval_ms", "stable_ms", "clear_after_ms")
        val game = cfg.activeProfile()?.optJSONObject("timing") ?: JSONObject()
        val gameHas = timed.any { game.optDouble(it, 0.0) > 0 }
        fun inUse(key: String) = if (cfg.bool("timing_custom") || game.optDouble(key, 0.0) <= 0) cfg.num(key) else game.optDouble(key)
        for (f in fields) {
            val key = f[0] as String
            val v = if (key in timed) inUse(key) else cfg.num(key, 12.0)
            add(k.field(T(f[1] as String), k.slider((f[2] as Int).toFloat(), (f[3] as Int).toFloat(), (f[4] as Int).toFloat(), v.toFloat(), { it.toInt().toString() }) { x ->
                if (key in timed && !cfg.bool("timing_custom")) {
                    for (t in timed) cfg.c.put(t, inUse(t))
                    cfg.put("timing_custom", true)
                    timed.forEach { cfg.touched(it) }
                }
                cfg.put(key, x.toInt())
            }, T(f[5] as String)))
        }
        if (gameHas && cfg.bool("timing_custom")) {
            add(k.button(T("Use the game's timing")) {
                cfg.put("timing_custom", false)
                refresh()
            }.apply { layoutParams = lp(12) })
        }
    }

    override fun close() {
        ps5.close()
        xbox.close()
    }

}

// ------------------------------------------------------------------------------------- Show on

/**
 * Show on: the TVs (the desktop's "Show on a TV": connect with the code the TV shows), the live
 * translations on other devices (the home-network page), and the preview's options.
 */
class ShowPanel(h: Host) : Panel(h) {
    private val tvFound = k.column()
    private val tvConnected = k.column()
    private val lanInfo = k.callout()

    override fun refresh() {
        view.removeAllViews()
        add(k.h3(T("Show on a TV")))
        add(k.muted(T("The translations over the game on a Google TV, Android TV, LG or Samsung TV, with the VI Translator app. Open the app on the TV, then connect it here with the code it shows.")))
        add(k.button(T("Connect a TV"), primary = true, icon = R.drawable.ic_tv) { findTvs() }.apply { layoutParams = lp(12) })
        add(tvFound)
        add(k.link(T("Type the TV's address")) { typeAddress() })
        add(tvConnected)
        listTvs()

        add(k.h3(T("Live translations on other devices")))
        add(k.check(T("Share on my home network"), cfg.bool("lan_page")) {
            cfg.put("lan_page", it)
            updateLan()
        })
        add(lanInfo.apply { layoutParams = lp(4) })
        (qr.parent as? android.view.ViewGroup)?.removeView(qr)
        add(qr)
        add(k.check(T("Allow changes from that page"), cfg.bool("lan_control")) { cfg.put("lan_control", it) })
        add(k.muted(T("Lets the page switch the overlays and the live translations, and show or hide read areas and game pages."), small = true))
        updateLan()

        add(k.h3(T("Preview")))
        val corners = listOf("top-left" to T("Top left"), "top-right" to T("Top right"), "bottom-left" to T("Bottom left"), "bottom-right" to T("Bottom right"))
        add(k.check(T("Show frames per second (fps) on the preview"), cfg.bool("fps_badge")) { cfg.put("fps_badge", it) })
        add(k.field(T("Where it shows"), k.select(corners, cfg.str("fps_corner", "top-right")) { cfg.put("fps_corner", it) }))
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(top) }

    private val qr = android.webkit.WebView(h.act).apply {
        setBackgroundColor(Color.WHITE)
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(k.dp(170), k.dp(170)).apply { topMargin = k.dp(8) }
    }

    private fun updateLan(tries: Int = 0) {
        if (!cfg.bool("lan_page")) {
            lanInfo.text = T("Off. Turn it on to read the live translations on another device on your home network.")
            qr.visibility = View.GONE
            return
        }
        // (the setting is saved first; the page starts with it)
        cfg.flush()
        h.io {
            val info = try {
                App.core.lanInfo()?.let { JSONObject(it) }
            } catch (e: Exception) {
                null
            }
            h.ui {
                val url = info?.takeUnless { it.isNull("url") }?.optString("url").orEmpty()
                val error = info?.takeUnless { it.isNull("error") }?.optString("error").orEmpty()
                lanInfo.text = when {
                    info == null -> T("Not in the phone app yet: coming with an update.")
                    url.isNotEmpty() -> T("Open {url} on a phone, tablet or PC on the same network.", "url" to url)
                    error.isNotEmpty() -> T("The page couldn't start ({error}). Another program may be using this port.", "error" to error)
                    else -> T("Starting…")
                }
                val svg = info?.takeUnless { it.isNull("qr") }?.optString("qr").orEmpty()
                qr.visibility = if (url.isNotEmpty() && svg.contains("<svg")) View.VISIBLE else View.GONE
                if (qr.visibility == View.VISIBLE) {
                    val page = "<html><body style=\"margin:0;background:#fff\">" + svg.replace("<svg", "<svg style=\"width:100%;height:100%\"") + "</body></html>"
                    qr.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
                }
                if (url.isEmpty() && error.isEmpty() && info != null && tries < 20) view.postDelayed({ updateLan(tries + 1) }, 400)
            }
        }
    }

    private fun listTvs() {
        h.io {
            val tvs = try {
                App.core.pairedTvs()
            } catch (e: Exception) {
                emptyList()
            }
            h.ui {
                tvConnected.removeAllViews()
                if (tvs.isEmpty()) return@ui
                tvConnected.addView(k.label(T("Connected TVs")).apply { setPadding(0, k.dp(14), 0, k.dp(4)) })
                for (tv in tvs) {
                    tvConnected.addView(k.row(
                        k.icon(R.drawable.ic_tv, if (tv.online == false) k.muted else k.accentUi, 20),
                        k.grow(k.column(
                            k.text(tv.name + if (tv.address.isEmpty()) "" else " · ${tv.address}", k.base, k.text, k.bold),
                            k.muted(when (tv.online) {
                                true -> T("Showing the translations")
                                false -> T("Not connected now (the TV is off, or VI Translator isn't open on it)")
                                null -> ""
                            }, small = true),
                        )),
                        k.link(T("Remove")) { forget(tv) },
                        gap = 10,
                    ).apply {
                        background = k.round(k.panel, 12, k.line)
                        setPadding(k.dp(12), k.dp(6), k.dp(8), k.dp(6))
                        layoutParams = lp(8)
                    })
                }
            }
        }
    }

    private fun forget(tv: Tv) {
        k.ask(T("Disconnect {name}?", "name" to tv.name), T("Remove")) {
            h.io {
                App.core.forgetTv(tv.id)
                h.ui { listTvs() }
            }
        }
    }

    private fun findTvs() {
        tvFound.removeAllViews()
        tvFound.addView(k.muted(T("Looking for TVs on the home network…"), small = true).apply { setPadding(0, k.dp(10), 0, 0) })
        h.io {
            val tvs = App.core.findTvs()
            h.ui {
                tvFound.removeAllViews()
                if (tvs.isEmpty()) {
                    tvFound.addView(k.callout(T("No TV found. Is VI Translator open on the TV, on the same network? You can also type its address.")).apply { layoutParams = lp(10) })
                    return@ui
                }
                for (tv in tvs) {
                    tvFound.addView(k.row(
                        k.icon(R.drawable.ic_tv, k.muted, 20),
                        k.grow(k.text(tv.name, k.base, k.text, k.bold)),
                        k.button(T("Connect"), primary = true) { askCode(tv.address, tv.name) },
                        gap = 10,
                    ).apply {
                        background = k.round(k.panel, 12, k.line)
                        setPadding(k.dp(12), k.dp(6), k.dp(6), k.dp(6))
                        layoutParams = lp(8)
                    })
                }
            }
        }
    }

    private fun typeAddress() {
        val e = k.input("", "192.168.1.30", ltr = true) {}
        var d: android.app.Dialog? = null
        d = k.dialog(k.card(18, k.text(T("The TV's address"), k.base + 0.5f, k.text, k.bold), k.gap(10), e, k.gap(12),
            k.button(T("Next"), primary = true) {
                d?.dismiss()
                if (e.text.isNotBlank()) askCode(e.text.toString().trim(), e.text.toString().trim())
            }))
        d.show()
    }

    /** The code the TV shows, then connect. */
    private fun askCode(address: String, name: String) {
        val e = k.input("", "000000", number = true, ltr = true) {}
        val msg = k.muted("", small = true)
        var d: android.app.Dialog? = null
        d = k.dialog(k.card(18, k.text(T("Enter the code {name} shows", "name" to name), k.base + 0.5f, k.text, k.bold), k.gap(10), e, msg, k.gap(12),
            k.button(T("Connect"), primary = true) {
                val code = e.text.toString().filter { it.isDigit() }
                if (code.length != 6) {
                    msg.text = T("The code has 6 digits.")
                    return@button
                }
                msg.text = T("Connecting…")
                h.io {
                    val r = App.core.pairTv(address, code)
                    h.ui {
                        msg.text = when (r) {
                            PairResult.OK -> T("Connected")
                            PairResult.WRONG_CODE -> T("That isn't the code the TV shows.")
                            PairResult.NEW_CODE -> T("Too many wrong codes: the TV shows a new one.")
                            PairResult.NOT_SHARING -> T("This phone isn't on a home network: connect it to the same Wi-Fi as the TV.")
                            PairResult.UNREACHABLE -> T("The TV didn't answer. Is VI Translator still open on it?")
                        }
                        if (r == PairResult.OK) {
                            d?.dismiss()
                            tvFound.removeAllViews()
                            listTvs()
                        }
                    }
                }
            }))
        d.show()
    }
}

// ------------------------------------------------------------------------------------- Snapshot

/** Snapshot: the whole picture translated at once, like a translated photo; and its service. */
class SnapshotPanel(h: Host) : Panel(h) {
    private val status = k.callout()

    override fun refresh() {
        view.removeAllViews()
        add(k.h3(T("Snapshot")))
        add(k.muted(T("Translates all the text on the game's picture at once and shows it over the picture, like a translated photo: menus, the map, the phone, a car's stats.")))
        add(k.button(T("Translate the whole picture"), primary = true, icon = R.drawable.ic_camera) { take() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(12) }
        })
        status.visibility = View.GONE
        add(status.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(8) } })
        add(k.h3(T("Snapshot service")))
        form()
    }

    private fun take() {
        status.visibility = View.VISIBLE
        status.setTextColor(k.text)
        status.text = T("Translating the whole picture…")
        Snaps.take(h) { snap, err ->
            if (snap != null) {
                status.text = Snaps.got(h, snap)
            } else {
                status.text = T("Could not translate the picture: {e}", "e" to (err ?: ""))
                status.setTextColor(k.err)
            }
        }
    }

    private fun form() {
        val live = TranslatorPanel.ENGINES.firstOrNull { it[0] == cfg.str("engine") }
        val opts = mutableListOf("" to T("Same as live translations ({name})", "name" to (live?.let { T(it[1]) } ?: cfg.str("engine"))))
        for (e in TranslatorPanel.ENGINES) {
            if (e[0] == "local") continue
            opts += e[0] to if (e[0] == "claude" || e[0] == "openai") T("{name} — reads the picture", "name" to T(e[1])) else T(e[1])
        }
        opts += "gemini-image" to T("Gemini (Nano Banana) — redraws the picture in the language")
        opts += "azure-image" to T("Azure image translation — picture in, picture out")
        val svc = cfg.str("snap_service")
        val used = svc.ifEmpty { cfg.str("engine") }
        val help = when (used) {
            "gemini-image" -> T("Google's image model redraws the whole picture with its text in the language: the most natural look. It can change small details (an icon, a number, a car), takes 10–30 s and Google bills about $0.03–0.13 per picture. Needs a Gemini API key from Google AI Studio.")
            "azure-image" -> T("Azure redraws the picture with the translation. Needs an Azure Translator resource with its own endpoint (https://<name>.cognitiveservices.azure.com) and its key. Billed by Azure per picture.")
            "claude", "openai" -> T("The picture and the text the app found go to the model in one request: it fixes misread words and translates with the whole screen as context. Uses the key and model from the Translator tab.")
            else -> T("Each piece of text is translated on its own, the translation memory first, so the game's words match the subtitles.")
        }
        add(k.field(T("Service"), k.select(opts, svc) {
            cfg.put("snap_service", it)
            refresh()
        }, help))
        if (used == "azure-image") {
            add(k.field(T("Azure endpoint"), k.input(cfg.str("snap_azure_endpoint"), "https://<name>.cognitiveservices.azure.com", ltr = true) { cfg.put("snap_azure_endpoint", it.trim()) }))
            add(k.field(T("Azure key"), k.input(cfg.str("snap_azure_key"), password = true, ltr = true) { cfg.put("snap_azure_key", it.trim()) }))
        }
        if (used == "gemini-image") {
            add(k.field(T("Gemini API key"), k.input(cfg.str("snap_gemini_key"), password = true, ltr = true) { cfg.put("snap_gemini_key", it.trim()) }))
            val models = listOf(
                "gemini-nano-banana-2.1" to T("Nano Banana 2.1 — recommended, about $0.05 a picture"),
                "gemini-3-pro-image" to T("Nano Banana Pro — best text, about $0.13 a picture"),
                "gemini-3.1-flash-lite-image" to T("Nano Banana 2 Lite — fastest, about $0.03 a picture"),
            )
            add(k.field(T("Model"), k.select(models, cfg.str("snap_gemini_model", models[0].first)) { cfg.put("snap_gemini_model", it) }))
        }
        add(k.field(T("Behind the translation"), k.select(listOf("blur" to T("The picture, blurred"), "solid" to T("A solid color")), if (cfg.str("snap_style") == "solid") "solid" else "blur") { cfg.put("snap_style", it) }))
        add(k.check(T("Keep every snapshot in the phone's Pictures, in VI Translator"), cfg.bool("snap_save", true)) { cfg.put("snap_save", it) })
        add(k.check(T("Allow snapshots from the home-network page"), cfg.bool("snap_lan", true)) { cfg.put("snap_lan", it) })
        add(k.muted(T("Phones and tablets showing the live translations get a snapshot button too."), small = true))
        add(k.muted(T("Text a game page marks as private (other players' names) is never read or sent: it's covered in the picture."), small = true).apply { setPadding(0, k.dp(10), 0, 0) })
    }
}

// ------------------------------------------------------------------------------------- Translator

/** Translator: the language, the translation service (with its keys), and the translation memory. */
class TranslatorPanel(h: Host) : Panel(h) {
    private val memStats = k.callout()

    override fun refresh() {
        view.removeAllViews()
        add(k.h3(T("Language")))
        val langs = App.core.languages()
        add(k.select(langs.map { it.code to if (it.english.isEmpty() || it.english == it.name) it.name else "${it.english} — ${it.name}" }, cfg.str("target_lang", "ar"), T("Language")) {
            cfg.put("target_lang", it, now = true)
            refresh()
        })
        add(k.muted(T("The phone app has no free offline model: lines the translation memory knows (reviewed translations from vitranslator.app) are free; for the others choose DeepL, Azure, Google, LibreTranslate, Claude or ChatGPT."), small = true).apply { setPadding(0, k.dp(6), 0, 0) })

        add(k.h3(T("Translation service")))
        val engine = cfg.str("engine", "local")
        for (e in ENGINES) {
            val on = engine == e[0]
            val name = if (e[0] == "local") T("Translation memory only") else T(e[1])
            val badge = if (e[0] == "local") T("Free") else T(e[2])
            val about = if (e[0] == "local") T("Only lines the translation memory and the reviewed translations know are shown. No account needed.") else T(e[3])
            add(k.column(
                k.row(k.grow(k.text(name, k.base, k.text, k.bold)), k.badge(badge, on)),
                k.muted(about, small = true).apply { setPadding(0, k.dp(4), 0, 0) },
            ).apply {
                background = k.round(if (on) k.accentSoft else k.panel, 12, if (on) k.accentUi else k.line, if (on) 1.5 else 1)
                setPadding(k.dp(14), k.dp(10), k.dp(14), k.dp(10))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(8) }
                setOnClickListener {
                    cfg.put("engine", e[0])
                    refresh()
                }
            })
        }
        // the service's fields (the Arabic variety only for Arabic)
        for (f in FIELDS[engine] ?: emptyList()) {
            if (f.key == "dialect" && cfg.str("target_lang", "ar") != "ar") continue
            val input = if (f.options != null) {
                k.select(f.options.map { it.first to T(it.second) }, cfg.str(f.key, f.options[0].first)) { cfg.put(f.key, it) }
            } else {
                k.input(cfg.str(f.key), password = f.password, ltr = true) { cfg.put(f.key, it.trim()) }
            }
            add(k.field(T(f.label), input, f.help?.let { T(it) }))
        }
        add(k.muted(T("Keys are saved only on this phone, in the app's settings."), small = true).apply { setPadding(0, k.dp(10), 0, 0) })

        add(k.h3(T("Translation memory")))
        add(k.muted(T("Remembers every translated line and reuses it instantly the next time it appears — no translator call, and no waiting for the line to settle."), small = true))
        add(memStats.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(8) } })
        add(k.button(T("Clear memory"), danger = true) {
            k.ask(T("Forget all remembered translations?"), T("Clear memory")) {
                h.io {
                    val ok = App.core.memoryClear()
                    h.ui { if (!ok) memStats.text = T("Not in the phone app yet: coming with an update.") else tick() }
                }
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = k.dp(10) } })
        tick()
    }

    /** the memory's numbers, every 2 s while shown (as the desktop) */
    private var ticks = 0

    override fun tick() {
        if (ticks++ % 2 != 0) return
        h.io {
            val m = try {
                JSONObject(App.core.memoryStatsJson())
            } catch (e: Exception) {
                JSONObject()
            }
            val p = try {
                JSONObject(App.core.perfJson())
            } catch (e: Exception) {
                JSONObject()
            }
            h.ui { memStats.text = memoryText(m, p) }
        }
    }

    private fun memoryText(m: JSONObject, p: JSONObject): String {
        val lines = mutableListOf(T("{n} remembered line(s)", "n" to m.optInt("entries")))
        val hits = m.optInt("hits")
        val misses = m.optInt("misses")
        if (hits + misses > 0) {
            lines += T("This session: {hits} from memory, {misses} translated ({rate}% hits)", "hits" to hits, "misses" to misses, "rate" to Math.round(100.0 * hits / (hits + misses)))
            lines += T("Translation ready after the English appeared: memory {a} · translator {b}",
                "a" to (if (hits > 0) "${Math.round(m.optDouble("avg_hit_ms"))} ms" else "—"), "b" to (if (misses > 0) "${Math.round(m.optDouble("avg_miss_ms"))} ms" else "—"))
        } else {
            lines += T("No lines seen yet this session.")
        }
        p.optJSONObject("read")?.let { read ->
            fun ms(x: JSONObject?) = if (x != null) "${x.optInt("avg")} ms" else "—"
            lines += T("Last minute: reading {read} per pass · translating {tr} · translation ready {ready} after the English (slowest {max})",
                "read" to ms(read), "tr" to ms(p.optJSONObject("translate")), "ready" to ms(p.optJSONObject("ready")),
                "max" to (p.optJSONObject("ready")?.let { "${it.optInt("max")} ms" } ?: "—"))
            lines += T("Running for {m} min", "m" to p.optInt("minutes"))
        }
        return lines.joinToString("\n")
    }

    data class Field(val key: String, val label: String, val password: Boolean = false, val help: String? = null, val options: List<Pair<String, String>>? = null)

    companion object {
        /** the desktop's ENGINES: id, name, badge, description */
        val ENGINES = listOf(
            listOf("local", "Local (offline)", "Free", "Runs on this PC. No account or internet after a one-time ~110 MB download per language. Basic, literal quality. Not every language has one."),
            listOf("deepl", "DeepL", "Free tier", "Very good quality. The free API plan includes 500,000 characters a month."),
            listOf("azure", "Microsoft Azure Translator", "Free tier", "Good quality. The free tier includes 2 million characters a month."),
            listOf("google", "Google Cloud Translation", "Free tier", "Good quality. Around 500,000 free characters a month."),
            listOf("libre", "LibreTranslate", "Free / self-hosted", "Open source. Use your own server or a hosted one."),
            listOf("claude", "Claude (Anthropic)", "≈ $1 per game", "Best with slang, jokes and context. Can use a dialect. Paid per use."),
            listOf("openai", "ChatGPT (OpenAI)", "≈ $1 per game", "Natural translations with context, like Claude. Can use a dialect. Paid per use."),
        )

        private val DIALECTS = listOf("msa" to "Modern Standard (الفصحى)", "egyptian" to "Egyptian (مصري)", "gulf" to "Gulf (خليجي)", "levantine" to "Levantine (شامي)")

        /** the desktop's ENGINE_FIELDS */
        val FIELDS = mapOf(
            "deepl" to listOf(Field("deepl_key", "DeepL API key", true, "Free keys end in :fx")),
            "azure" to listOf(Field("azure_key", "Azure Translator key", true), Field("azure_region", "Region (e.g. westeurope)", help = "Leave empty for a global resource")),
            "google" to listOf(Field("google_key", "Google Cloud API key", true)),
            "libre" to listOf(Field("libre_url", "Server URL"), Field("libre_key", "API key (if the server needs one)", true)),
            "claude" to listOf(
                Field("claude_key", "Anthropic API key", true),
                Field("claude_model", "Model", options = listOf("claude-haiku-5-5" to "Claude Haiku 5.5 — cheapest, fast", "claude-sonnet-5-5" to "Claude Sonnet 5.5 — better", "claude-opus-5-5" to "Claude Opus 5.5 — best, ~40× Haiku's price")),
                Field("dialect", "Arabic variety", options = DIALECTS),
            ),
            "openai" to listOf(
                Field("openai_key", "OpenAI API key", true),
                Field("openai_model", "Model", options = listOf("gpt-6-luna" to "GPT-6 Luna — cheapest, fast", "gpt-6.1-terra" to "GPT-6.1 Terra — better", "gpt-6.2-sol" to "GPT-6.2 Sol — best, ~20× Luna's price")),
                Field("dialect", "Arabic variety", options = DIALECTS),
            ),
        )

        fun engineName(id: String) = if (id == "local") T("Translation memory only") else ENGINES.firstOrNull { it[0] == id }?.let { T(it[1]) } ?: id
    }
}
