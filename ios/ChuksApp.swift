// Chuks Mobile, Phase 2: a generic UIKit + Yoga host for a 100% Chuks app.
//
// The host creates NO app-specific widgets. It renders whatever tree Chuks emits
// (C/S/P/T/I/R stream) into two lockstep trees: real UIViews and a Yoga shadow
// tree. Each frame it runs Yoga and copies the computed rects onto the UIViews.
// The only node kinds it special-cases are structural: a `Scroll` node whose
// viewport it reports back to Chuks (virtualization), and an `Input` node whose
// text it reports back (TextInput). Everything else (the search bar, the list,
// the cards) is declared in engine.chuks. This is React Native's architecture
// with Chuks in place of JavaScript, and the layout engine is the real Yoga.

import UIKit
import AVFoundation
import WebKit
import MapKit
import Photos
import UserNotifications
import CoreLocation
import CoreMotion
import Security
import Network

// Process memory footprint (what iOS jetsam actually measures), in MB.
func physFootprintMB() -> Double {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
    let kr = withUnsafeMutablePointer(to: &info) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
        }
    }
    return kr == KERN_SUCCESS ? Double(info.phys_footprint) / 1_048_576.0 : 0
}

// A view whose backing layer IS an AVPlayerLayer, so the video follows the
// frame we drive manually (no per-frame layout work).
final class VideoView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

// The normal per-app UIKit host. The Chuks Preview build (-D CHUKS_PREVIEW) supplies its
// own @main in ChuksPreview.swift, gating CardsVC behind a connect/scan screen.
#if !CHUKS_PREVIEW
@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ a: UIApplication, didFinishLaunchingWithOptions o: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let w = UIWindow(frame: UIScreen.main.bounds)
        w.rootViewController = CardsVC()
        w.makeKeyAndVisible()
        window = w
        return true
    }
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        chuksOrientationMask   // Orientation.lockTo() drives this
    }
}
#endif

// Yoga measure callback: text nodes self-size by measuring their label's text.
let measureText: YGMeasureFunc = { node, width, widthMode, _, _ in
    guard let node = node, let ctx = YGNodeGetContext(node) else { return YGSize(width: 0, height: 0) }
    let label = Unmanaged<UILabel>.fromOpaque(ctx).takeUnretainedValue()
    let maxW = (widthMode == YGMeasureMode.undefined || width.isNaN) ? CGFloat.greatestFiniteMagnitude : CGFloat(width)
    let s = (label.text ?? "") as NSString
    let r = s.boundingRect(with: CGSize(width: maxW, height: .greatestFiniteMagnitude),
                           options: [.usesLineFragmentOrigin], attributes: [.font: label.font as Any], context: nil)
    return YGSize(width: Float(ceil(r.width)), height: Float(ceil(r.height)))
}

// hex "5B8CFF" -> UIColor
func hexColor(_ h: String) -> UIColor {
    var v: UInt64 = 0
    Scanner(string: h).scanHexInt64(&v)
    return UIColor(red: CGFloat((v >> 16) & 0xff) / 255, green: CGFloat((v >> 8) & 0xff) / 255,
                   blue: CGFloat(v & 0xff) / 255, alpha: 1)
}

// Orientation capability helpers: the current interface orientation as a string, and a
// lock through the mask the AppDelegate reports to UIKit.
var chuksOrientationMask: UIInterfaceOrientationMask = .all
func currentOrientationString() -> String {
    let io = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first?.interfaceOrientation
    return (io?.isLandscape ?? false) ? "landscape" : "portrait"
}
func applyOrientationLock(_ mode: String) {
    switch mode {
    case "portrait":  chuksOrientationMask = .portrait
    case "landscape": chuksOrientationMask = .landscape
    default:          chuksOrientationMask = .all
    }
    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    if #available(iOS 16.0, *) {
        scenes.forEach { $0.requestGeometryUpdate(.iOS(interfaceOrientations: chuksOrientationMask)) }
        scenes.forEach { $0.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations() }
    } else {
        UIViewController.attemptRotationToDeviceOrientation()
    }
}

// DEV mode (built with -D DEV): the engine runs in the Chuks VM dev server and
// the host fetches the mutation stream over HTTP, so editing engine.chuks hot-
// reloads with no app rebuild. Otherwise the engine is the AOT-linked cgo library.
#if DEV
let DEV_MODE = true
#else
let DEV_MODE = false
#endif
// The perf/jank harness (auto-scroll sweep + fps header) is a benchmark tool, not
// app behavior — off unless built with -D BENCHMARK. Without this it grabs whatever
// Scroll is on screen (e.g. the Components gallery) and auto-scrolls it.
#if BENCHMARK
let BENCHMARK_MODE = true
#else
let BENCHMARK_MODE = false
#endif

// Synchronous request to the dev server. Returns nil on a network error (server
// restarting during a reload), otherwise the mutation stream (possibly empty).
// Dev server host: a DEV=1 build may bundle chuks-dev.txt (the machine's LAN IP for a
// real device); the simulator falls back to localhost.
// Mutable so the Chuks Preview host can point it at a scanned/entered server at runtime.
func defaultDevServerHost() -> String {
    if let u = Bundle.main.url(forResource: "chuks-dev", withExtension: "txt"),
       let s = try? String(contentsOf: u, encoding: .utf8) {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if !t.isEmpty { return t }
    }
    return "localhost:7799"
}
var devServerHost: String = defaultDevServerHost()

func devHTTP(_ path: String, _ body: String, get: Bool = false) -> String? {
    guard let url = URL(string: "http://\(devServerHost)\(path)") else { return nil }
    var req = URLRequest(url: url, timeoutInterval: 2)
    req.httpMethod = get ? "GET" : "POST"
    if !get { req.httpBody = body.data(using: .utf8) }
    let sem = DispatchSemaphore(value: 0)
    var out: String? = nil
    URLSession.shared.dataTask(with: req) { data, _, err in
        if err == nil, let d = data { out = String(data: d, encoding: .utf8) ?? "" }
        sem.signal()
    }.resume()
    _ = sem.wait(timeout: .now() + 2.5)
    return out
}

// A vector drawing surface: parses the ";"-joined shape descriptors and draws them
// with Core Graphics. Shapes use pixel coordinates within the view.
final class CanvasView: UIView {
    var shapes: String = "" { didSet { setNeedsDisplay() } }
    override func draw(_ rect: CGRect) {
        for shape in shapes.components(separatedBy: ";") where !shape.isEmpty {
            let f = shape.components(separatedBy: ",")
            guard let type = f.first else { continue }
            switch type {
            case "rect" where f.count >= 9:
                guard let x = Double(f[1]), let y = Double(f[2]), let w = Double(f[3]), let h = Double(f[4]) else { break }
                let path = UIBezierPath(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerRadius: Double(f[8]) ?? 0)
                fillStroke(path, fill: f[5], stroke: f[6], sw: Double(f[7]) ?? 0)
            case "circle" where f.count >= 7:
                guard let cx = Double(f[1]), let cy = Double(f[2]), let r = Double(f[3]) else { break }
                let path = UIBezierPath(ovalIn: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2))
                fillStroke(path, fill: f[4], stroke: f[5], sw: Double(f[6]) ?? 0)
            case "line" where f.count >= 7:
                guard let x1 = Double(f[1]), let y1 = Double(f[2]), let x2 = Double(f[3]), let y2 = Double(f[4]) else { break }
                let path = UIBezierPath(); path.move(to: CGPoint(x: x1, y: y1)); path.addLine(to: CGPoint(x: x2, y: y2))
                path.lineCapStyle = .round
                fillStroke(path, fill: "", stroke: f[5], sw: Double(f[6]) ?? 1)
            case "path" where f.count >= 5:
                fillStroke(CanvasView.parsePath(f[1]), fill: f[2], stroke: f[3], sw: Double(f[4]) ?? 0)
            default: break
            }
        }
    }
    private func fillStroke(_ path: UIBezierPath, fill: String, stroke: String, sw: Double) {
        if !fill.isEmpty { hexColor(fill).setFill(); path.fill() }
        if !stroke.isEmpty && sw > 0 { hexColor(stroke).setStroke(); path.lineWidth = sw; path.stroke() }
    }
    static func parsePath(_ d: String) -> UIBezierPath {
        let path = UIBezierPath()
        var spaced = d
        for cmd in ["M", "L", "Z", "m", "l", "z"] { spaced = spaced.replacingOccurrences(of: cmd, with: " \(cmd) ") }
        let toks = spaced.split(separator: " ").map(String.init)
        var i = 0
        while i < toks.count {
            let t = toks[i]
            if (t == "M" || t == "L"), i + 2 < toks.count, let x = Double(toks[i + 1]), let y = Double(toks[i + 2]) {
                let pt = CGPoint(x: x, y: y)
                if t == "M" { path.move(to: pt) } else { path.addLine(to: pt) }
                i += 3; continue
            } else if t == "Z" || t == "z" { path.close() }
            i += 1
        }
        return path
    }
}

// Permission (F2): map each framework's status enum to the cross-platform grant
// string. Top-level so the sync + async permission paths share them.
func avAuthStr(_ s: AVAuthorizationStatus) -> String {
    switch s { case .authorized: return "granted"; case .denied: return "denied"; case .restricted: return "restricted"; default: return "undetermined" }
}
func phAuthStr(_ s: PHAuthorizationStatus) -> String {
    switch s { case .authorized, .limited: return "granted"; case .denied: return "denied"; case .restricted: return "restricted"; default: return "undetermined" }
}
func clAuthStr(_ s: CLAuthorizationStatus) -> String {
    switch s { case .authorizedWhenInUse, .authorizedAlways: return "granted"; case .denied: return "denied"; case .restricted: return "restricted"; default: return "undetermined" }
}
func unAuthStr(_ s: UNAuthorizationStatus) -> String {
    switch s { case .authorized, .provisional, .ephemeral: return "granted"; case .denied: return "denied"; default: return "undetermined" }
}

// Location permission needs a CLLocationManager delegate (result via callback).
final class LocPerm: NSObject, CLLocationManagerDelegate {
    private let mgr = CLLocationManager()
    private var onDecide: ((String) -> Void)?
    override init() { super.init(); mgr.delegate = self }
    func request(_ cb: @escaping (String) -> Void) {
        let s = mgr.authorizationStatus
        if s != .notDetermined { cb(clAuthStr(s)); return }
        onDecide = cb
        mgr.requestWhenInUseAuthorization()
    }
    func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        if m.authorizationStatus == .notDetermined { return }
        if let cb = onDecide { onDecide = nil; DispatchQueue.main.async { cb(clAuthStr(m.authorizationStatus)) } }
    }
}

// Streams CLLocation fixes to a callback: once==true delivers a single fix then stops,
// once==false keeps updating until stop(). If authorization is still undetermined it
// prompts and begins as soon as the grant arrives; a denial goes to onErr.
final class LocFix: NSObject, CLLocationManagerDelegate {
    private let mgr = CLLocationManager()
    private let once: Bool
    private let onFix: (String) -> Void
    private let onErr: (String) -> Void
    private var pending = false   // waiting on the authorization decision to begin
    init(once: Bool, onFix: @escaping (String) -> Void, onErr: @escaping (String) -> Void) {
        self.once = once; self.onFix = onFix; self.onErr = onErr
        super.init(); mgr.delegate = self; mgr.desiredAccuracy = kCLLocationAccuracyBest
    }
    func start() {
        switch mgr.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: begin()
        case .denied, .restricted: onErr("location permission denied")
        default: pending = true; mgr.requestWhenInUseAuthorization()
        }
    }
    func stop() { mgr.stopUpdatingLocation() }
    private func begin() { if once { mgr.requestLocation() } else { mgr.startUpdatingLocation() } }
    func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        guard pending else { return }
        switch m.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: pending = false; begin()
        case .denied, .restricted: pending = false; onErr("location permission denied")
        default: break   // still undetermined
        }
    }
    func locationManager(_ m: CLLocationManager, didUpdateLocations locs: [CLLocation]) {
        guard let l = locs.last else { return }
        let c = l.coordinate
        onFix("\(c.latitude),\(c.longitude),\(l.horizontalAccuracy),\(l.altitude),\(l.speed),\(l.course)")
        if once { m.stopUpdatingLocation() }
    }
    func locationManager(_ m: CLLocationManager, didFailWithError e: Error) {
        if (e as? CLError)?.code == .locationUnknown { return }  // transient: no fix yet, keep waiting
        onErr(e.localizedDescription)
    }
}

