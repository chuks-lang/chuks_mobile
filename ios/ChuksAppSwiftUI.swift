// Chuks Mobile — the SwiftUI iOS engine. A second renderer for the SAME Chuks
// mutation stream the UIKit host consumes (C/S/P/T/I/R over the chuks_* C ABI),
// but rendered with NATIVE SwiftUI (no Yoga; SwiftUI does its own layout).
//
// Flow: apply the stream to an observable node model (Scene), then render it
// recursively with NodeView, mapping the flexbox Style to SwiftUI layout. Events
// call chuks_dispatch; the returned diff is applied. Selected via chuks.json
// "iosEngine": "swiftui". The developer writes only Chuks; this is the native layer.

import SwiftUI
import UIKit
import MapKit
import Foundation
import AVFoundation
import WebKit
import Photos
import PhotosUI
import UserNotifications
import CoreLocation
import CoreMotion
import CoreHaptics
import LocalAuthentication
import Security
import Network

// A muted, looping video surface backed by an AVPlayerLayer (no controls), driven
// by its SwiftUI frame — matches the UIKit host's VideoView. `src` is a bundled
// filename (e.g. "sample.mp4").
final class LoopingPlayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    private var player: AVPlayer?
    private var looper: NSObjectProtocol?
    private var currentSrc = ""
    func configure(_ src: String) {
        guard src != currentSrc, !src.isEmpty else { return }
        // http(s) URL -> remote; otherwise a bundled asset (e.g. clip.mp4).
        let url: URL? = src.hasPrefix("http") ? URL(string: src) : Bundle.main.url(forResource: src, withExtension: nil)
        guard let url = url else { return }
        teardown()
        currentSrc = src
        let item = AVPlayerItem(url: url)
        let p = AVPlayer(playerItem: item); p.isMuted = true; p.actionAtItemEnd = .none
        looper = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime,
                                                        object: item, queue: .main) { [weak p] _ in
            p?.seek(to: .zero); p?.play()
        }
        playerLayer.player = p
        playerLayer.videoGravity = .resizeAspectFill
        p.play(); player = p
    }
    private func teardown() {
        player?.pause()
        if let l = looper { NotificationCenter.default.removeObserver(l); looper = nil }
        player = nil; playerLayer.player = nil
    }
    deinit { teardown() }
}

struct ChuksVideo: UIViewRepresentable {
    let src: String
    func makeUIView(context: Context) -> LoopingPlayerView {
        let v = LoopingPlayerView(); v.clipsToBounds = true; v.configure(src); return v
    }
    func updateUIView(_ uiView: LoopingPlayerView, context: Context) { uiView.configure(src) }
}

// A native web view (WKWebView) loading a URL from the node's text channel.
struct ChuksWeb: UIViewRepresentable {
    let url: String
    func makeUIView(context: Context) -> WKWebView {
        let w = WKWebView(); w.clipsToBounds = true
        w.scrollView.showsVerticalScrollIndicator = false; w.isOpaque = false
        return w
    }
    func updateUIView(_ w: WKWebView, context: Context) {
        guard let u = URL(string: url), w.url?.absoluteString != url else { return }
        w.load(URLRequest(url: u))
    }
}

// DEV mode (built with -D DEV): the engine runs in the Chuks VM dev server started by
// `chuks watch .chuks/devserver.chuks`, and the host fetches the mutation stream over
// HTTP. Saving any .chuks file makes `chuks watch` restart the server; the host detects
// the bounce (see Scene.startDevWatch) and remounts — hot reload with no app rebuild.
// Out of DEV mode the engine is the AOT-linked library.
#if DEV
let DEV_MODE = true
#else
let DEV_MODE = false
#endif

// The dev server host: a DEV=1 build may bundle chuks-dev.txt with the machine's LAN
// IP (for a real device on the same Wi-Fi); the simulator falls back to localhost.
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

// Synchronous request to the dev server. Returns nil on a network error (the server is
// briefly down while `chuks watch` restarts it), else the response body (may be empty).
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

// ================= engine bridge (identical C ABI to the UIKit host) =============
// Each engine call has a DEV branch that talks to the VM dev server over HTTP instead
// of the AOT-linked cgo library, so the two hosts share one control flow.
enum Engine {
    static func drain() -> String {
        guard let c = chuks_drain() else { return "" }
        let s = String(cString: c); chuks_free_str(c); return s
    }
    static func setup(_ n: Int32) { if DEV_MODE { return }; chuks_set_count(n) }   // dev server self-inits on boot
    static func mount() -> String { if DEV_MODE { return devHTTP("/mount", "") ?? "" }; _ = chuks_mount(); return drain() }
    static func tick() -> String { if DEV_MODE { return devHTTP("/tick", "") ?? "" }; _ = chuks_tick(); return drain() }
    static func dispatch(_ tag: String) -> String {
        if DEV_MODE { return devHTTP("/event", tag) ?? "" }
        _ = tag.withCString { chuks_dispatch(UnsafeMutablePointer(mutating: $0)) }
        return drain()
    }
    static func input(_ tag: String, _ v: String) -> String {
        if DEV_MODE { return devHTTP("/input", "\(tag)\n\(v)") ?? "" }
        _ = tag.withCString { a in v.withCString { b in
            chuks_dispatchInput(UnsafeMutablePointer(mutating: a), UnsafeMutablePointer(mutating: b)) } }
        return drain()
    }
    // Async host->engine bridge (F3): report a native capability result for `token`.
    static func resolve(_ token: String, _ payload: String) -> String {
        if DEV_MODE { return devHTTP("/resolve", "\(token)\n\(payload)") ?? "" }
        _ = token.withCString { a in payload.withCString { b in
            chuks_resolve(UnsafeMutablePointer(mutating: a), UnsafeMutablePointer(mutating: b)) } }
        return drain()
    }
    // Report a capability FAILURE for `token` (error channel).
    static func fail(_ token: String, _ message: String) -> String {
        if DEV_MODE { return devHTTP("/fail", "\(token)\n\(message)") ?? "" }
        _ = token.withCString { a in message.withCString { b in
            chuks_fail(UnsafeMutablePointer(mutating: a), UnsafeMutablePointer(mutating: b)) } }
        return drain()
    }
    static func viewport(_ top: Int32, _ h: Int32) -> String {
        if DEV_MODE { return devHTTP("/viewport", "\(top) \(h)") ?? "" }
        _ = chuks_setViewport(top, h); return drain()
    }
    // Dark-mode sync: report the OS appearance (no render), and query whether the app
    // is still following it. setColorScheme updates the theme; the caller re-renders.
    // The dev server has no appearance/insets/platform endpoints, so these no-op in DEV.
    static func setColorScheme(_ dark: Bool) { if DEV_MODE { return }; chuks_setColorScheme(dark ? 1 : 0) }
    static func colorSchemeFollows() -> Bool { if DEV_MODE { return true }; return chuks_colorSchemeFollows() == 1 }
    static func setInsets(_ t: Int32, _ r: Int32, _ b: Int32, _ l: Int32) { if DEV_MODE { return }; chuks_setInsets(t, r, b, l) }
    static func setPlatform() {
        if DEV_MODE { return }
        let version = UIDevice.current.systemVersion, model = UIDevice.current.model
        let isPad: Int32 = UIDevice.current.userInterfaceIdiom == .pad ? 1 : 0
        "ios".withCString { o in version.withCString { v in model.withCString { m in
            chuks_setPlatform(UnsafeMutablePointer(mutating: o), UnsafeMutablePointer(mutating: v), UnsafeMutablePointer(mutating: m), isPad) } } }
    }
}

// Carries a ScrollView's scroll offset up so we can report the visible window to
// the engine (List virtualization: it mounts/recycles rows to match).
struct ScrollOffsetKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

// ================= helpers ======================================================
func hexColor(_ h: String) -> Color {
    var v: UInt64 = 0; Scanner(string: h).scanHexInt64(&v)
    return Color(red: Double((v >> 16) & 0xff) / 255.0,
                 green: Double((v >> 8) & 0xff) / 255.0,
                 blue: Double(v & 0xff) / 255.0)
}
func numOf(_ s: String?) -> CGFloat? { guard let s = s, let f = Float(s) else { return nil }; return CGFloat(f) }

// ─── Orientation (capability) ─────────────────────────────────────────────────
// The app's current interface orientation, as "portrait"/"landscape", and a lock.
// The lock reads through the global mask that the app delegate reports to UIKit.
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
// SwiftUI has no AppDelegate by default; this adaptor lets UIKit read the lock mask.
class ChuksOrientationDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        chuksOrientationMask
    }
}

// Perceived lightness of a hex color (ITU-R BT.601 luma). Used to pick the status
// bar / system chrome scheme from the app's live background, so the host chrome
// follows setTheme() instead of being pinned dark.
func isLightHex(_ h: String) -> Bool {
    var v: UInt64 = 0; Scanner(string: h).scanHexInt64(&v)
    let r = Double((v >> 16) & 0xff), g = Double((v >> 8) & 0xff), b = Double(v & 0xff)
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0 > 0.5
}

