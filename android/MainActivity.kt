// Chuks Mobile, Android host. The SAME engine.chuks (compiled to libapp.so)
// drives real Android Views via the SAME mutation protocol (C/S/P/T/I/R) the
// iOS host uses. This host is the Android analogue of ChuksApp.swift: it mirrors
// the stream into two lockstep trees (Android Views + a Yoga shadow tree),
// runs Yoga each frame, and copies the computed rects onto the views. Proves
// the thesis: one Chuks engine, thin per-platform hosts.

package com.chuks.app

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import java.util.Calendar
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.hardware.camera2.CameraManager
import android.view.WindowManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.content.res.ColorStateList

// JNI surface (implemented in jni.cpp / the engine .so).
object N {
    init { System.loadLibrary("app") }
    external fun setup(n: Int)
    external fun mount(): Int
    external fun tick(): Int
    external fun viewport(top: Int, h: Int): Int
    external fun event(action: String): Int
    external fun input(action: String, value: String): Int
    external fun resolve(token: String, payload: String): Int   // F3: async capability result
    external fun fail(token: String, message: String): Int      // F3: capability failure (error channel)
    external fun setColorScheme(dark: Int)
    external fun colorSchemeFollows(): Int
    external fun setInsets(top: Int, right: Int, bottom: Int, left: Int)
    external fun setPlatform(os: String, version: String, model: String, isTablet: Int)
    external fun drain(): String
    external fun yNew(): Long
    external fun yInsert(parent: Long, child: Long, idx: Int)
    external fun yRemove(parent: Long, child: Long)
    external fun yOwner(node: Long): Long
    external fun yChildCount(node: Long): Int
    external fun yFree(node: Long)
    external fun yCalc(node: Long, w: Float, h: Float)
    external fun yGet(node: Long, which: Int): Float
    external fun ySetF(node: Long, key: Int, v: Float)
}

class MainActivity : Activity() {
    private val views = HashMap<String, View>()
    private val ynodes = HashMap<String, Long>()
    private var density = 1f

    private lateinit var root: FrameLayout
    private var listScroll: ScrollView? = null
    private var scrollId = ""
    private val modalIds = HashSet<String>()             // Modal node ids (full-screen overlays)
    private var activeModal: String? = null              // the currently-visible Modal
    private val sheetModals = HashSet<String>()          // Modal ids with position=bottom (draggable sheets)
    private val modalActions = HashMap<String, String>() // Modal id -> onDismiss action
    private var sheetBg: View? = null                    // host-drawn sheet surface (rounded top, behind content)
    private var sheetHandle: View? = null                // host-drawn grab handle pill
    private var shownSheet: String? = null               // sheet currently on screen (null = none); a change drives the slide-up
    private val sliderMin = HashMap<String, Int>()        // Slider id -> min, to offset the SeekBar's 0-based progress
    private val selectIds = HashSet<String>()             // Select node ids (PopupMenu buttons)
    private val selectOptions = HashMap<String, List<String>>()  // id -> option labels
    private val selectSel = HashMap<String, Int>()        // id -> chosen index
    private val datePickerIds = HashSet<String>()         // DatePicker node ids (a Button that opens the dialog)
    private val datePickerModes = HashMap<String, String>() // id -> "date"|"time"|"datetime"
    private val datePickerVals = HashMap<String, String>()  // id -> current ISO value
    private val menuIds = HashSet<String>()                // Menu node ids (a Button opening a PopupMenu)
    private val menuData = HashMap<String, List<String>>()  // id -> [label, item0, item1, ...]
    private val contextMenuIds = HashSet<String>()         // ContextMenu node ids (long-press wrappers)
    private val contextMenuData = HashMap<String, List<String>>()  // id -> [item0, item1, ...]
    private val mapIds = HashSet<String>()                 // Map node ids (an OSM web view)
    private val gestureIds = HashSet<String>()             // Gesture node ids (GestureDetector wrappers)
    private val alertIds = HashSet<String>()              // Alert node ids (native AlertDialog)
    private val alertData = HashMap<String, List<String>>()  // id -> [title, message, confirm, cancel]
    private val alertActions = HashMap<String, String>()  // id -> button-dispatch action
    private var presentedAlertId: String? = null          // Alert id currently on screen
    private var presentedAlertDialog: android.app.AlertDialog? = null
    private val imageCache = HashMap<String, android.graphics.Bitmap>()  // URL -> decoded bitmap
    private val bgImageViews = HashMap<String, ImageView>()              // ImageBackground id -> its backing image view
    private var refreshAction = ""                                       // Scroll onRefresh action
    private var refreshSpinner: ProgressBar? = null                      // pull-to-refresh spinner (overlaid on root)
    private var pullStartY = 0f
    private var pulling = false
    private var contentId = ""
    private var frame = 0
    private val handler = Handler(Looper.getMainLooper())

    // StatusBar directive: hide/show the system status bar (platform flags, no androidx).
    @Suppress("DEPRECATION")
    private fun setStatusBarHidden(hidden: Boolean) {
        val d = window.decorView
        d.systemUiVisibility =
            if (hidden) d.systemUiVisibility or View.SYSTEM_UI_FLAG_FULLSCREEN
            else d.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN.inv()
    }
    // StatusBar content: "dark" = dark icons (LIGHT_STATUS_BAR flag), "light" = light
    // icons (clear it), "" = leave to the system.
    @Suppress("DEPRECATION")
    private fun setStatusBarStyle(style: String) {
        val d = window.decorView
        d.systemUiVisibility = when (style) {
            "dark"  -> d.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            "light" -> d.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            else    -> d.systemUiVisibility
        }
    }

    // Report the system-bar (safe-area) insets to the engine, in dp (Chuks lengths are
    // dp on Android). Re-render if they changed so inset-using components update.
    private var lastInsets = intArrayOf(-1, -1, -1, -1)
    @Suppress("DEPRECATION")
    private fun reportInsets() {
        if (devMode) return   // dev server has no /insets endpoint; uses default insets
        val wi = window.decorView.rootWindowInsets ?: return
        val t = (wi.systemWindowInsetTop / density).toInt()
        val r = (wi.systemWindowInsetRight / density).toInt()
        val b = (wi.systemWindowInsetBottom / density).toInt()
        val l = (wi.systemWindowInsetLeft / density).toInt()
        if (t != lastInsets[0] || r != lastInsets[1] || b != lastInsets[2] || l != lastInsets[3]) {
            lastInsets = intArrayOf(t, r, b, l)
            N.setInsets(t, r, b, l); N.tick(); applyDrain(); relayout()
        }
    }