// Secure storage (Tier B): Keychain-backed key/value.
func keychainSet(_ key: String, _ value: String) {
    let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key]
    SecItemDelete(base as CFDictionary)
    var add = base; add[kSecValueData as String] = value.data(using: .utf8)!
    SecItemAdd(add as CFDictionary, nil)
}
func keychainGet(_ key: String) -> String? {
    let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key,
                            kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
    var out: AnyObject?
    guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
    return String(data: d, encoding: .utf8)
}
func keychainDelete(_ key: String) {
    SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key] as CFDictionary)
}

// Decode a base64 string to UTF-8 (for capability args carrying arbitrary text).
func b64str(_ s: String) -> String {
    Data(base64Encoded: s).flatMap { String(data: $0, encoding: .utf8) } ?? ""
}

// Show notifications even while the app is foregrounded (iOS otherwise suppresses
// the banner for the active app).
final class NotifDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ c: UNUserNotificationCenter, willPresent n: UNNotification,
                                withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        done([.banner, .sound])
    }
}
let notifDelegate = NotifDelegate()

final class CardsVC: UIViewController, UIScrollViewDelegate, UITextFieldDelegate, UITextViewDelegate, UIGestureRecognizerDelegate, UIContextMenuInteractionDelegate {
    let N: Int32 = 1000

    // The two lockstep trees, keyed by Chuks node id.
    var views: [String: UIView] = [:]
    var ynodes: [String: YGNodeRef] = [:]
    let config: YGConfigRef = YGConfigNew()

    // Discovered from the Chuks tree (not hardcoded): the scroll region + its content.
    var listScroll: UIScrollView?
    var scrollId = ""
    var contentId = ""

    let header = UILabel()                              // host diagnostics only (not app UI)
    var timer: Timer?
    var frame = 0
    var taps: [UITapGestureRecognizer: String] = [:]
    var pillIds = Set<String>()   // views wanting a "full" (pill) corner radius, clamped to height/2 after layout
    var videoPlayers: [String: AVPlayer] = [:]        // per-node ATTACHED (visible) players
    var videoPlayerKey: [String: String] = [:]        // node id -> resource key (for pooling)
    var videoPool: [String: [AVPlayer]] = [:]         // idle, still-primed players by resource
    var videoObs: [ObjectIdentifier: NSObjectProtocol] = [:]  // player -> loop observer (persists across reuse)
    let videoPoolCap = 16                             // bound idle players kept warm
    // Perf harness: auto-scroll the feed while a CADisplayLink measures real frame times.
    var displayLink: CADisplayLink?
    var perfActive = false
    var perfLastTs: CFTimeInterval = 0
    var perfFrames = 0, perfJanky = 0, perfMaxPlayers = 0
    var perfMaxFrame: Double = 0, perfSumTime: Double = 0
    // Velocity sweep: ramp the fling speed to find the breaking point.
    let perfVels: [CGFloat] = [120, 180, 240, 300, 360, 480]
    var perfVelIdx = 0
    var perfDir: CGFloat = 1
    let perfPhaseFrames = 240   // ~4s per velocity phase
    var perfWarmup = 150        // unmeasured frames to prime players + steady state
    var buttonActions: [UIButton: String] = [:]
    var fieldActions: [UITextField: String] = [:]
    var switchActions: [UISwitch: String] = [:]
    var sliderActions: [UISlider: String] = [:]
    var datePickerActions: [UIDatePicker: String] = [:]                   // DatePicker -> onChange action
    var datePickerModes: [UIDatePicker: String] = [:]                     // DatePicker -> "date"|"time"|"datetime"
    var gestureIds: Set<String> = []                                     // Gesture node ids
    var gestureActions: [String: String] = [:]                           // id -> onGesture action
    var menuIds: Set<String> = []                                        // Menu node ids (pull-down action buttons)
    var menuData: [String: [String]] = [:]                               // id -> [label, item0, item1, ...]
    var menuActions: [String: String] = [:]                              // id -> onChange action
    var contextMenuIds: Set<String> = []                                 // ContextMenu node ids (long-press wrappers)
    var contextMenuData: [String: [String]] = [:]                        // id -> [item0, item1, ...]
    var contextMenuActions: [String: String] = [:]                       // id -> onChange action
    var selectIds: Set<String> = []                                       // Select node ids (pull-down menu buttons)
    var selectOptions: [String: [String]] = [:]                          // id -> option labels
    var selectSel: [String: Int] = [:]                                   // id -> chosen index
    var selectActions: [String: String] = [:]                            // id -> onChange action
    var textAreaActions: [UITextView: String] = [:]                      // multiline field -> onChange action
    var textAreaPlaceholders: [UITextView: UILabel] = [:]                // multiline field -> placeholder label
    static var imageCache: [String: UIImage] = [:]                       // URL -> decoded image (shared)
    var bgImageViews: [String: UIImageView] = [:]                        // ImageBackground id -> its backing image view
    var refreshActions: [UIRefreshControl: String] = [:]                 // pull-to-refresh control -> onRefresh action
    var alertIds: Set<String> = []                                       // Alert node ids (native alerts)
    var alertData: [String: [String]] = [:]                              // id -> [title, message, confirm, cancel]
    var alertActions: [String: String] = [:]                             // id -> button-dispatch action
    var presentedAlert: String? = nil                                    // the Alert id currently on screen
    var pressOpacity: [String: CGFloat] = [:]                              // id -> Pressable active alpha (0-1)
    var pressGestures: [UILongPressGestureRecognizer: (String, CGFloat)] = [:]   // gesture -> (action, alpha)
    var modalIds: Set<String> = []                                        // Modal node ids (full-screen overlays)
    var activeModal: String? = nil                                        // the currently-visible Modal
    var sheetModals: Set<String> = []                                     // Modal ids with position=bottom (draggable sheets)
    var modalActions: [String: String] = [:]                             // Modal id -> onDismiss action
    var sheetBg: UIView? = nil                                            // host-drawn sheet surface (rounded top, behind content)
    var sheetHandle: UIView? = nil                                        // host-drawn grab handle pill
    var sheetPan: UIPanGestureRecognizer? = nil                          // drag-to-dismiss recognizer on the sheet
    var shownSheet: String? = nil                                        // the sheet currently on screen (nil = none); a change drives the slide-up
    var connected = false                               // dev mode: is the VM server reachable?