func parseStyle(_ s: String) -> [String: String] {
    var d: [String: String] = [:]
    for kv in s.split(separator: ";") {
        let p = kv.split(separator: "=", maxSplits: 1)
        if p.count == 2 { d[String(p[0])] = String(p[1]) }
    }
    return d
}

// ================= node model + stream application ==============================
struct NodeData {
    var kind: String
    var text: String = ""
    var style: [String: String] = [:]
    var children: [String] = []
    var action: String = ""
}

// The parsed fields of a visible Alert node, for the native .alert presentation.
struct AlertInfo {
    let id: String, title: String, message: String, confirm: String, cancel: String
}

// Permission (F2): map each framework's status enum to the cross-platform grant
// string ("granted" | "denied" | "undetermined" | "restricted"). Top-level so both
// the sync and async permission paths share them.
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

// Location permission needs a CLLocationManager delegate (the request result comes
// via a callback, not a completion handler). Holds the pending completion until the
// authorization decision arrives.
final class LocPerm: NSObject, CLLocationManagerDelegate {
    private let mgr = CLLocationManager()
    private var onDecide: ((String) -> Void)?
    override init() { super.init(); mgr.delegate = self }
    func request(_ cb: @escaping (String) -> Void) {
        let s = mgr.authorizationStatus
        if s != .notDetermined { cb(clAuthStr(s)); return }   // already decided
        onDecide = cb
        mgr.requestWhenInUseAuthorization()
    }
    func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        if m.authorizationStatus == .notDetermined { return }  // ignore the initial call
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

// Media picker + camera: copy the chosen/captured UIImage into the app's Documents and
// return its path, so the "file://<path>" can feed an Image node.
func chuksSaveImage(_ img: UIImage) -> String? {
    guard let data = img.jpegData(compressionQuality: 0.9) else { return nil }
    let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("picked-\(UUID().uuidString).jpg")
    do { try data.write(to: url); return url.path } catch { return nil }
}
// The frontmost view controller to present the picker/camera from (the host is SwiftUI).
func chuksTopVC() -> UIViewController? {
    let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap { $0.windows }
    var top = windows.first { $0.isKeyWindow }?.rootViewController
    while let p = top?.presentedViewController { top = p }
    return top
}
// Delegate for PHPicker (library) and UIImagePickerController (camera). Held by the host
// for the lifetime of the presentation; `done` gets a "file://" path, `cancel` a message.
final class MediaCoordinator: NSObject, PHPickerViewControllerDelegate, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    private let done: (String) -> Void
    private let cancel: (String) -> Void
    init(done: @escaping (String) -> Void, cancel: @escaping (String) -> Void) { self.done = done; self.cancel = cancel }
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let prov = results.first?.itemProvider, prov.canLoadObject(ofClass: UIImage.self) else { cancel("canceled"); return }
        prov.loadObject(ofClass: UIImage.self) { obj, _ in
            let path = (obj as? UIImage).flatMap { chuksSaveImage($0) }
            DispatchQueue.main.async { if let p = path { self.done("file://" + p) } else { self.cancel("no image") } }
        }
    }
    func imagePickerController(_ p: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        p.dismiss(animated: true)
        if let path = (info[.originalImage] as? UIImage).flatMap({ chuksSaveImage($0) }) { done("file://" + path) } else { cancel("no image") }
    }
    func imagePickerControllerDidCancel(_ p: UIImagePickerController) { p.dismiss(animated: true); cancel("canceled") }
}

// Secure storage (Tier B): Keychain-backed key/value.
func keychainSet(_ key: String, _ value: String) {
    let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key]
    SecItemDelete(base as CFDictionary)   // replace any existing
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

// Show notifications even while the app is foregrounded (otherwise iOS suppresses
// the banner for the active app).
final class NotifDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ c: UNUserNotificationCenter, willPresent n: UNNotification,
                                withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        done([.banner, .sound])
    }
}
let notifDelegate = NotifDelegate()

final class Scene: ObservableObject {
    @Published var nodes: [String: NodeData] = [:]
    // Whether the app is still tracking the OS appearance (false once the user picks a
    // theme by hand). Drives whether RootView forces preferredColorScheme.
    @Published var followSystem = true
    // Host-global status bar (from a StatusBar node): hidden, and content style
    // ("light" | "dark" | "" = follow theme).
    @Published var sbHidden = false
    @Published var sbStyle = ""
    @Published var sbColor = ""   // status-bar background color (hex); "" = none

    private var booted = false
    func boot(osDark: Bool) {
        if booted { return }; booted = true
        UNUserNotificationCenter.current().delegate = notifDelegate  // foreground banners
        Engine.setup(0); Engine.setPlatform(); Engine.setColorScheme(osDark); apply(Engine.mount()); syncFollow()
        if DEV_MODE { startDevWatch() }
    }

    // DEV hot reload: `chuks watch` restarts the VM dev server (~1s) whenever a .chuks
    // file is saved. We poll it off the main thread; when it comes back after a bounce,
    // we clear the tree and remount from a fresh /mount, so the edit shows with no
    // rebuild. (Engine state resets on reload; state preservation is a later refinement.)
    private var devConnected = true
    private func startDevWatch() {
        DispatchQueue.global(qos: .utility).async { [weak self] in
            while self != nil {
                Thread.sleep(forTimeInterval: 0.4)
                let up = devHTTP("/state", "", get: true) != nil
                guard let self = self else { return }
                if up && !self.devConnected {
                    DispatchQueue.main.async {
                        self.nodes.removeAll()
                        self.apply(Engine.mount())
                        self.syncFollow()
                    }
                }
                self.devConnected = up
            }
        }
    }
    func dispatch(_ tag: String) { apply(Engine.dispatch(tag)); syncFollow() }
    func input(_ tag: String, _ v: String) { apply(Engine.input(tag, v)) }
    func viewport(_ top: Int32, _ h: Int32) { apply(Engine.viewport(top, h)) }
    // The OS appearance changed while following it: update the theme and re-render.
    func osChanged(dark: Bool) { Engine.setColorScheme(dark); apply(Engine.tick()); syncFollow() }
    func syncFollow() { let f = Engine.colorSchemeFollows(); if f != followSystem { followSystem = f } }
    // Report device safe-area insets (points) to the engine and re-render if changed.
    private var lastInsets: EdgeInsets? = nil
    func reportInsets(_ i: EdgeInsets) {
        if lastInsets == i { return }
        lastInsets = i
        Engine.setInsets(Int32(i.top), Int32(i.trailing), Int32(i.bottom), Int32(i.leading))
        apply(Engine.tick())
    }

    func apply(_ stream: String) {
        if stream.isEmpty { return }
        for raw in stream.split(separator: "\n") {
            let f = raw.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
            guard let op = f.first else { continue }
            switch op {
            case "C" where f.count >= 3:
                if nodes[f[1]] == nil { nodes[f[1]] = NodeData(kind: f[2]) }
            case "S" where f.count >= 3:
                nodes[f[1]]?.style = parseStyle(f[2])
                if nodes[f[1]]?.kind == "StatusBar" { applyStatusBar(nodes[f[1]]!.style) }
            case "P" where f.count >= 3: nodes[f[1]]?.text = f[2]
            case "T" where f.count >= 3: nodes[f[1]]?.action = f[2]
            case "I" where f.count >= 4:
                let id = f[1], parent = f[2], idx = Int(f[3]) ?? 0
                if parent != "root", nodes[parent] != nil {
                    var kids = nodes[parent]!.children
                    kids.removeAll { $0 == id }
                    kids.insert(id, at: min(idx, kids.count))
                    nodes[parent]!.children = kids
                }
            case "R" where f.count >= 2: removeSubtree(f[1])
            case "X" where f.count >= 3:
                // Async host->engine command: X|token|capability|args. Run it AFTER
                // this apply() finishes (main.async), so a synchronous capability's
                // resolve() doesn't re-enter apply() mid-parse. args may contain '|'.
                let token = f[1], cap = f[2]
                let args = f.count >= 4 ? f[3...].joined(separator: "|") : ""
                DispatchQueue.main.async { [weak self] in self?.handleCommand(token, cap, args) }
            default: break
            }
        }
    }

    // Deliver a native capability result back to the engine and apply the re-render.
    func resolve(_ token: String, _ payload: String) { apply(Engine.resolve(token, payload)) }
    // Report a capability failure back to the engine (fires the request's onErr).
    func fail(_ token: String, _ message: String) { apply(Engine.fail(token, message)) }

