package app.vitranslator.phone.run

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import app.vitranslator.phone.App
import app.vitranslator.phone.Lang
import app.vitranslator.phone.Prefs
import app.vitranslator.phone.R
import app.vitranslator.phone.read.BitmapFrame
import app.vitranslator.phone.read.Reader
import app.vitranslator.phone.ps.Ps5
import app.vitranslator.phone.ps.PsStream
import app.vitranslator.phone.ps.chiaki.Chiaki
import app.vitranslator.phone.ps.chiaki.VideoResolutionPreset
import app.vitranslator.phone.ui.MainActivity
import app.vitranslator.phone.ui.T
import app.vitranslator.phone.xbox.XboxAuth
import app.vitranslator.phone.xbox.XboxConsole
import app.vitranslator.phone.xbox.XboxStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Translating: keeps the phone at it while its screen is off (PS5, Xbox) or on another app: the core's
 * TV page served on the home network, the reading of the game, and the PS5 or Xbox stream. A notification
 * says so, with Stop.
 */
class TranslateService : Service(), PsStream.Listener, XboxStream.Listener {
    enum class Source { PS5, XBOX, CARD }

    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var stream: PsStream? = null
    private var xbox: XboxStream? = null
    private var card: app.vitranslator.phone.card.CardStream? = null
    private var source: Source? = null

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var projected: ImageReader? = null
    private var projectThread: HandlerThread? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PROJECT -> {
                foreground(true)
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                @Suppress("DEPRECATION")
                val rect = intent.getParcelableExtra<Rect>(EXTRA_RECT)
                if (data != null && rect != null) startProjection(code, data, rect)
                return START_NOT_STICKY
            }
        }
        val s = Source.valueOf(intent?.getStringExtra(EXTRA_SOURCE) ?: Source.PS5.name)
        foreground(false, camera = s == Source.CARD)
        if (source == null) begin(s)
        return START_NOT_STICKY
    }

    private fun foreground(projecting: Boolean, camera: Boolean = source == Source.CARD) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notify_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TranslateService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            // (the app's pink behind the icon in the notification list)
            .setColor(0xFFE0367F.toInt())
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notify_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            if (projecting) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            // (the capture card is a camera: reading it with the screen off needs this type)
            if (camera && Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            startForeground(NOTIFICATION, n, type)
        } else {
            startForeground(NOTIFICATION, n)
        }
    }

    private fun begin(s: Source) {
        source = s
        Live.set(Live.State.STARTING, null)
        val pm = getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vit:translate").apply { acquire(6 * 60 * 60 * 1000L) }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifi = wm.createWifiLock(mode, "vit:translate").apply { acquire() }

        val prefs = Prefs(this)
        Thread {
            val core = App.core
            try {
                // the language and the game: the settings' (the desktop's target_lang, and the game of the profile in use)
                val c = org.json.JSONObject(core.configJson())
                val profile = (0 until (c.optJSONArray("builtins")?.length() ?: 0)).map { c.getJSONArray("builtins").getJSONObject(it) } +
                    (0 until (c.optJSONArray("profiles")?.length() ?: 0)).map { c.getJSONArray("profiles").getJSONObject(it) }
                val game = profile.firstOrNull { it.optString("id") == c.optString("profile") }?.optString("game")?.ifEmpty { null } ?: "gta6"
                core.prepare(game, c.optString("target_lang").ifEmpty { "ar" })
                core.startServer()
            } catch (e: Exception) {
                Log.e(TAG, "core", e)
                main.post {
                    Live.set(Live.State.FAILED, e.message)
                    stopSelf()
                }
                return@Thread
            }
            val reader = Reader(core)
            reader.power = getSystemService(PowerManager::class.java)
            App.reader = reader
            main.post {
                if (source == null) return@post
                when (s) {
                    Source.PS5 -> startPs(prefs)
                    Source.CARD -> startCard(prefs)
                    // (Microsoft's web player gives the frames itself, XboxActivity; else the app's own stream)
                    Source.XBOX -> if (prefs.xboxWebPlayer) Live.set(Live.State.READING, null) else startXbox(prefs)
                }
            }
        }.start()
    }

    private fun startPs(prefs: Prefs) {
        val console = Ps5.saved(this)
        val reader = App.reader
        if (!Chiaki.available || console == null || reader == null) {
            Live.set(Live.State.FAILED, getString(if (!Chiaki.available) R.string.ps_not_in_build else R.string.ps_not_registered))
            stopSelf()
            return
        }
        val res = if (prefs.psResolution == 720) VideoResolutionPreset.RES_720P else VideoResolutionPreset.RES_1080P
        stream = PsStream(console, reader, this, File(cacheDir, "chiaki.log").path).also { it.start(res) }
    }

    private fun startCard(prefs: Prefs) {
        val reader = App.reader ?: return
        Live.set(Live.State.CONNECTING, null)
        card = app.vitranslator.phone.card.CardStream(this, reader) { state, detail ->
            main.post {
                when (state) {
                    app.vitranslator.phone.card.CardStream.State.STREAMING -> Live.set(Live.State.READING, null)
                    else -> {
                        Live.set(if (state == app.vitranslator.phone.card.CardStream.State.FAILED) Live.State.FAILED else Live.State.ENDED, detail)
                        stopSelf()
                    }
                }
            }
        }.also { it.start(prefs.card4k) }
    }

    private fun startXbox(prefs: Prefs) {
        val console = XboxConsole.saved(this)
        val reader = App.reader
        if (!XboxAuth.signedIn(this) || console == null || reader == null) {
            Live.set(Live.State.FAILED, T("Sign in to Microsoft and choose your Xbox in Setup first."))
            stopSelf()
            return
        }
        xbox = XboxStream(this, console, prefs.xboxResolution, reader, this).also { it.start() }
    }

    override fun onState(state: XboxStream.State, detail: String?) {
        main.post {
            when (state) {
                XboxStream.State.CONNECTING -> Live.set(Live.State.CONNECTING, null)
                XboxStream.State.STREAMING -> Live.set(Live.State.READING, null)
                XboxStream.State.ENDED, XboxStream.State.FAILED -> {
                    Live.set(if (state == XboxStream.State.FAILED) Live.State.FAILED else Live.State.ENDED, detail)
                    stopSelf()
                }
            }
        }
    }

    override fun onState(state: PsStream.State, detail: String?) {
        main.post {
            when (state) {
                PsStream.State.CONNECTING -> Live.set(Live.State.CONNECTING, null)
                PsStream.State.STREAMING -> Live.set(Live.State.READING, null)
                PsStream.State.ENDED, PsStream.State.FAILED -> {
                    Live.set(if (state == PsStream.State.FAILED) Live.State.FAILED else Live.State.ENDED, detail)
                    stopSelf()
                }
            }
        }
    }

    override fun onLoginPin(incorrect: Boolean) {
        main.post { Live.askPin(incorrect) }
    }

    override fun onCantDisplay(cantDisplay: Boolean) {
        main.post { Live.setCantDisplay(cantDisplay) }
    }

    /** The passcode the user typed, for the PS5. */
    fun pin(p: String) {
        stream?.setLoginPin(p)
    }

    // ---- Xbox, when PixelCopy sees no video: this app's screen, by MediaProjection

    private fun startProjection(code: Int, data: Intent, rect: Rect) {
        stopProjection()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data) ?: return
        projection = mp
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val t = HandlerThread("projection").apply { start() }
        projectThread = t
        val handler = Handler(t.looper)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopProjection() }
            }
        }, handler)
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var bmp: android.graphics.Bitmap? = null
        ir.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val reader = App.reader ?: return@setOnImageAvailableListener
                val plane = img.planes[0]
                val rowPx = plane.rowStride / plane.pixelStride
                val b = bmp?.takeIf { it.width == rowPx && it.height == img.height }
                    ?: android.graphics.Bitmap.createBitmap(rowPx, img.height, android.graphics.Bitmap.Config.ARGB_8888).also { bmp = it }
                b.copyPixelsFromBuffer(plane.buffer)
                val r2 = Rect(rect).apply { intersect(0, 0, img.width, img.height) }
                if (!r2.isEmpty) {
                    val player = android.graphics.Bitmap.createBitmap(b, r2.left, r2.top, r2.width(), r2.height())
                    reader.offer(BitmapFrame(player))
                    player.recycle()
                }
            } finally {
                img.close()
            }
        }, handler)
        projected = ir
        display = mp.createVirtualDisplay("vit-xbox", w, h, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.surface, null, handler)
        projecting = true
    }

    private fun stopProjection() {
        projecting = false
        display?.release()
        display = null
        projected?.close()
        projected = null
        projection?.stop()
        projection = null
        projectThread?.quitSafely()
        projectThread = null
    }

    override fun onDestroy() {
        instance = null
        stopProjection()
        stream?.stop()
        stream = null
        xbox?.stop()
        xbox = null
        card?.stop()
        card = null
        source = null
        App.reader?.close()
        App.reader = null
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        if (Live.state != Live.State.FAILED && Live.state != Live.State.ENDED) Live.set(Live.State.IDLE, null)
        super.onDestroy()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TranslateService"
        private const val CHANNEL = "translating"
        private const val NOTIFICATION = 1
        private const val ACTION_STOP = "app.vitranslator.phone.STOP"
        private const val ACTION_PROJECT = "app.vitranslator.phone.PROJECT"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_RECT = "rect"

        @Volatile
        var instance: TranslateService? = null
            private set

        /** MediaProjection gives the Xbox frames (not PixelCopy). */
        @Volatile
        var projecting = false
            private set

        fun start(c: Context, source: Source) {
            c.startForegroundService(Intent(c, TranslateService::class.java).putExtra(EXTRA_SOURCE, source.name))
        }

        fun project(c: Context, code: Int, data: Intent, rect: Rect) {
            c.startForegroundService(
                Intent(c, TranslateService::class.java).setAction(ACTION_PROJECT)
                    .putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data).putExtra(EXTRA_RECT, rect),
            )
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, TranslateService::class.java))
        }
    }
}

/** What translating is doing, for the screens (main thread). */
object Live {
    enum class State { IDLE, STARTING, CONNECTING, READING, ENDED, FAILED }

    interface Watcher {
        fun changed()
        fun askPin(incorrect: Boolean) {}
    }

    var state = State.IDLE
        private set
    var detail: String? = null
        private set
    /** the PS5 says it can't stream what it shows now (a screen or game that blocks Remote Play) */
    var cantDisplay = false
        private set
    private val watchers = CopyOnWriteArrayList<Watcher>()

    fun set(s: State, d: String?) {
        state = s
        detail = d
        // (the PS5 may say so while connecting, before READING: kept until the stream ends)
        if (s != State.CONNECTING && s != State.READING) cantDisplay = false
        watchers.forEach { it.changed() }
    }

    fun askPin(incorrect: Boolean) {
        watchers.forEach { it.askPin(incorrect) }
    }

    fun setCantDisplay(cant: Boolean) {
        cantDisplay = cant
        watchers.forEach { it.changed() }
    }

    fun watch(w: Watcher) {
        watchers.addIfAbsent(w)
    }

    fun unwatch(w: Watcher) {
        watchers.remove(w)
    }
}
