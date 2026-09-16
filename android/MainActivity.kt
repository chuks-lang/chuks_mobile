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
import android.view.WindowInsets
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
    external fun remount(): Int   // the full tree again, for a new Activity over an engine that already mounted
    external fun tick(): Int
    external fun viewport(top: Int, h: Int, w: Int): Int
    external fun event(action: String): Int
    external fun back(): Int                                    // system back: 1 = the app handled it
    external fun input(action: String, value: String): Int
    external fun resolve(token: String, payload: String): Int   // F3: async capability result
    external fun fail(token: String, message: String): Int      // F3: capability failure (error channel)
    external fun setColorScheme(dark: Int)
    external fun setTimezone(id: String, offsetSeconds: Int)       // the device zone; Go's own default on Android is UTC
    external fun colorSchemeFollows(): Int
    external fun setInsets(top: Int, right: Int, bottom: Int, left: Int)
    external fun setPlatform(os: String, version: String, model: String, isTablet: Int)
    external fun drain(): String
    external fun saveState(): String                            // route stack + useState cells
    external fun loadState(data: String)
    external fun cmrBoot(bundle: ByteArray, tmpdir: String): Int   // CMR: load a chukspack bundle (libcmr only)
    external fun cmrApplyDelta(delta: ByteArray): Int              // CMR: merge only the changed modules + re-init
    external fun cmrLastError(): String                            // CMR: why the last boot/delta failed (dev error overlay)
    external fun cmrSaveState(): String                            // CMR: serialize app state (cells + nav) before a reload
    external fun cmrLoadState(state: String)                       // CMR: restore it into the fresh VM after a reload
    external fun yNew(): Long
    external fun yInsert(parent: Long, child: Long, idx: Int)
    external fun yRemove(parent: Long, child: Long)
    external fun yOwner(node: Long): Long
    external fun yChildCount(node: Long): Int
    external fun yFree(node: Long)
    external fun yCalc(node: Long, w: Float, h: Float)
    external fun yGet(node: Long, which: Int): Float
    external fun ySetF(node: Long, key: Int, v: Float)
    external fun yResetStyle(node: Long)   // reset LAYOUT style to Yoga defaults (reused-node hygiene; see jni.cpp)
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

// Every colour on the wire, parsed the one way.
//
// Style values carry a bare hex ("2F7A4F") because that is how the theme stores them, and
// every site used to write Color.parseColor("#$vl") on that assumption. An app that wrote
// the colour the way a person writes one, "#2F7A4F", produced "##..." and an uncaught
// NumberFormatException that killed the process, from a style value correct by every
// other measure. Accept both, and never throw: a misspelled colour should not be fatal.
fun hexColorStatic(h: String, fallback: Int = android.graphics.Color.TRANSPARENT): Int {
    val t = h.trim()
    if (t.isEmpty()) return fallback
    return try { android.graphics.Color.parseColor(if (t.startsWith("#")) t else "#$t") }
    catch (e: Throwable) { android.util.Log.w("chuks", "bad colour: \"$h\""); fallback }
}

// Text arriving on the P|/V| channels, restored.
//
// The engine escapes a backslash and the two line endings before putting text into a
// newline-delimited stream, because a newline inside a label used to end the op early and
// leave the rest standing as a line the host would then RUN. See escText in core/ui.chuks.
// One left-to-right scan, because search-and-replace would corrupt a label ending in a
// real backslash.
// A free-text style value (an accessibility label) arrives percent-encoded for the
// six characters that are separators on the way here: %25 %3B %3D %7C %0A %0D %09.
fun chuksUnescapeStyle(s: String): String {
    if (s.indexOf('%') < 0) return s
    return try { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { s }
}
fun chuksUnescapeText(s: String): String {
    if (s.indexOf('\\') < 0) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                '\\' -> out.append('\\')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                else -> out.append(s[i + 1])
            }
            i += 2
            continue
        }
        out.append(c)
        i++
    }
    return out.toString()
}

class MainActivity : Activity(), ChuksModuleHost {
    private val views = HashMap<String, View>()
    private val ynodes = HashMap<String, Long>()
    // Incremental apply: relayout() reassigns a view's LayoutParams (which triggers a child
    // requestLayout) only when its computed frame actually changed vs the last applied one,
    // so an incremental update touches a handful of views instead of the whole tree.
    private val lastFrame = HashMap<String, IntArray>()   // id -> [left, top, width, height] last applied
    private val needsFrame = HashSet<String>()            // just-created views, force-applied once
    private var density = 1f