    // Live native subscriptions (stream token -> timer/observer), for teardown.
    // A stream leaks if this isn't cleared on __cancel__ — the assertion the
    // teardown tests check via debug.activeStreams.
    var activeStreams: [String: Timer] = [:]
    // Real OS streams (battery/app-state/network) keep an unregister closure here
    // instead of a Timer; __cancel__ runs it so the observer is torn down.
    var streamTeardown: [String: () -> Void] = [:]
    var orientationTokens = Set<String>()   // orientation.watch tokens, so a lock can re-emit the new value
    var locFixes: [String: LocFix] = [:]    // live Location managers, keyed by token (once + watch)
    let motion = CMMotionManager()          // one shared motion manager; sensors fan out to token sets
    var accelTokens = Set<String>()
    var gyroTokens = Set<String>()
    var magTokens = Set<String>()
    var mediaCoord: MediaCoordinator? = nil   // retains the picker/camera delegate while presented
    var audioPlayer: AVPlayer? = nil   // single-track audio playback (Tier B); AVPlayer handles mp4 audio
    let speech = AVSpeechSynthesizer()  // text-to-speech (Tier B)

    // Execute a native capability requested via an `X|` command. Fire-and-forget
    // commands (token "0") just perform the side effect; async reads call resolve()
    // with the result payload. Runs on the main queue (post-apply).
    func handleCommand(_ token: String, _ cap: String, _ args: String) {
        switch cap {
        case "__cancel__":
            // Chuks cancelled this token (unmount / explicit): tear down its native
            // subscription so it stops firing and stops draining resources.
            activeStreams[token]?.invalidate(); activeStreams[token] = nil
            streamTeardown[token]?(); streamTeardown[token] = nil
        case "pulse.watch":
            // A stream: tick a counter until cancelled. Keyed by token so __cancel__
            // can invalidate exactly this one. ~7Hz keeps the per-tick re-render
            // smooth (a fast test-only stream; real streams tick far slower).
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
            emit()   // fire current value immediately
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
        case "deviceinfo.screen":
            let b = UIScreen.main.bounds
            resolve(token, "\(Int(b.width)),\(Int(b.height)),\(UIScreen.main.scale)")
        case "deviceinfo.appversion":
            let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
            let bld = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""
            resolve(token, "\(v),\(bld)")
        case "deviceinfo.locale":
            resolve(token, "\(Locale.current.languageCode ?? ""),\(Locale.current.regionCode ?? "")")
        case "linking.opensettings":
            if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
        case "mediapicker.image":
            let coord = MediaCoordinator(done: { [weak self] p in self?.mediaCoord = nil; self?.resolve(token, p) },
                                         cancel: { [weak self] m in self?.mediaCoord = nil; self?.fail(token, m) })
            mediaCoord = coord
            var cfg = PHPickerConfiguration(); cfg.filter = .images; cfg.selectionLimit = 1
            let pk = PHPickerViewController(configuration: cfg); pk.delegate = coord
            chuksTopVC()?.present(pk, animated: true)
        case "camera.photo":
            if !UIImagePickerController.isSourceTypeAvailable(.camera) { fail(token, "camera unavailable"); break }
            let coord = MediaCoordinator(done: { [weak self] p in self?.mediaCoord = nil; self?.resolve(token, p) },
                                         cancel: { [weak self] m in self?.mediaCoord = nil; self?.fail(token, m) })
            mediaCoord = coord
            let pk = UIImagePickerController(); pk.sourceType = .camera; pk.delegate = coord
            chuksTopVC()?.present(pk, animated: true)
        case "mediapicker.save":
            let path = args.hasPrefix("file://") ? String(args.dropFirst(7)) : args
            guard let img = UIImage(contentsOfFile: path) else { fail(token, "no such image"); break }
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { [weak self] status in
                guard status == .authorized || status == .limited else { DispatchQueue.main.async { self?.fail(token, "photos permission denied") }; return }
                PHPhotoLibrary.shared().performChanges({ PHAssetChangeRequest.creationRequestForAsset(from: img) },
                    completionHandler: { ok, err in DispatchQueue.main.async { ok ? self?.resolve(token, "ok") : self?.fail(token, err?.localizedDescription ?? "save failed") } })
            }
        case "biometrics.available":
            resolve(token, LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) ? "1" : "0")
        case "biometrics.authenticate":
            let ctx = LAContext()
            var perr: NSError?
            if !ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &perr) {
                fail(token, perr?.localizedDescription ?? "biometrics unavailable"); break
            }
            ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: args.isEmpty ? "Authenticate" : args) { [weak self] ok, e in
                DispatchQueue.main.async { ok ? self?.resolve(token, "success") : self?.fail(token, e?.localizedDescription ?? "authentication failed") }
            }
        case "debug.activeStreams": resolve(token, String(activeStreams.count + streamTeardown.count))
        case "debug.fail": fail(token, "simulated native failure")
        case "permission.status": permStatus(args, token)
        case "permission.request": permRequest(args, token)
        case "fs.write":
            // args = "name|<base64 content>"
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
        case "haptics.vibrate": hapticBuzz(Int(args) ?? 0)
        case "haptics.pattern": hapticPattern(args)
        case "torch.set": setTorch(args == "1")
        case "brightness.set": if let v = Double(args) { UIScreen.main.brightness = CGFloat(max(0, min(1, v))) }
        case "brightness.keepAwake": UIApplication.shared.isIdleTimerDisabled = (args == "1")
        case "orientation.watch":
            UIDevice.current.beginGeneratingDeviceOrientationNotifications()
            orientationTokens.insert(token)
            let emit: () -> Void = { [weak self] in self?.resolve(token, currentOrientationString()) }
            let o = NotificationCenter.default.addObserver(forName: UIDevice.orientationDidChangeNotification, object: nil, queue: .main) { _ in emit() }
            streamTeardown[token] = { [weak self] in self?.orientationTokens.remove(token); NotificationCenter.default.removeObserver(o); UIDevice.current.endGeneratingDeviceOrientationNotifications() }
            emit()   // fire current value immediately
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

    private var hapticEngine: CHHapticEngine?
    // A single buzz of `ms` (via a continuous CoreHaptics segment).
    private func hapticBuzz(_ ms: Int) { hapticPlay([(0.0, Double(max(1, ms)) / 1000.0)]) }
    // A custom wait,buzz,wait,buzz sequence in ms (even index = wait, odd = buzz).
    private func hapticPattern(_ csv: String) {
        let vals = csv.split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
        var segs: [(Double, Double)] = []; var t = 0.0
        for (i, v) in vals.enumerated() { if i % 2 == 1 { segs.append((t, v / 1000.0)) }; t += v / 1000.0 }
        hapticPlay(segs)
    }
    // Play continuous haptic segments [(startSec, durSec)]; fall back to a medium impact
    // where CoreHaptics is unsupported (older devices, the Simulator).
    private func hapticPlay(_ segs: [(Double, Double)]) {
        guard !segs.isEmpty, CHHapticEngine.capabilitiesForHardware().supportsHaptics else { fireHaptic("medium"); return }
        let events = segs.map { seg in
            CHHapticEvent(eventType: .hapticContinuous,
                          parameters: [CHHapticEventParameter(parameterID: .hapticIntensity, value: 1.0),
                                       CHHapticEventParameter(parameterID: .hapticSharpness, value: 0.5)],
                          relativeTime: seg.0, duration: seg.1)
        }
        do {
            if hapticEngine == nil { hapticEngine = try CHHapticEngine(); try hapticEngine?.start() }
            let player = try hapticEngine?.makePlayer(with: CHHapticPattern(events: events, parameters: []))
            try player?.start(atTime: 0)
        } catch { fireHaptic("medium") }
    }

    private func setTorch(_ on: Bool) {
        guard let dev = AVCaptureDevice.default(for: .video), dev.hasTorch else { return }
        try? dev.lockForConfiguration()
        try? dev.setTorchModeOn(level: 1.0)
        if !on { dev.torchMode = .off }
        dev.unlockForConfiguration()
    }

    // Permission (F2): read the current grant without prompting. camera/microphone/
    // photos/location resolve synchronously; notifications is async (getSettings).
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
        default: resolve(token, "undetermined")   // unknown kind
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
        let scene = UIApplication.shared.connectedScenes.first { $0.activationState == .foregroundActive } as? UIWindowScene
        guard let root = scene?.keyWindow?.rootViewController else { return }
        vc.popoverPresentationController?.sourceView = root.view   // iPad anchor
        root.present(vc, animated: true)
    }

    // A StatusBar node set/changed: pull its config into the observable state that
    // RootView reads. Runs inside apply() (an event response), never a view body.
    private func applyStatusBar(_ style: [String: String]) {
        let h = style["sbh"] == "1"; if h != sbHidden { sbHidden = h }
        let st = style["sbstyle"] ?? ""; if st != sbStyle { sbStyle = st }
        let c = style["sbcolor"] ?? ""; if c != sbColor { sbColor = c }
    }

    private func removeSubtree(_ id: String) {
        let prefix = id + "."
        // A removed StatusBar reverts the bar to defaults (visible, theme-driven).
        let hadStatusBar = nodes.keys.contains { ($0 == id || $0.hasPrefix(prefix)) && nodes[$0]?.kind == "StatusBar" }
        for k in nodes.keys where k == id || k.hasPrefix(prefix) { nodes[k] = nil }
        for k in nodes.keys {
            if let kids = nodes[k]?.children {
                let filtered = kids.filter { $0 != id && !$0.hasPrefix(prefix) }
                if filtered.count != kids.count { nodes[k]!.children = filtered }
            }
        }
        if hadStatusBar { if sbHidden { sbHidden = false }; if !sbStyle.isEmpty { sbStyle = "" }; if !sbColor.isEmpty { sbColor = "" } }
    }
}

