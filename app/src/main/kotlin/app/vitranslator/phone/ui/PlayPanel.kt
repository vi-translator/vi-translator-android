package app.vitranslator.phone.ui

import android.app.Dialog
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.vitranslator.phone.App
import app.vitranslator.phone.Prefs
import app.vitranslator.phone.R
import app.vitranslator.phone.core.Line
import app.vitranslator.phone.ps.Ps5
import app.vitranslator.phone.ps.chiaki.Chiaki
import app.vitranslator.phone.run.Live
import app.vitranslator.phone.run.TranslateService
import app.vitranslator.phone.xbox.XboxActivity

/**
 * Play, as on the desktop: start (the phone's own step: it connects to the console), the picture with
 * the read areas and their translations, what is running at a glance (picture source, language,
 * read areas, each with Change), and the live translations.
 */
class PlayPanel(h: Host) : Panel(h) {
    private val start: TextView
    private val startNote: TextView
    private val preview = PreviewView(k, cfg)
    private val stageMsg: LinearLayout
    private val stats: TextView

    /**
     * The translations over the picture as the TVs draw them: the core's own TV page, loaded from
     * this phone, transparent (game pages included). Touches go through to the picture.
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private val tvPage = android.webkit.WebView(h.act).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        isClickable = false
        isFocusable = false
        visibility = View.GONE
    }
    private var overlayUrl: String? = null
    private val glance = k.column()
    /** where the translations are shown, each switched on or off at once (the desktop's Play "Show on" card) */
    private val outputs = k.column()
    private var tvs: List<app.vitranslator.phone.core.Tv> = emptyList()
    private var ticks = 0
    private var allOffShown = false
    private val log = k.column()
    private val paused: TextView
    private var langs = App.core.languages()

    /** lines older than this were cleared from the list (clear) */
    private var clearedAt = 0L

    /** the live translations full screen, while open */
    private var liveFull: LiveFull? = null

    init {
        // ---- start / stop
        start = k.button(T("Start translating"), primary = true, icon = R.drawable.ic_play) { startOrStop() }
        startNote = k.muted("", small = true).apply { setPadding(0, k.dp(6), 0, 0) }
        add(k.card(14, start, startNote))

        // ---- the picture
        stageMsg = k.column().apply { gravity = Gravity.CENTER }
        stats = k.text("", k.base - 2f, Color.WHITE, k.bold).apply {
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setPadding(k.dp(8), k.dp(6), k.dp(8), k.dp(6))
        }
        val buttons = k.row(
            k.iconButton(R.drawable.ic_camera, T("Translate the whole picture")) { snapshot() },
            k.iconButton(R.drawable.ic_fullscreen, T("Full screen")) { fullScreen() },
            gap = 8,
        )
        val stage = FrameLayout(h.act).apply {
            background = k.round(k.stage, 14, k.line)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            addView(preview, FrameLayout.LayoutParams(-1, -2))
            addView(tvPage, FrameLayout.LayoutParams(-1, -1))
            addView(stageMsg, FrameLayout.LayoutParams(-1, -1))
            addView(stats, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            addView(buttons, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(k.dp(10), k.dp(10), k.dp(10), k.dp(10)) })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(12) }
        }
        preview.tapped = { id -> preview.selected = id }
        add(stage)