    // Is the OS currently in night (dark) mode?
    private fun osDark(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    // OS appearance changed at runtime: report it and re-render. Requires
    // android:configChanges="uiMode" in the manifest so we aren't recreated instead.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        orientationTokens.toList().forEach { resolve(it, currentOrientation()) }   // rotation stream (works in dev too)
        // Rotation changes the window size; onConfigurationChanged fires BEFORE the new
        // measure, so re-layout after it (post) or the tree keeps the old width.
        root.post { reportInsets(); if (pushViewport()) relayout() else relayout() }
        if (devMode) return   // dev server has no /colorScheme endpoint
        val dark = (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        N.setColorScheme(if (dark) 1 else 0)
        N.tick(); applyDrain(); relayout()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        density = resources.displayMetrics.density
        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0E1116"))
        setContentView(root)

        // DEV hot reload: assets/chuks-dev.txt (written by a DEV=1 build) points at the
        // running dev server. Present => fetch the UI over HTTP instead of the JNI engine.
        try { assets.open("chuks-dev.txt").bufferedReader().use { devBase = "http://" + it.readText().trim() } } catch (e: Exception) {}
        // Chuks Preview: a server chosen at runtime on the connect screen wins over any
        // bundled one, so one generic host can point at any `chuks dev`.
        getSharedPreferences("chuks.preview", MODE_PRIVATE).getString("host", "")?.let {
            if (it.isNotEmpty()) devBase = "http://$it"
        }

        if (!devMode) {
            N.setup(1000)
            val isTablet = if (resources.configuration.smallestScreenWidthDp >= 600) 1 else 0
            N.setPlatform("android", android.os.Build.VERSION.RELEASE, android.os.Build.MODEL, isTablet)   // platform + device info
            N.setColorScheme(if (osDark()) 1 else 0)   // open in the OS appearance
        }
        hostMount()

        // first layout after the window is measured
        root.post { reportInsets(); relayout(); if (pushViewport()) relayout() }
        root.setOnApplyWindowInsetsListener { _, insets -> reportInsets(); insets }   // update on inset changes

        if (devMode) {
            // Poll the dev server off the main thread; when it comes back after a chuks
            // watch restart, remount so the edit shows with no rebuild.
            Thread {
                while (true) {
                    try { Thread.sleep(400) } catch (e: InterruptedException) { return@Thread }
                    val up = devReq("/state", "", get = true) != null
                    if (up && !devConnected) {
                        // Server came back after a chuks watch restart: recreate the Activity
                        // for a clean remount (onCreate re-fetches the full tree), and stop this
                        // poll — the new Activity starts its own.
                        handler.post { recreate() }
                        return@Thread
                    }
                    devConnected = up
                }
            }.apply { isDaemon = true; start() }
        } else {
            // tick timer
            val ticker = object : Runnable {
                override fun run() {
                    frame++
                    N.tick(); applyDrain(); relayout()
                    handler.postDelayed(this, 400)
                }
            }
            handler.postDelayed(ticker, 400)
        }
    }

    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density

    // ---- viewport / scroll -------------------------------------------------
    private fun pushViewport(): Boolean {
        val sc = listScroll ?: return false
        val h = sc.height
        if (h <= 0) return false
        val topDp = (sc.scrollY / density).toInt()
        val hDp = (h / density).toInt()
        if (devMode) { applyStream(devBlocking("/viewport", "$topDp $hDp")); return true }
        if (N.viewport(topDp, hDp) > 0) { applyDrain(); return true }
        return false
    }

    // ---- apply the mutation stream ----------------------------------------
    private fun applyDrain() { applyStream(N.drain()) }

    private fun applyStream(stream: String) {
        if (stream.isEmpty()) return
        for (raw in stream.split("\n")) {
            val f = raw.split("|")
            when (f.getOrNull(0)) {
                "C" -> if (f.size >= 3) make(f[1], f[2])
                "S" -> if (f.size >= 3) style(f[1], f[2])
                "P" -> if (f.size >= 3) setText(f[1], f[2])
                "T" -> if (f.size >= 3) bindAction(f[1], f[2])
                "I" -> if (f.size >= 4) insert(f[1], f[2], f[3].toIntOrNull() ?: 0)
                "R" -> if (f.size >= 2) remove(f[1])
                "X" -> if (f.size >= 3) {
                    // Async host->engine command: X|token|capability|args. Run AFTER this
                    // applyDrain() (main-looper post), so a sync capability's resolve()
                    // doesn't re-enter applyDrain(). args may contain '|'.
                    val token = f[1]; val cap = f[2]
                    val args = if (f.size >= 4) f.subList(3, f.size).joinToString("|") else ""
                    Handler(Looper.getMainLooper()).post { handleCommand(token, cap, args) }
                }
            }
        }
    }

    // ================= DEV hot reload =======================================
    // Built with DEV=1, android/build.sh drops assets/chuks-dev.txt holding
    // "<host>:<port>" (e.g. 10.0.2.2:7799, the emulator's alias for the host loopback).
    // The engine then runs in the Chuks VM dev server (chuks watch) and this host
    // fetches the mutation stream over HTTP instead of the JNI-linked library, so saving
    // a .chuks file hot-reloads the running app with no rebuild. Yoga layout stays JNI.
    private var devBase = ""            // "http://10.0.2.2:7799"; empty => production (JNI)
    private val devMode get() = devBase.isNotEmpty()
    private var devConnected = true

    // Synchronous HTTP to the dev server. MUST run off the main thread. Returns null on a
    // network error (the server is briefly down while chuks watch restarts it).
    private fun devReq(path: String, body: String, get: Boolean = false): String? {
        return try {
            val c = java.net.URL(devBase + path).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 1500; c.readTimeout = 2000
            c.requestMethod = if (get) "GET" else "POST"
            if (!get) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            if (c.responseCode !in 200..299) { c.disconnect(); return null }
            val s = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            c.disconnect(); s
        } catch (e: Exception) { null }
    }

    // Run a dev request on a worker thread and block briefly for the result. Called from
    // the main thread at dispatch sites; requests are ~1ms while the server is up, and
    // taps during the short restart window are rare.
    private fun devBlocking(path: String, body: String): String {
        var out = ""
        val t = Thread { out = devReq(path, body) ?: "" }
        t.start(); t.join(2500)
        return out
    }

    // Engine calls: DEV routes to the dev server, production to the JNI library. Each
    // returns the mutation stream (production folds the op and drain into one call).
    private fun engMount() = if (devMode) devBlocking("/mount", "") else { N.mount(); N.drain() }
    private fun engEvent(a: String) = if (devMode) devBlocking("/event", a) else { N.event(a); N.drain() }
    private fun engInput(a: String, v: String) = if (devMode) devBlocking("/input", "$a\n$v") else { N.input(a, v); N.drain() }
    private fun engResolve(t: String, p: String) = if (devMode) devBlocking("/resolve", "$t\n$p") else { N.resolve(t, p); N.drain() }
    private fun engFail(t: String, m: String) = if (devMode) devBlocking("/fail", "$t\n$m") else { N.fail(t, m); N.drain() }

    // Dispatch helpers used by every widget handler, so DEV vs production routing lives in
    // one place. They apply the resulting stream and relayout on the main thread.
    private fun hostMount() { applyStream(engMount()); relayout() }
    private fun hostEvent(a: String) { applyStream(engEvent(a)); relayout() }
    private fun hostInput(a: String, v: String) { applyStream(engInput(a, v)); relayout() }

    // Deliver a native capability result back to the engine and apply the re-render.
    private fun resolve(token: String, payload: String) {
        applyStream(engResolve(token, payload)); relayout()
    }
    // Report a capability failure back to the engine (fires the request's onErr).
    private fun fail(token: String, message: String) {
        applyStream(engFail(token, message)); relayout()
    }

    // Live native subscriptions (stream token -> repeating Runnable), for teardown.
    private val streamHandler = Handler(Looper.getMainLooper())
    private val activeStreams = mutableMapOf<String, Runnable>()
    // Real OS streams (battery/app-state/network): a teardown closure per token,
    // run on __cancel__ so the receiver/callback is unregistered.
    private val streamTeardown = mutableMapOf<String, () -> Unit>()
    private val appStateTokens = mutableSetOf<String>()   // tokens watching foreground/background
    private val orientationTokens = mutableSetOf<String>()   // tokens watching device orientation
    private fun currentOrientation(): String =
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"

    // Execute a native capability requested via an `X|` command (F3). Fire-and-forget
    // commands (token "0") just perform the side effect; async reads call resolve().
    private fun handleCommand(token: String, cap: String, args: String) {
        when (cap) {
            "__cancel__" -> {
                // Chuks cancelled this token (unmount / explicit): stop + drop the
                // repeating runnable so the native subscription is released.
                activeStreams.remove(token)?.let { streamHandler.removeCallbacks(it) }
                streamTeardown.remove(token)?.invoke()
                appStateTokens.remove(token)
            }
            "pulse.watch" -> {
                // A stream: tick a counter every 150ms (~7Hz) until cancelled — smooth
                // per-tick re-render (a fast test-only stream; real streams tick slower).
                val count = intArrayOf(0)
                val r = object : Runnable {
                    override fun run() {
                        count[0]++; resolve(token, count[0].toString())
                        if (activeStreams.containsKey(token)) streamHandler.postDelayed(this, 150)
                    }
                }
                activeStreams[token] = r
                streamHandler.postDelayed(r, 150)
            }
            "battery.watch" -> {
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) { i?.let { emitBattery(token, it) } }
                }
                // registerReceiver returns the current sticky battery Intent -> emit now.
                val sticky = registerReceiver(receiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                streamTeardown[token] = { try { unregisterReceiver(receiver) } catch (e: Exception) {} }
                sticky?.let { emitBattery(token, it) }
            }
            "appstate.watch" -> {
                appStateTokens.add(token)
                streamTeardown[token] = { appStateTokens.remove(token) }
                resolve(token, if (appForeground) "active" else "background")
            }
            "network.watch" -> {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(n: android.net.Network) { emitNetwork(token, cm) }
                    override fun onLost(n: android.net.Network) { runOnUiThread { resolve(token, "none") } }
                    override fun onCapabilitiesChanged(n: android.net.Network, caps: android.net.NetworkCapabilities) { emitNetwork(token, cm) }
                }
                cm.registerDefaultNetworkCallback(cb)
                streamTeardown[token] = { try { cm.unregisterNetworkCallback(cb) } catch (e: Exception) {} }
                emitNetwork(token, cm)
            }
            "debug.activeStreams" -> resolve(token, (activeStreams.size + streamTeardown.size).toString())
            "debug.fail" -> fail(token, "simulated native failure")
            "permission.status" -> {
                val p = permString(args)
                if (p == null) resolve(token, "undetermined")
                else resolve(token, if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) "granted" else "denied")
            }
            "permission.request" -> {
                val p = permString(args)
                if (p == null) fail(token, "unknown permission: $args")
                else if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) resolve(token, "granted")
                else {
                    val code = ++permSeq
                    pendingPerms[code] = token       // resolved in onRequestPermissionsResult
                    requestPermissions(arrayOf(p), code)
                }
            }
            "fs.write" -> {
                val bar = args.indexOf('|')   // "name|<base64 content>"
                if (bar >= 0) {
                    val name = args.substring(0, bar)
                    val content = String(android.util.Base64.decode(args.substring(bar + 1), android.util.Base64.DEFAULT))
                    java.io.File(filesDir, name).writeText(content)
                }
            }
            "fs.read" -> {
                val f = java.io.File(filesDir, args)
                if (f.exists()) resolve(token, f.readText()) else fail(token, "no such file: $args")
            }
            "fs.list" -> resolve(token, (filesDir.listFiles()?.map { it.name } ?: emptyList()).joinToString("\n"))
            "fs.delete" -> java.io.File(filesDir, args).delete()
            "secure.set" -> {
                val bar = args.indexOf('|')   // "key|<base64 value>"
                if (bar >= 0) {
                    val key = args.substring(0, bar)
                    val value = String(android.util.Base64.decode(args.substring(bar + 1), android.util.Base64.DEFAULT))
                    secureSet(key, value)
                }
            }
            "secure.get" -> { val v = secureGet(args); if (v != null) resolve(token, v) else fail(token, "no such key: $args") }
            "secure.delete" -> securePrefs().edit().remove(args).apply()
            "notif.notify" -> {
                val bar = args.indexOf('|')   // "<base64 title>|<base64 body>"
                val title = if (bar >= 0) decodeB64(args.substring(0, bar)) else decodeB64(args)
                val body = if (bar >= 0) decodeB64(args.substring(bar + 1)) else ""
                notify(title, body)
            }
            "audio.play" -> {
                audioMp?.release(); audioMp = null
                val mp = MediaPlayer()
                try {
                    val afd = assets.openFd(args)
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length); afd.close()
                    mp.setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    mp.prepare(); mp.start()   // synchronous prepare: the asset is a local file
                    audioMp = mp
                } catch (e: Exception) { mp.release() }   // bad asset / bad state: leave nothing playing
            }
            "audio.pause" -> audioMp?.let { if (it.isPlaying) it.pause() }
            "audio.resume" -> audioMp?.start()
            "audio.stop" -> audioMp?.let { if (it.isPlaying) it.pause(); it.seekTo(0) }
            "audio.position" -> {
                val mp = audioMp
                resolve(token, "${mp?.currentPosition ?: 0}/${mp?.duration ?: 0}")
            }
            "tts.speak" -> {
                val text = decodeB64(args)
                ensureTts()
                if (ttsReady) speakNow(text) else pendingSpeak = text   // speak once the engine finishes init
            }
            "tts.stop" -> tts?.stop()
            "tts.isSpeaking" -> resolve(token, if (tts?.isSpeaking == true) "1" else "0")
            "clipboard.set" -> clipboard().setPrimaryClip(ClipData.newPlainText("", args))
            "clipboard.get" -> {
                val t = clipboard().primaryClip?.let { if (it.itemCount > 0) it.getItemAt(0).coerceToText(this).toString() else "" } ?: ""
                resolve(token, t)
            }
            "linking.open" -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(args))) } catch (_: Exception) {}
            "linking.canOpen" -> {
                val ok = Intent(Intent.ACTION_VIEW, Uri.parse(args)).resolveActivity(packageManager) != null
                resolve(token, if (ok) "1" else "0")
            }
            "share.text", "share.url" -> {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, args)
                startActivity(Intent.createChooser(i, null))
            }
            "haptics.impact" -> fireHaptic(args)
            "torch.set" -> setTorch(args == "1")
            "brightness.set" -> args.toFloatOrNull()?.let {
                val lp = window.attributes; lp.screenBrightness = it.coerceIn(0f, 1f); window.attributes = lp
            }
            "brightness.keepAwake" ->
                if (args == "1") window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            "orientation.watch" -> {
                orientationTokens.add(token)
                streamTeardown[token] = { orientationTokens.remove(token) }
                resolve(token, currentOrientation())
            }
            "orientation.lock" -> requestedOrientation = when (args) {
                "portrait"  -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else        -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // Permission (F2): map a Chuks kind to an Android permission string, and hold the
    // request token until the async onRequestPermissionsResult callback fires.
    private var permSeq = 0
    private val pendingPerms = mutableMapOf<Int, String>()   // requestCode -> engine token
    private fun permString(kind: String): String? = when (kind) {
        "camera" -> Manifest.permission.CAMERA
        "microphone" -> Manifest.permission.RECORD_AUDIO
        "location" -> Manifest.permission.ACCESS_FINE_LOCATION
        "notifications" -> Manifest.permission.POST_NOTIFICATIONS   // runtime perm on API 33+
        "photos" -> Manifest.permission.READ_MEDIA_IMAGES           // API 33+
        else -> null
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grantResults)
        val token = pendingPerms.remove(code) ?: return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        resolve(token, if (granted) "granted" else "denied")
    }

    // Secure storage (Tier B): values are AES-GCM encrypted with an AndroidKeyStore
    // key (never leaves the secure hardware) and the ciphertext kept in a private
    // SharedPreferences. iv is prepended to the ciphertext, the whole blob base64'd.
    private fun secureKey(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore"); ks.load(null)
        (ks.getEntry("chuks_secure", null) as? java.security.KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(android.security.keystore.KeyGenParameterSpec.Builder("chuks_secure",
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return kg.generateKey()
    }
    private fun securePrefs() = getSharedPreferences("chuks_secure", Context.MODE_PRIVATE)
    private fun secureSet(key: String, value: String) {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); c.init(javax.crypto.Cipher.ENCRYPT_MODE, secureKey())
        val iv = c.iv; val ct = c.doFinal(value.toByteArray())
        val blob = iv + ct
        securePrefs().edit().putString(key, android.util.Base64.encodeToString(blob, android.util.Base64.DEFAULT)).apply()
    }
    private fun secureGet(key: String): String? {
        val enc = securePrefs().getString(key, null) ?: return null
        val blob = android.util.Base64.decode(enc, android.util.Base64.DEFAULT)
        val iv = blob.copyOfRange(0, 12); val ct = blob.copyOfRange(12, blob.size)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, secureKey(), javax.crypto.spec.GCMParameterSpec(128, iv))
        return String(c.doFinal(ct))
    }

    private fun decodeB64(s: String) = String(android.util.Base64.decode(s, android.util.Base64.DEFAULT))

    private var notifId = 1
    private var audioMp: MediaPlayer? = null   // single-track audio playback (Tier B)

    // Text-to-speech (Tier B). The engine inits asynchronously; a speak() that
    // arrives before onInit is stashed in pendingSpeak and flushed once ready.
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: String? = null
    private fun ensureTts() {
        if (tts != null) return
        tts = android.speech.tts.TextToSpeech(this) { status ->
            ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setLanguage(java.util.Locale.US)
                // Route speech through the media/speaker path (same as Audio playback),
                // else the engine default can land on a track that isn't audible.
                tts?.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                pendingSpeak?.let { speakNow(it); pendingSpeak = null }
            }
        }
    }
    private fun speakNow(text: String) {
        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "chuks")
    }

    // ---- Real streams: battery / app-state / network ----
    private var appForeground = true
    private fun emitBattery(token: String, i: Intent) {
        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val charging = if (status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == android.os.BatteryManager.BATTERY_STATUS_FULL) 1 else 0
        runOnUiThread { resolve(token, "$pct,$charging") }
    }
    private fun emitNetwork(token: String, cm: android.net.ConnectivityManager) {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val s = when {
            caps == null -> "none"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        runOnUiThread { resolve(token, s) }
    }
    override fun onResume() {
        super.onResume(); appForeground = true
        appStateTokens.toList().forEach { resolve(it, "active") }
    }
    override fun onPause() {
        super.onPause(); appForeground = false
        appStateTokens.toList().forEach { resolve(it, "background") }
    }
    private fun notify(title: String, body: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = android.app.NotificationChannel("chuks", "Chuks", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(chan)
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    android.app.Notification.Builder(this, "chuks")
                else @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        val n = b.setContentTitle(title).setContentText(body)
                 .setSmallIcon(android.R.drawable.ic_dialog_info)
                 .setAutoCancel(true).build()
        nm.notify(notifId++, n)
    }

    private fun clipboard() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun fireHaptic(style: String) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        val ms = when (style) { "light", "selection" -> 10L; "heavy", "error" -> 40L; else -> 20L }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(ms)
    }

    private fun setTorch(on: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return
            cm.setTorchMode(id, on)
        } catch (_: Exception) {}
    }

    private fun make(id: String, kind: String) {
        if (views.containsKey(id)) return
        val n = N.yNew()
        val v: View = when (kind) {
            "Text" -> TextView(this).also { it.gravity = Gravity.CENTER_VERTICAL }
            "Video" -> TextureView(this).also { it.isOpaque = false }   // MediaPlayer target (iOS: AVPlayerLayer)
            "WebView" -> android.webkit.WebView(this).also {            // native WebView (iOS: WKWebView)
                it.settings.javaScriptEnabled = true
                it.settings.domStorageEnabled = true
                it.setBackgroundColor(Color.TRANSPARENT)
            }
            "Map" -> android.webkit.WebView(this).also {                // OpenStreetMap in a web view (iOS: native MapKit)
                mapIds.add(id)
                it.settings.javaScriptEnabled = true
                it.settings.domStorageEnabled = true
            }
            "Canvas" -> DrawCanvas(this)                                 // vector drawing (iOS: Core Graphics / SwiftUI Canvas)
            "Gesture" -> FrameLayout(this).also { g ->                   // swipe / double-tap / long-press (iOS: UIGestureRecognizers)
                gestureIds.add(id)
                val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true
                    override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                        val dx = e2.x - (e1?.x ?: 0f); val dy = e2.y - (e1?.y ?: 0f)
                        val dir = if (Math.abs(dx) > Math.abs(dy)) (if (dx < 0) "left" else "right") else (if (dy < 0) "up" else "down")
                        dispatchGesture(g, "swipe:$dir"); return true
                    }
                    override fun onDoubleTap(e: MotionEvent): Boolean { dispatchGesture(g, "doubletap"); return true }
                    override fun onLongPress(e: MotionEvent) { dispatchGesture(g, "longpress") }
                })
                g.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }
            }
            "Image" -> ImageView(this).also { it.scaleType = ImageView.ScaleType.CENTER_CROP }   // remote URL image (SF Symbols are iOS-only)
            "ImageBackground" -> FrameLayout(this).also { box ->
                val iv = ImageView(this)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                box.addView(iv)                          // background, behind children
                bgImageViews[id] = iv
            }
            "Button" -> Button(this).also { it.setPadding(0, 0, 0, 0); it.gravity = Gravity.CENTER
                it.setOnClickListener { fire((it as View).getTag(TAG) as? String ?: "") } }
            "Input" -> EditText(this).also {
                it.setSingleLine(); it.setPadding(dp(10), 0, dp(10), 0)
                it.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {}
                    override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
                    override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {
                        val action = it.getTag(TAG) as? String ?: return
                        applyStream(engInput(action, it.text.toString()))
                        listScroll?.scrollTo(0, 0); relayout()
                    } }) }
            "Alert" -> View(this).also { alertIds.add(id) }   // invisible placeholder; the OS dialog shows on avis=1
            "TextArea" -> EditText(this).also {
                it.setPadding(dp(10), dp(8), dp(10), dp(8))
                it.gravity = Gravity.TOP or Gravity.START
                it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                it.isSingleLine = false
                it.setHorizontallyScrolling(false)
                it.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {}
                    override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
                    override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {
                        val action = it.getTag(TAG) as? String ?: return
                        hostInput(action, it.text.toString())   // keep scroll pos (no scrollTo)
                    } }) }
            "Spinner" -> ProgressBar(this).also { it.isIndeterminate = true }   // circular indeterminate; Yoga sizes it via w/h
            "Progress" -> ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).also {
                it.isIndeterminate = false; it.max = 100
            }
            "Select" -> Button(this).also { b ->
                selectIds.add(id)
                b.isAllCaps = false
                b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                // a down-chevron at the trailing edge (drawableEnd draws at the right edge)
                val chevron = getDrawable(android.R.drawable.arrow_down_float)?.mutate()
                chevron?.setTint(Color.parseColor("#8E8E93"))
                b.setCompoundDrawablesWithIntrinsicBounds(null, null, chevron, null)
                b.setOnClickListener {
                    val opts = selectOptions[id] ?: return@setOnClickListener
                    val pm = PopupMenu(this, b)
                    opts.forEachIndexed { i, label -> pm.menu.add(0, i, i, label) }
                    pm.setOnMenuItemClickListener { mi ->
                        (b.getTag(TAG) as? String)?.let { hostInput(it, mi.itemId.toString()) }
                        true
                    }
                    pm.show()
                }
            }
            "Menu" -> Button(this).also { b ->
                menuIds.add(id); b.isAllCaps = false
                b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                b.setOnClickListener {
                    val items = (menuData[id] ?: return@setOnClickListener).drop(1)   // [0] is the label
                    val pm = PopupMenu(this, b)
                    items.forEachIndexed { i, t -> pm.menu.add(0, i, i, t) }
                    pm.setOnMenuItemClickListener { mi ->
                        (b.getTag(TAG) as? String)?.let { hostInput(it, mi.itemId.toString()) }; true
                    }
                    pm.show()
                }
            }
            "ContextMenu" -> FrameLayout(this).also { c ->
                contextMenuIds.add(id)
                c.setOnLongClickListener {
                    val items = contextMenuData[id] ?: return@setOnLongClickListener false
                    val pm = PopupMenu(this, c)
                    items.forEachIndexed { i, t -> pm.menu.add(0, i, i, t) }
                    pm.setOnMenuItemClickListener { mi ->
                        (c.getTag(TAG) as? String)?.let { hostInput(it, mi.itemId.toString()) }; true
                    }
                    pm.show(); true
                }
            }
            "Slider" -> SeekBar(this).also { sb ->
                sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return                       // ignore programmatic setProgress (the controlled sync)
                        val action = sb.getTag(TAG) as? String ?: return
                        val min = sliderMin[id] ?: 0
                        hostInput(action, (progress + min).toString())
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            // Android's idiomatic date/time UI is a modal Material dialog, so both the
            // compact and the (iOS-only) inline/wheels displays open the same dialog here.
            "DatePicker", "DatePickerInline" -> Button(this).also { b ->
                datePickerIds.add(id)
                b.isAllCaps = false
                b.gravity = Gravity.START or Gravity.CENTER_VERTICAL   // value hugs the leading edge
                b.setOnClickListener { openDateDialog(id, b) }
                // inline/wheels don't carry a compact height from Chuks (that's for the
                // always-open iOS picker); give the dialog-button one so it stays visible.
                if (kind == "DatePickerInline") N.ySetF(n, 6, dpf(48f))
            }
            "Switch" -> Switch(this).also { sw ->
                // Kill the default clickable-view chrome: the ripple/selection outline
                // otherwise draws a full-bounds rectangle around the row (two faint
                // horizontal lines). We paint our own state; the switch needs no bg.
                sw.background = null
                sw.isFocusable = false; sw.isFocusableInTouchMode = false
                // onClick fires ONLY on user interaction; a programmatic setChecked
                // (the re-render syncing Chuks state) does NOT, so no dispatch loop.
                sw.setOnClickListener { fire((sw as View).getTag(TAG) as? String ?: "") }
                val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                sw.measure(spec, spec)
                N.ySetF(n, 5, sw.measuredWidth.toFloat()); N.ySetF(n, 6, sw.measuredHeight.toFloat())
            }
            "Scroll" -> ScrollView(this).also { sc ->
                sc.isFillViewport = false
                sc.viewTreeObserver.addOnScrollChangedListener { if (pushViewport()) relayout(); updateVideoVisibility() }
                sc.viewTreeObserver.addOnGlobalLayoutListener { updateVideoVisibility() }   // attach on-screen videos on the initial (static) layout too
                listScroll = sc; scrollId = id }
            "Modal" -> FrameLayout(this).also {         // full-screen dimmed scrim; content laid out inside
                it.setBackgroundColor(Color.argb(128, 0, 0, 0))
                it.visibility = View.GONE                // shown when mvis=1
                modalIds.add(id)
            }
            else -> FrameLayout(this)
        }
        views[id] = v
        ynodes[id] = n
    }

    // pending visual state per view (bg color + radius) -> a GradientDrawable
    private val bgColor = HashMap<String, Int>()
    private val bgRadius = HashMap<String, Float>()
    private val pressOpacity = HashMap<String, Float>()   // id -> Pressable active alpha (0-1)
    private val borderW = HashMap<String, Float>()   // border width (px)
    private val borderC = HashMap<String, Int>()     // border color
    private val textWidthPx = HashMap<String, Float>()   // id -> explicit Text width (px), so text WRAPS to it
    private val iconFonts = HashMap<String, android.graphics.Typeface>()   // custom fonts by name, cached
    private fun iconFont(name: String): android.graphics.Typeface =
        iconFonts.getOrPut(name) { android.graphics.Typeface.createFromAsset(assets, "$name.ttf") }

    private fun style(id: String, s: String) {
        val n = ynodes[id] ?: return
        val v = views[id] ?: return
        // style() always receives the FULL style, so drop any stale visual state for
        // this id first. Node ids are reused when a screen swaps in place; without
        // this a previous bordered/filled element leaves its border/bg under the new
        // one (e.g. a stray outline around a row that later holds a Switch).
        bgColor.remove(id); bgRadius.remove(id); borderW.remove(id); borderC.remove(id); pressOpacity.remove(id); textWidthPx.remove(id)
        var fsPx = dpf(14f)
        var bold = false
        var customFont = ""   // a registered font family (e.g. an icon font)
        var slV = -1; var slMin = 0; var slMax = 100   // Slider: collected in-loop, applied after (order-independent)
        // animation: collect transform + opacity, apply (animated) after the loop
        var tx = 0f; var ty = 0f; var sc = 1f; var rot = 0f
        var hasTransform = false; var opacity: Float? = null; var animMs = -1; var animEz = ""
        for (kv in s.split(";")) {
            if (kv.isEmpty()) continue
            val p = kv.split("="); if (p.size != 2) continue
            val k = p[0]; val vl = p[1]
            val f = vl.toFloatOrNull() ?: 0f
            when (k) {
                "d" -> N.ySetF(n, 0, if (vl == "row") 1f else 0f)
                "j" -> N.ySetF(n, 1, justify(vl))
                "a" -> N.ySetF(n, 2, align(vl))
                "g" -> N.ySetF(n, 3, f)
                "basis" -> N.ySetF(n, 4, dpf(f))
                "w" -> { N.ySetF(n, 5, dpf(f)); textWidthPx[id] = dpf(f) }   // record so Text wraps to this width
                "h" -> { N.ySetF(n, 6, dpf(f))
                    // A ScrollView measures its child with UNSPECIFIED height, so a
                    // FrameLayout content sizes to its (windowed) children and ignores the
                    // Yoga height — collapsing the scroll range. minimumHeight forces the
                    // content to measure to its true (tall) height so the List scrolls.
                    v.minimumHeight = dpf(f).toInt() }
                "p" -> N.ySetF(n, 7, dpf(f))
                "gap" -> N.ySetF(n, 8, dpf(f))
                "pos" -> if (vl == "abs") N.ySetF(n, 9, 0f)
                "top" -> N.ySetF(n, 10, dpf(f))
                "left" -> N.ySetF(n, 11, dpf(f))
                "right" -> N.ySetF(n, 12, dpf(f))
                "on" -> (v as? Switch)?.isChecked = (vl == "1")
                "bg" -> if (v is Switch) {
                    v.trackTintList = ColorStateList.valueOf(Color.parseColor("#$vl"))   // on-track = primary
                    v.thumbTintList = ColorStateList.valueOf(Color.WHITE)                // white thumb, like iOS
                } else bgColor[id] = Color.parseColor("#$vl")
                "r" -> bgRadius[id] = dpf(f)
                "bw" -> borderW[id] = dpf(f)
                "bc" -> borderC[id] = Color.parseColor("#$vl")
                "opacity" -> opacity = f / 100f
                "tx" -> { tx = f; hasTransform = true }
                "ty" -> { ty = f; hasTransform = true }
                "rot" -> { rot = f; hasTransform = true }
                "sc" -> { sc = f / 100f; hasTransform = true }
                "anim" -> animMs = f.toInt()
                "ez" -> animEz = vl
                "shadow" -> v.elevation = dpf(if (f >= 3f) 12f else if (f == 2f) 6f else 3f)
                "wrap" -> N.ySetF(n, 13, if (vl == "wrap") 1f else 0f)
                "fs" -> fsPx = dpf(f)
                "fw" -> bold = (vl == "bold" || vl == "semibold")
                "slv" -> slV = f.toInt()                 // Slider: current value
                "slmin" -> slMin = f.toInt()             // Slider: min
                "slmax" -> slMax = f.toInt()             // Slider: max
                "seli" -> if (selectIds.contains(id)) { selectSel[id] = f.toInt(); (v as? Button)?.text = selectLabel(id) }
                "dp" -> if (datePickerIds.contains(id)) { datePickerModes[id] = vl; (v as? Button)?.text = dateLabel(id) }
                "avis" -> if (vl == "1") root.post { presentAlert(id) } else dismissAlert(id)   // defer present until the batch applies
                "prog" -> (v as? ProgressBar)?.progress = f.toInt()   // determinate progress fill
                "rmode" -> {                              // Image resize mode
                    val st = if (vl == "contain") ImageView.ScaleType.FIT_CENTER else if (vl == "center") ImageView.ScaleType.CENTER else ImageView.ScaleType.CENTER_CROP
                    (v as? ImageView)?.scaleType = st; bgImageViews[id]?.scaleType = st
                }
                "fg" -> { val c = Color.parseColor("#$vl")
                    (v as? TextView)?.setTextColor(c); (v as? EditText)?.setTextColor(c)
                    if (v is SeekBar) { v.progressTintList = ColorStateList.valueOf(c); v.thumbTintList = ColorStateList.valueOf(c) }
                    else if (v is ProgressBar) { if (v.isIndeterminate) v.indeterminateTintList = ColorStateList.valueOf(c) else v.progressTintList = ColorStateList.valueOf(c) } }
                "ta" -> (v as? TextView)?.gravity =
                    (if (vl == "right") Gravity.END else if (vl == "center") Gravity.CENTER else Gravity.START) or Gravity.CENTER_VERTICAL
                "font" -> customFont = vl
                "vid" -> videoWanted[id] = vl   // a Video node wants this clip; a player is attached only while it's on screen (see updateVideoVisibility)
                "press" -> pressOpacity[id] = f / 100f   // Pressable active alpha
                "sec" -> (v as? EditText)?.let {         // password field: mask input
                    if (vl == "1") {
                        it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                        it.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                    }
                }
                "sbh" -> setStatusBarHidden(vl == "1")   // StatusBar: hide/show
                "sbstyle" -> setStatusBarStyle(vl)       // StatusBar: light/dark icons
                "mvis" -> {                              // Modal visible: show/hide the overlay
                    val vis = (vl == "1")
                    v.visibility = if (vis) View.VISIBLE else View.GONE
                    if (vis) { activeModal = id; v.bringToFront() } else if (activeModal == id) activeModal = null
                    root.requestLayout()
                }
                "mpos" -> {                              // sheet pins bottom + stretches; dialog centers
                    val bottom = (vl == "bottom")
                    if (bottom) sheetModals.add(id) else sheetModals.remove(id)
                    N.ySetF(n, 1, if (bottom) 3f else 2f)   // justify: flex-end(3) vs center(2)
                    N.ySetF(n, 2, if (bottom) 4f else 2f)   // align: stretch(4) vs center(2)
                }
            }
        }
        (v as? TextView)?.let {
            it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fsPx)
            if (customFont.isNotEmpty()) it.typeface = iconFont(customFont)
            else it.setTypeface(null, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            // Size/font/width just changed; re-measure so wrapped height (and any
            // fixed width) are correct regardless of style-vs-text op order.
            if (it.text.isNotEmpty()) measureText(id, it)
        }
        if (v is SeekBar && slV >= 0) {              // Slider: SeekBar is 0-based, so offset by min
            sliderMin[id] = slMin
            v.max = maxOf(1, slMax - slMin)
            if (!v.isPressed) v.progress = slV - slMin   // don't fight an active drag (controlled sync)
        }
        // Apply transform + opacity, animated natively when `anim` is set (a
        // ViewPropertyAnimator interpolates off the main render loop). Visual only.
        if (hasTransform || animMs >= 0 || opacity != null) {
            if (animMs >= 0) {
                val a = v.animate().setDuration(animMs.toLong())
                a.interpolator = when (animEz) {
                    "linear" -> android.view.animation.LinearInterpolator()
                    "spring" -> android.view.animation.OvershootInterpolator()
                    else -> android.view.animation.AccelerateDecelerateInterpolator()
                }
                if (hasTransform) { a.translationX(dpf(tx)); a.translationY(dpf(ty)); a.scaleX(sc); a.scaleY(sc); a.rotation(rot) }
                opacity?.let { a.alpha(it) }
                a.start()
            } else {
                if (hasTransform) { v.translationX = dpf(tx); v.translationY = dpf(ty); v.scaleX = sc; v.scaleY = sc; v.rotation = rot }
                opacity?.let { v.alpha = it }
            }
        }
        // bg + radius + border -> GradientDrawable (a large radius clamps to a pill)
        if (bgColor.containsKey(id) || bgRadius.containsKey(id) || borderW.containsKey(id)) {
            val gd = GradientDrawable()
            gd.setColor(bgColor[id] ?: Color.TRANSPARENT)
            gd.cornerRadius = bgRadius[id] ?: 0f
            val bw = borderW[id]
            if (bw != null && bw > 0f) gd.setStroke(bw.toInt(), borderC[id] ?: Color.parseColor("#334155"))
            v.background = gd
        } else if (v !is Switch && !modalIds.contains(id)) {
            v.background = null   // the new style has no bg/border: clear a stale drawable on a reused view
        }                        // (a Modal keeps its dim scrim background)
        // An ImageView's foreground bitmap isn't clipped by a rounded background, so
        // round it via the outline (radius 36 on a 72x72 image → a circle).
        if (v is ImageView && bgRadius.containsKey(id)) {
            val rad = bgRadius[id]!!
            v.clipToOutline = true
            v.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, rad)
                }
            }
        }
    }

    // Looping, muted video on a Video node's TextureView via the platform's
    // built-in MediaPlayer (the Android analogue of iOS AVPlayer; no ExoPlayer
    // dependency, so it works with the no-Gradle build). The asset ships in the
    // APK uncompressed so openFd hands MediaPlayer a seekable descriptor.
    //
    // Players are POOLED and reused across recycled cards (a fast fling just
    // re-attaches a warm, already-prepared player to the next card's surface
    // instead of building a new one), and the number of live decoders is CAPPED
    // so weak hardware / the emulator degrade gracefully (an over-cap card stays
    // black) instead of spamming decoder errors. Mirrors the iOS player pool.
    private val MAX_VIDEO_PLAYERS = 8                                  // hardware decoder budget
    private val videoPlayers = HashMap<String, MediaPlayer>()          // id -> attached player
    private val videoKey = HashMap<String, String>()                  // id -> asset name
    private val videoPool = HashMap<String, ArrayDeque<MediaPlayer>>() // asset -> idle, prepared players
    private val videoReady = HashSet<MediaPlayer>()                   // players past prepare()
    private val videoSizes = HashMap<MediaPlayer, Pair<Int, Int>>()   // player -> native video size
    private val videoAlive = HashSet<MediaPlayer>()                   // every live player (attached + pooled); its size IS the decoder count
    private val videoWanted = HashMap<String, String>()              // id -> asset for every mounted Video node (on- or off-screen)
    private val visRect = android.graphics.Rect()

    // Decode only the videos actually on screen. The virtualized window mounts a
    // buffer of off-screen cards too; decoding all of them oversubscribes the
    // hardware codecs (Android then reclaims codecs between players and they go
    // black). Attaching a player only while its card is visible keeps live
    // decoders to the handful on screen — what Instagram/TikTok-style feeds do,
    // and it also saves battery. Called after every layout/scroll.
    private fun updateVideoVisibility() {
        if (listScroll == null) return
        for (id in videoWanted.keys.toList()) {
            val v = views[id] ?: continue
            val onScreen = v.getGlobalVisibleRect(visRect) && visRect.height() > 0
            val hasPlayer = videoPlayers.containsKey(id)
            if (onScreen && !hasPlayer) attachVideo(id, videoWanted[id]!!)
            else if (!onScreen && hasPlayer) poolVideo(id)
        }
    }

    private fun attachVideo(id: String, asset: String) {
        if (videoPlayers.containsKey(id)) return
        val tv = views[id] as? TextureView ?: return
        val idle = videoPool[asset]
        val reused = if (idle != null && idle.isNotEmpty()) idle.removeLast() else null
        val mp: MediaPlayer
        if (reused != null) {
            mp = reused                                    // warm reuse: no alloc, no re-prepare
        } else {
            if (videoAlive.size >= MAX_VIDEO_PLAYERS) return  // decoder cap: leave the card black rather than overload
            mp = MediaPlayer()
            videoAlive.add(mp)
            try {
                if (asset.startsWith("http")) {
                    mp.setDataSource(asset)                  // remote URL
                } else {
                    val afd = assets.openFd(asset)           // bundled asset
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                }
            } catch (e: Exception) { videoAlive.remove(mp); mp.release(); return }
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener { videoReady.add(it); it.start() }
            mp.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) { videoSizes[mp] = Pair(w, h); applyCover(tv, mp) } }
            // A decoder that fails (routine on the emulator's ~2-decoder ceiling)
            // must free its slot at once, or dead players pile up and clog the cap
            // until every card goes black. Reclaim it and let another card try.
            mp.setOnErrorListener { player, _, _ -> killVideo(player); true }
        }
        videoPlayers[id] = mp
        videoKey[id] = asset
        bindSurface(tv, mp)
    }

    private fun bindSurface(tv: TextureView, mp: MediaPlayer) {
        val start = { st: SurfaceTexture ->
            try { mp.setSurface(Surface(st)) } catch (e: Exception) {}
            if (videoReady.contains(mp)) { try { mp.seekTo(0); mp.start() } catch (e: Exception) {} }
            else { try { mp.prepareAsync() } catch (e: Exception) {} }
            applyCover(tv, mp)
        }
        val ready = tv.surfaceTexture
        if (tv.isAvailable && ready != null) start(ready)
        else tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { start(st) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { applyCover(tv, mp) }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    // Center-crop the video to cover its card (iOS resizeAspectFill): TextureView
    // fills by default, so scale up uniformly around the center to remove letterbox.
    private fun applyCover(tv: TextureView, mp: MediaPlayer) {
        val size = videoSizes[mp] ?: return
        val vw = size.first; val vh = size.second
        val w = tv.width; val h = tv.height
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return
        val scale = maxOf(w.toFloat() / vw, h.toFloat() / vh)
        val m = android.graphics.Matrix()
        m.setScale(vw * scale / w, vh * scale / h, w / 2f, h / 2f)
        tv.setTransform(m)
    }

    // Drop a player from every collection and free its decoder slot. Idempotent
    // (the videoAlive guard makes a double-call, e.g. error then recycle, a no-op).
    private fun killVideo(mp: MediaPlayer) {
        if (!videoAlive.remove(mp)) return
        videoPlayers.entries.firstOrNull { it.value === mp }?.key?.let { videoPlayers.remove(it); videoKey.remove(it) }
        videoReady.remove(mp); videoSizes.remove(mp)
        videoPool.values.forEach { it.remove(mp) }
        try { mp.reset() } catch (e: Exception) {}
        try { mp.release() } catch (e: Exception) {}
    }

    // Recycle a card's player: detach it and return it to the pool warm (only a
    // healthy, prepared player), or release it if the pool is full.
    private fun poolVideo(id: String) {
        val mp = videoPlayers.remove(id) ?: return
        val asset = videoKey.remove(id) ?: ""
        try { mp.setSurface(null); mp.pause() } catch (e: Exception) {}
        val idle = videoPool.getOrPut(asset) { ArrayDeque() }
        if (videoReady.contains(mp) && idle.size < MAX_VIDEO_PLAYERS) {
            idle.addLast(mp)
        } else {
            killVideo(mp)
        }
    }

    private fun setText(id: String, t: String) {
        if (selectIds.contains(id)) {                        // a Select's "text" is its tab-joined options
            selectOptions[id] = t.split("\t")
            (views[id] as? Button)?.text = selectLabel(id)
            return
        }
        if (datePickerIds.contains(id)) {                    // a DatePicker's "text" is its ISO value
            datePickerVals[id] = t
            (views[id] as? Button)?.text = dateLabel(id)
            return
        }
        if (menuIds.contains(id)) { menuData[id] = t.split("\t"); (views[id] as? Button)?.text = menuData[id]?.firstOrNull() ?: "Menu"; return }   // [label, items...]
        if (contextMenuIds.contains(id)) { contextMenuData[id] = t.split("\t"); return }   // items
        if (alertIds.contains(id)) { alertData[id] = t.split("\t"); return }   // Alert's tab-joined fields
        if (mapIds.contains(id)) {                                              // Map's "text" is "lat,lng,zoom" -> OSM embed
            val p = t.split(",")
            if (p.size == 3) {
                val lat = p[0].toDoubleOrNull(); val lng = p[1].toDoubleOrNull(); val z = p[2].toDoubleOrNull()
                if (lat != null && lng != null && z != null) {
                    val span = 360.0 / Math.pow(2.0, z)
                    val dlon = span / 2.0; val dlat = span / 2.0 * 0.6
                    val url = "https://www.openstreetmap.org/export/embed.html?bbox=" +
                        "${lng - dlon}%2C${lat - dlat}%2C${lng + dlon}%2C${lat + dlat}" +
                        "&layer=mapnik&marker=${lat}%2C${lng}"
                    (views[id] as? android.webkit.WebView)?.loadUrl(url)
                }
            }
            return
        }
        (views[id] as? DrawCanvas)?.let { it.shapes = t; return }               // Canvas shape list
        (views[id] as? android.webkit.WebView)?.let { it.loadUrl(t); return }   // WebView URL
        if (t.startsWith("http")) {                                           // remote image / background URL
            (bgImageViews[id] ?: views[id] as? ImageView)?.let { loadRemoteImage(t, it); return }
        }
        (bgImageViews[id] ?: views[id] as? ImageView)?.let { iv ->            // bundled local asset (e.g. chuks-logo.png)
            if (t.isNotEmpty()) try { assets.open(t).use { iv.setImageBitmap(android.graphics.BitmapFactory.decodeStream(it)) } } catch (e: Exception) {}
            return
        }
        when (val v = views[id]) {
            is Button -> v.text = t
            is EditText -> v.hint = t
            is TextView -> { v.text = t; measureText(id, v) }
        }
    }

    // A recognized gesture -> the engine (via the wrapper's action tag).
    private fun dispatchGesture(v: View, g: String) {
        (v.getTag(TAG) as? String)?.let { hostInput(it, g) }
    }

    private fun selectLabel(id: String): String {
        val opts = selectOptions[id] ?: return "Select"
        val sel = selectSel[id] ?: 0
        return if (sel in opts.indices) opts[sel] else "Select"
    }

    // ---- DatePicker -------------------------------------------------------
    // A Calendar seeded from the id's current ISO value (or "now" if unset/unparseable).
    private fun dateCalendar(id: String): Calendar {
        val cal = Calendar.getInstance()
        val mode = datePickerModes[id] ?: "date"
        val v = datePickerVals[id] ?: ""
        try {
            if (mode == "time") {
                val hm = v.split(":")
                if (hm.size == 2) { cal.set(Calendar.HOUR_OF_DAY, hm[0].toInt()); cal.set(Calendar.MINUTE, hm[1].toInt()) }
            } else {
                val datePart = if (v.contains("T")) v.substringBefore("T") else v
                val ymd = datePart.split("-")
                if (ymd.size == 3) { cal.set(ymd[0].toInt(), ymd[1].toInt() - 1, ymd[2].toInt()) }
                if (mode == "datetime" && v.contains("T")) {
                    val hm = v.substringAfter("T").split(":")
                    if (hm.size == 2) { cal.set(Calendar.HOUR_OF_DAY, hm[0].toInt()); cal.set(Calendar.MINUTE, hm[1].toInt()) }
                }
            }
        } catch (e: Exception) { /* fall back to now */ }
        return cal
    }
    // Format a Calendar to the id's ISO value string.
    private fun isoOf(id: String, cal: Calendar): String {
        val mode = datePickerModes[id] ?: "date"
        val y = cal.get(Calendar.YEAR); val mo = cal.get(Calendar.MONTH) + 1; val d = cal.get(Calendar.DAY_OF_MONTH)
        val h = cal.get(Calendar.HOUR_OF_DAY); val mi = cal.get(Calendar.MINUTE)
        val date = "%04d-%02d-%02d".format(y, mo, d)
        val time = "%02d:%02d".format(h, mi)
        return when (mode) { "time" -> time; "datetime" -> "${date}T${time}"; else -> date }
    }
    // The button label: the current value (or a mode-appropriate hint when empty).
    private fun dateLabel(id: String): String {
        val v = datePickerVals[id] ?: ""
        if (v.isNotEmpty()) return v
        return when (datePickerModes[id] ?: "date") { "time" -> "Select time"; "datetime" -> "Select date & time"; else -> "Select date" }
    }
    // Open the native DatePickerDialog / TimePickerDialog, dispatch the picked ISO value.
    // The dialog's own theme doesn't follow uiMode by default, so pick a light/dark
    // DeviceDefault dialog theme to match the OS appearance (and thus the app).
    private fun openDateDialog(id: String, b: Button) {
        val mode = datePickerModes[id] ?: "date"
        val theme = if (osDark()) android.R.style.Theme_DeviceDefault_Dialog
                    else android.R.style.Theme_DeviceDefault_Light_Dialog
        val cal = dateCalendar(id)
        val fire = { picked: Calendar ->
            val iso = isoOf(id, picked)
            (b.getTag(TAG) as? String)?.let { hostInput(it, iso) }
        }
        if (mode == "time") {
            TimePickerDialog(this, theme, { _, hh, mm ->
                cal.set(Calendar.HOUR_OF_DAY, hh); cal.set(Calendar.MINUTE, mm); fire(cal)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        } else {
            DatePickerDialog(this, theme, { _, yy, mo, dd ->
                cal.set(yy, mo, dd)
                if (mode == "datetime") {
                    TimePickerDialog(this, theme, { _, hh, mm ->
                        cal.set(Calendar.HOUR_OF_DAY, hh); cal.set(Calendar.MINUTE, mm); fire(cal)
                    }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                } else { fire(cal) }
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
    }

    // eager text self-sizing (the Android analogue of Yoga's measure func)
    private fun measureText(id: String, tv: TextView) {
        val n = ynodes[id] ?: return
        val fixedW = textWidthPx[id]
        val unspecH = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        if (fixedW != null) {
            // Explicit width: measure CONSTRAINED so the text wraps, keep that width,
            // and report the wrapped height. (Measuring UNSPECIFIED here would report the
            // single-line width and clobber the width, so the text never wraps.)
            tv.measure(View.MeasureSpec.makeMeasureSpec(fixedW.toInt(), View.MeasureSpec.EXACTLY), unspecH)
            N.ySetF(n, 6, tv.measuredHeight.toFloat())
        } else {
            tv.measure(unspecH, unspecH)
            N.ySetF(n, 5, tv.measuredWidth.toFloat())
            N.ySetF(n, 6, tv.measuredHeight.toFloat())
        }
    }

    // A vector drawing surface: parses the ";"-joined shape descriptors and draws them
    // with android.graphics. Shape coords are dp (converted to px via dpf), matching the
    // dp-based layout, so a canvas looks the same as on iOS points.
    inner class DrawCanvas(context: android.content.Context) : View(context) {
        var shapes: String = ""
            set(value) { field = value; invalidate() }
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private fun col(h: String) = Color.parseColor("#$h")
        override fun onDraw(c: android.graphics.Canvas) {
            for (shape in shapes.split(";")) {
                if (shape.isEmpty()) continue
                val f = shape.split(",")
                when (f.getOrNull(0)) {
                    "rect" -> if (f.size >= 9) {
                        val x = dpf(f[1].toFloatOrNull() ?: 0f); val y = dpf(f[2].toFloatOrNull() ?: 0f)
                        val w = dpf(f[3].toFloatOrNull() ?: 0f); val h = dpf(f[4].toFloatOrNull() ?: 0f)
                        val r = dpf(f[8].toFloatOrNull() ?: 0f)
                        if (f[5].isNotEmpty()) { paint.style = android.graphics.Paint.Style.FILL; paint.color = col(f[5]); c.drawRoundRect(x, y, x + w, y + h, r, r, paint) }
                        val sw = f[7].toFloatOrNull() ?: 0f
                        if (f[6].isNotEmpty() && sw > 0) { paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = dpf(sw); paint.color = col(f[6]); c.drawRoundRect(x, y, x + w, y + h, r, r, paint) }
                    }
                    "circle" -> if (f.size >= 7) {
                        val cx = dpf(f[1].toFloatOrNull() ?: 0f); val cy = dpf(f[2].toFloatOrNull() ?: 0f); val r = dpf(f[3].toFloatOrNull() ?: 0f)
                        if (f[4].isNotEmpty()) { paint.style = android.graphics.Paint.Style.FILL; paint.color = col(f[4]); c.drawCircle(cx, cy, r, paint) }
                        val sw = f[6].toFloatOrNull() ?: 0f
                        if (f[5].isNotEmpty() && sw > 0) { paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = dpf(sw); paint.color = col(f[5]); c.drawCircle(cx, cy, r, paint) }
                    }
                    "line" -> if (f.size >= 7) {
                        val x1 = dpf(f[1].toFloatOrNull() ?: 0f); val y1 = dpf(f[2].toFloatOrNull() ?: 0f)
                        val x2 = dpf(f[3].toFloatOrNull() ?: 0f); val y2 = dpf(f[4].toFloatOrNull() ?: 0f)
                        if (f[5].isNotEmpty()) { paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = dpf(f[6].toFloatOrNull() ?: 1f); paint.strokeCap = android.graphics.Paint.Cap.ROUND; paint.color = col(f[5]); c.drawLine(x1, y1, x2, y2, paint) }
                    }
                    "path" -> if (f.size >= 5) {
                        val path = parsePath(f[1])
                        if (f[2].isNotEmpty()) { paint.style = android.graphics.Paint.Style.FILL; paint.color = col(f[2]); c.drawPath(path, paint) }
                        val sw = f[4].toFloatOrNull() ?: 0f
                        if (f[3].isNotEmpty() && sw > 0) { paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = dpf(sw); paint.color = col(f[3]); c.drawPath(path, paint) }
                    }
                }
            }
        }
        private fun parsePath(d: String): android.graphics.Path {
            val path = android.graphics.Path()
            var spaced = d
            for (cmd in listOf("M", "L", "Z", "m", "l", "z")) spaced = spaced.replace(cmd, " $cmd ")
            val toks = spaced.split(Regex("\\s+")).filter { it.isNotEmpty() }
            var i = 0
            while (i < toks.size) {
                val t = toks[i]
                if ((t == "M" || t == "L") && i + 2 < toks.size) {
                    val x = dpf(toks[i + 1].toFloatOrNull() ?: 0f); val y = dpf(toks[i + 2].toFloatOrNull() ?: 0f)
                    if (t == "M") path.moveTo(x, y) else path.lineTo(x, y)
                    i += 3; continue
                } else if (t == "Z" || t == "z") path.close()
                i += 1
            }
            return path
        }
    }

    private fun insert(id: String, parent: String, index: Int) {
        val child = views[id] ?: return
        if (parent == "root") { root.addView(child); return }
        if (modalIds.contains(id)) { root.addView(child); return }   // Modal overlays mount on root, above the app
        if (parent == scrollId) contentId = id
        val pv = views[parent] as? ViewGroup ?: return
        val pn = ynodes[parent] ?: return
        val base = if (bgImageViews.containsKey(parent)) 1 else 0   // keep an ImageBackground's bg image at the back
        pv.addView(child, minOf(index + base, pv.childCount))
        N.yInsert(pn, ynodes[id]!!, minOf(index, N.yChildCount(pn)))
    }

    private fun remove(id: String) {
        views[id]?.let { (it.parent as? ViewGroup)?.removeView(it) }
        ynodes[id]?.let { val o = N.yOwner(it); if (o != 0L) N.yRemove(o, it); N.yFree(it) }
        val prefix = "$id."
        videoPlayers.keys.filter { it == id || it.startsWith(prefix) }.toList().forEach { k -> poolVideo(k) }
        videoWanted.keys.filter { it == id || it.startsWith(prefix) }.toList().forEach { videoWanted.remove(it) }
        views.keys.filter { it == id || it.startsWith(prefix) }.forEach { views.remove(it); bgColor.remove(it); bgRadius.remove(it); borderW.remove(it); borderC.remove(it); pressOpacity.remove(it); sliderMin.remove(it); selectIds.remove(it); selectOptions.remove(it); selectSel.remove(it); datePickerIds.remove(it); datePickerModes.remove(it); datePickerVals.remove(it); menuIds.remove(it); menuData.remove(it); contextMenuIds.remove(it); contextMenuData.remove(it); mapIds.remove(it); gestureIds.remove(it); alertIds.remove(it); alertData.remove(it); alertActions.remove(it); bgImageViews.remove(it) }
        ynodes.keys.filter { it == id || it.startsWith(prefix) }.forEach { ynodes.remove(it) }
    }

    private fun bindAction(id: String, action: String) {
        val v = views[id] ?: return
        if (modalIds.contains(id)) modalActions[id] = action   // onDismiss: scrim tap AND sheet drag
        if (alertIds.contains(id)) { alertActions[id] = action; return }   // alert buttons dispatch this
        if (contextMenuIds.contains(id)) { v.setTag(TAG, action); return }  // long-press PopupMenu reads this
        if (gestureIds.contains(id)) { v.setTag(TAG, action); return }      // GestureDetector reads this
        if (v is ScrollView) { setupPullToRefresh(v, action); return }      // Scroll onRefresh -> pull-to-refresh
        when (v) {
            is Button -> v.setTag(TAG, action)
            is EditText -> v.setTag(TAG, action)
            is SeekBar -> v.setTag(TAG, action)      // Slider: the change listener reads this
            else -> {
                val ao = pressOpacity[id]
                if (ao != null) {
                    // Pressable: dim on touch-down, restore on release/cancel, fire only
                    // if released inside (TouchableOpacity). Instant, no round-trip.
                    v.isClickable = true
                    v.setOnTouchListener { view, ev ->
                        when (ev.action) {
                            MotionEvent.ACTION_DOWN -> { view.animate().alpha(ao).setDuration(90).start(); true }
                            MotionEvent.ACTION_UP -> {
                                view.animate().alpha(1f).setDuration(90).start()
                                if (ev.x >= 0 && ev.y >= 0 && ev.x <= view.width && ev.y <= view.height) fire(action)
                                true
                            }
                            MotionEvent.ACTION_CANCEL -> { view.animate().alpha(1f).setDuration(90).start(); true }
                            else -> false
                        }
                    }
                } else { v.isClickable = true; v.setOnClickListener { fire(action) } }
            }
        }
    }

    private fun fire(action: String) {
        if (action.isEmpty()) return
        hostEvent(action)
    }

    // Present a native AlertDialog for an Alert node (title/message/buttons from alertData).
    private fun presentAlert(id: String) {
        if (presentedAlertId == id) return
        val f = alertData[id] ?: return
        val title = f.getOrNull(0) ?: ""; val msg = f.getOrNull(1) ?: ""
        val confirm = f.getOrNull(2) ?: "OK"; val cancel = f.getOrNull(3) ?: ""
        val b = android.app.AlertDialog.Builder(this)
        if (title.isNotEmpty()) b.setTitle(title)
        if (msg.isNotEmpty()) b.setMessage(msg)
        b.setPositiveButton(confirm) { _, _ -> presentedAlertId = null; alertDispatch(id, "1") }
        if (cancel.isNotEmpty()) {
            b.setNegativeButton(cancel) { _, _ -> presentedAlertId = null; alertDispatch(id, "0") }
            b.setOnCancelListener { presentedAlertId = null; alertDispatch(id, "0") }   // back / tap-outside = cancel
        } else b.setCancelable(false)
        val d = b.create()
        presentedAlertId = id; presentedAlertDialog = d
        d.show()
    }
    private fun dismissAlert(id: String) {
        if (presentedAlertId == id) { presentedAlertId = null; presentedAlertDialog?.dismiss(); presentedAlertDialog = null }
    }
    private fun alertDispatch(id: String, v: String) {
        val action = alertActions[id] ?: return
        hostInput(action, v)
    }

    // Manual pull-to-refresh (the platform SDK has no SwipeRefreshLayout). A spinner
    // overlaid on root peeks as you drag down at the top; releasing past a threshold
    // fires onRefresh (synchronous — its state change re-renders), then fades out.
    private fun setupPullToRefresh(sc: ScrollView, action: String) {
        refreshAction = action
        if (refreshSpinner == null) {
            val sp = ProgressBar(this); sp.isIndeterminate = true; sp.visibility = View.GONE
            root.addView(sp, FrameLayout.LayoutParams(dp(28), dp(28)).also {
                it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; it.topMargin = dp(10)
            })
            refreshSpinner = sp
        }
        sc.setOnTouchListener { _, ev ->
            val sp = refreshSpinner ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pullStartY = ev.y; pulling = false; false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.y - pullStartY
                    if (sc.scrollY == 0 && dy > dp(12)) {
                        pulling = true
                        // The CONTENT follows the finger (damped rubber-band) — translationY is a
                        // cheap HW-accelerated render-node property, so this stays smooth. The
                        // spinner rides in the gap the translation opens up above the content.
                        val pull = minOf((dy - dp(12)) * 0.5f, dp(140).toFloat())
                        sc.translationY = pull
                        sp.visibility = View.VISIBLE
                        sp.translationY = pull * 0.5f
                        sp.alpha = minOf(1f, pull / dp(56).toFloat())
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pulling) {
                        pulling = false
                        val trigger = (ev.y - pullStartY) > dp(90) && refreshAction.isNotEmpty()
                        sc.animate().translationY(0f).setDuration(240).start()   // spring the content back
                        sp.animate().alpha(0f).setDuration(240).withEndAction {
                            sp.visibility = View.GONE; sp.translationY = 0f; sp.alpha = 1f }.start()
                        if (trigger) sc.post { fire(refreshAction) }   // defer the heavy re-render off the touch
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    // Fetch a remote image off the main thread (cached), then set it. Tag-guarded so a
    // recycled ImageView doesn't get a late bitmap for a URL it no longer wants.
    private fun loadRemoteImage(url: String, iv: ImageView) {
        imageCache[url]?.let { iv.setImageBitmap(it); return }
        iv.setTag(TAG, url)
        Thread {
            try {
                val bmp = java.net.URL(url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    imageCache[url] = bmp
                    iv.post { if (iv.getTag(TAG) == url) iv.setImageBitmap(bmp) }
                }
            } catch (e: Exception) { /* leave the placeholder */ }
        }.start()
    }

    // ---- Yoga layout -> Android frames ------------------------------------
    private fun relayout() {
        val app = ynodes["app"] ?: return
        val topY = dp(6)
        val w = root.width; val h = root.height - topY
        if (w <= 0 || h <= 0) return
        N.ySetF(app, 5, w.toFloat()); N.ySetF(app, 6, h.toFloat())
        N.yCalc(app, w.toFloat(), h.toFloat())
        // A visible Modal is a separate full-screen Yoga root (over the whole window) —
        // lay it out before copying frames so its children get valid positions.
        val mid = activeModal
        if (mid != null && views[mid]?.visibility == View.VISIBLE) {
            ynodes[mid]?.let { mn ->
                N.ySetF(mn, 5, root.width.toFloat()); N.ySetF(mn, 6, root.height.toFloat())
                N.yCalc(mn, root.width.toFloat(), root.height.toFloat())
            }
        }
        for ((id, node) in ynodes) {
            if (modalIds.contains(id)) continue          // modal overlay node placed explicitly below
            val v = views[id] ?: continue
            val lp = FrameLayout.LayoutParams(N.yGet(node, 2).toInt(), N.yGet(node, 3).toInt())
            lp.leftMargin = N.yGet(node, 0).toInt()
            lp.topMargin = N.yGet(node, 1).toInt()
            v.layoutParams = lp
        }
        // place the whole Chuks app just below the top inset
        (views["app"]?.layoutParams as? FrameLayout.LayoutParams)?.let { it.leftMargin = 0; it.topMargin = topY }
        // a visible modal fills the window and sits on top (children laid out above)
        if (mid != null && views[mid]?.visibility == View.VISIBLE) {
            views[mid]?.let { mv ->
                mv.layoutParams = FrameLayout.LayoutParams(root.width, root.height).also { it.leftMargin = 0; it.topMargin = 0 }
                mv.bringToFront()
                if (sheetModals.contains(mid)) {
                    val firstOpen = shownSheet != mid; shownSheet = mid
                    layoutSheetChrome(mv as FrameLayout, firstOpen)
                } else clearSheetChrome()
            }
        } else clearSheetChrome()
        root.requestLayout()
        root.post { updateVideoVisibility() }   // recompute on-screen videos once positions settle
    }

    // ---- Bottom sheet: a host-drawn draggable surface (@gorhom-style) ---------
    // The Modal's children are laid out (full-width, pinned bottom) above; here we
    // slip a rounded-top surface + grab handle behind them and wire a drag so the
    // whole sheet slides down to dismiss / springs back.
    private fun layoutSheetChrome(mv: FrameLayout, animateIn: Boolean = false) {
        val content = (0 until mv.childCount).map { mv.getChildAt(it) }.filter { it !== sheetBg && it !== sheetHandle }
        if (content.isEmpty()) return
        var top = Int.MAX_VALUE
        for (c in content) { c.translationY = 0f; top = minOf(top, (c.layoutParams as FrameLayout.LayoutParams).topMargin) }
        val sheetTop = maxOf(0, top - dp(22))          // reserve a strip above content for the grab handle
        sheetTopPx = sheetTop
        val W = root.width; val H = root.height

        val bg = sheetBg ?: View(this).also { sheetBg = it }
        val gd = GradientDrawable()
        gd.setColor(surfaceColor())
        gd.cornerRadii = floatArrayOf(dpf(20f), dpf(20f), dpf(20f), dpf(20f), 0f, 0f, 0f, 0f)  // top corners only
        bg.background = gd
        bg.translationY = 0f
        bg.layoutParams = FrameLayout.LayoutParams(W, H - sheetTop).also { it.topMargin = sheetTop }
        (bg.parent as? ViewGroup)?.removeView(bg); mv.addView(bg, 0)   // index 0 = behind the content

        val handle = sheetHandle ?: View(this).also {
            val hd = GradientDrawable(); hd.setColor(Color.argb(102, 128, 128, 128)); hd.cornerRadius = dpf(2.5f)
            it.background = hd; sheetHandle = it
        }
        handle.translationY = 0f
        handle.layoutParams = FrameLayout.LayoutParams(dp(40), dp(5)).also {
            it.topMargin = sheetTop + dp(8); it.leftMargin = (W - dp(40)) / 2 }
        (handle.parent as? ViewGroup)?.removeView(handle); mv.addView(handle, 1)   // just above bg, below content

        mv.setBackgroundColor(Color.argb(128, 0, 0, 0))
        attachSheetDrag(mv)

        if (animateIn) {                                 // slide the whole sheet up from below
            val dy = (H - sheetTop).toFloat()
            for (i in 0 until mv.childCount) {
                val c = mv.getChildAt(i)
                c.translationY = dy
                c.animate().translationY(0f).setDuration(320)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }
    }

    private fun clearSheetChrome() {
        sheetBg?.let { (it.parent as? ViewGroup)?.removeView(it) }
        sheetHandle?.let { (it.parent as? ViewGroup)?.removeView(it) }
        shownSheet = null
    }

    private var sheetDownY = 0f
    private var sheetDownX = 0f
    private var sheetTopPx = 0
    // Drag handling lives on the Modal FrameLayout. Clickable children (buttons, list
    // items) sit on top and consume their own DOWN, so this listener only fires for the
    // scrim, the handle strip, and inert content: return true on DOWN to capture the
    // drag, translate on MOVE, and on UP either dismiss (dragged far / tapped the scrim)
    // or spring back.
    private fun attachSheetDrag(mv: FrameLayout) {
        mv.setOnTouchListener { _, ev ->
            val mid = activeModal ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { sheetDownY = ev.rawY; sheetDownX = ev.x; true }
                MotionEvent.ACTION_MOVE -> {
                    // Translate ONLY the sheet views (bg + handle + content, 3 nodes). The scrim
                    // is mv's own background and is left untouched: repainting a full-screen
                    // semi-transparent color every frame is what janked the drag.
                    val dy = maxOf(0f, ev.rawY - sheetDownY)
                    for (i in 0 until mv.childCount) mv.getChildAt(i).translationY = dy
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = maxOf(0f, ev.rawY - sheetDownY)
                    if (dy > dp(120)) {
                        modalActions[mid]?.let { fire(it) }
                    } else if (dy < dp(8) && sheetDownYIsOnScrim()) {
                        modalActions[mid]?.let { fire(it) }        // tap on the dimmed area = dismiss
                    } else {
                        for (i in 0 until mv.childCount) mv.getChildAt(i).animate().translationY(0f).setDuration(220).start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // was the touch-down above the sheet surface (i.e. on the dimmed scrim)?
    private fun sheetDownYIsOnScrim(): Boolean {
        val mv = activeModal?.let { views[it] } ?: return false
        val onScreen = IntArray(2); mv.getLocationOnScreen(onScreen)
        val localY = sheetDownY - onScreen[1]
        return localY < sheetTopPx
    }

    private fun surfaceColor(): Int {
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.parseColor("#1C1C1E") else Color.parseColor("#F2F2F7")
    }

    // NB: this Yoga build's YGJustify enum starts with YGJustifyAuto=0, so
    // FlexStart=1, Center=2, FlexEnd=3, ... (1-based, like YGAlign below). The JNI
    // casts these ints straight to the enum, so they must match the header.
    private fun justify(v: String) = when (v) {
        "center" -> 2f; "end" -> 3f; "between" -> 4f; "around" -> 5f; else -> 1f }
    private fun align(v: String) = when (v) {
        "center" -> 2f; "end" -> 3f; "stretch" -> 4f; else -> 1f }

    companion object { const val TAG = 0x7f_00_00_01 }
}