// ================= style application (flexbox -> SwiftUI modifiers) ==============
struct BoxStyle: ViewModifier {
    let s: [String: String]
    let parentRow: Bool
    // Content alignment inside a fill frame. nil for CONTAINERS (so their flexible
    // children expand + distribute, e.g. tab items); set for TEXT leaves (so a
    // grown text aligns leading/center/right instead of centering).
    var align: Alignment? = nil
    var parentStretch: Bool = false      // parent's align-items is stretch (fill cross axis)
    func fill(_ v: AnyView, _ maxW: Bool) -> AnyView {
        if let a = align {
            return maxW ? AnyView(v.frame(maxWidth: .infinity, alignment: a)) : AnyView(v.frame(maxHeight: .infinity, alignment: a))
        }
        return maxW ? AnyView(v.frame(maxWidth: .infinity)) : AnyView(v.frame(maxHeight: .infinity))
    }
    // bg / radius / border / shadow / opacity — applied after sizing.
    func decor(_ input: AnyView) -> AnyView {
        var v = input
        if let bg = s["bg"] { let r = numOf(s["r"]) ?? 0; v = AnyView(v.background(RoundedRectangle(cornerRadius: r).fill(hexColor(bg)))) }
        if let r = numOf(s["r"]) { v = AnyView(v.clipShape(RoundedRectangle(cornerRadius: r))) }
        if let bw = numOf(s["bw"]), let bc = s["bc"] { let r = numOf(s["r"]) ?? 0; v = AnyView(v.overlay(RoundedRectangle(cornerRadius: r).stroke(hexColor(bc), lineWidth: bw))) }
        if let lvl = numOf(s["shadow"]) { let radius: CGFloat = lvl >= 3 ? 20 : (lvl == 2 ? 8 : 3); v = AnyView(v.shadow(color: Color.black.opacity(0.18), radius: radius, x: 0, y: lvl >= 3 ? 8 : 3)) }
        if let o = numOf(s["opacity"]) { v = AnyView(v.opacity(Double(o) / 100.0)) }
        return v
    }
    func body(content: Content) -> some View {
        var v = AnyView(content)
        if let p = numOf(s["p"]) { v = AnyView(v.padding(p)) }
        // Absolute positioning (List rows in a fixed-height content ZStack): fill
        // width + fixed height FIRST so the bg spans the row, THEN inset by
        // left/right and offset by top. (Applying bg before the width-fill was why
        // rows collapsed to invisible content-width pills.)
        if s["pos"] == "abs" {
            let h = numOf(s["h"])
            v = AnyView(v.frame(maxWidth: .infinity, minHeight: h, maxHeight: h))
            v = decor(v)
            let top = numOf(s["top"]) ?? 0, left = numOf(s["left"]) ?? 0, right = numOf(s["right"]) ?? 0
            return AnyView(v.padding(.leading, left).padding(.trailing, right).offset(y: top))
        }
        // grow along the parent's main axis
        if let g = numOf(s["g"]), g > 0 { v = fill(v, parentRow) }
        // cross-axis stretch (flexbox align-items: stretch default): a COLUMN
        // stretches its children to full width (cards, rows). NOT full height on row
        // children — that fights the vertical layout (tab bar would unpin).
        if parentStretch, !parentRow, numOf(s["w"]) == nil { v = fill(v, true) }
        // fixed size
        let w = numOf(s["w"]), h = numOf(s["h"])
        if w != nil || h != nil { v = AnyView(v.frame(width: w, height: h)) }
        v = decor(v)
        // transform (visual only) + implicit animation: when `anim` is set, SwiftUI
        // tweens the offset/scale/rotation/opacity whenever the value key changes.
        let tx = numOf(s["tx"]) ?? 0, ty = numOf(s["ty"]) ?? 0
        let sc = (numOf(s["sc"]) ?? 100) / 100, rot = numOf(s["rot"]) ?? 0
        if tx != 0 || ty != 0 || sc != 1 || rot != 0 {
            v = AnyView(v.rotationEffect(.degrees(rot)).scaleEffect(sc).offset(x: tx, y: ty))
        }
        if let ms = numOf(s["anim"]) {
            let key = "\(tx),\(ty),\(sc),\(rot),\(s["opacity"] ?? "")"
            let dur = ms / 1000
            let anim: Animation = s["ez"] == "spring" ? .spring() : (s["ez"] == "linear" ? .linear(duration: dur) : .easeInOut(duration: dur))
            v = AnyView(v.animation(anim, value: key))
        }
        return v
    }
}

// A node with an action tag that isn't a Button/Input gets a tap gesture (tab
// bar items, chips, tappable cards are Views with onPress). Matches the UIKit
// host, which adds a tap recognizer to any actioned view.
struct TapAction: ViewModifier {
    let action: String
    let scene: Scene
    @ViewBuilder func body(content: Content) -> some View {
        if action.isEmpty { content }
        else { content.contentShape(Rectangle()).onTapGesture { scene.dispatch(action) } }
    }
}

// Pressable: instant touch-down dim (no round-trip), fire onPress on release. The
// DragGesture(minimumDistance: 0) reports the press-down immediately so the opacity
// animates the moment the finger lands, like TouchableOpacity.
struct PressAction: ViewModifier {
    let action: String
    let activeOpacity: Double
    let scene: Scene
    @State private var pressed = false
    func body(content: Content) -> some View {
        content
            .contentShape(Rectangle())
            .opacity(pressed ? activeOpacity : 1.0)
            .animation(.easeOut(duration: 0.09), value: pressed)
            .simultaneousGesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in pressed = true }
                    .onEnded { _ in
                        pressed = false
                        if !action.isEmpty { scene.dispatch(action) }
                    }
            )
    }
}

// A text field that owns its text across re-renders (its @State survives because
// its structural identity is stable), reports each change to the engine, and
// dismisses the keyboard on return. `placeholder` is the Chuks Input's text.
struct ChuksInput: View {
    @ObservedObject var scene: Scene
    let placeholder: String
    let action: String
    let style: [String: String]
    let parentRow: Bool
    let parentStretch: Bool
    @State private var text: String = ""
    var body: some View {
        Group {
            if style["sec"] == "1" { SecureField(placeholder, text: $text) }   // password field
            else { TextField(placeholder, text: $text) }
        }
            .onChange(of: text) { newVal in if !action.isEmpty { scene.input(action, newVal) } }
            .textFieldStyle(.plain)
            .autocorrectionDisabled(true)
            .textInputAutocapitalization(.never)
            .submitLabel(.done)
            .font(textFont(style))
            .foregroundColor(style["fg"].map { hexColor($0) } ?? .primary)
            .modifier(BoxStyle(s: style, parentRow: parentRow, parentStretch: parentStretch))
    }
}

// A native value slider that owns its value across re-renders (stable @State,
// seeded from `slv` at init so it never spuriously dispatches on mount) and reports
// each rounded value to the engine as the user drags. Range is `slmin`...`slmax`.
struct ChuksSlider: View {
    @ObservedObject var scene: Scene
    let action: String
    let style: [String: String]
    let parentRow: Bool
    let parentStretch: Bool
    @State private var value: Double
    init(scene: Scene, action: String, style: [String: String], parentRow: Bool, parentStretch: Bool) {
        self.scene = scene; self.action = action; self.style = style
        self.parentRow = parentRow; self.parentStretch = parentStretch
        let lo = Int(style["slmin"] ?? "0") ?? 0
        _value = State(initialValue: Double(Int(style["slv"] ?? "") ?? lo))
    }
    var body: some View {
        let lo = Double(Int(style["slmin"] ?? "0") ?? 0)
        let hi = Double(Int(style["slmax"] ?? "100") ?? 100)
        let tint = style["fg"].map { hexColor($0) } ?? Color.accentColor
        return Slider(value: $value, in: lo...max(lo + 1, hi), step: 1)
            .tint(tint)
            .onChange(of: value) { nv in if !action.isEmpty { scene.input(action, String(Int(nv.rounded()))) } }
            .modifier(BoxStyle(s: style, parentRow: parentRow, parentStretch: parentStretch))
    }
}

// A UITextView wrapped for SwiftUI with a CLEAR background (TextEditor's own bg is
// opaque on iOS 15 and can't be cleared reliably), so the field bg + rounded corners
// from BoxStyle show through and a placeholder can sit behind it.
struct MultilineTextView: UIViewRepresentable {
    let placeholder: String
    @Binding var text: String
    let textColor: UIColor
    let onChange: (String) -> Void
    func makeUIView(context: Context) -> UITextView {
        let tv = UITextView()
        tv.font = .systemFont(ofSize: 15)
        tv.backgroundColor = .clear
        tv.textColor = textColor
        tv.delegate = context.coordinator
        tv.textContainerInset = UIEdgeInsets(top: 10, left: 8, bottom: 10, right: 8)
        return tv
    }
    func updateUIView(_ tv: UITextView, context: Context) { if tv.text != text { tv.text = text } }
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    final class Coordinator: NSObject, UITextViewDelegate {
        let parent: MultilineTextView
        init(_ p: MultilineTextView) { parent = p }
        func textViewDidChange(_ tv: UITextView) { parent.text = tv.text; parent.onChange(tv.text) }
    }
}

