package app.vitranslator.phone.ui

import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.vitranslator.phone.App
import app.vitranslator.phone.R
import org.json.JSONArray
import org.json.JSONObject

/** The fonts bundled with the app (the desktop's BUNDLED_FONTS, SIL Open Font License). */
object Fonts {
    /** the core's own list (fontList), read once */
    private val core: List<String> by lazy {
        try {
            App.core.fontList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    val BUNDLED = listOf(
        "Lalezar" to "heavy display, great for game titles",
        "Changa" to "bold geometric",
        "Cairo" to "clean, many weights",
        "Tajawal" to "clean text",
        "Noto Sans Arabic Medium" to "like GTA VI's subtitles",
    )

    fun options(withDefault: String?): List<Pair<String, String>> {
        val o = mutableListOf<Pair<String, String>>()
        if (withDefault != null) o += "" to withDefault
        o += (Kit.GROUP + "bundled") to T("Included with the app")
        for ((n, note) in BUNDLED) o += n to "$n — ${T(note)}"
        // (any other font the core bundles)
        for (n in core) if (BUNDLED.none { it.first == n }) o += n to n
        return o
    }

    fun face(k: Kit, name: String): Typeface? = when {
        name.startsWith("Lalezar") -> k.font("Lalezar-Regular.ttf")
        name.startsWith("Changa") -> k.font("Changa.ttf")
        name.startsWith("Cairo") -> k.font("Cairo.ttf")
        name.startsWith("Tajawal") -> k.font("Tajawal-Regular.ttf")
        name.startsWith("Noto Sans Arabic") -> k.font("NotoSansArabic.ttf")
        else -> null
    }
}

// ------------------------------------------------------------------------------------- Game profile

/**
 * Game profile: a game's read areas, how its translations look, and the game pages it translates.
 * Built-in profiles can't be changed: make a copy to adjust one (as the desktop).
 */
class ProfilePanel(h: Host) : Panel(h) {
    override fun refresh() {
        view.removeAllViews()
        add(k.h3(T("Game profile")))
        add(k.muted(T("A profile holds a game's read areas, how its translations look, and the game's own pages it translates. Built-in profiles can't be changed: make a copy to adjust one.")))
        val p = cfg.activeProfile() ?: return
        val opts = mutableListOf<Pair<String, String>>()
        opts += (Kit.GROUP + "b") to T("Built-in")
        cfg.builtins().forEach { opts += it.optString("id") to it.optString("name") }
        if (cfg.profiles().isNotEmpty()) {
            opts += (Kit.GROUP + "m") to T("Your profiles")
            cfg.profiles().forEach { opts += it.optString("id") to it.optString("name") }
        }
        add(k.field(T("Profile in use"), k.select(opts, p.optString("id")) {
            cfg.useProfile(it)
            h.rerender()
        }))
        val builtin = cfg.isBuiltin(p)
        val nameRow = if (builtin) {
            k.row(k.grow(k.text(p.optString("name"), k.base + 0.5f, k.text, k.bold)), k.badge(T("Built-in")))
        } else {
            k.row(k.grow(k.input(p.optString("name")) { cfg.renameProfile(it) }), k.badge(T("Yours"), true))
        }
        val actions = k.row(k.button(T("Make a copy")) {
            cfg.copyProfile(cfg.activeProfile()!!)
            h.rerender()
        })
        if (!builtin) actions.addView(k.button(T("Delete"), danger = true) {
            k.ask(T("Delete the profile “{name}”? This can't be undone.", "name" to p.optString("name")), T("Delete")) {
                cfg.deleteProfile(p)
                h.rerender()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = k.dp(8) })
        add(k.card(14,
            nameRow,
            k.muted(T("For {game} · {n} read area(s)", "game" to cfg.gameName(p.optString("game")), "n" to (p.optJSONArray("areas")?.length() ?: 0)), small = true).apply { setPadding(0, k.dp(8), 0, 0) },
            k.gap(12), actions,
        ).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(12) } })
        add(k.muted(
            if (builtin) T("Built-in profiles get updates for the game automatically. A copy keeps your changes and stays yours.")
            else T("Your changes to read areas, text style and game pages are saved in this profile."), small = true,
        ).apply { setPadding(0, k.dp(10), 0, 0) })
        // how translations look (built-in profiles: a profile of your own places each area's translation itself)
        if (builtin) {
            add(k.h3(T("How translations look")))
            val cover = cfg.coverLook()
            for ((on, label) in listOf(false to T("Above the game's text"), true to T("Over the game's text, on a black box that hides it"))) {
                add(k.card(12, k.switch(label, cover == on) {
                    if (it != (cover == on)) {
                        cfg.setCoverLook(if (it) on else !on)
                        h.rerender()
                    }
                }).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(8) } })
            }
        }
    }
}