        // ---- at a glance
        add(glance.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(4) } })
        add(outputs.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(10) } })

        // ---- live translations
        paused = k.badge(T("Paused"))
        val head = k.row(
            View(h.act).apply {
                background = k.round(k.neon, 999)
                layoutParams = LinearLayout.LayoutParams(k.dp(7), k.dp(7))
            },
            k.label(T("Live translations")),
            paused,
            k.grow(View(h.act)),
            k.link(T("clear")) {
                clearedAt = App.core.lines(1).lastOrNull()?.id ?: 0L
                lines()
            },
            k.iconButton(R.drawable.ic_fullscreen, T("Live translations full screen")) { liveFullScreen() },
        )
        add(k.card(14, head, k.gap(6), log).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(12) } })
    }

    override fun refresh() {
        langs = App.core.languages()
        if (overlayUrl == null) h.io {
            val url = App.core.localViewerUrl()
            if (url != null) h.ui {
                overlayUrl = url
                tvPage.loadUrl(url)
                tvPage.visibility = View.VISIBLE
                // (the TV page draws the translations: the preview's own drawing of them stops)
                preview.drawText = false
            }
        }
        // (without the core's TV page, the preview draws the translations itself: this phone's overlay switch too)
        if (overlayUrl == null) preview.drawText = cfg.bool("device_overlays", true)
        val running = Live.state in listOf(Live.State.STARTING, Live.State.CONNECTING, Live.State.READING)
        start.text = if (running) T("Stop") else T("Start translating")
        start.background = if (running) k.round(k.panel2, 9, k.line) else k.sunset(9)
        start.setTextColor(if (running) k.text else Color.WHITE)
        start.setCompoundDrawablesRelativeWithIntrinsicBounds(if (running) null else k.tinted(R.drawable.ic_play, Color.WHITE, 16), null, null, null)
        val console = Prefs(h.act).console
        startNote.text = when {
            console == "xbox" && Prefs(h.act).xboxWebPlayer -> T("Xbox: Microsoft's Remote Play player opens; keep the phone's screen on while playing.")
            console == "xbox" -> T("Keep VI Translator open while playing (with an Xbox the screen can be off).")
            console == "ps5" -> T("Keep VI Translator open while playing (with a PS5 the screen can be off).")
            console == "card" -> T("Keep the capture card plugged into the phone while playing (the screen can be off).")
            else -> T("Choose your console in Source first.")
        }
        val rtl = langs.firstOrNull { it.code == cfg.str("target_lang", "ar") }?.rtl ?: true
        preview.rtl = rtl
        stageMessage()
        glance()
        listTvs()
        paused.visibility = if (cfg.bool("live_translations", true)) View.GONE else View.VISIBLE
        lines()
    }

    private fun stageMessage() {
        stageMsg.removeAllViews()
        val on = Prefs(h.act).preview
        stageMsg.visibility = View.VISIBLE
        when {
            !on -> {
                stageMsg.addView(k.icon(R.drawable.ic_eye_off, k.muted, 28))
                stageMsg.addView(k.text(T("Preview is off"), k.base + 1f, Color.WHITE, k.bold).apply { gravity = Gravity.CENTER; setPadding(0, k.dp(6), 0, 0) })
                stageMsg.addView(k.text(T("Translations keep working. Turn it on to see the game here."), k.base - 1.5f, 0xffa193b5.toInt()).apply { gravity = Gravity.CENTER })
                stageMsg.addView(k.button(T("Turn on preview")) {
                    Prefs(h.act).preview = true
                    h.rerender()
                }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = k.dp(8) } })
                stageMsg.setBackgroundColor(k.stage)
            }
            preview.picture == null -> {
                stageMsg.addView(k.text(T("No picture yet"), k.base + 1f, Color.WHITE, k.bold).apply { gravity = Gravity.CENTER })
                val msg = if (Live.state == Live.State.IDLE || Live.state == Live.State.ENDED || Live.state == Live.State.FAILED)
                    T("Press Start translating: the picture shows here once the console is connected.")
                else T("Waiting for the console's picture…")
                stageMsg.addView(k.text(msg, k.base - 1.5f, 0xffa193b5.toInt()).apply { gravity = Gravity.CENTER; setPadding(k.dp(20), k.dp(4), k.dp(20), 0) })
                stageMsg.background = null
            }
            else -> stageMsg.visibility = View.GONE
        }
    }

    fun picture(b: Bitmap) {
        val had = preview.picture != null
        preview.picture = b
        if (!had) stageMessage()
    }

    override fun tick() {
        // (the TVs' online dots: asked again every few seconds)
        if (++ticks % 3 == 0) listTvs()
        val r = App.reader
        stats.text = if (r != null && Live.state == Live.State.READING) T("{n} read · {ms} ms", "n" to r.read, "ms" to r.lastMs) else ""
        stats.visibility = if (stats.text.isEmpty() || !cfg.bool("fps_badge", false)) View.GONE else View.VISIBLE
    }

    // ---- what is running, at a glance (the desktop's .glance cards)
    private fun glance() {
        glance.removeAllViews()
        val console = Prefs(h.act).console
        val ps = Ps5.saved(h.act)
        val source = when (console) {
            "ps5" -> if (ps != null) "PlayStation 5 · ${ps.nickname.ifEmpty { ps.host }}" else T("PlayStation 5 (not linked yet)")
            "xbox" -> "Xbox"
            "card" -> T("Capture card")
            else -> T("Not chosen yet")
        }
        val sourceNote = when {
            console == "ps5" && !Chiaki.available -> T("PS Remote Play isn't in this build of the app.")
            Live.state == Live.State.READING -> T("Reading the game")
            else -> ""
        }
        glance.addView(glanceCard(R.drawable.ic_source, T("Picture source"), source, sourceNote) { h.go("source") })
        val lang = langs.firstOrNull { it.code == cfg.str("target_lang", "ar") }
        val engine = TranslatorPanel.engineName(cfg.str("engine", "local"))
        glance.addView(glanceCard(R.drawable.ic_translator, T("Language"), lang?.name ?: cfg.str("target_lang"), engine) { h.go("translator") })
        val areas = cfg.areas()
        val names = (0 until areas.length()).map { areas.getJSONObject(it) }.filter { it.optBoolean("enabled", true) }.joinToString(", ") { it.optString("name") }
        glance.addView(glanceCard(R.drawable.ic_areas, T("Read areas"), names.ifEmpty { "—" }, cfg.activeProfile()?.optString("name") ?: "") { h.go("areas") })
    }

    // ---- Show on: this phone's overlay and each connected TV, each on or off at once while translating
    private fun listTvs() {
        h.io {
            val list = try { App.core.pairedTvs() } catch (e: Exception) { emptyList() }
            h.ui {
                if (list != tvs || outputs.childCount == 0 || allOffShown != !cfg.bool("overlays_enabled", true)) {
                    tvs = list
                    outputsCard()
                }
            }
        }
    }

    private fun outputsCard() {
        outputs.removeAllViews()
        val ic = k.icon(R.drawable.ic_show, k.accentUi, 20).apply {
            background = k.round(k.accentSoft, 10)
            setPadding(k.dp(8), k.dp(8), k.dp(8), k.dp(8))
            layoutParams = LinearLayout.LayoutParams(k.dp(38), k.dp(38))
            scaleType = android.widget.ImageView.ScaleType.CENTER
        }
        val body = k.column(k.label(T("Show on")))
        body.addView(k.switch(T("Overlays on this phone"), cfg.bool("device_overlays", true), small = true) {
            cfg.put("device_overlays", it, now = true)
        })
        for (tv in tvs) {
            body.addView(k.switch("📺 " + tv.name + if (tv.online == true) "  ●" else "", tv.on, small = true) { on ->
                tvs = tvs.map { t -> if (t.id == tv.id) t.copy(on = on) else t }
                h.io { App.core.setTvOn(tv.id, on) }
            })
        }
        val allOff = !cfg.bool("overlays_enabled", true)
        allOffShown = allOff
        body.addView(k.muted(
            if (allOff) T("Overlays are off for everything: turn on Overlays at the top.") else T("Turn each one on or off at any time, even while translating."),
            small = true,
        ).apply { if (allOff) setTextColor(k.warn) })
        outputs.addView(k.row(ic, k.grow(body), k.link(T("Change")) { h.go("show") }, gap = 12).apply {
            background = k.round(k.panel, 14, k.line)
            setPadding(k.dp(12), k.dp(12), k.dp(8), k.dp(12))
            gravity = android.view.Gravity.TOP
        })
    }

    private fun glanceCard(icon: Int, key: String, value: String, note: String, change: () -> Unit): View {
        val ic = k.icon(icon, k.accentUi, 20).apply {
            background = k.round(k.accentSoft, 10)
            setPadding(k.dp(8), k.dp(8), k.dp(8), k.dp(8))
            layoutParams = LinearLayout.LayoutParams(k.dp(38), k.dp(38))
            scaleType = android.widget.ImageView.ScaleType.CENTER
        }
        val bodyCol = k.column(k.label(key), k.text(value, k.base, k.text, k.bold).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
        if (note.isNotEmpty()) bodyCol.addView(k.muted(note, small = true))
        return k.row(ic, k.grow(bodyCol), k.link(T("Change"), change), gap = 12).apply {
            background = k.round(k.panel, 14, k.line)
            setPadding(k.dp(12), k.dp(12), k.dp(8), k.dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(10) }
            setOnClickListener { change() }
        }
    }

    // ---- the live translations: newest first, the English small above its translation
    override fun lines() {
        val all = App.core.lines(40)
        val ls = all.filter { it.id > clearedAt }.asReversed()
        val rtl = langs.firstOrNull { it.code == cfg.str("target_lang", "ar") }?.rtl ?: true
        // the latest of each area, drawn on the preview
        preview.shown = all.groupBy { it.areaId }.mapValues { (_, v) -> v.last().translated }.filterValues { it.isNotEmpty() }
        liveFull?.update()
        log.removeAllViews()
        if (ls.isEmpty()) {
            log.addView(k.muted(T("Translations appear here as the game shows text."), small = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, k.dp(14), 0, k.dp(14))
            })
            return
        }
        for (l in ls.take(30)) log.addView(lineView(l, rtl))
    }

    private fun lineView(l: Line, rtl: Boolean) = k.column(
        k.text(l.original, k.base - 1f, k.muted).apply { textDirection = View.TEXT_DIRECTION_LTR },
        k.text(l.translated.ifEmpty { "…" }, k.base + 2.5f, k.text, if (rtl) k.font("Tajawal-Bold.ttf") else k.bold).apply {
            textDirection = if (rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        },
    ).apply {
        setPadding(0, k.dp(8), 0, k.dp(8))
        background = k.round(Color.TRANSPARENT, 0)
    }

    private fun snapshot() {
        Snaps.take(h) { snap, err ->
            if (snap != null) Snaps.got(h, snap)
            else android.widget.Toast.makeText(h.act, T("Could not translate the picture: {e}", "e" to (err ?: "")), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** The live translations full screen, upright or sideways, with the text size to choose (LiveFull). */
    private fun liveFullScreen() {
        val rtl = langs.firstOrNull { it.code == cfg.str("target_lang", "ar") }?.rtl ?: true
        val f = LiveFull(h.act, k, rtl) { App.core.lines(LiveFull.MAX_LINES).filter { it.id > clearedAt } }
        f.onClose = { liveFull = null }
        liveFull = f
        f.show()
    }

    override fun close() {
        tvPage.destroy()
    }

    // ---- start / stop (the phone connects to the console; the desktop reads a window)
    private fun startOrStop() {
        val running = Live.state in listOf(Live.State.STARTING, Live.State.CONNECTING, Live.State.READING)
        if (running) {
            TranslateService.stop(h.act)
            return
        }
        when (Prefs(h.act).console) {
            "ps5" -> TranslateService.start(h.act, TranslateService.Source.PS5)
            "xbox" -> if (Prefs(h.act).xboxWebPlayer) XboxActivity.open(h.act) else TranslateService.start(h.act, TranslateService.Source.XBOX)
            // (the card is a camera to Android: its permission first, then MainActivity starts it)
            "card" -> if (h.act.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                TranslateService.start(h.act, TranslateService.Source.CARD)
            } else {
                h.act.requestPermissions(arrayOf(android.Manifest.permission.CAMERA), MainActivity.ASK_CAMERA)
            }
            else -> h.go("source")
        }
    }

    /** The picture full screen, sideways (the desktop's full-screen preview). */
    private fun fullScreen() {
        val d = Dialog(h.act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val big = PreviewView(k, cfg).apply {
            picture = preview.picture
            shown = preview.shown
            rtl = preview.rtl
        }
        val keep = App.reader?.preview
        App.reader?.preview = { b -> h.ui { big.picture = b; picture(b) } }
        d.setContentView(FrameLayout(h.act).apply {
            setBackgroundColor(Color.BLACK)
            addView(big, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            setOnClickListener { d.dismiss() }
        })
        val before = h.act.requestedOrientation
        h.act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        d.setOnDismissListener {
            h.act.requestedOrientation = before
            App.reader?.preview = keep
        }
        d.show()
    }
}