// A multi-line text area that owns its text across re-renders, reports each change,
// and overlays a placeholder while empty.
struct ChuksTextArea: View {
    @ObservedObject var scene: Scene
    let placeholder: String
    let action: String
    let style: [String: String]
    let parentRow: Bool
    let parentStretch: Bool
    @State private var text: String = ""
    var body: some View {
        ZStack(alignment: .topLeading) {
            if text.isEmpty {
                Text(placeholder).foregroundColor(.secondary)
                    .padding(.horizontal, 13).padding(.vertical, 10)
                    .allowsHitTesting(false)
            }
            MultilineTextView(placeholder: placeholder, text: $text,
                              textColor: style["fg"].map { UIColor(hexColor($0)) } ?? .label,
                              onChange: { nv in if !action.isEmpty { scene.input(action, nv) } })
        }
        .modifier(BoxStyle(s: style, parentRow: parentRow, parentStretch: parentStretch))
    }
}

// ISO <-> Date helpers for the DatePicker value channel (POSIX locale + current
// time zone so the string round-trips regardless of device locale).
func dpFormatter(_ mode: String) -> DateFormatter {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.timeZone = TimeZone.current
    f.dateFormat = (mode == "time") ? "HH:mm" : (mode == "datetime" ? "yyyy-MM-dd'T'HH:mm" : "yyyy-MM-dd")
    return f
}
func dpParseISO(_ s: String, _ mode: String) -> Date? { s.isEmpty ? nil : dpFormatter(mode).date(from: s) }
func dpFormatISO(_ d: Date, _ mode: String) -> String { dpFormatter(mode).string(from: d) }

// A native date/time picker that owns its value across re-renders (seeded @State,
// like ChuksSlider, so it never spuriously dispatches on mount) and reports each
// pick to the engine as an ISO string.
struct ChuksDatePicker: View {
    @ObservedObject var scene: Scene
    let action: String
    let mode: String
    let style: [String: String]
    let parentRow: Bool
    let parentStretch: Bool
    @State private var date: Date
    init(scene: Scene, action: String, value: String, mode: String, style: [String: String], parentRow: Bool, parentStretch: Bool) {
        self.scene = scene; self.action = action; self.mode = mode; self.style = style
        self.parentRow = parentRow; self.parentStretch = parentStretch
        _date = State(initialValue: dpParseISO(value, mode) ?? Date())
    }
    var body: some View {
        let comps: DatePickerComponents = (mode == "time") ? [.hourAndMinute]
            : (mode == "datetime" ? [.date, .hourAndMinute] : [.date])
        let base = DatePicker("", selection: $date, displayedComponents: comps)
            .labelsHidden()
            .tint(style["fg"].map { hexColor($0) } ?? Color.accentColor)
            .onChange(of: date) { nv in if !action.isEmpty { scene.input(action, dpFormatISO(nv, mode)) } }
        // display: "" compact field (tap opens a popover), "inline" always-open
        // graphical calendar, "wheels" the classic wheel.
        let disp = style["dpd"] ?? ""
        let styled: AnyView = disp == "inline" ? AnyView(base.datePickerStyle(.graphical))
            : (disp == "wheels" ? AnyView(base.datePickerStyle(.wheel)) : AnyView(base.datePickerStyle(.compact)))
        return styled
            .modifier(BoxStyle(s: style, parentRow: parentRow, align: .leading, parentStretch: parentStretch))   // hug the leading edge
    }
}

// A native select: a Menu that shows the chosen option's label + a chevron, and
// dispatches the picked index. Controlled — `sel` comes from Chuks state, so the
// re-render after a pick updates the label.
struct ChuksSelect: View {
    @ObservedObject var scene: Scene
    let action: String
    let options: [String]
    let sel: Int
    let style: [String: String]
    let parentRow: Bool
    let parentStretch: Bool
    var body: some View {
        Menu {
            ForEach(options.indices, id: \.self) { i in
                Button { if !action.isEmpty { scene.input(action, String(i)) } } label: {
                    if i == sel { Label(options[i], systemImage: "checkmark") } else { Text(options[i]) }
                }
            }
        } label: {
            HStack {
                Text(sel >= 0 && sel < options.count ? options[sel] : "Select")
                    .foregroundColor(style["fg"].map { hexColor($0) } ?? .primary)
                Spacer()
                Image(systemName: "chevron.up.chevron.down").font(.system(size: 12)).foregroundColor(.secondary)
            }
            .padding(.horizontal, 14)          // breathing room inside the field
            .contentShape(Rectangle())
        }
        .modifier(BoxStyle(s: style, parentRow: parentRow, parentStretch: parentStretch))
    }
}

// A native MapKit map centered on "lat,lng,zoom" (a real MKMapView, works across iOS
// versions unlike the newer SwiftUI Map API). A pin marks the center.
struct ChuksMap: UIViewRepresentable {
    let spec: String   // "lat,lng,zoom"
    func makeUIView(context: Context) -> MKMapView {
        let mv = MKMapView(); mv.isRotateEnabled = false; return mv
    }
    func updateUIView(_ mv: MKMapView, context: Context) {
        let p = spec.components(separatedBy: ",")
        guard p.count == 3, let lat = Double(p[0]), let lng = Double(p[1]), let z = Double(p[2]) else { return }
        let span = 360.0 / pow(2.0, z)
        mv.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: lat, longitude: lng),
                                        span: MKCoordinateSpan(latitudeDelta: span, longitudeDelta: span)), animated: false)
        mv.removeAnnotations(mv.annotations)
        let pin = MKPointAnnotation(); pin.coordinate = CLLocationCoordinate2D(latitude: lat, longitude: lng)
        mv.addAnnotation(pin)
    }
}

// A vector drawing surface: parses the ";"-joined shape descriptors and draws them
// with SwiftUI's GraphicsContext. Shapes use pixel coordinates within the canvas.
struct ChuksCanvas: View {
    let shapes: String
    let style: [String: String]
    let parentRow: Bool
    let parentStretch: Bool
    var body: some View {
        Canvas { ctx, _ in
            for shape in shapes.components(separatedBy: ";") where !shape.isEmpty {
                let f = shape.components(separatedBy: ",")
                guard let type = f.first else { continue }
                switch type {
                case "rect" where f.count >= 9:
                    guard let x = Double(f[1]), let y = Double(f[2]), let w = Double(f[3]), let h = Double(f[4]) else { break }
                    let path = Path(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerRadius: Double(f[8]) ?? 0)
                    if !f[5].isEmpty { ctx.fill(path, with: .color(hexColor(f[5]))) }
                    if !f[6].isEmpty, let sw = Double(f[7]), sw > 0 { ctx.stroke(path, with: .color(hexColor(f[6])), lineWidth: sw) }
                case "circle" where f.count >= 7:
                    guard let cx = Double(f[1]), let cy = Double(f[2]), let r = Double(f[3]) else { break }
                    let path = Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2))
                    if !f[4].isEmpty { ctx.fill(path, with: .color(hexColor(f[4]))) }
                    if !f[5].isEmpty, let sw = Double(f[6]), sw > 0 { ctx.stroke(path, with: .color(hexColor(f[5])), lineWidth: sw) }
                case "line" where f.count >= 7:
                    guard let x1 = Double(f[1]), let y1 = Double(f[2]), let x2 = Double(f[3]), let y2 = Double(f[4]) else { break }
                    var path = Path(); path.move(to: CGPoint(x: x1, y: y1)); path.addLine(to: CGPoint(x: x2, y: y2))
                    if !f[5].isEmpty { ctx.stroke(path, with: .color(hexColor(f[5])), style: StrokeStyle(lineWidth: Double(f[6]) ?? 1, lineCap: .round)) }
                case "path" where f.count >= 5:
                    let path = ChuksCanvas.parsePath(f[1])
                    if !f[2].isEmpty { ctx.fill(path, with: .color(hexColor(f[2]))) }
                    if !f[3].isEmpty, let sw = Double(f[4]), sw > 0 { ctx.stroke(path, with: .color(hexColor(f[3])), lineWidth: sw) }
                default: break
                }
            }
        }
        .modifier(BoxStyle(s: style, parentRow: parentRow, parentStretch: parentStretch))
    }
    static func parsePath(_ d: String) -> Path {
        var path = Path()
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
            } else if t == "Z" || t == "z" { path.closeSubpath() }
            i += 1
        }
        return path
    }
}