// ------------------------------------------------------------------------------------- Read areas

/**
 * Read areas: each area is read separately; its translation's place, font, background, size and
 * text style; its position as percentages of the picture (or drag it in the picture above).
 */
class AreasPanel(h: Host) : Panel(h) {
    private val preview = PreviewView(k, cfg)
    private var selected: String? = null
    private val list = k.column()
    private var showSample = true
    private var sample = "هذا نص تجريبي لمعاينة شكل الترجمة على الشاشة"
    private val styleOpen = HashSet<String>()

    init {
        preview.moved = { a ->
            cfg.touched("areas")
            numbers[a.optString("id")]?.invoke()
        }
        preview.tapped = { id ->
            selected = id
            drawList()
        }
        // (its card then shows the custom position)
        preview.madeCustom = { drawList() }
        preview.editAreas = true
    }

    /** an area's percentage fields, refreshed when it is dragged */
    private val numbers = HashMap<String, () -> Unit>()

    override fun refresh() {
        view.removeAllViews()
        lockNote()?.let { add(it) }
        add(k.h3(T("Read areas")))
        add(k.muted(T("Each area is read separately. Position and size are a percentage of the window, so they stay right if the window is resized.")))
        // the picture with the areas: drag a box to move it, its corner to resize
        // (the same preview each time: out of the last refresh's stage first, or adding it again
        // throws "The specified child already has a parent", as a header switch did here)
        (preview.parent as? android.view.ViewGroup)?.removeView(preview)
        val stage = FrameLayout(h.act).apply {
            background = k.round(k.stage, 12, k.line)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            addView(preview, FrameLayout.LayoutParams(-1, -2))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(12) }
        }
        add(stage)
        if (!cfg.locked()) add(k.muted(T("Drag a box to move it, drag its corners to resize."), small = true).apply { setPadding(0, k.dp(6), 0, 0) })
        add(k.check(T("Show sample text"), showSample) {
            showSample = it
            preview.sample = if (it) sample else null
        })
        preview.sample = if (showSample) sample else null
        preview.rtl = App.core.languages().firstOrNull { it.code == cfg.str("target_lang", "ar") }?.rtl ?: true
        if (selected == null) selected = cfg.areas().optJSONObject(0)?.optString("id")
        preview.selected = selected
        add(list)
        drawList()
        add(k.button(T("＋ Add read area"), primary = true) { addArea() }.apply {
            isEnabled = !cfg.locked()
            alpha = if (cfg.locked()) 0.45f else 1f
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(14) }
        })
    }

    private fun drawList() {
        list.removeAllViews()
        numbers.clear()
        preview.selected = selected
        val areas = cfg.areas()
        for (i in 0 until areas.length()) list.addView(areaCard(areas.getJSONObject(i)))
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(top) }

    private fun areaCard(a: JSONObject): View {
        val locked = cfg.locked()
        val id = a.optString("id")
        val sel = id == selected
        val card = k.card(12).apply {
            background = k.round(k.panel, 14, if (sel) k.accentUi else k.line, if (sel) 1.5 else 1)
            layoutParams = lp(12)
            setOnClickListener {
                if (selected != id) {
                    selected = id
                    drawList()
                }
            }
        }
        fun changed() {
            cfg.touched("areas")
            preview.invalidate()
        }
        // read or not (the player's own choice, even with a built-in profile), its name, delete
        val en = k.switch("", a.optBoolean("enabled", true), small = true) {
            a.put("enabled", it)
            cfg.setShown("area", id, it)
            changed()
        }
        val name = if (locked) k.text(a.optString("name"), k.base, k.text, k.bold) else k.input(a.optString("name")) {
            a.put("name", it)
            changed()
        }
        val head = k.row(en.apply { layoutParams = LinearLayout.LayoutParams(k.dp(52), -2) }, k.grow(name), gap = 6)
        if (!locked) head.addView(k.iconButton(R.drawable.ic_trash, T("Delete")) {
            k.ask(T("Delete the read area \"{name}\"?", "name" to a.optString("name")), T("Delete")) {
                val keep = JSONArray()
                val areas = cfg.areas()
                for (i in 0 until areas.length()) if (areas.getJSONObject(i) !== a) keep.put(areas.get(i))
                cfg.c.put("areas", keep)
                cfg.touched("areas")
                drawList()
                preview.invalidate()
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = k.dp(8) })
        card.addView(head)
        if (!sel) {
            card.addView(k.muted(T("Translation: {where}", "where" to T(PLACEMENTS.firstOrNull { it.first == a.optString("placement") }?.second ?: "")), small = true).apply { setPadding(0, k.dp(6), 0, 0) })
            return card
        }
        val ctl = ArrayList<View>()
        fun c(v: View) = v.also { ctl.add(it); card.addView(it) }

        c(k.field(T("Translation"), k.select(PLACEMENTS.map { it.first to T("Translation: {where}", "where" to T(it.second)) }, a.optString("placement", "over")) { v ->
            if (v == "custom") toCustom(a) else a.put("placement", v)
            changed()
            drawList()
        }))
        val st = Cfg.styleOf(a, cfg.style())
        c(k.field(T("Font"), k.select(Fonts.options(T("Main font ({font})", "font" to st.optString("font_family"))), a.optString("font_family"), T("Font"), { Fonts.face(k, it) }) {
            a.put("font_family", it)
            changed()
        }))
        c(k.field(T("Background"), k.select(listOf("" to T("Its text style's box"), "none" to T("None — outlined text, like game subtitles"), "game" to T("Game-style dark box")), a.optString("background")) {
            a.put("background", it)
            changed()
        }))
        c(k.check(T("Match the game's text size"), a.optBoolean("match_size", true)) {
            a.put("match_size", it)
            changed()
            drawList()
        })
        c(k.check(T("Only text centred in the picture (subtitles)"), a.optBoolean("centered_only")) {
            a.put("centered_only", it)
            changed()
        })
        c(k.field(if (a.optBoolean("match_size", true)) T("Fine-tune") else T("Text size"),
            k.slider(0.5f, 2f, 0.05f, a.optDouble("font_scale", 1.0).toFloat(), { "${Math.round(it * 100)}%" }) {
                a.put("font_scale", Math.round(it * 100) / 100.0)
                changed()
            }))
        // its text style (the profile's until changed here)
        val open = id in styleOpen
        val styleHead = k.row(k.icon(R.drawable.ic_chevron, k.muted, 14).apply { rotation = if (open) 90f else 0f }, k.text(T("Text style"), k.base - 0.5f, k.text, k.bold), gap = 6).apply {
            setPadding(0, k.dp(12), 0, k.dp(4))
            setOnClickListener {
                if (open) styleOpen.remove(id) else styleOpen.add(id)
                drawList()
            }
        }
        card.addView(styleHead)
        if (open) styleForm(a, card, ctl)

        card.addView(k.label(T("Read area")).apply { setPadding(0, k.dp(14), 0, 0) })
        c(posGrid(a, listOf("x", "y", "w", "h")))
        if (a.optString("placement") != "custom") {
            c(k.field(T("Text sits"), k.select(TEXT_POS.map { it.first to T(it.second) }, a.optString("text_pos")) {
                a.put("text_pos", it)
                changed()
            }))
        } else {
            card.addView(k.label(T("Translation region")).apply { setPadding(0, k.dp(14), 0, 0) })
            c(posGrid(a, listOf("out_x", "out_y", "out_w", "out_h")))
            c(k.row(
                k.grow(k.select(listOf("top" to T("Text at the top"), "middle" to T("Text in the middle"), "bottom" to T("Text at the bottom")), a.optString("out_valign", "bottom")) {
                    a.put("out_valign", it)
                    changed()
                }),
                k.grow(k.select(listOf("right" to T("Right"), "center" to T("Centre"), "left" to T("Left")), a.optString("out_halign", "center")) {
                    a.put("out_halign", it)
                    changed()
                }),
            ).apply { layoutParams = lp(8) })
        }
        if (locked) for (v in ctl) disable(v)
        fxForm(id, card)
        return card
    }

    /** How the area's translation comes on screen (the desktop's fxFields): the player's own, a built-in
     *  profile's areas too. */
    private fun fxForm(id: String, card: LinearLayout) {
        val fx = cfg.fxOf(id)
        val cover = cfg.coverLook()
        fun set(key: String, v: Any) {
            cfg.setFx(id, key, v)
            preview.invalidate()
        }
        card.addView(k.label(T("Showing the translation")).apply { setPadding(0, k.dp(14), 0, 0) })
        card.addView(k.check(T("Black box at least as big as the game's text"), fx.optBoolean("cover_min")) { set("cover_min", it) }.apply { if (!cover) disable(this) })
        card.addView(k.check(T("Black box as soon as the text appears, before its translation"), fx.optBoolean("cover_early")) { set("cover_early", it) }.apply { if (!cover) disable(this) })
        if (!cover) card.addView(k.muted(T("The black box is for the look \"Over the game's text\" (Game profile)."), small = true))
        card.addView(k.check(T("Lines stack up from the bottom line: the lines above are read only when the bottom one is there"), fx.optBoolean("stack_up")) { set("stack_up", it) })
        card.addView(k.check(T("Right above the stacked lines, moving up with them (with the subtitles and the bottom instructions as one block)"), fx.optBoolean("ride")) { set("ride", it) }.apply { if (cover) disable(this) })
        if (cover) card.addView(k.muted(T("Moving above the lines is for the look \"Above the game's text\" (Game profile)."), small = true))
        card.addView(k.check(T("A border in Vice City colours, to tell it apart"), fx.optBoolean("accent")) { set("accent", it) })
        card.addView(k.check(T("Only reviewed translations: a reading that matches none isn't shown"), fx.optBoolean("reviewed_only")) { set("reviewed_only", it) })
        val fades = listOf(0, 100, 150, 250, 400, 600)
        card.addView(k.field(T("Fade in"), k.select(fades.map { "$it" to if (it == 0) T("Off") else "$it ms" }, "${fx.optInt("fade_ms")}") { set("fade_ms", it.toInt()) }))
    }

    private fun disable(v: View) {
        v.isEnabled = false
        v.alpha = 0.5f
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) disable(v.getChildAt(i))
    }

    /** Four percentage fields for a rectangle kept as fractions (the desktop's posGrid). */
    private fun posGrid(a: JSONObject, keys: List<String>): View {
        val labels = listOf("Left %", "Top %", "Width %", "Height %")
        val row = k.row(gap = 6)
        val edits = ArrayList<android.widget.EditText>()
        keys.forEachIndexed { i, key ->
            val e = k.input(String.format(java.util.Locale.ROOT, "%.1f", a.optDouble(key) * 100), number = true, ltr = true) {}
            e.setOnFocusChangeListener { _, has ->
                if (has) return@setOnFocusChangeListener
                val v = e.text.toString().toDoubleOrNull() ?: return@setOnFocusChangeListener
                val min = if (i >= 2) 0.02 else 0.0
                a.put(key, Math.round((v / 100).coerceIn(min, 1.0) * 10000) / 10000.0)
                if (a.optDouble(keys[0]) + a.optDouble(keys[2]) > 1) a.put(keys[2], 1 - a.optDouble(keys[0]))
                if (a.optDouble(keys[1]) + a.optDouble(keys[3]) > 1) a.put(keys[3], 1 - a.optDouble(keys[1]))
                cfg.touched("areas")
                preview.invalidate()
            }
            edits += e
            row.addView(k.grow(k.column(k.text(T(labels[i]), k.base - 3f, k.muted, k.bold).apply { setPadding(0, 0, 0, k.dp(3)) }, e)))
        }
        if (keys[0] == "x") numbers[a.optString("id")] = {
            keys.forEachIndexed { i, key -> edits[i].setText(String.format(java.util.Locale.ROOT, "%.1f", a.optDouble(key) * 100)) }
        }
        return row.apply { layoutParams = lp(6) }
    }

    /** The desktop's STYLE_FIELDS for one area: its own style from its first change on. */
    private fun styleForm(a: JSONObject, card: LinearLayout, ctl: ArrayList<View>) {
        val s = Cfg.styleOf(a, cfg.style())
        fun set(key: String, v: Any) {
            if (!a.has("style") || a.isNull("style")) a.put("style", Cfg.clone(cfg.style()))
            a.getJSONObject("style").put(key, v)
            cfg.touched("areas")
            preview.invalidate()
        }
        fun c(v: View) = v.also { ctl.add(it); card.addView(it) }
        c(k.field(T("Text size (px at 1080p; scales with the game window)"), k.slider(10f, 96f, 1f, s.optDouble("font_size", 26.0).toFloat(), { "${it.toInt()} px" }) { set("font_size", it.toInt()) }))
        c(k.check(T("Bold"), s.optBoolean("bold")) { set("bold", it) })
        c(k.field(T("Line spacing"), k.slider(1f, 2.2f, 0.05f, s.optDouble("line_height", 1.5).toFloat(), { String.format(java.util.Locale.ROOT, "%.2f", it) }) { set("line_height", Math.round(it * 100) / 100.0) }))
        c(k.field(T("Text color"), k.colorPick(s.optString("text_color", "#ffffff")) { set("text_color", it) }))
        c(k.check(T("Color highlighted words like the game does"), s.optBoolean("keep_colors", true)) { set("keep_colors", it) })
        c(k.field(T("Box color"), k.colorPick(s.optString("bg_color", "#000000")) { set("bg_color", it) }))
        c(k.field(T("Box opacity"), k.slider(0f, 1f, 0.05f, s.optDouble("bg_opacity", 0.65).toFloat(), { String.format(java.util.Locale.ROOT, "%.2f", it) }) { set("bg_opacity", Math.round(it * 100) / 100.0) }))
        c(k.field(T("Box padding"), k.slider(0f, 30f, 1f, s.optDouble("padding", 10.0).toFloat(), { it.toInt().toString() }) { set("padding", it.toInt()) }))
        c(k.field(T("Box corner roundness"), k.slider(0f, 30f, 1f, s.optDouble("radius", 10.0).toFloat(), { it.toInt().toString() }) { set("radius", it.toInt()) }))
        c(k.field(T("Text outline color"), k.colorPick(s.optString("outline_color", "#000000")) { set("outline_color", it) }))
        c(k.field(T("Text outline width"), k.slider(0f, 6f, 0.5f, s.optDouble("outline_width", 0.0).toFloat(), { String.format(java.util.Locale.ROOT, "%.2f", it) }) { set("outline_width", it.toDouble()) }))
        c(k.check(T("Text shadow"), s.optBoolean("shadow")) { set("shadow", it) })
        if (a.has("style") && !a.isNull("style")) {
            c(k.button(T("Reset to defaults")) {
                a.put("style", org.json.JSONObject.NULL)
                cfg.touched("areas")
                drawList()
                preview.invalidate()
            }.apply { layoutParams = lp(10) })
        }
    }

    /** "Custom position": its region starts where the translation is now (over the area). */
    private fun toCustom(a: JSONObject) {
        a.put("placement", "custom")
        if (!a.has("out_w") || a.optDouble("out_w") <= 0) {
            a.put("out_x", a.optDouble("x")).put("out_y", (a.optDouble("y") - 0.15).coerceAtLeast(0.0)).put("out_w", a.optDouble("w")).put("out_h", 0.15)
                .put("out_valign", "bottom").put("out_halign", "center")
        }
    }

    private fun addArea() {
        if (cfg.locked()) return
        val id = "area_${java.lang.Long.toString(System.currentTimeMillis(), 36)}"
        cfg.areas().put(
            JSONObject().put("id", id).put("name", T("Area {n}", "n" to cfg.areas().length() + 1)).put("enabled", true)
                .put("x", 0.35).put("y", 0.4).put("w", 0.3).put("h", 0.12).put("placement", "above").put("font_scale", 1).put("font_family", "")
                .put("out_x", 0.35).put("out_y", 0.25).put("out_w", 0.3).put("out_h", 0.15).put("out_valign", "bottom").put("out_halign", "center"),
        )
        selected = id
        cfg.touched("areas")
        drawList()
        preview.invalidate()
    }

    companion object {
        val PLACEMENTS = listOf(
            "under_text" to "Below the game's text box (same left edge, at most its width)",
            "above_text" to "Just above the game's text (centred on it)",
            "above" to "Above the area", "below" to "Below the area", "right" to "To the right", "left" to "To the left",
            "over" to "On top of the area", "custom" to "Custom position (drag it in the preview)",
        )
        val TEXT_POS = listOf(
            "" to "Automatic for this placement",
            "top-left" to "Top left", "top-center" to "Top centre", "top-right" to "Top right",
            "middle-left" to "Middle left", "middle-center" to "Middle centre", "middle-right" to "Middle right",
            "bottom-left" to "Bottom left", "bottom-center" to "Bottom centre", "bottom-right" to "Bottom right",
        )
    }
}

