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
import android.provider.CalendarContract
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraMetadata
import android.media.ImageReader
import android.graphics.ImageFormat
import android.util.Size
import android.os.HandlerThread
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.tech.Ndef
import java.util.UUID
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
    external fun viewport(top: Int, h: Int, w: Int): Int
    external fun event(action: String): Int
    external fun input(action: String, value: String): Int
    external fun resolve(token: String, payload: String): Int   // F3: async capability result
    external fun fail(token: String, message: String): Int      // F3: capability failure (error channel)
    external fun setColorScheme(dark: Int)
    external fun colorSchemeFollows(): Int
    external fun setInsets(top: Int, right: Int, bottom: Int, left: Int)
    external fun setPlatform(os: String, version: String, model: String, isTablet: Int)
    external fun drain(): String
    external fun cmrBoot(bundle: ByteArray, tmpdir: String): Int   // CMR: load a chukspack bundle (libcmr only)
    external fun yNew(): Long
    external fun yInsert(parent: Long, child: Long, idx: Int)
    external fun yRemove(parent: Long, child: Long)
    external fun yOwner(node: Long): Long
    external fun yChildCount(node: Long): Int
    external fun yFree(node: Long)
    external fun yCalc(node: Long, w: Float, h: Float)
    external fun yGet(node: Long, which: Int): Float
    external fun ySetF(node: Long, key: Int, v: Float)
    external fun ySetTextMeasure(node: Long)
    external fun yMarkDirty(node: Long)
    var measureCb: ((Long, Float, Int) -> Long)? = null
    @JvmStatic fun measureTextNode(node: Long, width: Float, wmode: Int): Long =
        measureCb?.invoke(node, width, wmode) ?: 0L

    // Host wake: registers a native callback (jni.cpp) that a background Chuks task
    // fires when it posts work to the render thread. onNativeWake is called FROM that
    // native trampoline on the task's own thread; it hops to the UI thread and runs
    // the activity's pump. Coalesced so a burst of messages schedules one tick.
    external fun setWake()
    var onWake: (() -> Unit)? = null
    private val wakeHandler = Handler(Looper.getMainLooper())
    private val wakeScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    @JvmStatic fun onNativeWake() {
        if (wakeScheduled.getAndSet(true)) return
        wakeHandler.post { wakeScheduled.set(false); onWake?.invoke() }
    }
}

class MainActivity : Activity() {
    private val views = HashMap<String, View>()
    private val ynodes = HashMap<String, Long>()
    private var density = 1f