// A native menu button: a fixed label + one-shot action items (no selection state),
// dispatching the picked index. Distinct from ChuksSelect, which tracks a selection.
struct ChuksMenu: View {
    @ObservedObject var scene: Scene
    let action: String
    let label: String
    let items: [String]
    let style: [String: String]
    let parentRow: Bool
    let parentStretch: Bool
    var body: some View {
        Menu {
            ForEach(items.indices, id: \.self) { i in
                Button { if !action.isEmpty { scene.input(action, String(i)) } } label: { Text(items[i]) }
            }
        } label: {
            HStack {
                Text(label).foregroundColor(style["fg"].map { hexColor($0) } ?? .primary)
                Spacer()
                Image(systemName: "chevron.down").font(.system(size: 12)).foregroundColor(.secondary)
            }
            .padding(.horizontal, 14)
            .contentShape(Rectangle())
        }
        .modifier(BoxStyle(s: style, parentRow: parentRow, parentStretch: parentStretch))
    }
}

// A 0-size probe that finds its enclosing UIScrollView and KVO-observes its
// contentOffset, reporting the vertical offset on EVERY scroll. This is far more
// reliable than a GeometryReader/preference (which SwiftUI doesn't re-evaluate for
// a fast/programmatic scroll of a very tall List content) — the List's windowing
// depends on getting the offset on every frame of a fling.
struct ScrollOffsetReader: UIViewRepresentable {
    let onOffset: (CGFloat) -> Void
    func makeUIView(context: Context) -> UIView {
        let v = UIView(frame: .zero)
        DispatchQueue.main.async { context.coordinator.attach(from: v) }
        return v
    }
    func updateUIView(_ uiView: UIView, context: Context) {
        DispatchQueue.main.async { context.coordinator.attach(from: uiView) }
    }
    func makeCoordinator() -> Coordinator { Coordinator(onOffset) }
    final class Coordinator: NSObject {
        let onOffset: (CGFloat) -> Void
        weak var scrollView: UIScrollView?
        var obs: NSKeyValueObservation?
        init(_ cb: @escaping (CGFloat) -> Void) { onOffset = cb }
        func attach(from v: UIView) {
            guard scrollView == nil else { return }
            var s = v.superview
            while let cur = s, !(cur is UIScrollView) { s = cur.superview }
            if let sv = s as? UIScrollView {
                scrollView = sv
                obs = sv.observe(\.contentOffset, options: [.initial, .new]) { [weak self] sv, _ in
                    self?.onOffset(sv.contentOffset.y)
                }
            }
        }
        deinit { obs?.invalidate() }
    }
}

// A Chuks List/Scroll: a ScrollView whose child is the fixed-height content node
// (its items are absolutely positioned). It reports the visible window (offset +
// height) to the engine, which mounts/recycles rows to match (virtualization).
struct ChuksScroll: View {
    @ObservedObject var scene: Scene
    let id: String
    let style: [String: String]
    let parentRow: Bool
    let parentStretch: Bool
    var body: some View {
        GeometryReader { outer in
            ScrollView {
                // probe the underlying UIScrollView for its live contentOffset
                ScrollOffsetReader { off in
                    scene.viewport(Int32(max(0, off)), Int32(outer.size.height))
                }.frame(width: 0, height: 0)
                ForEach(scene.nodes[id]?.children ?? [], id: \.self) { cid in
                    NodeView(scene: scene, id: cid, parentRow: false, parentStretch: true)
                }
            }
            .onAppear { scene.viewport(0, Int32(outer.size.height)) }
            // Pull-to-refresh when the Scroll has an onRefresh (its style carries `rfsh`).
            .refreshable {
                guard style["rfsh"] != nil, let action = scene.nodes[id]?.action, !action.isEmpty else { return }
                scene.dispatch(action)                       // run onRefresh (synchronous re-render)
                try? await Task.sleep(nanoseconds: 600_000_000)   // brief spinner so the refresh reads as native
            }
        }
        .modifier(BoxStyle(s: style, parentRow: parentRow, parentStretch: parentStretch))
    }
}

func textFont(_ s: [String: String]) -> Font {
    let size = numOf(s["fs"]) ?? 14
    if let fam = s["font"], !fam.isEmpty { return .custom(fam, size: size) }
    let weight: Font.Weight = s["fw"] == "bold" ? .bold : (s["fw"] == "semibold" ? .semibold : .regular)
    return .system(size: size, weight: weight)
}

// ================= recursive renderer ===========================================
struct NodeView: View {
    @ObservedObject var scene: Scene
    let id: String
    var parentRow: Bool = false
    var parentStretch: Bool = false

    var body: some View {
        if let node = scene.nodes[id] {
            // A Pressable (press-feedback hint) gets instant press dim + onPress.
            if let po = node.style["press"] {
                render(node).modifier(PressAction(action: node.action,
                                                  activeOpacity: Double(numOf(po) ?? 60) / 100.0,
                                                  scene: scene))
            } else {
                // Button/Input/Switch wire their own action; any other actioned node
                // (a View tab-bar item, chip, tappable card/row) gets a tap gesture.
                let tap = (node.kind == "Button" || node.kind == "Input" || node.kind == "Switch") ? "" : node.action
                render(node).modifier(TapAction(action: tap, scene: scene))
            }
        }
    }

    @ViewBuilder func render(_ node: NodeData) -> some View {
        switch node.kind {
        case "Text":   textView(node)
        case "Button": buttonView(node)
        case "Image":  imageView(node)
        case "Input":  inputView(node)
        case "TextArea": ChuksTextArea(scene: scene, placeholder: node.text, action: node.action, style: node.style, parentRow: parentRow, parentStretch: parentStretch)
        case "Switch": switchView(node)
        case "Slider": ChuksSlider(scene: scene, action: node.action, style: node.style, parentRow: parentRow, parentStretch: parentStretch)
        case "Select": ChuksSelect(scene: scene, action: node.action, options: node.text.components(separatedBy: "\t"), sel: Int(node.style["seli"] ?? "0") ?? 0, style: node.style, parentRow: parentRow, parentStretch: parentStretch)
        case "DatePicker", "DatePickerInline": ChuksDatePicker(scene: scene, action: node.action, value: node.text, mode: node.style["dp"] ?? "date", style: node.style, parentRow: parentRow, parentStretch: parentStretch)
        case "Menu": ChuksMenu(scene: scene, action: node.action, label: node.text.components(separatedBy: "\t").first ?? "Menu", items: Array(node.text.components(separatedBy: "\t").dropFirst()), style: node.style, parentRow: parentRow, parentStretch: parentStretch)
        case "ContextMenu": container(node).contextMenu {
            ForEach(node.text.components(separatedBy: "\t").indices, id: \.self) { i in
                Button { if !node.action.isEmpty { scene.input(node.action, String(i)) } } label: { Text(node.text.components(separatedBy: "\t")[i]) }
            }
        }
        case "Gesture": container(node)
            .onTapGesture(count: 2) { if !node.action.isEmpty { scene.input(node.action, "doubletap") } }
            .onLongPressGesture(minimumDuration: 0.5) { if !node.action.isEmpty { scene.input(node.action, "longpress") } }
            .gesture(DragGesture(minimumDistance: 24).onEnded { val in
                let dx = val.translation.width, dy = val.translation.height
                let dir = abs(dx) > abs(dy) ? (dx < 0 ? "left" : "right") : (dy < 0 ? "up" : "down")
                if !node.action.isEmpty { scene.input(node.action, "swipe:\(dir)") }
            })
        case "Map": ChuksMap(spec: node.text).modifier(BoxStyle(s: node.style, parentRow: parentRow, parentStretch: parentStretch))
        case "Canvas": ChuksCanvas(shapes: node.text, style: node.style, parentRow: parentRow, parentStretch: parentStretch)
        case "Spinner": spinnerView(node)
        case "Progress": progressBarView(node)
        case "StatusBar": Color.clear.frame(width: 0, height: 0)   // directive only; config read in apply()
        case "Modal": Color.clear.frame(width: 0, height: 0)       // rendered in the overlay by RootView, not inline
        case "Video":  videoView(node)
        case "WebView": webView(node)
        case "ImageBackground": imageBackgroundView(node)
        case "Scroll": ChuksScroll(scene: scene, id: id, style: node.style, parentRow: parentRow, parentStretch: parentStretch)
        default:       container(node)   // "View" and anything structural
        }
    }

    // ---- leaves ----
    func textView(_ node: NodeData) -> some View {
        let s = node.style
        let color = s["fg"].map { hexColor($0) } ?? Color.primary
        let ta = s["ta"]
        let align: TextAlignment = ta == "center" ? .center : (ta == "right" ? .trailing : .leading)
        // a grown text box aligns its text left/center/right per ta (default left)
        let boxAlign: Alignment = ta == "center" ? .center : (ta == "right" ? .trailing : .leading)
        return Text(node.text).font(textFont(s)).foregroundColor(color).multilineTextAlignment(align)
            .modifier(BoxStyle(s: s, parentRow: parentRow, align: boxAlign, parentStretch: parentStretch))
            // A label is not interactive, but a grown label (`.frame(maxWidth:.infinity)`,
            // e.g. a NavBar title) still occupies its full frame for hit-testing and can
            // swallow taps meant for a sibling button (the theme toggle). Make labels
            // tap-transparent; a genuinely tappable text still receives taps via the
            // TapAction wrapper's contentShape applied outside in NodeView.body.
            .allowsHitTesting(false)
    }