    private lateinit var root: FrameLayout
    private var listScroll: FrameLayout? = null   // a ScrollView (vertical) or HorizontalScrollView (carousel)
    private val horizScrollIds = HashSet<String>()   // which scroll ids scroll sideways, by id rather than
                                                     // by inference: LV can hand the live slot to any of them
    private var listHoriz = false                 // the tracked list scrolls horizontally (report x, not y)
    private var scrollId = ""
    private var stickBottomOn = false   // Scroll stickBottom: keep pinned to newest (chat)
    private var stickPrevH = 0          // previous content height, to tell if the user was at the bottom
    private val modalIds = HashSet<String>()             // Modal node ids (full-screen overlays)
    private var activeModal: String? = null              // the currently-visible Modal
    private val sheetModals = HashSet<String>()          // Modal ids with position=bottom (draggable sheets)
    private val modalActions = HashMap<String, String>() // Modal id -> onDismiss action
    private val popoverIds = HashSet<String>()           // Popover ids (an anchored Modal)
    private val popoverAnchor = HashMap<String, String>()  // popover id -> anchor node id
    private val popoverPlace = HashMap<String, String>()   // popover id -> auto|top|bottom|left|right
    private val popoverGap = HashMap<String, Int>()        // popover id -> gap to the anchor (px)
    private val popoverArrow = HashSet<String>()           // popovers that draw a pointer
    private val popoverArrowViews = HashMap<String, View>()  // popover id -> its pointer
    private val sheetIds = HashSet<String>()             // Sheet overlays (host-driven bottom sheets)
    private val layoutIds = HashSet<String>()            // ids whose node has onLayout
    private val lastLayoutReport = HashMap<String, String>()  // id -> last "x,y,w,h" reported
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
    // The insets last dispatched to the content view. reportInsets reads these rather
    // than the decor view's rootWindowInsets: after another Activity has been on top
    // (the photo picker, a Custom Tab) the decor view's copy can read as zero while
    // the content view was handed the real values, and a hot reload that re-sent
    // "zero" laid the app out under the status bar and the navigation bar.
    private var dispatchedInsets: WindowInsets? = null
    private fun reportInsets(given: WindowInsets? = null) {
        if (devMode) return   // dev server has no /insets endpoint; uses default insets
        if (given != null) dispatchedInsets = given
        val wi = given ?: dispatchedInsets ?: window.decorView.rootWindowInsets ?: return
        val t: Int; val r: Int; val b: Int; val l: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val sb = wi.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            t = (sb.top / density).toInt(); r = (sb.right / density).toInt(); b = (sb.bottom / density).toInt(); l = (sb.left / density).toInt()
        } else {
            @Suppress("DEPRECATION")
            run { t = (wi.systemWindowInsetTop / density).toInt(); r = (wi.systemWindowInsetRight / density).toInt(); b = (wi.systemWindowInsetBottom / density).toInt(); l = (wi.systemWindowInsetLeft / density).toInt() }
        }
        if (traceStream) android.util.Log.v("ChuksStream", "insets t=$t r=$r b=$b l=$l given=${given != null} kept=${dispatchedInsets != null}")
        if (t != lastInsets[0] || r != lastInsets[1] || b != lastInsets[2] || l != lastInsets[3]) {
            lastInsets = intArrayOf(t, r, b, l)
            N.setInsets(t, r, b, l); N.tick(); applyDrain(); relayout()
        }
    }

    private var zoneReceiver: android.content.BroadcastReceiver? = null

    override fun onDestroy() {
        zoneReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        zoneReceiver = null
        super.onDestroy()
    }

    // Hand the engine the device's time zone: its IANA id (Europe/London), and the
    // offset now as the fallback for an id the engine's zone database lacks.
    private fun pushTimezone() {
        val tz = java.util.TimeZone.getDefault()
        N.setTimezone(tz.id, tz.getOffset(System.currentTimeMillis()) / 1000)
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
        if (appAppearance == "auto") repaintDefaultText()   // following the OS: the default moved with it
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    // Tap outside a focused text field dismisses the keyboard. Android does not do this
    // on its own (unlike iOS): a DOWN outside the focused EditText clears focus + hides IME.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            (currentFocus as? EditText)?.let { ed ->
                val r = android.graphics.Rect(); ed.getGlobalVisibleRect(r)
                if (!r.contains(ev.rawX.toInt(), ev.rawY.toInt())) { hideKeyboard(ed); ed.clearFocus() }
            }
        }
        warmFrames()
        return super.dispatchTouchEvent(ev)
    }

    // Keep frames flowing while a finger is on the screen and for a short tail after it
    // lifts. This app draws only when something moves, so between two swipes the panel
    // drops to its idle rate and the GPU clocks down, and the first frame of the next
    // swipe pays ~10ms to wake them: the one frame per gesture a finger still felt.
    // A frame a beat costs 1-2ms of GPU while it runs, nothing once the tail ends.
    private var warmUntil = 0L
    private var warmArmed = false
    private val warmTick = object : Runnable {
        override fun run() {
            if (System.nanoTime() >= warmUntil) { warmArmed = false; return }
            root.invalidate()
            root.postOnAnimation(this)
        }
    }
    private fun warmFrames() {
        warmUntil = System.nanoTime() + 700_000_000L
        if (!warmArmed) { warmArmed = true; root.postOnAnimation(warmTick) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pipeStdioToLog()
        density = resources.displayMetrics.density
        // Keyboard avoidance. adjustResize shrinks the WHOLE window, which lifts every
        // bottom-anchored thing -- typing in a field at the top of the screen dragged the
        // tab bar up over the keyboard. Let the keyboard overlay instead and lift only
        // what it actually covers (see applyKeyboard). Needs ime() insets to know the
        // keyboard's height, which is API 30+; older devices keep the resize behaviour,
        // where the platform gives no reliable height with ADJUST_NOTHING.
        window.setSoftInputMode(
            if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0E1116"))
        setContentView(root)
        // Edge-to-edge: draw the app under the status + nav bars (transparent) and let the
        // reported insets (safeTop/safeBottom) pad the content exactly once, like iOS.
        // Without this the OS insets the window above an opaque nav bar AND the app pads
        // by safeBottom -> a doubled gap under a bottom bar. The system bar icons stay
        // visible over the app; a tab bar's own bg now reaches the true screen edge.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        run {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        // Re-layout when the root's own size changes (keyboard show/hide, rotation): the
        // Chuks tree is laid out to root.height, so the shrink reflows content above the
        // keyboard. Child layout changes don't resize root, so this never loops.
        root.addOnLayoutChangeListener { _, l, t, r, bo, ol, ot, or2, ob ->
            if ((bo - t) != (ob - ot) || (r - l) != (or2 - ol)) { relayout(); pushViewport() }
        }
        intent?.data?.let { lastUrl = it.toString() }   // deep link that launched the app
        ChuksNotif.deliverTap(intent)                   // a notification tap that launched the app
        ChuksNotif.rearmAll(this)                       // alarms do not survive a reboot; the store does

        // DEV hot reload: assets/chuks-dev.txt (written by a DEV=1 build) points at the
        // running dev server. Present => fetch the UI over HTTP instead of the JNI engine.
        try { assets.open("chuks-dev.txt").bufferedReader().use { devBase = "http://" + it.readText().trim() } } catch (e: Exception) {}
        // Chuks Preview: a server chosen at runtime on the connect screen wins over any
        // bundled one, so one generic host can point at any `chuks dev`.
        getSharedPreferences("chuks.preview", MODE_PRIVATE).getString("host", "")?.let {
            if (it.isNotEmpty()) devBase = "http://$it"
        }

        // The engine's clock zone. Go leaves time.Local at UTC on Android, so without
        // this every time.format / date.today in the app is off by the device's offset.
        // It has to land before the engine boots (module-level code may read the date)
        // and again whenever the user or the network moves the device to another zone.
        if (!devMode) {
            pushTimezone()
            zoneReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { pushTimezone(); N.tick(); applyDrain(); relayout() }
            }
            registerReceiver(zoneReceiver, android.content.IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
        }

        // CMR boot. Two sources for the source bundle the on-device VM (libcmr) runs:
        //   1. cmr-dev.txt present  -> fetch it over HTTP from `chukspack serve` and
        //      hot-reload on change (Metro/Hermes model: the VM runs HERE, only the
        //      SOURCE is fetched, unlike the retired chuks-dev.txt mutation-stream flow).
        //   2. else                 -> the baked assets/cmr.bundle (standalone CMR).
        val cmrDevHost = try { assets.open("cmr-dev.txt").bufferedReader().use { it.readText().trim() } } catch (e: Exception) { "" }
        if (cmrDevHost.isNotEmpty()) {
            cmrDevBase = "http://$cmrDevHost"
            var b: ByteArray? = null; var v = 0
            val t = Thread { val r = cmrFetchBundle(); b = r.first; v = r.second }; t.start(); t.join(8000)
            if (b == null) try { b = assets.open("cmr.bundle").readBytes() } catch (e: Exception) {}   // fallback if server down
            if (b != null) { val rc = N.cmrBoot(b!!, cacheDir.absolutePath); cmrVersion = v; android.util.Log.i("CMR", "dev boot rc=$rc v=$v (${b!!.size} bytes) from $cmrDevBase"); if (rc != 0) showDevError(N.cmrLastError()) else dismissDevError() }
            startCmrHmr()
        } else {
            try {
                val bundle = assets.open("cmr.bundle").readBytes()
                val rc = N.cmrBoot(bundle, cacheDir.absolutePath)   // cacheDir => $TMPDIR for on-device compile
                android.util.Log.i("CMR", "booted rc=$rc (${bundle.size} bytes)")
                if (rc != 0) showDevError(N.cmrLastError()) else dismissDevError()
            } catch (_: Throwable) { /* no cmr.bundle, or cmrBoot native absent (AOT) */ }
        }

        // Android keeps a process after its Activity is finished (Back at the root) and
        // creates a new Activity in it on the next launch. The engine lives in the process:
        // it has already set up, mounted and holds the app's live state, so this Activity
        // asks it for the whole tree again (a mount would diff against the tree the engine
        // believes is on screen, emit nothing, and leave the new window black) and does
        // not restore a snapshot over state the engine still has.
        val warm = !devMode && engineMounted
        if (!devMode && !warm) {
            N.setup(1000)
            val isTablet = if (resources.configuration.smallestScreenWidthDp >= 600) 1 else 0
            N.setPlatform("android", android.os.Build.VERSION.RELEASE, android.os.Build.MODEL, isTablet)   // platform + device info
            N.setColorScheme(if (osDark()) 1 else 0)   // open in the OS appearance
        }
        if (warm) {
            try { runMarker.writeText("1") } catch (e: Exception) {}     // arm crash detection for this run too
            applyStream(N.remount().let { N.drain() }); relayout()
        } else {
            // BEFORE the first mount: loadState replaces the route stack and the useState
            // cells, so it has to land while there is still nothing on screen.
            restoreStateIfAppropriate()
            hostMount()
        }
        if (!devMode) engineMounted = true

        // first layout after the window is measured
        root.post { reportInsets(); relayout(); if (pushViewport()) relayout() }
        // Predictive back: ride the system's own gesture progress so the pop is
        // interactive and cancellable, instead of happening all at once on release.
        if (Build.VERSION.SDK_INT >= 34) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                object : android.window.OnBackAnimationCallback {
                    override fun onBackStarted(e: android.window.BackEvent) {
                        val mid = activeModal
                        if (mid != null && views[mid]?.visibility == View.VISIBLE) return
                        if (backLayers()) backProgress(0f)
                    }
                    override fun onBackProgressed(e: android.window.BackEvent) { backProgress(e.progress) }
                    override fun onBackCancelled() { backSettle(false) }
                    override fun onBackInvoked() {
                        val mid = activeModal
                        if (mid != null && views[mid]?.visibility == View.VISIBLE) {
                            modalActions[mid]?.let { fire(it) }   // parent flips `visible`; the re-render hides it
                            return
                        }
                        if (closeTopSheet()) return
                        if (backTop != null) backSettle(true) else doBack()
                    }
                })
        }
        root.setOnApplyWindowInsetsListener { _, insets ->                            // update on inset changes
            reportInsets(insets)
            if (Build.VERSION.SDK_INT >= 30) applyKeyboard(insets.getInsets(WindowInsets.Type.ime()).bottom)
            insets
        }

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
                    N.tick(); val st = N.drain(); if (st.isNotEmpty()) { applyStream(st); relayout() }
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

    // `adb shell setprop log.tag.ChuksStream VERBOSE` prints every mutation the host applies
    // and every event it sends, for reading a feedback loop between a widget and the engine.
    private val traceStream = android.util.Log.isLoggable("ChuksStream", android.util.Log.VERBOSE)
    private fun applyStream(stream: String) {
        if (stream.isEmpty()) return
        for (raw in stream.split("\n")) {
            if (traceStream) android.util.Log.v("ChuksStream", "<- " + raw.take(160))
            val f = raw.split("|")
            when (f.getOrNull(0)) {
                "C" -> if (f.size >= 3) make(f[1], f[2])
                "S" -> if (f.size >= 3) style(f[1], f[2])
                "P" -> if (f.size >= 3) setText(f[1], chuksUnescapeText(f.drop(2).joinToString("|")))   // rejoin: text may contain '|'
                "V" -> if (f.size >= 3) setFieldValue(f[1], chuksUnescapeText(f.drop(2).joinToString("|")))   // controlled value (may contain '|')
                "T" -> if (f.size >= 3) bindAction(f[1], f[2])
                "TC" -> if (f.size >= 2) bindChange(f[1])   // the node's value event: "<id>:change"
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
                "LY" -> if (f.size >= 3) {                                                // onLayout bound (1) or dropped (0)
                    if (f[2] == "1") { layoutIds.add(f[1]); lastLayoutReport.remove(f[1]); needsFrame.add(f[1]) } else layoutIds.remove(f[1]) }
                "IF" -> if (f.size >= 3) {                                                // Image GPU op-chain (JSON; rejoin '|')
                    imageOpChain[f[1]] = f.drop(2).joinToString("|")
                    imageOrigBmp[f[1]]?.let { b -> (views[f[1]] as? ImageView)?.setImageBitmap(runOps(f[1], b)) }
                }
                "MP" -> if (f.size >= 2) { mediaProgress[f[1]] = f[1] + ":progress"; startProgressPoll(f[1]) }   // Video onProgress
                "LS" -> if (f.size >= 3) scrollListTo(f[1], f[2].toIntOrNull() ?: 0)   // scrollToIndex/scrollToEnd
                // VC|<id>|<name>|<json>: the app told ONE package view to do something.
                // The arguments are JSON, so a pipe inside them is safe: everything after
                // the third field is joined back together, as a capability's args are.
                "VC" -> if (f.size >= 3) {
                    val raw = if (f.size >= 4) f.drop(3).joinToString("|") else ""
                    val pv = packageViews[f[1]]
                    val sl = views[f[1]] as? SheetLayout
                    if (pv != null) pv.command(f[2], ChuksArgs(raw, "view." + f[2], "0", this))
                    else if (sl != null) {
                        val a = ChuksArgs(raw, "sheet." + f[2], "0", this)
                        when (f[2]) {
                            "snapTo" -> sheetAnimate(f[1], sl, a.int("index") ?: 0)
                            "expand" -> sheetAnimate(f[1], sl, sl.heights.size - 1)
                            "collapse" -> sheetAnimate(f[1], sl, 0)
                            "close" -> sheetAnimate(f[1], sl, -1)
                        }
                    }
                }
                "I" -> if (f.size >= 4) insert(f[1], f[2], f[3].toIntOrNull() ?: 0)
                "R" -> if (f.size >= 2) remove(f[1])
                // LV|<id>: the engine names the LIVE list (the one on the top screen).
                // With several screens mounted, "most recently created scroll" is wrong:
                // a covered screen's list would take the viewport reports and scroll.
                // Which container holds the two screens predictive back drags.
                "SK" -> { stackHostId = if (f.size >= 2) f[1] else "" }
                "LV" -> {
                    val lid = if (f.size >= 2) f[1] else ""
                    if (lid.isEmpty()) { listScroll = null; scrollId = ""; contentId = "" }
                    // listHoriz MUST move with the live scroll. It used to be set only at
                    // creation, so once LV handed the slot to a horizontal list (a Carousel
                    // inside a vertical Scroll) the host kept reporting the vertical axis: it
                    // sent that list's own HEIGHT as the app viewport, and the engine windowed
                    // every list against a 96dp-tall screen.
                    else (views[lid] as? FrameLayout)?.let { sc ->
                        listScroll = sc; scrollId = lid; contentId = "$lid.0"
                        listHoriz = horizScrollIds.contains(lid)
                    }
                }
                "FA" -> if (f.size >= 2) setFrameDriver(f[1] == "1")   // per-frame physics on/off
                // A runtime error the live app hit (errPayload JSON, which may contain '|'):
                // a handler that threw, a task that failed with nobody awaiting it.
                "E" -> if (f.size >= 2) showDevError(f.drop(1).joinToString("|"))
                "MV", "MS", "MX" -> motionOp(f)                          // shared values (docs/shared-values.md)
                "MK" -> if (f.size >= 2) f[1].toIntOrNull()?.let { mKeyboardValues.add(it) }
                "X" -> if (f.size >= 3) {
                    // Async host->engine command: X|token|capability|args. Run AFTER this
                    // applyDrain() (main-looper post), so a sync capability's resolve()
                    // doesn't re-enter applyDrain(). args may contain '|'.
                    val token = f[1]; val cap = f[2]
                    // The arguments are JSON, so a raw pipe inside them is safe here:
                    // they are everything after the third one, joined back together.
                    val raw = if (f.size >= 4) f.subList(3, f.size).joinToString("|") else ""
                    Handler(Looper.getMainLooper()).post {
                        // Parsed once, centrally, rather than by each capability. `args`
                        // is the single argument as a string, which is all a one-argument
                        // capability ever wanted; `a` reads the rest by name.
                        val a = ChuksArgs(raw, cap, token, this)
                        handleCommand(token, cap, a.str, a)
                    }
                }
            }
        }
    }

    // ================= Dev error overlay ====================================
    // A boot / hot-reload that fails to compile (type error, parse error) or crashes
    // at runtime shows this instead of a blank screen: a dark card with a red header
    // naming the error class and the exact message + file:line underneath. The reason
    // comes from the VM via cmrLastError(). Dismissed automatically on the next clean
    // reload. Dev-only surface; a shipped app never carries an unfixed error.
    private var devErrorOverlay: android.view.View? = null
    // Renders the structured JSON payload from cmrLastError as a full-screen dev error
    // screen: an error-class badge, the message, the developer's relative file:line, a
    // source code frame with the offending line highlighted, and a clean call stack.
    // Falls back to plain text if the payload is not JSON.
    private fun showDevError(message: String) {
        runOnUiThread {
            dismissDevError()
            val dm = resources.displayMetrics
            fun dp(v: Int) = (v * dm.density).toInt()

            // --- parse payload (json) ---
            var cls = "Error"; var msg = message; var file = ""; var line = 0; var amber = false; var live = false
            val frame = ArrayList<Triple<Int, String, Boolean>>()   // n, text, hot
            val stack = ArrayList<Pair<String, Int>>()              // file, line
            try {
                val o = org.json.JSONObject(message)
                cls = o.optString("class", "Error"); msg = o.optString("message", "")
                file = o.optString("file", ""); line = o.optInt("line", 0)
                amber = cls.startsWith("Type", true)
                live = o.optBoolean("live", false)   // the app is still running behind this card
                o.optJSONArray("frame")?.let { a -> for (i in 0 until a.length()) { val f = a.getJSONObject(i); frame.add(Triple(f.optInt("n"), f.optString("t"), f.optBoolean("hot"))) } }
                o.optJSONArray("stack")?.let { a -> for (i in 0 until a.length()) { val s = a.getJSONObject(i); stack.add(Pair(s.optString("file"), s.optInt("line"))) } }
            } catch (e: Throwable) { cls = "Error"; msg = message }

            val red = 0xFFF0616D.toInt(); val redDeep = 0xFFE5484D.toInt(); val amberC = 0xFFF5A524.toInt()
            val txt = 0xFFE9EDF3.toInt(); val muted = 0xFF98A2B3.toInt(); val dim = 0xFF69727F.toInt()
            val link = 0xFF4DD08A.toInt(); val gutter = 0xFF4A5464.toInt()
            val cardBg = 0xFF151A22.toInt(); val frameBg = 0xFF11151C.toInt(); val lineC = 0xFF232A35.toInt()
            val mono = android.graphics.Typeface.MONOSPACE

            val scrim = android.widget.FrameLayout(this)
            scrim.setBackgroundColor(0xFF0D1016.toInt())
            scrim.isClickable = true

            val scroll = android.widget.ScrollView(this)
            scroll.isFillViewport = true
            val col = android.widget.LinearLayout(this)
            col.orientation = android.widget.LinearLayout.VERTICAL
            val top = dp(56)
            col.setPadding(dp(22), top, dp(22), dp(28))

            fun tv(text: String, size: Float, color: Int, bold: Boolean = false, monospace: Boolean = false): android.widget.TextView {
                val t = android.widget.TextView(this); t.text = text; t.textSize = size; t.setTextColor(color)
                if (monospace) t.typeface = mono else if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
                return t
            }

            // badge
            val badge = tv("⚠  " + cls.uppercase(), 11.5f, 0xFFFFFFFF.toInt(), bold = true)
            badge.letterSpacing = 0.06f
            val badgeBg = android.graphics.drawable.GradientDrawable()
            badgeBg.setColor(if (amber) amberC else red); badgeBg.cornerRadius = dp(999).toFloat()
            badge.background = badgeBg; badge.setPadding(dp(11), dp(6), dp(12), dp(6))
            val bl = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            col.addView(badge, bl)

            // message
            val m = tv(if (msg.isBlank()) "Unknown error" else msg, 22f, txt, bold = true)
            m.setPadding(0, dp(16), 0, dp(4)); m.setLineSpacing(0f, 1.05f)
            col.addView(m)

            // location
            if (file.isNotEmpty()) {
                val loc = tv("$file:$line", 13.5f, link, monospace = true)
                loc.setPadding(0, dp(2), 0, dp(14))
                col.addView(loc)
            }

            // code frame: a fixed line-number gutter + a HORIZONTALLY scrollable code
            // column, so a long line can be scrolled to instead of being truncated.
            if (frame.isNotEmpty()) {
                val rowH = dp(24); val padV = dp(12); val gutterW = dp(40)
                val contentPx = dm.widthPixels - dp(22) * 2       // col has 22dp side padding
                val viewportW = contentPx - gutterW - dp(2)       // min width so the hot bar fills the frame
                val fbox = android.widget.LinearLayout(this)
                fbox.orientation = android.widget.LinearLayout.HORIZONTAL
                val fbg = android.graphics.drawable.GradientDrawable()
                fbg.setColor(frameBg); fbg.cornerRadius = dp(14).toFloat(); fbg.setStroke(dp(1), lineC)
                fbox.background = fbg; fbox.clipToOutline = true

                val gutterCol = android.widget.LinearLayout(this)
                gutterCol.orientation = android.widget.LinearLayout.VERTICAL
                gutterCol.setPadding(0, padV, 0, padV)
                val codeCol = android.widget.LinearLayout(this)
                codeCol.orientation = android.widget.LinearLayout.VERTICAL
                codeCol.setPadding(0, padV, 0, padV)
                for ((n, t, hot) in frame) {
                    val g = tv(n.toString(), 12.5f, if (hot) red else gutter, monospace = true)
                    g.height = rowH; g.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                    g.setPadding(0, 0, dp(10), 0)
                    if (hot) g.setBackgroundColor(0x24F0616D)
                    gutterCol.addView(g, android.widget.LinearLayout.LayoutParams(gutterW, rowH))

                    val c = tv(t, 12.5f, if (hot) 0xFFFFFFFF.toInt() else 0xFFC6CFDB.toInt(), monospace = true)
                    c.height = rowH; c.gravity = android.view.Gravity.CENTER_VERTICAL
                    c.setPadding(dp(12), 0, dp(16), 0); c.setSingleLine(true); c.ellipsize = null
                    c.minimumWidth = viewportW                  // hot bar fills the frame even for short lines
                    if (hot) c.setBackgroundColor(0x24F0616D)
                    codeCol.addView(c, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, rowH))
                }
                val hs = android.widget.HorizontalScrollView(this)
                hs.isHorizontalScrollBarEnabled = false; hs.addView(codeCol)
                fbox.addView(gutterCol, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
                fbox.addView(hs, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                col.addView(fbox)
            }

            // call stack
            if (stack.isNotEmpty()) {
                val sh = tv("CALL STACK", 11f, dim, bold = true)
                sh.letterSpacing = 0.12f; sh.setPadding(dp(2), dp(20), 0, dp(8))
                col.addView(sh)
                for ((sf, sl) in stack) {
                    val row = tv("$sf:$sl", 12.5f, link, monospace = true)
                    val rb = android.graphics.drawable.GradientDrawable()
                    rb.setColor(cardBg); rb.cornerRadius = dp(10).toFloat()
                    row.background = rb; row.setPadding(dp(12), dp(10), dp(12), dp(10))
                    val lp = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = dp(3)
                    col.addView(row, lp)
                }
            }

            // footer hint. A live error (a handler that threw, a task that failed with
            // nobody awaiting it) happened in an app that is still up: a tap dismisses the
            // card and the app is there underneath. A failed boot or reload has nothing underneath.
            val hint = tv(if (live) "The app is still running. Tap anywhere to dismiss; the next save reloads."
                          else "Fix the error and save to reload. Hot reload keeps your state.", 12.5f, dim)
            hint.setPadding(0, dp(24), 0, 0)
            col.addView(hint)
            if (live) { scrim.setOnClickListener { dismissDevError() }; col.setOnClickListener { dismissDevError() } }

            scroll.addView(col)
            scrim.addView(scroll, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            root.addView(scrim, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            devErrorOverlay = scrim
        }
    }

    // ---- println reaches the log ------------------------------------------
    //
    // The engine's println goes to file descriptor 1, and on Android nothing reads that:
    // an app can print all day and see nothing in logcat. Every other platform gives you
    // a print statement that shows up somewhere, and a print statement that goes nowhere
    // is worse than none, because it looks like the code did not run.
    //
    // So take the descriptor over. A pipe replaces stdout and stderr, and one daemon
    // thread pumps whole lines into logcat under the "Chuks" tag. This catches the AOT
    // binary, the CMR VM and anything a native package prints, because it works at the
    // descriptor rather than at any one of them.
    //
    // The reader must outlive everything that writes: a pipe whose buffer fills blocks
    // the writer, so a reader that stopped would freeze the app on its next print.
    private var stdioPumped = false
    private fun pipeStdioToLog() {
        if (stdioPumped) return
        stdioPumped = true
        try {
            val fds = android.system.Os.pipe()
            android.system.Os.dup2(fds[1], 1)   // stdout
            android.system.Os.dup2(fds[1], 2)   // stderr, so a panic is not lost either
            val t = Thread {
                try {
                    val r = java.io.BufferedReader(java.io.InputStreamReader(
                        java.io.FileInputStream(fds[0]), Charsets.UTF_8))
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.isNotEmpty()) android.util.Log.i("Chuks", line)
                    }
                } catch (_: Throwable) { }
            }
            t.isDaemon = true
            t.start()
        } catch (_: Throwable) { }   // never let logging stop the app from starting
    }

    private fun dismissDevError() {
        runOnUiThread { devErrorOverlay?.let { root.removeView(it) }; devErrorOverlay = null }
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

    // ================= CMR dev hot reload ===================================
    // A CMR dev build (build-cmr.sh DEV=1) drops assets/cmr-dev.txt = "<host>:<port>"
    // of `chukspack serve`. On boot the host fetches GET /bundle and boots the
    // on-device VM with it; a background thread long-polls GET /hmr?since=N and,
    // when a .chuks file changes, recreate()s the Activity so onCreate re-fetches the
    // new bundle and re-boots. The VM runs on the device; only the SOURCE crosses HTTP.
    private var cmrDevBase = ""         // "http://10.0.2.2:7799"; empty => baked bundle
    private var cmrVersion = 0
    // GET /bundle -> (bytes, X-CMR-Version). Blocking; call off the main thread.
    private fun cmrFetchBundle(): Pair<ByteArray?, Int> {
        return try {
            val c = java.net.URL("$cmrDevBase/bundle").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 3000; c.readTimeout = 8000
            val code = c.responseCode
            // Read the version header BEFORE consuming the body (Go canonicalizes it to
            // "X-Cmr-Version"; match case-insensitively).
            val ver = c.headerFields?.entries?.firstOrNull { it.key?.equals("X-CMR-Version", true) == true }
                ?.value?.firstOrNull()?.trim()?.toIntOrNull() ?: cmrVersion
            val body = if (code == 200) c.inputStream.use { it.readBytes() } else null
            c.disconnect(); Pair(body, ver)
        } catch (e: Exception) { Pair(null, cmrVersion) }
    }
    // GET /hmr?since=N -> new version (!= since), or 0 on 204/timeout/error. Long-poll.
    private fun cmrPollHmr(since: Int): Int {
        return try {
            val c = java.net.URL("$cmrDevBase/hmr?since=$since").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 3000; c.readTimeout = 35000
            val v = if (c.responseCode == 200) c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8).trim().toIntOrNull() ?: 0 else 0
            c.disconnect(); v
        } catch (e: Exception) { 0 }
    }
    private fun startCmrHmr() {
        Thread {
            while (true) {
                val v = cmrPollHmr(cmrVersion)
                // Any other version is a change: a restarted server counts from 1 again,
                // and its lower number must resync the device rather than be waited out.
                if (v != cmrVersion && v > 0) {
                    val (b, ver, isDelta) = cmrFetchDelta(cmrVersion)   // since = OLD version, so the delta covers the edit
                    cmrVersion = ver                                     // advance now so the next poll doesn't re-trigger
                    if (b != null) handler.post { cmrReloadInPlace(b, ver, isDelta) }
                } else {
                    try { Thread.sleep(150) } catch (e: InterruptedException) { return@Thread }
                }
            }
        }.apply { isDaemon = true; start() }
    }
    // GET /delta?since=cmrVersion: the changed modules only (X-CMR-Delta:1), or a full
    // bundle (X-CMR-Delta:0) on first load / after a server restart. Off the main thread.
    private fun cmrFetchDelta(since: Int): Triple<ByteArray?, Int, Boolean> {
        return try {
            val c = java.net.URL("$cmrDevBase/delta?since=$since").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 3000; c.readTimeout = 8000
            val code = c.responseCode
            fun hdr(name: String) = c.headerFields?.entries?.firstOrNull { it.key?.equals(name, true) == true }?.value?.firstOrNull()?.trim()
            val ver = hdr("X-CMR-Version")?.toIntOrNull() ?: cmrVersion
            val isDelta = hdr("X-CMR-Delta") == "1"
            val body = if (code == 200) c.inputStream.use { it.readBytes() } else null
            c.disconnect(); Triple(body, ver, isDelta)
        } catch (e: Exception) { Triple(null, cmrVersion, false) }
    }
    // Hot reload WITHOUT an Activity recreate (no blank flash): tear down the current
    // tree, apply the delta (or full bundle) to the VM, and rebuild, all in one
    // main-thread frame so the screen swaps in place. Runs on the UI thread.
    private fun cmrReloadInPlace(payload: ByteArray, ver: Int, isDelta: Boolean) {
        setFrameDriver(false)
        val saved = try { N.cmrSaveState() } catch (e: Throwable) { "" }   // capture cells + nav from the OLD VM
        remove("app")                                    // detaches from root, frees the Yoga tree, clears per-node maps
        activeModal = null; listScroll = null; contentId = ""; shownSheet = null
        everMounted = false
        val rc = if (isDelta) N.cmrApplyDelta(payload) else N.cmrBoot(payload, cacheDir.absolutePath)
        cmrVersion = ver
        android.util.Log.i("CMR", "reload ${if (isDelta) "delta" else "full"} rc=$rc v=$ver (${payload.size}B)")
        if (rc != 0) {
            // A live edit introduced a type/parse error: show the dev overlay with the
            // reason (the old tree is already torn down) and wait for the next good save.
            lastGoodState = saved                                          // keep state to restore after the fix
            showDevError(N.cmrLastError())
            return
        }
        try { N.cmrLoadState(if (lastGoodState.isNotEmpty()) lastGoodState else saved) } catch (e: Throwable) {}  // restore into the fresh VM before mount
        lastGoodState = ""
        dismissDevError()
        // A hot reload swaps in a FRESH VM whose insets are zero. reportInsets' change-guard
        // would skip re-sending them, so the new VM would lay out edge-to-edge (content under
        // the status bar, tab bar under the nav bar). Invalidate the cache so reportInsets resends.
        // The same goes for everything else the host told the old VM at launch: the OS
        // appearance (without it the fresh VM opens in the engine's default theme, dark,
        // on a phone in light mode after the first save) and the platform info.
        lastInsets = intArrayOf(-1, -1, -1, -1)
        val isTablet = if (resources.configuration.smallestScreenWidthDp >= 600) 1 else 0
        N.setPlatform("android", android.os.Build.VERSION.RELEASE, android.os.Build.MODEL, isTablet)
        N.setColorScheme(if (osDark()) 1 else 0)
        hostMount(); reportInsets(); relayout(); if (pushViewport()) relayout()
    }
    private var lastGoodState: String = ""   // app state kept across a failed reload, restored on the fix

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
    private fun engInput(a: String, v: String): String {
        if (traceStream) android.util.Log.v("ChuksStream", "-> input $a [$v]")
        return if (devMode) devBlocking("/input", "$a\n$v") else { N.input(a, v); N.drain() }
    }
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

    // ---- State restoration --------------------------------------------------
    //
    // The engine already serializes the route stack, every tab's own history, and every
    // useState cell, because hot reload needs exactly that. Restoring therefore returns
    // the user to the screen they left with what they had typed and where they had
    // scrolled, not merely to the right screen.
    private val stateFile get() = java.io.File(filesDir, "chuks-state.json")
    // Written at launch, removed on a clean pause. Finding it at launch means the last
    // run ended without pausing, which usually means it crashed, and dropping someone
    // straight back onto a screen that crashes can trap them in a loop.
    private val runMarker get() = java.io.File(filesDir, "chuks-running")
    // How recently the app must have been backgrounded for its state to count, in
    // seconds. 0 disables restoration. Baked in from app.json at build time.
    private val restoreWindow: Int get() = ChuksBuild.STATE_RESTORE_WINDOW

    private fun persistState() {
        if (devMode || restoreWindow <= 0) return
        val st = try { N.saveState() } catch (e: Throwable) { "" }
        if (st.isEmpty()) return
        val doc = org.json.JSONObject()
        doc.put("at", System.currentTimeMillis() / 1000)
        doc.put("build", ChuksBuild.BUILD_ID)
        doc.put("state", st)
        try { stateFile.writeText(doc.toString()); runMarker.delete() } catch (e: Throwable) {}
    }

    /// Decide whether to restore, and do it. Called once, BEFORE the first mount, because
    /// loadState replaces the route stack and the cells: restoring afterwards would build
    /// the wrong screen and then throw it away.
    private fun restoreStateIfAppropriate() {
        val crashed = runMarker.exists()
        try { runMarker.writeText("1") } catch (e: Exception) {}     // arm for this run

        if (devMode || restoreWindow <= 0) return
        if (!lastUrl.isNullOrEmpty()) return              // a deep link is a deliberate destination
        if (crashed) {
            stateFile.delete()
            android.util.Log.w("chuks-state", "not restoring, the previous run did not exit cleanly")
            return
        }
        if (!stateFile.exists()) return
        try {
            val doc = org.json.JSONObject(stateFile.readText())
            val age = System.currentTimeMillis() / 1000 - doc.getLong("at")
            if (age > restoreWindow) { stateFile.delete(); return }   // stale: start fresh
            // Written by another build of the code: its routes and cells may not exist
            // here, or mean something else. Only the build that wrote a snapshot reads it.
            if (doc.optString("build", "") != ChuksBuild.BUILD_ID) {
                stateFile.delete()
                android.util.Log.w("chuks-state", "not restoring, the state was saved by a different build")
                return
            }
            N.loadState(doc.getString("state"))
        } catch (e: Throwable) { stateFile.delete() }
    }
    // An empty reply changed nothing: no apply, no layout. A value's end or settle whose
    // handler only queued a spring, or a gesture nobody bound, comes back empty.
    private fun hostEvent(a: String) { if (traceStream) android.util.Log.v("ChuksStream", "-> event $a"); val st = engEvent(a); if (st.isEmpty()) return; applyStream(st); relayout() }
    private fun hostInput(a: String, v: String) { val st = engInput(a, v); if (st.isEmpty()) return; applyStream(st); relayout() }

    // Deliver a native capability result back to the engine and apply the re-render.
    // Public because a package's module answers through the same channel the framework's
    // own capabilities do (ChuksModuleHost).
    // Token "0" is a call without a callback: nothing is waiting, so nothing to render.
    override fun resolve(token: String, payload: String) {
        if (token == "0") return
        val st = engResolve(token, payload); if (st.isEmpty()) return
        applyStream(st); relayout()
    }
    // Report a capability failure back to the engine (fires the request's onErr).
    // Token "0" means the caller passed no callback, so the engine allocated nothing and
    // there is no closure anywhere to hand this to. The engine's own unhandled-failure
    // warning cannot reach these, because there is no token for it to fail: a
    // fire-and-forget capability has nowhere to report to BY CONSTRUCTION. The host is
    // the last place that still knows both the capability and the reason, so it says so
    // here rather than letting the failure evaporate.
    override fun fail(token: String, message: String) {
        if (token == "0") {
            val what = if (dispatchingCap.isEmpty()) "a capability" else dispatchingCap
            android.util.Log.w("Chuks", "chuks warning: $what failed and nothing is listening: " +
                "\"$message\". It was called without a callback, so nothing could be told.")
            return
        }
        applyStream(engFail(token, message)); relayout()
    }
    // The capability currently being dispatched, so a failure can name itself.
    private var dispatchingCap: String = ""
    // ChuksModuleHost: a module gets the Activity the framework's own capabilities use.
    override val activity: Activity get() = this

    // ChuksModuleHost: a module's stream is torn down through the same map the
    // framework's own streams use, so `__cancel__` releases both alike.
    override fun onCancel(token: String, teardown: () -> Unit) { streamTeardown[token] = teardown }

    // ChuksModuleHost: a runtime permission request rides the host's existing pending-token
    // plumbing, because only the Activity receives onRequestPermissionsResult.
    override fun requestPermission(token: String, permissions: Array<String>) {
        val code = ++permSeq
        pendingPerms[code] = token
        enqueuePermissionRequest(permissions, code)
    }
    // Android answers an in-flight requestPermissions with an EMPTY result the moment a
    // second one is made, which reads as "denied" for a prompt the user never saw. So
    // requests go one at a time: the next is made when the current one is answered. A
    // request for something the first dialog already granted is answered by the system
    // without a dialog, so the queue drains at the speed of the user's taps.
    private val permQueue = ArrayDeque<Pair<Array<String>, Int>>()
    private var permInFlight = false
    private fun enqueuePermissionRequest(permissions: Array<String>, code: Int) {
        permQueue.addLast(Pair(permissions, code))
        pumpPermissionQueue()
    }
    private fun pumpPermissionQueue() {
        if (permInFlight) return
        val next = permQueue.removeFirstOrNull() ?: return
        permInFlight = true
        requestPermissions(next.first, next.second)
    }

    // Capabilities installed packages provide, consulted for any command the framework's
    // own `when` does not claim. Lazy: an app with no native package never builds it.
    private val packageModules by lazy { ChuksModuleRegistry(this) }

    // Live native subscriptions (stream token -> repeating Runnable), for teardown.
    private val streamHandler = Handler(Looper.getMainLooper())
    private val activeStreams = mutableMapOf<String, Runnable>()
    // Real OS streams (battery/app-state/network): a teardown closure per token,
    // run on __cancel__ so the receiver/callback is unregistered.
    // Live package-supplied views, by node id.
    private val packageViews = HashMap<String, ChuksNativeView>()
    private val packageViewHosts = HashMap<String, ViewBinding>()
    /** One package view's host object; see ChuksViewHost. */
    inner class ViewBinding(private val id: String) : ChuksViewHost {
        override val activity: Activity get() = this@MainActivity
        override fun emit(name: String, value: String) { hostInput("$id:$name", value) }
        override fun invalidateSize() { remeasure(id) }
    }
    private val streamTeardown = mutableMapOf<String, () -> Unit>()
    private val appStateTokens = mutableSetOf<String>()   // tokens watching foreground/background
    private val orientationTokens = mutableSetOf<String>()   // tokens watching device orientation
    // "coarse,edge", where edge is where the TOP OF THE DEVICE points. Display
    // rotation is counter-clockwise from the natural orientation, so ROTATION_90 has
    // the top pointing left, and the coarse word comes from the configuration, which
    // is what the layout actually follows (a tablet's natural orientation may be
    // landscape, where rotation alone would give the wrong coarse answer).
    private fun currentOrientation(): String {
        val coarse = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        val rotation = if (android.os.Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0
                       else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        val edge = when (rotation) {
            android.view.Surface.ROTATION_90  -> "left"
            android.view.Surface.ROTATION_180 -> "down"
            android.view.Surface.ROTATION_270 -> "right"
            else                              -> "up"
        }
        // A phone's natural orientation is portrait, so up/down pair with portrait and
        // left/right with landscape. On a natural-landscape device they cross; report
        // what the device is doing rather than a pair that cannot happen.
        return "$coarse,$edge"
    }

    // Execute a native capability requested via an `X|` command (F3). Fire-and-forget
    // commands (token "0") just perform the side effect; async reads call resolve().
    // The live view tree, for Debug.viewTree. Node ids are structural paths ("app.0.1"),
    // so sorting them puts a parent before its children and the dot count is the depth.
    // Frames are reported in dp, matching what the Chuks side asked for.
    private val DETACHED_MARK = "\u0000detached"

    private fun viewTreeDump(): String {
        // Asked before the first layout pass, every frame would read 0 and the dump
        // would call the whole tree ZERO-SIZED, which is a lie that looks exactly like
        // the bug this tool exists to find. Say what is actually true instead.
        val rootNode = ynodes["app"]
        if (rootNode == null || N.yGet(rootNode, 2).toInt() == 0) {
            return "(no frames yet: the first layout pass has not run, so nothing has a resolved size)"
        }
        // The app's own tree first. Ids sort as strings, and a recycled list cell is
        // named ".0.cellN", so a plain sort puts a pool of detached cells above the
        // screen you are looking at and fills the first screenful with them. They are
        // real views and worth seeing, so they follow under a heading rather than
        // being dropped.
        val rooted = views.keys.filter { it == "app" || it.startsWith("app.") }.sorted()
        val detached = views.keys.filter { !(it == "app" || it.startsWith("app.")) }.sorted()
        val sb = StringBuilder()
        for (id in rooted + listOf(DETACHED_MARK) + detached) {
            if (id == DETACHED_MARK) {
                if (detached.isEmpty()) continue
                sb.append("-- not attached to the app root (recycled list cells, torn-down screens) --\n")
                continue
            }
            val v = views[id] ?: continue
            val depth = id.count { it == '.' }
            val pad = "  ".repeat(depth)
            // The frame comes from Yoga, not from the View. Android applies a frame by
            // assigning LayoutParams and asking for a layout pass, and that pass has not
            // run yet when a capability answers on the main looper: every v.width would
            // read 0 and the dump would call the whole tree ZERO-SIZED. The Yoga node
            // holds the resolved frame the moment relayout() computed it, which is the
            // number this dump claims to show, and it is what iOS reports too (there the
            // frame is assigned to the view directly, so the two agree).
            val yn = ynodes[id]
            val x: Int; val y: Int; val w: Int; val h: Int
            // A popover's content is placed by the host after Yoga, so its truth is the
            // LayoutParams the host wrote, not the node.
            val placed = popoverIds.contains(id.substringBeforeLast(".")) && v.layoutParams is FrameLayout.LayoutParams
            if (placed) {
                val lp = v.layoutParams as FrameLayout.LayoutParams
                x = (lp.leftMargin / density).toInt(); y = (lp.topMargin / density).toInt()
                w = (lp.width / density).toInt(); h = (lp.height / density).toInt()
            } else if (yn != null) {
                x = (N.yGet(yn, 0) / density).toInt(); y = (N.yGet(yn, 1) / density).toInt()
                w = (N.yGet(yn, 2) / density).toInt(); h = (N.yGet(yn, 3) / density).toInt()
            } else {
                x = (v.x / density).toInt(); y = (v.y / density).toInt()
                w = (v.width / density).toInt(); h = (v.height / density).toInt()
            }
            sb.append("$pad$id  ${v.javaClass.simpleName}  $x,$y ${w}x$h")
            if (v.visibility != View.VISIBLE) sb.append("  hidden")
            if (w == 0 || h == 0) sb.append("  ZERO-SIZED")
            // Where the platform disagrees with the engine. Android applies Yoga's frame
            // through LayoutParams and then measures for itself, and a parent may
            // overrule the child's height (a ScrollView measures its content
            // UNSPECIFIED, so the content's own padding fell out of the scroll range
            // and a page that just fit would not scroll). Yoga is what the app asked
            // for; the View is what the user gets; a gap between them is a host bug,
            // so the dump names it. Hidden views are skipped (a GONE view has no size);
            // a horizontal list's content has its height pinned on purpose, so only its
            // width is held to Yoga's. The root is placed by the host itself (topY below
            // the inset), so it is exempt.
            if (id != "app" && v.visibility == View.VISIBLE && !placed) {
                val px = (v.x / density).toInt(); val py = (v.y / density).toInt()
                val pw = (v.width / density).toInt(); val ph = (v.height / density).toInt()
                val pinnedH = listHoriz && id == contentId
                if (Math.abs(px - x) > 1 || Math.abs(pw - w) > 1 || (!pinnedH && (Math.abs(py - y) > 1 || Math.abs(ph - h) > 1))) {
                    sb.append("  host=$px,$py ${pw}x$ph DRIFT")
                }
            }
            (v as? TextView)?.text?.toString()?.let {
                if (it.isNotEmpty()) sb.append("  \"" + (if (it.length > 30) it.take(30) + "…" else it) + "\"")
            }
            sb.append(a11yDump(id, v))
            sb.append("\n")
        }
        return if (sb.isEmpty()) "(no views)" else sb.toString()
    }
    // What TalkBack would get for this view, read back from the AccessibilityNodeInfo
    // the view builds (so the delegate has run) rather than from what the host thinks
    // it set. Same shape as the iOS dump, so a test can compare the two.
    // Run `block` once the platform has laid the tree out. A frame just written to a
    // LayoutParams is not on any View until the next traversal; a caller that wants
    // to compare Yoga's frames with the platform's has to wait for that.
    private fun afterLayout(block: () -> Unit) {
        if (!root.isLayoutRequested) { block(); return }
        root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                block()
            }
        })
    }

    private fun a11yDump(id: String, v: View): String {
        val parts = ArrayList<String>()
        if (v.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) parts.add("hidden")
        // A node TalkBack would stop on: it says something of its own (text, a label) or
        // the host made it focusable for a role. Mirrors iOS "element".
        val isGroupElement = a11yIds.contains(id) && v.isFocusable && v is ViewGroup
        val speaks = (v is TextView && v.text.isNotEmpty()) || !v.contentDescription.isNullOrEmpty() || isGroupElement || v is android.widget.CompoundButton || v is SeekBar
        if (speaks && v.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO && v.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
            parts.add("element")
            val info = try { v.createAccessibilityNodeInfo() } catch (e: Exception) { null }
            if (info != null) {
                // A focusable group with no label: TalkBack reads its descendants' text.
                val cd = info.contentDescription?.toString() ?: ""
                if (cd.isNotEmpty()) parts.add("label=\"$cd\"")
                else if (isGroupElement) descendantText(v).let { if (it.isNotEmpty()) parts.add("label=\"$it\"") }
                val roles = ArrayList<String>()
                when (info.className?.toString() ?: "") {
                    "android.widget.Button" -> roles.add("button")
                    "android.widget.ImageView" -> roles.add("image")
                    "android.widget.Switch" -> roles.add("switch")
                    "android.widget.CheckBox" -> roles.add("checkbox")
                    "android.widget.RadioButton" -> roles.add("radio")
                    "android.widget.EditText" -> roles.add("search")
                    "android.widget.SeekBar" -> roles.add("adjustable")
                }
                info.extras.getCharSequence("AccessibilityNodeInfo.roleDescription")?.let { roles.add(it.toString()) }
                if (Build.VERSION.SDK_INT >= 28 && info.isHeading && !roles.contains("heading")) roles.add("heading")
                if (info.isSelected) roles.add("selected")
                if (!info.isEnabled) roles.add("disabled")
                if (roles.isNotEmpty()) parts.add("role=" + roles.joinToString("+"))
                val vals = ArrayList<String>()
                if (info.isCheckable) vals.add(if (info.isChecked) "checked" else "unchecked")
                if (Build.VERSION.SDK_INT >= 30) info.stateDescription?.let { if (it.isNotEmpty()) vals.add(it.toString()) }
                if (vals.isNotEmpty()) parts.add("value=\"" + vals.joinToString(", ") + "\"")
                if (Build.VERSION.SDK_INT >= 28) info.tooltipText?.let { if (it.isNotEmpty()) parts.add("hint=\"$it\"") }
                info.recycle()
            }
        }
        if (v.accessibilityLiveRegion != View.ACCESSIBILITY_LIVE_REGION_NONE) parts.add("live=" + (if (v.accessibilityLiveRegion == View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE) "assertive" else "polite"))
        return if (parts.isEmpty()) "" else "  a11y{" + parts.joinToString(" ") + "}"
    }

    private fun handleCommand(token: String, cap: String, args: String, a: ChuksArgs) {
        dispatchingCap = cap
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
                // Battery Saver toggling is a battery fact too. Its broadcast carries no
                // battery extras, so re-read the sticky intent for the rest of the payload.
                val saver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) { batterySticky()?.let { emitBattery(token, it) } }
                }
                registerReceiver(saver, android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
                streamTeardown[token] = {
                    try { unregisterReceiver(receiver) } catch (e: Exception) {}
                    try { unregisterReceiver(saver) } catch (e: Exception) {}
                }
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
                    override fun onLost(n: android.net.Network) { runOnUiThread { resolve(token, "none,0") } }
                    override fun onCapabilitiesChanged(n: android.net.Network, caps: android.net.NetworkCapabilities) { emitNetwork(token, cm) }
                }
                cm.registerDefaultNetworkCallback(cb)
                streamTeardown[token] = { try { cm.unregisterNetworkCallback(cb) } catch (e: Exception) {} }
                emitNetwork(token, cm)
            }
            // ---- location: see ChuksGeo.kt ----------------------------------------
            "location.once" -> withLocationPerm(token) {
                val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val provider = ChuksGeo.provider(lm, args)
                if (provider == null) { fail(token, "location unavailable"); return@withLocationPerm }
                try {
                    ChuksGeo.current(lm, provider, args) { l -> if (l != null) resolve(token, ChuksGeo.fixString(l)) else fail(token, "location unavailable") }
                } catch (e: SecurityException) { fail(token, "location permission denied") }
            }
            "location.lastKnown" -> {
                if (!hasLocationPerm()) { resolve(token, ""); return }
                val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val l = ChuksGeo.lastKnown(lm, (a.num("maxAge") ?: 0.0).toLong(), (a.num("maxAcc") ?: 0.0).toFloat())
                resolve(token, if (l != null) ChuksGeo.fixString(l) else "")
            }
            "location.enabled" -> resolve(token, if (ChuksGeo.enabled(getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager)) "1" else "0")
            "location.heading" -> {
                val h = ChuksGeo.Heading(this) { s -> resolve(token, s) }
                val err = h.start(getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager)
                if (err != null) { fail(token, err); return }
                streamTeardown[token] = { h.stop() }
            }
            "location.geocode" -> ChuksGeo.geocode(this, args, { runOnUiThread(it) }) { rows, msg -> if (msg == null) resolve(token, rows) else fail(token, msg) }
            "location.reverseGeocode" -> ChuksGeo.reverseGeocode(this, a.num("lat") ?: 0.0, a.num("lng") ?: 0.0, { runOnUiThread(it) }) { rows, msg -> if (msg == null) resolve(token, rows) else fail(token, msg) }
            "location.watch" -> withLocationPerm(token) {
                val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val provider = ChuksGeo.provider(lm, a.s("acc"))
                if (provider == null) { fail(token, "location unavailable"); return@withLocationPerm }
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(l: android.location.Location) { resolve(token, ChuksGeo.fixString(l)) }
                    override fun onProviderDisabled(p: String) {}
                    override fun onProviderEnabled(p: String) {}
                    @Deprecated("kept for older API levels") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                }
                try {
                    ChuksGeo.requestUpdates(lm, provider, a.s("acc"), (a.num("dist") ?: 0.0).toFloat(), (a.num("interval") ?: 1000.0).toLong(), listener)
                    streamTeardown[token] = { try { lm.removeUpdates(listener) } catch (e: Exception) {} }
                } catch (e: SecurityException) { fail(token, "location permission denied") }
            }
            "location.watchBackground" -> withLocationPerm(token) {
                // A foreground service started while the app is on screen keeps the
                // "while in use" grant with the screen off, so this needs no separate
                // ACCESS_BACKGROUND_LOCATION prompt.
                val title = a.s("title").ifEmpty { "Location" }
                val body = a.s("body")
                ChuksLocation.token = token
                ChuksLocation.deliver = { t, fix -> resolve(t, fix) }
                val svc = Intent(this, ChuksLocationService::class.java)
                svc.putExtra("title", title)
                svc.putExtra("body", body)
                svc.putExtra("acc", a.s("acc")); svc.putExtra("dist", (a.num("dist") ?: 0.0).toFloat()); svc.putExtra("interval", (a.num("interval") ?: 1000.0).toLong())
                try {
                    startForegroundService(svc)
                } catch (e: Throwable) {
                    ChuksLocation.deliver = null
                    fail(token, "background location unavailable: " + (e.message ?: e.toString()))
                    return@withLocationPerm
                }
                streamTeardown[token] = {
                    ChuksLocation.deliver = null
                    ChuksLocation.token = ""
                    try { stopService(Intent(this, ChuksLocationService::class.java)) } catch (e: Throwable) {}
                }
            }
            "motion.accel" -> startSensor(token, android.hardware.Sensor.TYPE_ACCELEROMETER)
            "motion.gyro" -> startSensor(token, android.hardware.Sensor.TYPE_GYROSCOPE)
            "motion.mag" -> startSensor(token, android.hardware.Sensor.TYPE_MAGNETIC_FIELD)
            "motion.proximity" -> startProximity(token)
            "motion.light" -> startLightSensor(token, android.hardware.Sensor.TYPE_LIGHT) { v -> v[0].toString() }
            "motion.barometer" -> startBarometer(token)
            "pedometer.available" -> {
                val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
                resolve(token, if (sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER) != null) "true" else "false")
            }
            "pedometer.watch" -> startPedometer(token)
            "pedometer.query" -> fail(token, "historical steps need Health Connect on Android; use Pedometer.watch for a live count")
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
            "deviceinfo.id" -> {
                @Suppress("HardwareIds")
                val id = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
                resolve(token, id ?: "")
            }
            "deviceinfo.appid" -> resolve(token, packageName)
            "deviceinfo.appname" -> resolve(token, applicationInfo.loadLabel(packageManager).toString())
            "deviceinfo.installtime" -> {
                val pi = packageManager.getPackageInfo(packageName, 0)
                resolve(token, pi.firstInstallTime.toString())
            }
            // ---- contacts: see ChuksContacts.kt --------------------------------
            "contacts.pick" -> {
                // No permission: the picker's answer carries a grant for what was chosen.
                // A phone or email pick answers ONE data row, readable through that
                // grant; a whole-card pick grants the card, whose phones and emails
                // need READ_CONTACTS to read (the card's own name does not).
                val code = ++mediaSeq
                pendingContactPick[code] = Pair(token, args)
                val type = when (args) {
                    "phone" -> android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
                    "email" -> android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_TYPE
                    else -> android.provider.ContactsContract.Contacts.CONTENT_TYPE
                }
                startActivityForResult(Intent(Intent.ACTION_PICK).setType(type), code)
            }
            "contacts.list", "contacts.search" -> withContactsPerm(token, false) {
                val q = if (cap == "contacts.search") args else ""
                Thread {
                    val out = try { ChuksContacts.list(this, q) } catch (e: Exception) { null }
                    runOnUiThread { if (out != null) resolve(token, out) else fail(token, "read failed") }
                }.start()
            }
            "contacts.get" -> withContactsPerm(token, false) {
                val l = try { ChuksContacts.get(this, args) } catch (e: Exception) { null }
                if (l != null) resolve(token, l) else fail(token, "no such contact: $args")
            }
            "contacts.add" -> withContactsPerm(token, true) {
                try { resolve(token, ChuksContacts.add(this, a.s("name"), a.s("phones"), a.s("emails"))) }
                catch (e: Exception) { fail(token, "cannot add contact: ${e.message}") }
            }
            "contacts.update" -> withContactsPerm(token, true) {
                try { if (ChuksContacts.update(this, a.s("id"), a.s("name"), a.s("phones"), a.s("emails"))) resolve(token, "") else fail(token, "no such contact: ${a.s("id")}") }
                catch (e: Exception) { fail(token, "cannot update contact: ${e.message}") }
            }
            "contacts.delete" -> withContactsPerm(token, true) {
                try { if (ChuksContacts.delete(this, args)) resolve(token, "") else fail(token, "no such contact: $args") }
                catch (e: Exception) { fail(token, "cannot delete contact: ${e.message}") }
            }
            "contacts.photo" -> withContactsPerm(token, false) {
                val p = try { ChuksContacts.photo(this, args) } catch (e: Exception) { null }
                if (p != null) resolve(token, p) else fail(token, "no such contact: $args")
            }
            "contacts.watch" -> withContactsPerm(token, false) {
                val w = ChuksContacts.Watch(this) { resolve(token, "") }
                w.start()
                streamTeardown[token] = { w.stop() }
            }
            // ---- calendar: see ChuksCalendar.kt --------------------------------
            "calendar.upcoming" -> withCalendarPerm(token) {
                val days = a.num()?.toLong() ?: 0L
                val now = System.currentTimeMillis()
                try { resolve(token, ChuksCalendar.events(this, now, now + days * 86400000L, "")) } catch (e: Exception) { fail(token, "read failed: ${e.message}") }
            }
            "calendar.events" -> withCalendarPerm(token) {
                try { resolve(token, ChuksCalendar.events(this, (a.num("start") ?: 0.0).toLong(), (a.num("end") ?: 0.0).toLong(), a.s("cal"))) }
                catch (e: Exception) { fail(token, "read failed: ${e.message}") }
            }
            "calendar.get" -> withCalendarPerm(token) {
                val l = try { ChuksCalendar.get(this, args) } catch (e: Exception) { null }
                if (l != null) resolve(token, l) else fail(token, "no such event: $args")
            }
            "calendar.calendars" -> withCalendarPerm(token) {
                try { resolve(token, ChuksCalendar.calendars(this)) } catch (e: Exception) { fail(token, "read failed: ${e.message}") }
            }
            "calendar.create" -> withCalendarPerm(token) {
                val startMin = a.num("startInMin")?.toLong() ?: 0L
                val durMin = a.num("durationMin")?.toLong() ?: 0L
                val calId = ChuksCalendar.defaultCalendar(this)
                if (calId < 0) { fail(token, "no writable calendar"); return@withCalendarPerm }
                val now = System.currentTimeMillis()
                try { resolve(token, ChuksCalendar.add(this, calId, a.s("title"), now + startMin * 60000L, now + (startMin + durMin) * 60000L, "", "", false, -1).toString()) }
                catch (e: Exception) { fail(token, "save failed: ${e.message}") }
            }
            "calendar.add" -> withCalendarPerm(token) {
                val calId = if (a.s("cal").isEmpty()) ChuksCalendar.defaultCalendar(this) else (a.s("cal").toLongOrNull() ?: -2L)
                if (calId == -1L) { fail(token, "no writable calendar"); return@withCalendarPerm }
                val w = if (calId < 0) null else ChuksCalendar.writable(this, calId)
                if (w == null) { fail(token, "no such calendar: ${a.s("cal")}"); return@withCalendarPerm }
                if (!w) { fail(token, "calendar is read-only: ${a.s("cal")}"); return@withCalendarPerm }
                try {
                    resolve(token, ChuksCalendar.add(this, calId, a.s("title"), (a.num("start") ?: 0.0).toLong(), (a.num("end") ?: 0.0).toLong(),
                        a.s("location"), a.s("notes"), a.bool("allDay"), (a.num("alarm") ?: -1.0).toLong()).toString())
                } catch (e: Exception) { fail(token, "save failed: ${e.message}") }
            }
            "calendar.update" -> withCalendarPerm(token) {
                try {
                    val ok = ChuksCalendar.update(this, a.s("id"), a.s("title"), (a.num("start") ?: 0.0).toLong(), (a.num("end") ?: 0.0).toLong(),
                        a.s("location"), a.s("notes"), a.bool("allDay"), (a.num("alarm") ?: -1.0).toLong())
                    if (ok) resolve(token, "") else fail(token, "no such event: ${a.s("id")}")
                } catch (e: Exception) { fail(token, "save failed: ${e.message}") }
            }
            "calendar.delete" -> withCalendarPerm(token) {
                try { if (ChuksCalendar.delete(this, args)) resolve(token, "") else fail(token, "no such event: $args") }
                catch (e: Exception) { fail(token, "delete failed: ${e.message}") }
            }
            "calendar.compose" -> {
                // No permission. The Calendar app answers no result, so the app hears
                // "" when the form closes, whatever the user did in it.
                val code = ++mediaSeq
                pendingCompose[code] = token
                try { startActivityForResult(ChuksCalendar.composeIntent(a.s("title"), (a.num("start") ?: 0.0).toLong(), (a.num("end") ?: 0.0).toLong(), a.s("location"), a.s("notes")), code) }
                catch (e: Exception) { pendingCompose.remove(code); fail(token, "no calendar app: ${e.message}") }
            }
            "calendar.watch" -> withCalendarPerm(token) {
                val w = ChuksCalendar.Watch(this) { resolve(token, "") }
                w.start()
                streamTeardown[token] = { w.stop() }
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
            // ---- the library picker with choices: see ChuksMedia.kt --------------
            "mediapicker.video", "mediapicker.pick" -> {
                val video = cap == "mediapicker.video"
                val code = ++mediaSeq
                pendingPick[code] = PickReq(token, if (video) 0.9 else (a.num("quality") ?: 0.9), if (video) 0 else (a.int("maxSize") ?: 0), video)
                try { startActivityForResult(ChuksMedia.pickIntent(if (video) "video" else a.s("kind"), if (video) 1 else (a.int("limit") ?: 1)), code) }
                catch (e: Exception) { pendingPick.remove(code); fail(token, "no picker available") }
            }
            "mediapicker.info" -> {
                val l = ChuksMedia.info(ChuksFiles.resolve(this, args))
                if (l != null) resolve(token, l) else fail(token, "not an image or video: $args")
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
            "camera.available" -> resolve(token, if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) "1" else "0")
            "camera.video" -> {
                val code = ++mediaSeq
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "chuks-$code.mp4")
                    put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                }
                val outUri = contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (outUri == null) { fail(token, "cannot create output"); return }
                pendingMedia[code] = Pair(token, outUri)
                pendingVideo.add(code)
                val intent = Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE).apply {
                    putExtra(android.provider.MediaStore.EXTRA_OUTPUT, outUri)
                    putExtra(android.provider.MediaStore.EXTRA_VIDEO_QUALITY, if (a.s("quality") == "low") 0 else 1)
                    val cap = a.int("maxSeconds") ?: 0
                    if (cap > 0) putExtra(android.provider.MediaStore.EXTRA_DURATION_LIMIT, cap)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try { startActivityForResult(intent, code) }
                catch (e: Exception) { pendingMedia.remove(code); pendingVideo.remove(code); fail(token, "no camera app") }
            }
            "camera.flash" -> cameraController?.setFlash(args)
            "camera.zoom" -> a.num()?.let { cameraController?.setZoom(it.toFloat()) }
            "camera.zoomRange" -> resolve(token, cameraController?.zoomRange() ?: "1.0,1.0")
            "camera.focus" -> cameraController?.focusAt((a.num("x") ?: 0.5).toFloat(), (a.num("y") ?: 0.5).toFloat())
            "camera.hasFlash" -> resolve(token, if (cameraController?.hasFlash() == true) "1" else "0")
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
                ensureBle().read(a.s("deviceId"), a.s("service"), a.s("characteristic"), { p -> resolve(token, p) }, { m -> fail(token, m) })
            }
            "ble.write" -> {
                ensureBle().write(a.s("deviceId"), a.s("service"), a.s("characteristic"), a.s("valueHex"), { p -> resolve(token, p) }, { m -> fail(token, m) })
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
                // An image or a video, by what the file is; see ChuksMedia.save.
                val f = ChuksFiles.resolve(this, args)
                if (!f.isFile) { fail(token, "no such file: $args"); return }
                val err = ChuksMedia.save(this, f)
                if (err == null) resolve(token, "ok") else fail(token, err)
            }
            "biometrics.available" ->
                resolve(token, if (bioCode() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS) "1" else "0")
            // The two halves of "available". canAuthenticate() answers both through its
            // result code: ERROR_NO_HARDWARE is no sensor; ERROR_NONE_ENROLLED is a
            // sensor with nothing on it; SUCCESS is both. Anything else (unavailable
            // right now, an update needed) still counts as hardware present.
            "biometrics.hardware" ->
                resolve(token, if (bioCode() == android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) "0" else "1")
            "biometrics.enrolled" ->
                resolve(token, if (bioCode() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS) "1" else "0")
            "biometrics.types" -> {
                // Android does not say which biometric is enrolled, only which the device
                // can do, which is what a button label needs anyway.
                val pm = packageManager
                val kinds = ArrayList<String>()
                if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FACE)) kinds.add("face")
                if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT)) kinds.add("fingerprint")
                if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_IRIS)) kinds.add("iris")
                resolve(token, kinds.joinToString(","))
            }
            "biometrics.level" -> {
                // Strongest thing enrolled. Class 3 (STRONG) is a fingerprint or 3D face;
                // class 2 (WEAK) a 2D face unlock; DEVICE_CREDENTIAL a PIN, pattern or
                // password. Asked strongest first, so the answer is the best available.
                val ok = android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
                if (android.os.Build.VERSION.SDK_INT < 30) {
                    // API 29 has only the un-classed canAuthenticate(): success is "strong" by
                    // the era's definition, and a secure keyguard is "secret".
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                    resolve(token, when {
                        bioCode() == ok -> "strong"
                        km.isDeviceSecure -> "secret"
                        else -> "none"
                    })
                    return
                }
                resolve(token, when {
                    bioCode(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG) == ok -> "strong"
                    bioCode(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK) == ok -> "weak"
                    bioCode(android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL) == ok -> "secret"
                    else -> "none"
                })
            }
            "biometrics.authenticate" -> authenticateBiometric(token, args)
            "debug.activeStreams" -> resolve(token, (activeStreams.size + streamTeardown.size).toString())
            // Answered after the pending layout pass, so the platform frames the dump
            // compares against Yoga's are the ones on screen, not the previous pass's.
            "debug.viewTree" -> afterLayout { resolve(token, viewTreeDump()) }
            "debug.frames" -> frameStats(token, args.toDoubleOrNull() ?: 10000.0)
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
                    enqueuePermissionRequest(when (args) {
                        "calendar" -> arrayOf(p, Manifest.permission.WRITE_CALENDAR)
                        "contacts" -> arrayOf(p, Manifest.permission.WRITE_CONTACTS)
                        else -> arrayOf(p)
                    }, code)
                }
            }
            // ---- files: see ChuksFiles.kt. A null message is success. ----------
            "fs.write" -> ChuksFiles.write(this, a.s("name"), a.s("content")).let { if (it == null) resolve(token, "") else fail(token, it) }
            "fs.writeB64" -> ChuksFiles.writeB64(this, a.s("name"), a.s("content")).let { if (it == null) resolve(token, "") else fail(token, it) }
            "fs.read" -> {
                val f = ChuksFiles.resolve(this, args)
                if (f.isFile) resolve(token, f.readText()) else fail(token, "no such file: $args")
            }
            "fs.readB64" -> ChuksFiles.readB64(this, args).let { if (it != null) resolve(token, it) else fail(token, "no such file: $args") }
            "fs.list" -> ChuksFiles.list(this, args).let { if (it != null) resolve(token, it) else fail(token, "no such directory: $args") }
            "fs.delete" -> ChuksFiles.delete(this, args).let { if (it == null) resolve(token, "") else fail(token, it) }
            "fs.exists" -> resolve(token, if (ChuksFiles.resolve(this, args).exists()) "1" else "0")
            "fs.info" -> resolve(token, ChuksFiles.info(this, args))
            "fs.mkdir" -> ChuksFiles.mkdir(this, args).let { if (it == null) resolve(token, "") else fail(token, it) }
            "fs.copy", "fs.move" -> ChuksFiles.transfer(this, a.s("src"), a.s("dst"), cap == "fs.move", { runOnUiThread(it) }) { msg ->
                if (msg == null) resolve(token, "") else fail(token, msg)
            }
            "fs.download" -> ChuksFiles.download(this, a.s("url"), a.s("dst"), { runOnUiThread(it) }) { result, msg ->
                if (msg == null) resolve(token, result) else fail(token, msg)
            }
            "fs.diskSpace" -> resolve(token, ChuksFiles.diskSpace(this))
            "fs.dir" -> resolve(token, ChuksFiles.dir(this, args))
            // ---- secure storage: see ChuksSecure.kt ----------------------------
            "secure.set" -> {
                // A write can fail (the keystore can refuse, the key can be empty). With
                // a token the awaited form throws; without one the host logs it, rather
                // than the app reading nothing back later with no reason anywhere.
                val key = a.s("key")
                if (key.isEmpty()) fail(token, "SecureStore.set: the key is empty")
                else try { secure.setPlain(key, a.s("value")); resolve(token, "") }
                     catch (e: Exception) { fail(token, "SecureStore.set: cannot store \"$key\": ${e.message}") }
            }
            "secure.setProtected" -> secure.setProtected(a.s("key"), a.s("value"), "", { resolve(token, "") }, { m -> fail(token, m) })
            "secure.get" -> secure.get(args, "", { v -> resolve(token, v) }, { fail(token, "no such key: $args") }, { m -> fail(token, m) })
            "secure.has" -> resolve(token, if (secure.has(args)) "1" else "0")
            "secure.keys" -> resolve(token, secure.keys().joinToString("\n"))
            "secure.delete" -> secure.delete(args)
            "secure.deleteAll" -> secure.deleteAll()
            "secure.available" -> resolve(token, if (secure.availableBool()) "1" else "0")
            // ---- Local notifications: see ChuksNotifications.kt ----
            "notif.notify" -> {
                val id = a.s("id").ifEmpty { "chuks-" + (notifId++) }
                ChuksNotif.post(this, id, a.s("title"), a.s("body"), a.s("data"), a.s("channel"))
            }
            "notif.schedule" -> {
                val atMs = a.num("atMs")?.toLong() ?: 0L
                val inSec = a.num("inSeconds")?.toLong() ?: 0L
                val fireAt = if (atMs > 0) atMs else System.currentTimeMillis() + inSec * 1000
                ChuksNotif.schedule(this, a.s("id"), a.s("title"), a.s("body"), fireAt, a.s("data"), a.s("channel"))
            }
            "notif.cancel" -> ChuksNotif.cancel(this, args)
            "notif.cancelAll" -> ChuksNotif.cancelAll(this)
            "notif.dismiss" -> ChuksNotif.dismiss(this, args)
            "notif.dismissAll" -> ChuksNotif.dismissAll(this)
            "notif.scheduled" -> resolve(token, ChuksNotif.scheduled(this))
            "notif.setBadge" -> ChuksNotif.setBadge(this, a.int() ?: 0)
            "notif.badge" -> resolve(token, ChuksNotif.badge(this).toString())
            "notif.channel" -> ChuksNotif.ensureChannel(this, a.s("id"), a.s("name"), a.s("importance"))
            "notif.onResponse" -> {
                notifTokens.add(token)
                ChuksNotif.onResponse = { p -> runOnUiThread { notifTokens.toList().forEach { resolve(it, p) } } }
                streamTeardown[token] = {
                    notifTokens.remove(token)
                    if (notifTokens.isEmpty()) ChuksNotif.onResponse = null
                }
                // The tap that launched the app, if any, goes to the first subscriber.
                ChuksNotif.pending?.let {
                    android.util.Log.i("Chuks", "notif.onResponse: delivering the launch tap to the first subscriber")
                    ChuksNotif.pending = null; resolve(token, it)
                }
            }
            // ---- Audio: see ChuksAudio.kt ----
            "audio.mode" -> ChuksAudio.mode = args
            "audio.create" -> {
                val id = a.s("id"); val src = a.s("src")
                if (ChuksAudio.players.size >= ChuksAudio.CAP) {
                    val msg = "too many players (${ChuksAudio.CAP}); release() the ones you are done with"
                    audioWatchers[id]?.forEach { resolve(it, "error,0,0,1,1,0,$msg") }
                    android.util.Log.w("Chuks", "Audio: $msg")
                    return
                }
                ChuksAudio.listen(this)
                ChuksAudio.players.remove(id)?.release()
                val p = ChuksAudioPlayer(id) { st -> runOnUiThread { audioWatchers[id]?.toList()?.forEach { resolve(it, st) } } }
                ChuksAudio.players[id] = p
                p.load(this, src)
            }
            "audio.play" -> { ChuksAudio.requestFocus(this); ChuksAudio.players[a.s("id")]?.play() }
            "audio.pause" -> ChuksAudio.players[a.s("id")]?.pause()
            "audio.stop" -> ChuksAudio.players[a.s("id")]?.stop()
            "audio.seek" -> ChuksAudio.players[a.s("id")]?.seek(a.int("ms") ?: 0)
            "audio.volume" -> ChuksAudio.players[a.s("id")]?.setVolume((a.num("v") ?: 1.0).toFloat())
            "audio.rate" -> ChuksAudio.players[a.s("id")]?.setRate((a.num("r") ?: 1.0).toFloat())
            "audio.loop" -> ChuksAudio.players[a.s("id")]?.setLoop(a.bool("on"))
            "audio.status" -> resolve(token, ChuksAudio.players[a.s("id")]?.status() ?: "error,0,0,1,1,0,no such player (released, or never created)")
            "audio.watch" -> {
                val id = a.s("id")
                audioWatchers.getOrPut(id) { HashSet() }.add(token)
                streamTeardown[token] = { audioWatchers[id]?.remove(token) }
                ChuksAudio.players[id]?.let { resolve(token, it.status()) }
            }
            "audio.release" -> {
                val id = a.s("id")
                ChuksAudio.players.remove(id)?.release()
                audioWatchers.remove(id)
            }
            // ---- recorder: see ChuksRecorder.kt ---------------------------------
            "recorder.start" -> withMicPerm(token) {
                val err = recorder.start(args)
                if (err == null) resolve(token, "") else fail(token, err)
            }
            "recorder.pause" -> recorder.pause()
            "recorder.resume" -> recorder.resume()
            "recorder.stop" -> {
                val p = recorder.stop()
                if (p == null) fail(token, "not recording") else if (p.isEmpty()) fail(token, "nothing was recorded") else resolve(token, "file://$p")
            }
            "recorder.cancel" -> recorder.cancel()
            "recorder.status" -> resolve(token, recorder.status())
            "recorder.watch" -> {
                recorder.watchers.add(token)
                streamTeardown[token] = { recorder.watchers.remove(token) }
                resolve(token, recorder.status())
            }
            "recorder.levels" -> {
                val r = object : Runnable {
                    override fun run() {
                        resolve(token, String.format(java.util.Locale.US, "%.3f", recorder.level()))
                        if (activeStreams.containsKey(token)) streamHandler.postDelayed(this, 80)
                    }
                }
                activeStreams[token] = r
                streamHandler.postDelayed(r, 80)
            }
            // ---- text-to-speech: see ChuksSpeech.kt ----------------------------
            "tts.speak", "tts.say" -> speech.speak(a.s("id"), a.s("text"), a.s("lang"), (a.num("rate") ?: 1.0).toFloat(), (a.num("pitch") ?: 1.0).toFloat(),
                (a.num("volume") ?: 1.0).toFloat(), a.s("voice"), a.bool("queue"), if (cap == "tts.say") token else null)
            "tts.stop" -> speech.stop()
            "tts.pause" -> speech.pause()
            "tts.resume" -> speech.resume()
            "tts.isSpeaking" -> resolve(token, if (speech.isSpeaking()) "1" else "0")
            "tts.status" -> resolve(token, speech.status())
            "tts.voices" -> speech.whenReady { resolve(token, speech.voices()) }
            "tts.watch" -> { ttsWatchers.add(token); streamTeardown[token] = { ttsWatchers.remove(token) } }
            // The framework noticed something the app probably did not mean. Logged
            // natively because the engine's own println reaches nothing on Android.
            "dev.warn" -> android.util.Log.w("Chuks", args)
            // Which appearance the APP is in. iOS applies this to the window and every
            // dynamic colour follows; Android has no dynamic colours on a plain view, so
            // the one default the host owns, the colour of text the app left colourless,
            // is repainted here instead.
            "appearance.set" -> { appAppearance = args; repaintDefaultText() }
            // ---- clipboard: see ChuksClipboard.kt ------------------------------
            "clipboard.set" -> clipboard().setPrimaryClip(ClipData.newPlainText("", args))
            "clipboard.get" -> {
                val t = clipboard().primaryClip?.let { if (it.itemCount > 0) it.getItemAt(0).coerceToText(this).toString() else "" } ?: ""
                resolve(token, t)
            }
            "clipboard.setUrl" -> clipboard().setPrimaryClip(ClipData.newPlainText("", args))
            "clipboard.setImage" -> ChuksClipboard.setImage(this, clipboard(), args).let { if (it == null) resolve(token, "") else fail(token, it) }
            "clipboard.getImage" -> {
                val r = try { ChuksClipboard.getImage(this, clipboard()) } catch (e: Exception) { fail(token, "cannot read the image: ${e.message}"); return }
                resolve(token, r)
            }
            "clipboard.has" -> resolve(token, if (ChuksClipboard.kinds(this, clipboard()).split(",").contains(args)) "1" else "0")
            "clipboard.clear" -> ChuksClipboard.clear(clipboard())
            "clipboard.watch" -> {
                val cm = clipboard()
                // Recent Android delivers the listener twice for one setPrimaryClip; the
                // description's timestamp tells the two apart, so one change is one event.
                var seen = -1L
                val l = ClipboardManager.OnPrimaryClipChangedListener {
                    val ts = if (android.os.Build.VERSION.SDK_INT >= 26) (cm.primaryClipDescription?.timestamp ?: 0L) else System.currentTimeMillis()
                    if (ts == seen) return@OnPrimaryClipChangedListener
                    seen = ts
                    resolve(token, ChuksClipboard.kinds(this, cm))
                }
                cm.addPrimaryClipChangedListener(l)
                streamTeardown[token] = { cm.removePrimaryClipChangedListener(l) }
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
            "haptics.vibrate" -> a.num()?.let { hapticVibrate(it.toLong()) }
            "haptics.pattern" -> hapticPattern(args)
            "torch.set" -> setTorch(args == "1")
            // ---- brightness ------------------------------------------------------
            // The window's brightness is the app's: it applies while the window is in
            // front and the system's setting comes back when it is not. The system's
            // own value is a Settings.System row, written only with WRITE_SETTINGS,
            // which is a grant the user gives in Settings rather than a prompt.
            "brightness.set" -> a.num()?.let {
                val lp = window.attributes; lp.screenBrightness = it.toFloat().coerceIn(0f, 1f); window.attributes = lp
            }
            "brightness.get" -> {
                val o = window.attributes.screenBrightness
                resolve(token, brightnessStr(if (o >= 0f) o.toDouble() else systemBrightness()))
            }
            "brightness.restore" -> { val lp = window.attributes; lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; window.attributes = lp }
            "brightness.system" -> resolve(token, brightnessStr(systemBrightness()))
            "brightness.setSystem" -> {
                val v = a.num() ?: return
                if (!android.provider.Settings.System.canWrite(this)) { fail(token, "system brightness needs the Modify system settings grant: Brightness.requestSystemAccess() opens where the user gives it"); return }
                try {
                    android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, (v.coerceIn(0.0, 1.0) * 255).toInt())
                    resolve(token, "")
                } catch (e: Exception) { fail(token, "cannot set system brightness: ${e.message}") }
            }
            "brightness.canSetSystem" -> resolve(token, if (android.provider.Settings.System.canWrite(this)) "1" else "0")
            "brightness.requestSystemAccess" -> try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {}
            "brightness.mode" -> {
                val m = try { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE) } catch (e: Exception) { 0 }
                resolve(token, if (m == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) "auto" else "manual")
            }
            "brightness.watch" -> {
                val obs = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(self: Boolean) { resolve(token, brightnessStr(systemBrightness())) }
                }
                contentResolver.registerContentObserver(android.provider.Settings.System.getUriFor(android.provider.Settings.System.SCREEN_BRIGHTNESS), false, obs)
                streamTeardown[token] = { contentResolver.unregisterContentObserver(obs) }
            }
            "brightness.keepAwake" ->
                if (args == "1") window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            "orientation.watch" -> {
                orientationTokens.add(token)
                streamTeardown[token] = { orientationTokens.remove(token) }
                resolve(token, currentOrientation())
            }
            // ---- Background tasks ----
            "bg.define" -> {
                // Remember the token process-wide: a job may be handled by the service
                // with this Activity alive, in which case it uses what we learned here
                // rather than mounting again.
                ChuksBg.tokens[args] = token
                streamTeardown[token] = { ChuksBg.tokens.remove(args) }
            }
            "bg.result" -> {
                ChuksBg.finish(a.s("name"), a.bool("ok"))
            }
            "bg.periodic", "bg.once", "bg.processing" -> {
                // Same three arguments whichever of the three schedulers this is.
                val secs = a.int("seconds") ?: return
                val cons = HashMap<String, String>()
                for (pair in a.s("constraints").split(";")) {
                    if (pair.isEmpty()) continue
                    val kv = pair.split("=")
                    if (kv.size == 2) cons[kv[0]] = kv[1]
                }
                // A processing task is long work that wants power, so it is a one-off
                // job with those constraints rather than a repeating one.
                scheduleChuksJob(this, a.s("name"), secs, cap == "bg.periodic", cons)
            }
            "bg.cancel" -> cancelChuksJob(this, args)
            "bg.cancelAll" -> cancelAllChuksJobs(this)
            "bg.status" -> resolve(token, chuksJobStatus(this, args))

            // ---- One-shot reads of live OS state --------------------------
            // The value the matching watch() would fire right now. Before these, reading
            // one value meant opening a stream and cancelling it, which is a teardown to
            // forget.
            "battery.current" -> {
                val i = batterySticky()
                if (i == null) fail(token, "battery state unavailable on this device")
                else resolve(token, batteryPayload(i))
            }
            "battery.available" -> {
                val i = batterySticky()
                val lvl = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                resolve(token, if (lvl >= 0) "1" else "0")
            }
            "appstate.current" -> resolve(token, if (appForeground) "active" else "background")
            "network.current" -> {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                resolve(token, networkState(cm))
            }
            "orientation.current" -> resolve(token, currentOrientation())

            // ---- Availability ----------------------------------------------
            // "Does this device have the hardware", asked before a feature is offered
            // rather than discovered from a stream that never fires.
            "torch.available" -> resolve(token,
                if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FLASH)) "1" else "0")
            "motion.available" -> resolve(token, if (sensorAvailable(args)) "1" else "0")
            "recorder.available" -> resolve(token,
                if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE)) "1" else "0")
            "tts.available" -> speech.whenReady { resolve(token, if (speech.hasVoice()) "1" else "0") }
            "ble.available" -> {
                val hasLe = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)
                val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                resolve(token, if (hasLe && mgr?.adapter != null) "1" else "0")
            }

            "orientation.lock" -> requestedOrientation = when (args) {
                "portrait"  -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else        -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            // Not a framework capability. An installed package may claim this namespace.
            // If none does the command is unknown, and saying nothing was the worst
            // answer available: a misspelt capability, one a package forgot to declare,
            // and one that simply does not exist on this platform all behaved like a
            // call that quietly worked. Route it through fail(), which reaches the app
            // when somebody is listening and the log when nobody is.
            else -> if (!packageModules.handle(token, cap, args, a)) {
                fail(token, "no capability named $cap on Android. Check the spelling, or " +
                    "whether the package that provides it declares this platform.")
            }
        }
    }

    // Permission (F2): map a Chuks kind to an Android permission string, and hold the
    // request token until the async onRequestPermissionsResult callback fires.
    // Request codes start from the clock, not 0: a process killed with a dialog up has
    // its result delivered to the NEXT process, and a counter restarting at 0 would
    // match it to a fresh request and report a denial the user never made. Codes must
    // fit in 16 bits; permissions take the low range, activity results the high one.
    private var permSeq = (android.os.SystemClock.uptimeMillis() % 8000).toInt()
    private val pendingPerms = mutableMapOf<Int, String>()   // requestCode -> engine token
    private fun permString(kind: String): String? = when (kind) {
        "camera" -> Manifest.permission.CAMERA
        "microphone" -> Manifest.permission.RECORD_AUDIO
        "location" -> Manifest.permission.ACCESS_FINE_LOCATION
        "contacts" -> Manifest.permission.READ_CONTACTS
        "calendar" -> Manifest.permission.READ_CALENDAR
        "notifications" -> Manifest.permission.POST_NOTIFICATIONS   // runtime perm on API 33+
        "photos" -> Manifest.permission.READ_MEDIA_IMAGES           // API 33+
        "activity" -> Manifest.permission.ACTIVITY_RECOGNITION      // step counter, API 29+
        "bodySensors" -> Manifest.permission.BODY_SENSORS           // heart-rate sensor
        else -> null
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grantResults)
        permInFlight = false
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        // A CameraView mounted before the app had the permission stayed dark: open it now.
        if (granted && permissions.contains(Manifest.permission.CAMERA)) cameraController?.reopen()
        pendingPermActions.remove(code)?.let { it(granted) } ?: pendingPerms.remove(code)?.let { resolve(it, if (granted) "granted" else "denied") }
        pumpPermissionQueue()
    }
    // A capability that needs a permission it does not have yet asks for it and carries
    // on when the answer comes, the way iOS's CLLocationManager does: a location read
    // made before the app asked is the prompt, not a failure. A denial fails the token.
    private val pendingPermActions = mutableMapOf<Int, (Boolean) -> Unit>()
    private fun withContactsPerm(token: String, write: Boolean, run: () -> Unit) {
        val need = if (write) arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS) else arrayOf(Manifest.permission.READ_CONTACTS)
        if (need.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) { run(); return }
        val code = ++permSeq
        pendingPermActions[code] = { ok -> if (ok) run() else fail(token, "contacts permission denied") }
        enqueuePermissionRequest(need, code)
    }
    private fun withCalendarPerm(token: String, run: () -> Unit) {
        val need = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        if (need.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) { run(); return }
        val code = ++permSeq
        pendingPermActions[code] = { ok -> if (ok) run() else fail(token, "calendar permission denied") }
        enqueuePermissionRequest(need, code)
    }
    // Permission, then the device-wide switch. Location Services off means no provider
    // will ever answer, and a watch that starts anyway waits forever with nothing to say:
    // a two-minute walk recorded from the step counter alone, with "Finding your
    // position" on screen the whole way. Refuse up front, in the words iOS uses.
    private fun withLocationPerm(token: String, run: () -> Unit) {
        val gated = {
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            if (ChuksGeo.enabled(lm)) run() else fail(token, "location services are off: turn them on in Settings")
        }
        if (hasLocationPerm()) { gated(); return }
        val code = ++permSeq
        pendingPermActions[code] = { ok -> if (ok) gated() else fail(token, "location permission denied") }
        enqueuePermissionRequest(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), code)
    }

    // Deep links (F3): the URL that launched (or re-opened) the app, delivered to any
    // linking.onurl subscribers. lastUrl is held so a subscriber that registers after launch
    // still gets it.
    private var lastUrl: String? = null
    private val notifTokens = HashSet<String>()      // Notifications.onResponse subscribers
    private val urlTokens = mutableSetOf<String>()
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        newIntent.data?.toString()?.let { url ->
            // A package waiting on this URL (an OAuth redirect) takes it; the app's own
            // deep links are everything a package did not claim.
            if (packageModules.onUrl(url)) return
            lastUrl = url; runOnUiThread { urlTokens.forEach { resolve(it, url) } }
        }
        ChuksNotif.deliverTap(newIntent)
    }

    // Media picker + camera (F3): each launch holds (engine token, camera output uri | null)
    // under its request code; the result is copied into app files and answered as "file://".
    private var mediaSeq = 9000 + (android.os.SystemClock.uptimeMillis() % 8000).toInt()
    private val pendingMedia = mutableMapOf<Int, Pair<String, android.net.Uri?>>()
    private val pendingContactPick = mutableMapOf<Int, Pair<String, String>>()
    private val pendingCompose = mutableMapOf<Int, String>()
    private val pendingVideo = HashSet<Int>()   // camera.video requests among pendingMedia
    private class PickReq(val token: String, val quality: Double, val maxSize: Int, val pathOnly: Boolean)
    private val pendingPick = mutableMapOf<Int, PickReq>()
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        pendingCompose.remove(requestCode)?.let { token -> resolve(token, ""); return }
        pendingContactPick.remove(requestCode)?.let { (token, kind) ->
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null) { fail(token, "canceled"); return }
            val canRead = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            val line = try {
                when (kind) {
                    "phone", "email" -> ChuksContacts.pickedRow(this, uri, kind)
                    else -> if (canRead) ChuksContacts.get(this, uri.lastPathSegment ?: "") else ChuksContacts.picked(this, uri)
                }
            } catch (e: Exception) { null }
            if (line != null) resolve(token, line) else fail(token, "cannot read the picked contact")
            return
        }
        pendingPick.remove(requestCode)?.let { req ->
            if (resultCode != RESULT_OK) { fail(req.token, "canceled"); return }
            val uris = ChuksMedia.picked(data)
            if (uris.isEmpty()) { fail(req.token, "canceled"); return }
            Thread {
                val lines = uris.mapNotNull { u -> try { ChuksMedia.ingest(this, u, req.quality, req.maxSize) } catch (e: Exception) { android.util.Log.w("Chuks", "mediapicker: cannot bring in $u", e); null } }.map { "file://$it" }
                runOnUiThread {
                    if (lines.isEmpty()) fail(req.token, "no media")
                    else resolve(req.token, if (req.pathOnly) lines[0].split("\t")[0] else lines.joinToString("\n"))
                }
            }.start()
            return
        }
        val entry = pendingMedia.remove(requestCode) ?: return
        val (token, outUri) = entry
        if (resultCode != RESULT_OK) { fail(token, "canceled"); return }
        val src = outUri ?: data?.data
        if (src == null) { fail(token, "no image"); return }
        val isVideo = pendingVideo.remove(requestCode)
        try {
            val dest = java.io.File(filesDir, if (isVideo) "cam_$requestCode.mp4" else "picked-$requestCode.jpg")
            contentResolver.openInputStream(src)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            resolve(token, "file://" + dest.absolutePath)
        } catch (e: Exception) { fail(token, "copy failed: ${e.message}") }
    }

    // Location (F3): fine or coarse grant is enough to read a fix; pick GPS, else the
    // network provider (LocationManager, not fused — fused needs Google Play services).
    private fun hasLocationPerm(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
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

    // A single-value sensor stream (light lux, heart-rate bpm): register `type` and
    // emit `fmt(values)` on every reading. NORMAL sampling rate (these change slowly).
    private fun startLightSensor(token: String, type: Int, fmt: (FloatArray) -> String) {
        val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(type)
        if (sensor == null) { fail(token, "sensor unavailable"); return }
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) { resolve(token, fmt(e.values)) }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        streamTeardown[token] = { sm.unregisterListener(listener) }
    }

    // Proximity: emit "near"/"far". Most phones report a binary near/far (values[0] is
    // 0 or the max range), so classify against the sensor's maximumRange.
    private fun startProximity(token: String) {
        val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY)
        if (sensor == null) { fail(token, "proximity sensor unavailable"); return }
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                resolve(token, if (e.values[0] < sensor.maximumRange) "near" else "far")
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        streamTeardown[token] = { sm.unregisterListener(listener) }
    }

    // Barometer: emit "pressureHpa,relativeAltitudeMeters". Relative altitude is measured
    // from the first reading (baseline), matching CMAltimeter's relativeAltitude on iOS.
    private fun startBarometer(token: String) {
        val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_PRESSURE)
        if (sensor == null) { fail(token, "barometer unavailable"); return }
        val baseline = FloatArray(1) { Float.NaN }
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val hpa = e.values[0]
                val alt = android.hardware.SensorManager.getAltitude(android.hardware.SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hpa)
                if (baseline[0].isNaN()) baseline[0] = alt
                resolve(token, "$hpa,${alt - baseline[0]}")
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        streamTeardown[token] = { sm.unregisterListener(listener) }
    }

    // Pedometer: TYPE_STEP_COUNTER reports cumulative steps since boot, so we baseline
    // on the first event and stream the delta since the watch started. Distance/pace are
    // estimated from steps (Android's raw sensor gives no distance); floors are always 0.
    private fun startPedometer(token: String) {
        if (android.os.Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            fail(token, "activity-recognition permission denied"); return
        }
        val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) { fail(token, "step counter unavailable"); return }
        val startNanos = System.nanoTime()
        val base = longArrayOf(-1L)          // cumulative-since-boot baseline (set on first event)
        val stride = 0.762                   // avg walking stride in meters (distance estimate)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val total = e.values[0].toLong()
                if (base[0] < 0) base[0] = total
                val steps = total - base[0]
                val dist = steps * stride
                val elapsedSec = (System.nanoTime() - startNanos) / 1e9
                val cadence = if (elapsedSec > 0) steps / elapsedSec else 0.0
                val pace = if (dist > 0) elapsedSec / dist else 0.0
                resolve(token, "$steps,$dist,$pace,$cadence,0,0")
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        streamTeardown[token] = { sm.unregisterListener(listener) }
    }

    // Biometrics (F3): the framework BiometricPrompt (API 28+, no androidx dependency).
    // onAuthenticationSucceeded -> "success"; a cancel/lockout/error -> fail(); a single
    // non-match (onAuthenticationFailed) leaves the prompt open for a retry.
    // BiometricManager.canAuthenticate() throws SecurityException when the manifest
    // lacks USE_BIOMETRIC, which is what happens when app.json declares no "faceId".
    // A capability query must never take the app down: without the permission the
    // answer is "no hardware", and the reason is logged once so the developer sees it.
    private var bioWarned = false
    private fun bioCode(authenticators: Int = -1): Int {
        val none = android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        if (android.os.Build.VERSION.SDK_INT < 29) return none
        val bm = getSystemService(android.hardware.biometrics.BiometricManager::class.java) ?: return none
        return try {
            if (authenticators >= 0 && android.os.Build.VERSION.SDK_INT >= 30) bm.canAuthenticate(authenticators)
            else @Suppress("DEPRECATION") bm.canAuthenticate()
        } catch (e: SecurityException) {
            if (!bioWarned) {
                bioWarned = true
                android.util.Log.w("Chuks", "Biometrics: USE_BIOMETRIC is not declared. Add \"faceId\" to permissions in app.json; until then every biometrics query answers unavailable.")
            }
            none
        }
    }

    private fun authenticateBiometric(token: String, reason: String) {
        if (android.os.Build.VERSION.SDK_INT < 28) { fail(token, "biometrics unavailable"); return }
        if (bioCode() == android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) {
            fail(token, "biometrics unavailable (no sensor, or \"faceId\" is not declared in app.json)"); return
        }
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

    private val secure by lazy { ChuksSecure(this) }



    private var notifId = 1
    private val audioWatchers = HashMap<String, HashSet<String>>()   // AudioPlayer id -> watch tokens
    private val recorder: ChuksRecorder by lazy { ChuksRecorder(this) { s -> for (t in recorder.watchers.toList()) resolve(t, s) } }
    private fun withMicPerm(token: String, run: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { run(); return }
        val code = ++permSeq
        pendingPermActions[code] = { ok -> if (ok) run() else fail(token, "microphone permission denied") }
        enqueuePermissionRequest(arrayOf(Manifest.permission.RECORD_AUDIO), code)
    }

    // Text-to-speech: see ChuksSpeech.kt. Watchers get every event; a tts.say token
    // is settled when its own utterance ends.
    private val ttsWatchers = HashSet<String>()
    private val speech by lazy {
        ChuksSpeech(this, { ev -> for (t in ttsWatchers.toList()) resolve(t, ev) }, { tok, err -> if (err == null) resolve(tok, "") else fail(tok, err) })
    }

    // ---- Real streams: battery / app-state / network ----
    private var appForeground = true
    // The battery reading, in one place, so watch() and current() cannot drift apart.
    private fun batteryPayload(i: Intent): String {
        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val charging = if (status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == android.os.BatteryManager.BATTERY_STATUS_FULL) 1 else 0
        // The state the intent already carried, no longer collapsed into the 1/0.
        val state = when (status) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING     -> "charging"
            android.os.BatteryManager.BATTERY_STATUS_FULL         -> "full"
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING  -> "unplugged"
            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not-charging"
            else                                                  -> "unknown"
        }
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val low = if (pm?.isPowerSaveMode == true) 1 else 0
        return "$pct,$charging,$state,$low"
    }
    private fun emitBattery(token: String, i: Intent) {
        val s = batteryPayload(i)
        runOnUiThread { resolve(token, s) }
    }
    // ACTION_BATTERY_CHANGED is sticky: registering a null receiver returns the last
    // broadcast synchronously, which is the current state with no subscription to undo.
    private fun batterySticky(): Intent? =
        registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    // The transport, in one place, for watch() and current() alike.
    private fun networkState(cm: android.net.ConnectivityManager): String {
        // "transport,reachable". Reachable is the system's own validation of the
        // network: it probes for real, so a captive portal fails it. VPN is checked
        // first because a tunnel also reports the transport it rides on.
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none,0"
        val transport = when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)       -> "vpn"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)      -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)  -> "cellular"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)  -> "ethernet"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
        val reachable = if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 1 else 0
        return "$transport,$reachable"
    }
    private fun emitNetwork(token: String, cm: android.net.ConnectivityManager) {
        val s = networkState(cm)
        runOnUiThread { resolve(token, s) }
    }

    // Whether ONE named motion sensor exists here, for Motion.available(). An unknown
    // name is "no" rather than a crash, so a typo shows up as a feature that never
    // appears instead of taking the app down.
    private fun sensorAvailable(name: String): Boolean {
        val type = when (name) {
            "accelerometer" -> android.hardware.Sensor.TYPE_ACCELEROMETER
            "gyroscope"     -> android.hardware.Sensor.TYPE_GYROSCOPE
            "magnetometer"  -> android.hardware.Sensor.TYPE_MAGNETIC_FIELD
            "proximity"     -> android.hardware.Sensor.TYPE_PROXIMITY
            "light"         -> android.hardware.Sensor.TYPE_LIGHT
            "barometer"     -> android.hardware.Sensor.TYPE_PRESSURE
            else -> return false
        }
        val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        return sm.getDefaultSensor(type) != null
    }

    override fun onResume() {
        super.onResume(); appForeground = true
        appStateTokens.toList().forEach { resolve(it, "active") }
    }
    override fun onPause() {
        super.onPause(); appForeground = false
        appStateTokens.toList().forEach { resolve(it, "background") }
        // The last reliable moment: Android makes no promise to run anything when it
        // later kills a backgrounded process.
        persistState()
    }
    private fun clipboard() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    // The system setting as 0.0..1.0. Stored as 0..255 (some devices 0..4095, which
    // the framework maps; the setting itself is 0..255 on every device we target).
    // Three decimals, as iOS answers, so a level reads the same on both.
    private fun brightnessStr(v: Double) = String.format(java.util.Locale.US, "%.3f", v)
    private fun systemBrightness(): Double {
        val v = try { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) } catch (e: Exception) { 128 }
        return (v / 255.0).coerceIn(0.0, 1.0)
    }

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

    private fun hexColor(h: String, fallback: Int = Color.TRANSPARENT): Int = hexColorStatic(h, fallback)

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
                it.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                it.settings.allowFileAccess = true                       // bundled Leaflet from file:///android_asset/
                it.settings.setAllowFileAccessFromFileURLs(true)         // the file:// page may load file:// leaflet.js
                it.settings.setAllowUniversalAccessFromFileURLs(true)    // + cross-origin OSM tiles from the file:// page
                it.setBackgroundColor(Color.parseColor("#0E1116"))
                it.webChromeClient = android.webkit.WebChromeClient()   // required for full JS support in the WebView
            }
            "Canvas" -> DrawCanvas(this)                                 // vector drawing (iOS: Core Graphics)
            "Gesture" -> FrameLayout(this).also { g ->                   // swipe / double-tap / long-press + continuous pan/pinch/rotate
                gestureIds.add(id)
                g.setTag(GTAG, "$id:gesture")                            // its own event; a T| on it is an onPress like any view's
                val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true
                    override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                        val dx = e2.x - (e1?.x ?: 0f); val dy = e2.y - (e1?.y ?: 0f)
                        val dir = if (Math.abs(dx) > Math.abs(dy)) (if (dx < 0) "left" else "right") else (if (dy < 0) "up" else "down")
                        dispatchGesture(g, "swipe:$dir"); return true
                    }
                    override fun onDoubleTap(e: MotionEvent): Boolean { dispatchGesture(g, "doubletap"); return true }
                    override fun onLongPress(e: MotionEvent) { dispatchGesture(g, "longpress") }
                    // The Gesture's onPress (its T| binding, kept in TAG): the view's own touch
                    // listener owns the touches, so the tap is recognised here, not by a click listener.
                    override fun onSingleTapUp(e: MotionEvent): Boolean { (g.getTag(TAG) as? String)?.let { if (!disabledIds.contains(id)) fire(it) }; return true }
                })
                // Continuous state, per Gesture view (physical px -> logical via /density, matching tx units).
                // The pan is tracked in RAW (screen) coordinates: a Gesture that moves itself under the
                // finger (a swipeable row bound to its own panX) has its local ev.x shifted by its own
                // translation on every move, and a pan read locally sawtooths (-30, -13, -35, -18 ...)
                // and lags the finger. iOS reads the same pan in window space for the same reason.
                var panSX = 0f; var panSY = 0f
                var vt: android.view.VelocityTracker? = null
                // The velocity tracker reads the event's own coordinates too: give it the raw ones.
                fun track(ev: MotionEvent) { val c = MotionEvent.obtain(ev); c.setLocation(ev.rawX, ev.rawY); vt?.addMovement(c); c.recycle() }
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
                                // A pan feeding shared values on one axis (a swipeable row's panX) decides
                                // its direction on the first moves; the other kinds win over a parent Scroll now.
                                if (!motionPanOneAxis(id)) g.parent?.requestDisallowInterceptTouchEvent(true)
                                panSX = ev.rawX; panSY = ev.rawY
                                vt = android.view.VelocityTracker.obtain(); track(ev)
                                if (cont.contains("pan") && !motionPan(id, 0, 0f, 0f, 0f, 0f)) dispatchGesture(g, "pan:0,0,0,0,0")
                            }
                            MotionEvent.ACTION_POINTER_DOWN -> if (cont.contains("rotate") && ev.pointerCount >= 2) {
                                rotStart = Math.toDegrees(Math.atan2((ev.getY(1) - ev.getY(0)).toDouble(), (ev.getX(1) - ev.getX(0)).toDouble())).toFloat()
                            }
                            MotionEvent.ACTION_MOVE -> {
                                track(ev)
                                if (motionPanOneAxis(id) && !motionPanLocked(id)) {
                                    // Past the slop: along our axis it is ours (the Scroll may not take it any more);
                                    // across it the Scroll intercepts and cancels this touch.
                                    val dx = Math.abs(ev.rawX - panSX); val dy = Math.abs(ev.rawY - panSY)
                                    if (Math.max(dx, dy) < dp(8)) return@setOnTouchListener true
                                    val ours = if (mPan[id]?.get(0) ?: -1 >= 0) dx >= dy else dy >= dx
                                    if (!ours) return@setOnTouchListener true
                                    motionPanLock(id); g.parent?.requestDisallowInterceptTouchEvent(true)
                                }
                                if (cont.contains("pan") && ev.pointerCount == 1 && !motionPan(id, 1, ev.rawX - panSX, ev.rawY - panSY, 0f, 0f)) {
                                    val dx = ((ev.rawX - panSX) / density).toInt(); val dy = ((ev.rawY - panSY) / density).toInt()
                                    dispatchGesture(g, "pan:1,$dx,$dy,0,0")
                                }
                                if (cont.contains("rotate") && ev.pointerCount >= 2) {
                                    val ang = Math.toDegrees(Math.atan2((ev.getY(1) - ev.getY(0)).toDouble(), (ev.getX(1) - ev.getX(0)).toDouble())).toFloat()
                                    dispatchGesture(g, "rotate:1,${(ang - rotStart).toInt()},0")
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                if (cont.contains("pan")) {
                                    track(ev); vt?.computeCurrentVelocity(1000)
                                    if (!motionPan(id, 2, ev.rawX - panSX, ev.rawY - panSY, vt?.xVelocity ?: 0f, vt?.yVelocity ?: 0f)) {
                                        val vx = ((vt?.xVelocity ?: 0f) / density).toInt(); val vy = ((vt?.yVelocity ?: 0f) / density).toInt()
                                        val dx = ((ev.rawX - panSX) / density).toInt(); val dy = ((ev.rawY - panSY) / density).toInt()
                                        dispatchGesture(g, "pan:2,$dx,$dy,$vx,$vy")
                                    }
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
                        val action = ed.getTag(CTAG) as? String ?: return
                        applyStream(engInput(action, ed.text.toString()))
                        relayout()
                    } })
                ed.setOnEditorActionListener { _, actionId, _ ->   // onSubmit (IME action) + dismiss
                    fieldSubmit[ed]?.let { hostEvent(it) }
                    // Done/Go/Send/Search close the keyboard; Next advances to the following field.
                    if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { hideKeyboard(ed); ed.clearFocus() }
                    true
                }
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
                        val action = it.getTag(CTAG) as? String ?: return
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
                        (b.getTag(CTAG) as? String)?.let { hostInput(it, mi.itemId.toString()) }
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
                        (b.getTag(CTAG) as? String)?.let { hostInput(it, mi.itemId.toString()) }; true
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
                        (c.getTag(CTAG) as? String)?.let { hostInput(it, mi.itemId.toString()) }; true
                    }
                    pm.show(); true
                }
            }
            "Slider" -> SeekBar(this).also { sb ->
                sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return                       // ignore programmatic setProgress (the controlled sync)
                        val action = sb.getTag(CTAG) as? String ?: return
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
                sc.viewTreeObserver.addOnScrollChangedListener { motionScrolled(id, sc.scrollY); if (pushViewport()) relayout(); updateVideoVisibility(); reportScroll(id, sc.scrollY) }
                sc.viewTreeObserver.addOnGlobalLayoutListener { updateVideoVisibility() }   // attach on-screen videos on the initial (static) layout too
                horizScrollIds.remove(id)
                if (listScroll == null) { listScroll = sc; scrollId = id; listHoriz = false } }
            "HScroll" -> SnapHScrollView(this).also { sc ->   // horizontal list (carousel); snaps when paging=1
                N.ySetF(n, 34, 2f)   // Yoga overflow:scroll so content is sized at its full width
                sc.isFillViewport = false
                sc.isHorizontalScrollBarEnabled = false
                sc.viewTreeObserver.addOnScrollChangedListener { motionScrolled(id, sc.scrollX); if (pushViewport()) relayout(); reportScroll(id, sc.scrollX) }
                horizScrollIds.add(id)
                if (listScroll == null) { listScroll = sc; scrollId = id; listHoriz = true } }
            "Modal" -> FrameLayout(this).also {         // full-screen dimmed scrim; content laid out inside
                it.setBackgroundColor(Color.argb(128, 0, 0, 0))
                it.visibility = View.GONE                // shown when mvis=1
                modalIds.add(id)
            }
            "Sheet" -> SheetLayout(this).also { sl ->
                sl.id = id; sl.visibility = View.GONE
                N.ySetF(n, 1, 0f); N.ySetF(n, 2, 4f)     // justify flex-start, align stretch: children fill the box
                sheetIds.add(id)
            }
            "Popover" -> FrameLayout(this).also {       // an anchored Modal: transparent overlay, content
                it.visibility = View.GONE                // laid out at its natural size at the origin and
                N.ySetF(n, 1, 0f); N.ySetF(n, 2, 0f)     // moved beside the anchor by placePopover
                modalIds.add(id); popoverIds.add(id)
            }
            // A kind the framework does not know may be one a package claims. Only then
            // do we look: an unknown kind is otherwise a plain container, exactly as
            // before, so an app with no view packages pays one map miss.
            else -> {
                val factory = ChuksPackageModules.views()[kind]
                if (factory != null) {
                    // One binding per view, carrying the node id, so emit() reaches this
                    // node's handlers and not another instance's.
                    val binding = ViewBinding(id)
                    val pv = factory(binding)
                    packageViewHosts[id] = binding
                    packageViews[id] = pv
                    // A view that sizes itself is measured by Yoga during layout, exactly
                    // like a Text. Only a LEAF may carry a measure func, which is also true
                    // of the framework's own Text.
                    if (pv is ChuksMeasurableView) { measureViews[n] = pv; N.ySetTextMeasure(n) }
                    pv.view
                } else FrameLayout(this).also { it.clipChildren = false }   // a child moved past the edge stays visible, as on iOS; `overflow: hidden` clips
            }
        }
        views[id] = v
        ynodes[id] = n
        needsFrame.add(id); lastFrame.remove(id)   // a fresh view must get its frame next relayout
        if (v is TextView && v !is Button && v !is EditText) { textNodes[n] = v; N.ySetTextMeasure(n) }
    }

    private val textNodes = HashMap<Long, TextView>()
    /** Self-sizing package views, keyed by Yoga node exactly as textNodes is: one measure
     *  callback comes back from Yoga and it asks whichever table owns the node. */
    private val measureViews = HashMap<Long, ChuksMeasurableView>()
    /** Children a package view placed itself: child id -> the package view's id, so the
     *  same package takes it out again. */
    private val containerPlaced = HashMap<String, String>()

    // pending visual state per view (bg color + radius) -> a GradientDrawable
    private val bgColor = HashMap<String, Int>()
    private val bgRadius = HashMap<String, Float>()
    private val blurAmt = HashMap<String, Int>()           // id -> 0..100 backdrop-blur strength
    private val blurTint = HashMap<String, String>()       // id -> light | dark | system
    private val gradColors = HashMap<String, IntArray>()   // id -> linear-gradient colors
    private val gradAngle = HashMap<String, Int>()         // id -> angle in degrees (0 = top to bottom)
    private val gradStops = HashMap<String, FloatArray>()  // id -> 0..1 positions matching the colors
    private var appAppearance: String = "auto"   // the app's own light/dark choice
    private val glassIds = HashSet<String>()   // Liquid Glass: no backdrop blur on Android views, so a frosted panel over the bg

    // What a glass surface is painted with here. Android has no backdrop blur inside a
    // window, and a scrim that is truly translucent shows the content under it crisp:
    // a floating tab bar at 22% white had the page's last lines running straight
    // through its labels. iOS already has the rule for a device without the material:
    // the `bg` the app gave the surface is the fallback. Same rule here, nearly opaque
    // (a hint of what scrolls under it, never legible), and theme-aware when the app
    // gave no bg, since a white haze on a dark screen is a bug of its own.
    private fun glassSurface(id: String): Int {
        val base = bgColor[id] ?: (if (osDark()) Color.parseColor("#1C1C1E") else Color.WHITE)
        return Color.argb(240, Color.red(base), Color.green(base), Color.blue(base))
    }
    private val pressOpacity = HashMap<String, Float>()   // id -> Pressable active alpha (0-1)
    private val longPressActions = HashMap<String, String>()   // id -> onLongPress action
    private val pressInActions = HashMap<String, String>()     // id -> onPressIn action
    private val pressOutActions = HashMap<String, String>()    // id -> onPressOut action
    private val disabledIds = HashSet<String>()                // ids whose disabled=1 (block fire)
    private val a11yIds = HashSet<String>()                    // ids carrying any a11y key (reset on reuse)
    private val a11yFocusable = HashMap<String, Boolean>()     // id -> the view's isFocusable before a11y made it focusable
    private val longDelayMs = HashMap<String, Long>()          // id -> onLongPress hold time (ms)
    private val mediaLoad = HashMap<String, String>()          // id -> onLoad action (Image)
    private val mediaError = HashMap<String, String>()         // id -> onError action (Image)
    private val mediaEnd = HashMap<String, String>()           // id -> onEnd action (Video)
    private val mediaProgress = HashMap<String, String>()      // id -> onProgress action (Video)
    private val imageTint = HashMap<String, Int>()             // id -> Image tintColor
    private val imageBlur = HashMap<String, Float>()           // id -> Image blur radius (px)
    // The color a fresh TextView gets: the default a reused label resets to when its
    // new role emits no `fg` (a colorless Text uses the theme default).
    // The colour a Text, Button or field gets when the app gave it none. iOS uses
    // `.label`, which resolves against the appearance the APP is rendering in, because
    // the host applies the app's theme choice to the window. This used to come from the
    // Activity theme instead, so a colourless Button was white on a light app whenever
    // the device happened to be in dark mode: invisible. Same two colours as `.label`.
    private fun defaultTextColor(): Int = if (appIsDark()) Color.WHITE else Color.BLACK
    private fun appIsDark(): Boolean = when (appAppearance) { "dark" -> true; "light" -> false; else -> osDark() }
    // Ids the app gave an explicit text colour. On an appearance change the rest are
    // repainted to the new default, which is what a dynamic colour does on iOS for free.
    private val explicitFg = HashSet<String>()
    private fun repaintDefaultText() {
        val c = defaultTextColor()
        for ((id, v) in views) {
            if (explicitFg.contains(id)) continue
            if (v is TextView && v !is EditText) v.setTextColor(c)
        }
    }
    private val imageOpChain = HashMap<String, String>()       // id -> Image GPU op-chain (JSON)
    private val imageOrigBmp = HashMap<String, android.graphics.Bitmap>()   // id -> pre-op bitmap, for re-applying a changed chain
    private val imageSrc = HashMap<String, String>()           // id -> local image source (asset name or file://), for sized re-decode
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
    // A font by family name, from assets: `Name.ttf`, else `Name.otf` (Gill Sans and
    // many licensed families ship as OpenType). iOS matches the same name as the
    // PostScript name, so one `font:` value resolves on both.
    private fun iconFont(name: String): android.graphics.Typeface =
        iconFonts.getOrPut(name) {
            try { android.graphics.Typeface.createFromAsset(assets, "$name.ttf") }
            catch (e: RuntimeException) { android.graphics.Typeface.createFromAsset(assets, "$name.otf") }
        }

    // ── Accessibility ──────────────────────────────────────────────────────────
    // Six style keys say what TalkBack reads and how it treats a node. The role is the
    // widget class TalkBack announces ("button", "check box", "switch"), plus a role
    // description for the roles that have no widget class (link, image, heading, tab,
    // alert); the state goes through the node info (enabled, selected, checkable +
    // checked) and, for expanded/collapsed/busy, the state description. A container given
    // a role becomes ONE focusable node: with no label of its own TalkBack reads its
    // descendants' text, so a Pressable wrapping an icon and a Text says "Add to cart,
    // button" unasked. Mirrors the iOS host key for key.
    class A11ySpec { var label = ""; var hint = ""; var role = ""; var live = ""; var states: List<String> = emptyList(); var hidden = false }
    private val roleClass = mapOf(
        "button" to "android.widget.Button", "link" to "android.widget.TextView", "image" to "android.widget.ImageView",
        "header" to "android.widget.TextView", "switch" to "android.widget.Switch", "checkbox" to "android.widget.CheckBox",
        "radio" to "android.widget.RadioButton", "tab" to "android.widget.Button", "search" to "android.widget.EditText",
        "adjustable" to "android.widget.SeekBar", "alert" to "android.widget.TextView", "text" to "android.widget.TextView")
    private val roleWord = mapOf("link" to "link", "image" to "image", "header" to "heading", "tab" to "tab", "alert" to "alert")

    private fun applyA11y(id: String, v: View, a: A11ySpec) {
        a11yIds.add(id)
        if (a.hidden) {
            v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            v.accessibilityDelegate = null
            return
        }
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        v.contentDescription = if (a.label.isEmpty()) null else a.label
        v.accessibilityLiveRegion = when (a.live) {
            "polite" -> View.ACCESSIBILITY_LIVE_REGION_POLITE
            "assertive" -> View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            else -> View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        if (a.role == "none") {
            v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            v.accessibilityDelegate = null
            return
        }
        // A role or a label makes the node one focusable thing for TalkBack; a plain
        // container with only a hint or a live region keeps its children separate.
        val asElement = a.role.isNotEmpty() || a.label.isNotEmpty()
        if (asElement && v is ViewGroup) {
            if (!a11yFocusable.containsKey(id)) a11yFocusable[id] = v.isFocusable
            v.isFocusable = true
            if (Build.VERSION.SDK_INT >= 28) v.isScreenReaderFocusable = true
        }
        if (Build.VERSION.SDK_INT >= 28) v.isAccessibilityHeading = (a.role == "header")
        val cls = roleClass[a.role]; val word = roleWord[a.role]
        val states = a.states; val hint = a.hint
        v.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (cls != null) info.className = cls
                if (word != null) info.extras.putCharSequence("AccessibilityNodeInfo.roleDescription", word)
                val described = ArrayList<String>()
                for (st in states) when (st) {
                    "dis" -> info.isEnabled = false
                    "sel" -> info.isSelected = true
                    "chk" -> { info.isCheckable = true; info.isChecked = true }
                    "unchk" -> { info.isCheckable = true; info.isChecked = false }
                    "mixed" -> { info.isCheckable = true; described.add("partially checked") }
                    "exp" -> described.add("expanded")
                    "col" -> described.add("collapsed")
                    "busy" -> described.add("busy")
                }
                if (Build.VERSION.SDK_INT >= 30) { if (described.isNotEmpty()) info.stateDescription = described.joinToString(", ") }
                else if (described.isNotEmpty()) info.contentDescription = listOfNotNull(info.contentDescription?.toString(), described.joinToString(", ")).joinToString(", ")
                if (hint.isNotEmpty()) {
                    if (Build.VERSION.SDK_INT >= 28) info.tooltipText = hint
                    else info.contentDescription = listOfNotNull(info.contentDescription?.toString(), hint).joinToString(", ")
                }
            }
        }
    }
    private fun descendantText(v: View): String {
        val parts = ArrayList<String>()
        fun walk(x: View) {
            if (x.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) return
            if (x is TextView) { if (x.text.isNotEmpty()) parts.add(x.text.toString()); return }
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        return parts.joinToString(" ")
    }
    private fun resetA11y(id: String, v: View) {
        a11yIds.remove(id)
        v.accessibilityDelegate = null
        v.contentDescription = null
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        v.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        if (Build.VERSION.SDK_INT >= 28) { v.isAccessibilityHeading = false; v.isScreenReaderFocusable = false }
        a11yFocusable.remove(id)?.let { v.isFocusable = it }
    }

    // A text field's style is applied with its watcher muted: EditText re-sets its own
    // text when the input type, the transformation or single-line mode changes, and
    // the watcher would report that as an edit. The engine then re-rendered, re-styled,
    // and heard the same "edit" again: four change events per keystroke, and with a
    // controlled value that disagreed, an unbounded loop. Only a person's typing is a
    // change; setFieldValue keeps its own mute for the mirrored value.
    private fun style(id: String, s: String) {
        val quiet = views[id] is EditText
        val was = fieldSelfSet
        if (quiet) fieldSelfSet = true
        try { styleApply(id, s) } finally { if (quiet) fieldSelfSet = was }
    }
    private fun styleApply(id: String, s: String) {
        val n = ynodes[id] ?: return
        val v = views[id] ?: return
        // style() always receives the FULL style, so drop any stale visual state for
        // this id first. Node ids are reused when a screen swaps in place; without
        // this a previous bordered/filled element leaves its border/bg under the new
        // one (e.g. a stray outline around a row that later holds a Switch).
        bgColor.remove(id); bgRadius.remove(id); borderW.remove(id); borderC.remove(id); pressOpacity.remove(id); textWidthPx.remove(id); explicitHeight.remove(id)
        (views[id] as? SheetLayout)?.surfaceOverride = null   // a Sheet's bg is its surface; back to the platform's unless set again
        motionUnbindNode(id)   // bindings and sources are style keys: absent means none
        enterSpec.remove(id); exitSpec.remove(id); layoutSpec.remove(id)   // transitions: absent means none
        borderStyleM.remove(id); bwSideM.remove(id)
        // Also drop stale LAYOUT geometry (Yoga width/height/grow/basis/padding/…): the visual
        // removes above didn't touch the Yoga node, so a reused node kept its previous role's
        // sizing (e.g. a content-sized History container inheriting a stale explicit height ->
        // taller than its content, or the first flex child not taking its grow share). The
        // incoming style is complete, so clearing first + re-applying below fully re-describes
        // the node. minimumHeight/Width mirror the `h`/`w` cases and are re-set there if present.
        N.yResetStyle(n)
        v.minimumHeight = 0; v.minimumWidth = 0
        // A leaf native control's Yoga size came from make() (its measured size) and the
        // reset just cleared it; put it back before the keys, so an explicit w/h wins.
        if (v is Switch) {
            val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            v.measure(spec, spec)
            N.ySetF(n, 5, v.measuredWidth.toFloat()); N.ySetF(n, 6, v.measuredHeight.toFloat())
        }
        // Text props are set conditionally below (ta/nlines only if present), so a reused
        // TextView would keep the previous role's alignment/line-clamp when the new role
        // relies on defaults (e.g. a reused label staying centered). Reset to make()'s
        // defaults; the loop re-applies whatever this role sets. (size/weight are always
        // re-applied from locals, so they need no reset.)
        (v as? TextView)?.let { it.gravity = Gravity.CENTER_VERTICAL; it.maxLines = Integer.MAX_VALUE; it.ellipsize = null }
        // Reused-node completeness: props style() applies only-when-present must be reset
        // here or a reused node inherits the previous role's value (stale-state bug class,
        // docs/ui-update-model-vs-rn.md). Text size/weight/decoration/tracking are already
        // re-applied every pass (post-loop TextView block); transform + alpha are reset in
        // the post-loop block where `anim` is known so a reset never fights an animation.
        v.elevation = 0f                                   // shadow
        v.translationZ = 0f                                // z
        v.clipToOutline = false                            // overflow / TextureView+ImageView radius outline
        if (v.javaClass == FrameLayout::class.java) (v as FrameLayout).clipChildren = false   // overflow (a plain container; scrolls and sheets keep clipping)
        v.isEnabled = true                                 // dis / edit
        disabledIds.remove(id)                             // dis (alpha handled post-loop)
        if (a11yIds.contains(id)) resetA11y(id, v)         // al/ah/ar/as/ax/av
        blurAmt.remove(id); blurTint.remove(id)            // backdrop blur (drawn as a scrim)
        gradColors.remove(id); gradAngle.remove(id); gradStops.remove(id)   // linear gradient
        glassIds.remove(id)                                // glass frosted panel
        pressOpacity.remove(id); longDelayMs.remove(id)    // Pressable active-alpha / long-press
        if (!modalIds.contains(id)) v.visibility = View.VISIBLE   // hidden/mvis (modal drives its own)
        (v as? ImageView)?.let { it.clearColorFilter(); it.scaleType = ImageView.ScaleType.CENTER_CROP }   // tint/filt/rmode
        imageTint.remove(id); imageBlur.remove(id)         // Image tint/blur (pixels re-driven on load/recycle)
        // Option A: close the Android-only reset gaps the coverage guard tracked
        // (fg / leading / input config), so a reused node inherits none of them. iOS
        // resets these in resetPaintStyle; Android now matches. The loop below re-applies
        // whatever the new role sets.
        (v as? TextView)?.let {
            it.setTextColor(defaultTextColor())            // fg: a colorless Text uses the default
            it.setLineSpacing(0f, 1f)                      // leading: back to the font's natural line height
        }
        explicitFg.remove(id)                              // the loop below records it again if fg is set
        if (v is SeekBar) { v.progressTintList = null; v.thumbTintList = null }              // fg on a Slider
        else if (v is ProgressBar) { v.progressTintList = null; v.indeterminateTintList = null }   // fg on Progress
        (v as? EditText)?.let {                            // input config: kbt/ret/edit/acap/acor/maxlen/sec
            it.inputType = android.text.InputType.TYPE_CLASS_TEXT
            it.transformationMethod = null
            it.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            it.filters = arrayOf()
            it.isEnabled = true; it.isFocusable = true; it.isFocusableInTouchMode = true
            it.setSingleLine()
        }
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
        var isSecure = false   // password field this pass: keeps the mask through the post-loop transformationMethod set
        // accessibility: collected across the loop, applied together after it (a role and a
        // state combine into one node-info delegate)
        val a11y = A11ySpec(); var hasA11y = false
        for (kv in s.split(";")) {
            if (kv.isEmpty()) continue
            val p = kv.split("="); if (p.size != 2) continue
            val k = p[0]; val vl = p[1]
            val f = vl.toFloatOrNull() ?: 0f
            when (k) {
                "al" -> { a11y.label = chuksUnescapeStyle(vl); hasA11y = true }    // what it is
                "ah" -> { a11y.hint = chuksUnescapeStyle(vl); hasA11y = true }     // what it does
                "ar" -> { a11y.role = vl; hasA11y = true }                          // button|link|image|header|...
                "as" -> { a11y.states = vl.split(","); hasA11y = true }             // dis,sel,chk,...
                "ax" -> { a11y.hidden = (vl == "1"); hasA11y = true }              // decoration
                "av" -> { a11y.live = vl; hasA11y = true }                          // polite|assertive
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
                "bg" -> if (v is SheetLayout) v.surfaceOverride = hexColor(vl)   // the panel's surface, not the backdrop
                else if (v is Switch) {
                    v.trackTintList = ColorStateList.valueOf(hexColor(vl))   // on-track = primary
                    v.thumbTintList = ColorStateList.valueOf(switchThumb[id] ?: Color.WHITE)   // white thumb, like iOS (unless thumbColor set)
                } else bgColor[id] = hexColor(vl)
                "swtc" -> if (v is Switch) {                                              // Switch thumb (knob) color
                    val c = hexColor(vl); switchThumb[id] = c
                    v.thumbTintList = ColorStateList.valueOf(c)
                }
                "r" -> bgRadius[id] = dpf(f)
                "bw" -> borderW[id] = dpf(f)
                "bc" -> borderC[id] = hexColor(vl)
                "bstyle" -> if (vl == "dashed" || vl == "dotted") borderStyleM[id] = vl
                "bwt" -> (bwSideM.getOrPut(id) { FloatArray(4) { -1f } })[0] = dpf(f)
                "bwr" -> (bwSideM.getOrPut(id) { FloatArray(4) { -1f } })[1] = dpf(f)
                "bwb" -> (bwSideM.getOrPut(id) { FloatArray(4) { -1f } })[2] = dpf(f)
                "bwl" -> (bwSideM.getOrPut(id) { FloatArray(4) { -1f } })[3] = dpf(f)
                "opacity" -> opacity = f / 100f
                "mo" -> motionBind(id, chuksUnescapeStyle(vl))
                "en" -> { val t = chuksUnescapeStyle(vl); enterSpec[id] = t; if (needsFrame.contains(id)) pendingEnter[id] = t }   // mounted this batch: enters once framed
                "ex" -> exitSpec[id] = chuksUnescapeStyle(vl)
                "lt" -> layoutSpec[id] = chuksUnescapeStyle(vl)
                "mpx" -> mPan[id] = intArrayOf(vl.toIntOrNull() ?: -1, mPan[id]?.get(1) ?: -1)
                "mpy" -> mPan[id] = intArrayOf(mPan[id]?.get(0) ?: -1, vl.toIntOrNull() ?: -1)
                "msc" -> vl.toIntOrNull()?.let { mScroll[id] = it }
                "tx" -> { tx = f; hasTransform = true }
                "ty" -> { ty = f; hasTransform = true }
                "rot" -> { rot = f; hasTransform = true }
                "sc" -> { sc = f / 100f; hasTransform = true }
                "anim" -> animMs = f.toInt()
                "ez" -> animEz = vl
                "shadow" -> v.elevation = dpf(if (f >= 3f) 12f else if (f == 2f) 6f else 3f)
                "bkblur" -> blurAmt[id] = vl.toIntOrNull() ?: 60
                "bktint" -> blurTint[id] = vl
                "grad" -> {
                    val cs = vl.split(",").filter { it.isNotEmpty() }.map { hexColor(it) }
                    if (cs.size >= 2) gradColors[id] = cs.toIntArray() else gradColors.remove(id)
                }
                "gradang" -> gradAngle[id] = vl.toIntOrNull() ?: 0
                "gradstop" -> {
                    val ps = vl.split(",").mapNotNull { it.toFloatOrNull() }
                    if (ps.isNotEmpty()) gradStops[id] = ps.map { it / 100f }.toFloatArray() else gradStops.remove(id)
                }
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
                "fg" -> { val c = hexColor(vl); explicitFg.add(id)
                    (v as? TextView)?.setTextColor(c); (v as? EditText)?.setTextColor(c)
                    // A field's placeholder must follow its text color. The platform default
                    // hint color comes from the Activity theme, not from the field's own
                    // colors, so on a light surface under a dark theme (or the reverse) the
                    // hint renders invisible. iOS's UITextField.placeholder derives from the
                    // text color for the same reason; match it at ~45% alpha.
                    (v as? EditText)?.setHintTextColor(Color.argb(115, Color.red(c), Color.green(c), Color.blue(c)))
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
                "nlines" -> (v as? TextView)?.let {   // Text: cap lines; default to a tail ellipsis (as UIKit does), an explicit `ellip` overrides
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
                    isSecure = (vl == "1")
                    if (isSecure) {
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
                    if (!vis && v.visibility == View.VISIBLE) animateOverlayOut(id, v as FrameLayout)   // a snapshot slides/fades out
                    v.visibility = if (vis) View.VISIBLE else View.GONE
                    if (vis) { activeModal = id; v.bringToFront() } else if (activeModal == id) activeModal = null
                    if (!vis) popoverArrowViews.remove(id)?.let { (it.parent as? ViewGroup)?.removeView(it) }
                    root.requestLayout()
                }
                "shsnap" -> (v as? SheetLayout)?.let { it.snapSpec = vl.split(",") }
                "shidx" -> (v as? SheetLayout)?.let { sheetSetIndex(id, it, f.toInt()) }
                "shbd" -> (v as? SheetLayout)?.let { it.backdrop = (vl == "1") }
                "shptc" -> (v as? SheetLayout)?.let { it.panToClose = (vl == "1") }
                "shhdl" -> (v as? SheetLayout)?.let { it.showHandle = (vl == "1") }
                "shkb" -> (v as? SheetLayout)?.let { it.keyboard = vl }
                "panc" -> popoverAnchor[id] = vl                                // Popover: the anchor node
                "pplc" -> popoverPlace[id] = vl                                 // Popover: preferred side
                "pgap" -> popoverGap[id] = dp(f.toInt())                        // Popover: gap to the anchor
                "parw" -> if (vl == "1") popoverArrow.add(id) else popoverArrow.remove(id)
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
            // Case transform. A password field keeps its mask (isSecure) rather than
            // being unmasked by the default txform=none -> null.
            it.transformationMethod = if (isSecure) android.text.method.PasswordTransformationMethod.getInstance() else when (txform) {
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
        if (hasA11y) applyA11y(id, v, a11y)
        // Apply transform + opacity, animated natively when `anim` is set (a
        // ViewPropertyAnimator interpolates off the main render loop). Visual only.
        // A node with bindings keeps its own paint values under them: the bound props
        // replace these, the rest stay (a static tx beside a bound ty).
        if (mBindings.containsKey(id)) mStatic[id] = floatArrayOf(tx, ty, sc, rot, opacity ?: 1f) else mStatic.remove(id)
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
        } else {
            // No transform/opacity/anim keys this role: clear any left by a previous role on
            // a reused node. Safe here because an animating node always carries the transform
            // keys or `anim`, so this branch never interrupts an animation.
            if (v.translationX != 0f || v.translationY != 0f || v.scaleX != 1f || v.scaleY != 1f || v.rotation != 0f) {
                v.translationX = 0f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f; v.rotation = 0f
            }
            if (v.alpha != 1f && !disabledIds.contains(id)) v.alpha = 1f
        }
        // bg + radius + border -> GradientDrawable (a large radius clamps to a pill)
        val hasPerCorner = rcTL >= 0f || rcTR >= 0f || rcBR >= 0f || rcBL >= 0f
        val side = bwSideM[id]
        val gcs = gradColors[id]
        if (gcs != null && v !is TextureView) {
            // A gradient owns the whole background: fill, corners and stroke together, the
            // same way the solid path below does, so radius and border keep working on it.
            val radii = if (hasPerCorner) {
                val tl = if (rcTL >= 0f) rcTL else 0f; val tr = if (rcTR >= 0f) rcTR else 0f
                val br = if (rcBR >= 0f) rcBR else 0f; val bl = if (rcBL >= 0f) rcBL else 0f
                floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
            } else null
            v.background = LinearGradientDrawable(gcs, gradStops[id], gradAngle[id] ?: 0,
                radii, bgRadius[id] ?: 0f, borderW[id] ?: 0f,
                borderC[id] ?: Color.parseColor("#334155"))
        } else if (side != null && v !is TextureView) {   // per-side border: a custom drawable draws the set edges
            val bc = borderC[id] ?: Color.parseColor("#334155")
            v.background = SideBorderDrawable(bgColor[id] ?: Color.TRANSPARENT, bgRadius[id] ?: 0f,
                if (side[0] >= 0f) side[0] else 0f, if (side[1] >= 0f) side[1] else 0f,
                if (side[2] >= 0f) side[2] else 0f, if (side[3] >= 0f) side[3] else 0f, bc)
        } else if (bgColor.containsKey(id) || bgRadius.containsKey(id) || borderW.containsKey(id) || glassIds.contains(id) || blurAmt.containsKey(id) || hasPerCorner) {
            val gd = GradientDrawable()
            gd.setColor(when {
                blurAmt.containsKey(id) -> blurScrim(id)                       // Blur -> tinted scrim
                glassIds.contains(id) -> glassSurface(id)                      // frosted panel over the app's own bg
                else -> bgColor[id] ?: Color.TRANSPARENT
            })
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
            else if (glassIds.contains(id)) gd.setStroke(dpf(1f).toInt(), if (osDark()) Color.argb(40, 255, 255, 255) else Color.argb(18, 0, 0, 0))   // subtle glass rim
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
        // Bound props win over the style line just applied: a re-render never resets
        // what a shared value drives.
        if (mBindings.containsKey(id)) motionApplyNode(id)
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
        // A package view's "text" is its props, as JSON. Layout and background have
        // already been applied by the framework; this is the package's own half.
        packageViews[id]?.let { it.apply(ChuksArgs(t, "view", "0", this)); return }
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
        if (mapIds.contains(id)) {                                              // Map's "text" is "lat,lng,zoom" [ "|" route ] -> OSM
            val segs = t.split("|")
            // Route polyline: "p1lat,p1lng p2lat,p2lng ...". Render via Leaflet + fit to it.
            if (segs.size >= 2 && segs[1].isNotEmpty()) {
                val pts = segs[1].split(" ").mapNotNull {
                    val ll = it.split(","); if (ll.size == 2) "[${ll[0]},${ll[1]}]" else null
                }.joinToString(",")
                // Leaflet is BUNDLED (webassets/ -> file:///android_asset/), not from a CDN:
                // loadDataWithBaseURL spoofs the page origin, and cross-origin CDN script
                // requests from it silently hang. Loading Leaflet same-origin from the
                // file:// base is instant; OSM tiles are cross-origin images, allowed via
                // setAllowUniversalAccessFromFileURLs. No '#' in the HTML (it truncates the
                // data at the first one): CSS class not id, rgb() not hex.
                val html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                    "<link rel='stylesheet' href='leaflet.css'/>" +
                    "<style>html,body,.mp{height:100%;width:100%;margin:0;background:rgb(14,17,22)}</style></head><body><div class='mp'></div><script>" +
                    "var ls=document.createElement('script');ls.src='leaflet.js';ls.onerror=function(){console.log('leaflet local FAILED')};" +
                    "ls.onload=function(){try{var pts=[$pts];" +
                    "var map=L.map(document.getElementsByClassName('mp')[0],{zoomControl:false,attributionControl:false});" +
                    "L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19}).addTo(map);" +
                    "var line=L.polyline(pts,{color:'rgb(46,212,122)',weight:5}).addTo(map);" +
                    "if(pts.length){L.circleMarker(pts[0],{radius:6,color:'rgb(46,212,122)',fillColor:'rgb(46,212,122)',fillOpacity:1}).addTo(map);" +
                    "L.circleMarker(pts[pts.length-1],{radius:6,color:'rgb(255,255,255)',fillColor:'rgb(46,212,122)',fillOpacity:1}).addTo(map);}" +
                    "function fit(){map.invalidateSize();if(pts.length>1)map.fitBounds(line.getBounds(),{padding:[26,26]});else map.setView(pts[0],16);}" +
                    "fit();setTimeout(fit,300);}catch(e){console.log('map err '+e);}};document.head.appendChild(ls);</script></body></html>"
                (views[id] as? android.webkit.WebView)?.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
                return
            }
            val p = segs[0].split(",")
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
        (v.getTag(GTAG) as? String)?.let { hostInput(it, g) }   // the Gesture's own event; its T| binding, if any, is an onPress
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
            (b.getTag(CTAG) as? String)?.let { hostInput(it, iso) }
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

    /** A self-sizing package view says its content changed. yMarkDirty is a no-op on a
     *  node with no measure func, so this costs nothing for a view that does not size
     *  itself, and the relayout is the one every other change goes through. */
    private fun remeasure(id: String) {
        val n = ynodes[id] ?: return
        N.yMarkDirty(n)
        relayout()
    }

    // Yoga measure callback (from jni.cpp during layout): measure the TextView at the width
    // Yoga resolved, so text wraps to its container. wmode: 0 undefined, 1 exactly, 2 at-most.
    private fun measureTextNode(node: Long, width: Float, wmode: Int): Long {
        measureViews[node]?.let { mv ->
            // Unconstrained arrives as mode 0 (undefined); tell the package with a
            // negative width rather than a NaN it has to test for.
            val sz = mv.measure(if (wmode == 0) -1f else width)
            // A package that answers with a nonsense number would abort Yoga rather than
            // draw wrong, so clamp: the view is a guest, and a guest's arithmetic is not
            // trusted.
            val w = if (sz.width.isFinite()) maxOf(0f, sz.width) else 0f
            val h = if (sz.height.isFinite()) maxOf(0f, sz.height) else 0f
            return (w.toInt().toLong() shl 32) or (h.toInt().toLong() and 0xffffffffL)
        }
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
        private fun col(h: String) = hexColorStatic(h)
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
        if (modalIds.contains(id) || sheetIds.contains(id)) { root.addView(child); return }   // overlays mount on root, above the app
        if (parent == scrollId) contentId = id
        val pn = ynodes[parent] ?: return
        // A package view may own where its children go: its root can be a decoration layer,
        // a mask, or someone else's SDK surface, and a child dropped straight into it lands
        // in the wrong layer. Only the layer is the package's to choose; the frame is still
        // the framework's, computed in the package root's coordinate space.
        val container = packageViews[parent] as? ChuksContainerView
        if (container != null) {
            container.insertChild(child, index)
            containerPlaced[id] = parent
        } else {
            val pv = (views[parent] as? SheetLayout)?.panel ?: (views[parent] as? ViewGroup ?: return)   // a Sheet's content lives in its panel
            val base = if (bgImageViews.containsKey(parent)) 1 else 0   // keep an ImageBackground's bg image at the back
            (child.parent as? ViewGroup)?.removeView(child)   // a MOVE (a keyed child at a new position): Android will not re-add a parented view
            pv.addView(child, minOf(index + base, pv.childCount))
        }
        val cn = ynodes[id]!!
        // Yoga aborts if `cn` still has an owner (a hot reload can re-emit an insert for a
        // node already parented). Detach it from its old owner first (reconciler re-parent).
        val owner = N.yOwner(cn); if (owner != 0L) N.yRemove(owner, cn)
        N.yInsert(pn, cn, minOf(index, N.yChildCount(pn)))
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
        // The live request, re-issued whenever a control changes it (trap: a Camera2
        // setting is a field on the repeating request, not a device property).
        private var previewReq: CaptureRequest.Builder? = null
        private var chars: CameraCharacteristics? = null
        private var flash = "off"          // off | on | auto at capture; torch = light on
        private var zoomRatio = 1f
        private var afRegion: android.hardware.camera2.params.MeteringRectangle? = null

        init {
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { open() }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { close(); return true }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
            if (texture.isAvailable) open()
        }

        /** After a permission grant: the view is there, the device is not. */
        fun reopen() { if (device == null && texture.isAvailable) open() }

        fun setFacing(f: String) {
            val want = if (f == "front") "front" else "back"
            if (want == facing) return
            facing = want
            if (texture.isAvailable) { close(); open() }
        }

        private fun pickCamera(): String? {
            val want = if (facing == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            // A device can list several cameras of one facing: the main one, plus
            // ultra-wide, tele, and logical wrappers, some with no flash and no zoom.
            // Manufacturers number the main back camera 0 and the main front 1, so among
            // the wanted facing prefer the lowest-numbered id, which is the main lens.
            // (An emulator can list a limited "10" before the full "0".)
            val matches = camMgr.cameraIdList.filter { camMgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            val best = matches.minByOrNull { it.toIntOrNull() ?: Int.MAX_VALUE }
            return best ?: camMgr.cameraIdList.firstOrNull()
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
            this.chars = chars
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
                        previewReq = req
                        applyControls(req)
                        try { s.setRepeatingRequest(req.build(), null, bgH) } catch (e: Exception) {}
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                }, bgH)
            } catch (e: Exception) {}
        }

        // ---- controls -----------------------------------------------------------
        private fun applyControls(req: CaptureRequest.Builder) {
            val c = chars
            if (c != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    req.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
                } else {
                    // Below 30 zoom is a crop of the active array, centred.
                    val rect = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    if (rect != null) {
                        val w = (rect.width() / zoomRatio).toInt(); val h = (rect.height() / zoomRatio).toInt()
                        val l = rect.centerX() - w / 2; val t = rect.centerY() - h / 2
                        req.set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(l, t, l + w, t + h))
                    }
                }
            }
            val hasFlash = c?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (hasFlash) {
                if (flash == "torch") { req.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON); req.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH) }
                else { req.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF); req.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON) }
            }
            afRegion?.let { r ->
                req.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(r))
                req.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(r))
            }
        }
        private fun reissue() {
            val s = session ?: return; val req = previewReq ?: return
            applyControls(req)
            try { s.setRepeatingRequest(req.build(), null, bgH) } catch (e: Exception) {}
        }
        fun hasFlash(): Boolean = chars?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        fun setFlash(mode: String) { flash = mode; bgH.post { reissue() } }
        fun zoomRange(): String {
            val c = chars ?: return "1.0,1.0"
            val max = if (Build.VERSION.SDK_INT >= 30) (c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f)
                      else (c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
            val min = if (Build.VERSION.SDK_INT >= 30) (c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f) else 1f
            return String.format(java.util.Locale.US, "%.1f,%.1f", min, max)
        }
        fun setZoom(factor: Float) {
            val r = zoomRange().split(","); val lo = r[0].toFloat(); val hi = r[1].toFloat()
            zoomRatio = factor.coerceIn(lo, hi)
            bgH.post { reissue() }
        }
        fun focusAt(x: Float, y: Float) {
            // The view's fraction to a metering rectangle on the sensor, which is
            // rotated by the sensor orientation relative to the view.
            val c = chars ?: return
            val arr = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            if ((c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) < 1) return
            val (sx, sy) = when (sensorOrientation) { 90 -> y to 1f - x; 180 -> 1f - x to 1f - y; 270 -> 1f - y to x; else -> x to y }
            val size = (minOf(arr.width(), arr.height()) / 10)
            val cx = (arr.left + sx * arr.width()).toInt(); val cy = (arr.top + sy * arr.height()).toInt()
            val rect = android.graphics.Rect((cx - size / 2).coerceIn(arr.left, arr.right - size), (cy - size / 2).coerceIn(arr.top, arr.bottom - size), 0, 0)
            rect.right = rect.left + size; rect.bottom = rect.top + size
            afRegion = android.hardware.camera2.params.MeteringRectangle(rect, android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX - 1)
            bgH.post {
                val s = session ?: return@post; val req = previewReq ?: return@post
                applyControls(req)
                req.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                req.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                try { s.capture(req.build(), null, bgH) } catch (e: Exception) {}
                req.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                try { s.setRepeatingRequest(req.build(), null, bgH) } catch (e: Exception) {}
            }
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
                applyControls(req)
                // The flash at capture: the AE mode carries it on Camera2.
                if (hasFlash() && flash != "torch") {
                    req.set(CaptureRequest.CONTROL_AE_MODE, when (flash) {
                        "on" -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                        "auto" -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                        else -> CameraMetadata.CONTROL_AE_MODE_ON
                    })
                }
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
        // The platform ScrollView reports its scrolling to a nested-scroll parent only when
        // asked to (it is off by default). A Sheet above a list needs the report for the
        // hand-off: the list scrolls at the top point, the sheet moves everywhere else.
        init { isNestedScrollingEnabled = true }
        // A scroll clips itself, as a UIScrollView does. On Android a view's drawing is
        // clipped by its PARENT's clipChildren, and a plain container does not clip (a
        // child moved past its edge stays visible, as on iOS), so without this the rows
        // scrolled above the top painted over whatever sits above the scroll.
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); clipBounds = android.graphics.Rect(0, 0, w, h) }
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
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); clipBounds = android.graphics.Rect(0, 0, w, h) }   // self-clip, see SnapScrollView
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
    // A rounded-rect fill painted with a LinearGradient shader, plus the same stroke a
    // GradientDrawable would draw. GradientDrawable only accepts orientation in 45-degree
    // steps, so using it would have made an angle mean something different on each host;
    // a shader takes the angle as given and keeps the two in step.
    /// Android has no backdrop blur for an in-window view: its blur effect blurs a view's
    /// OWN content, which would blur the children drawn on top. So a Blur is a translucent
    /// tinted scrim here: the same shape and the same weight in the layout, without the live
    /// sampling iOS gets. Documented on the component, because designing for the blur and
    /// getting the scrim is how a screen ends up looking thin on Android.
    private fun blurScrim(id: String): Int {
        val amt = (blurAmt[id] ?: 60).coerceIn(0, 100)
        val alpha = (30 + amt * 1.5f).toInt().coerceIn(0, 200)
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val light = when (blurTint[id] ?: "system") {
            "light" -> true
            "dark" -> false
            else -> !night                         // system: match the OS appearance, as the iOS material does
        }
        return if (light) Color.argb(alpha, 255, 255, 255) else Color.argb(alpha, 20, 20, 22)
    }

    inner class LinearGradientDrawable(
        private val colors: IntArray, private val stops: FloatArray?, private val angleDeg: Int,
        private val radii: FloatArray?, private val radius: Float,
        private val strokeW: Float, private val strokeC: Int
    ) : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val path = android.graphics.Path()
        private fun rrect(): android.graphics.Path {
            val b = bounds; path.reset()
            val r = android.graphics.RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            if (radii != null) path.addRoundRect(r, radii, android.graphics.Path.Direction.CW)
            else path.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW)
            return path
        }
        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            // 0 degrees runs top to bottom and the angle grows clockwise, matching iOS. Both
            // endpoints sit on a unit vector through the centre, scaled to the bounds.
            val rad = Math.toRadians(angleDeg.toDouble())
            val cx = b.exactCenterX(); val cy = b.exactCenterY()
            val dx = (Math.sin(rad) * b.width() / 2.0).toFloat()
            val dy = (Math.cos(rad) * b.height() / 2.0).toFloat()
            p.shader = android.graphics.LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy,
                colors, stops, android.graphics.Shader.TileMode.CLAMP)
            p.style = android.graphics.Paint.Style.FILL
            canvas.drawPath(rrect(), p)
            if (strokeW > 0f) {
                p.shader = null; p.style = android.graphics.Paint.Style.STROKE
                p.strokeWidth = strokeW; p.color = strokeC
                canvas.drawPath(rrect(), p)   // inset by half the stroke, as GradientDrawable does
            }
        }
        override fun setAlpha(a: Int) { p.alpha = a }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
        @Deprecated("deprecated in API 29, still required by the base class")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

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
        // Unknown id: nothing to tear down. Mounts emit R| before C| (see mountNode in
        // core/ui.chuks), so keep this a map miss rather than the O(views) sweep below.
        if (!views.containsKey(id) && !ynodes.containsKey(id)) return
        packageViews.remove(id)?.destroy()
        packageViewHosts.remove(id)
        // A package that chose where this child went takes it out the same way, so a view
        // held in a layer of its own is released rather than orphaned.
        views[id]?.let { v ->
            val owner = containerPlaced.remove(id)
            val cv = if (owner != null) packageViews[owner] as? ChuksContainerView else null
            // An exiting spec keeps the view (and its children) on screen until its
            // animation ends; everything else about the node is torn down now.
            val ex = exitSpec[id]
            if (cv != null) cv.removeChild(v) else if (!(ex != null && runExit(v, ex))) (v.parent as? ViewGroup)?.removeView(v)
        }
        ynodes[id]?.let { textNodes.remove(it); measureViews.remove(it); val o = N.yOwner(it); if (o != 0L) N.yRemove(o, it); N.yFree(it) }
        val prefix = "$id."
        videoPlayers.keys.filter { it == id || it.startsWith(prefix) }.toList().forEach { k -> poolVideo(k) }
        videoWanted.keys.filter { it == id || it.startsWith(prefix) }.toList().forEach { videoWanted.remove(it); videoPlayPref.remove(it); videoMutePref.remove(it); videoLoopPref.remove(it) }
        if (cameraIds.any { it == id || it.startsWith(prefix) }) { cameraController?.close(); cameraController = null }
        views.keys.filter { it == id || it.startsWith(prefix) }.forEach { sheetIds.remove(it); popoverIds.remove(it); popoverAnchor.remove(it); popoverPlace.remove(it); popoverGap.remove(it); popoverArrow.remove(it); popoverArrowViews.remove(it); layoutIds.remove(it); lastLayoutReport.remove(it) }
        views.keys.filter { it == id || it.startsWith(prefix) }.forEach { views.remove(it); explicitFg.remove(it); cameraIds.remove(it); bgColor.remove(it); bgRadius.remove(it); borderW.remove(it); borderC.remove(it); pressOpacity.remove(it); sliderMin.remove(it); sliderStep.remove(it); sliderDone.remove(it); switchThumb.remove(it); explicitHeight.remove(it); scrollOnScroll.remove(it); scrollLastPos.remove(it); selectIds.remove(it); selectOptions.remove(it); selectSel.remove(it); datePickerIds.remove(it); datePickerModes.remove(it); datePickerVals.remove(it); menuIds.remove(it); menuData.remove(it); contextMenuIds.remove(it); contextMenuData.remove(it); mapIds.remove(it); gestureIds.remove(it); gestureCont.remove(it); motionForget(it); enterSpec.remove(it); exitSpec.remove(it); layoutSpec.remove(it); pendingEnter.remove(it); layoutLast.remove(it); alertIds.remove(it); alertData.remove(it); alertActions.remove(it); bgImageViews.remove(it); imageSrc.remove(it); imageDecodedDim.remove(it); lastFrame.remove(it); needsFrame.remove(it) }
        ynodes.keys.filter { it == id || it.startsWith(prefix) }.forEach { ynodes.remove(it) }
    }

    // TC|id: the node reports a value (a field's text, a slider's number, a picked index
    // or date, an alert's button) on "<id>:change". The value readers read CTAG; the
    // press binding (TAG, bindAction) is separate and may coexist.
    private fun bindChange(id: String) {
        val v = views[id] ?: return
        val action = "$id:change"
        if (alertIds.contains(id)) { alertActions[id] = action; return }
        v.setTag(CTAG, action)
    }
    // T|id|action: the node's press. A control that reports a value keeps that on its
    // change binding (bindChange); here a press is a press on every kind of view.
    private fun bindAction(id: String, action: String) {
        val v = views[id] ?: return
        if (gestureIds.contains(id)) { v.setTag(TAG, action); return }   // a Gesture's press: its own detector fires it (the touch listener stays)
        if (modalIds.contains(id)) modalActions[id] = action   // onDismiss: scrim tap AND sheet drag
        if (modalIds.contains(id) && !sheetModals.contains(id)) {
            // A tap on the overlay's content is the content's; only a tap on the scrim
            // dismisses. Children consume their own touches first, so this listener sees
            // the rest: inside any content view it declines (the overlay swallows it),
            // outside it captures the DOWN and fires on the UP. A bottom sheet keeps its
            // own drag listener (attachSheetDrag), which already checks the scrim.
            v.isClickable = true
            v.setOnClickListener(null)
            v.setOnTouchListener { ov, ev ->
                val g = ov as? ViewGroup
                var inside = false
                if (g != null) for (i in 0 until g.childCount) {
                    val c = g.getChildAt(i)
                    if (c.visibility == View.VISIBLE && ev.x >= c.left && ev.x <= c.right && ev.y >= c.top && ev.y <= c.bottom) { inside = true; break }
                }
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> !inside
                    MotionEvent.ACTION_UP -> { if (!inside && action.isNotEmpty()) fire(action); true }
                    else -> true
                }
            }
            return
        }
        if (v is ScrollView) { setupPullToRefresh(v, action); return }      // Scroll onRefresh -> pull-to-refresh
        when (v) {
            is Button -> v.setTag(TAG, action)
            else -> {
                // Reset any prior interaction binding before (re)binding. Node ids are
                // reused when a screen swaps in place, so a Pressable can become an inert
                // row at the same id; its old touch listener consumes ACTION_UP and fires
                // the PREVIOUS action, so it must be cleared or the reused view keeps
                // firing it (RN resets a recycled view to defaults — this is that reset).
                // A press that is IN FLIGHT when we rebind never gets its ACTION_UP:
                // clearing the listener cancels the touch silently, so the press dim
                // would stick on the view forever (and that press is lost -- RN cancels
                // it the same way when a view is recycled mid-touch).
                if (v.alpha != 1f && !disabledIds.contains(id)) { v.animate().cancel(); v.alpha = 1f }
                v.setOnTouchListener(null)
                v.setOnClickListener(null)
                v.isClickable = false
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
                } else if (action.isNotEmpty()) { v.isClickable = true; v.setOnClickListener { if (!disabledIds.contains(id)) fire(action) } }
                // else: empty action + not pressable -> leave the view non-interactive (default)
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
    // ---- predictive back (API 34+): an INTERACTIVE pop ------------------------
    // The engine mounts the top two routes as full-screen sibling containers under the
    // app root (see NavStack.render), so the system's back progress can drag the top
    // screen off and slide the one beneath it in, and cancelling puts it back. Below
    // API 34 the platform reports no progress, so back stays instant there (onBackPressed).
    private var backTop: View? = null
    private var backBelow: View? = null
    private val backParallax = 0.28f
    // Which container holds the pair. "" = the app stack's root; a tab that owns its
    // history announces its own, so the screens that move are the ones on screen rather
    // than the shell behind them. Set from the engine's SK| line, like the live list.
    private var stackHostId: String = ""

    private fun backLayers(): Boolean {
        val host = (if (stackHostId.isEmpty()) views["app"] else (views[stackHostId] ?: views["app"])) as? ViewGroup ?: return false
        if (host.childCount < 2) return false
        backTop = host.getChildAt(host.childCount - 1)
        backBelow = host.getChildAt(host.childCount - 2)
        return true
    }
    private fun backProgress(p: Float) {
        val w = root.width.toFloat().coerceAtLeast(1f)
        val t = (p.coerceIn(0f, 1f)) * w
        backTop?.translationX = t
        backBelow?.translationX = -w * backParallax * (1f - t / w)
    }
    private fun backSettle(commit: Boolean) {
        val w = root.width.toFloat().coerceAtLeast(1f)
        val top = backTop; val below = backBelow
        backTop = null; backBelow = null
        if (top == null || below == null) { if (commit) doBack(); return }
        top.animate().translationX(if (commit) w else 0f).setDuration(180).withEndAction {
            top.translationX = 0f
            below.translationX = 0f
            if (commit) doBack()
        }.start()
        below.animate().translationX(if (commit) 0f else -w * backParallax).setDuration(180).start()
    }
    // The pop itself: ask the app first (a handler or a stacked route consumes it);
    // only when nothing does are we really at the root and the activity should finish.
    private fun doBack() {
        if (N.back() > 0) { applyDrain(); relayout(); return }
        finish()
    }

    override fun onBackPressed() {
        val mid = activeModal
        if (mid != null && views[mid]?.visibility == View.VISIBLE) {
            modalActions[mid]?.let { fire(it) }   // parent flips `visible` false; the re-render hides it
            return
        }
        if (closeTopSheet()) return
        // Ask the app before leaving. A registered back handler or a stacked route
        // consumes the press; only when nothing does are we really at the root, and the
        // default (finish the activity) is correct. Without this, back exited the app
        // from any pushed screen, which is not what any Android user expects.
        if (N.back() > 0) { applyDrain(); relayout(); return }
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
    // How far the tree is lifted for the keyboard: the OVERLAP of the focused field, not
    // the keyboard height. See applyKeyboard.
    private var kbShift = 0
    private var kbScroll: ScrollView? = null
    private var kbSheet: String? = null                 // the Sheet holding the focused field, to restore on hide
    // The Sheet a view sits in, if any.
    private fun sheetContaining(v: View): Pair<String, SheetLayout>? {
        var p: View? = v.parent as? View
        while (p != null) {
            if (p is SheetLayout) { val sid = views.entries.firstOrNull { it.value === p }?.key ?: return null; return Pair(sid, p) }
            p = p.parent as? View
        }
        return null
    }
    // A field inside a Sheet: the sheet makes room the way its `keyboard` prop says, and
    // the app tree under it does not move.
    private fun sheetKeyboard(sid: String, sl: SheetLayout, kbH: Float) {
        val mode = if (kbH > 0f) sl.keyboard else ""
        val lift = if (mode == "interactive") kbH else 0f
        val pad = if (mode == "extend" || mode == "fillParent") kbH else 0f
        val fill = mode == "fillParent"
        if (sl.kbLift == lift && sl.kbPad == pad && sl.kbFill == fill) return
        sl.kbLift = lift; sl.kbPad = pad; sl.kbFill = fill
        if (mode == "extend") sheetAnimate(sid, sl, sl.heights.size - 1) else sheetAnimate(sid, sl, sl.index, 0f, false)
        relayout()
    }

    // The keyboard overlays the app; lift only what it covers, and only as much as it
    // takes. A focused field inside a Scroll gets bottom padding on that scroll and is
    // scrolled into view (nothing else moves); a field pinned to the bottom outside a
    // scroll (a chat composer) shifts the tree by exactly the overlap; a field already
    // above the keyboard moves nothing. Mirrors the iOS host.
    private fun applyKeyboard(imeH: Int) {
        motionKeyboard(Math.max(0, imeH))
        kbScroll?.let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, 0) }
        kbScroll = null
        kbSheet?.let { sid -> if (imeH <= 0 || currentFocus?.let { sheetContaining(it)?.first } != sid) { kbSheet = null; (views[sid] as? SheetLayout)?.let { sheetKeyboard(sid, it, 0f) } } }
        if (imeH <= 0) { if (kbShift != 0) { kbShift = 0; relayout() }; return }
        val f = currentFocus
        if (f == null) { if (kbShift != 0) { kbShift = 0; relayout() }; return }
        if (motionKeyboardOwns(f)) { if (kbShift != 0) { kbShift = 0; relayout() }; return }   // a composer bound to the keyboard's height moves itself
        sheetContaining(f)?.let { (sid, sl) -> kbSheet = sid; sheetKeyboard(sid, sl, imeH.toFloat()); return }
        val loc = IntArray(2); f.getLocationInWindow(loc)
        val rootLoc = IntArray(2); root.getLocationInWindow(rootLoc)
        // Measured against the UNLIFTED layout (add back the current shift), so repeated
        // inset callbacks can't measure the field where the previous pass put it and
        // collapse the lift to nothing.
        val bottomInRoot = loc[1] - rootLoc[1] + f.height + kbShift
        val overlap = maxOf(0, bottomInRoot + dp(8) - (root.height - imeH))

        var p: View? = f.parent as? View
        while (p != null && p !is ScrollView) { p = p.parent as? View }
        if (p is ScrollView) {
            val isNew = (kbScroll !== p)                              // scroll only on the way in
            kbScroll = p
            p.clipToPadding = false
            p.setPadding(p.paddingLeft, p.paddingTop, p.paddingRight, imeH)
            if (isNew && overlap > 0) p.smoothScrollBy(0, overlap)
            if (kbShift != 0) { kbShift = 0; relayout() }
            return
        }
        if (overlap == kbShift) return
        kbShift = overlap
        relayout()
    }

    private fun relayout() {
        val app = ynodes["app"] ?: return
        val topY = dp(6)
        val w = root.width; val h = root.height - topY - kbShift
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
        // Every Sheet is its own root-sized Yoga root, padded at the top so its children
        // fill the tallest snap box; the overlay translates them to the current point.
        for (sid in sheetIds) {
            val sn = ynodes[sid] ?: continue
            val sl = views[sid] as? SheetLayout ?: continue
            sl.layoutParams = FrameLayout.LayoutParams(root.width, root.height).also { it.leftMargin = 0; it.topMargin = 0 }
            if (sl.width != root.width || sl.height != root.height) sl.layout(0, 0, root.width, root.height)
            @Suppress("DEPRECATION")
            sl.topInset = (window.decorView.rootWindowInsets?.systemWindowInsetTop ?: 0).toFloat()
            @Suppress("DEPRECATION")
            sl.bottomInset = (window.decorView.rootWindowInsets?.systemWindowInsetBottom ?: 0).toFloat()
            sl.resolveHeights()
            N.ySetF(sn, 5, root.width.toFloat()); N.ySetF(sn, 6, root.height.toFloat())
            N.ySetF(sn, 16, sl.layoutTop + sl.handleStrip)
            N.ySetF(sn, 18, sl.bottomPad)
            N.yCalc(sn, root.width.toFloat(), root.height.toFloat())
            if (sl.snapSpec.contains("c")) {
                var total = 0f
                for ((cid, cn) in ynodes) if (cid.startsWith("$sid.") && cid.indexOf('.', sid.length + 1) < 0) total += N.yGet(cn, 3)
                if (total > 0f && Math.abs(total - sl.contentHeight) > 0.5f) {
                    sl.contentHeight = total; sl.resolveHeights()
                    N.ySetF(sn, 16, sl.layoutTop + sl.handleStrip)
                    N.yCalc(sn, root.width.toFloat(), root.height.toFloat())
                }
            }
        }
        val pendingLayout = ArrayList<Pair<String, String>>()   // onLayout reports, delivered after this pass
        // Whether any view's frame moved. Only then does Android get asked for a layout
        // pass: a pass over this tree costs 13-15ms on a 20-row screen (every TextView
        // measures again), and it used to be requested unconditionally, after every
        // engine event and every heartbeat, with nothing to lay out. A finger swiping a
        // row made a gesture, an end and a settle event per swipe, so three passes.
        var moved = false
        for ((id, node) in ynodes) {
            if (modalIds.contains(id) || sheetIds.contains(id)) continue   // overlay roots are placed explicitly below
            val v = views[id] ?: continue
            val left = N.yGet(node, 0).toInt(); val top = N.yGet(node, 1).toInt()
            val wd = N.yGet(node, 2).toInt()
            // A horizontal list's content node has an explicit WIDTH but no height (its cells
            // are abs), so Yoga gives it height 0 and Android would clip the cells. Fill it to
            // the scroll's own height (the cross axis); it scrolls sideways only.
            val ht = if (listHoriz && id == contentId) (listScroll?.height ?: N.yGet(node, 3).toInt()) else N.yGet(node, 3).toInt()
            // Incremental apply: reassign LayoutParams (which triggers a child requestLayout)
            // only when the frame changed vs the last applied one, or the view is brand new.
            val prev = lastFrame[id]
            if (!needsFrame.contains(id) && prev != null && prev[0] == left && prev[1] == top && prev[2] == wd && prev[3] == ht) continue
            val lp = FrameLayout.LayoutParams(wd, ht)
            lp.leftMargin = left
            lp.topMargin = top
            v.layoutParams = lp                                  // requests a layout pass by itself
            // A scroll's content is what iOS sizes from the content node's frame. Android's
            // ScrollView ignores its child's layout height and measures it UNSPECIFIED, so
            // a FrameLayout content reports the extent of its children and nothing else:
            // a content column's own bottom padding (the space a screen keeps clear of a
            // floating tab bar) fell out of the scroll range, and a page whose children
            // just fit the window would not scroll at all. The Yoga size is the floor the
            // platform measure may report. (Set here, in the branch that already asks for
            // a layout pass, so it costs no extra one; a reused node is reset in applyStyle.)
            when (v.parent) {
                is ScrollView -> if (v.minimumHeight != ht) v.minimumHeight = ht
                is android.widget.HorizontalScrollView -> if (v.minimumWidth != wd) v.minimumWidth = wd
            }
            moved = true
            lastFrame[id] = intArrayOf(left, top, wd, ht)
            // now that the frame is known, decode the image to its display size.
            if (imageSrc.containsKey(id)) ensureSizedImage(id, maxOf(wd, ht))
            if (layoutIds.contains(id)) {
                val key = "${Math.round(left / density)},${Math.round(top / density)},${Math.round(wd / density)},${Math.round(ht / density)}"
                if (lastLayoutReport[id] != key) { lastLayoutReport[id] = key; pendingLayout.add(Pair(id, key)) }
            }
        }
        runLayoutTransitions()   // layout-spec views move from where they were (in absolute terms, so a moved parent counts)
        runPendingEnters()       // mounted views enter once they have a frame
        needsFrame.clear()   // consumed for this pass
        // onLayout: the frame changed for a node that asked. Delivered after this pass
        // returns, because a handler re-renders and re-enters relayout.
        if (pendingLayout.isNotEmpty()) root.post { for ((id, key) in pendingLayout) if (layoutIds.contains(id)) hostInput("$id:layout", key) }
        // place the whole Chuks app just below the top inset (a change here is edited in
        // place, so it asks for the pass itself)
        (views["app"]?.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.leftMargin != 0 || it.topMargin != topY) { it.leftMargin = 0; it.topMargin = topY; moved = true }
        }
        // Sheets: on top of the app, at their current point (a drag or animation in flight
        // keeps its position; a layout never fights the finger).
        for (sid in sheetIds) {
            val sl = views[sid] as? SheetLayout ?: continue
            if (sl.closed && !sl.animating) { sl.visibility = View.GONE; continue }
            sl.bringToFront()
            if (!sl.animating && !sl.dragging) sl.position = sl.positionFor(sl.index)
            sl.apply()
        }
        // a visible modal fills the window and sits on top (children laid out above)
        if (mid != null && views[mid]?.visibility == View.VISIBLE) {
            views[mid]?.let { mv ->
                mv.layoutParams = FrameLayout.LayoutParams(root.width, root.height).also { it.leftMargin = 0; it.topMargin = 0 }
                mv.bringToFront()
                if (popoverIds.contains(mid)) { clearSheetChrome(); placePopover(mid, mv as FrameLayout) }
                else if (sheetModals.contains(mid)) {
                    val firstOpen = shownSheet != mid; shownSheet = mid
                    layoutSheetChrome(mv as FrameLayout, firstOpen)
                } else clearSheetChrome()
            }
        } else clearSheetChrome()
        if (moved) root.requestLayout()
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

    // Back closes an open sheet with a backdrop, as it closes a Modal.
    private fun closeTopSheet(): Boolean {
        for (sid in sheetIds) {
            val sl = views[sid] as? SheetLayout ?: continue
            if (sl.visibility == View.VISIBLE && !sl.closed && sl.backdrop) { sheetAnimate(sid, sl, -1); return true }
        }
        return false
    }


    // ---- Frame cadence measurement (Debug.frames) -----------------------------------
    // Choreographer frames for `ms`; each frame's interval is recorded. The same metric
    // the RN bench measures with reanimated's useFrameCallback.
    private fun frameStats(token: String, ms: Double) {
        // Rendered frames against their real deadline, from the window's FrameMetrics (the
        // source `dumpsys gfxinfo` reads). A Choreographer cadence cannot tell jank from an
        // adaptive panel dropping to 24Hz while nothing draws: on a Galaxy S23 it reported
        // a swipe scene as 58 janky where no frame had missed its vsync. Here a frame is
        // janky when it finished after the deadline the system gave it, and the duration
        // percentiles are of frames the app actually drew.
        val durations = ArrayList<Double>()
        var janky = 0
        val hz = Math.round(windowManager.defaultDisplay.refreshRate)
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, m, _ ->
            val total = m.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1e6
            durations.add(total)
            // DEADLINE is the time the frame was allowed (one or two vsyncs, in ns), not a timestamp.
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                if (m.getMetric(android.view.FrameMetrics.TOTAL_DURATION) > m.getMetric(android.view.FrameMetrics.DEADLINE)) janky++
            } else if (total > 1000.0 / hz) janky++
        }
        window.addOnFrameMetricsAvailableListener(listener, handler)
        handler.postDelayed({
            window.removeOnFrameMetricsAvailableListener(listener)
            val s = durations.sorted()
            fun pct(p: Double): Double = if (s.isEmpty()) 0.0 else s[Math.min(s.size - 1, (s.size * p).toInt())]
            resolve(token, String.format(java.util.Locale.US, "hz=%d frames=%d janky=%d p50=%.1f p95=%.1f max=%.1f", hz, durations.size, janky, pct(0.5), pct(0.95), s.lastOrNull() ?: 0.0))
        }, ms.toLong())
    }

    // ---- Shared values: motion the host drives (docs/shared-values.md) ------------
    // A value the engine created with MV|; the host holds its number, animates it (MS|
    // with a spring/timing/decay), feeds it from a pan or a scroll, and re-evaluates the
    // bindings that read it. The engine hears a gesture's end and a settle, nothing else.
    // The expression vocabulary is fixed and identical to the iOS host's.
    sealed class MExpr {
        class Value(val id: Int) : MExpr()
        class Const(val v: Double) : MExpr()
        class Interp(val x: MExpr, val ins: DoubleArray, val outs: DoubleArray, val clampEnds: Boolean) : MExpr()
        class Clamp(val x: MExpr, val lo: Double, val hi: Double) : MExpr()
        class Bin(val op: String, val a: MExpr, val b: MExpr) : MExpr()
        class Un(val op: String, val x: MExpr) : MExpr()
        fun deps(out: HashSet<Int>) {
            when (this) {
                is Value -> out.add(id)
                is Const -> {}
                is Interp -> x.deps(out)
                is Clamp -> x.deps(out)
                is Bin -> { a.deps(out); b.deps(out) }
                is Un -> x.deps(out)
            }
        }
        fun eval(get: (Int) -> Double): Double = when (this) {
            is Value -> get(id)
            is Const -> v
            is Clamp -> Math.min(hi, Math.max(lo, x.eval(get)))
            is Bin -> { val l = a.eval(get); val r = b.eval(get); when (op) { "+" -> l + r; "-" -> l - r; "*" -> l * r; "min" -> Math.min(l, r); else -> Math.max(l, r) } }
            is Un -> { val v = x.eval(get); if (op == "neg") -v else Math.abs(v) }
            is Interp -> {
                val xv = x.eval(get)
                if (ins.size < 2 || ins.size != outs.size) (outs.firstOrNull() ?: 0.0)
                else if (clampEnds && xv <= ins[0]) outs[0]
                else if (clampEnds && xv >= ins[ins.size - 1]) outs[outs.size - 1]
                else {
                    // Piecewise linear between the stops; past the ends the end segment's line
                    // continues (extend), so an over-drag keeps moving.
                    var i = 0
                    while (i < ins.size - 2 && xv > ins[i + 1]) i++
                    val x0 = ins[i]; val x1 = ins[i + 1]; val y0 = outs[i]; val y1 = outs[i + 1]
                    if (x1 - x0 == 0.0) y0 else y0 + (xv - x0) / (x1 - x0) * (y1 - y0)
                }
            }
        }
    }
    /// Parse the engine's prefix text ("i(v3,0,120,200,80,e)"). null on a malformed
    /// string: a binding that fails to parse is ignored, never a crash.
    private fun parseMExpr(text: String): MExpr? {
        var pos = 0
        fun peek(): Char? = if (pos < text.length) text[pos] else null
        fun token(): String { val sb = StringBuilder(); while (true) { val c = peek() ?: break; if (c == '(' || c == ')' || c == ',') break; sb.append(c); pos++ }; return sb.toString() }
        fun num(e: MExpr): Double? = (e as? MExpr.Const)?.v
        fun parse(): MExpr? {
            val tok = token()
            if (peek() != '(') {
                if (tok == "c") return MExpr.Const(1.0)        // interpolate's mode letters
                if (tok == "e") return MExpr.Const(0.0)
                if (tok.startsWith("v")) tok.substring(1).toIntOrNull()?.let { return MExpr.Value(it) }
                return tok.toDoubleOrNull()?.let { MExpr.Const(it) }
            }
            pos++
            val args = ArrayList<MExpr>()
            if (peek() == ')') pos++ else {
                while (true) {
                    args.add(parse() ?: return null)
                    if (peek() == ',') { pos++; continue }
                    if (peek() == ')') { pos++; break }
                    return null
                }
            }
            return when (tok) {
                "i" -> {
                    if (args.size < 6 || (args.size - 2) % 2 != 0) return null
                    val mode = num(args[args.size - 1]) ?: return null
                    val n = (args.size - 2) / 2
                    val ins = DoubleArray(n); val outs = DoubleArray(n)
                    for (k in 0 until n) { ins[k] = num(args[1 + k]) ?: return null; outs[k] = num(args[1 + n + k]) ?: return null }
                    MExpr.Interp(args[0], ins, outs, mode == 1.0)
                }
                "cl" -> if (args.size == 3) MExpr.Clamp(args[0], num(args[1]) ?: return null, num(args[2]) ?: return null) else null
                "+", "-", "*", "min", "max" -> if (args.size == 2) MExpr.Bin(tok, args[0], args[1]) else null
                "neg", "abs" -> if (args.size == 1) MExpr.Un(tok, args[0]) else null
                else -> null
            }
        }
        val e = parse()
        return if (pos == text.length) e else null
    }
    class MotionValue(var cur: Double) {
        var velocity = 0.0                 // units per second
        var anim: String = ""              // "" | "sp" | "tm" | "dc"
        var target = 0.0; var stiffness = 180.0; var damping = 22.0
        var from = 0.0; var startNs = 0L; var ms = 300.0; var easing = "ease"
        var lo = -1e9; var hi = 1e9
        var lastNs = 0L
    }
    class MotionBinding(val prop: String, val expr: MExpr)
    private val mValues = HashMap<Int, MotionValue>()
    private val mBindings = HashMap<String, List<MotionBinding>>()      // node id -> bindings
    private val mDeps = HashMap<Int, HashSet<String>>()                // value id -> nodes bound to it
    private val mPan = HashMap<String, IntArray>()                     // Gesture node -> [x id, y id] (-1 none)
    private val mPanBase = HashMap<String, DoubleArray>()
    private val mScroll = HashMap<String, Int>()                       // Scroll node -> value id
    private val mStatic = HashMap<String, FloatArray>()                // a node's own paint values under its bindings: tx ty sc rot alpha
    private val mAnimating = HashSet<Int>()
    private val mKeyboardValues = HashSet<Int>()                       // values fed with the keyboard's height (MK|)
    private val mKeyboardNodes = HashSet<String>()                     // nodes whose bindings read a keyboard value: they move themselves
    private var mLayoutDirty = false
    private var motionFrameArmed = false

    /// MV|id|initial, MS|id|target|anim, MX|id.
    private fun motionOp(f: List<String>) {
        if (f.size < 2) return
        val id = f[1].toIntOrNull() ?: return
        when (f[0]) {
            "MV" -> {
                val initial = if (f.size >= 3) (f[2].toDoubleOrNull() ?: 0.0) else 0.0
                val mv = mValues[id]
                if (mv != null) { mv.cur = initial; mv.anim = "" } else mValues[id] = MotionValue(initial)
                motionWrite(id, initial); motionFlush()
            }
            "MS" -> {
                val mv = mValues[id] ?: return
                val target = if (f.size >= 3) (f[2].toDoubleOrNull() ?: mv.cur) else mv.cur
                val anim = if (f.size >= 4) f[3] else ""
                val kind = anim.substringBefore(":")
                val args = if (anim.contains(":")) anim.substringAfter(":").split(",") else emptyList()
                fun arg(i: Int, d: Double): Double = args.getOrNull(i)?.toDoubleOrNull() ?: d
                mv.lastNs = System.nanoTime()
                when (kind) {
                    "sp" -> { mv.anim = "sp"; mv.target = target; mv.stiffness = arg(0, 180.0); mv.damping = arg(1, 22.0); mv.velocity = arg(2, 0.0); motionStartAnimating(id) }
                    "tm" -> { mv.anim = "tm"; mv.from = mv.cur; mv.target = target; mv.startNs = mv.lastNs; mv.ms = Math.max(1.0, arg(0, 300.0)); mv.easing = args.getOrNull(1) ?: "ease"; motionStartAnimating(id) }
                    "dc" -> { mv.anim = "dc"; mv.velocity = arg(0, 0.0); mv.lo = arg(1, -1e9); mv.hi = arg(2, 1e9); motionStartAnimating(id) }
                    else -> { mv.anim = ""; mv.velocity = 0.0; mAnimating.remove(id); motionWrite(id, target); motionFlush() }
                }
            }
            "MX" -> {
                mAnimating.remove(id); mValues.remove(id); mKeyboardValues.remove(id)
                mDeps[id]?.forEach { nid -> mBindings[nid]?.let { bs -> mBindings[nid] = bs.filter { b -> val d = HashSet<Int>(); b.expr.deps(d); !d.contains(id) } } }
                mDeps.remove(id)
            }
        }
    }
    /// The `mo` style key: "prop:expr|prop:expr". Replaces this node's bindings.
    private fun motionBind(id: String, spec: String) {
        motionUnbindNode(id)
        val bs = ArrayList<MotionBinding>()
        for (part in spec.split("|")) {
            val i = part.indexOf(':'); if (i <= 0) continue
            val e = parseMExpr(part.substring(i + 1)) ?: continue
            bs.add(MotionBinding(part.substring(0, i), e))
            val d = HashSet<Int>(); e.deps(d)
            for (vid in d) mDeps.getOrPut(vid) { HashSet() }.add(id)
        }
        if (bs.isNotEmpty()) mBindings[id] = bs
        if (bs.any { b -> val d = HashSet<Int>(); b.expr.deps(d); d.any { mKeyboardValues.contains(it) } }) mKeyboardNodes.add(id)
    }
    /// Is this view inside a node that moves itself with the keyboard? Then the host's
    /// own keyboard avoidance leaves it alone (it would move twice).
    private fun motionKeyboardOwns(v: View): Boolean {
        if (mKeyboardNodes.isEmpty()) return false
        var cur: View? = v
        while (cur != null) {
            val id = views.entries.firstOrNull { it.value === cur }?.key
            if (id != null && mKeyboardNodes.contains(id)) return true
            cur = cur.parent as? View
        }
        return false
    }
    /// Drop a node's bindings and sources (the style reset, before the keys re-add).
    private fun motionUnbindNode(id: String) {
        mKeyboardNodes.remove(id)
        mBindings.remove(id)?.forEach { b -> val d = HashSet<Int>(); b.expr.deps(d); for (vid in d) mDeps[vid]?.remove(id) }
        mPan.remove(id); mScroll.remove(id)
    }
    /// A node left the tree: forget everything of its own.
    private fun motionForget(id: String) { motionUnbindNode(id); mStatic.remove(id); mPanBase.remove(id) }
    /// A new number for a value: every node bound to it is re-evaluated.
    private fun motionWrite(vid: Int, value: Double) {
        val mv = mValues[vid] ?: return
        mv.cur = value
        mDeps[vid]?.forEach { motionApplyNode(it) }
    }
    /// Evaluate a node's bindings and set the props: paint props on the view (over the
    /// style's own values, kept in mStatic), layout props on the Yoga node (laid out at
    /// the next flush). Values are in points; the view takes pixels.
    private fun motionApplyNode(id: String) {
        val bs = mBindings[id] ?: return
        val v = views[id] ?: return
        val st = mStatic[id] ?: floatArrayOf(0f, 0f, 1f, 0f, 1f)
        var tx = st[0]; var ty = st[1]; var sc = st[2]; var rot = st[3]; var alpha = st[4]
        var xform = false; var alphaSet = false
        val n = ynodes[id]
        for (b in bs) {
            val x = b.expr.eval { mValues[it]?.cur ?: 0.0 }.toFloat()
            when (b.prop) {
                "tx" -> { tx = x; xform = true }
                "ty" -> { ty = x; xform = true }
                "scale" -> { sc = x / 100f; xform = true }
                "rotate" -> { rot = x; xform = true }
                "opacity" -> { alpha = x / 100f; alphaSet = true }
                "w" -> if (n != null) { N.ySetF(n, 5, dpf(x)); mLayoutDirty = true }
                "h" -> if (n != null) { N.ySetF(n, 6, dpf(x)); mLayoutDirty = true }
                "pt" -> if (n != null) { N.ySetF(n, 16, dpf(x)); mLayoutDirty = true }
                "pr" -> if (n != null) { N.ySetF(n, 17, dpf(x)); mLayoutDirty = true }
                "pb" -> if (n != null) { N.ySetF(n, 18, dpf(x)); mLayoutDirty = true }
                "pl" -> if (n != null) { N.ySetF(n, 19, dpf(x)); mLayoutDirty = true }
                "top" -> if (n != null) { N.ySetF(n, 10, dpf(x)); mLayoutDirty = true }
                "left" -> if (n != null) { N.ySetF(n, 11, dpf(x)); mLayoutDirty = true }
            }
        }
        if (xform) { v.translationX = dpf(tx); v.translationY = dpf(ty); v.scaleX = sc; v.scaleY = sc; v.rotation = rot }
        if (alphaSet) v.alpha = Math.max(0f, Math.min(1f, alpha))
    }
    /// One layout pass for everything the frame's writes changed.
    private fun motionFlush() { if (mLayoutDirty) { mLayoutDirty = false; relayout() } }

    // ---- sources ----
    /// A pan on a Gesture that feeds values: write base + travel (in points), and
    /// report the end with the velocity. Returns false when the gesture is not one of ours.
    private fun motionPan(id: String, phase: Int, dxPx: Float, dyPx: Float, vxPx: Float, vyPx: Float): Boolean {
        val ids = mPan[id] ?: return false
        val dx = (dxPx / density).toDouble(); val dy = (dyPx / density).toDouble()
        when (phase) {
            0 -> {
                mPanLocked.remove(id)
                val bx = if (ids[0] >= 0) (mValues[ids[0]]?.cur ?: 0.0) else 0.0
                val by = if (ids[1] >= 0) (mValues[ids[1]]?.cur ?: 0.0) else 0.0
                mPanBase[id] = doubleArrayOf(bx, by)
                for (vid in ids) if (vid >= 0) { mValues[vid]?.anim = ""; mAnimating.remove(vid) }   // the finger takes over from any animation
            }
            1 -> {
                val base = mPanBase[id] ?: doubleArrayOf(0.0, 0.0)
                if (ids[0] >= 0) motionWrite(ids[0], base[0] + dx)
                if (ids[1] >= 0) motionWrite(ids[1], base[1] + dy)
                motionFlush()
            }
            else -> {
                if (motionPanOneAxis(id) && !mPanLocked.contains(id)) { mPanBase.remove(id); return true }   // the Scroll took this touch: nothing moved, nothing to report
                val base = mPanBase[id] ?: doubleArrayOf(0.0, 0.0)
                if (ids[0] >= 0) motionWrite(ids[0], base[0] + dx)
                if (ids[1] >= 0) motionWrite(ids[1], base[1] + dy)
                motionFlush()
                mPanBase.remove(id); mPanLocked.remove(id)
                if (ids[0] >= 0) hostInput("mv${ids[0]}:end", "${mValues[ids[0]]?.cur ?: 0.0},${(vxPx / density).toDouble()}")
                if (ids[1] >= 0) hostInput("mv${ids[1]}:end", "${mValues[ids[1]]?.cur ?: 0.0},${(vyPx / density).toDouble()}")
            }
        }
        return true
    }
    private val mPanLocked = HashSet<String>()                        // one-axis pans that claimed the current touch
    private fun motionPanOneAxis(id: String): Boolean { val ids = mPan[id] ?: return false; return (ids[0] >= 0) != (ids[1] >= 0) }
    private fun motionPanLocked(id: String): Boolean = mPanLocked.contains(id)
    private fun motionPanLock(id: String) { mPanLocked.add(id) }
    /// A scroll that feeds a value: its offset along its axis, in points.
    private fun motionScrolled(id: String, offsetPx: Int) {
        val vid = mScroll[id] ?: return
        motionWrite(vid, (offsetPx / density).toDouble())
        motionFlush()
    }

    /// The keyboard's height changed: every keyboard value eases to it (points), so a
    /// bound composer arrives with the keyboard.
    private fun motionKeyboard(heightPx: Int) {
        for (vid in mKeyboardValues) {
            val mv = mValues[vid] ?: continue
            mv.lastNs = System.nanoTime(); mv.anim = "tm"; mv.from = mv.cur; mv.target = (heightPx / density).toDouble()
            mv.startNs = mv.lastNs; mv.ms = 250.0; mv.easing = "out"
            motionStartAnimating(vid)
        }
    }

    // ---- host animations ----
    private fun motionStartAnimating(vid: Int) {
        mAnimating.add(vid)
        if (!motionFrameArmed) { motionFrameArmed = true; android.view.Choreographer.getInstance().postFrameCallback(motionFrame) }
    }
    private val motionFrame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            motionFrameArmed = false
            val settled = ArrayList<Int>()
            for (vid in ArrayList(mAnimating)) {
                val mv = mValues[vid]
                if (mv == null || mv.anim == "") { settled.add(vid); continue }
                val dt = Math.min(Math.max((frameTimeNanos - mv.lastNs) / 1e9, 0.0), 0.064)   // a stall must not fling a spring
                mv.lastNs = frameTimeNanos
                var done = false
                when (mv.anim) {
                    "sp" -> {
                        // Semi-implicit Euler in sub-steps of at most 8 ms: stable for any stiffness an app would use.
                        var remaining = dt
                        while (remaining > 0) {
                            val h = Math.min(remaining, 0.008); remaining -= h
                            val a = -mv.stiffness * (mv.cur - mv.target) - mv.damping * mv.velocity
                            mv.velocity += a * h; mv.cur += mv.velocity * h
                        }
                        if (Math.abs(mv.velocity) < 1 && Math.abs(mv.cur - mv.target) < 0.05) { mv.cur = mv.target; mv.velocity = 0.0; done = true }
                    }
                    "tm" -> {
                        var t = Math.min(1.0, Math.max(0.0, (frameTimeNanos - mv.startNs) / 1e6 / mv.ms))
                        t = when (mv.easing) { "linear" -> t; "in" -> t * t * t; "out" -> 1 - Math.pow(1 - t, 3.0); else -> if (t < 0.5) 4 * t * t * t else 1 - Math.pow(-2 * t + 2, 3.0) / 2 }
                        mv.cur = mv.from + (mv.target - mv.from) * t
                        if ((frameTimeNanos - mv.startNs) / 1e6 >= mv.ms) { mv.cur = mv.target; done = true }
                    }
                    "dc" -> {
                        mv.velocity *= Math.exp(-3.5 * dt)
                        mv.cur += mv.velocity * dt
                        if (mv.cur < mv.lo || mv.cur > mv.hi) {
                            // Past the edge: a rubber band, which is a spring to the edge carrying the velocity.
                            mv.target = if (mv.cur < mv.lo) mv.lo else mv.hi; mv.stiffness = 180.0; mv.damping = 22.0; mv.anim = "sp"
                        } else if (Math.abs(mv.velocity) < 4) done = true
                    }
                }
                motionWrite(vid, mv.cur)
                if (done) { mv.anim = ""; mv.velocity = 0.0; settled.add(vid) }
            }
            motionFlush()
            for (vid in settled) { mAnimating.remove(vid); mValues[vid]?.let { hostInput("mv$vid:settle", "${it.cur}") } }
            if (mAnimating.isNotEmpty() && !motionFrameArmed) { motionFrameArmed = true; android.view.Choreographer.getInstance().postFrameCallback(this) }
        }
    }


    // ---- Transitions: how a view arrives, leaves and moves ----------------------------
    // Specs come as style keys (en=, ex=, lt=): a kind and, optionally, a duration in ms,
    // an easing and a delay ("slide-up 300 out 50"). The host runs them: entering after
    // the mounted view has its first frame, exiting on removal with the view kept in
    // place and inert until it is done, layout as a move from the old frame to the new.
    class TransitionSpec(text: String) {
        val kind: String; val ms: Long; val easing: String; val delay: Long
        init {
            val parts = text.split(" ").filter { it.isNotEmpty() }
            kind = parts.getOrNull(0) ?: "fade"
            ms = parts.getOrNull(1)?.toLongOrNull() ?: (if (kind == "spring") 450L else 300L)
            var ez = parts.getOrNull(2) ?: (if (kind == "spring") "spring" else "out")
            if (kind == "spring") ez = "spring"
            easing = ez
            delay = parts.getOrNull(3)?.toLongOrNull() ?: 0L
        }
        fun interpolator(): android.animation.TimeInterpolator = when (easing) {
            "linear" -> android.view.animation.LinearInterpolator()
            "in" -> android.view.animation.AccelerateInterpolator()
            "ease" -> android.view.animation.AccelerateDecelerateInterpolator()
            "spring" -> android.view.animation.OvershootInterpolator(0.8f)
            else -> android.view.animation.DecelerateInterpolator(1.6f)
        }
    }
    private val enterSpec = HashMap<String, String>()
    private val exitSpec = HashMap<String, String>()
    private val layoutSpec = HashMap<String, String>()
    private val pendingEnter = HashMap<String, String>()
    private val layoutLast = HashMap<String, FloatArray>()            // a layout-spec node's absolute frame after the last pass
    /// The state a view starts from (entering) or ends at (exiting): an offset by its own
    /// size for the slides, a small scale for zoom, invisible for fade. [tx, ty, scale, alpha]
    private fun transitionState(v: View, kind: String, entering: Boolean): FloatArray {
        val w = Math.max(v.width, 1).toFloat(); val h = Math.max(v.height, 1).toFloat()
        return when (kind) {
            "slide-up" -> floatArrayOf(0f, if (entering) h else -h, 1f, 1f)
            "slide-down" -> floatArrayOf(0f, if (entering) -h else h, 1f, 1f)
            "slide-left" -> floatArrayOf(if (entering) w else -w, 0f, 1f, 1f)
            "slide-right" -> floatArrayOf(if (entering) -w else w, 0f, 1f, 1f)
            "zoom" -> floatArrayOf(0f, 0f, 0.6f, 0f)
            else -> floatArrayOf(0f, 0f, 1f, 0f)
        }
    }
    /// Mounted views with an entering spec, once they have a frame (the layout pass
    /// just set it; the view measures on the next frame, so the start state is applied
    /// from its LayoutParams size).
    private fun runPendingEnters() {
        if (pendingEnter.isEmpty()) return
        val batch = HashMap(pendingEnter); pendingEnter.clear()
        for ((id, text) in batch) {
            val v = views[id] ?: continue
            if (mBindings.containsKey(id)) continue   // a bound node's transform is the graph's
            val spec = TransitionSpec(text)
            val lp = v.layoutParams
            val w = Math.max(lp?.width ?: v.width, 1).toFloat(); val h = Math.max(lp?.height ?: v.height, 1).toFloat()
            val st = when (spec.kind) {
                "slide-up" -> floatArrayOf(0f, h, 1f, 1f); "slide-down" -> floatArrayOf(0f, -h, 1f, 1f)
                "slide-left" -> floatArrayOf(w, 0f, 1f, 1f); "slide-right" -> floatArrayOf(-w, 0f, 1f, 1f)
                "zoom" -> floatArrayOf(0f, 0f, 0.6f, 0f); else -> floatArrayOf(0f, 0f, 1f, 0f)
            }
            val endAlpha = v.alpha
            v.translationX = st[0]; v.translationY = st[1]; v.scaleX = st[2]; v.scaleY = st[2]; v.alpha = st[3]
            v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(endAlpha)
                .setDuration(spec.ms).setStartDelay(spec.delay).setInterpolator(spec.interpolator()).start()
        }
    }
    /// A removed view with an exiting spec leaves on its own time: it stays in its parent
    /// where it was, takes no touches, and is removed when the animation ends. Its node
    /// is already gone, so a new node at the same id is unaffected.
    private fun runExit(v: View, text: String): Boolean {
        if (!v.isAttachedToWindow || (v.width == 0 && v.height == 0)) return false
        val spec = TransitionSpec(text)
        v.isEnabled = false; v.isClickable = false
        if (v is ViewGroup) { v.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS }
        v.bringToFront()   // out of the live children's order, so their insert indices stay right
        val end = transitionState(v, spec.kind, false)
        v.animate().translationX(end[0]).translationY(end[1]).scaleX(end[2]).scaleY(end[2]).alpha(end[3])
            .setDuration(spec.ms).setStartDelay(spec.delay).setInterpolator(spec.interpolator())
            .withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
        return true
    }
    /// A node's frame in absolute terms: its Yoga frame summed up the owner chain, so a
    /// child of a parent that moved has moved too. Scrolling does not change Yoga frames,
    /// so a scroll never counts as a move. [x, y, w, h] in px.
    private fun yogaAbsoluteFrame(id: String): FloatArray? {
        val n = ynodes[id] ?: return null
        var x = N.yGet(n, 0); var y = N.yGet(n, 1)
        val w = N.yGet(n, 2); val h = N.yGet(n, 3)
        var o = N.yOwner(n)
        while (o != 0L) { x += N.yGet(o, 0); y += N.yGet(o, 1); o = N.yOwner(o) }
        return floatArrayOf(x, y, w, h)
    }
    /// After a layout pass: every view with a layout spec whose absolute frame changed is
    /// placed at the new frame and moves there from the old one (translate and scale
    /// back to rest, pivot top-left). A view mounted this pass enters instead.
    private fun runLayoutTransitions() {
        if (layoutSpec.isEmpty()) return
        for ((id, text) in layoutSpec) {
            val v = views[id] ?: continue
            val fr = yogaAbsoluteFrame(id) ?: continue
            val old = layoutLast[id]
            layoutLast[id] = fr
            if (old == null || needsFrame.contains(id) || mBindings.containsKey(id)) continue
            if (old[0] == fr[0] && old[1] == fr[1] && old[2] == fr[2] && old[3] == fr[3]) continue
            if (fr[2] <= 0f || fr[3] <= 0f || old[2] <= 0f || old[3] <= 0f) continue
            val spec = TransitionSpec(text)
            v.pivotX = 0f; v.pivotY = 0f
            v.translationX = old[0] - fr[0]; v.translationY = old[1] - fr[1]
            v.scaleX = old[2] / fr[2]; v.scaleY = old[3] / fr[3]
            v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(spec.ms).setStartDelay(spec.delay).setInterpolator(spec.interpolator()).start()
        }
    }

    // ── Sheet: index and animation ─────────────────────────────────────────────
    private fun sheetSetIndex(id: String, sl: SheetLayout, idx: Int) {
        val target = maxOf(-1, minOf(idx, maxOf(sl.heights.size - 1, 0)))
        if (sl.dragging) { sl.index = target; return }
        if (sl.visibility != View.VISIBLE && target < 0) { sl.index = -1; return }
        if (sl.visibility != View.VISIBLE) {
            // Opening from closed: place it closed, then slide to the point.
            sl.index = -1
            if (sl.width == 0) sl.layout(0, 0, root.width, root.height)
            sl.resolveHeights(); sl.position = sl.closedPosition; sl.apply(); sl.bringToFront()
        }
        if (sl.index != target) sheetAnimate(id, sl, target, 0f, false) else sl.index = target
    }
    // Spring to a point. `report` tells the app through onChange when the point is not
    // the one it last set (a drag, a command, the backdrop); an index the app sent is
    // not echoed back.
    private fun sheetAnimate(id: String, sl: SheetLayout, idx: Int, velocity: Float = 0f, report: Boolean = true) {
        val target = maxOf(-1, minOf(idx, maxOf(sl.heights.size - 1, 0)))
        if (sl.heights.isEmpty()) sl.resolveHeights()
        val dest = sl.positionFor(target)
        val reported = sl.index
        sl.index = target; sl.animating = true; sl.visibility = View.VISIBLE; sl.bringToFront()
        val from = sl.position
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 320
        anim.interpolator = android.view.animation.DecelerateInterpolator(1.6f)
        anim.addUpdateListener { va -> val t = va.animatedValue as Float; sl.position = from + (dest - from) * t; sl.apply() }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) {
                sl.position = dest; sl.apply(); sl.animating = false
                if (sl.closed) sl.visibility = View.GONE
                if (report && reported != target) hostInput("$id:change", target.toString())
            }
        })
        anim.start()
    }

    // ── Popover placement ──────────────────────────────────────────────────────
    // The content was laid out at the overlay's origin at its natural size. Measure the
    // anchor in the root's coordinates (the overlay fills the root, so this is the
    // screen), choose a side, flip when it would not fit, clamp inside the system bars,
    // and point the arrow at the anchor's middle. Runs on every layout pass the popover
    // is visible for, so it follows the anchor through a scroll or a rotation. Frames
    // come from the Yoga nodes, which hold the resolved layout before Android's own pass.
    private fun placePopover(mid: String, mv: FrameLayout) {
        val content = (0 until mv.childCount).map { mv.getChildAt(it) }.firstOrNull { it !== popoverArrowViews[mid] } ?: return
        val cid = views.entries.firstOrNull { it.value === content }?.key ?: return
        val cnode = ynodes[cid] ?: return
        val cw = N.yGet(cnode, 2).toInt(); val ch = N.yGet(cnode, 3).toInt()
        val W = root.width; val H = root.height
        val wi = window.decorView.rootWindowInsets
        @Suppress("DEPRECATION")
        val saT = wi?.systemWindowInsetTop ?: 0; @Suppress("DEPRECATION") val saB = wi?.systemWindowInsetBottom ?: 0
        @Suppress("DEPRECATION")
        val saL = wi?.systemWindowInsetLeft ?: 0; @Suppress("DEPRECATION") val saR = wi?.systemWindowInsetRight ?: 0
        val margin = dp(8)
        val minX = saL + margin; val maxX = W - saR - margin; val minY = saT + margin; val maxY = H - saB - margin
        val gap = popoverGap[mid] ?: dp(8)
        val aid = popoverAnchor[mid]
        val av = if (aid != null) views[aid] else null
        val anchor: android.graphics.Rect? = if (av != null && av.isAttachedToWindow) {
            val loc = IntArray(2); av.getLocationInWindow(loc)
            val rl = IntArray(2); root.getLocationInWindow(rl)
            android.graphics.Rect(loc[0] - rl[0], loc[1] - rl[1], loc[0] - rl[0] + av.width, loc[1] - rl[1] + av.height)
        } else null
        fun put(x: Int, y: Int) { content.layoutParams = FrameLayout.LayoutParams(cw, ch).also { it.leftMargin = x; it.topMargin = y } }
        if (anchor == null) {
            // No anchor yet (a popover visible before its anchor mounted): a dialog.
            put((W - cw) / 2, (H - ch) / 2)
            popoverArrowViews.remove(mid)?.let { mv.removeView(it) }
            return
        }
        val a = anchor
        val fitsBelow = a.bottom + gap + ch <= maxY; val fitsAbove = a.top - gap - ch >= minY
        val fitsRight = a.right + gap + cw <= maxX; val fitsLeft = a.left - gap - cw >= minX
        var place = popoverPlace[mid] ?: "auto"
        place = when (place) {
            "top" -> if (!fitsAbove && fitsBelow) "bottom" else "top"
            "bottom" -> if (!fitsBelow && fitsAbove) "top" else "bottom"
            "left" -> if (!fitsLeft && fitsRight) "right" else "left"
            "right" -> if (!fitsRight && fitsLeft) "left" else "right"
            else -> if (fitsBelow) "bottom" else if (fitsAbove) "top" else if (a.centerY() < H / 2) "bottom" else "top"
        }
        var x: Int; var y: Int
        when (place) {
            "top" -> { x = a.centerX() - cw / 2; y = a.top - gap - ch }
            "left" -> { x = a.left - gap - cw; y = a.centerY() - ch / 2 }
            "right" -> { x = a.right + gap; y = a.centerY() - ch / 2 }
            else -> { x = a.centerX() - cw / 2; y = a.bottom + gap }
        }
        x = minOf(maxOf(x, minX), maxOf(minX, maxX - cw)); y = minOf(maxOf(y, minY), maxOf(minY, maxY - ch))
        put(x, y)
        // The pointer: a triangle on the anchor's side of the content, on the content's own
        // colour, its tip at the anchor's middle (kept clear of the content's corners).
        if (!popoverArrow.contains(mid)) { popoverArrowViews.remove(mid)?.let { mv.removeView(it) }; return }
        val sz = dp(8); val r = maxOf((bgRadius[cid] ?: 0f).toInt(), dp(6))
        val color = bgColor[cid] ?: Color.TRANSPARENT
        val path = android.graphics.Path()
        val ax: Int; val ay: Int; val aw: Int; val ah: Int
        when (place) {
            "top" -> { val tx = minOf(maxOf(a.centerX(), x + r + sz), x + cw - r - sz); ax = tx - sz; ay = y + ch; aw = 2 * sz; ah = sz
                path.moveTo(0f, 0f); path.lineTo(sz.toFloat(), sz.toFloat()); path.lineTo(2f * sz, 0f) }
            "left" -> { val ty = minOf(maxOf(a.centerY(), y + r + sz), y + ch - r - sz); ax = x + cw; ay = ty - sz; aw = sz; ah = 2 * sz
                path.moveTo(0f, 0f); path.lineTo(sz.toFloat(), sz.toFloat()); path.lineTo(0f, 2f * sz) }
            "right" -> { val ty = minOf(maxOf(a.centerY(), y + r + sz), y + ch - r - sz); ax = x - sz; ay = ty - sz; aw = sz; ah = 2 * sz
                path.moveTo(sz.toFloat(), 0f); path.lineTo(0f, sz.toFloat()); path.lineTo(sz.toFloat(), 2f * sz) }
            else -> { val tx = minOf(maxOf(a.centerX(), x + r + sz), x + cw - r - sz); ax = tx - sz; ay = y - sz; aw = 2 * sz; ah = sz
                path.moveTo(0f, sz.toFloat()); path.lineTo(sz.toFloat(), 0f); path.lineTo(2f * sz, sz.toFloat()) }
        }
        path.close()
        val arrow = (popoverArrowViews[mid] as? PopoverArrowView) ?: PopoverArrowView(this).also { popoverArrowViews[mid] = it; mv.addView(it) }
        arrow.path = path; arrow.color = color
        arrow.layoutParams = FrameLayout.LayoutParams(aw, ah).also { it.leftMargin = ax; it.topMargin = ay }
        arrow.invalidate()
    }
    // ── Sheet ──────────────────────────────────────────────────────────────────
    // A bottom sheet the host drives between snap points with the finger; the design
    // and the vocabulary are the iOS host's (SheetOverlay), which follows
    // @gorhom/react-native-bottom-sheet. The overlay fills the root; the app's children
    // are laid out once in the tallest snap box (a top padding on the sheet's Yoga root)
    // and the panel (surface, handle, children) is translated to the current position.
    // A drag on the panel is handled here; a scrollable inside hands off through nested
    // scrolling, the way Material's BottomSheetBehavior does it: the sheet consumes the
    // scroll until it is at its highest point, then the list scrolls, and a list at its
    // top that is pulled down moves the sheet again.
    inner class SheetLayout(ctx: Context) : FrameLayout(ctx) {
        // The panel holds the content. Its background is the surface, inset down to the
        // highest point, and it carries the elevation: on Android elevation orders the
        // drawing, so a surface that was a sibling of the content painted over it. The
        // content is root-relative (Yoga pads it down to the highest point) and the panel
        // is translated as one to the current point.
        val panel = FrameLayout(ctx)
        val handle = View(ctx)
        private val surfaceDrawable = GradientDrawable()
        private var surfaceInset = -1
        private var surfaceShown = 0
        var surfaceOverride: Int? = null   // the app's `bg` for the surface; null = the platform's sheet colour
        var id = ""
        var backdrop = true
        var panToClose = true
        var showHandle = true
        var keyboard = "interactive"
        var snapSpec: List<String> = emptyList()
        var heights: FloatArray = FloatArray(0)
        var index = -1
        var position = 0f
        var contentHeight = 0f
        var animating = false
        var dragging = false
        val handleStrip = dp(22)
        // The keyboard, while a field inside the sheet has it (see applyKeyboard). The
        // `keyboard` prop picks one: "interactive" lifts the sheet by the keyboard's height
        // (kbLift), "extend" goes to the highest point and pads the content bottom by it
        // (kbPad), "fillParent" takes the whole window (kbFill) and pads.
        var kbLift = 0f
        var kbPad = 0f
        var kbFill = false
        var topInset = 0f                  // the status bar's height: no point sits above it (set each layout)
        var bottomInset = 0f               // the navigation bar's height: the content clears it
        // Bottom padding for the children: the keyboard when it pads, nothing when the sheet
        // is lifted onto the keyboard (its bottom edge is above it), else the system bar.
        val bottomPad: Float get() = if (kbPad > 0f) kbPad else if (kbLift > 0f) 0f else bottomInset
        private var downY = 0f; private var initial = 0f; private var ownsDrag = false
        private var tracker: android.view.VelocityTracker? = null
        private var nestedVelocity = 0f
        init {
            surfaceDrawable.setColor(surfaceColor())
            surfaceDrawable.cornerRadii = floatArrayOf(dpf(20f), dpf(20f), dpf(20f), dpf(20f), 0f, 0f, 0f, 0f)
            panel.elevation = dpf(8f)
            val hd = GradientDrawable(); hd.setColor(Color.argb(102, 128, 128, 128)); hd.cornerRadius = dpf(2.5f)
            handle.background = hd
            panel.addView(handle)
            addView(panel)
            isClickable = true
        }
        val closed: Boolean get() = index < 0
        val highestHeight: Float get() = heights.maxOrNull() ?: 0f
        val closedPosition: Float get() = height.toFloat()
        val highestPosition: Float get() = height - highestHeight
        // Where the children are laid out from (the top of the tallest box), and the
        // highest the top edge may go: the same point, unless the keyboard changed them.
        val layoutTop: Float get() = if (kbFill) topInset else highestPosition
        val minPosition: Float get() = if (kbFill) topInset else maxOf(topInset, highestPosition - kbLift)
        fun positionFor(i: Int): Float {
            if (i < 0 || i >= heights.size) return closedPosition
            if (kbFill) return topInset
            return maxOf(topInset, height - heights[i] - kbLift)
        }
        fun settlePositions(): List<Float> { val ps = heights.indices.map { positionFor(it) }.toMutableList(); if (panToClose) ps.add(closedPosition); return ps }
        fun indexFor(p: Float): Int { for (i in heights.indices) if (Math.abs(positionFor(i) - p) < 0.5f) return i; return -1 }
        fun resolveHeights() {
            val h = height.toFloat()
            var hs = snapSpec.map { spec ->
                when {
                    spec == "c" -> if (contentHeight > 0f) minOf(contentHeight + handleStrip + bottomInset, h) else 0f
                    spec.endsWith("p") -> h * ((spec.dropLast(1).toFloatOrNull() ?: 50f) / 100f)
                    else -> dpf(spec.toFloatOrNull() ?: 300f)
                }
            }
            val mx = hs.maxOrNull() ?: 0f
            if (mx > 0f) hs = hs.map { if (it == 0f) mx else it }
            heights = hs.map { minOf(maxOf(it, handleStrip + 1f), h) }.toFloatArray()
        }
        fun apply() {
            val w = width; val h = height
            val inset = layoutTop.toInt()
            val color = surfaceOverride ?: surfaceColor()
            if (inset != surfaceInset || color != surfaceShown) {
                surfaceInset = inset; surfaceShown = color
                surfaceDrawable.setColor(color)
                panel.background = android.graphics.drawable.InsetDrawable(surfaceDrawable, 0, inset, 0, 0)
                panel.setPadding(0, 0, 0, 0)   // a background's insets become view padding; the content is root-relative already
            }
            val ph = (h - inset + h).toInt()
            if (panel.layoutParams?.width != w || panel.layoutParams?.height != ph) panel.layoutParams = LayoutParams(w, ph)
            val hl = (w - dp(40)) / 2; val ht = inset + dp(8)
            val hlp = handle.layoutParams as? LayoutParams
            if (hlp == null || hlp.leftMargin != hl || hlp.topMargin != ht) handle.layoutParams = LayoutParams(dp(40), dp(5)).also { it.leftMargin = hl; it.topMargin = ht }
            handle.visibility = if (showHandle) View.VISIBLE else View.GONE
            panel.translationY = position - layoutTop
            val openness = if (highestHeight > 0f) maxOf(0f, minOf(1f, (closedPosition - position) / highestHeight)) else 0f
            setBackgroundColor(if (backdrop) Color.argb((128 * openness).toInt(), 0, 0, 0) else Color.TRANSPARENT)
            visibility = View.VISIBLE
        }
        fun destination(p: Float, v: Float): Float {
            val target = p + 0.2f * v
            var best = closedPosition; var bestD = Float.MAX_VALUE
            for (sp in settlePositions()) { val d = Math.abs(target - sp); if (d < bestD) { bestD = d; best = sp } }
            return best
        }
        // A persistent sheet must not eat the touches beside it.
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (!backdrop && ev.action == MotionEvent.ACTION_DOWN && ev.y < position) return false
            return super.dispatchTouchEvent(ev)
        }
        private fun clampDrag(raw: Float): Float {
            val lowest = if (panToClose) closedPosition else (settlePositions().maxOrNull() ?: closedPosition)
            return when {
                raw < minPosition -> minPosition - Math.sqrt(1.0 + (minPosition - raw)).toFloat() * dpf(2.5f)
                raw > lowest -> lowest + Math.sqrt(1.0 + (raw - lowest)).toFloat() * dpf(2.5f)
                else -> raw
            }
        }
        // Touches that no scrollable child took: the handle, the surface, inert content.
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { downY = ev.y; initial = position; ownsDrag = false; tracker = android.view.VelocityTracker.obtain(); tracker?.addMovement(ev) }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(ev)
                    if (!ownsDrag && Math.abs(ev.y - downY) > dp(6) && ev.y >= position - dp(1)) {
                        // A drag on the panel outside any scrollable: the sheet's. Inside a
                        // scrollable the child took the DOWN and nested scrolling reports here.
                        if (findScrollableAt(ev) == null) { ownsDrag = true; dragging = true; initial = position; downY = ev.y; return true }
                    }
                }
            }
            return false
        }
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            tracker?.addMovement(ev)
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { downY = ev.y; initial = position; return true }
                MotionEvent.ACTION_MOVE -> {
                    if (!ownsDrag) { if (Math.abs(ev.y - downY) > dp(6)) { ownsDrag = true; dragging = true; initial = position; downY = ev.y } else return true }
                    position = clampDrag(initial + (ev.y - downY)); apply(); return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDrag = ownsDrag
                    ownsDrag = false; dragging = false
                    var vy = 0f
                    tracker?.let { it.computeCurrentVelocity(1000); vy = it.yVelocity; it.recycle() }; tracker = null
                    if (wasDrag) settle(vy)
                    else if (backdrop && ev.action == MotionEvent.ACTION_UP && ev.y < position) sheetAnimate(id, this, -1)   // backdrop tap
                    return true
                }
            }
            return true
        }
        private fun findScrollableAt(ev: MotionEvent): View? {
            fun walk(v: View, x: Float, y: Float): View? {
                if (v is ScrollView || v is android.widget.HorizontalScrollView || v is android.widget.ListView) return v
                if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) {
                    val c = v.getChildAt(i); if (c.visibility != View.VISIBLE) continue
                    val cx = x - c.left - c.translationX; val cy = y - c.top - c.translationY
                    if (cx >= 0 && cy >= 0 && cx <= c.width && cy <= c.height) { val r = walk(c, cx, cy); if (r != null) return r }
                }
                return null
            }
            return walk(this, ev.x, ev.y)
        }
        fun settle(vy: Float) { val dest = destination(position, vy); sheetAnimate(id, this, indexFor(dest), vy) }
        // ── nested scrolling: the hand-off with a Scroll inside ──────────────────
        override fun onStartNestedScroll(child: View, target: View, axes: Int): Boolean = (axes and View.SCROLL_AXIS_VERTICAL) != 0
        override fun onNestedScrollAccepted(child: View, target: View, axes: Int) { initial = position; nestedVelocity = 0f; dragging = true }
        override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) {
            // dy > 0: the finger moves up. Below the highest point the sheet takes it; at
            // the highest point the list scrolls.
            if (dy > 0 && position > minPosition) {
                val take = minOf(dy.toFloat(), position - minPosition)
                position -= take; apply(); consumed[1] = take.toInt()
            }
        }
        override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int) {
            // dyUnconsumed < 0: the list is at its top and the finger keeps pulling down: the sheet takes it.
            if (dyUnconsumed < 0) { position = clampDrag(position - dyUnconsumed); apply() }
        }
        override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean {
            nestedVelocity = -velocityY
            // A fling while the sheet is between points is the sheet's; at the top the list's.
            return position > minPosition + 0.5f
        }
        override fun onNestedFling(target: View, velocityX: Float, velocityY: Float, consumed: Boolean): Boolean = false
        override fun onStopNestedScroll(target: View) { dragging = false; if (Math.abs(position - positionFor(index)) > 0.5f) settle(nestedVelocity) }
        override fun getNestedScrollAxes(): Int = View.SCROLL_AXIS_VERTICAL
    }

    class PopoverArrowView(ctx: Context) : View(ctx) {
        var path: android.graphics.Path = android.graphics.Path()
        var color: Int = 0
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: android.graphics.Canvas) { paint.color = color; canvas.drawPath(path, paint) }
    }

    // Dismissal is animated the way presentation is. The overlay's content is torn
    // down by the same mutation batch that hides it, so what animates out is a BITMAP
    // of the last frame: a sheet slides down from wherever it is (a drag past the
    // threshold continues from the finger), a dialog fades. Mirrors iOS.
    private fun animateOverlayOut(id: String, mv: FrameLayout) {
        if (mv.width <= 0 || mv.height <= 0) return
        if (sheetModals.contains(id)) {
            var top = mv.height; var bottom = 0
            for (i in 0 until mv.childCount) {
                val c = mv.getChildAt(i); if (c.visibility != View.VISIBLE) continue
                top = minOf(top, (c.top + c.translationY).toInt()); bottom = maxOf(bottom, (c.bottom + c.translationY).toInt())
            }
            bottom = minOf(bottom, mv.height)
            if (bottom <= top) return
            val bmp = android.graphics.Bitmap.createBitmap(mv.width, bottom - top, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.translate(0f, -top.toFloat())
            for (i in 0 until mv.childCount) {
                val c = mv.getChildAt(i); if (c.visibility != View.VISIBLE) continue
                canvas.save(); canvas.translate(c.left.toFloat(), c.top + c.translationY); c.draw(canvas); canvas.restore()
            }
            val scrim = View(this); scrim.background = mv.background?.constantState?.newDrawable()
            scrim.layoutParams = FrameLayout.LayoutParams(mv.width, mv.height).also { it.leftMargin = mv.left; it.topMargin = mv.top }
            val snap = ImageView(this); snap.setImageBitmap(bmp)
            snap.layoutParams = FrameLayout.LayoutParams(mv.width, bottom - top).also { it.leftMargin = mv.left; it.topMargin = mv.top + top }
            root.addView(scrim); root.addView(snap)
            val travel = (mv.height - top).toFloat()
            scrim.animate().alpha(0f).setDuration(260).start()
            snap.animate().translationY(travel).setDuration(260).setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction { root.removeView(snap); root.removeView(scrim); bmp.recycle() }.start()
            for (i in 0 until mv.childCount) mv.getChildAt(i).translationY = 0f   // the real views are reused next time
            return
        }
        val bmp = android.graphics.Bitmap.createBitmap(mv.width, mv.height, android.graphics.Bitmap.Config.ARGB_8888)
        mv.draw(android.graphics.Canvas(bmp))
        val snap = ImageView(this); snap.setImageBitmap(bmp)
        snap.layoutParams = FrameLayout.LayoutParams(mv.width, mv.height).also { it.leftMargin = mv.left; it.topMargin = mv.top }
        root.addView(snap)
        snap.animate().alpha(0f).setDuration(180).withEndAction { root.removeView(snap); bmp.recycle() }.start()
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

    companion object {
        const val TAG = 0x7f_00_00_01; const val GTAG = 0x7f_00_00_02; const val CTAG = 0x7f_00_00_03   // TAG: press; GTAG: a Gesture's stream; CTAG: a value change
        // Process-wide: the engine (libapp.so) mounted once already. See onCreate.
        @JvmStatic var engineMounted = false
    }
}