    // ---- engine calls: cgo (prod) or HTTP to the VM dev server (dev) -------
    // Each returns the mutation stream; nil means the server is down (reloading).
    func drainStr() -> String {
        guard let c = chuks_drain() else { return "" }
        let s = String(cString: c); chuks_free_str(c); return s
    }
    func eSetup() { if !DEV_MODE { chuks_set_count(N) } }   // chuks_* bridge auto-runs chuks_init; dev server self-inits on boot
    func eMount() -> String? {
        if DEV_MODE { return devHTTP("/mount", "") }
        _ = chuks_mount(); return drainStr()
    }
    func eTick() -> String? {
        if DEV_MODE { return devHTTP("/tick", "") }
        _ = chuks_tick(); return drainStr()
    }
    func eViewport(_ top: Int32, _ h: Int32) -> String? {
        if DEV_MODE { return devHTTP("/viewport", "\(top) \(h)") }
        _ = chuks_setViewport(top, h); return drainStr()
    }
    // Report the OS appearance to the engine (updates the theme unless the user has
    // overridden). No render here — the caller mounts/ticks afterwards.
    func eColorScheme(_ dark: Bool) {
        if DEV_MODE { return }   // dev-server hot-reload path doesn't wire this; AOT covers it
        chuks_setColorScheme(dark ? 1 : 0)
    }
    // Report the platform + device info to the engine once at launch.
    func ePlatform() {
        if DEV_MODE { return }
        let version = UIDevice.current.systemVersion
        let model = UIDevice.current.model
        let isPad: Int32 = UIDevice.current.userInterfaceIdiom == .pad ? 1 : 0
        "ios".withCString { o in version.withCString { v in model.withCString { m in
            chuks_setPlatform(UnsafeMutablePointer(mutating: o), UnsafeMutablePointer(mutating: v), UnsafeMutablePointer(mutating: m), isPad)
        }}}
    }
    var lastInsets: UIEdgeInsets = UIEdgeInsets(top: -1, left: -1, bottom: -1, right: -1)
    func eInsets(_ i: UIEdgeInsets) {
        if DEV_MODE { return }
        chuks_setInsets(Int32(i.top), Int32(i.right), Int32(i.bottom), Int32(i.left))
    }
    func eEvent(_ action: String) -> String? {
        if DEV_MODE { return devHTTP("/event", action) }
        _ = action.withCString { chuks_dispatch(UnsafeMutablePointer(mutating: $0)) }
        return drainStr()
    }
    func eInput(_ action: String, _ value: String) -> String? {
        if DEV_MODE { return devHTTP("/input", "\(action)\n\(value)") }
        _ = action.withCString { a in value.withCString { v in
            chuks_dispatchInput(UnsafeMutablePointer(mutating: a), UnsafeMutablePointer(mutating: v)) } }
        return drainStr()
    }
    // Async host->engine bridge (F3): report a native capability result for `token`.
    func eResolve(_ token: String, _ payload: String) -> String? {
        if DEV_MODE { return devHTTP("/resolve", "\(token)\n\(payload)") }
        _ = token.withCString { a in payload.withCString { p in
            chuks_resolve(UnsafeMutablePointer(mutating: a), UnsafeMutablePointer(mutating: p)) } }
        return drainStr()
    }
    // Report a capability FAILURE for `token` (error channel).
    func eFail(_ token: String, _ message: String) -> String? {
        if DEV_MODE { return devHTTP("/fail", "\(token)\n\(message)") }
        _ = token.withCString { a in message.withCString { m in
            chuks_fail(UnsafeMutablePointer(mutating: a), UnsafeMutablePointer(mutating: m)) } }
        return drainStr()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        print("BENCHMARK CHUKS: booting…")
        UNUserNotificationCenter.current().delegate = notifDelegate  // foreground banners
        view.backgroundColor = hexColor("0E1116")
        YGConfigSetPointScaleFactor(config, Float(UIScreen.main.scale))

        // The fps/frame header is a benchmark readout — only show it under BENCHMARK.
        if BENCHMARK_MODE {
            header.numberOfLines = 2
            header.textColor = hexColor("E6E9EF")
            header.font = .monospacedSystemFont(ofSize: 12, weight: .semibold)
            header.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(header)
            NSLayoutConstraint.activate([
                header.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
                header.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 14),
                header.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -14),
            ])
        }

        NotificationCenter.default.addObserver(self, selector: #selector(kbShow(_:)),
            name: UIResponder.keyboardWillShowNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(kbHide),
            name: UIResponder.keyboardWillHideNotification, object: nil)

        // Tap anywhere outside a text field to dismiss the keyboard. cancelsTouchesInView
        // = false + simultaneous recognition so it never swallows a node's onPress tap
        // or the scroll gesture; the delegate skips taps that land on a text field so
        // focusing another field still works.
        let dismissTap = UITapGestureRecognizer(target: self, action: #selector(dismissKeyboard))
        dismissTap.cancelsTouchesInView = false
        dismissTap.delegate = self
        view.addGestureRecognizer(dismissTap)

        eSetup()
        ePlatform()                                                 // report platform + device info
        eColorScheme(traitCollection.userInterfaceStyle == .dark)   // open in the OS appearance
        if let s = eMount() { apply(s); connected = true }   // build the app tree
        // (dev: if the server isn't up yet, step() reconnects + remounts)

        timer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { [weak self] _ in self?.step() }
        // Auto-start the perf harness after a warmup (benchmark builds only — otherwise
        // it would auto-scroll the on-screen Scroll, e.g. the Components gallery).
        if BENCHMARK_MODE {
            DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) { [weak self] in self?.startPerfScroll() }
        }
    }

    // The OS appearance changed (Settings, Control Center, or automatic day/night):
    // report it and re-render. The engine follows it unless the user has overridden.
    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        let now = traitCollection.userInterfaceStyle
        if now != previous?.userInterfaceStyle {
            eColorScheme(now == .dark)
            if let s = eTick() { apply(s); relayout() }
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let ins = view.safeAreaInsets                  // report safe insets to the engine on change
        if ins != lastInsets {
            lastInsets = ins
            eInsets(ins)
            if let s = eTick() { apply(s) }             // re-render so inset-using components update
        }
        relayout()                                     // position app + scroll (frames known)
        if pushViewport() { relayout() }               // mount visible window, then place it
    }

    // Report the scroll position to Chuks; it mounts/recycles to match.
    @discardableResult
    func pushViewport() -> Bool {
        guard let sc = listScroll else { return false }
        let h = Int32(sc.bounds.height); if h <= 0 { return false }
        let top = Int32(max(0, sc.contentOffset.y))
        guard let s = eViewport(top, h) else { connected = false; return false }
        if !s.isEmpty { apply(s); return true }
        return false
    }

    func scrollViewDidScroll(_ sv: UIScrollView) {
        if pushViewport() { relayout() }
        if !perfActive { headerText("scroll \(Int(sv.contentOffset.y))pt") }
    }

    // ── Perf harness ───────────────────────────────────────────────────────
    // Fling the feed at a fast, steady speed and measure the actual per-frame
    // interval (CADisplayLink fires once per display refresh; a dropped frame
    // shows up as a longer interval). Reports avg fps, worst frame, and how many
    // frames ran slower than 50 fps. NOTE: the simulator runs on the Mac's CPU/GPU
    // and is NOT a device benchmark — this is a jank/hang smoke test of the
    // framework's scroll → reconcile → layout pipeline under load.
    func startPerfScroll() {
        if perfActive { return }
        guard let sc = listScroll else {           // list not mounted yet: retry
            print("BENCHMARK CHUKS: waiting for list…")
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in self?.startPerfScroll() }
            return
        }
        sc.setContentOffset(.zero, animated: false)
        perfActive = true; perfVelIdx = 0; perfDir = 1; perfWarmup = 150
        resetPhase()
        perfLastTs = CACurrentMediaTime()
        let dl = CADisplayLink(target: self, selector: #selector(perfTick))
        dl.add(to: .main, forMode: .common); displayLink = dl
        print("BENCHMARK CHUKS: warming up…")
    }
    func resetPhase() {
        perfFrames = 0; perfJanky = 0; perfMaxFrame = 0; perfSumTime = 0; perfMaxPlayers = 0
    }
    @objc func perfTick() {
        let now = CACurrentMediaTime(), dt = now - perfLastTs; perfLastTs = now
        // Unmeasured warmup: prime video players + reach steady state before sampling.
        if perfWarmup > 0 {
            perfWarmup -= 1
            if let sc = listScroll {
                let maxY = max(0, sc.contentSize.height - sc.bounds.height)
                var y = sc.contentOffset.y + 90 * perfDir
                if y >= maxY { y = maxY; perfDir = -1 }
                if y <= 0 { y = 0; perfDir = 1 }
                sc.setContentOffset(CGPoint(x: 0, y: y), animated: false)
            }
            if perfWarmup == 0 { resetPhase(); print("BENCHMARK CHUKS: velocity sweep starting…") }
            return
        }
        if perfFrames > 0 {                          // skip the first interval
            perfSumTime += dt
            if dt > perfMaxFrame { perfMaxFrame = dt }
            if dt > 0.020 { perfJanky += 1 }         // slower than 50 fps
        }
        perfFrames += 1
        if videoPlayers.count > perfMaxPlayers { perfMaxPlayers = videoPlayers.count }
        guard let sc = listScroll else { stopPerf(); return }
        let maxY = max(0, sc.contentSize.height - sc.bounds.height)
        let vel = perfVels[perfVelIdx]
        var y = sc.contentOffset.y + vel * perfDir   // ping-pong so fast phases keep sampling
        if y >= maxY { y = maxY; perfDir = -1 }
        if y <= 0 { y = 0; perfDir = 1 }
        sc.setContentOffset(CGPoint(x: 0, y: y), animated: false)
        if perfFrames >= perfPhaseFrames { logPhase(vel) }
    }
    func logPhase(_ vel: CGFloat) {
        let n = max(1, perfFrames - 1)
        let avg = Double(n) / max(0.0001, perfSumTime)
        let msg = String(format: "BENCHMARK CHUKS vel=%d: avg %.0f fps | worst frame %.1f ms | janky(<50fps) %d/%d | %d video players peak | mem %.0f MB",
                         Int(vel), avg, perfMaxFrame * 1000, perfJanky, n, perfMaxPlayers, physFootprintMB())
        print(msg); NSLog(msg); header.text = msg
        perfVelIdx += 1
        if perfVelIdx >= perfVels.count { stopPerf(); return }
        resetPhase()
    }
    func stopPerf() {
        perfActive = false; displayLink?.invalidate(); displayLink = nil
        print("BENCHMARK CHUKS: sweep done")
    }

    func step() {
        // dev mode: if the server went away (a reload), keep trying to reconnect,
        // then remount from a fresh /mount -- with the engine's state restored.
        if DEV_MODE && !connected {
            guard let s = eMount() else { headerText("dev server down — reloading…"); return }
            connected = true
            remount(s)
            _ = pushViewport(); relayout()
            headerText("↻ hot-reloaded (state preserved)")
            return
        }
        frame += 1
        guard let s = eTick() else { connected = false; headerText("dev server down — reloading…"); return }
        apply(s); relayout()
        headerText("frame \(frame): live")
    }

    // Tear down the view + Yoga trees and rebuild from a fresh mount stream. Used
    // on hot reload: the host process stays alive, only the tree is rebuilt.
    func remount(_ mountStream: String) {
        for (_, v) in views { v.removeFromSuperview() }
        if let app = ynodes["app"] { YGNodeFreeRecursive(app) }   // frees the whole subtree
        views.removeAll(); ynodes.removeAll()
        taps.removeAll(); buttonActions.removeAll(); fieldActions.removeAll(); switchActions.removeAll(); sliderActions.removeAll()
        selectIds.removeAll(); selectOptions.removeAll(); selectSel.removeAll(); selectActions.removeAll()
        textAreaActions.removeAll(); textAreaPlaceholders.removeAll()
        alertIds.removeAll(); alertData.removeAll(); alertActions.removeAll(); presentedAlert = nil
        bgImageViews.removeAll(); refreshActions.removeAll()
        pressGestures.removeAll(); pressOpacity.removeAll(); modalIds.removeAll(); activeModal = nil
        sheetModals.removeAll(); modalActions.removeAll(); shownSheet = nil
        sheetBg?.removeFromSuperview(); sheetBg = nil
        sheetHandle?.removeFromSuperview(); sheetHandle = nil; sheetPan = nil
        listScroll = nil; scrollId = ""; contentId = ""
        apply(mountStream)
    }

    func headerText(_ line2: String) {
        header.text = "Chuks app (100% Chuks): \(N) cards, \(views.count) live views\n\(line2)"
    }

    // return key dismisses the keyboard
    func textFieldShouldReturn(_ tf: UITextField) -> Bool { tf.resignFirstResponder(); return true }

    // Dismiss the keyboard when tapping outside any field.
    @objc func dismissKeyboard() { view.endEditing(true) }
    // Don't hijack a tap that lands on a text field (let it focus normally); do
    // dismiss for taps anywhere else.
    func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        return !(touch.view is UITextField)
    }
    // Let the dismiss tap coexist with node onPress taps and the scroll gestures.
    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        return true
    }

    @objc func kbShow(_ n: Notification) {
        guard let sc = listScroll,
              let end = (n.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue else { return }
        let inset = max(0, end.height - view.safeAreaInsets.bottom)
        sc.contentInset.bottom = inset
        sc.verticalScrollIndicatorInsets.bottom = inset
    }
    @objc func kbHide() {
        listScroll?.contentInset.bottom = 0
        listScroll?.verticalScrollIndicatorInsets.bottom = 0
    }

    // ---- apply a Chuks mutation stream to both trees -----------------------
    // Status bar contrast follows the app theme (set in syncChrome from the app bg).
    private var statusBarStyle: UIStatusBarStyle = .lightContent   // theme-derived (auto)
    private var sbStyleOverride: UIStatusBarStyle? = nil           // explicit StatusBar style
    private var sbHiddenOverride = false                           // explicit StatusBar hidden
    override var preferredStatusBarStyle: UIStatusBarStyle { sbStyleOverride ?? statusBarStyle }
    override var prefersStatusBarHidden: Bool { sbHiddenOverride }

    // After every mutation batch, mirror the app's ROOT background onto the VC view
    // (so the safe-area strips match instead of staying a hardcoded dark) and pick
    // the status-bar contrast from it. This is what carries setTheme() into the host
    // chrome — the app content already repaints from the S| stream, the chrome did not.
    func syncChrome() {
        guard let appBg = views["app"]?.backgroundColor else { return }
        view.backgroundColor = appBg
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        appBg.getRed(&r, green: &g, blue: &b, alpha: &a)
        statusBarStyle = (0.299 * r + 0.587 * g + 0.114 * b) > 0.5 ? .darkContent : .lightContent
        setNeedsStatusBarAppearanceUpdate()
    }

    func apply(_ stream: String) {
        for raw in stream.split(separator: "\n") {
            let f = raw.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
            guard let op = f.first else { continue }
            switch op {
            case "C" where f.count >= 3: make(f[1], f[2])
            case "S" where f.count >= 3: style(f[1], f[2])
            case "P" where f.count >= 3: setText(f[1], f[2])
            case "T" where f.count >= 3: bindAction(f[1], action: f[2])
            case "I" where f.count >= 4: insert(f[1], parent: f[2], index: Int(f[3]) ?? 0)
            case "R" where f.count >= 2: remove(f[1])
            case "X" where f.count >= 3:
                // Async host->engine command: X|token|capability|args. Run AFTER this
                // apply() finishes (main.async), so a sync capability's resolve() doesn't
                // re-enter apply() mid-parse. args may contain '|'.
                let token = f[1], cap = f[2]
                let args = f.count >= 4 ? f[3...].joined(separator: "|") : ""
                DispatchQueue.main.async { [weak self] in self?.handleCommand(token, cap, args) }
            default: break
            }
        }
        syncChrome()
    }

    // Deliver a native capability result back to the engine and apply the re-render.
    // Like every other apply path, re-run Yoga layout after applying: a stream tick
    // that changes a label's width (a growing counter, live data) must re-measure
    // its node, or the frame lags the text and truncates it for a frame (the jank).
    func resolve(_ token: String, _ payload: String) { if let s = eResolve(token, payload) { apply(s); relayout() } }
    // Report a capability failure back to the engine (fires the request's onErr).
    func fail(_ token: String, _ message: String) { if let s = eFail(token, message) { apply(s); relayout() } }

    // Live native subscriptions (stream token -> timer/observer), for teardown.
    var activeStreams: [String: Timer] = [:]
    var streamTeardown: [String: () -> Void] = [:]   // real OS streams: unregister closure, run on __cancel__
    var orientationTokens = Set<String>()   // orientation.watch tokens, so a lock can re-emit the new value
    var locFixes: [String: LocFix] = [:]    // live Location managers, keyed by token (once + watch)
    let motion = CMMotionManager()          // one shared motion manager; sensors fan out to token sets
    var accelTokens = Set<String>()
    var gyroTokens = Set<String>()
    var magTokens = Set<String>()
    var audioPlayer: AVPlayer? = nil   // single-track audio playback (Tier B); AVPlayer handles mp4 audio
    let speech = AVSpeechSynthesizer()  // text-to-speech (Tier B)

    // Execute a native capability requested via an `X|` command (F3). Same UIKit
    // implementations as the SwiftUI host; only presentShare differs (this host IS a
    // UIViewController, so it presents directly).
    func handleCommand(_ token: String, _ cap: String, _ args: String) {
        switch cap {
        case "__cancel__":
            activeStreams[token]?.invalidate(); activeStreams[token] = nil
            streamTeardown[token]?(); streamTeardown[token] = nil
        case "pulse.watch":
            // ~7Hz: smooth per-tick re-render (UIKit relayouts the tree per batch, so
            // a faster test stream janks the counter). Real streams tick far slower.
            var n = 0
            let t = Timer.scheduledTimer(withTimeInterval: 0.15, repeats: true) { [weak self] _ in
                n += 1; self?.resolve(token, String(n))
            }
            activeStreams[token] = t
        case "battery.watch":
            UIDevice.current.isBatteryMonitoringEnabled = true
            let emit = { [weak self] in
                let d = UIDevice.current
                let lvl = d.batteryLevel < 0 ? -1 : Int((d.batteryLevel * 100).rounded())
                let chg = (d.batteryState == .charging || d.batteryState == .full) ? 1 : 0
                self?.resolve(token, "\(lvl),\(chg)")
            }
            let o1 = NotificationCenter.default.addObserver(forName: UIDevice.batteryLevelDidChangeNotification, object: nil, queue: .main) { _ in emit() }
            let o2 = NotificationCenter.default.addObserver(forName: UIDevice.batteryStateDidChangeNotification, object: nil, queue: .main) { _ in emit() }
            streamTeardown[token] = { NotificationCenter.default.removeObserver(o1); NotificationCenter.default.removeObserver(o2) }
            emit()
        case "appstate.watch":
            let emit: (String) -> Void = { [weak self] s in self?.resolve(token, s) }
            let a = NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in emit("active") }
            let i = NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { _ in emit("inactive") }
            let b = NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { _ in emit("background") }
            streamTeardown[token] = { for o in [a, i, b] { NotificationCenter.default.removeObserver(o) } }
            emit(UIApplication.shared.applicationState == .active ? "active" : UIApplication.shared.applicationState == .background ? "background" : "inactive")
        case "network.watch":
            let mon = NWPathMonitor()
            mon.pathUpdateHandler = { [weak self] path in
                let s = path.status != .satisfied ? "none" : path.usesInterfaceType(.wifi) ? "wifi" : path.usesInterfaceType(.cellular) ? "cellular" : "other"
                DispatchQueue.main.async { self?.resolve(token, s) }
            }
            mon.start(queue: DispatchQueue.global(qos: .utility))
            streamTeardown[token] = { mon.cancel() }
        case "location.once":
            let fix = LocFix(once: true,
                onFix: { [weak self] s in self?.resolve(token, s); self?.locFixes[token] = nil },
                onErr: { [weak self] m in self?.fail(token, m); self?.locFixes[token] = nil })
            locFixes[token] = fix; fix.start()
        case "location.watch":
            let fix = LocFix(once: false,
                onFix: { [weak self] s in self?.resolve(token, s) },
                onErr: { [weak self] m in self?.fail(token, m) })
            locFixes[token] = fix
            streamTeardown[token] = { [weak self] in self?.locFixes[token]?.stop(); self?.locFixes[token] = nil }
            fix.start()
        case "motion.accel":
            if !motion.isAccelerometerAvailable { fail(token, "accelerometer unavailable"); break }
            accelTokens.insert(token)
            if !motion.isAccelerometerActive {
                motion.accelerometerUpdateInterval = 1.0 / 20.0
                motion.startAccelerometerUpdates(to: .main) { [weak self] data, _ in
                    guard let self = self, let a = data?.acceleration else { return }
                    let s = "\(a.x),\(a.y),\(a.z)"; for t in self.accelTokens { self.resolve(t, s) }
                }
            }
            streamTeardown[token] = { [weak self] in
                self?.accelTokens.remove(token)
                if self?.accelTokens.isEmpty == true { self?.motion.stopAccelerometerUpdates() }
            }
        case "motion.gyro":
            if !motion.isGyroAvailable { fail(token, "gyroscope unavailable"); break }
            gyroTokens.insert(token)
            if !motion.isGyroActive {
                motion.gyroUpdateInterval = 1.0 / 20.0
                motion.startGyroUpdates(to: .main) { [weak self] data, _ in
                    guard let self = self, let r = data?.rotationRate else { return }
                    let s = "\(r.x),\(r.y),\(r.z)"; for t in self.gyroTokens { self.resolve(t, s) }
                }
            }
            streamTeardown[token] = { [weak self] in
                self?.gyroTokens.remove(token)
                if self?.gyroTokens.isEmpty == true { self?.motion.stopGyroUpdates() }
            }
        case "motion.mag":
            if !motion.isMagnetometerAvailable { fail(token, "magnetometer unavailable"); break }
            magTokens.insert(token)
            if !motion.isMagnetometerActive {
                motion.magnetometerUpdateInterval = 1.0 / 20.0
                motion.startMagnetometerUpdates(to: .main) { [weak self] data, _ in
                    guard let self = self, let f = data?.magneticField else { return }
                    let s = "\(f.x),\(f.y),\(f.z)"; for t in self.magTokens { self.resolve(t, s) }
                }
            }
            streamTeardown[token] = { [weak self] in
                self?.magTokens.remove(token)
                if self?.magTokens.isEmpty == true { self?.motion.stopMagnetometerUpdates() }
            }
        case "debug.activeStreams": resolve(token, String(activeStreams.count + streamTeardown.count))
        case "debug.fail": fail(token, "simulated native failure")
        case "permission.status": permStatus(args, token)
        case "permission.request": permRequest(args, token)
        case "fs.write":
            if let bar = args.firstIndex(of: "|") {
                let name = String(args[..<bar]), b64 = String(args[args.index(after: bar)...])
                if let d = Data(base64Encoded: b64), let content = String(data: d, encoding: .utf8) {
                    try? content.write(to: appFile(name), atomically: true, encoding: .utf8)
                }
            }
        case "fs.read":
            if let s = try? String(contentsOf: appFile(args), encoding: .utf8) { resolve(token, s) }
            else { fail(token, "no such file: \(args)") }
        case "fs.list":
            let names = (try? FileManager.default.contentsOfDirectory(atPath: appDir().path)) ?? []
            resolve(token, names.joined(separator: "\n"))
        case "fs.delete": try? FileManager.default.removeItem(at: appFile(args))
        case "secure.set":
            if let bar = args.firstIndex(of: "|") {
                let key = String(args[..<bar]), b64 = String(args[args.index(after: bar)...])
                if let d = Data(base64Encoded: b64), let v = String(data: d, encoding: .utf8) { keychainSet(key, v) }
            }
        case "secure.get":
            if let v = keychainGet(args) { resolve(token, v) } else { fail(token, "no such key: \(args)") }
        case "secure.delete": keychainDelete(args)
        case "notif.notify":
            // args = "b64title|b64body"
            let parts = args.split(separator: "|", maxSplits: 1).map(String.init)
            let content = UNMutableNotificationContent()
            content.title = b64str(parts.first ?? ""); content.body = parts.count > 1 ? b64str(parts[1]) : ""
            content.sound = .default
            UNUserNotificationCenter.current().add(UNNotificationRequest(
                identifier: UUID().uuidString, content: content,
                trigger: UNTimeIntervalNotificationTrigger(timeInterval: 2, repeats: false)))
        case "audio.play":
            if let url = Bundle.main.url(forResource: args, withExtension: nil) {
                try? AVAudioSession.sharedInstance().setCategory(.playback)
                try? AVAudioSession.sharedInstance().setActive(true)
                audioPlayer?.pause()                 // stop any previous track: no overlapping players
                let p = AVPlayer(url: url); audioPlayer = p; p.play()
            }
        case "audio.pause": audioPlayer?.pause()
        case "audio.resume": audioPlayer?.play()
        case "audio.stop": audioPlayer?.pause(); audioPlayer?.seek(to: .zero)
        case "audio.position":
            let cur = audioPlayer.map { CMTimeGetSeconds($0.currentTime()) } ?? 0
            let dur = audioPlayer?.currentItem?.duration.seconds ?? 0
            resolve(token, "\(cur.isFinite ? Int(cur*1000) : 0)/\(dur.isFinite ? Int(dur*1000) : 0)")
        case "tts.speak":
            try? AVAudioSession.sharedInstance().setCategory(.playback)
            try? AVAudioSession.sharedInstance().setActive(true)
            if speech.isSpeaking { speech.stopSpeaking(at: .immediate) }
            speech.speak(AVSpeechUtterance(string: b64str(args)))
        case "tts.stop": speech.stopSpeaking(at: .immediate)
        case "tts.isSpeaking": resolve(token, speech.isSpeaking ? "1" : "0")
        case "clipboard.set": UIPasteboard.general.string = args
        case "clipboard.get": resolve(token, UIPasteboard.general.string ?? "")
        case "linking.open":
            if let u = URL(string: args) { UIApplication.shared.open(u) }
        case "linking.canOpen":
            let ok = (URL(string: args).map { UIApplication.shared.canOpenURL($0) }) ?? false
            resolve(token, ok ? "1" : "0")
        case "share.text": presentShare([args])
        case "share.url": presentShare([URL(string: args) ?? args])
        case "haptics.impact": fireHaptic(args)
        case "torch.set": setTorch(args == "1")
        case "brightness.set": if let v = Double(args) { UIScreen.main.brightness = CGFloat(max(0, min(1, v))) }
        case "brightness.keepAwake": UIApplication.shared.isIdleTimerDisabled = (args == "1")
        case "orientation.watch":
            UIDevice.current.beginGeneratingDeviceOrientationNotifications()
            orientationTokens.insert(token)
            let emit: () -> Void = { [weak self] in self?.resolve(token, currentOrientationString()) }
            let o = NotificationCenter.default.addObserver(forName: UIDevice.orientationDidChangeNotification, object: nil, queue: .main) { _ in emit() }
            streamTeardown[token] = { [weak self] in self?.orientationTokens.remove(token); NotificationCenter.default.removeObserver(o); UIDevice.current.endGeneratingDeviceOrientationNotifications() }
            emit()
        case "orientation.lock":
            applyOrientationLock(args)
            // A lock changes the interface orientation without a device-rotation notification,
            // so re-emit the new value to the watchers once the geometry update settles.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
                guard let self = self else { return }
                self.orientationTokens.forEach { self.resolve($0, currentOrientationString()) }
            }
        default: break
        }
    }

    private func fireHaptic(_ style: String) {
        switch style {
        case "light": UIImpactFeedbackGenerator(style: .light).impactOccurred()
        case "medium": UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        case "heavy": UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
        case "success": UINotificationFeedbackGenerator().notificationOccurred(.success)
        case "warning": UINotificationFeedbackGenerator().notificationOccurred(.warning)
        case "error": UINotificationFeedbackGenerator().notificationOccurred(.error)
        case "selection": UISelectionFeedbackGenerator().selectionChanged()
        default: UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        }
    }

    private func setTorch(_ on: Bool) {
        guard let dev = AVCaptureDevice.default(for: .video), dev.hasTorch else { return }
        try? dev.lockForConfiguration()
        try? dev.setTorchModeOn(level: 1.0)
        if !on { dev.torchMode = .off }
        dev.unlockForConfiguration()
    }

    // Permission (F2): read the current grant without prompting.
    private func permStatus(_ kind: String, _ token: String) {
        switch kind {
        case "camera": resolve(token, avAuthStr(AVCaptureDevice.authorizationStatus(for: .video)))
        case "microphone": resolve(token, avAuthStr(AVCaptureDevice.authorizationStatus(for: .audio)))
        case "photos": resolve(token, phAuthStr(PHPhotoLibrary.authorizationStatus(for: .readWrite)))
        case "location": resolve(token, clAuthStr(CLLocationManager().authorizationStatus))
        case "notifications":
            UNUserNotificationCenter.current().getNotificationSettings { s in
                DispatchQueue.main.async { self.resolve(token, unAuthStr(s.authorizationStatus)) }
            }
        default: resolve(token, "undetermined")
        }
    }
    // Show the OS dialog once and report the outcome (all async).
    private func permRequest(_ kind: String, _ token: String) {
        switch kind {
        case "camera": AVCaptureDevice.requestAccess(for: .video) { g in DispatchQueue.main.async { self.resolve(token, g ? "granted" : "denied") } }
        case "microphone": AVCaptureDevice.requestAccess(for: .audio) { g in DispatchQueue.main.async { self.resolve(token, g ? "granted" : "denied") } }
        case "photos": PHPhotoLibrary.requestAuthorization(for: .readWrite) { s in DispatchQueue.main.async { self.resolve(token, phAuthStr(s)) } }
        case "notifications":
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { g, _ in
                DispatchQueue.main.async { self.resolve(token, g ? "granted" : "denied") }
            }
        case "location": locPerm.request { s in self.resolve(token, s) }
        default: fail(token, "unknown permission: \(kind)")
        }
    }
    private let locPerm = LocPerm()

    // File system (Tier B): the app's private Documents directory.
    private func appDir() -> URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }
    private func appFile(_ name: String) -> URL { appDir().appendingPathComponent(name) }

    private func presentShare(_ items: [Any]) {
        let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
        vc.popoverPresentationController?.sourceView = self.view   // iPad anchor
        self.present(vc, animated: true)
    }

    func setText(_ id: String, _ t: String) {
        if selectIds.contains(id) {                                  // a Select's "text" is its tab-joined options
            selectOptions[id] = t.components(separatedBy: "\t")
            rebuildSelectMenu(id)
            return
        }
        if menuIds.contains(id) { menuData[id] = t.components(separatedBy: "\t"); rebuildMenu(id); return }   // [label, items...]
        if contextMenuIds.contains(id) { contextMenuData[id] = t.components(separatedBy: "\t"); return }        // items
        if alertIds.contains(id) { alertData[id] = t.components(separatedBy: "\t"); return }   // Alert's tab-joined fields
        if let iv = bgImageViews[id], t.hasPrefix("http") { loadRemoteImage(t, into: iv); return }   // ImageBackground URL
        if let iv = views[id] as? UIImageView, t.hasPrefix("http") { loadRemoteImage(t, into: iv); return }   // remote Image URL
        if let iv = views[id] as? UIImageView, !t.isEmpty {   // bundled local asset (e.g. chuks-logo.png)
            if let url = Bundle.main.url(forResource: t, withExtension: nil) { iv.image = UIImage(contentsOfFile: url.path) }
            return
        }
        if let iv = bgImageViews[id], !t.isEmpty {            // bundled ImageBackground asset
            if let url = Bundle.main.url(forResource: t, withExtension: nil) { iv.image = UIImage(contentsOfFile: url.path) }
            return
        }
        if let wv = views[id] as? WKWebView { if let u = URL(string: t) { wv.load(URLRequest(url: u)) }; return }   // WebView URL
        if let cv = views[id] as? CanvasView { cv.shapes = t; return }                // Canvas's "text" is the shape list
        if let mv = views[id] as? MKMapView {                                        // Map's "text" is "lat,lng,zoom"
            let p = t.components(separatedBy: ",")
            if p.count == 3, let lat = Double(p[0]), let lng = Double(p[1]), let z = Double(p[2]) {
                let span = 360.0 / pow(2.0, z)                                        // degrees visible at this zoom
                let region = MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: lat, longitude: lng),
                                                span: MKCoordinateSpan(latitudeDelta: span, longitudeDelta: span))
                mv.setRegion(region, animated: false)
                mv.removeAnnotations(mv.annotations)
                let pin = MKPointAnnotation(); pin.coordinate = CLLocationCoordinate2D(latitude: lat, longitude: lng)
                mv.addAnnotation(pin)
            }
            return
        }
        if let dp = views[id] as? UIDatePicker {                                     // DatePicker's "text" is its ISO value
            if let d = Self.parseISO(t, mode: datePickerModes[dp] ?? "date") { dp.date = d }
            return
        }
        if let l = views[id] as? UILabel { l.text = t; if let n = ynodes[id] { YGNodeMarkDirty(n) } }
        else if let b = views[id] as? UIButton { b.setTitle(t, for: .normal) }
        else if let tf = views[id] as? UITextField { tf.placeholder = t }
        else if let tv = views[id] as? UITextView { textAreaPlaceholders[tv]?.text = t }   // TextArea placeholder
    }

    func make(_ id: String, _ kind: String) {
        if views[id] != nil { return }
        let v: UIView
        let n = YGNodeNewWithConfig(config)
        switch kind {
        case "Text":
            let l = UILabel(); l.font = .systemFont(ofSize: 14); l.numberOfLines = 0; v = l   // 0 = wrap to as many lines as fit the measured (Yoga) width
            YGNodeSetContext(n, Unmanaged.passUnretained(l).toOpaque())
            YGNodeSetMeasureFunc(n, measureText)
        case "Image":
            let iv = UIImageView(); iv.contentMode = .scaleAspectFit; iv.clipsToBounds = true; v = iv
        case "ImageBackground":
            let box = UIView(); box.clipsToBounds = true
            let iv = UIImageView(); iv.contentMode = .scaleAspectFill; iv.clipsToBounds = true
            iv.frame = box.bounds; iv.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            box.addSubview(iv)                              // background, behind children (inserted later)
            bgImageViews[id] = iv; v = box
        case "Video":
            let vv = VideoView(); vv.playerLayer.videoGravity = .resizeAspectFill; vv.clipsToBounds = true; v = vv
        case "WebView":
            let wv = WKWebView(); wv.clipsToBounds = true
            wv.scrollView.showsVerticalScrollIndicator = false; wv.isOpaque = false; v = wv
        case "Button":
            let b = UIButton(type: .system)
            b.titleLabel?.font = .systemFont(ofSize: 14, weight: .bold)
            b.addTarget(self, action: #selector(handleButton(_:)), for: .touchUpInside)
            v = b
        case "Input":
            let tf = UITextField()
            tf.autocorrectionType = .no; tf.autocapitalizationType = .none
            tf.clearButtonMode = .whileEditing; tf.returnKeyType = .search
            tf.leftView = UIView(frame: CGRect(x: 0, y: 0, width: 10, height: 1)); tf.leftViewMode = .always
            tf.delegate = self
            tf.addTarget(self, action: #selector(inputChanged(_:)), for: .editingChanged)
            v = tf
        case "Spinner":
            let ai = UIActivityIndicatorView(style: .medium)   // ~20pt; scaled to `w` in style()
            ai.startAnimating(); ai.color = .gray
            v = ai
        case "Switch":
            let sw = UISwitch()
            sw.addTarget(self, action: #selector(handleSwitch(_:)), for: .valueChanged)
            // Yoga needs a size for a leaf native control; use the UISwitch intrinsic.
            let sz = sw.intrinsicContentSize
            YGNodeStyleSetWidth(n, Float(sz.width)); YGNodeStyleSetHeight(n, Float(sz.height))
            v = sw
        case "Slider":
            let sl = UISlider()
            sl.isContinuous = true
            sl.addTarget(self, action: #selector(sliderChanged(_:)), for: .valueChanged)
            v = sl
        case "DatePicker", "DatePickerInline":   // same UIDatePicker; the "dpd" style picks compact/inline/wheels
            let dp = UIDatePicker()
            if #available(iOS 14.0, *) { dp.preferredDatePickerStyle = .compact }   // a tappable native field
            dp.datePickerMode = .date                                               // refined by the "dp" style
            dp.contentHorizontalAlignment = .leading                                // hug the leading edge (match Android/SwiftUI)
            dp.addTarget(self, action: #selector(dateChanged(_:)), for: .valueChanged)
            datePickerModes[dp] = "date"
            let sz = dp.intrinsicContentSize                                        // Yoga needs a leaf size
            YGNodeStyleSetWidth(n, Float(sz.width)); YGNodeStyleSetHeight(n, Float(sz.height))
            v = dp
        case "Progress":
            let pv = UIProgressView(progressViewStyle: .default)
            pv.layer.cornerRadius = 3; pv.clipsToBounds = true
            pv.transform = CGAffineTransform(scaleX: 1, y: 1.6)   // thicken the ~4pt native track to ~6pt
            v = pv
        case "TextArea":
            let tv = UITextView()
            tv.font = .systemFont(ofSize: 15)
            tv.delegate = self
            tv.textContainerInset = UIEdgeInsets(top: 10, left: 8, bottom: 10, right: 8)
            tv.backgroundColor = .clear                         // the field bg comes from the "bg" style
            let ph = UILabel()                                  // UITextView has no placeholder: overlay one
            ph.font = .systemFont(ofSize: 15)
            ph.textColor = .placeholderText
            ph.numberOfLines = 0
            ph.translatesAutoresizingMaskIntoConstraints = false
            tv.addSubview(ph)
            NSLayoutConstraint.activate([
                ph.topAnchor.constraint(equalTo: tv.topAnchor, constant: 10),
                ph.leadingAnchor.constraint(equalTo: tv.leadingAnchor, constant: 12),
                ph.trailingAnchor.constraint(lessThanOrEqualTo: tv.trailingAnchor, constant: -12),
            ])
            textAreaPlaceholders[tv] = ph
            v = tv
        case "Select":
            let b = UIButton(type: .system)
            b.showsMenuAsPrimaryAction = true                       // tap opens the pull-down menu
            b.titleLabel?.font = .systemFont(ofSize: 15)
            b.contentHorizontalAlignment = .left
            b.contentEdgeInsets = UIEdgeInsets(top: 0, left: 14, bottom: 0, right: 36)   // room + trailing chevron
            // a down-chevron pinned to the trailing edge (a subview can use Auto Layout
            // even though the button itself is frame-driven by Yoga)
            let chev = UIImageView(image: UIImage(systemName: "chevron.up.chevron.down"))
            chev.tintColor = .secondaryLabel
            chev.contentMode = .scaleAspectFit
            chev.translatesAutoresizingMaskIntoConstraints = false
            b.addSubview(chev)
            NSLayoutConstraint.activate([
                chev.trailingAnchor.constraint(equalTo: b.trailingAnchor, constant: -14),
                chev.centerYAnchor.constraint(equalTo: b.centerYAnchor),
                chev.widthAnchor.constraint(equalToConstant: 13),
                chev.heightAnchor.constraint(equalToConstant: 16),
            ])
            selectIds.insert(id); v = b
        case "Menu":
            let b = UIButton(type: .system)
            b.showsMenuAsPrimaryAction = true                       // tap opens the pull-down menu
            b.titleLabel?.font = .systemFont(ofSize: 15)
            b.contentHorizontalAlignment = .left
            b.contentEdgeInsets = UIEdgeInsets(top: 0, left: 14, bottom: 0, right: 14)
            menuIds.insert(id); v = b
        case "ContextMenu":
            let cv = UIView()
            cv.addInteraction(UIContextMenuInteraction(delegate: self))   // long-press opens the menu
            contextMenuIds.insert(id); v = cv
        case "Map":
            let mv = MKMapView()
            mv.isRotateEnabled = false
            v = mv
        case "Canvas":
            let cv = CanvasView(); cv.backgroundColor = .clear; cv.isOpaque = false
            v = cv
        case "Gesture":
            let gv = UIView()
            for dir: UISwipeGestureRecognizer.Direction in [.left, .right, .up, .down] {
                let sw = UISwipeGestureRecognizer(target: self, action: #selector(handleSwipe(_:)))
                sw.direction = dir; gv.addGestureRecognizer(sw)
            }
            let dt = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
            dt.numberOfTapsRequired = 2; gv.addGestureRecognizer(dt)
            gv.addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:))))
            gestureIds.insert(id); v = gv
        case "Scroll":
            let sc = UIScrollView(); sc.delegate = self; sc.keyboardDismissMode = .onDrag
            sc.showsVerticalScrollIndicator = true
            listScroll = sc; scrollId = id; v = sc
        case "Modal":
            let mv = UIView(); mv.backgroundColor = UIColor(white: 0, alpha: 0.5)   // scrim
            mv.isHidden = true                     // shown when mvis=1
            modalIds.insert(id); v = mv
        case "Alert":
            let a = UIView(); a.isUserInteractionEnabled = false   // invisible placeholder; the OS alert shows on avis=1
            alertIds.insert(id); v = a
        default:
            v = UIView()
        }
        v.translatesAutoresizingMaskIntoConstraints = true   // we drive .frame directly
        views[id] = v
        ynodes[id] = n
    }

    // parse "k=v;k=v" -> Yoga style (layout) + UIView style (visual)
    func style(_ id: String, _ s: String) {
        guard let n = ynodes[id], let v = views[id] else { return }
        let label = v as? UILabel
        let btn = v as? UIButton
        let field = v as? UITextField
        let imgView = v as? UIImageView
        let hasShadow = s.contains("shadow=")   // a shadow needs masksToBounds=false, so don't clip
        var fs: CGFloat = 14, fw: UIFont.Weight = .regular
        var customFont = ""   // a registered font family (e.g. an icon font)
        // animation: collect transform + opacity across the loop, apply (animated) after
        var tx: CGFloat = 0, ty: CGFloat = 0, sc: CGFloat = 1, rot: CGFloat = 0
        var hasTransform = false, opacity: CGFloat? = nil, animMs = -1, animEz = ""
        for kv in s.split(separator: ";") {
            let p = kv.split(separator: "="); guard p.count == 2 else { continue }
            let k = String(p[0]), val = String(p[1])
            let f = Float(val) ?? 0
            switch k {
            case "d":   YGNodeStyleSetFlexDirection(n, val == "row" ? YGFlexDirection.row : YGFlexDirection.column)
            case "j":   YGNodeStyleSetJustifyContent(n, justify(val))
            case "a":   YGNodeStyleSetAlignItems(n, align(val))
            case "g":     YGNodeStyleSetFlexGrow(n, f)
            case "basis": YGNodeStyleSetFlexBasis(n, f)
            case "w":   YGNodeStyleSetWidth(n, f)
                        // scale the ~20pt indicator up/down to the requested diameter
                        (v as? UIActivityIndicatorView)?.transform = CGAffineTransform(scaleX: CGFloat(f) / 20, y: CGFloat(f) / 20)
            case "h":   YGNodeStyleSetHeight(n, f)
            case "p":   YGNodeStyleSetPadding(n, YGEdge.all, f)
            case "gap": YGNodeStyleSetGap(n, YGGutter.all, f)
            case "press": pressOpacity[id] = CGFloat(f) / 100     // Pressable active alpha
            case "sec": (v as? UITextField)?.isSecureTextEntry = (val == "1")   // password field
            case "sbh": sbHiddenOverride = (val == "1"); setNeedsStatusBarAppearanceUpdate()
            case "sbstyle":
                sbStyleOverride = (val == "light") ? .lightContent : (val == "dark" ? .darkContent : nil)
                setNeedsStatusBarAppearanceUpdate()
            case "mvis":
                let vis = (val == "1")
                v.isHidden = !vis
                if vis { activeModal = id } else if activeModal == id { activeModal = nil }
                view.setNeedsLayout()
            case "mpos":
                let bottom = (val == "bottom")
                if bottom { sheetModals.insert(id) } else { sheetModals.remove(id) }
                // a sheet pins to the bottom and stretches full-width; a dialog centers
                YGNodeStyleSetJustifyContent(n, bottom ? YGJustify.flexEnd : YGJustify.center)
                YGNodeStyleSetAlignItems(n, bottom ? YGAlign.stretch : YGAlign.center)
                // leave room below the sheet for the home indicator
                YGNodeStyleSetPadding(n, YGEdge.bottom, bottom ? 34 : 0)
            case "on":  (v as? UISwitch)?.setOn(val == "1", animated: true)
            case "prog":  (v as? UIProgressView)?.setProgress(f / 100, animated: false)
            case "rfsh":  if let sc = v as? UIScrollView, let rc = sc.refreshControl {   // controlled: end the spinner when the app clears it
                            if val == "1" { if !rc.isRefreshing { rc.beginRefreshing() } } else { rc.endRefreshing() }
                          }
            case "slmin": (v as? UISlider)?.minimumValue = f
            case "slmax": (v as? UISlider)?.maximumValue = f
            case "slv":   if let sl = v as? UISlider, !sl.isTracking { sl.setValue(f, animated: false) }  // don't fight an active drag
            case "seli":  if selectIds.contains(id) { selectSel[id] = Int(val) ?? 0; rebuildSelectMenu(id) }
            case "dp":    if let dp = v as? UIDatePicker {
                            datePickerModes[dp] = val
                            dp.datePickerMode = (val == "time") ? .time : (val == "datetime" ? .dateAndTime : .date)
                          }
            case "dpd":   if let dp = v as? UIDatePicker {   // display: inline / wheels / compact
                            if #available(iOS 14.0, *) {
                                dp.preferredDatePickerStyle = (val == "inline") ? .inline : (val == "wheels" ? .wheels : .compact)
                            }
                            let sz = dp.intrinsicContentSize   // inline/wheels are large — re-measure for Yoga
                            if let yn = ynodes[id] { YGNodeStyleSetWidth(yn, Float(sz.width)); YGNodeStyleSetHeight(yn, Float(sz.height)) }
                          }
            case "avis":  if val == "1" { presentAlert(id) } else { dismissAlert(id) }
            case "bg":  if let sw = v as? UISwitch { sw.onTintColor = hexColor(val) } else { v.backgroundColor = hexColor(val) }
            case "fg":  label?.textColor = hexColor(val); btn?.setTitleColor(hexColor(val), for: .normal)
                        field?.textColor = hexColor(val); imgView?.tintColor = hexColor(val)
                        (v as? UIActivityIndicatorView)?.color = hexColor(val)
                        (v as? UITextView)?.textColor = hexColor(val)
                        (v as? UIProgressView)?.progressTintColor = hexColor(val)
                        if let sl = v as? UISlider { sl.minimumTrackTintColor = hexColor(val); sl.thumbTintColor = hexColor(val) }
            case "fs":  fs = CGFloat(f)
            case "fw":  fw = (val == "bold") ? .bold : (val == "semibold" ? .semibold : .regular)
            case "ta":  label?.textAlignment = (val == "right") ? .right : (val == "center" ? .center : .left)
            case "font": customFont = val
            case "img": imgView?.image = UIImage(systemName: val)
            case "rmode":
                let mode: UIView.ContentMode = (val == "contain") ? .scaleAspectFit : (val == "center" ? .center : .scaleAspectFill)
                (v as? UIImageView)?.contentMode = mode
                bgImageViews[id]?.contentMode = mode
            case "vid":
                // Attach a muted, looping player when a Video node mounts. Reuse an
                // idle player from the pool (its item + loop observer are still primed)
                // instead of rebuilding an AVPlayerItem/AVPlayer on the main thread --
                // that synchronous setup was the scroll-churn bottleneck under a fast
                // fling. Only cold-start (empty pool) pays the build cost.
                if let vv = v as? VideoView, videoPlayers[id] == nil {
                    var player: AVPlayer? = nil
                    if var idle = videoPool[val], !idle.isEmpty {
                        player = idle.removeLast(); videoPool[val] = idle       // reuse (no alloc)
                    } else if let url = (val.hasPrefix("http") ? URL(string: val) : Bundle.main.url(forResource: val, withExtension: nil)) {
                        let item = AVPlayerItem(url: url)
                        let p = AVPlayer(playerItem: item); p.isMuted = true; p.actionAtItemEnd = .none
                        let obs = NotificationCenter.default.addObserver(
                            forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak p] _ in
                                p?.seek(to: .zero); p?.play()
                        }
                        videoObs[ObjectIdentifier(p)] = obs
                        player = p
                    }
                    if let player = player {
                        vv.playerLayer.player = player
                        videoPlayers[id] = player; videoPlayerKey[id] = val
                        player.play()
                    }
                }
            case "r":   v.clipsToBounds = !hasShadow
                        // A huge radius (rounded-full) is a pill: clamp to height/2 in
                        // relayout once the frame is known; a raw 9999 makes the layer
                        // path degenerate and the view stops drawing.
                        if f >= 9999 { pillIds.insert(id) }
                        else { pillIds.remove(id); v.layer.cornerRadius = CGFloat(f) }
            case "pos": if val == "abs" { YGNodeStyleSetPositionType(n, YGPositionType.absolute) }
            case "top":   YGNodeStyleSetPosition(n, YGEdge.top, f)
            case "left":  YGNodeStyleSetPosition(n, YGEdge.left, f)
            case "right": YGNodeStyleSetPosition(n, YGEdge.right, f)
            case "bw":  v.layer.borderWidth = CGFloat(f)
            case "bc":  v.layer.borderColor = hexColor(val).cgColor
            case "opacity": opacity = CGFloat(f) / 100.0
            case "tx": tx = CGFloat(f); hasTransform = true
            case "ty": ty = CGFloat(f); hasTransform = true
            case "rot": rot = CGFloat(f); hasTransform = true
            case "sc": sc = CGFloat(f) / 100.0; hasTransform = true
            case "anim": animMs = Int(val) ?? 0
            case "ez": animEz = val
            case "wrap": YGNodeStyleSetFlexWrap(n, val == "wrap" ? YGWrap.wrap : YGWrap.noWrap)
            case "shadow":
                let lvl = Int(f)
                v.layer.masksToBounds = false
                v.layer.shadowColor = UIColor.black.cgColor
                v.layer.shadowOpacity = lvl >= 3 ? 0.22 : (lvl == 2 ? 0.16 : 0.12)
                v.layer.shadowRadius = CGFloat(lvl >= 3 ? 20 : (lvl == 2 ? 8 : 3))
                v.layer.shadowOffset = CGSize(width: 0, height: lvl >= 3 ? 8 : (lvl == 2 ? 3 : 1))
            default:    break
            }
        }
        // Apply transform + opacity, animated natively when `anim` is set (no per-frame
        // round-trip: Core Animation interpolates). Transforms are visual, not layout.
        if hasTransform || animMs >= 0 || opacity != nil {
            let t = CGAffineTransform(translationX: tx, y: ty).scaledBy(x: sc, y: sc).rotated(by: rot * .pi / 180)
            let apply = { if hasTransform || animMs >= 0 { v.transform = t }; if let o = opacity { v.alpha = o } }
            if animMs >= 0 {
                let dur = Double(animMs) / 1000.0
                if animEz == "spring" {
                    UIView.animate(withDuration: dur, delay: 0, usingSpringWithDamping: 0.6, initialSpringVelocity: 0.5, options: [], animations: apply)
                } else {
                    UIView.animate(withDuration: dur, delay: 0, options: animEz == "linear" ? .curveLinear : .curveEaseInOut, animations: apply)
                }
            } else { apply() }
        }
        let font: UIFont = customFont.isEmpty
            ? .systemFont(ofSize: fs, weight: fw)
            : (UIFont(name: customFont, size: fs) ?? .systemFont(ofSize: fs, weight: fw))
        if let l = label {
            l.font = font
            // A font change (e.g. a tab label going regular -> semibold when active)
            // changes the text's measured size, but Yoga only re-runs its measure func
            // for DIRTY nodes. setText marks dirty; a style-only font change must too,
            // or the label keeps its old width and the wider text truncates to "…".
            YGNodeMarkDirty(n)
        }
        btn?.titleLabel?.font = font
        field?.font = font
    }

    func insert(_ id: String, parent: String, index: Int) {
        guard let child = views[id], let cn = ynodes[id] else { return }
        if parent == "root" {                                // the app root goes into the VC's view
            view.addSubview(child)
            return
        }
        if modalIds.contains(id) {                           // a Modal is a full-screen overlay on the
            view.addSubview(child)                           // ROOT, not inline; its Yoga node stays a
            return                                           // separate root (laid out in relayout)
        }
        if parent == scrollId { contentId = id }             // the scroll's content node
        guard let pv = views[parent], let pn = ynodes[parent] else { return }
        let base = bgImageViews[parent] != nil ? 1 : 0       // keep an ImageBackground's bg image at the back
        let i = min(index + base, pv.subviews.count)
        pv.insertSubview(child, at: i)
        YGNodeInsertChild(pn, cn, min(index, YGNodeGetChildCount(pn)))
    }

    // Recycle a subtree: detach + free its Yoga nodes, remove the views, and drop
    // every id under this prefix from both maps (and its recognizers/actions).
    func remove(_ id: String) {
        if let v = views[id] {
            for g in v.gestureRecognizers ?? [] {
                if let t = g as? UITapGestureRecognizer { taps[t] = nil }
                if let lp = g as? UILongPressGestureRecognizer { pressGestures[lp] = nil }
            }
            v.removeFromSuperview()
        }
        if let n = ynodes[id] {
            if let owner = YGNodeGetOwner(n) { YGNodeRemoveChild(owner, n) }
            YGNodeFreeRecursive(n)
        }
        let prefix = id + "."
        for k in views.keys.filter({ $0 == id || $0.hasPrefix(prefix) }) {
            if let b = views[k] as? UIButton { buttonActions[b] = nil }
            if let tf = views[k] as? UITextField { fieldActions[tf] = nil }
            if let sw = views[k] as? UISwitch { switchActions[sw] = nil }
            if let sl = views[k] as? UISlider { sliderActions[sl] = nil }
            if let dp = views[k] as? UIDatePicker { datePickerActions[dp] = nil; datePickerModes[dp] = nil }
            if let tv = views[k] as? UITextView { textAreaActions[tv] = nil; textAreaPlaceholders[tv] = nil }
            if selectIds.contains(k) { selectIds.remove(k); selectOptions[k] = nil; selectSel[k] = nil; selectActions[k] = nil }
            if gestureIds.contains(k) { gestureIds.remove(k); gestureActions[k] = nil }
            if menuIds.contains(k) { menuIds.remove(k); menuData[k] = nil; menuActions[k] = nil }
            if contextMenuIds.contains(k) { contextMenuIds.remove(k); contextMenuData[k] = nil; contextMenuActions[k] = nil }
            if alertIds.contains(k) { alertIds.remove(k); alertData[k] = nil; alertActions[k] = nil; if presentedAlert == k { presentedAlert = nil } }
            bgImageViews[k] = nil
            pressOpacity[k] = nil
            modalIds.remove(k); if activeModal == k { activeModal = nil }
            if let p = videoPlayers[k] {                     // recycle: pool the player, keep it primed
                p.pause()
                (views[k] as? VideoView)?.playerLayer.player = nil
                let key = videoPlayerKey[k] ?? ""
                var idle = videoPool[key] ?? []
                if idle.count < videoPoolCap {
                    idle.append(p); videoPool[key] = idle    // return to pool (no teardown)
                } else if let o = videoObs[ObjectIdentifier(p)] {  // pool full: actually free it
                    NotificationCenter.default.removeObserver(o); videoObs[ObjectIdentifier(p)] = nil
                }
                videoPlayers[k] = nil; videoPlayerKey[k] = nil
            }
            pillIds.remove(k)
            views[k] = nil
        }
        for k in ynodes.keys.filter({ $0 == id || $0.hasPrefix(prefix) }) { ynodes[k] = nil }
    }

    // A node with an action tag: Button/Input use their own targets; any other
    // View gets a tap recognizer.
    func bindAction(_ id: String, action: String) {
        guard let v = views[id] else { return }
        if modalIds.contains(id) { modalActions[id] = action }   // onDismiss: fired by scrim tap AND sheet drag
        if selectIds.contains(id) { selectActions[id] = action; rebuildSelectMenu(id); return }   // menu items dispatch this
        if gestureIds.contains(id) { gestureActions[id] = action; return }                        // gestures dispatch this
        if menuIds.contains(id) { menuActions[id] = action; rebuildMenu(id); return }             // Menu items dispatch this
        if contextMenuIds.contains(id) { contextMenuActions[id] = action; return }                // ContextMenu items dispatch this
        if alertIds.contains(id) { alertActions[id] = action; return }                            // alert buttons dispatch this
        if let sc = v as? UIScrollView {                                                          // Scroll onRefresh -> pull-to-refresh control
            let rc = sc.refreshControl ?? {
                let r = UIRefreshControl(); sc.refreshControl = r
                r.addTarget(self, action: #selector(refreshPulled(_:)), for: .valueChanged); return r
            }()
            refreshActions[rc] = action
            return
        }
        if let b = v as? UIButton { buttonActions[b] = action; return }
        if let tf = v as? UITextField { fieldActions[tf] = action; return }
        if let sw = v as? UISwitch { switchActions[sw] = action; return }
        if let sl = v as? UISlider { sliderActions[sl] = action; return }
        if let dp = v as? UIDatePicker { datePickerActions[dp] = action; return }
        if let tv = v as? UITextView { textAreaActions[tv] = action; return }
        v.isUserInteractionEnabled = true
        if let ao = pressOpacity[id] {                          // Pressable: press-feedback gesture, not a plain tap
            let g = UILongPressGestureRecognizer(target: self, action: #selector(handlePress(_:)))
            g.minimumPressDuration = 0
            v.addGestureRecognizer(g)
            pressGestures[g] = (action, ao)
            return
        }
        let g = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        v.addGestureRecognizer(g)
        taps[g] = action
    }

    @objc func handleButton(_ b: UIButton) { if let a = buttonActions[b] { fire(a) } }
    @objc func handleTap(_ g: UITapGestureRecognizer) { if let a = taps[g] { fire(a) } }
    // A native value event from a Switch node: dispatch, then the re-render syncs the
    // control back to Chuks state (the controlled pattern, like Input).
    @objc func handleSwitch(_ sw: UISwitch) { if let a = switchActions[sw] { fire(a) } }
    // Pressable: dim on touch-down, restore on release/cancel, fire only if the touch
    // ended inside the view (TouchableOpacity semantics). minimumPressDuration=0 makes
    // .began fire the instant the finger lands.
    @objc func handlePress(_ g: UILongPressGestureRecognizer) {
        guard let v = g.view, let (action, ao) = pressGestures[g] else { return }
        switch g.state {
        case .began:
            UIView.animate(withDuration: 0.09) { v.alpha = ao }
        case .ended, .cancelled, .failed:
            UIView.animate(withDuration: 0.09) { v.alpha = 1.0 }
            if g.state == .ended && v.bounds.contains(g.location(in: v)) { fire(action) }
        default: break
        }
    }

    // A native value event from an Input node -> the engine (cgo or HTTP).
    @objc func inputChanged(_ tf: UITextField) {
        guard let action = fieldActions[tf] else { return }
        let val = tf.text ?? ""
        guard let s = eInput(action, val) else { connected = false; return }
        apply(s)
        listScroll?.setContentOffset(.zero, animated: false)
        relayout()
        headerText("input \(action)=\"\(val)\"")
    }

    // A native value event from a Slider node -> the engine. Dispatches the rounded
    // int value; the controlled re-render syncs the thumb back (guarded by isTracking).
    @objc func sliderChanged(_ sl: UISlider) {
        guard let action = sliderActions[sl] else { return }
        let val = String(Int(sl.value.rounded()))
        guard let s = eInput(action, val) else { connected = false; return }
        apply(s)
        relayout()
        headerText("slider \(action)=\(val)")
    }

    // A native value event from a DatePicker -> the engine, as an ISO string; the
    // controlled re-render syncs the field back (dp.date via setText).
    @objc func dateChanged(_ dp: UIDatePicker) {
        guard let action = datePickerActions[dp] else { return }
        let val = Self.formatISO(dp.date, mode: datePickerModes[dp] ?? "date")
        guard let s = eInput(action, val) else { connected = false; return }
        apply(s); relayout()
        headerText("date \(action)=\(val)")
    }

    // ISO <-> Date for the DatePicker value channel. Fixed POSIX locale + current
    // time zone so the string round-trips independent of device locale.
    static func dpFormatter(_ mode: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone.current
        f.dateFormat = (mode == "time") ? "HH:mm" : (mode == "datetime" ? "yyyy-MM-dd'T'HH:mm" : "yyyy-MM-dd")
        return f
    }
    static func parseISO(_ s: String, mode: String) -> Date? {
        if s.isEmpty { return nil }
        return dpFormatter(mode).date(from: s)
    }
    static func formatISO(_ d: Date, mode: String) -> String {
        return dpFormatter(mode).string(from: d)
    }

    // (Re)build a Select's pull-down menu from its options + current index, and set
    // the button title to the chosen label. Called whenever options/index/action change.
    func rebuildSelectMenu(_ id: String) {
        guard let b = views[id] as? UIButton else { return }
        let opts = selectOptions[id] ?? []
        let sel = selectSel[id] ?? 0
        let items = opts.enumerated().map { (i, label) in
            UIAction(title: label, state: i == sel ? .on : .off) { [weak self] _ in self?.selectPick(id, i) }
        }
        b.menu = UIMenu(children: items)
        b.setTitle(sel < opts.count ? opts[sel] : "Select", for: .normal)
    }
    // A menu pick -> dispatch the chosen index; the controlled re-render syncs the title.
    func selectPick(_ id: String, _ i: Int) {
        guard let action = selectActions[id] else { return }
        guard let s = eInput(action, String(i)) else { connected = false; return }
        apply(s); relayout(); headerText("select \(action)=\(i)")
    }

    // (Re)build a Menu button: a fixed label + one-shot action items (no selection state).
    func rebuildMenu(_ id: String) {
        guard let b = views[id] as? UIButton else { return }
        let parts = menuData[id] ?? []
        let label = parts.first ?? "Menu"
        let items = Array(parts.dropFirst())
        b.setTitle(label, for: .normal)
        b.menu = UIMenu(children: items.enumerated().map { (i, t) in
            UIAction(title: t) { [weak self] _ in self?.menuPick(id, i) }
        })
    }
    func menuPick(_ id: String, _ i: Int) {
        guard let action = menuActions[id] else { return }
        guard let s = eInput(action, String(i)) else { connected = false; return }
        apply(s); relayout(); headerText("menu \(action)=\(i)")
    }

    // Long-press context menu: build the UIMenu for the interaction's view on demand.
    func contextMenuInteraction(_ interaction: UIContextMenuInteraction,
                                configurationForMenuAtLocation location: CGPoint) -> UIContextMenuConfiguration? {
        guard let view = interaction.view,
              let id = contextMenuIds.first(where: { views[$0] === view }) else { return nil }
        let items = contextMenuData[id] ?? []
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
            UIMenu(children: items.enumerated().map { (i, t) in
                UIAction(title: t) { _ in self?.contextMenuPick(id, i) }
            })
        }
    }
    func contextMenuPick(_ id: String, _ i: Int) {
        guard let action = contextMenuActions[id] else { return }
        guard let s = eInput(action, String(i)) else { connected = false; return }
        apply(s); relayout(); headerText("ctxmenu \(action)=\(i)")
    }

    // ---- Gesture ---------------------------------------------------------
    func gestureIdFor(_ view: UIView?) -> String? { views.first(where: { $0.value === view })?.key }
    func dispatchGesture(_ view: UIView?, _ g: String) {
        guard let id = gestureIdFor(view), let action = gestureActions[id] else { return }
        guard let s = eInput(action, g) else { connected = false; return }
        apply(s); relayout(); headerText("gesture \(action)=\(g)")
    }
    @objc func handleSwipe(_ g: UISwipeGestureRecognizer) {
        let dir = g.direction == .left ? "left" : (g.direction == .right ? "right" : (g.direction == .up ? "up" : "down"))
        dispatchGesture(g.view, "swipe:\(dir)")
    }
    @objc func handleDoubleTap(_ g: UITapGestureRecognizer) { dispatchGesture(g.view, "doubletap") }
    @objc func handleLongPress(_ g: UILongPressGestureRecognizer) { if g.state == .began { dispatchGesture(g.view, "longpress") } }

    // A native edit on a multiline TextArea -> the engine; toggles the placeholder.
    func textViewDidChange(_ tv: UITextView) {
        textAreaPlaceholders[tv]?.isHidden = !tv.text.isEmpty
        guard let action = textAreaActions[tv] else { return }
        guard let s = eInput(action, tv.text) else { connected = false; return }
        apply(s); relayout()
        headerText("textarea \(action)")
    }

    // Present a native UIAlertController for an Alert node. Deferred so the whole
    // mutation batch (incl. the tab-joined title/message via setText) is applied first.
    func presentAlert(_ id: String) {
        if presentedAlert == id { return }
        DispatchQueue.main.async {
            guard self.alertIds.contains(id), self.presentedAlert == nil else { return }
            let f = self.alertData[id] ?? []
            let title = f.count > 0 ? f[0] : "", msg = f.count > 1 ? f[1] : ""
            let confirm = f.count > 2 ? f[2] : "OK", cancel = f.count > 3 ? f[3] : ""
            let ac = UIAlertController(title: title.isEmpty ? nil : title,
                                       message: msg.isEmpty ? nil : msg, preferredStyle: .alert)
            if !cancel.isEmpty {
                ac.addAction(UIAlertAction(title: cancel, style: .cancel) { _ in self.presentedAlert = nil; self.alertDispatch(id, "0") })
            }
            ac.addAction(UIAlertAction(title: confirm, style: .default) { _ in self.presentedAlert = nil; self.alertDispatch(id, "1") })
            self.presentedAlert = id
            self.present(ac, animated: true)
        }
    }
    func dismissAlert(_ id: String) {
        if presentedAlert == id { presentedAlert = nil; presentedViewController?.dismiss(animated: true) }
    }
    func alertDispatch(_ id: String, _ v: String) {
        guard let action = alertActions[id] else { return }
        guard let s = eInput(action, v) else { connected = false; return }
        apply(s); relayout()
        headerText("alert \(action)=\(v)")
    }

    // Fetch a remote image (cached) and set it on the given view. Tag-guarded so a
    // recycled view doesn't get a late response for a URL it no longer wants.
    static let imageSession: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 20
        return URLSession(configuration: c)
    }()
    func loadRemoteImage(_ urlStr: String, into iv: UIImageView) {
        if let cached = CardsVC.imageCache[urlStr] { iv.image = cached; return }
        guard let url = URL(string: urlStr) else { return }
        let wanted = urlStr.hashValue
        iv.tag = wanted
        CardsVC.imageSession.dataTask(with: url) { data, _, _ in
            guard let data = data, let img = UIImage(data: data) else { return }
            CardsVC.imageCache[urlStr] = img
            DispatchQueue.main.async { if iv.tag == wanted { iv.image = img } }
        }.resume()
    }

    // Pull-to-refresh fired: run onRefresh (synchronous — its state change re-renders),
    // then end the spinner. (A controlled `refreshing` flag can also drive it via rfsh.)
    @objc func refreshPulled(_ rc: UIRefreshControl) {
        if let a = refreshActions[rc] { fire(a) }
        rc.endRefreshing()
    }

    // A native tap/press -> the engine, then apply the minimal diff.
    func fire(_ action: String) {
        guard let s = eEvent(action) else { connected = false; return }
        apply(s)
        relayout()
        headerText("action \(action)")
    }

    func justify(_ v: String) -> YGJustify {
        switch v { case "center": return .center; case "end": return .flexEnd
        case "between": return .spaceBetween; case "around": return .spaceAround
        default: return .flexStart }
    }
    func align(_ v: String) -> YGAlign {
        switch v { case "center": return .center; case "end": return .flexEnd
        case "stretch": return .stretch; default: return .flexStart }
    }

    // ---- run Yoga on the app tree and copy computed rects onto the UIViews ---
    func relayout() {
        guard let app = ynodes["app"] else { return }
        let insets = view.safeAreaInsets
        // Below the benchmark header when shown; otherwise just below the safe-area top
        // (status bar / notch). Without this the app slides up under the status bar,
        // since a hidden header has a .zero frame.
        let topY = BENCHMARK_MODE ? header.frame.maxY + 6 : insets.top
        let W = Float(view.bounds.width - insets.left - insets.right)
        let H = Float(view.bounds.height - topY - insets.bottom)
        if W <= 0 || H <= 0 { return }
        YGNodeStyleSetWidth(app, W); YGNodeStyleSetHeight(app, H)
        YGNodeCalculateLayout(app, W, H, YGDirection.LTR)
        // A visible Modal is a separate full-screen Yoga root (over the whole window,
        // safe area included) — lay it out before copying frames.
        if let mid = activeModal, let mn = ynodes[mid], views[mid]?.isHidden == false {
            let fw = Float(view.bounds.width), fh = Float(view.bounds.height)
            YGNodeStyleSetWidth(mn, fw); YGNodeStyleSetHeight(mn, fh)
            YGNodeCalculateLayout(mn, fw, fh, YGDirection.LTR)
        }
        for (id, n) in ynodes {
            if modalIds.contains(id) { continue }   // modal overlay node has no app-tree layout (placed below)
            let fr = CGRect(x: CGFloat(YGNodeLayoutGetLeft(n)), y: CGFloat(YGNodeLayoutGetTop(n)),
                            width: CGFloat(YGNodeLayoutGetWidth(n)), height: CGFloat(YGNodeLayoutGetHeight(n)))
            if let vv = views[id] {
                // Setting .frame on a view with a non-identity transform corrupts it
                // (frame is transform-affected); position such views via bounds + center.
                if vv.transform.isIdentity { vv.frame = fr }
                else { vv.bounds = CGRect(origin: .zero, size: fr.size); vv.center = CGPoint(x: fr.midX, y: fr.midY) }
            }
            if pillIds.contains(id) { views[id]?.layer.cornerRadius = min(fr.width, fr.height) / 2 }
        }
        // place the whole Chuks app in the safe area, below the diagnostics header
        views["app"]?.frame = CGRect(x: insets.left, y: topY, width: CGFloat(W), height: CGFloat(H))
        // a visible modal fills the window and sits on top (its children were laid out above)
        if let mid = activeModal, let mv = views[mid], !mv.isHidden {
            mv.frame = CGRect(x: 0, y: 0, width: view.bounds.width, height: view.bounds.height)
            view.bringSubviewToFront(mv)
            if sheetModals.contains(mid) {
                let firstOpen = (shownSheet != mid); shownSheet = mid
                layoutSheetChrome(mv, animateIn: firstOpen)
            } else { clearSheetChrome() }
        } else {
            clearSheetChrome()
        }
        // the scroll's content size comes from its content node's laid-out size
        if let sc = listScroll, let cn = ynodes[contentId] {
            sc.contentSize = CGSize(width: CGFloat(YGNodeLayoutGetWidth(cn)), height: CGFloat(YGNodeLayoutGetHeight(cn)))
        }
    }

    // ---- Bottom sheet: a host-drawn draggable surface (@gorhom-style) --------
    // The Modal's children are laid out (full-width, pinned bottom) by the frame
    // loop above. Here we slip a rounded-top surface + grab handle behind them and
    // wire a pan so the whole sheet drags down to dismiss / springs back.
    func layoutSheetChrome(_ mv: UIView, animateIn: Bool = false) {
        let content = mv.subviews.filter { $0 !== sheetBg && $0 !== sheetHandle }
        guard !content.isEmpty else { return }
        var union = CGRect.null
        for c in content { c.transform = .identity; union = union.union(c.frame) }   // clear any prior drag
        let handleStrip: CGFloat = 22
        let top = max(0, union.minY - handleStrip)
        let W = view.bounds.width, H = view.bounds.height

        let bg = sheetBg ?? { let b = UIView(); sheetBg = b; return b }()
        bg.backgroundColor = .secondarySystemBackground
        bg.layer.cornerRadius = 20
        bg.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        bg.transform = .identity
        bg.frame = CGRect(x: 0, y: top, width: W, height: H - top)
        if bg.superview !== mv { mv.insertSubview(bg, at: 0) } else { mv.sendSubviewToBack(bg) }

        let handle = sheetHandle ?? { let h = UIView(); h.backgroundColor = UIColor.gray.withAlphaComponent(0.4); h.layer.cornerRadius = 2.5; sheetHandle = h; return h }()
        handle.transform = .identity
        handle.frame = CGRect(x: (W - 40) / 2, y: top + 8, width: 40, height: 5)
        if handle.superview !== mv { mv.insertSubview(handle, aboveSubview: bg) } else { mv.bringSubviewToFront(handle) }

        if sheetPan == nil {
            let p = UIPanGestureRecognizer(target: self, action: #selector(handleSheetPan(_:)))
            mv.addGestureRecognizer(p); sheetPan = p
        }
        mv.backgroundColor = UIColor(white: 0, alpha: 0.5)

        if animateIn {                                   // slide the whole sheet up from below, scrim fades in
            let dy = H - top
            for sub in mv.subviews { sub.transform = CGAffineTransform(translationX: 0, y: dy) }
            mv.backgroundColor = UIColor(white: 0, alpha: 0)
            UIView.animate(withDuration: 0.34, delay: 0, options: [.curveEaseOut]) {
                for sub in mv.subviews { sub.transform = .identity }
                mv.backgroundColor = UIColor(white: 0, alpha: 0.5)
            }
        }
    }

    func clearSheetChrome() {
        sheetBg?.removeFromSuperview(); sheetHandle?.removeFromSuperview()
        if let p = sheetPan { p.view?.removeGestureRecognizer(p); sheetPan = nil }
        shownSheet = nil
    }

    @objc func handleSheetPan(_ g: UIPanGestureRecognizer) {
        guard let mid = activeModal, let mv = views[mid] else { return }
        let dy = max(0, g.translation(in: view).y)
        switch g.state {
        case .changed:
            for sub in mv.subviews { sub.transform = CGAffineTransform(translationX: 0, y: dy) }
            mv.backgroundColor = UIColor(white: 0, alpha: 0.5 * max(0, 1 - dy / 500))
        case .ended, .cancelled:
            if dy > 120 || g.velocity(in: view).y > 800 {
                if let a = modalActions[mid] { fire(a) }               // onDismiss -> state flips -> mvis=0
            } else {
                UIView.animate(withDuration: 0.25) {
                    for sub in mv.subviews { sub.transform = .identity }
                    mv.backgroundColor = UIColor(white: 0, alpha: 0.5)
                }
            }
        default: break
        }
    }
}