    func buttonView(_ node: NodeData) -> some View {
        let s = node.style
        let color = s["fg"].map { hexColor($0) } ?? Color.white
        let action = node.action
        return Button(action: { if !action.isEmpty { scene.dispatch(action) } }) {
            Text(node.text).font(textFont(s)).foregroundColor(color)
                .frame(maxWidth: numOf(s["g"]) != nil ? .infinity : nil)
        }
        .buttonStyle(.plain)
        .modifier(BoxStyle(s: s, parentRow: parentRow, parentStretch: parentStretch))
    }

    @ViewBuilder func imageView(_ node: NodeData) -> some View {
        let s = node.style
        if node.text.hasPrefix("http") {                     // remote (network) image
            let mode: ContentMode = s["rmode"] == "contain" ? .fit : .fill
            AsyncImage(url: URL(string: node.text)) { img in
                img.resizable().aspectRatio(contentMode: mode)
            } placeholder: {
                s["bg"].map { hexColor($0) } ?? Color.gray.opacity(0.15)
            }
            .frame(width: numOf(s["w"]), height: numOf(s["h"]))
            .clipped()
            .cornerRadius(numOf(s["r"]) ?? 0)
        } else if node.text.hasPrefix("file://"),
                  let ui = UIImage(contentsOfFile: String(node.text.dropFirst(7))) {   // picked/captured local file
            let mode: ContentMode = s["rmode"] == "contain" ? .fit : .fill
            Image(uiImage: ui).resizable().aspectRatio(contentMode: mode)
                .frame(width: numOf(s["w"]), height: numOf(s["h"]))
                .clipped()
                .cornerRadius(numOf(s["r"]) ?? 0)
        } else if !node.text.isEmpty,
                  let url = Bundle.main.url(forResource: node.text, withExtension: nil),
                  let ui = UIImage(contentsOfFile: url.path) {   // bundled local asset (e.g. chuks-logo.png)
            let mode: ContentMode = s["rmode"] == "contain" ? .fit : .fill
            Image(uiImage: ui).resizable().aspectRatio(contentMode: mode)
                .frame(width: numOf(s["w"]), height: numOf(s["h"]))
                .clipped()
                .cornerRadius(numOf(s["r"]) ?? 0)
        } else {                                             // local SF Symbol icon
            let tint = s["fg"].map { hexColor($0) } ?? Color.white
            let sym = s["img"] ?? ""
            Image(systemName: sym.isEmpty ? "square" : sym)
                .foregroundColor(tint)
                .modifier(BoxStyle(s: s, parentRow: parentRow, parentStretch: parentStretch))
        }
    }

    // A container whose background is a remote image, children on top.
    func imageBackgroundView(_ node: NodeData) -> some View {
        let s = node.style
        let mode: ContentMode = s["rmode"] == "contain" ? .fit : .fill
        return container(node).background(
            AsyncImage(url: URL(string: node.text)) { img in
                img.resizable().aspectRatio(contentMode: mode)
            } placeholder: { Color.gray.opacity(0.15) }
                .clipped()
        ).cornerRadius(numOf(s["r"]) ?? 0)
    }

    func inputView(_ node: NodeData) -> some View {
        // Uncontrolled like the UIKit host: the field owns its text (its own @State),
        // reports every change to the engine, and node.text is the placeholder.
        ChuksInput(scene: scene, placeholder: node.text, action: node.action,
                   style: node.style, parentRow: parentRow, parentStretch: parentStretch)
    }

    // A native SwiftUI Toggle. Controlled: `isOn` reads the Chuks state (style "on");
    // flipping it only dispatches the node's action — Chuks updates its own state and
    // the re-render syncs the control back. `bg` tints the on-track.
    func switchView(_ node: NodeData) -> some View {
        let on = node.style["on"] == "1"
        let action = node.action
        let tint = node.style["bg"].map { hexColor($0) } ?? Color.accentColor
        // `bg` on a Switch is the on-TINT, not a background rectangle. Strip it before
        // BoxStyle so decor() doesn't paint a box behind the native toggle.
        var layout = node.style; layout["bg"] = nil
        return Toggle("", isOn: Binding(get: { on }, set: { _ in
            if !action.isEmpty { scene.dispatch(action) }
        }))
        .labelsHidden()
        .tint(tint)
        .modifier(BoxStyle(s: layout, parentRow: parentRow, parentStretch: parentStretch))
    }

    // A native indeterminate spinner. `w` is the target diameter (the default
    // circular ProgressView is ~20pt, so scale to hit it), `fg` the tint.
    func spinnerView(_ node: NodeData) -> some View {
        let sz = numOf(node.style["w"]) ?? 24
        let tint = node.style["fg"].map { hexColor($0) } ?? Color.secondary
        return ProgressView()
            .progressViewStyle(.circular)
            .tint(tint)
            .scaleEffect(sz / 20.0)
            .modifier(BoxStyle(s: node.style, parentRow: parentRow, parentStretch: parentStretch))
    }

    func videoView(_ node: NodeData) -> some View {
        ChuksVideo(src: node.style["vid"] ?? "")
            .modifier(BoxStyle(s: node.style, parentRow: parentRow, parentStretch: parentStretch))
    }

    func webView(_ node: NodeData) -> some View {
        ChuksWeb(url: node.text)
            .modifier(BoxStyle(s: node.style, parentRow: parentRow, parentStretch: parentStretch))
    }

    // A determinate linear progress bar. `prog` is the fill percent (0-100), `fg` the tint.
    func progressBarView(_ node: NodeData) -> some View {
        let pct = numOf(node.style["prog"]) ?? 0
        let tint = node.style["fg"].map { hexColor($0) } ?? Color.accentColor
        return ProgressView(value: Double(pct), total: 100)
            .progressViewStyle(.linear)
            .tint(tint)
            .modifier(BoxStyle(s: node.style, parentRow: parentRow, parentStretch: parentStretch))
    }

    // ---- container (View): flexbox -> SwiftUI stack ----
    @ViewBuilder func container(_ node: NodeData) -> some View {
        let s = node.style
        let kids0 = node.children
        // A List's content node holds absolutely-positioned rows: render as a
        // fixed-height ZStack (top-leading) so each row sits at its `top` offset.
        if !kids0.isEmpty && kids0.allSatisfy({ scene.nodes[$0]?.style["pos"] == "abs" }) {
            let ch = numOf(s["h"])
            ZStack(alignment: .topLeading) {
                // full-size anchor: makes the ZStack's bounds the content height so
                // offset rows aren't clipped to a single row's natural bounds.
                Color.clear.frame(maxWidth: .infinity, minHeight: ch, maxHeight: ch)
                ForEach(kids0, id: \.self) { cid in
                    NodeView(scene: scene, id: cid, parentRow: false, parentStretch: false)
                }
            }
            .modifier(BoxStyle(s: s, parentRow: parentRow, parentStretch: parentStretch))
        } else {
            flowContainer(node)
        }
    }

    @ViewBuilder func flowContainer(_ node: NodeData) -> some View {
        let s = node.style
        let isRow = s["d"] == "row"
        let gap = numOf(s["gap"]) ?? 0
        // justify only distributes when the container actually fills its OWN main
        // axis (a main-axis size, or grow along its own main axis). Otherwise it is
        // content-sized and justify is a no-op in flexbox — adding Spacers here would
        // wrongly balloon the stack (e.g. tab-bar items with justify-center).
        let hasMainSize = isRow ? (numOf(s["w"]) != nil) : (numOf(s["h"]) != nil)
        let grows = (numOf(s["g"]) ?? 0) > 0
        let a = s["a"]
        let crossStretch = (a == nil || a == "stretch")
        let kids = node.children
        let hasGrowChild = kids.contains { (numOf(scene.nodes[$0]?.style["g"]) ?? 0) > 0 }
        // This container fills its own main axis (has room to distribute) if it has a
        // main-axis size, grows along its own main axis, or is a row a parent column
        // stretched to full width. Only then does justify/flex-start packing apply.
        let fillsMain = hasMainSize
            || (grows && (isRow == parentRow))
            || (parentStretch && !parentRow && isRow)
        let justify = fillsMain ? (s["j"] ?? "start") : ""
        // Content packing via Spacers (NOT frame alignment, which would collapse grow
        // children). flex-start (default) gets a TRAILING spacer to pack leading —
        // unless a child grows and already absorbs the space.
        let leadSpacer = justify == "center" || justify == "end"
        let trailSpacer = justify == "center" || (justify == "start" && !hasGrowChild)
        if isRow {
            HStack(alignment: alignV(a), spacing: gap) {
                if leadSpacer { Spacer(minLength: 0) }
                ForEach(kids, id: \.self) { cid in
                    NodeView(scene: scene, id: cid, parentRow: true, parentStretch: crossStretch)
                    if justify == "between", let i = kids.firstIndex(of: cid), i < kids.count - 1 { Spacer(minLength: 0) }
                }
                if trailSpacer { Spacer(minLength: 0) }
            }
            .modifier(BoxStyle(s: s, parentRow: parentRow, parentStretch: parentStretch))
        } else {
            VStack(alignment: alignH(a), spacing: gap) {
                if leadSpacer { Spacer(minLength: 0) }
                ForEach(kids, id: \.self) { cid in
                    NodeView(scene: scene, id: cid, parentRow: false, parentStretch: crossStretch)
                    if justify == "between", let i = kids.firstIndex(of: cid), i < kids.count - 1 { Spacer(minLength: 0) }
                }
                if trailSpacer { Spacer(minLength: 0) }
            }
            .modifier(BoxStyle(s: s, parentRow: parentRow, parentStretch: parentStretch))
        }
    }