// ------------------------------------------------------------------------------------- Game pages

/**
 * What's translated (Game pages): the name and the note of each switch the core lists for a game (page_options
 * "groups"; GTA VI's: the phone, the pause menu, the help and notices).
 */
private val CONTENT_TEXT = mapOf(
    "phone" to ("Phone translations" to "The phone's WhatUp chats, translated on a second phone beside it."),
    "menus" to ("Pause menu and map" to "The pause menu: the map and its index, lists, help pages, settings and buttons."),
    "notices" to ("Help, instructions and notices" to "The help box at the top left, the instructions under the subtitles, and the game's notices like “Controller disconnected”."),
)

/** Game pages: the game's own pages that get a hand-made translation, in the game's look. */
class PagesPanel(h: Host) : Panel(h) {
    override fun refresh() {
        view.removeAllViews()
        lockNote()?.let { add(it) }
        add(k.h3(T("Game pages")))
        add(k.muted(T("The game's own pages that get a hand-made translation, in the game's look.")))
        val game = cfg.profileGame()
        val box = k.column()
        add(box)
        h.io {
            val script = try {
                App.core.pageOptions(game)?.let { JSONObject(it) }
            } catch (e: Exception) {
                null
            }
            h.ui { draw(box, script, game) }
        }
    }

    private fun scriptText(v: Any?): String = when (v) {
        is String -> v
        is JSONObject -> v.optString(T.code).ifEmpty { v.optString("en") }.ifEmpty { v.keys().asSequence().firstOrNull()?.let { v.optString(it) } ?: "" }
        else -> ""
    }