    private lateinit var root: FrameLayout
    private var listScroll: FrameLayout? = null   // a ScrollView (vertical) or HorizontalScrollView (carousel)
    private var listHoriz = false                 // the tracked list scrolls horizontally (report x, not y)
    private var scrollId = ""
    private var stickBottomOn = false   // Scroll stickBottom: keep pinned to newest (chat)
    private var stickPrevH = 0          // previous content height, to tell if the user was at the bottom
    private val modalIds = HashSet<String>()             // Modal node ids (full-screen overlays)
    private var activeModal: String? = null              // the currently-visible Modal
    private val sheetModals = HashSet<String>()          // Modal ids with position=bottom (draggable sheets)
    private val modalActions = HashMap<String, String>() // Modal id -> onDismiss action
    private var sheetBg: View? = null                    // host-drawn sheet surface (rounded top, behind content)
    private var sheetHandle: View? = null                // host-drawn grab handle pill
    private var shownSheet: String? = null               // sheet currently on screen (null = none); a change drives the slide-up
    private val sliderMin = HashMap<String, Int>()        // Slider id -> min, to offset the SeekBar's 0-based progress
    private val sliderStep = HashMap<String, Int>()       // Slider id -> step (snap to multiples; 0 = continuous)
    private val sliderDone = HashMap<String, String>()    // Slider id -> onSlidingComplete tag ("<id>:slidedone")
    private val switchThumb = HashMap<String, Int>()      // Switch id -> thumb color (so bg's white default doesn't clobber thumbColor)
    private val scrollOnScroll = HashMap<String, String>() // Scroll id -> onScroll tag ("<id>:scroll")
    private val scrollLastPos = HashMap<String, Int>()     // Scroll id -> last reported offset (logical pts), to dedupe
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
    private val gestureCont = HashMap<String, String>()    // id -> enabled continuous recognizers ("pan,pinch,rotate")
    private val cameraIds = HashSet<String>()              // CameraView node ids (Camera2 preview on a TextureView)
    private var cameraController: CameraController? = null  // the live CameraView session (for camera.capturePreview)
    private var bleManager: BleManager? = null             // BLE central (lazy)
    private var nfcReader: NfcReader? = null               // NFC reader (lazy)
    private val bleStateTokens = HashSet<String>()         // ble.state stream subscribers
    private val alertIds = HashSet<String>()              // Alert node ids (native AlertDialog)
    private val alertData = HashMap<String, List<String>>()  // id -> [title, message, confirm, cancel]
    private val alertActions = HashMap<String, String>()  // id -> button-dispatch action
    private val fieldSubmit = HashMap<android.widget.EditText, String>()  // onSubmit tag (IME action / enter)
    private val fieldFocus = HashMap<android.widget.EditText, String>()   // onFocus tag
    private val fieldBlur = HashMap<android.widget.EditText, String>()    // onBlur tag
    private var fieldSelfSet = false                                      // guard: a controlled value.set is not a user edit
    private var presentedAlertId: String? = null          // Alert id currently on screen
    private var presentedAlertDialog: android.app.AlertDialog? = null
    // Bounded in-memory LRU (1/8 of the app heap) so a big image feed can't blow memory.
    private val imageMem = object : android.util.LruCache<String, android.graphics.Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, b: android.graphics.Bitmap): Int = b.byteCount / 1024
    }
    private val imgDir by lazy { java.io.File(cacheDir, "imgcache").apply { mkdirs() } }  // disk cache (survives relaunch)
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
    // StatusBar background color: the OS status-bar fill (hex, with or without a leading #).
    private fun setStatusBarColor(hex: String) {
        if (hex.isEmpty()) return
        try { window.statusBarColor = android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex") } catch (e: Exception) {}
    }
    // Nav-bar background color (Android only; iOS has no on-screen nav bar).
    private fun setNavBarColor(hex: String) {
        if (hex.isEmpty()) return
        try { window.navigationBarColor = android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex") } catch (e: Exception) {}
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
        // Keyboard avoidance: adjustResize shrinks the window content when the keyboard
        // shows, so a bottom input bar rises above it (set here too, not only in the
        // manifest, in case the manifest is regenerated from app.json).
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0E1116"))
        setContentView(root)
        // Re-layout when the root's own size changes (keyboard show/hide, rotation): the
        // Chuks tree is laid out to root.height, so the shrink reflows content above the
        // keyboard. Child layout changes don't resize root, so this never loops.
        root.addOnLayoutChangeListener { _, l, t, r, bo, ol, ot, or2, ob ->
            if ((bo - t) != (ob - ot) || (r - l) != (or2 - ol)) { relayout(); pushViewport() }
        }
        intent?.data?.let { lastUrl = it.toString() }   // deep link that launched the app

        // DEV hot reload: assets/chuks-dev.txt (written by a DEV=1 build) points at the
        // running dev server. Present => fetch the UI over HTTP instead of the JNI engine.
        try { assets.open("chuks-dev.txt").bufferedReader().use { devBase = "http://" + it.readText().trim() } } catch (e: Exception) {}
        // Chuks Preview: a server chosen at runtime on the connect screen wins over any
        // bundled one, so one generic host can point at any `chuks dev`.
        getSharedPreferences("chuks.preview", MODE_PRIVATE).getString("host", "")?.let {
            if (it.isNotEmpty()) devBase = "http://$it"
        }

        // CMR: if a chukspack bundle is baked into assets, load it into the
        // in-process VM (libcmr). No-op/caught in AOT + DEV builds.
        try {
            val bundle = assets.open("cmr.bundle").readBytes()
            val rc = N.cmrBoot(bundle, cacheDir.absolutePath)   // cacheDir => $TMPDIR for on-device compile
            android.util.Log.i("CMR", "booted rc=$rc (${bundle.size} bytes)")
        } catch (_: Throwable) { /* no cmr.bundle, or cmrBoot native absent (AOT) */ }

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

        // Text measure callback: needed in BOTH dev and production (the wake/heartbeat setup
        // below is split by devMode, but Yoga's text measurement is not).
        N.measureCb = { node, width, wmode -> measureTextNode(node, width, wmode) }

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
                    if (up && !everMounted) {
                        // The initial mount came back empty (server up but returned nothing):
                        // retry it so the app doesn't sit on a blank screen with no recovery.
                        handler.post { hostMount(); if (pushViewport()) relayout() }
                    }
                    devConnected = up
                }
            }.apply { isDaemon = true; start() }
        } else {
            // Host wake: a spawned Chuks task that posts to the render thread fires this so
            // we tick immediately instead of waiting for the 400ms heartbeat below.
            N.onWake = { pumpWake() }
            N.setWake()
            // tick timer (idle heartbeat / fallback; the wake drives prompt paints, this
            // covers any host without a wake and background state that changed with no wake)
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

    // Called on the UI thread when a background Chuks task posted work (via the wake
    // callback). Ticking drains the engine's async queue and re-renders.
    private fun pumpWake() {
        if (devMode) return
        N.tick(); applyDrain(); relayout()
    }

    // Per-frame animation driver (FA|1 / FA|0). Android's frame cadence is otherwise a
    // 400ms Handler; decay/spring physics needs vsync, so a Choreographer callback ticks
    // the engine every frame while physics is live and stops when the engine emits FA|0.
    private var frameActiveFlag = false
    private var frameScheduled = false
    private val frameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(ns: Long) {
            frameScheduled = false
            if (!frameActiveFlag) return
            pumpWake()
            if (frameActiveFlag) { frameScheduled = true; android.view.Choreographer.getInstance().postFrameCallback(this) }
        }
    }
    private fun setFrameDriver(on: Boolean) {
        frameActiveFlag = on
        if (on && !frameScheduled) {
            frameScheduled = true
            android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density

    // List scrollToIndex/scrollToEnd: smooth-scroll the list's ScrollView to content-offset y
    // (Chuks logical points -> px), clamped. Posted so the content-size/layout emitted in the
    // same batch settles first (otherwise the max scroll range is stale).
    // onProgress: poll the MediaPlayer's position (MediaPlayer has no periodic callback) and fire
    // when the whole second changes, clamped to the clip length. Re-posts itself while the video
    // and its onProgress handler live; only one poller per id.
    private fun startProgressPoll(id: String) {
        if (progressPollers.containsKey(id)) return
        val h = Handler(Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() {
                val a = mediaProgress[id]
                if (a == null) { progressPollers.remove(id); return }   // onProgress gone (node unmounted): stop
                val mp = videoPlayers[id]                                // may be null transiently (attaches after layout)
                if (mp != null) try {
                    val sec = mp.currentPosition / 1000
                    val dur = mp.duration / 1000
                    if (sec >= 0 && (dur <= 0 || sec <= dur + 1) && videoLastSec[id] != sec) {
                        videoLastSec[id] = sec; hostInput(a, sec.toString())
                    }
                } catch (e: Exception) {}
                h.postDelayed(this, 250)
            }
        }
        progressPollers[id] = r
        h.postDelayed(r, 250)
    }

    private fun scrollListTo(id: String, y: Int) {
        val vv = views[id] ?: return
        if (vv is HorizontalScrollView) {   // horizontal list: y is really the x offset
            vv.post {
                val child = if (vv.childCount > 0) vv.getChildAt(0) else null
                val maxX = if (child != null) (child.width - vv.width).coerceAtLeast(0) else Int.MAX_VALUE
                vv.smoothScrollTo(dp(y).coerceIn(0, maxX), 0)
            }
            return
        }
        val sc = vv as? ScrollView ?: return
        sc.post {
            val child = if (sc.childCount > 0) sc.getChildAt(0) else null
            val maxY = if (child != null) (child.height - sc.height).coerceAtLeast(0) else Int.MAX_VALUE
            sc.smoothScrollTo(0, dp(y).coerceIn(0, maxY))
        }
    }

    // Scroll onScroll: report the offset (px -> logical points) when it changes, if the
    // Scroll node opted in via SS. Deduped so an idle relayout doesn't re-fire.
    private fun reportScroll(id: String, offsetPx: Int) {
        val tag = scrollOnScroll[id] ?: return
        val pts = Math.round(offsetPx / resources.displayMetrics.density)
        if (scrollLastPos[id] == pts) return
        scrollLastPos[id] = pts
        hostInput(tag, pts.toString())
    }

    // ---- viewport / scroll -------------------------------------------------
    private fun pushViewport(): Boolean {
        val sc = listScroll
        if (sc == null) {
            // No scroll/list on screen: still report the full root viewport so
            // viewportWidth()/viewportHeight() are populated (e.g. for computing a Text
            // wrap width). Without this they stay 0 on scroll-less screens.
            val rw = root.width; val rh = root.height
            if (rw <= 0 || rh <= 0) return false
            val wDp = (rw / density).toInt(); val hDp = (rh / density).toInt()
            if (devMode) { applyStream(devBlocking("/viewport", "0 $hDp $wDp")); return true }
            if (N.viewport(0, hDp, wDp) > 0) { applyDrain(); return true }
            return false
        }
        // top is the scroll offset along the MAIN axis (x for a horizontal list, y otherwise);
        // height/width are ALWAYS the true viewport dimensions (never swapped), so
        // viewportWidth()/viewportHeight() stay correct. The reconciler picks vpW vs vpH as the
        // windowing extent per the list's orientation.
        val topDp = ((if (listHoriz) sc.scrollX else sc.scrollY) / density).toInt()
        val hDp = (sc.height / density).toInt()
        val wDp = (sc.width / density).toInt()
        if (hDp <= 0 || wDp <= 0) return false
        if (devMode) { applyStream(devBlocking("/viewport", "$topDp $hDp $wDp")); return true }
        if (N.viewport(topDp, hDp, wDp) > 0) { applyDrain(); return true }
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
                "P" -> if (f.size >= 3) setText(f[1], f.drop(2).joinToString("|"))   // rejoin: text may contain '|'
                "V" -> if (f.size >= 3) setFieldValue(f[1], f.drop(2).joinToString("|"))   // controlled value (may contain '|')
                "T" -> if (f.size >= 3) bindAction(f[1], f[2])
                "TS" -> if (f.size >= 2) (views[f[1]] as? android.widget.EditText)?.let { fieldSubmit[it] = f[1] + ":submit" }
                "TF" -> if (f.size >= 2) (views[f[1]] as? android.widget.EditText)?.let { fieldFocus[it] = f[1] + ":focus" }
                "TB" -> if (f.size >= 2) (views[f[1]] as? android.widget.EditText)?.let { fieldBlur[it] = f[1] + ":blur" }
                "TL" -> if (f.size >= 2) longPressActions[f[1]] = f[1] + ":longpress"   // Pressable onLongPress
                "TPI" -> if (f.size >= 2) pressInActions[f[1]] = f[1] + ":pressin"       // Pressable onPressIn
                "TPO" -> if (f.size >= 2) pressOutActions[f[1]] = f[1] + ":pressout"     // Pressable onPressOut
                "ML" -> if (f.size >= 2) mediaLoad[f[1]] = f[1] + ":load"                // Image/Video onLoad
                "ME" -> if (f.size >= 2) mediaError[f[1]] = f[1] + ":error"              // Image onError
                "MN" -> if (f.size >= 2) mediaEnd[f[1]] = f[1] + ":end"                  // Video onEnd
                "SC" -> if (f.size >= 2) sliderDone[f[1]] = f[1] + ":slidedone"          // Slider onSlidingComplete
                "SS" -> if (f.size >= 2) scrollOnScroll[f[1]] = f[1] + ":scroll"         // Scroll onScroll
                "IF" -> if (f.size >= 3) {                                                // Image GPU op-chain (JSON; rejoin '|')
                    imageOpChain[f[1]] = f.drop(2).joinToString("|")
                    imageOrigBmp[f[1]]?.let { b -> (views[f[1]] as? ImageView)?.setImageBitmap(runOps(f[1], b)) }
                }
                "MP" -> if (f.size >= 2) { mediaProgress[f[1]] = f[1] + ":progress"; startProgressPoll(f[1]) }   // Video onProgress
                "LS" -> if (f.size >= 3) scrollListTo(f[1], f[2].toIntOrNull() ?: 0)   // scrollToIndex/scrollToEnd
                "I" -> if (f.size >= 4) insert(f[1], f[2], f[3].toIntOrNull() ?: 0)
                "R" -> if (f.size >= 2) remove(f[1])
                "FA" -> if (f.size >= 2) setFrameDriver(f[1] == "1")   // per-frame physics on/off
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
    // True once a /mount has actually built a view tree. Until then the dev watcher keeps
    // retrying the mount, so a failed/empty initial mount (dev server momentarily
    // unreachable, or an empty-body race) recovers instead of stranding the app on a blank
    // screen. In the JNI (AOT) path the engine is in-process and this never fails.
    private var everMounted = false
    private fun hostMount() { applyStream(engMount()); relayout(); if (views.isNotEmpty()) everMounted = true }
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
            "location.once" -> {
                if (!hasLocationPerm()) { fail(token, "location permission denied"); return }
                val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val provider = bestLocationProvider(lm)
                if (provider == null) { fail(token, "location unavailable"); return }
                try {
                    val last = lm.getLastKnownLocation(provider)
                    if (last != null) { resolve(token, locFixStr(last)) }
                    else {
                        // No cached fix: take one live update, then release the listener.
                        val listener = object : android.location.LocationListener {
                            override fun onLocationChanged(l: android.location.Location) { resolve(token, locFixStr(l)); lm.removeUpdates(this) }
                            override fun onProviderDisabled(p: String) {}
                            override fun onProviderEnabled(p: String) {}
                            @Deprecated("kept for older API levels") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                        }
                        lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                    }
                } catch (e: SecurityException) { fail(token, "location permission denied") }
            }
            "location.watch" -> {
                if (!hasLocationPerm()) { fail(token, "location permission denied"); return }
                val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val provider = bestLocationProvider(lm)
                if (provider == null) { fail(token, "location unavailable"); return }
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(l: android.location.Location) { resolve(token, locFixStr(l)) }
                    override fun onProviderDisabled(p: String) {}
                    override fun onProviderEnabled(p: String) {}
                    @Deprecated("kept for older API levels") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                }
                try {
                    lm.getLastKnownLocation(provider)?.let { resolve(token, locFixStr(it)) }   // immediate value
                    lm.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                    streamTeardown[token] = { try { lm.removeUpdates(listener) } catch (e: Exception) {} }
                } catch (e: SecurityException) { fail(token, "location permission denied") }
            }
            "motion.accel" -> startSensor(token, android.hardware.Sensor.TYPE_ACCELEROMETER)
            "motion.gyro" -> startSensor(token, android.hardware.Sensor.TYPE_GYROSCOPE)
            "motion.mag" -> startSensor(token, android.hardware.Sensor.TYPE_MAGNETIC_FIELD)
            "deviceinfo.screen" -> {
                val dm = resources.displayMetrics
                val wdp = (dm.widthPixels / dm.density).toInt()
                val hdp = (dm.heightPixels / dm.density).toInt()
                resolve(token, "$wdp,$hdp,${dm.density}")
            }
            "deviceinfo.appversion" -> {
                val pi = packageManager.getPackageInfo(packageName, 0)
                @Suppress("DEPRECATION")
                val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
                resolve(token, "${pi.versionName ?: ""},$code")
            }
            "deviceinfo.locale" -> {
                val loc = resources.configuration.locales[0]
                resolve(token, "${loc.language},${loc.country}")
            }
            "contacts.list" -> {
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) { fail(token, "contacts permission denied"); return }
                try {
                    // id -> [name, phones, emails]; merge phone + email rows by contact id
                    val map = LinkedHashMap<String, Array<Any>>()
                    fun entry(id: String, name: String) = map.getOrPut(id) { arrayOf(name, linkedSetOf<String>(), linkedSetOf<String>()) }
                    contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID, android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                        null, null, android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getString(0) ?: continue
                            @Suppress("UNCHECKED_CAST") (entry(id, c.getString(1) ?: "")[1] as LinkedHashSet<String>).add(c.getString(2) ?: "")
                        }
                    }
                    contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        arrayOf(android.provider.ContactsContract.CommonDataKinds.Email.CONTACT_ID, android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS),
                        null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getString(0) ?: continue
                            @Suppress("UNCHECKED_CAST") (entry(id, c.getString(1) ?: "")[2] as LinkedHashSet<String>).add(c.getString(2) ?: "")
                        }
                    }
                    val out = map.values.joinToString("\n") { r ->
                        @Suppress("UNCHECKED_CAST")
                        "${r[0]}\t${(r[1] as Set<String>).joinToString(";")}\t${(r[2] as Set<String>).joinToString(";")}"
                    }
                    resolve(token, out)
                } catch (e: Exception) { fail(token, "read failed: ${e.message}") }
            }
            "calendar.upcoming" -> {
                if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) { fail(token, "calendar permission denied"); return }
                try {
                    val days = args.toLongOrNull() ?: 7L
                    val now = System.currentTimeMillis()
                    val sb = StringBuilder()
                    contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND),
                        "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?", arrayOf(now.toString(), (now + days * 86400000L).toString()),
                        "${CalendarContract.Events.DTSTART} ASC")?.use { c ->
                        while (c.moveToNext()) sb.append(c.getString(0) ?: "").append('\t').append(c.getLong(1)).append('\t').append(c.getLong(2)).append('\n')
                    }
                    resolve(token, sb.toString().trimEnd('\n'))
                } catch (e: Exception) { fail(token, "read failed: ${e.message}") }
            }
            "calendar.create" -> {
                if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) { fail(token, "calendar permission denied"); return }
                try {
                    val parts = args.split("|")
                    if (parts.size < 3) { fail(token, "bad args"); return }
                    val startMin = parts[1].toLongOrNull() ?: 0L; val durMin = parts[2].toLongOrNull() ?: 0L
                    var calId = -1L
                    contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
                        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?", arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use { c ->
                        if (c.moveToFirst()) calId = c.getLong(0)
                    }
                    if (calId < 0) { fail(token, "no writable calendar"); return }
                    val now = System.currentTimeMillis()
                    val values = android.content.ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, calId); put(CalendarContract.Events.TITLE, parts[0])
                        put(CalendarContract.Events.DTSTART, now + startMin * 60000L); put(CalendarContract.Events.DTEND, now + (startMin + durMin) * 60000L)
                        put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                    }
                    val uri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    if (uri == null) { fail(token, "insert failed"); return }
                    resolve(token, uri.lastPathSegment ?: "ok")
                } catch (e: Exception) { fail(token, "save failed: ${e.message}") }
            }
            "linking.onurl" -> {
                urlTokens.add(token)
                streamTeardown[token] = { urlTokens.remove(token) }
                lastUrl?.let { resolve(token, it) }   // deliver the launch URL to a late subscriber
            }
            "linking.opensettings" -> {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                } catch (e: Exception) {}
            }
            "mediapicker.image" -> {
                val code = ++mediaSeq
                pendingMedia[code] = Pair(token, null)
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
                try { startActivityForResult(Intent.createChooser(intent, "Pick image"), code) }
                catch (e: Exception) { pendingMedia.remove(code); fail(token, "no picker available") }
            }
            "camera.photo" -> {
                val code = ++mediaSeq
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "chuks-$code.jpg")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                }
                val outUri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (outUri == null) { fail(token, "cannot create output"); return }
                pendingMedia[code] = Pair(token, outUri)
                val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(android.provider.MediaStore.EXTRA_OUTPUT, outUri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try { startActivityForResult(intent, code) }
                catch (e: Exception) { pendingMedia.remove(code); fail(token, "no camera app") }
            }
            "camera.capturePreview" -> {
                val ctrl = cameraController
                if (ctrl == null) { fail(token, "no CameraView on screen"); return }
                ctrl.capture({ p -> resolve(token, p) }, { m -> fail(token, m) })
            }
            "ble.state" -> {
                val b = ensureBle()
                bleStateTokens.add(token)
                resolve(token, b.state())
                streamTeardown[token] = { bleStateTokens.remove(token) }
            }
            "ble.scan" -> {
                val b = ensureBle()
                if (b.state() != "on") { fail(token, "bluetooth is ${b.state()}"); return }
                b.startScan(token)
                streamTeardown[token] = { bleManager?.stopScan() }
            }
            "ble.connect" -> ensureBle().connect(args, { p -> resolve(token, p) }, { m -> fail(token, m) })
            "ble.disconnect" -> bleManager?.disconnect(args)
            "ble.read" -> {
                val a = args.split("\t")
                if (a.size == 3) ensureBle().read(a[0], a[1], a[2], { p -> resolve(token, p) }, { m -> fail(token, m) })
                else fail(token, "ble.read needs id, service, characteristic")
            }
            "ble.write" -> {
                val a = args.split("\t")
                if (a.size == 4) ensureBle().write(a[0], a[1], a[2], a[3], { p -> resolve(token, p) }, { m -> fail(token, m) })
                else fail(token, "ble.write needs id, service, characteristic, hex")
            }
            "ble.subscribe" -> {
                val a = args.split("\t")
                if (a.size == 3) {
                    ensureBle().subscribe(a[0], a[1], a[2], token, { m -> fail(token, m) })
                    streamTeardown[token] = { bleManager?.unsubscribe(token) }
                } else fail(token, "ble.subscribe needs id, service, characteristic")
            }
            "nfc.available" -> resolve(token, if (ensureNfc().available()) "1" else "0")
            "nfc.read" -> ensureNfc().read(token) { m -> fail(token, m) }
            "nfc.write" -> ensureNfc().write(args, token) { m -> fail(token, m) }
            "mediapicker.save" -> {
                val path = if (args.startsWith("file://")) args.substring(7) else args
                val f = java.io.File(path)
                if (!f.exists()) { fail(token, "no such image"); return }
                try {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "chuks-${mediaSeq++}.jpg")
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        if (android.os.Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures")
                    }
                    val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri == null) { fail(token, "cannot save"); return }
                    contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                    resolve(token, "ok")
                } catch (e: Exception) { fail(token, "save failed: ${e.message}") }
            }
            "biometrics.available" -> {
                if (android.os.Build.VERSION.SDK_INT < 29) { resolve(token, "0"); return }
                val bm = getSystemService(android.hardware.biometrics.BiometricManager::class.java)
                val ok = bm != null && bm.canAuthenticate() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
                resolve(token, if (ok) "1" else "0")
            }
            "biometrics.authenticate" -> authenticateBiometric(token, args)
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
                    // calendar needs both read + write; the rest are a single permission
                    requestPermissions(if (args == "calendar") arrayOf(p, Manifest.permission.WRITE_CALENDAR) else arrayOf(p), code)
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
                    if (args.startsWith("file://")) {
                        mp.setDataSource(args.substring(7))   // a recording / downloaded file
                    } else {
                        val afd = assets.openFd(args)         // a bundled asset
                        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length); afd.close()
                    }
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
            "recorder.start" -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { fail(token, "microphone permission denied"); return }
                try {
                    val f = java.io.File(filesDir, "rec-${System.nanoTime()}.m4a")
                    @Suppress("DEPRECATION")
                    val mr = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(this) else android.media.MediaRecorder()
                    mr.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    mr.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                    mr.setOutputFile(f.absolutePath)
                    mr.prepare(); mr.start()
                    mediaRecorder = mr; recPath = f.absolutePath
                } catch (e: Exception) { fail(token, "record failed: ${e.message}") }
            }
            "recorder.stop" -> {
                val mr = mediaRecorder ?: run { fail(token, "not recording"); return }
                try { mr.stop() } catch (e: Exception) {}
                mr.release(); mediaRecorder = null
                resolve(token, "file://" + (recPath ?: ""))
            }
            "recorder.levels" -> {
                val r = object : Runnable {
                    override fun run() {
                        mediaRecorder?.let {
                            val amp = try { it.maxAmplitude } catch (e: Exception) { 0 }
                            resolve(token, String.format("%.3f", (amp.toDouble() / 32767.0).coerceIn(0.0, 1.0)))
                        }
                        if (activeStreams.containsKey(token)) streamHandler.postDelayed(this, 80)
                    }
                }
                activeStreams[token] = r
                streamHandler.postDelayed(r, 80)
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
            "haptics.vibrate" -> hapticVibrate(args.toLongOrNull() ?: 0L)
            "haptics.pattern" -> hapticPattern(args)
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
        "contacts" -> Manifest.permission.READ_CONTACTS
        "calendar" -> Manifest.permission.READ_CALENDAR
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

    // Deep links (F3): the URL that launched (or re-opened) the app, delivered to any
    // linking.onurl subscribers. lastUrl is held so a subscriber that registers after launch
    // still gets it.
    private var lastUrl: String? = null
    private val urlTokens = mutableSetOf<String>()
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        newIntent.data?.toString()?.let { url -> lastUrl = url; runOnUiThread { urlTokens.forEach { resolve(it, url) } } }
    }

    // Media picker + camera (F3): each launch holds (engine token, camera output uri | null)
    // under its request code; the result is copied into app files and answered as "file://".
    private var mediaSeq = 9000
    private val pendingMedia = mutableMapOf<Int, Pair<String, android.net.Uri?>>()
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val entry = pendingMedia.remove(requestCode) ?: return
        val (token, outUri) = entry
        if (resultCode != RESULT_OK) { fail(token, "canceled"); return }
        val src = outUri ?: data?.data
        if (src == null) { fail(token, "no image"); return }
        try {
            val dest = java.io.File(filesDir, "picked-$requestCode.jpg")
            contentResolver.openInputStream(src)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            resolve(token, "file://" + dest.absolutePath)
        } catch (e: Exception) { fail(token, "copy failed: ${e.message}") }
    }

    // Location (F3): fine or coarse grant is enough to read a fix; pick GPS, else the
    // network provider (LocationManager, not fused — fused needs Google Play services).
    private fun hasLocationPerm(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun bestLocationProvider(lm: android.location.LocationManager): String? = when {
        lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) -> android.location.LocationManager.GPS_PROVIDER
        lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) -> android.location.LocationManager.NETWORK_PROVIDER
        else -> null
    }
    // "lat,lng,accuracy,altitude,speed,heading" — matches the iOS payload shape.
    private fun locFixStr(l: android.location.Location): String =
        "${l.latitude},${l.longitude},${l.accuracy},${l.altitude},${l.speed},${l.bearing}"

    // Motion (F3): stream a sensor's first three axes as "x,y,z" at ~20Hz until cancelled.
    private fun startSensor(token: String, type: Int) {
        val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(type)
        if (sensor == null) { fail(token, "sensor unavailable"); return }
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                resolve(token, "${e.values[0]},${e.values[1]},${e.values[2]}")
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, 50_000)   // 50ms sampling ≈ 20Hz
        streamTeardown[token] = { sm.unregisterListener(listener) }
    }

    // Biometrics (F3): the framework BiometricPrompt (API 28+, no androidx dependency).
    // onAuthenticationSucceeded -> "success"; a cancel/lockout/error -> fail(); a single
    // non-match (onAuthenticationFailed) leaves the prompt open for a retry.
    private fun authenticateBiometric(token: String, reason: String) {
        if (android.os.Build.VERSION.SDK_INT < 28) { fail(token, "biometrics unavailable"); return }
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
            .setTitle("Authenticate")
            .setSubtitle(if (reason.isEmpty()) "Confirm your identity" else reason)
            .setNegativeButton("Cancel", mainExecutor, android.content.DialogInterface.OnClickListener { _, _ -> })
            .build()
        prompt.authenticate(android.os.CancellationSignal(), mainExecutor,
            object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                    runOnUiThread { resolve(token, "success") }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    runOnUiThread { fail(token, errString.toString()) }
                }
                override fun onAuthenticationFailed() {}   // one non-match; prompt stays open
            })
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
    private var mediaRecorder: android.media.MediaRecorder? = null   // mic recording (Tier C)
    private var recPath: String? = null

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
    // A single buzz of `ms`.
    private fun hapticVibrate(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(ms.coerceAtLeast(1), VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(ms)
    }
    // A wait,buzz,wait,buzz sequence (ms) — matches VibrationEffect.createWaveform's
    // off-first timing, so the Chuks and iOS semantics line up.
    private fun hapticPattern(csv: String) {
        val timings = csv.split(",").mapNotNull { it.trim().toLongOrNull() }.toLongArray()
        if (timings.isEmpty()) return
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createWaveform(timings, -1))
        else @Suppress("DEPRECATION") v.vibrate(timings, -1)
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
            "VideoControls" -> TextureView(this).also { it.isOpaque = false; videoControlsIds.add(id) }   // + native MediaController
            "CameraView" -> TextureView(this).also {                     // Camera2 preview (iOS: AVCaptureVideoPreviewLayer)
                it.isOpaque = true
                cameraIds.add(id)
                cameraController = CameraController(it)
            }
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
            "Gesture" -> FrameLayout(this).also { g ->                   // swipe / double-tap / long-press + continuous pan/pinch/rotate
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
                // Continuous state, per Gesture view (physical px -> logical via /density, matching tx units).
                var panSX = 0f; var panSY = 0f
                var vt: android.view.VelocityTracker? = null
                var scaleAcc = 1f
                var rotStart = 0f
                val scaleDet = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean { scaleAcc = 1f; return true }
                    override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                        if (gestureCont[id]?.contains("pinch") == true) { scaleAcc *= d.scaleFactor; dispatchGesture(g, "pinch:1,${(scaleAcc * 100).toInt()},0") }
                        return true
                    }
                    override fun onScaleEnd(d: android.view.ScaleGestureDetector) {
                        if (gestureCont[id]?.contains("pinch") == true) dispatchGesture(g, "pinch:2,${(scaleAcc * 100).toInt()},0")
                    }
                })
                g.setOnTouchListener { _, ev ->
                    detector.onTouchEvent(ev)
                    val cont = gestureCont[id] ?: ""
                    if (cont.contains("pinch") || cont.contains("rotate")) scaleDet.onTouchEvent(ev)
                    if (cont.isNotEmpty()) {
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                g.parent?.requestDisallowInterceptTouchEvent(true)   // win over a parent Scroll
                                panSX = ev.x; panSY = ev.y
                                vt = android.view.VelocityTracker.obtain(); vt?.addMovement(ev)
                                if (cont.contains("pan")) dispatchGesture(g, "pan:0,0,0,0,0")
                            }
                            MotionEvent.ACTION_POINTER_DOWN -> if (cont.contains("rotate") && ev.pointerCount >= 2) {
                                rotStart = Math.toDegrees(Math.atan2((ev.getY(1) - ev.getY(0)).toDouble(), (ev.getX(1) - ev.getX(0)).toDouble())).toFloat()
                            }
                            MotionEvent.ACTION_MOVE -> {
                                vt?.addMovement(ev)
                                if (cont.contains("pan") && ev.pointerCount == 1) {
                                    val dx = ((ev.x - panSX) / density).toInt(); val dy = ((ev.y - panSY) / density).toInt()
                                    dispatchGesture(g, "pan:1,$dx,$dy,0,0")
                                }
                                if (cont.contains("rotate") && ev.pointerCount >= 2) {
                                    val ang = Math.toDegrees(Math.atan2((ev.getY(1) - ev.getY(0)).toDouble(), (ev.getX(1) - ev.getX(0)).toDouble())).toFloat()
                                    dispatchGesture(g, "rotate:1,${(ang - rotStart).toInt()},0")
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                if (cont.contains("pan")) {
                                    vt?.addMovement(ev); vt?.computeCurrentVelocity(1000)
                                    val vx = ((vt?.xVelocity ?: 0f) / density).toInt(); val vy = ((vt?.yVelocity ?: 0f) / density).toInt()
                                    val dx = ((ev.x - panSX) / density).toInt(); val dy = ((ev.y - panSY) / density).toInt()
                                    dispatchGesture(g, "pan:2,$dx,$dy,$vx,$vy")
                                }
                                if (cont.contains("rotate")) dispatchGesture(g, "rotate:2,0,0")
                                vt?.recycle(); vt = null
                            }
                        }
                    }
                    true
                }
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
            "Input" -> EditText(this).also { ed ->
                ed.setSingleLine(); ed.setPadding(dp(10), 0, dp(10), 0)
                ed.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {}
                    override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
                    override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {
                        if (fieldSelfSet) return   // a controlled value.set, not a user edit
                        val action = ed.getTag(TAG) as? String ?: return
                        applyStream(engInput(action, ed.text.toString()))
                        relayout()
                    } })
                ed.setOnEditorActionListener { _, _, _ -> fieldSubmit[ed]?.let { hostEvent(it) }; true }   // onSubmit (IME action)
                ed.onFocusChangeListener = View.OnFocusChangeListener { _, has ->
                    if (has) fieldFocus[ed]?.let { hostEvent(it) } else fieldBlur[ed]?.let { hostEvent(it) }
                } }
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
                        val step = sliderStep[id] ?: 0
                        val p = if (step > 0) Math.round(progress.toFloat() / step) * step else progress
                        if (step > 0 && p != progress) s.progress = p   // snap the thumb (re-fire is fromUser=false, ignored)
                        hostInput(action, (p + min).toString())
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {
                        val done = sliderDone[id] ?: return          // onSlidingComplete: fire once on release
                        val min = sliderMin[id] ?: 0
                        val step = sliderStep[id] ?: 0
                        val p = if (step > 0) Math.round(s.progress.toFloat() / step) * step else s.progress
                        hostInput(done, (p + min).toString())
                    }
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
            "Scroll" -> SnapScrollView(this).also { sc ->
                N.ySetF(n, 34, 2f)   // Yoga overflow:scroll so content is sized at its full height
                sc.isFillViewport = false
                sc.viewTreeObserver.addOnScrollChangedListener { if (pushViewport()) relayout(); updateVideoVisibility(); reportScroll(id, sc.scrollY) }
                sc.viewTreeObserver.addOnGlobalLayoutListener { updateVideoVisibility() }   // attach on-screen videos on the initial (static) layout too
                listScroll = sc; scrollId = id; listHoriz = false }
            "HScroll" -> SnapHScrollView(this).also { sc ->   // horizontal list (carousel); snaps when paging=1
                N.ySetF(n, 34, 2f)   // Yoga overflow:scroll so content is sized at its full width
                sc.isFillViewport = false
                sc.isHorizontalScrollBarEnabled = false
                sc.viewTreeObserver.addOnScrollChangedListener { if (pushViewport()) relayout(); reportScroll(id, sc.scrollX) }
                listScroll = sc; scrollId = id; listHoriz = true }
            "Modal" -> FrameLayout(this).also {         // full-screen dimmed scrim; content laid out inside
                it.setBackgroundColor(Color.argb(128, 0, 0, 0))
                it.visibility = View.GONE                // shown when mvis=1
                modalIds.add(id)
            }
            else -> FrameLayout(this)
        }
        views[id] = v
        ynodes[id] = n
        if (v is TextView && v !is Button && v !is EditText) { textNodes[n] = v; N.ySetTextMeasure(n) }
    }

    private val textNodes = HashMap<Long, TextView>()

    // pending visual state per view (bg color + radius) -> a GradientDrawable
    private val bgColor = HashMap<String, Int>()
    private val bgRadius = HashMap<String, Float>()
    private val glassIds = HashSet<String>()   // Liquid Glass: no backdrop blur on Android views, so a translucent frosted panel
    private val pressOpacity = HashMap<String, Float>()   // id -> Pressable active alpha (0-1)
    private val longPressActions = HashMap<String, String>()   // id -> onLongPress action
    private val pressInActions = HashMap<String, String>()     // id -> onPressIn action
    private val pressOutActions = HashMap<String, String>()    // id -> onPressOut action
    private val disabledIds = HashSet<String>()                // ids whose disabled=1 (block fire)
    private val longDelayMs = HashMap<String, Long>()          // id -> onLongPress hold time (ms)
    private val mediaLoad = HashMap<String, String>()          // id -> onLoad action (Image)
    private val mediaError = HashMap<String, String>()         // id -> onError action (Image)
    private val mediaEnd = HashMap<String, String>()           // id -> onEnd action (Video)
    private val mediaProgress = HashMap<String, String>()      // id -> onProgress action (Video)
    private val imageTint = HashMap<String, Int>()             // id -> Image tintColor
    private val imageBlur = HashMap<String, Float>()           // id -> Image blur radius (px)
    private val imageOpChain = HashMap<String, String>()       // id -> Image GPU op-chain (JSON)
    private val imageOrigBmp = HashMap<String, android.graphics.Bitmap>()   // id -> pre-op bitmap, for re-applying a changed chain
    private val imageSrc = HashMap<String, String>()           // id -> local image source (asset name or file://), for RN-style sized re-decode
    private val imageDecodedDim = HashMap<String, Int>()       // id -> power-of-two bucket last decoded at (guards relayout re-decode)
    private val videoSeek = HashMap<String, Int>()             // id -> last-applied seek (seconds)
    private val videoControlsIds = HashSet<String>()           // ids that show a native MediaController
    private val videoMediaControllers = HashMap<String, android.widget.MediaController>()  // id -> attached controller
    private val videoLastSec = HashMap<String, Int>()          // id -> last whole second reported to onProgress
    private val progressPollers = HashMap<String, Runnable>()  // id -> the active progress poll Runnable
    private val borderW = HashMap<String, Float>()   // border width (px)
    private val borderC = HashMap<String, Int>()     // border color
    private val borderStyleM = HashMap<String, String>()   // border style: dashed | dotted
    private val bwSideM = HashMap<String, FloatArray>()     // per-side border [t,r,b,l] (px)
    private val textWidthPx = HashMap<String, Float>()   // id -> explicit Text width (px), so text WRAPS to it
    private val explicitHeight = HashSet<String>()       // ids with an explicit `h`; a Button then keeps it instead of self-measuring
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
        bgColor.remove(id); bgRadius.remove(id); borderW.remove(id); borderC.remove(id); pressOpacity.remove(id); textWidthPx.remove(id); explicitHeight.remove(id)
        borderStyleM.remove(id); bwSideM.remove(id)
        var fsPx = dpf(14f)
        var bold = false
        var customFont = ""   // a registered font family (e.g. an icon font)
        var weightStr = ""    // font-weight name (thin..black); "" = default
        var italicText = false
        var deco = ""         // underline | strike
        var txform = ""       // upper | lower | cap
        var tracking = -9999f // letter spacing (px); em-normalized by font size at apply
        var leading = -1f     // line height (px)
        // per-corner radius (rtl/rtr/rbr/rbl), px; -1 = unset
        var rcTL = -1f; var rcTR = -1f; var rcBR = -1f; var rcBL = -1f
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
                "d" -> N.ySetF(n, 0, when (vl) { "row" -> 1f; "row-reverse" -> 2f; "col-reverse" -> 3f; else -> 0f })
                "j" -> N.ySetF(n, 1, justify(vl))
                "a" -> N.ySetF(n, 2, align(vl))
                "g" -> N.ySetF(n, 3, f)
                "basis" -> N.ySetF(n, 4, dpf(f))
                "w" -> { N.ySetF(n, 5, dpf(f)); textWidthPx[id] = dpf(f)   // record so Text wraps to this width
                    // A HorizontalScrollView measures its child with UNSPECIFIED width, so force
                    // the content to its true (wide) width via minimumWidth, mirroring `h` below.
                    v.minimumWidth = dpf(f).toInt() }
                "h" -> { N.ySetF(n, 6, dpf(f)); explicitHeight.add(id)
                    // A ScrollView measures its child with UNSPECIFIED height, so a
                    // FrameLayout content sizes to its (windowed) children and ignores the
                    // Yoga height — collapsing the scroll range. minimumHeight forces the
                    // content to measure to its true (tall) height so the List scrolls.
                    v.minimumHeight = dpf(f).toInt() }
                "p" -> N.ySetF(n, 7, dpf(f))
                "px" -> N.ySetF(n, 14, dpf(f))   // horizontal padding
                "py" -> N.ySetF(n, 15, dpf(f))   // vertical padding
                "pt" -> N.ySetF(n, 16, dpf(f)); "pr" -> N.ySetF(n, 17, dpf(f)); "pb" -> N.ySetF(n, 18, dpf(f)); "pl" -> N.ySetF(n, 19, dpf(f))
                "mt" -> N.ySetF(n, 20, dpf(f)); "mr" -> N.ySetF(n, 21, dpf(f)); "mb" -> N.ySetF(n, 22, dpf(f)); "ml" -> N.ySetF(n, 23, dpf(f))
                "minw" -> N.ySetF(n, 24, dpf(f)); "maxw" -> N.ySetF(n, 25, dpf(f)); "minh" -> N.ySetF(n, 26, dpf(f)); "maxh" -> N.ySetF(n, 27, dpf(f))
                "wpct" -> N.ySetF(n, 28, f); "hpct" -> N.ySetF(n, 29, f); "aspect" -> N.ySetF(n, 30, f)
                "bottom" -> N.ySetF(n, 31, dpf(f))
                "gap" -> N.ySetF(n, 8, dpf(f))
                "pos" -> if (vl == "abs") N.ySetF(n, 9, 0f)
                "top" -> N.ySetF(n, 10, dpf(f))
                "left" -> N.ySetF(n, 11, dpf(f))
                "right" -> N.ySetF(n, 12, dpf(f))
                "on" -> (v as? Switch)?.isChecked = (vl == "1")
                "bg" -> if (v is Switch) {
                    v.trackTintList = ColorStateList.valueOf(Color.parseColor("#$vl"))   // on-track = primary
                    v.thumbTintList = ColorStateList.valueOf(switchThumb[id] ?: Color.WHITE)   // white thumb, like iOS (unless thumbColor set)
                } else bgColor[id] = Color.parseColor("#$vl")
                "swtc" -> if (v is Switch) {                                              // Switch thumb (knob) color
                    val c = Color.parseColor("#$vl"); switchThumb[id] = c
                    v.thumbTintList = ColorStateList.valueOf(c)
                }
                "r" -> bgRadius[id] = dpf(f)
                "bw" -> borderW[id] = dpf(f)
                "bc" -> borderC[id] = Color.parseColor("#$vl")
                "bstyle" -> if (vl == "dashed" || vl == "dotted") borderStyleM[id] = vl
                "bwt" -> (bwSideM.getOrPut(id) { FloatArray(4) { -1f } })[0] = dpf(f)
                "bwr" -> (bwSideM.getOrPut(id) { FloatArray(4) { -1f } })[1] = dpf(f)
                "bwb" -> (bwSideM.getOrPut(id) { FloatArray(4) { -1f } })[2] = dpf(f)
                "bwl" -> (bwSideM.getOrPut(id) { FloatArray(4) { -1f } })[3] = dpf(f)
                "opacity" -> opacity = f / 100f
                "tx" -> { tx = f; hasTransform = true }
                "ty" -> { ty = f; hasTransform = true }
                "rot" -> { rot = f; hasTransform = true }
                "sc" -> { sc = f / 100f; hasTransform = true }
                "anim" -> animMs = f.toInt()
                "ez" -> animEz = vl
                "shadow" -> v.elevation = dpf(if (f >= 3f) 12f else if (f == 2f) 6f else 3f)
                "glass" -> if (vl == "1") glassIds.add(id) else glassIds.remove(id)
                "wrap" -> N.ySetF(n, 13, if (vl == "wrap") 1f else 0f)
                "fs" -> fsPx = dpf(f)
                "fw" -> { weightStr = vl; bold = (vl == "bold" || vl == "semibold" || vl == "extrabold" || vl == "black") }
                "fontfam" -> customFont = vl                       // font-family
                "italic" -> italicText = (vl == "1")
                "deco" -> deco = if (vl == "none") "" else vl      // underline/strike
                "txform" -> txform = if (vl == "none") "" else vl  // upper/lower/cap
                "tracking" -> tracking = dpf(f)                    // letter spacing (px)
                "leading" -> leading = dpf(f)                      // line height (px)
                "hidden" -> {                                      // display:none — out of layout + gone
                    val hid = (vl == "1")
                    v.visibility = if (hid) View.GONE else View.VISIBLE
                    N.ySetF(n, 32, if (hid) 1f else 0f)
                }
                "overflow" -> {   // overflow-hidden: clip children to the (rounded) bounds, like iOS clipsToBounds
                    val clip = (vl == "hidden")
                    (v as? ViewGroup)?.clipChildren = clip
                    v.clipToOutline = clip   // rounds the clip to the bg drawable's outline
                }
                "self" -> N.ySetF(n, 33, align(vl))                // align-self
                "z" -> v.translationZ = dpf(f)                     // z-index
                "rtl" -> rcTL = dpf(f)
                "rtr" -> rcTR = dpf(f)
                "rbr" -> rcBR = dpf(f)
                "rbl" -> rcBL = dpf(f)
                "slv" -> slV = f.toInt()                 // Slider: current value
                "slmin" -> slMin = f.toInt()             // Slider: min
                "slmax" -> slMax = f.toInt()             // Slider: max
                "slstep" -> sliderStep[id] = f.toInt()   // Slider: snap step
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
                "vplay" -> {                    // controllable playback: vplay=0 pauses (feed cells drive this)
                    val want = vl != "0"; videoPlayPref[id] = want
                    videoPlayers[id]?.let { try { if (want) it.start() else if (it.isPlaying) it.pause() } catch (e: Exception) {} }
                }
                "vmute" -> {
                    val muted = vl != "0"; videoMutePref[id] = muted
                    videoPlayers[id]?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
                }
                "vloop" -> {
                    val lp = vl != "0"; videoLoopPref[id] = lp
                    videoPlayers[id]?.let { try { it.isLooping = lp } catch (e: Exception) {} }
                }
                "vfit" -> {}                    // cover is the default; contain reserved for a later slice
                "paging" -> {   // List/Scroll snap-per-screen (vertical feed or horizontal carousel)
                    (v as? SnapScrollView)?.pageSnap = (vl == "1")
                    (v as? SnapHScrollView)?.pageSnap = (vl == "1")
                }
                "stick" -> if (v is ScrollView) stickBottomOn = (vl == "1")   // Scroll stickBottom (chat)
                "press" -> pressOpacity[id] = f / 100f   // Pressable active alpha
                "nlines" -> (v as? TextView)?.let {   // Text: cap lines; default to a tail ellipsis (UIKit/SwiftUI do), an explicit `ellip` overrides
                    val n = f.toInt()                 // nlines=-1 means "no cap" -> unlimited, and no forced ellipsis
                    if (n > 0) { it.maxLines = n; if (it.ellipsize == null) it.ellipsize = android.text.TextUtils.TruncateAt.END }
                    else { it.maxLines = Integer.MAX_VALUE }
                    measureText(id, it) }
                "ellip" -> (v as? TextView)?.let {                                                    // Text truncation mode
                    it.ellipsize = when (vl) { "head" -> android.text.TextUtils.TruncateAt.START
                        "middle" -> android.text.TextUtils.TruncateAt.MIDDLE
                        "clip" -> null; else -> android.text.TextUtils.TruncateAt.END } }
                "dis" -> { v.alpha = if (vl == "1") 0.4f else 1f; v.isEnabled = (vl != "1")            // disabled: dim + block
                    if (vl == "1") disabledIds.add(id) else disabledIds.remove(id) }
                "tint" -> (v as? ImageView)?.let { val c = Color.parseColor("#" + vl); imageTint[id] = c; it.setColorFilter(c) }   // Image tintColor
                "filt" -> (v as? ImageView)?.let { val m = photoMatrix(vl); if (m != null) it.colorFilter = android.graphics.ColorMatrixColorFilter(m) else it.clearColorFilter() }   // Image photo filter
                "seek" -> videoPlayers[id]?.let { mp ->                                                // Video seek (seconds)
                    val secs = f.toInt()
                    if (videoSeek[id] != secs) { videoSeek[id] = secs; try { mp.seekTo(secs * 1000) } catch (e: Exception) {} } }
                "vvol" -> videoPlayers[id]?.let { try { it.setVolume(f / 100f, f / 100f) } catch (e: Exception) {} }   // Video volume 0-100
                "vrate" -> videoPlayers[id]?.let { mp -> try {                                          // playback speed percent
                    if (mp.isPlaying) mp.playbackParams = mp.playbackParams.setSpeed(f / 100f) } catch (e: Exception) {} }
                "ldelay" -> longDelayMs[id] = f.toLong()                                                // onLongPress hold time (ms)
                "sel" -> (v as? TextView)?.setTextIsSelectable(vl == "1")                                // Text selectable
                "hitslop" -> { val slop = dp(f.toInt()); v.post {                                        // enlarge the tap area
                    (v.parent as? View)?.let { p -> val r = android.graphics.Rect(); v.getHitRect(r)
                        r.inset(-slop, -slop); p.touchDelegate = android.view.TouchDelegate(r, v) } } }
                "blur" -> imageBlur[id] = f   // Image blurRadius; applied to the bitmap when it loads (below)
                "sec" -> (v as? EditText)?.let {         // password field: mask input
                    if (vl == "1") {
                        it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                        it.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                    }
                }
                "kbt" -> (v as? EditText)?.let {
                    val it2 = android.text.InputType.TYPE_CLASS_TEXT
                    it.inputType = when (vl) {
                        "email" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        "number" -> android.text.InputType.TYPE_CLASS_NUMBER
                        "decimal" -> android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        "phone" -> android.text.InputType.TYPE_CLASS_PHONE
                        "url" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                        else -> it2 }
                    it.setSingleLine()
                }
                "ret" -> (v as? EditText)?.let {
                    it.imeOptions = when (vl) {
                        "send" -> android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                        "search" -> android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                        "next" -> android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                        "go" -> android.view.inputmethod.EditorInfo.IME_ACTION_GO
                        else -> android.view.inputmethod.EditorInfo.IME_ACTION_DONE }
                }
                "edit" -> (v as? EditText)?.let { it.isEnabled = (vl == "1"); it.isFocusable = (vl == "1"); it.isFocusableInTouchMode = (vl == "1") }
                "afoc" -> if (vl == "1") (v as? EditText)?.let { it.post { it.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.showSoftInput(it, 0) } }
                "acap" -> (v as? EditText)?.let {
                    val base = it.inputType and android.text.InputType.TYPE_MASK_FLAGS.inv()
                    val cap = when (vl) {
                        "sentences" -> android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        "words" -> android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
                        "characters" -> android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                        else -> 0 }
                    it.inputType = base or cap
                }
                "acor" -> (v as? EditText)?.let {
                    it.inputType = if (vl == "1") it.inputType or android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                                   else it.inputType and android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT.inv()
                }
                "maxlen" -> (v as? EditText)?.let { val n = vl.toIntOrNull() ?: -1; if (n >= 0) it.filters = arrayOf(android.text.InputFilter.LengthFilter(n)) }
                "sbh" -> setStatusBarHidden(vl == "1")   // StatusBar: hide/show
                "sbstyle" -> setStatusBarStyle(vl)       // StatusBar: light/dark icons
                "sbcolor" -> setStatusBarColor(vl)       // StatusBar: background color
                "sbnavcolor" -> setNavBarColor(vl)       // StatusBar: Android nav-bar color
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
            // Typeface: family (icon/custom font) or system, with bold/italic style bits.
            val styleBits = (if (bold) android.graphics.Typeface.BOLD else 0) or
                            (if (italicText) android.graphics.Typeface.ITALIC else 0)
            val base = if (customFont.isNotEmpty()) iconFont(customFont) else android.graphics.Typeface.DEFAULT
            it.setTypeface(base, styleBits)
            // Finer weights (thin/light/medium) on API 28+, keeping the italic bit.
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val wgt = when (weightStr) {
                    "thin" -> 100; "extralight" -> 200; "light" -> 300; "normal", "regular" -> 400
                    "medium" -> 500; "semibold" -> 600; "bold" -> 700; "extrabold" -> 800; "black" -> 900
                    else -> -1
                }
                if (wgt > 0) it.typeface = android.graphics.Typeface.create(base ?: android.graphics.Typeface.DEFAULT, wgt, italicText)
            }
            // Decoration: underline / line-through via paint flags (preserve antialias).
            var flags = it.paintFlags and
                (android.graphics.Paint.UNDERLINE_TEXT_FLAG or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG).inv()
            if (deco == "underline") flags = flags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            if (deco == "strike") flags = flags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            it.paintFlags = flags
            // Letter spacing is em-relative on Android; convert px -> em by the font size.
            it.letterSpacing = if (tracking != -9999f && fsPx > 0f) tracking / fsPx else 0f
            // Line height (leading).
            if (leading >= 0f && android.os.Build.VERSION.SDK_INT >= 28) it.lineHeight = leading.toInt()
            // Case transform.
            it.transformationMethod = when (txform) {
                "upper" -> object : android.text.method.ReplacementTransformationMethod() {
                    override fun getOriginal() = CharArray(0); override fun getReplacement() = CharArray(0)
                    override fun getTransformation(source: CharSequence?, v: View?): CharSequence = source?.toString()?.uppercase() ?: ""
                }
                "lower" -> object : android.text.method.ReplacementTransformationMethod() {
                    override fun getOriginal() = CharArray(0); override fun getReplacement() = CharArray(0)
                    override fun getTransformation(source: CharSequence?, v: View?): CharSequence = source?.toString()?.lowercase() ?: ""
                }
                "cap" -> object : android.text.method.ReplacementTransformationMethod() {
                    override fun getOriginal() = CharArray(0); override fun getReplacement() = CharArray(0)
                    override fun getTransformation(source: CharSequence?, v: View?): CharSequence =
                        source?.toString()?.split(" ")?.joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } } ?: ""
                }
                else -> null
            }
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
        val hasPerCorner = rcTL >= 0f || rcTR >= 0f || rcBR >= 0f || rcBL >= 0f
        val side = bwSideM[id]
        if (side != null && v !is TextureView) {   // per-side border: a custom drawable draws the set edges
            val bc = borderC[id] ?: Color.parseColor("#334155")
            v.background = SideBorderDrawable(bgColor[id] ?: Color.TRANSPARENT, bgRadius[id] ?: 0f,
                if (side[0] >= 0f) side[0] else 0f, if (side[1] >= 0f) side[1] else 0f,
                if (side[2] >= 0f) side[2] else 0f, if (side[3] >= 0f) side[3] else 0f, bc)
        } else if (bgColor.containsKey(id) || bgRadius.containsKey(id) || borderW.containsKey(id) || glassIds.contains(id) || hasPerCorner) {
            val gd = GradientDrawable()
            gd.setColor(if (glassIds.contains(id)) Color.argb(56, 255, 255, 255) else (bgColor[id] ?: Color.TRANSPARENT))   // frosted translucent
            if (hasPerCorner) {   // rounded-t-*, rounded-bl-*, … : per-corner radii (tl, tr, br, bl x2)
                val tl = if (rcTL >= 0f) rcTL else 0f; val tr = if (rcTR >= 0f) rcTR else 0f
                val br = if (rcBR >= 0f) rcBR else 0f; val bl = if (rcBL >= 0f) rcBL else 0f
                gd.cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
            } else gd.cornerRadius = bgRadius[id] ?: 0f
            val bw = borderW[id]
            if (bw != null && bw > 0f) {
                val bc = borderC[id] ?: Color.parseColor("#334155")
                when (borderStyleM[id]) {   // dashed/dotted -> a dashed stroke
                    "dashed" -> gd.setStroke(bw.toInt(), bc, dpf(6f), dpf(3f))
                    "dotted" -> gd.setStroke(bw.toInt(), bc, dpf(1.5f), dpf(2.5f))
                    else -> gd.setStroke(bw.toInt(), bc)
                }
            }
            else if (glassIds.contains(id)) gd.setStroke(dpf(1f).toInt(), Color.argb(40, 255, 255, 255))   // subtle glass rim
            if (v !is TextureView) v.background = gd   // TextureView rejects a background drawable (rounded below via outline)
        } else if (v !is Switch && v !is TextureView && !modalIds.contains(id)) {
            v.background = null   // the new style has no bg/border: clear a stale drawable on a reused view
        }                        // (a Modal keeps its dim scrim background)
        // A TextureView (Video / CameraView) can't take a rounded background drawable; clip
        // its corners via the outline instead so radius still works.
        if (v is TextureView && bgRadius.containsKey(id)) {
            val rad = bgRadius[id]!!
            v.clipToOutline = true
            v.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, rad)
                }
            }
        }
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
    private val videoPlayPref = HashMap<String, Boolean>()           // id -> controllable playing (default true); a feed cell drives this
    private val videoMutePref = HashMap<String, Boolean>()           // id -> muted (default true)
    private val videoLoopPref = HashMap<String, Boolean>()           // id -> loop (default true)
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

    // Video `controls`: Android's native MediaController (play/pause, scrubber, seek buttons)
    // anchored to the video; it pops up on tap and auto-hides. (The richer fullscreen/PiP/subtitle
    // menus that AVPlayerViewController gives on iOS would need ExoPlayer's PlayerView on Android.)
    private fun attachMediaController(id: String, tv: TextureView, mp: MediaPlayer) {
        if (videoMediaControllers.containsKey(id)) return
        val ctrl = object : android.widget.MediaController.MediaPlayerControl {
            override fun start() { try { mp.start() } catch (e: Exception) {} }
            override fun pause() { try { mp.pause() } catch (e: Exception) {} }
            override fun getDuration() = try { mp.duration } catch (e: Exception) { 0 }
            override fun getCurrentPosition() = try { mp.currentPosition } catch (e: Exception) { 0 }
            override fun seekTo(pos: Int) { try { mp.seekTo(pos) } catch (e: Exception) {} }
            override fun isPlaying() = try { mp.isPlaying } catch (e: Exception) { false }
            override fun getBufferPercentage() = 0
            override fun canPause() = true
            override fun canSeekBackward() = true
            override fun canSeekForward() = true
            override fun getAudioSessionId() = try { mp.audioSessionId } catch (e: Exception) { 0 }
        }
        val mc = android.widget.MediaController(this)
        mc.setMediaPlayer(ctrl); mc.setAnchorView(tv); mc.isEnabled = true
        tv.setOnClickListener { mc.show() }
        videoMediaControllers[id] = mc
        tv.post { mc.show(0) }   // show once, sticky, until first tap toggles it
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
                } else if (asset.startsWith("file://")) {
                    mp.setDataSource(asset.substring(7))     // captured / downloaded file
                } else {
                    val afd = assets.openFd(asset)           // bundled asset
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                }
            } catch (e: Exception) { videoAlive.remove(mp); mp.release(); return }
            mp.isLooping = videoLoopPref[id] ?: true
            val muted0 = videoMutePref[id] ?: true; mp.setVolume(if (muted0) 0f else 1f, if (muted0) 0f else 1f)
            mp.setOnPreparedListener { videoReady.add(it); if (videoPlayPref[id] != false) it.start()
                if (videoControlsIds.contains(id)) attachMediaController(id, tv, it) }
            // onEnd: MediaPlayer only fires completion when NOT looping. Fire onEnd for the id
            // currently showing this (pooled) player.
            mp.setOnCompletionListener { player ->
                videoPlayers.entries.firstOrNull { it.value === player }?.key?.let { curId -> mediaEnd[curId]?.let { fire(it) } }
            }
            mp.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) { videoSizes[mp] = Pair(w, h); applyCover(tv, mp) } }
            // A decoder that fails (routine on the emulator's ~2-decoder ceiling)
            // must free its slot at once, or dead players pile up and clog the cap
            // until every card goes black. Reclaim it and let another card try.
            mp.setOnErrorListener { player, _, _ -> killVideo(player); true }
        }
        videoPlayers[id] = mp
        videoKey[id] = asset
        bindSurface(tv, mp)
        // Apply this node's control prefs. loop/mute are safe in any state; a reused player
        // is already prepared so honor its play state now (a new one starts in onPrepared).
        try { mp.isLooping = videoLoopPref[id] ?: true } catch (e: Exception) {}
        val muted = videoMutePref[id] ?: true; try { mp.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f) } catch (e: Exception) {}
        if (reused != null) {
            try { if (videoPlayPref[id] == false) { if (mp.isPlaying) mp.pause() } else mp.start() } catch (e: Exception) {}
        }
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

    // A controlled TextInput's value. Set the native text ONLY when it differs (the field
    // is controlled, so the same value re-emits every keystroke) to avoid fighting the user;
    // fieldSelfSet stops the TextWatcher from reporting this programmatic change as an edit.
    private fun setFieldValue(id: String, value: String) {
        val ed = views[id] as? android.widget.EditText ?: return
        if (ed.text.toString() != value) {
            fieldSelfSet = true
            ed.setText(value)
            ed.setSelection(value.length)   // cursor to end
            fieldSelfSet = false
        }
    }

    private fun setText(id: String, t: String) {
        if (gestureIds.contains(id)) {                       // a Gesture's "text" is its continuous-recognizer list
            gestureCont[id] = t
            return
        }
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
        if (cameraIds.contains(id)) { cameraController?.setFacing(t); return }   // CameraView facing (node text)
        (views[id] as? DrawCanvas)?.let { it.shapes = t; return }               // Canvas shape list
        (views[id] as? android.webkit.WebView)?.let { it.loadUrl(t); return }   // WebView URL
        if (t.startsWith("http")) {                                           // remote image / background URL
            (bgImageViews[id] ?: views[id] as? ImageView)?.let { loadRemoteImage(t, it, id); return }
        }
        if (t.startsWith("file://")) {                                        // picked/captured local file
            (bgImageViews[id] ?: views[id] as? ImageView)?.let {
                imageSrc[id] = t; imageDecodedDim.remove(id)
                ensureSizedImage(id, if (root.width > 0) root.width else MAX_DIM)   // provisional; relayout() refines to the frame
                (if (imageDecodedDim.containsKey(id)) mediaLoad[id] else mediaError[id])?.let { a -> fire(a) }
                return
            }
        }
        (bgImageViews[id] ?: views[id] as? ImageView)?.let {                  // bundled local asset (e.g. chuks-logo.png)
            if (t.isNotEmpty()) {
                imageSrc[id] = t; imageDecodedDim.remove(id)
                ensureSizedImage(id, if (root.width > 0) root.width else MAX_DIM)
            }
            (if (imageDecodedDim.containsKey(id)) mediaLoad[id] else mediaError[id])?.let { a -> fire(a) }
            return
        }
        when (val v = views[id]) {
            is Button -> { v.text = t; measureButton(id, v) }
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
    // Text/style changed; the node owns a Yoga measure callback, so mark it dirty and Yoga
    // re-invokes measureTextNode with the resolved width on the next layout.
    private fun measureText(id: String, tv: TextView) {
        val n = ynodes[id] ?: return
        N.yMarkDirty(n)
    }

    // Yoga measure callback (from jni.cpp during layout): measure the TextView at the width
    // Yoga resolved, so text wraps to its container. wmode: 0 undefined, 1 exactly, 2 at-most.
    private fun measureTextNode(node: Long, width: Float, wmode: Int): Long {
        val tv = textNodes[node] ?: return 0L
        val wSpec = when (wmode) {
            1 -> View.MeasureSpec.makeMeasureSpec(width.toInt(), View.MeasureSpec.EXACTLY)
            2 -> View.MeasureSpec.makeMeasureSpec(width.toInt(), View.MeasureSpec.AT_MOST)
            else -> View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        }
        tv.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return (tv.measuredWidth.toLong() shl 32) or (tv.measuredHeight.toLong() and 0xffffffffL)
    }

    // A Button self-sizes its HEIGHT to its label (like iOS). Button subclasses
    // TextView but is handled before the TextView branch in setText, so it never ran
    // measureText and — with no explicit `h` — collapsed to 0-tall. Measure the
    // intrinsic height and set ONLY the Yoga height, leaving width auto so a Button
    // in a column still stretches full-width via align-stretch. Explicit `h` wins.
    private fun measureButton(id: String, b: Button) {
        if (explicitHeight.contains(id)) return
        val n = ynodes[id] ?: return
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        b.measure(unspec, unspec)
        N.ySetF(n, 6, b.measuredHeight.toFloat())
    }

    // Image `filter`: a GPU photo-filter preset as a ColorMatrix applied to the ImageView
    // (drawn on the hardware layer). null = no filter (normal/none). Mirrors the iOS CIFilter set.
    private fun photoMatrix(preset: String): android.graphics.ColorMatrix? {
        val cm = android.graphics.ColorMatrix()
        when (preset) {
            "mono" -> cm.setSaturation(0f)
            "noir" -> { cm.setSaturation(0f); cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(1.5f,0f,0f,0f,-40f, 0f,1.5f,0f,0f,-40f, 0f,0f,1.5f,0f,-40f, 0f,0f,0f,1f,0f))) }
            "sepia" -> { cm.setSaturation(0f); cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(1f,0f,0f,0f,40f, 0f,1f,0f,0f,20f, 0f,0f,1f,0f,-20f, 0f,0f,0f,1f,0f))) }
            "vivid" -> cm.setSaturation(1.6f)
            "cool" -> cm.set(floatArrayOf(0.9f,0f,0f,0f,0f, 0f,1f,0f,0f,0f, 0f,0f,1.18f,0f,0f, 0f,0f,0f,1f,0f))
            "warm" -> cm.set(floatArrayOf(1.18f,0f,0f,0f,0f, 0f,1.02f,0f,0f,0f, 0f,0f,0.85f,0f,0f, 0f,0f,0f,1f,0f))
            "fade" -> { cm.setSaturation(0.72f); cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(1f,0f,0f,0f,22f, 0f,1f,0f,0f,22f, 0f,0f,1f,0f,22f, 0f,0f,0f,1f,0f))) }
            else -> return null
        }
        return cm
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

    // Drives the live camera preview for a CameraView node on a TextureView, via Camera2
    // (no androidx/CameraX). Opens the requested lens, runs a repeating preview request, and
    // captures a still through an ImageReader on demand. One controller is retained by the
    // Activity while a CameraView is mounted (iOS: CameraController + AVCaptureSession).
    inner class CameraController(private val texture: TextureView) {
        private val camMgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        private var device: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var reader: ImageReader? = null
        private var camId: String? = null
        private var facing = "back"
        private var sensorOrientation = 0
        private val bg = HandlerThread("chuks.camera").apply { start() }
        private val bgH = Handler(bg.looper)
        private var onCapOk: ((String) -> Unit)? = null
        private var onCapErr: ((String) -> Unit)? = null

        init {
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { open() }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { close(); return true }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
            if (texture.isAvailable) open()
        }

        fun setFacing(f: String) {
            val want = if (f == "front") "front" else "back"
            if (want == facing) return
            facing = want
            if (texture.isAvailable) { close(); open() }
        }

        private fun pickCamera(): String? {
            val want = if (facing == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            for (id in camMgr.cameraIdList) {
                if (camMgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == want) return id
            }
            return camMgr.cameraIdList.firstOrNull()
        }

        @SuppressLint("MissingPermission")
        private fun open() {
            if (device != null) return
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            val id = pickCamera() ?: return
            camId = id
            try {
                camMgr.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) { device = cam; startPreview() }
                    override fun onDisconnected(cam: CameraDevice) { cam.close(); if (device === cam) device = null }
                    override fun onError(cam: CameraDevice, error: Int) { cam.close(); if (device === cam) device = null }
                }, bgH)
            } catch (e: Exception) {}
        }

        private fun startPreview() {
            val cam = device ?: return
            val st = texture.surfaceTexture ?: return
            val chars = camMgr.getCameraCharacteristics(camId ?: return)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = map?.getOutputSizes(SurfaceTexture::class.java)?.maxByOrNull { it.width.toLong() * it.height } ?: Size(1280, 720)
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(st)
            val jpegSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height } ?: previewSize
            reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            try {
                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                req.addTarget(previewSurface)
                cam.createCaptureSession(listOf(previewSurface, reader!!.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        try { s.setRepeatingRequest(req.build(), null, bgH) } catch (e: Exception) {}
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                }, bgH)
            } catch (e: Exception) {}
        }

        fun capture(ok: (String) -> Unit, err: (String) -> Unit) {
            val cam = device; val s = session; val rd = reader
            if (cam == null || s == null || rd == null) { err("camera not running"); return }
            onCapOk = ok; onCapErr = err
            rd.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage()
                if (img == null) { runOnUiThread { onCapErr?.invoke("no image") }; return@setOnImageAvailableListener }
                try {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                    // The ImageReader hands back raw sensor-oriented pixels (sideways, since the
                    // sensor is mounted rotated). Rotate them upright on save so the file is
                    // correct for every consumer, including our own EXIF-agnostic Image loader.
                    val deviceDeg = when (windowManager.defaultDisplay.rotation) {
                        Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
                    }
                    val rot = if (facing == "front") (sensorOrientation - deviceDeg + 360) % 360
                              else (sensorOrientation + deviceDeg) % 360
                    var bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null && (rot != 0 || facing == "front")) {
                        val m = android.graphics.Matrix()
                        if (rot != 0) m.postRotate(rot.toFloat())
                        if (facing == "front") m.postScale(-1f, 1f)   // un-mirror the selfie
                        bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    }
                    val f = java.io.File(filesDir, "cam_${System.nanoTime()}.jpg")
                    if (bmp != null) {
                        val out = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
                        f.writeBytes(out.toByteArray())
                    } else { f.writeBytes(bytes) }
                    runOnUiThread { onCapOk?.invoke("file://" + f.absolutePath) }
                } catch (e: Exception) { runOnUiThread { onCapErr?.invoke(e.message ?: "capture failed") } }
                finally { img.close() }
            }, bgH)
            try {
                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                req.addTarget(rd.surface)
                req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                s.capture(req.build(), null, bgH)
            } catch (e: Exception) { runOnUiThread { err(e.message ?: "capture failed") } }
        }

        fun close() {
            try { session?.close() } catch (e: Exception) {}; session = null
            try { device?.close() } catch (e: Exception) {}; device = null
            try { reader?.close() } catch (e: Exception) {}; reader = null
        }
    }

    // A ScrollView that snaps to the nearest full-screen page when paging is on (video feeds).
    // Pure-platform (no androidx ViewPager2): after a drag/fling settles, smooth-scroll to the
    // nearest multiple of the viewport height. Off by default = a normal ScrollView.
    inner class SnapScrollView(ctx: Context) : ScrollView(ctx) {
        var pageSnap = false
        private var lastY = -1
        private val settle = object : Runnable {
            override fun run() {
                if (!pageSnap) return
                if (scrollY == lastY) {
                    val ph = height
                    if (ph > 0) {
                        val target = Math.round(scrollY.toFloat() / ph) * ph
                        if (target != scrollY) smoothScrollTo(0, target)
                    }
                } else { lastY = scrollY; postDelayed(this, 80) }
            }
        }
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val handled = super.onTouchEvent(ev)
            if (pageSnap && (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL)) {
                lastY = -1; removeCallbacks(settle); postDelayed(settle, 80)
            }
            return handled
        }
    }

    // The horizontal counterpart: snaps to the nearest full-width page after a drag/fling
    // settles (a swipe carousel / onboarding pager). Same pure-platform approach as
    // SnapScrollView, snapping by the viewport WIDTH instead of height.
    inner class SnapHScrollView(ctx: Context) : HorizontalScrollView(ctx) {
        var pageSnap = false
        private var lastX = -1
        private val settle = object : Runnable {
            override fun run() {
                if (!pageSnap) return
                if (scrollX == lastX) {
                    val pw = width
                    if (pw > 0) {
                        val target = Math.round(scrollX.toFloat() / pw) * pw
                        if (target != scrollX) smoothScrollTo(target, 0)
                    }
                } else { lastX = scrollX; postDelayed(this, 80) }
            }
        }
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val handled = super.onTouchEvent(ev)
            if (pageSnap && (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL)) {
                lastX = -1; removeCallbacks(settle); postDelayed(settle, 80)
            }
            return handled
        }
    }

    // Draws a (rounded) background fill plus per-side border edges. Android has no native
    // per-side border, so we paint the edges ourselves. Radius applies to the fill only.
    inner class SideBorderDrawable(
        private val bg: Int, private val radius: Float,
        private val t: Float, private val r: Float, private val b: Float, private val l: Float,
        private val borderColor: Int
    ) : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: android.graphics.Canvas) {
            val bd = bounds
            val L = bd.left.toFloat(); val T = bd.top.toFloat(); val R = bd.right.toFloat(); val B = bd.bottom.toFloat()
            if (bg != Color.TRANSPARENT) {
                p.style = android.graphics.Paint.Style.FILL; p.color = bg
                canvas.drawRoundRect(L, T, R, B, radius, radius, p)
            }
            p.color = borderColor
            if (t > 0f) canvas.drawRect(L, T, R, T + t, p)
            if (b > 0f) canvas.drawRect(L, B - b, R, B, p)
            if (l > 0f) canvas.drawRect(L, T, L + l, B, p)
            if (r > 0f) canvas.drawRect(R - r, T, R, B, p)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun bytesToHex(b: ByteArray?): String {
        if (b == null) return ""
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }
    private fun hexToBytes(hex: String): ByteArray {
        val n = hex.length / 2
        val out = ByteArray(n)
        var i = 0
        while (i < n) { out[i] = ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte(); i++ }
        return out
    }

    // A BLE central via android.bluetooth.le (no androidx): scan, connect, and read /
    // write / subscribe to GATT characteristics. Values cross the bridge as hex strings.
    // The "bluetooth" permission (BLUETOOTH_SCAN + BLUETOOTH_CONNECT) must be granted.
    inner class BleManager {
        private val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        private val adapter get() = mgr.adapter
        private var scanner: BluetoothLeScanner? = null
        private var scanCb: ScanCallback? = null
        var scanToken: String? = null
        private val devices = HashMap<String, BluetoothDevice>()
        private val gatts = HashMap<String, BluetoothGatt>()
        private val chars = HashMap<String, BluetoothGattCharacteristic>()
        private val connectOk = HashMap<String, (String) -> Unit>()
        private val connectErr = HashMap<String, (String) -> Unit>()
        private val readOk = HashMap<String, (String) -> Unit>()
        private val readErr = HashMap<String, (String) -> Unit>()
        private val writeOk = HashMap<String, (String) -> Unit>()
        private val writeErr = HashMap<String, (String) -> Unit>()
        private val notifyTokens = HashMap<String, String>()

        init {
            // Re-emit adapter state to ble.state subscribers when Bluetooth is toggled.
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { bleStateTokens.toList().forEach { resolve(it, state()) } }
            }, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
        }

        fun state(): String = when { adapter == null -> "unsupported"; !adapter.isEnabled -> "off"; else -> "on" }
        private fun key(addr: String, s: String, c: String) = "$addr|${s.lowercase()}|${c.lowercase()}"
        private fun keyFor(addr: String, ch: BluetoothGattCharacteristic) = key(addr, ch.service.uuid.toString(), ch.uuid.toString())

        @SuppressLint("MissingPermission")
        fun startScan(token: String) {
            scanToken = token
            val a = adapter ?: return
            if (!a.isEnabled) return
            scanner = a.bluetoothLeScanner
            scanCb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val d = result.device; devices[d.address] = d
                    val name = try { d.name ?: result.scanRecord?.deviceName ?: "" } catch (e: SecurityException) { "" }
                    scanToken?.let { t -> runOnUiThread { resolve(t, "${d.address}\t$name\t${result.rssi}") } }
                }
            }
            scanner?.startScan(scanCb)
        }
        @SuppressLint("MissingPermission")
        fun stopScan() { try { scanner?.stopScan(scanCb) } catch (e: Exception) {}; scanToken = null }

        @SuppressLint("MissingPermission")
        fun connect(addr: String, ok: (String) -> Unit, err: (String) -> Unit) {
            val d = devices[addr] ?: try { adapter?.getRemoteDevice(addr) } catch (e: Exception) { null }
            if (d == null) { err("unknown device (scan first)"); return }
            connectOk[addr] = ok; connectErr[addr] = err
            d.connectGatt(this@MainActivity, false, gattCb)
        }
        @SuppressLint("MissingPermission")
        fun disconnect(addr: String) { gatts[addr]?.let { try { it.disconnect(); it.close() } catch (e: Exception) {} }; gatts.remove(addr) }

        private val gattCb = object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                val addr = g.device.address
                if (newState == BluetoothProfile.STATE_CONNECTED) { gatts[addr] = g; g.discoverServices() }
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runOnUiThread { connectErr.remove(addr)?.invoke("disconnected"); connectOk.remove(addr) }
                    gatts.remove(addr); try { g.close() } catch (e: Exception) {}
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val addr = g.device.address
                for (svc in g.services) for (ch in svc.characteristics) chars[key(addr, svc.uuid.toString(), ch.uuid.toString())] = ch
                runOnUiThread { connectOk.remove(addr)?.invoke("connected"); connectErr.remove(addr) }
            }
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                val k = keyFor(g.device.address, ch); val hex = bytesToHex(ch.value)
                runOnUiThread { if (status == BluetoothGatt.GATT_SUCCESS) readOk.remove(k)?.invoke(hex) else readErr.remove(k)?.invoke("read failed ($status)"); readOk.remove(k); readErr.remove(k) }
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                val k = keyFor(g.device.address, ch)
                runOnUiThread { if (status == BluetoothGatt.GATT_SUCCESS) writeOk.remove(k)?.invoke("ok") else writeErr.remove(k)?.invoke("write failed ($status)"); writeOk.remove(k); writeErr.remove(k) }
            }
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                val k = keyFor(g.device.address, ch); val hex = bytesToHex(ch.value)
                runOnUiThread { notifyTokens[k]?.let { resolve(it, hex) } }
            }
        }
        @SuppressLint("MissingPermission")
        fun read(addr: String, s: String, c: String, ok: (String) -> Unit, err: (String) -> Unit) {
            val g = gatts[addr]; val ch = chars[key(addr, s, c)]
            if (g == null || ch == null) { err("characteristic not found"); return }
            readOk[key(addr, s, c)] = ok; readErr[key(addr, s, c)] = err
            g.readCharacteristic(ch)
        }
        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        fun write(addr: String, s: String, c: String, hex: String, ok: (String) -> Unit, err: (String) -> Unit) {
            val g = gatts[addr]; val ch = chars[key(addr, s, c)]
            if (g == null || ch == null) { err("characteristic not found"); return }
            writeOk[key(addr, s, c)] = ok; writeErr[key(addr, s, c)] = err
            ch.value = hexToBytes(hex)
            g.writeCharacteristic(ch)
        }
        @SuppressLint("MissingPermission")
        fun subscribe(addr: String, s: String, c: String, token: String, err: (String) -> Unit) {
            val g = gatts[addr]; val ch = chars[key(addr, s, c)]
            if (g == null || ch == null) { err("characteristic not found"); return }
            notifyTokens[key(addr, s, c)] = token
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (cccd != null) { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(cccd) }
        }
        fun unsubscribe(token: String) { notifyTokens.filterValues { it == token }.keys.toList().forEach { notifyTokens.remove(it) } }
    }

    // NFC (NDEF) via NfcAdapter reader mode. Needs the "nfc" permission + NFC hardware.
    inner class NfcReader {
        private val adapter = NfcAdapter.getDefaultAdapter(this@MainActivity)
        private var token: String? = null
        private var writeText: String? = null
        fun available(): Boolean = adapter != null && adapter.isEnabled
        fun read(tok: String, err: (String) -> Unit) { if (adapter == null) { err("NFC not available"); return }; token = tok; writeText = null; enable() }
        fun write(text: String, tok: String, err: (String) -> Unit) { if (adapter == null) { err("NFC not available"); return }; token = tok; writeText = text; enable() }
        private fun enable() {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
            adapter?.enableReaderMode(this@MainActivity, { tag -> onTag(tag) }, flags, null)
        }
        private fun onTag(tag: Tag) {
            val tok = token ?: return
            val ndef = Ndef.get(tag)
            if (ndef == null) { runOnUiThread { fail(tok, "tag is not NDEF") }; disable(); return }
            try {
                ndef.connect()
                if (writeText != null) {
                    ndef.writeNdefMessage(NdefMessage(arrayOf(NdefRecord.createTextRecord("en", writeText))))
                    runOnUiThread { resolve(tok, "ok") }
                } else {
                    val msg = ndef.ndefMessage
                    val text = msg?.records?.firstOrNull()?.let { textFromRecord(it) } ?: ""
                    runOnUiThread { resolve(tok, text) }
                }
                ndef.close()
            } catch (e: Exception) { runOnUiThread { fail(tok, e.message ?: "nfc failed") } }
            finally { disable() }
        }
        private fun disable() { token = null; writeText = null; try { adapter?.disableReaderMode(this@MainActivity) } catch (e: Exception) {} }
        private fun textFromRecord(r: NdefRecord): String {
            val p = r.payload
            if (p.isEmpty()) return ""
            val langLen = p[0].toInt() and 0x3f
            return try { String(p, 1 + langLen, p.size - 1 - langLen, Charsets.UTF_8) } catch (e: Exception) { String(p, Charsets.UTF_8) }
        }
    }

    private fun ensureBle(): BleManager { val b = bleManager ?: BleManager().also { bleManager = it }; return b }
    private fun ensureNfc(): NfcReader { val n = nfcReader ?: NfcReader().also { nfcReader = it }; return n }

    private fun remove(id: String) {
        views[id]?.let { (it.parent as? ViewGroup)?.removeView(it) }
        ynodes[id]?.let { textNodes.remove(it); val o = N.yOwner(it); if (o != 0L) N.yRemove(o, it); N.yFree(it) }
        val prefix = "$id."
        videoPlayers.keys.filter { it == id || it.startsWith(prefix) }.toList().forEach { k -> poolVideo(k) }
        videoWanted.keys.filter { it == id || it.startsWith(prefix) }.toList().forEach { videoWanted.remove(it); videoPlayPref.remove(it); videoMutePref.remove(it); videoLoopPref.remove(it) }
        if (cameraIds.any { it == id || it.startsWith(prefix) }) { cameraController?.close(); cameraController = null }
        views.keys.filter { it == id || it.startsWith(prefix) }.forEach { views.remove(it); cameraIds.remove(it); bgColor.remove(it); bgRadius.remove(it); borderW.remove(it); borderC.remove(it); pressOpacity.remove(it); sliderMin.remove(it); sliderStep.remove(it); sliderDone.remove(it); switchThumb.remove(it); explicitHeight.remove(it); scrollOnScroll.remove(it); scrollLastPos.remove(it); selectIds.remove(it); selectOptions.remove(it); selectSel.remove(it); datePickerIds.remove(it); datePickerModes.remove(it); datePickerVals.remove(it); menuIds.remove(it); menuData.remove(it); contextMenuIds.remove(it); contextMenuData.remove(it); mapIds.remove(it); gestureIds.remove(it); gestureCont.remove(it); alertIds.remove(it); alertData.remove(it); alertActions.remove(it); bgImageViews.remove(it); imageSrc.remove(it); imageDecodedDim.remove(it) }
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
                    // if released inside (TouchableOpacity). Instant, no round-trip. Also
                    // dispatches onPressIn/onPressOut and onLongPress (after a hold).
                    v.isClickable = true
                    val longRunnable = arrayOfNulls<Runnable>(1)
                    val longFired = booleanArrayOf(false)
                    v.setOnTouchListener { view, ev ->
                        if (disabledIds.contains(id)) return@setOnTouchListener true   // disabled: swallow, no fire
                        when (ev.action) {
                            MotionEvent.ACTION_DOWN -> {
                                view.animate().alpha(ao).setDuration(90).start()
                                pressInActions[id]?.let { fire(it) }
                                longPressActions[id]?.let { lp ->
                                    longFired[0] = false
                                    val r = Runnable { longFired[0] = true; fire(lp) }
                                    longRunnable[0] = r; view.postDelayed(r, longDelayMs[id] ?: 500L)
                                }
                                true
                            }
                            MotionEvent.ACTION_UP -> {
                                view.animate().alpha(1f).setDuration(90).start()
                                longRunnable[0]?.let { view.removeCallbacks(it) }
                                pressOutActions[id]?.let { fire(it) }
                                if (!longFired[0] && ev.x >= 0 && ev.y >= 0 && ev.x <= view.width && ev.y <= view.height) fire(action)
                                true
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                view.animate().alpha(1f).setDuration(90).start()
                                longRunnable[0]?.let { view.removeCallbacks(it) }
                                pressOutActions[id]?.let { fire(it) }
                                true
                            }
                            else -> false
                        }
                    }
                } else { v.isClickable = true; v.setOnClickListener { if (!disabledIds.contains(id)) fire(action) } }
            }
        }
    }

    private fun fire(action: String) {
        if (action.isEmpty()) return
        hostEvent(action)
    }

    // Hardware / gesture back: if a Modal is open, dismiss it (fire its onDismiss) and
    // consume the press, matching iOS's expectation that back closes the top sheet
    // first. Only when no Modal is showing does back fall through to the default
    // (finish the activity).
    override fun onBackPressed() {
        val mid = activeModal
        if (mid != null && views[mid]?.visibility == View.VISIBLE) {
            modalActions[mid]?.let { fire(it) }   // parent flips `visible` false; the re-render hides it
            return
        }
        super.onBackPressed()
    }

    // Present a native AlertDialog for an Alert node. Encoding:
    // title, message, promptFlag, placeholder, promptValue, buttonsPipe.
    private fun presentAlert(id: String) {
        if (presentedAlertId == id) return
        val f = alertData[id] ?: return
        val title = f.getOrNull(0) ?: ""; val msg = f.getOrNull(1) ?: ""
        val isPrompt = (f.getOrNull(2) ?: "0") == "1"
        val placeholder = f.getOrNull(3) ?: ""; val promptValue = f.getOrNull(4) ?: ""
        // Fields from index 5 on are the buttons; "!"=destructive, "~"=cancel style.
        val labels = if (f.size > 5) f.subList(5, f.size) else listOf("OK")
        val cleaned = labels.map { when { it.startsWith("!") || it.startsWith("~") -> it.substring(1); else -> it } }
        val destructive = labels.map { it.startsWith("!") }
        val cancelIdx = labels.indexOfFirst { it.startsWith("~") }   // -1 if none
        val b = android.app.AlertDialog.Builder(this)
        if (title.isNotEmpty()) b.setTitle(title)
        if (msg.isNotEmpty() && !isPrompt) b.setMessage(msg)

        // Prompt: an EditText inside a padded container (also carries the message above it).
        var promptField: android.widget.EditText? = null
        if (isPrompt) {
            val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL
            box.setPadding(dp(20), dp(8), dp(20), 0)
            if (msg.isNotEmpty()) box.addView(TextView(this).also { it.text = msg; it.setPadding(0, 0, 0, dp(8)) })
            val et = android.widget.EditText(this); et.hint = placeholder; et.setText(promptValue)
            box.addView(et); promptField = et; b.setView(box)
        }

        fun report(which: Int) {
            presentedAlertId = null
            val text = promptField?.text?.toString() ?: ""
            alertDispatch(id, if (isPrompt) "$which\t$text" else "$which")
        }

        if (cleaned.size > 3 && !isPrompt) {
            // More buttons than the 3 native slots: a selectable list reports its index.
            b.setItems(cleaned.toTypedArray()) { _, which -> report(which) }
        } else {
            // Map tap-index -> native slots. 1: positive[0]. 2: negative[0],positive[1].
            // 3: negative[0],neutral[1],positive[2]. Index in the callback is the tap-index.
            when (cleaned.size) {
                1 -> b.setPositiveButton(cleaned[0]) { _, _ -> report(0) }
                2 -> { b.setNegativeButton(cleaned[0]) { _, _ -> report(0) }
                       b.setPositiveButton(cleaned[1]) { _, _ -> report(1) } }
                else -> { b.setNegativeButton(cleaned[0]) { _, _ -> report(0) }
                          b.setNeutralButton(cleaned[1]) { _, _ -> report(1) }
                          b.setPositiveButton(cleaned[2]) { _, _ -> report(2) } }
            }
        }
        if (cancelIdx >= 0) b.setOnCancelListener { report(cancelIdx) }   // back / tap-outside = the cancel button
        else b.setCancelable(false)

        val d = b.create()
        presentedAlertId = id; presentedAlertDialog = d
        d.show()
        // Redden destructive buttons after show() (AlertDialog has no destructive style).
        // Map each tap-index to its native slot for the 1..3-button layouts.
        if (cleaned.size <= 3 || isPrompt) {
            val red = Color.parseColor("#DC2626")
            for (i in cleaned.indices) {
                if (!destructive.getOrElse(i) { false }) continue
                val slot = when {
                    cleaned.size == 1 -> android.app.AlertDialog.BUTTON_POSITIVE
                    cleaned.size == 2 -> if (i == 0) android.app.AlertDialog.BUTTON_NEGATIVE else android.app.AlertDialog.BUTTON_POSITIVE
                    else -> if (i == 0) android.app.AlertDialog.BUTTON_NEGATIVE else if (i == 1) android.app.AlertDialog.BUTTON_NEUTRAL else android.app.AlertDialog.BUTTON_POSITIVE
                }
                d.getButton(slot)?.setTextColor(red)
            }
        }
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
    // Keep the on-disk image cache bounded (oldest-first eviction) so it can't grow forever.
    private fun trimImgDir() {
        try {
            val files = imgDir.listFiles() ?: return
            var total = files.sumOf { it.length() }
            val cap = 100L * 1024 * 1024   // 100MB
            if (total <= cap) return
            for (fl in files.sortedBy { it.lastModified() }) {
                if (total <= cap) break
                total -= fl.length(); fl.delete()
            }
        } catch (e: Exception) {}
    }
    // blurRadius: a cheap, all-versions Gaussian-ish blur (downscale then bilinear upscale),
    // so it works below API 31 where RenderEffect isn't available.
    private fun blurBitmap(src: android.graphics.Bitmap, radius: Float): android.graphics.Bitmap {
        if (radius <= 0f) return src
        // Downscale hard then bilinear-upscale to approximate a Gaussian; two passes smooth out
        // the blockiness and deepen the blur so it reads like iOS's CIGaussianBlur.
        var out = src
        for (i in 0 until 3) {
            val scale = radius.coerceIn(2f, 30f)
            val w = (src.width / scale).toInt().coerceAtLeast(1)
            val h = (src.height / scale).toInt().coerceAtLeast(1)
            val small = android.graphics.Bitmap.createScaledBitmap(out, w, h, true)
            out = android.graphics.Bitmap.createScaledBitmap(small, src.width, src.height, true)
        }
        return out
    }
    private fun bmpFor(bmp: android.graphics.Bitmap, id: String): android.graphics.Bitmap {
        val r = imageBlur[id] ?: 0f
        val capped = capBitmap(bmp)
        val base = if (r > 0f) blurBitmap(capped, r) else capped
        imageOrigBmp[id] = base
        return runOps(id, base)
    }

    // A hardware canvas refuses to draw a bitmap over ~100MB, so a high-res source
    // image (e.g. a 5000px onboarding photo) crashes at draw time. Decode local
    // assets downsampled (decodeScaled), and cap anything that still arrives large
    // here so every setImageBitmap path is safe. MAX_DIM keeps a bitmap near 26MB.
    private val MAX_DIM = 2560
    private fun capBitmap(b: android.graphics.Bitmap, maxDim: Int = MAX_DIM): android.graphics.Bitmap {
        val w = b.width; val h = b.height
        if (w <= maxDim && h <= maxDim) return b
        val scale = maxDim.toFloat() / maxOf(w, h)
        val nb = android.graphics.Bitmap.createScaledBitmap(b, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
        if (nb !== b) b.recycle()
        return nb
    }
    // Two-pass decode: read bounds, pick an inSampleSize so neither side exceeds
    // maxDim, then decode at that sample. Avoids ever allocating the full-res bitmap.
    private fun decodeScaled(bytes: ByteArray, maxDim: Int = MAX_DIM): android.graphics.Bitmap? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / s > maxDim || o.outHeight / s > maxDim) s *= 2
        val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = s }
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
    }

    // RN/Fresco parity: (re)decode a LOCAL image downsampled to the view's laid-out
    // pixel size instead of the source resolution. Called provisionally at screen
    // width on load, then refined from the real frame in relayout(). Re-reads from
    // the source each time (bundled assets/files are cheap); a power-of-two bucket
    // skips the common case where a relayout did not cross a resolution boundary.
    private fun ensureSizedImage(id: String, targetPx: Int) {
        val src = imageSrc[id] ?: return
        val iv = (views[id] as? ImageView) ?: bgImageViews[id] ?: return
        val target = targetPx.coerceIn(1, MAX_DIM)
        val bucket = Integer.highestOneBit(target)
        if (imageDecodedDim[id] == bucket) return
        val bytes = try {
            if (src.startsWith("file://")) java.io.File(src.substring(7)).readBytes()
            else assets.open(src).use { it.readBytes() }
        } catch (e: Exception) { return }
        decodeScaled(bytes, target)?.let { iv.setImageBitmap(bmpFor(it, id)); imageDecodedDim[id] = bucket }
    }
    // Run the Image GPU op-chain (effects.chain) on the GPU, if set (else the base bitmap).
    private fun runOps(id: String, bmp: android.graphics.Bitmap): android.graphics.Bitmap {
        val j = imageOpChain[id]
        return if (j.isNullOrEmpty()) bmp else try { ChuksEffects.run(bmp, j) } catch (e: Exception) { bmp }
    }

    private fun loadRemoteImage(url: String, iv: ImageView, id: String = "") {
        // Feed-grade: memory LRU -> disk cache -> network, decoded off the main thread, with
        // tag cancellation so a recycled cell never gets a late image for a URL it dropped.
        imageMem.get(url)?.let { iv.setImageBitmap(bmpFor(it, id)); mediaLoad[id]?.let { a -> fire(a) }; return }
        iv.setTag(TAG, url)
        // RN parity: downsample to the view's display size (captured here on the main
        // thread), else screen width if the view is not laid out yet.
        val target = (if (iv.width > 0 || iv.height > 0) maxOf(iv.width, iv.height) else root.width).coerceIn(1, MAX_DIM)
        Thread {
            try {
                val key = Integer.toHexString(url.hashCode())
                val f = java.io.File(imgDir, key)
                var bmp = if (f.exists()) f.readBytes().let { decodeScaled(it, target) } else null
                if (bmp == null) {
                    val bytes = java.net.URL(url).openStream().use { it.readBytes() }
                    bmp = decodeScaled(bytes, target)
                    if (bmp != null) try { f.outputStream().use { os -> bmp!!.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, os) }; trimImgDir() } catch (e: Exception) {}
                }
                if (bmp != null) {
                    imageMem.put(url, bmp)
                    iv.post { if (iv.getTag(TAG) == url) iv.setImageBitmap(bmpFor(bmp!!, id)); mediaLoad[id]?.let { a -> fire(a) } }
                } else iv.post { mediaError[id]?.let { a -> fire(a) } }
            } catch (e: Exception) { iv.post { mediaError[id]?.let { a -> fire(a) } } }
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
            // A horizontal list's content node has an explicit WIDTH but no height (its cells
            // are abs), so Yoga gives it height 0 and Android would clip the cells. Fill it to
            // the scroll's own height (the cross axis); it scrolls sideways only.
            if (listHoriz && id == contentId) { lp.height = listScroll?.height ?: lp.height }
            v.layoutParams = lp
            // RN parity: now that the frame is known, decode the image to its display size.
            if (imageSrc.containsKey(id)) ensureSizedImage(id, maxOf(lp.width, lp.height))
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
        // stickBottom (chat): after layout settles, keep the transcript pinned to the newest
        // message if the user was already at the bottom (new message, or the keyboard shrinking).
        if (stickBottomOn) (listScroll as? ScrollView)?.let { sc -> sc.post {
            val child = if (sc.childCount > 0) sc.getChildAt(0) else null
            val newH = child?.height ?: 0
            val wasAtBottom = sc.scrollY + sc.height >= stickPrevH - dp(20)
            if (wasAtBottom && newH > sc.height) sc.smoothScrollTo(0, newH - sc.height)
            stickPrevH = newH
        } }
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