    func alignV(_ a: String?) -> VerticalAlignment {
        switch a { case "start": return .top; case "end": return .bottom; default: return .center }
    }
    func alignH(_ a: String?) -> HorizontalAlignment {
        switch a { case "center": return .center; case "end": return .trailing; default: return .leading }
    }
}

// ================= app entry (SwiftUI lifecycle, no AppDelegate) =================
struct RootView: View {
    @ObservedObject var scene: Scene
    // The true OS appearance — readable as long as we DON'T force preferredColorScheme
    // (we only force it once the user overrides), so live OS changes stay detectable.
    @Environment(\.colorScheme) private var osScheme
    // The currently-visible Alert node (avis=1), parsed — drives the native .alert.
    private var alertInfo: AlertInfo? {
        guard let id = scene.nodes.first(where: { $0.value.kind == "Alert" && $0.value.style["avis"] == "1" })?.key,
              let n = scene.nodes[id] else { return nil }
        let f = n.text.components(separatedBy: "\t")
        return AlertInfo(id: id,
                         title: f.count > 0 ? f[0] : "", message: f.count > 1 ? f[1] : "",
                         confirm: f.count > 2 ? f[2] : "OK", cancel: f.count > 3 ? f[3] : "")
    }
    var body: some View {
        // The app's root background IS the theme signal: it flips 0B1120 -> F6F7F9
        // when setTheme() runs. Drive the safe-area fill and the status-bar scheme
        // from it so the host chrome follows light/dark instead of being pinned dark.
        let appBg = scene.nodes["app"]?.style["bg"] ?? "0B1120"
        // A GeometryReader that RESPECTS the safe area reports the device insets in
        // geo.safeAreaInsets — report them to the engine. The ZStack inside still fills
        // the screen (its bg ignoresSafeArea); content sits in the safe area as before.
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                hexColor(appBg).ignoresSafeArea()
                if scene.nodes["app"] != nil {
                    NodeView(scene: scene, id: "app")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .ignoresSafeArea(.keyboard, edges: .bottom)
                }
                if !scene.sbColor.isEmpty {   // StatusBar color: fill the top safe area
                    hexColor(scene.sbColor)
                        .frame(maxWidth: .infinity)
                        .frame(height: geo.safeAreaInsets.top)
                        .ignoresSafeArea(edges: .top)
                }
                modalOverlay   // a visible Modal renders here, above everything
            }
            .onAppear { scene.reportInsets(geo.safeAreaInsets) }
            .onChange(of: geo.safeAreaInsets) { scene.reportInsets($0) }
        }
        // Tap anywhere to dismiss the keyboard (like the UIKit host). Simultaneous so
        // it never swallows a button/tap; resigning the CURRENT first responder lets a
        // tap on another field still focus it (old resigns, new focuses after).
        .simultaneousGesture(TapGesture().onEnded {
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        })
        .statusBarHidden(scene.sbHidden)
        // Status bar content: an explicit StatusBar style wins (SwiftUI has no direct
        // bar-style API, so map it through the scheme: "light" content = dark scheme).
        // Otherwise, while following the OS don't force the scheme (nil) so osScheme
        // keeps reporting the real OS; once overridden, force it from the app bg.
        .preferredColorScheme(
            scene.sbStyle == "light" ? .dark :
            scene.sbStyle == "dark"  ? .light :
            (scene.followSystem ? nil : (isLightHex(appBg) ? .light : .dark)))
        .onAppear { scene.boot(osDark: osScheme == .dark) }        // launch: open in OS appearance
        .onChange(of: osScheme) { newScheme in                     // live OS change (while following)
            if scene.followSystem { scene.osChanged(dark: newScheme == .dark) }
        }
        // A visible Alert node presents the native OS alert; buttons dispatch "1"/"0".
        // The isPresented binding reads avis (state-driven); when a button dispatches and
        // Chuks sets visible=false, alertInfo goes nil and the alert dismisses.
        .alert(alertInfo?.title ?? "",
               isPresented: Binding(get: { alertInfo != nil }, set: { _ in }),
               presenting: alertInfo) { info in
            Button(info.confirm) { scene.input(info.id, "1") }
            if !info.cancel.isEmpty { Button(info.cancel, role: .cancel) { scene.input(info.id, "0") } }
        } message: { info in
            Text(info.message)
        }
    }

    // The overlay for a visible Modal: a dimmed scrim (tap dismisses) with the modal's
    // children positioned center (dialog) or bottom (sheet), on top of everything.
    @ViewBuilder var modalOverlay: some View {
        if let modalId = scene.nodes.first(where: { $0.value.kind == "Modal" && $0.value.style["mvis"] == "1" })?.key,
           let m = scene.nodes[modalId] {
            if m.style["mpos"] == "bottom" {
                BottomSheetView(scene: scene, modal: m)      // draggable sheet (drag down / tap out to dismiss)
            } else {
                ZStack(alignment: .center) {                 // centered dialog
                    Color.black.opacity(0.5).ignoresSafeArea()
                        .onTapGesture { if !m.action.isEmpty { scene.dispatch(m.action) } }
                    VStack(spacing: 0) {
                        ForEach(m.children, id: \.self) { cid in
                            NodeView(scene: scene, id: cid, parentRow: false, parentStretch: false)
                        }
                    }
                    .padding(24)
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
                    .onTapGesture { }                        // absorb taps so content never dismisses
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .ignoresSafeArea()
            }
        }
    }
}

// Rounds only the top two corners (UnevenRoundedRectangle is iOS 16+ only).
struct TopRoundedShape: Shape {
    var radius: CGFloat
    func path(in rect: CGRect) -> Path {
        Path(UIBezierPath(roundedRect: rect, byRoundingCorners: [.topLeft, .topRight],
                          cornerRadii: CGSize(width: radius, height: radius)).cgPath)
    }
}

// A draggable bottom sheet: rounded-top surface + grab handle over a dimmed scrim.
// Drag down past a threshold (or flick) to dismiss; otherwise it springs back.
// The host owns the gesture; dismissing dispatches the Modal's onDismiss action.
struct BottomSheetView: View {
    @ObservedObject var scene: Scene
    let modal: NodeData
    @State private var dragY: CGFloat = 0
    @State private var appeared = false      // false until the entrance slide-up runs

    private func dismiss() { if !modal.action.isEmpty { scene.dispatch(modal.action) } }

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.opacity((appeared ? 0.5 : 0) * Double(max(0, 1 - dragY / 500)))
                .ignoresSafeArea()
                .onTapGesture(perform: dismiss)
            VStack(spacing: 0) {
                Capsule().fill(Color.gray.opacity(0.4))
                    .frame(width: 40, height: 5)
                    .padding(.top, 8).padding(.bottom, 4)
                VStack(spacing: 0) {
                    ForEach(modal.children, id: \.self) { cid in
                        NodeView(scene: scene, id: cid, parentRow: false, parentStretch: true)
                    }
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.bottom, 34)                                  // clear the home indicator
            .background(Color(.secondarySystemBackground))
            .clipShape(TopRoundedShape(radius: 20))
            .contentShape(Rectangle())
            .offset(y: (appeared ? 0 : 1200) + max(0, dragY))     // start off-screen, slide up on appear
            .onAppear { withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) { appeared = true } }
            .gesture(
                DragGesture()
                    .onChanged { v in dragY = max(0, v.translation.height) }
                    .onEnded { v in
                        if v.translation.height > 120 || v.predictedEndTranslation.height > 320 {
                            dismiss()
                        } else {
                            withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { dragY = 0 }
                        }
                    }
            )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
    }
}

// The normal per-app host. The Chuks Preview build (-D CHUKS_PREVIEW) supplies its own
// @main in ChuksPreview.swift, which gates RootView behind a connect/scan screen.
#if !CHUKS_PREVIEW
@main
struct ChuksMobileApp: App {
    @UIApplicationDelegateAdaptor(ChuksOrientationDelegate.self) var appDelegate   // reports the orientation lock mask
    @StateObject var scene = Scene()
    var body: some SwiftUI.Scene {
        WindowGroup {
            RootView(scene: scene)
        }
    }
}
#endif
