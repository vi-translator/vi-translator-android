package app.vitranslator.phone.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.vitranslator.phone.App
import app.vitranslator.phone.Lang
import app.vitranslator.phone.Prefs
import app.vitranslator.phone.R
import app.vitranslator.phone.Updater
import app.vitranslator.phone.core.CoreListener
import app.vitranslator.phone.run.Live
import app.vitranslator.phone.run.TranslateService
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The app, laid out as the desktop app for a phone: the header (who we are, Setup / Play, what's
 * happening, the quick switches, the app's language and light / dark), then either Play (the
 * picture, what is running, the live translations) or Setup (the desktop's tabs, in a strip).
 */
class MainActivity : Activity(), Live.Watcher, CoreListener, Host {
    override lateinit var k: Kit
    override val cfg = Cfg(App.core)
    override val act: Activity get() = this
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()

    private lateinit var body: FrameLayout
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var savedLabel: TextView
    private lateinit var modeSetup: TextView
    private lateinit var modePlay: TextView
    private lateinit var updateBar: LinearLayout
    private var mode = "play"
    private var tab = "source"
    private var panel: Panel? = null
    private var coreStatus: String? = null
    private var loaded = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        mode = savedInstanceState?.getString("mode") ?: prefs.lastMode
        tab = savedInstanceState?.getString("tab") ?: "source"
        T.load(this, prefs.uiLanguage.ifEmpty { "en" })
        k = Kit(this, prefs.dark)
        window.decorView.setBackgroundColor(k.bg)
        setContentView(FrameLayout(this).apply { setBackgroundColor(k.bg) })
        // the settings come from the core (its files): a moment off the main thread, then the screen
        io {
            val json = try {
                cfg.loadBlocking()
            } catch (e: Exception) {
                org.json.JSONObject()
            }
            ui {
                cfg.set(json)
                // the interface language and the theme are the desktop's settings; the phone's language picks the first
                var lang = cfg.str("ui_lang")
                if (lang.isEmpty()) {
                    T.load(this, "en")
                    lang = T.fromLocale(Locale.getDefault())
                    cfg.put("ui_lang", lang)
                }
                val dark = cfg.str("theme", "dark") != "light"
                if (lang != prefs.uiLanguage || dark != prefs.dark) {
                    prefs.uiLanguage = lang
                    prefs.dark = dark
                    cfg.flush()
                    recreate()
                    return@ui
                }
                loaded = true
                build()
                debugOptions(intent)
                offerCrash()
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != ASK_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            TranslateService.start(this, TranslateService.Source.CARD)
        } else {
            android.widget.Toast.makeText(this, T("The capture card needs the camera permission: Android lists capture cards as cameras."), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("mode", mode)
        outState.putString("tab", tab)
    }

    // ---- the frame of the screen

    private fun build() {
        cfg.saved = { ok ->
            savedLabel.text = if (ok) T("Saved ✓") else T("Save failed")
            main.removeCallbacks(clearSaved)
            main.postDelayed(clearSaved, 1500)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(k.bg)
            layoutDirection = if (T.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        root.addView(header())
        updateBar = k.row().apply {
            background = k.round(k.accentSoft, 0)
            setPadding(k.dp(16), k.dp(8), k.dp(16), k.dp(8))
            visibility = View.GONE
        }
        root.addView(updateBar)
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.clearOfSystemBars()
        setContentView(root)
        window.statusBarColor = k.panel
        window.navigationBarColor = k.bg
        @Suppress("DEPRECATION")
        if (!k.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        showMode()
        changed()
        showUpdate()
    }

    private val clearSaved = Runnable { savedLabel.text = "" }

    private fun header(): View {
        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = k.round(Color.TRANSPARENT, 10)
            layoutParams = LinearLayout.LayoutParams(k.dp(36), k.dp(36))
        }
        val version = k.text("v" + Updater.current(this), k.base - 2.5f, k.muted, k.bold).apply { textDirection = View.TEXT_DIRECTION_LTR }
        val brand = k.column(k.text("VI Translator", k.base + 1.5f, k.text, k.heavy), version)
        savedLabel = k.text("", k.base - 2.5f, k.muted)
        val globe = k.iconButton(R.drawable.ic_globe, T("App language")) { pickLanguage() }
        val theme = k.iconButton(if (k.dark) R.drawable.ic_sun else R.drawable.ic_moon, T("Switch between light and dark")) {
            cfg.put("theme", if (k.dark) "light" else "dark", now = true)
            Prefs(this).dark = !k.dark
            recreate()
        }
        val top = k.row(logo, brand, k.grow(View(this)), savedLabel, globe, theme, gap = 10)

        modeSetup = modeButton(T("Setup"), R.drawable.ic_setup) { setMode("setup") }
        modePlay = modeButton(T("Play"), R.drawable.ic_play) { setMode("play") }
        val modes = k.row(modeSetup, modePlay, gap = 4).apply {
            background = k.round(k.panel2, 999)
            setPadding(k.dp(4), k.dp(4), k.dp(4), k.dp(4))
        }
        statusDot = View(this).apply {
            background = k.round(k.muted, 999)
            layoutParams = LinearLayout.LayoutParams(k.dp(7), k.dp(7))
        }
        status = k.text(T("Starting…"), k.base - 1f, k.muted, k.bold).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val pill = k.row(statusDot, k.grow(status), gap = 7).apply {
            background = k.round(k.panel2, 999, k.line)
            setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12))
        }
        val second = k.row(modes, k.grow(pill), gap = 10)

        // the quick switches (the desktop header's chips)
        val chips = k.row(
            chip(T("Overlays"), cfg.bool("overlays_enabled", true)) { cfg.put("overlays_enabled", it) },
            chip(T("Live translations"), cfg.bool("live_translations", true)) {
                cfg.put("live_translations", it)
                panel?.refresh()
            },
            chip(T("Preview"), Prefs(this).preview) {
                Prefs(this).preview = it
                panel?.refresh()
                wirePreview()
            },
            gap = 8,
        )
        val box = k.column(top, k.gap(10), second, k.gap(10), k.hscroll(chips)).apply {
            setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(12))
            setBackgroundColor(k.panel)
        }
        // a thin sunset line under the header, like the logo's horizon
        val horizon = View(this).apply {
            background = k.sunset(0)
            alpha = 0.85f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, k.dp(2))
        }
        return k.column(box, horizon)
    }

    private fun modeButton(label: String, icon: Int, click: () -> Unit) = k.text(label, k.base, k.muted, k.bold).apply {
        gravity = Gravity.CENTER
        minHeight = k.dp(40)
        setPadding(k.dp(16), k.dp(8), k.dp(18), k.dp(8))
        compoundDrawablePadding = k.dp(6)
        tag = icon
        setOnClickListener { click() }
    }

    private fun chip(label: String, on: Boolean, changed: (Boolean) -> Unit) = k.switch(label, on, small = true, changed = changed).apply {
        background = k.round(k.panel2, 999, k.line)
        setPadding(k.dp(14), k.dp(2), k.dp(9), k.dp(2))
    }

    private fun setMode(m: String) {
        if (m == mode) return
        mode = m
        Prefs(this).lastMode = m
        showMode()
    }

    private fun paintMode(v: TextView, on: Boolean) {
        v.background = if (on) k.sunset(999) else null
        v.setTextColor(if (on) Color.WHITE else k.muted)
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(k.tinted(v.tag as Int, if (on) Color.WHITE else k.muted, 15), null, null, null)
    }

    private fun showMode() {
        paintMode(modeSetup, mode == "setup")
        paintMode(modePlay, mode == "play")
        body.removeAllViews()
        panel?.close()
        App.reader?.preview = null
        if (mode == "play") {
            open(PlayPanel(this))
        } else {
            val content = FrameLayout(this)
            body.addView(k.column(tabs()).apply {
                addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
            openTab(tab, content)
        }
    }

    /** The desktop's rail: Source, Show on, Snapshot, Translator, then the profile and its parts. */
    private val tabList = listOf(
        Triple("source", "Source", R.drawable.ic_source),
        Triple("show", "Show on", R.drawable.ic_show),
        Triple("snapshot", "Snapshot", R.drawable.ic_snapshot),
        Triple("translator", "Translator", R.drawable.ic_translator),
        Triple("profile", "Game profile", R.drawable.ic_profile),
        Triple("areas", "Read areas", R.drawable.ic_areas),
        Triple("pages", "Game pages", R.drawable.ic_pages),
    )

    private fun tabs(): View {
        val strip = k.row(gap = 4).apply { setPadding(k.dp(10), k.dp(8), k.dp(10), k.dp(8)) }
        for ((id, label, icon) in tabList) {
            val on = id == tab
            // (no lock on the tabs, as on the desktop: a built-in profile's note says so inside)
            val t = k.text(T(label), k.base - 2f, if (on) k.accentUi else k.muted, k.bold).apply {
                gravity = Gravity.CENTER
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, k.tinted(icon, if (on) k.accentUi else k.muted, 22), null, null)
                compoundDrawablePadding = k.dp(4)
                setPadding(k.dp(12), k.dp(9), k.dp(12), k.dp(9))
                minWidth = k.dp(72)
                background = if (on) k.round(k.accentSoft, 10) else null
                setOnClickListener {
                    if (tab != id) {
                        tab = id
                        showMode()
                    }
                }
            }
            strip.addView(t)
        }
        val sv = k.hscroll(strip).apply { setBackgroundColor(k.panel) }
        // (the chosen tab in view)
        sv.post {
            val i = tabList.indexOfFirst { it.first == tab }
            strip.getChildAt(i)?.let { c -> sv.smoothScrollTo(c.left - k.dp(40), 0) }
        }
        return k.column(sv, View(this).apply {
            setBackgroundColor(k.line)
            layoutParams = LinearLayout.LayoutParams(-1, k.dp(1))
        })
    }

    private fun openTab(id: String, into: FrameLayout) {
        val p = when (id) {
            "show" -> ShowPanel(this)
            "snapshot" -> SnapshotPanel(this)
            "translator" -> TranslatorPanel(this)
            "profile" -> ProfilePanel(this)
            "areas" -> AreasPanel(this)
            "pages" -> PagesPanel(this)
            else -> SourcePanel(this)
        }
        panel = p
        p.view.setPadding(k.dp(16), k.dp(4), k.dp(16), k.dp(40))
        into.addView(k.scroll(p.view))
        p.refresh()
    }

    private fun open(p: Panel) {
        panel = p
        body.addView(k.scroll(p.view.apply { setPadding(k.dp(16), k.dp(14), k.dp(16), k.dp(40)) }), FrameLayout.LayoutParams(-1, -1))
        p.refresh()
        wirePreview()
    }

    // ---- Host
    override fun go(tab: String) {
        this.tab = tab
        mode = "setup"
        Prefs(this).lastMode = mode
        showMode()
    }

    override fun rerender() {
        if (mode == "setup") showMode() else panel?.refresh()
    }

    override fun io(block: () -> Unit) {
        pool.execute(block)
    }

    override fun ui(block: () -> Unit) {
        main.post(block)
    }

    override fun pickLanguage() {
        k.pick(T("App language"), T.langs.map { it.code to it.name }, T.code) { code ->
            cfg.put("ui_lang", code, now = true)
            Prefs(this).uiLanguage = code
            recreate()
        }
    }

    // ---- what's happening

    override fun onResume() {
        super.onResume()
        Live.watch(this)
        App.core.setListener(this)
        main.post(tick)
        Updater.check(this) { main.post { showUpdate() } }
        if (loaded) {
            changed()
            showUpdate()
            wirePreview()
        }
    }

    override fun onPause() {
        Live.unwatch(this)
        App.core.setListener(null)
        main.removeCallbacks(tick)
        App.reader?.preview = null
        cfg.flush()
        super.onPause()
    }

    override fun onDestroy() {
        panel?.close()
        super.onDestroy()
    }

    /** The preview gets frames while it is on screen and switched on. */
    private fun wirePreview() {
        val p = panel as? PlayPanel
        App.reader?.preview = if (p != null && Prefs(this).preview) { bmp: Bitmap -> main.post { p.picture(bmp) } } else null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (loaded) {
                panel?.tick()
                if (panel is PlayPanel && App.reader != null && App.reader?.preview == null && Prefs(this@MainActivity).preview) wirePreview()
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun changed() {
        if (!loaded) return
        val (txt, color) = when (Live.state) {
            Live.State.IDLE -> (coreStatus ?: T("Ready")) to k.muted
            Live.State.STARTING -> T("Starting…") to k.warn
            Live.State.CONNECTING -> T("Connecting to the PS5…") to k.warn
            Live.State.READING -> (if (Live.cantDisplay) T("The PS5 is showing something Remote Play can't stream") else coreStatus ?: T("Translating")) to k.ok
            Live.State.ENDED -> (T("Stopped") + (Live.detail?.let { " ($it)" } ?: "")) to k.muted
            Live.State.FAILED -> T("Stopped: {reason}", "reason" to (Live.detail ?: "")) to k.err
        }
        status.text = txt
        status.setTextColor(if (Live.state == Live.State.FAILED) k.err else k.text)
        statusDot.background = k.round(color, 999)
        if (Live.state == Live.State.READING) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        panel?.refresh()
    }

    override fun askPin(incorrect: Boolean) {
        val e = k.input("", T("Passcode"), password = true) {}
        var d: android.app.Dialog? = null
        d = k.dialog(
            k.card(
                18,
                k.text(T(if (incorrect) "Wrong passcode: try again" else "The PS5 asks for the account's passcode"), k.base + 0.5f),
                k.gap(12), e, k.gap(12),
                k.button(T("OK"), primary = true) {
                    TranslateService.instance?.pin(e.text.toString())
                    d?.dismiss()
                },
            ),
        )
        d.show()
    }

    override fun linesChanged() {
        main.post { if (loaded) panel?.lines() }
    }

    override fun status(message: String) {
        main.post {
            coreStatus = message.ifBlank { null }
            changed()
        }
    }

    private fun showUpdate() {
        if (!loaded) return
        val r = Updater.available
        updateBar.removeAllViews()
        updateBar.visibility = if (r != null) View.VISIBLE else View.GONE
        if (r == null) return
        val text = when (Updater.state) {
            Updater.State.DOWNLOADING -> T("Downloading the update…")
            Updater.State.INSTALLING -> T("Installing…")
            Updater.State.FAILED -> T("The update failed: try again")
            Updater.State.IDLE -> T("Version {v} is available.", "v" to r.version)
        }
        updateBar.addView(k.grow(k.text(text, k.base - 1f)))
        updateBar.addView(k.button(T("Update now"), primary = true) { Updater.install(this, r) { main.post { showUpdate() } } })
    }

    /** If the app ended with a crash last time: offer to copy the report (nothing is sent by itself). */
    private fun offerCrash() {
        val report = app.vitranslator.phone.Crashes.take(this) ?: return
        var d: android.app.Dialog? = null
        d = k.dialog(k.card(18,
            k.text(T("VI Translator closed unexpectedly last time"), k.base + 0.5f, k.text, k.bold),
            k.muted(T("Copy the details and send them to the developer, so the problem can be fixed."), small = true).apply { setPadding(0, k.dp(8), 0, 0) },
            k.gap(14),
            k.row(
                k.grow(k.button(T("Not now")) { d?.dismiss() }),
                k.grow(k.button(T("Copy the details"), primary = true) {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("VI Translator crash", report))
                    android.widget.Toast.makeText(this, T("Copied."), android.widget.Toast.LENGTH_SHORT).show()
                    d?.dismiss()
                }),
            ),
        ))
        d.show()
    }

    // ---- debug builds, for emulators: --es advertise 10.0.2.2:8791 (the stand-in core), --es fake_ps5 <address>, --es mode, --es tab
    private fun debugOptions(i: Intent?) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        i?.getStringExtra("advertise")?.let { a -> (App.core as? app.vitranslator.phone.core.StubCore)?.advertise = a }
        i?.getStringExtra("fake_ps5")?.let { host ->
            app.vitranslator.phone.ps.Ps5.save(this, app.vitranslator.phone.ps.PsConsole(host, "fake PS5", ByteArray(6), "0123abcd".toByteArray() + ByteArray(8), ByteArray(16)))
            panel?.refresh()
        }
        if (i?.hasExtra("ps5_steps") == true) Ps5Link.showSteps = i.getBooleanExtra("ps5_steps", false)
        i?.getStringExtra("mode")?.let { setMode(it) }
        i?.getStringExtra("tab")?.let { go(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (loaded) debugOptions(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        panel?.result(requestCode, resultCode, data)
    }

    companion object {
        /** the camera permission, asked for when translating from a capture card starts */
        const val ASK_CAMERA = 3
    }
}

/** What the panels get from the screen around them. */
interface Host {
    val k: Kit
    val cfg: Cfg
    val act: Activity

    /** to a Setup tab */
    fun go(tab: String)

    /** draw the panel (or the whole Setup) again */
    fun rerender()

    fun io(block: () -> Unit)
    fun ui(block: () -> Unit)
    fun pickLanguage()
}

/** A screen's content: its view, drawn again on [refresh]. */
abstract class Panel(val h: Host) {
    val k get() = h.k
    val cfg get() = h.cfg
    val view: LinearLayout = LinearLayout(h.act).apply { orientation = LinearLayout.VERTICAL }

    /** the settings or the state changed: show them */
    open fun refresh() {}

    /** every second while on screen */
    open fun tick() {}

    /** the lines read and translated changed */
    open fun lines() {}

    open fun result(requestCode: Int, resultCode: Int, data: Intent?) {}
    open fun close() {}

    fun add(v: View) = view.addView(v)

    /** a locked built-in profile's note, with "Make a copy" (the desktop's .lock-note) */
    fun lockNote(): View? {
        if (!cfg.locked()) return null
        val p = cfg.activeProfile() ?: return null
        return k.row(
            k.icon(R.drawable.ic_lock, k.accentUi, 18),
            k.grow(k.text(T("“{name}” is built in and can't be changed.", "name" to p.optString("name")), k.base - 1f, k.text, k.bold)),
            k.button(T("Make a copy"), primary = true) {
                cfg.copyProfile(cfg.activeProfile()!!)
                h.rerender()
            },
            gap = 10,
        ).apply {
            background = k.round(k.accentSoft, 12, k.accentUi)
            setPadding(k.dp(12), k.dp(10), k.dp(10), k.dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(12) }
        }
    }

}
