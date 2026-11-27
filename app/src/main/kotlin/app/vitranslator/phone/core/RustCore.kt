package app.vitranslator.phone.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import app.vitranslator.core.Engine
import app.vitranslator.core.EngineListener
import app.vitranslator.core.OcrWord
import app.vitranslator.core.PairException
import app.vitranslator.core.TextRecognizer
import app.vitranslator.core.TranslatedLine
import app.vitranslator.core.coreVersion
import org.json.JSONObject
import app.vitranslator.core.CoreException as FfiCoreException
import app.vitranslator.core.languages as coreLanguages

/**
 * The shared Rust core (libvi_translator_ffi.so, through its UniFFI bindings in
 * app.vitranslator.core): the same reading, translating and TV page as the desktop app.
 * Its settings, translation memory and connected TVs live in the app's files.
 */
class RustCore(context: Context, recognizer: Recognizer) : Core {
    private val ctx = context.applicationContext

    @Volatile
    private var listener: CoreListener? = null

    private val engine = Engine(
        ctx.filesDir.resolve("vit").path,
        ctx.filesDir.resolve("vit-db").path,
        object : TextRecognizer {
            override fun recognize(bgra: ByteArray, width: UInt, height: UInt): List<OcrWord> =
                recognizer.recognize(bgra, width.toInt(), height.toInt()).map {
                    OcrWord(it.line.toUInt(), it.text, it.left.toDouble(), it.top.toDouble(), (it.right - it.left).toDouble(), (it.bottom - it.top).toDouble())
                }

            // (0: the core's default, 2600 px)
            override fun maxDimension(): UInt = 0u
        },
        object : EngineListener {
            override fun onTranslation(line: TranslatedLine) {
                listener?.linesChanged()
            }

            override fun onScreen(layoutJson: String?) {}

            override fun onStatus(message: String) {
                listener?.status(message)
            }

            override fun onEvent(name: String, json: String) {}
        },
    )

    override val name: String = "vi-translator-core ${coreVersion()}"

    override fun games(): List<Game> = engine.games().map { Game(it.id, it.name) }

    override fun languages(): List<Language> = coreLanguages().map { Language(it.code, it.native.ifEmpty { it.name }, it.rtl, it.name) }

    override fun prepare(game: String, language: String) {
        try {
            engine.prepare(game, language)
        } catch (e: FfiCoreException) {
            throw CoreException((e as? FfiCoreException.Failed)?.reason ?: e.message ?: e.toString())
        }
    }

    override fun processFrame(rgba: ByteArray, width: Int, height: Int): Long =
        engine.processFrame(rgba, width.toUInt(), height.toUInt()).toLong()

    override fun noPicture() {
        engine.noPicture()
    }

    override fun lines(max: Int): List<Line> =
        engine.recentLines(max.toUInt()).map { Line(it.id.toLong(), it.area, it.en, it.text, it.atMs.toLong()) }

    override fun setListener(listener: CoreListener?) {
        this.listener = listener
    }

    override fun service(): Pair<Service, String> {
        val cfg = JSONObject(engine.configJson())
        val s = Service.entries.firstOrNull { it.engine == cfg.optString("engine") } ?: Service.MEMORY
        return s to (s.keyName?.let { cfg.optString(it) } ?: "")
    }

    override fun setService(service: Service, key: String) {
        val patch = JSONObject().put("engine", service.engine)
        if (service.keyName != null) patch.put(service.keyName, key.trim())
        engine.updateConfig(patch.toString())
    }

    override fun configJson(): String = engine.configJson()

    override fun updateConfig(patch: String) {
        try {
            engine.updateConfig(patch)
        } catch (e: FfiCoreException) {
            throw CoreException((e as? FfiCoreException.Failed)?.reason ?: e.message ?: e.toString())
        }
    }

    override fun memoryStatsJson(): String = engine.memoryStatsJson()

    override fun memoryClear(): Boolean {
        engine.memoryClear()
        return true
    }

    override fun pageOptions(game: String): String = engine.pageOptions(game)

    override fun screenJson(): String = engine.screenJson()

    override fun takeSnapshot(rgba: ByteArray, width: Int, height: Int): String = try {
        engine.takeSnapshot(rgba, width.toUInt(), height.toUInt())
    } catch (e: FfiCoreException) {
        throw CoreException((e as? FfiCoreException.Failed)?.reason ?: e.message ?: e.toString())
    }

    override fun lanInfo(): String = engine.lanInfo()

    override fun localViewerUrl(): String? = engine.localViewerUrl()

    override fun fontList(): List<String> = engine.fontList()

    override fun perfJson(): String = engine.perfJson()

    override val coreVersion: String = coreVersion()

    override fun startServer(): Int = engine.startTvServer(PORT.toUShort()).toInt()

    override fun stopServer() {
        engine.stopTvServer()
    }

    override fun findTvs(): List<Tv> = engine.findTvs().filter { !it.connected }.map { Tv(it.id, it.name, it.address) }

    override fun pairTv(address: String, code: String): PairResult = try {
        engine.pairTv(address.trim(), code.trim(), deviceName())
        PairResult.OK
    } catch (e: PairException.WrongCode) {
        PairResult.WRONG_CODE
    } catch (e: PairException.TooMany) {
        PairResult.NEW_CODE
    } catch (e: PairException.NotSharing) {
        PairResult.NOT_SHARING
    } catch (e: PairException) {
        PairResult.UNREACHABLE
    }

    override fun pairedTvs(): List<Tv> = engine.listTvs().map { Tv(it.id, it.name, "", it.online, it.on) }

    override fun setTvOn(id: String, on: Boolean): Boolean = engine.setTvOn(id, on)

    override fun forgetTv(id: String) {
        engine.removeTv(id)
    }

    private fun deviceName(): String {
        val n = try {
            Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (e: RuntimeException) {
            null
        }
        return (n?.trim()?.takeIf { it.isNotEmpty() } ?: Build.MODEL).take(60)
    }

    companion object {
        /** the TV page's port, as the desktop app's */
        const val PORT = 8791
    }
}