    private fun draw(box: LinearLayout, script: JSONObject?, game: String) {
        box.removeAllViews()
        val groups = script?.optJSONArray("groups")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }?.filter { CONTENT_TEXT.containsKey(it.optString("id")) } ?: emptyList()
        // (GTA VI's phone in the hand — its WhatUp chats translated on a second phone beside it: shown or not, the
        // player's, whatever the profile; with a core that lists What's translated, it's one of those switches)
        if (game == "gta6" && groups.isEmpty()) {
            val card = k.card(12, k.switch(T("Phone translations"), cfg.bool("phone_text", true)) { cfg.put("phone_text", it) })
            card.addView(k.muted(T("The phone's WhatUp chats, translated on a second phone beside it."), small = true))
            card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(12) }
            box.addView(card)
        }
        val pages = script?.optJSONArray("pages")
        if (script == null || pages == null || pages.length() == 0) {
            box.addView(k.callout(
                if (script == null) T("The game pages' switches come to the phone app with an update. The pages themselves are translated as on the PC.")
                else T("{game}'s own pages aren't known yet, so none are translated for now. Subtitles and the read areas work as usual.", "game" to cfg.gameName(game)),
            ).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(12) } })
            if (script == null) fontsCard(box)
            return
        }
        val o = cfg.profileOpts()
        val more = o.optJSONObject("more") ?: JSONObject().also { o.put("more", it) }
        val options = script.optJSONArray("options") ?: JSONArray()
        if (groups.isNotEmpty()) contentCard(box, groups, options, more, script, game)
        val covered = groups.flatMap { g -> g.optJSONArray("pages")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList() }.toSet()
        for (i in 0 until options.length()) {
            val opt = options.getJSONObject(i)
            val id = opt.optString("id")
            if (id in covered) continue // (one of What's translated above)
            val on = if (more.has(id)) more.optBoolean(id) else opt.optBoolean("on")
            val card = k.card(12,
                k.switch(scriptText(opt.opt("name")).ifEmpty { id }, on) {
                    more.put(id, it)
                    cfg.setShown("page", id, it)
                    cfg.touched("profile_opts")
                })
            scriptText(opt.opt("about")).takeIf { it.isNotEmpty() }?.let { card.addView(k.muted(it, small = true)) }
            card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(10) }
            box.addView(card)
        }
        fontsCard(box)
    }

    /**
     * What's translated: the dialogue always; each of the game's switches (the core's list, page_options "groups")
     * stands for read areas, pages of the game and the phone, shown on when any of them is on and switching them
     * all; "Dialogue only" turns them and the game's other page switches off.
     */
    private fun contentCard(box: LinearLayout, groups: List<JSONObject>, options: JSONArray, more: JSONObject, script: JSONObject, game: String) {
        fun strings(g: JSONObject, key: String) = g.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        fun pageOn(id: String): Boolean {
            if (more.has(id)) return more.optBoolean(id)
            for (i in 0 until options.length()) if (options.getJSONObject(i).optString("id") == id) return options.getJSONObject(i).optBoolean("on")
            return false
        }
        val areas = cfg.areas()
        fun areaOn(id: String) = (0 until areas.length()).any { areas.getJSONObject(it).let { a -> a.optString("id") == id && a.optBoolean("enabled", true) } }
        fun groupOn(g: JSONObject) = (g.optBoolean("phone") && cfg.bool("phone_text", true)) || strings(g, "areas").any(::areaOn) || strings(g, "pages").any(::pageOn)
        fun setGroup(g: JSONObject, on: Boolean) {
            if (g.optBoolean("phone")) cfg.put("phone_text", on)
            val ids = strings(g, "areas")
            for (i in 0 until areas.length()) areas.getJSONObject(i).takeIf { it.optString("id") in ids }?.let {
                it.put("enabled", on)
                cfg.setShown("area", it.optString("id"), on)
            }
            for (id in strings(g, "pages")) {
                more.put(id, on)
                cfg.setShown("page", id, on)
            }
            cfg.touched("areas", "profile_opts")
        }
        val card = k.card(12, k.text(T("What's translated"), k.base, k.text, k.bold))
        val dialogue = k.switch(T("Dialogue and subtitles"), true) {}.apply { isEnabled = false }
        card.addView(dialogue)
        card.addView(k.muted(T("Always translated."), small = true))
        for (g in groups) {
            val (name, about) = CONTENT_TEXT.getValue(g.optString("id"))
            card.addView(k.switch(T(name), groupOn(g)) { setGroup(g, it) })
            card.addView(k.muted(T(about), small = true))
        }
        card.addView(k.button(T("Dialogue only")) {
            for (g in groups) setGroup(g, false)
            for (i in 0 until options.length()) {
                val id = options.getJSONObject(i).optString("id")
                more.put(id, false)
                cfg.setShown("page", id, false)
            }
            cfg.touched("profile_opts")
            draw(box, script, game)
        }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = k.dp(12) } })
        card.addView(k.muted(T("Turns off everything here except the dialogue and subtitles."), small = true).apply { setPadding(0, k.dp(6), 0, 0) })
        card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(12) }
        box.addView(card)
    }

    private fun fontsCard(box: LinearLayout) {
        val o = cfg.profileOpts()
        val title = k.select(Fonts.options(null), o.optString("title_font", "Lalezar"), T("Title font"), { Fonts.face(k, it) }) {
            o.put("title_font", it)
            cfg.touched("profile_opts")
        }
        val text = k.select(Fonts.options(null), o.optString("text_font", "Tajawal"), T("Reason and button font"), { Fonts.face(k, it) }) {
            o.put("text_font", it)
            cfg.touched("profile_opts")
        }
        val card = k.card(12, k.field(T("Title font"), title), k.field(T("Reason and button font"), text))
        if (cfg.locked()) for (v in listOf(title, text)) {
            v.isEnabled = false
            v.alpha = 0.5f
        }
        card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(10) }
        box.addView(card)
    }
}
