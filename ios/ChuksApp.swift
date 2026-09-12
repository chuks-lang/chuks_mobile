// Chuks Mobile, Phase 2: a generic UIKit + Yoga host for a 100% Chuks app.
//
// The host creates NO app-specific widgets. It renders whatever tree Chuks emits
// (C/S/P/T/I/R stream) into two lockstep trees: real UIViews and a Yoga shadow
// tree. Each frame it runs Yoga and copies the computed rects onto the UIViews.
// The only node kinds it special-cases are structural: a `Scroll` node whose
// viewport it reports back to Chuks (virtualization), and an `Input` node whose
// text it reports back (TextInput). Everything else (the search bar, the list,
// the cards) is declared in engine.chuks. The app is written entirely in Chuks; this
// native layer only renders it, and the layout engine is the real Yoga.

import UIKit
import AVFoundation
import AVKit
import WebKit
import MapKit
import Photos
import PhotosUI
import UserNotifications
import CoreLocation
import CoreMotion
import os
import ImageIO

// Per WWDC "Image and Graphics Best Practices": decode a
// large source image downsampled to the target display size via ImageIO, instead
// of letting UIImageView hold a full-res bitmap. maxPixel is the longest side in
// PIXELS (points * screen scale).
// Resolve a bundled asset name to a file URL in the .app. The build scripts preserve
// each asset's path relative to assets/, so a name may be a subfolder path like
// "images/logo.png"; a direct bundlePath join finds those. Fall back to the flat
// resource lookup for a bare top-level name.
func bundledAssetURL(_ name: String) -> URL? {
    let direct = Bundle.main.bundlePath + "/" + name
    if FileManager.default.fileExists(atPath: direct) { return URL(fileURLWithPath: direct) }
    return Bundle.main.url(forResource: name, withExtension: nil)
}

func downsampledImage(data: Data, maxPixel: CGFloat) -> UIImage? {
    guard maxPixel > 0,
          let src = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary)
    else { return UIImage(data: data) }
    let opts: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceShouldCacheImmediately: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: maxPixel,
    ]
    guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return UIImage(data: data) }
    return UIImage(cgImage: cg)
}
func downsampledImage(path: String, maxPixel: CGFloat) -> UIImage? {
    guard let data = try? Data(contentsOf: URL(fileURLWithPath: path)) else { return nil }
    return downsampledImage(data: data, maxPixel: maxPixel)
}
import CoreHaptics
import Contacts
import EventKit
import LocalAuthentication
import Security
import Network
import CoreBluetooth
import CoreNFC
import BackgroundTasks

// ===== Feed-grade image cache (shared by both iOS hosts) =====
// Bounded in-memory LRU (NSCache, auto-evicts under pressure) + an on-disk URLCache
// (survives relaunch) + off-main-thread decode, so a fast-scrolling image feed neither
// re-downloads nor janks the main thread decoding. Replaces the earlier unbounded,
// uncached image dictionary.
final class ChuksImageLoader {
    static let shared = ChuksImageLoader()
    private let mem = NSCache<NSString, UIImage>()
    private let session: URLSession
    init() {
        mem.countLimit = 200                 // secondary bound
        mem.totalCostLimit = 128 << 20       // primary bound: 128MB of decoded pixels, byte-accurate
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 20
        c.requestCachePolicy = .returnCacheDataElseLoad
        c.urlCache = URLCache(memoryCapacity: 16 << 20, diskCapacity: 256 << 20, diskPath: "chuks-img")
        session = URLSession(configuration: c)
    }
    func cached(_ url: String) -> UIImage? { mem.object(forKey: url as NSString) }
    // done() is always called on the main thread with the decoded image.
    func load(_ urlStr: String, _ done: @escaping (UIImage) -> Void, fail: (() -> Void)? = nil) {
        if let img = mem.object(forKey: urlStr as NSString) { done(img); return }
        guard let url = URL(string: urlStr) else { DispatchQueue.main.async { fail?() }; return }
        // cap the decode near screen size so a huge remote image never
        // holds a full-res bitmap. A generous bound (screen's long side) keeps
        // full-bleed feed images crisp while shielding memory.
        let cap = max(UIScreen.main.bounds.width, UIScreen.main.bounds.height) * UIScreen.main.scale
        session.dataTask(with: url) { [weak self] data, _, _ in
            guard let data = data else { DispatchQueue.main.async { fail?() }; return }
            // Thread-safe off-main decode (UIGraphicsImageRenderer is UIKit and NOT thread-safe
            // off main — it corrupts UIKit state and crashes text drawing). ImageIO downsampling
            // and preparingForDisplay are both background-safe.
            guard let raw = downsampledImage(data: data, maxPixel: cap) else { DispatchQueue.main.async { fail?() }; return }
            let img = raw.preparingForDisplay() ?? raw
            let cost = Int(img.size.width * img.size.height * img.scale * img.scale) * 4  // ~bytes of the decoded bitmap
            self?.mem.setObject(img, forKey: urlStr as NSString, cost: cost)
            DispatchQueue.main.async { done(img) }
        }.resume()
    }
}

// ===== Bluetooth LE (CoreBluetooth) + NFC (CoreNFC): shared by both iOS hosts =====
// Decoupled from the host via onResolve/onFail closures (set to the host's resolve/fail).
// CoreBluetooth is driven on the main queue, so callbacks fire on main.
func bleHexToData(_ hex: String) -> Data {
    var d = Data(); var i = hex.startIndex
    while i < hex.endIndex {
        let j = hex.index(i, offsetBy: 2, limitedBy: hex.endIndex) ?? hex.endIndex
        if let b = UInt8(hex[i..<j], radix: 16) { d.append(b) }
        i = j
    }
    return d
}
func bleDataToHex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }

final class BleManager: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    var onResolve: ((String, String) -> Void)?
    var onFail: ((String, String) -> Void)?
    private var central: CBCentralManager!
    var scanToken: String?
    var stateToken: String?
    private var peripherals: [String: CBPeripheral] = [:]
    private var chars: [String: CBCharacteristic] = [:]          // "id|service|char" -> characteristic
    private var connectOk: [String: (String) -> Void] = [:]
    private var connectErr: [String: (String) -> Void] = [:]
    private var readOk: [String: (String) -> Void] = [:]
    private var readErr: [String: (String) -> Void] = [:]
    private var writeOk: [String: (String) -> Void] = [:]
    private var writeErr: [String: (String) -> Void] = [:]
    private var notifyTokens: [String: String] = [:]            // "id|s|c" -> stream token

    override init() { super.init(); central = CBCentralManager(delegate: self, queue: .main) }

    private func key(_ id: String, _ s: String, _ c: String) -> String { "\(id)|\(s.lowercased())|\(c.lowercased())" }

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        emitState()
        if c.state == .poweredOn, scanToken != nil { central.scanForPeripherals(withServices: nil, options: nil) }
    }
    func emitState() {
        guard let t = stateToken else { return }
        let s: String
        switch central.state {
        case .poweredOn: s = "on"
        case .poweredOff: s = "off"
        case .unauthorized: s = "unauthorized"
        case .unsupported: s = "unsupported"
        default: s = "off"
        }
        onResolve?(t, s)
    }
    func startScan(_ token: String) {
        scanToken = token
        if central.state == .poweredOn { central.scanForPeripherals(withServices: nil, options: nil) }
    }
    func stopScan() { central.stopScan(); scanToken = nil }
    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let id = p.identifier.uuidString
        peripherals[id] = p
        let name = p.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? ""
        if let t = scanToken { onResolve?(t, "\(id)\t\(name)\t\(RSSI.intValue)") }
    }
    func connect(_ id: String, ok: @escaping (String) -> Void, err: @escaping (String) -> Void) {
        guard let p = peripherals[id] else { err("unknown device (scan first)"); return }
        connectOk[id] = ok; connectErr[id] = err; p.delegate = self; central.connect(p, options: nil)
    }
    func disconnect(_ id: String) { if let p = peripherals[id] { central.cancelPeripheralConnection(p) } }
    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) { p.discoverServices(nil) }
    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        let id = p.identifier.uuidString
        connectErr[id]?(error?.localizedDescription ?? "connect failed")
        connectOk[id] = nil; connectErr[id] = nil
    }
    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        if let e = error { connectErr[p.identifier.uuidString]?(e.localizedDescription); return }
        let svcs = p.services ?? []
        if svcs.isEmpty { finishConnect(p.identifier.uuidString) }
        for s in svcs { p.discoverCharacteristics(nil, for: s) }
    }
    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let id = p.identifier.uuidString
        for ch in service.characteristics ?? [] { chars[key(id, service.uuid.uuidString, ch.uuid.uuidString)] = ch }
        finishConnect(id)
    }
    private func finishConnect(_ id: String) {
        guard let ok = connectOk[id] else { return }
        ok("connected"); connectOk[id] = nil; connectErr[id] = nil
    }
    func read(_ id: String, _ s: String, _ c: String, ok: @escaping (String) -> Void, err: @escaping (String) -> Void) {
        guard let p = peripherals[id], let ch = chars[key(id, s, c)] else { err("characteristic not found"); return }
        readOk[key(id, s, c)] = ok; readErr[key(id, s, c)] = err; p.readValue(for: ch)
    }
    func write(_ id: String, _ s: String, _ c: String, _ hex: String, ok: @escaping (String) -> Void, err: @escaping (String) -> Void) {
        guard let p = peripherals[id], let ch = chars[key(id, s, c)] else { err("characteristic not found"); return }
        writeOk[key(id, s, c)] = ok; writeErr[key(id, s, c)] = err
        p.writeValue(bleHexToData(hex), for: ch, type: .withResponse)
    }
    func subscribe(_ id: String, _ s: String, _ c: String, token: String, err: @escaping (String) -> Void) {
        guard let p = peripherals[id], let ch = chars[key(id, s, c)] else { err("characteristic not found"); return }
        notifyTokens[key(id, s, c)] = token; p.setNotifyValue(true, for: ch)
    }
    func unsubscribe(_ token: String) {
        for (k, t) in notifyTokens where t == token {
            notifyTokens[k] = nil
            let parts = k.split(separator: "|").map(String.init)
            if parts.count == 3, let p = peripherals[parts[0]], let ch = chars[k] { p.setNotifyValue(false, for: ch) }
        }
    }
    private func keyFor(_ ch: CBCharacteristic) -> String? { chars.first(where: { $0.value === ch })?.key }
    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        guard let k = keyFor(ch) else { return }
        if let e = error { readErr[k]?(e.localizedDescription); readErr[k] = nil; readOk[k] = nil; return }
        let hex = ch.value.map { bleDataToHex($0) } ?? ""
        if let ok = readOk[k] { ok(hex); readOk[k] = nil; readErr[k] = nil }
        if let t = notifyTokens[k] { onResolve?(t, hex) }
    }
    func peripheral(_ p: CBPeripheral, didWriteValueFor ch: CBCharacteristic, error: Error?) {
        guard let k = keyFor(ch) else { return }
        if let e = error { writeErr[k]?(e.localizedDescription) } else { writeOk[k]?("ok") }
        writeOk[k] = nil; writeErr[k] = nil
    }
}

// NFC (CoreNFC, NDEF). Needs NFCReaderUsageDescription + the CoreNFC reader entitlement in the
// app's provisioning profile; without the entitlement, begin() fails / readingAvailable is false.
final class NfcReader: NSObject, NFCNDEFReaderSessionDelegate {
    var onResolve: ((String, String) -> Void)?
    var onFail: ((String, String) -> Void)?
    private var session: NFCNDEFReaderSession?
    private var token = ""
    private var writeText: String?
    private var didComplete = false

    func read(_ token: String) { begin(token, write: nil) }
    func write(_ text: String, _ token: String) { begin(token, write: text) }
    var available: Bool { NFCNDEFReaderSession.readingAvailable }

    /// Why `readingAvailable` is false, which is not the question it looks like.
    ///
    /// iOS reports NFC as unavailable when the app is not signed with
    /// com.apple.developer.nfc.readersession.formats, exactly as it does on a device
    /// with no NFC reader at all. So an entitled build on an iPhone 12 Pro and an
    /// unentitled one give the same answer, and "NFC not available" sends the reader
    /// looking at the hardware when the problem is the signature.
    ///
    /// The build writes what it was actually signed for into Info.plist, so this can
    /// say which of the two it is instead of guessing.
    static func unavailableReason() -> String {
        #if targetEnvironment(simulator)
        return "NFC is unavailable: no simulator has an NFC reader. Test this on a device."
        #else
        let granted = Bundle.main.object(forInfoDictionaryKey: "ChuksGrantedEntitlements") as? [String] ?? []
        if granted.contains("com.apple.developer.nfc.readersession.formats") {
            return "NFC is unavailable on this device."
        }
        return "NFC is unavailable because this build is not signed for it. iOS reports "
            + "no NFC reader unless the app carries com.apple.developer.nfc.readersession.formats, "
            + "even on a device that has one. Declare \"nfc\" under permissions in app.json and "
            + "sign with a profile from an explicit App ID that has NFC Tag Reading enabled; a "
            + "wildcard development profile cannot carry that entitlement."
        #endif
    }

    private func begin(_ token: String, write: String?) {
        guard NFCNDEFReaderSession.readingAvailable else { onFail?(token, NfcReader.unavailableReason()); return }
        self.token = token; self.writeText = write; self.didComplete = false
        session = NFCNDEFReaderSession(delegate: self, queue: nil, invalidateAfterFirstRead: false)
        session?.alertMessage = write == nil ? "Hold your iPhone near a tag." : "Hold your iPhone near a tag to write."
        session?.begin()
    }
    func readerSession(_ s: NFCNDEFReaderSession, didDetectNDEFs messages: [NFCNDEFMessage]) { /* using didDetect(tags:) */ }
    func readerSession(_ s: NFCNDEFReaderSession, didDetect tags: [NFCNDEFTag]) {
        guard let tag = tags.first else { return }
        s.connect(to: tag) { [weak self] err in
            guard let self = self else { return }
            if let e = err { self.finish(nil, e.localizedDescription, s); return }
            if let text = self.writeText {
                guard let pl = NFCNDEFPayload.wellKnownTypeTextPayload(string: text, locale: Locale(identifier: "en")) else {
                    self.finish(nil, "encode failed", s); return
                }
                tag.writeNDEF(NFCNDEFMessage(records: [pl])) { werr in
                    if let we = werr { self.finish(nil, we.localizedDescription, s) } else { self.finish("ok", nil, s) }
                }
            } else {
                tag.readNDEF { msg, rerr in
                    if let re = rerr { self.finish(nil, re.localizedDescription, s); return }
                    let text = msg?.records.compactMap { self.textFrom($0) }.first ?? ""
                    self.finish(text, nil, s)
                }
            }
        }
    }
    private func textFrom(_ r: NFCNDEFPayload) -> String? {
        let (str, _) = r.wellKnownTypeTextPayload()
        if let str = str { return str }
        return String(data: r.payload, encoding: .utf8)
    }
    private func finish(_ ok: String?, _ err: String?, _ s: NFCNDEFReaderSession) {
        if didComplete { return }
        didComplete = true
        if let ok = ok { onResolve?(token, ok); s.alertMessage = "Done"; s.invalidate() }
        else { onFail?(token, err ?? "failed"); s.invalidate(errorMessage: err ?? "failed") }
    }
    func readerSession(_ s: NFCNDEFReaderSession, didInvalidateWithError error: Error) {
        if didComplete { return }
        didComplete = true
        // A missing entitlement is the one failure whose system message does not say
        // what to do about it: CoreNFC reports a security violation, which reads like
        // a tag problem rather than a signing one. NFC needs
        // com.apple.developer.nfc.readersession.formats, and that entitlement needs an
        // explicit App ID, so a build on the usual wildcard profile lands here.
        if let e = error as? NFCReaderError, e.code == .readerErrorSecurityViolation {
            onFail?(token, "NFC is not entitled for this build. Declare \"nfc\" under permissions in app.json and sign with a profile from an explicit App ID that has NFC Tag Reading enabled; a wildcard development profile cannot carry the entitlement.")
            return
        }
        onFail?(token, error.localizedDescription)
    }
}

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

// A UILabel that can be selected/copied (Text `selectable`). It becomes first responder on a
// long-press and shows the system Copy menu; with `selectable` off it's an ordinary label.
final class SelectableLabel: UILabel {
    var selectable = false
    override var canBecomeFirstResponder: Bool { selectable }
    func enableSelection() {
        guard gestureRecognizers?.isEmpty ?? true else { return }
        isUserInteractionEnabled = true
        addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(showCopy(_:))))
    }
    @objc private func showCopy(_ g: UILongPressGestureRecognizer) {
        guard selectable, g.state == .began, becomeFirstResponder() else { return }
        let menu = UIMenuController.shared
        menu.showMenu(from: self, rect: bounds)
    }
    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool { action == #selector(copy(_:)) }
    override func copy(_ sender: Any?) { UIPasteboard.general.string = text; UIMenuController.shared.hideMenu() }
}

// A view that also accepts touches within `hitSlop` px beyond its bounds (Pressable `hitSlop`).
final class HitSlopView: UIView {
    var hitSlop: CGFloat = 0
    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        if hitSlop <= 0 { return super.point(inside: point, with: event) }
        return bounds.insetBy(dx: -hitSlop, dy: -hitSlop).contains(point)
    }
}

// A UIView whose backing layer IS the camera preview layer, so it tracks the view frame.
final class CameraPreviewUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    weak var controller: CameraController?
    func attach(_ session: AVCaptureSession) {
        previewLayer.session = session
        previewLayer.videoGravity = .resizeAspectFill
    }
}

// Owns the AVCaptureSession + lens input + photo output for the CameraView node. Retained
// by CardsVC while a CameraView is mounted so camera.capturePreview can snap a still.
final class CameraController: NSObject, AVCapturePhotoCaptureDelegate {
    let session = AVCaptureSession()
    private let photoOut = AVCapturePhotoOutput()
    let previewView = CameraPreviewUIView()
    private var facing = ""
    private let q = DispatchQueue(label: "chuks.camera.session")
    private var onCapOk: ((String) -> Void)?
    private var onCapErr: ((String) -> Void)?

    override init() { super.init(); previewView.controller = self; previewView.attach(session) }

    func configure(_ want: String) {
        let facingNow = want == "front" ? "front" : "back"
        guard facingNow != facing || session.inputs.isEmpty else { return }
        facing = facingNow
        q.async { [weak self] in
            guard let self = self else { return }
            self.session.beginConfiguration()
            self.session.sessionPreset = .photo
            for i in self.session.inputs { self.session.removeInput(i) }
            let pos: AVCaptureDevice.Position = facingNow == "front" ? .front : .back
            if let dev = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: pos),
               let input = try? AVCaptureDeviceInput(device: dev), self.session.canAddInput(input) {
                self.session.addInput(input)
            }
            if self.session.outputs.isEmpty, self.session.canAddOutput(self.photoOut) {
                self.session.addOutput(self.photoOut)
            }
            self.session.commitConfiguration()
            if !self.session.isRunning { self.session.startRunning() }
        }
    }

    func stop() { q.async { [weak self] in if self?.session.isRunning == true { self?.session.stopRunning() } } }

    func capture(ok: @escaping (String) -> Void, err: @escaping (String) -> Void) {
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else { err("camera permission not granted"); return }
        onCapOk = ok; onCapErr = err
        q.async { [weak self] in
            guard let self = self, self.session.isRunning else { DispatchQueue.main.async { err("camera not running") }; return }
            self.photoOut.capturePhoto(with: AVCapturePhotoSettings(), delegate: self)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let ok = onCapOk, err = onCapErr; onCapOk = nil; onCapErr = nil
        if let e = error { DispatchQueue.main.async { err?(e.localizedDescription) }; return }
        guard let data = photo.fileDataRepresentation() else { DispatchQueue.main.async { err?("no image data") }; return }
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let url = dir.appendingPathComponent("cam_\(UUID().uuidString).jpg")
        do { try data.write(to: url); DispatchQueue.main.async { ok?("file://" + url.path) } }
        catch { DispatchQueue.main.async { err?(error.localizedDescription) } }
    }
}

// The normal per-app UIKit host. The Chuks Preview build (-D CHUKS_PREVIEW) supplies its
// own @main in ChuksPreview.swift, gating CardsVC behind a connect/scan screen.
#if !CHUKS_PREVIEW
// Cold start: process launch -> the first frame the user could see. Measured the same
// way as the RN and Flutter benchmark apps (process start time from the kernel, first
// display-link callback after launch), so the three numbers are comparable. Benchmark
// builds only.
final class ColdStartProbe {
    static let shared = ColdStartProbe()
    private var link: CADisplayLink?
    func begin() {
        let l = CADisplayLink(target: self, selector: #selector(tick))
        l.add(to: .main, forMode: .common)
        link = l
    }
    private var firstFrameMs: Double = -1
    // Process start, from the kernel.
    private func sinceLaunchMs() -> Double {
        var kp = kinfo_proc(); var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        sysctl(&mib, 4, &kp, &size, nil, 0)
        let st = kp.kp_proc.p_un.__p_starttime
        let start = Double(st.tv_sec) + Double(st.tv_usec) / 1_000_000.0
        var tv = timeval(); gettimeofday(&tv, nil)
        return ((Double(tv.tv_sec) + Double(tv.tv_usec) / 1_000_000.0) - start) * 1000.0
    }
    // A list that has actually rendered: a scroll view whose content is taller than
    // itself. "First frame" alone is not comparable across frameworks, because a
    // framework that renders asynchronously shows an EMPTY first frame and would look
    // faster while the user is still staring at nothing.
    private func contentReady(_ v: UIView?) -> Bool {
        guard let v = v else { return false }
        if let sv = v as? UIScrollView, sv.contentSize.height > sv.bounds.height, sv.bounds.height > 0 { return true }
        for sub in v.subviews where contentReady(sub) { return true }
        return false
    }
    @objc private func tick() {
        if firstFrameMs < 0 { firstFrameMs = sinceLaunchMs() }
        guard contentReady(UIApplication.shared.windows.first) else { return }   // keep waiting
        link?.invalidate(); link = nil
        let msg = String(format: "BENCHMARK CHUKS COLDSTART: first frame %.0f ms | CONTENT %.0f ms | mem %.0f MB",
                         firstFrameMs, sinceLaunchMs(), physFootprintMB())
        print(msg); NSLog(msg)
        if let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            try? (msg + "\n").write(to: dir.appendingPathComponent("coldstart.txt"),
                                    atomically: true, encoding: .utf8)
        }
    }
}

// ---- println reaches the log ----------------------------------------------
//
// The engine's println goes to file descriptor 1. On a device that descriptor leads
// nowhere a developer can see: not the unified log, not idevicesyslog, not Console. An
// app can print all day and see nothing, which is worse than having no print statement
// at all, because it reads as code that never ran.
//
// So take the descriptor over. A pipe replaces stdout and stderr and each line is
// re-emitted through NSLog, which does reach the unified log. This works at the
// descriptor rather than at any one engine, so it catches the AOT binary, the CMR VM,
// and anything a native package prints.
private let chuksLog = OSLog(subsystem: "org.chuks.mobile", category: "Chuks")
private var chuksStdioPipe: Pipe?
private var chuksStdioTail = ""
func chuksPipeStdioToLog() {
    if chuksStdioPipe != nil { return }
    let p = Pipe()
    chuksStdioPipe = p
    setvbuf(stdout, nil, _IOLBF, 0)          // line buffered, so a print appears when written
    setvbuf(stderr, nil, _IONBF, 0)
    let w = p.fileHandleForWriting.fileDescriptor
    dup2(w, STDOUT_FILENO)
    dup2(w, STDERR_FILENO)                    // so a runtime panic is not lost either
    // Whole lines only: a read can split mid-line, and half a message logged twice is
    // harder to read than the one it came from.
    p.fileHandleForReading.readabilityHandler = { h in
        let d = h.availableData
        if d.isEmpty { return }
        guard let chunk = String(data: d, encoding: .utf8) else { return }
        chuksStdioTail += chunk
        while let nl = chuksStdioTail.firstIndex(of: "\n") {
            let line = String(chuksStdioTail[chuksStdioTail.startIndex..<nl])
            chuksStdioTail = String(chuksStdioTail[chuksStdioTail.index(after: nl)...])
            // os_log, NOT NSLog. NSLog can write to stderr, and stderr is one of the
            // two descriptors redirected into this very pipe: the first print would
            // have fed itself back in and looped for ever.
            if !line.isEmpty { os_log("%{public}@", log: chuksLog, type: .info, line) }
        }
    }
}

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ a: UIApplication, didFinishLaunchingWithOptions o: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        chuksPipeStdioToLog()
        // Before anything else. The notification delegate has to be in place before this
        // method returns, or the tap that LAUNCHED the app is never delivered at all:
        // iOS hands the launch response only to a delegate that already exists. It used
        // to be set in viewDidLoad, which is too late for exactly that case.
        UNUserNotificationCenter.current().delegate = notifDelegate
        let w = UIWindow(frame: UIScreen.main.bounds)
        let vc = CardsVC()
        if let url = o?[.url] as? URL { vc.lastURL = url.absoluteString }   // deep link that launched the app
        w.rootViewController = vc
        w.makeKeyAndVisible()
        window = w
        // Background task handlers MUST be registered before this method returns, and
        // only for identifiers the plist permits. makeKeyAndVisible above has already
        // loaded the view and mounted the engine, so by the time a handler fires the
        // app's module scope has run and its Background.define calls have handed us
        // their tokens. That is what makes a COLD background wake work: there is no
        // separate context to boot and no entry point to look up by name.
        for id in CardsVC.permittedTaskIds {
            BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: nil) { task in
                vc.runBackgroundTask(id, task)
            }
        }
        if BENCHMARK_MODE { ColdStartProbe.shared.begin() }
        return true
    }
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        chuksOrientationMask   // Orientation.lockTo() drives this
    }
    // Save on the way out rather than on termination: iOS makes no promise to run
    // anything when it kills a suspended app, so this is the last reliable moment.
    func applicationDidEnterBackground(_ application: UIApplication) {
        (window?.rootViewController as? CardsVC)?.persistState()
    }
    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        (window?.rootViewController as? CardsVC)?.receiveURL(url.absoluteString)   // subsequent deep link
        return true
    }
}
#endif

// Yoga measure callback: text nodes self-size by measuring their label's text.
let measureText: YGMeasureFunc = { node, width, widthMode, _, _ in
    guard let node = node, let ctx = YGNodeGetContext(node) else { return YGSize(width: 0, height: 0) }
    let label = Unmanaged<UILabel>.fromOpaque(ctx).takeUnretainedValue()
    let maxW = (widthMode == YGMeasureMode.undefined || width.isNaN) ? CGFloat.greatestFiniteMagnitude : CGFloat(width)
    // Measure via the label itself (sizeThatFits), NOT NSString.boundingRect: boundingRect
    // is unreliable for multi-line wrapping (it can stop after one line even when given a
    // bounded width), whereas sizeThatFits honors the label's numberOfLines/lineBreakMode/
    // attributedText and wraps to the given width. This is what makes Text wrap to its
    // container width without an explicit `w` (paired with the Yoga errata config).
    let sz = label.sizeThatFits(CGSize(width: maxW, height: .greatestFiniteMagnitude))
    if ProcessInfo.processInfo.environment["CHUKS_MEASURE_LOG"] != nil {
        NSLog("chuks-measure text=%@ mode=%d maxW=%.1f -> %.1fx%.1f", String((label.attributedText?.string ?? label.text ?? "").prefix(12)), widthMode.rawValue, maxW, sz.width, sz.height)
    }
    return YGSize(width: Float(ceil(sz.width)), height: Float(ceil(sz.height)))
}

// Tailwind font-weight name -> UIFont.Weight (thin..black).
func weightOf(_ s: String) -> UIFont.Weight {
    switch s {
    case "thin": return .thin
    case "extralight": return .ultraLight
    case "light": return .light
    case "normal", "regular": return .regular
    case "medium": return .medium
    case "semibold": return .semibold
    case "bold": return .bold
    case "extrabold": return .heavy
    case "black": return .black
    default: return .regular
    }
}

// hex "5B8CFF" -> UIColor
/// A view whose backing layer is the gradient itself, so it resizes with the view and
/// needs no manual frame bookkeeping. It goes in as a SUBVIEW (like the glass material)
/// rather than as a bare sublayer: a sublayer added to a container draws above the
/// container's subviews on this host, which hid every child behind the gradient.
/// Tag on every decorative backing view (an ImageBackground's image, the glass material,
/// a gradient). They are inserted at the BACK of a container, before its children, so the
/// child-insert index has to skip them; counting them by tag means a new decoration works
/// without touching the insert path again.
let CHUKS_DECOR_TAG = 0x43484B44

final class ChuksGradientView: UIView {
    override class var layerClass: AnyClass { CAGradientLayer.self }
    var grad: CAGradientLayer { layer as! CAGradientLayer }
}

func hexColor(_ h: String) -> UIColor {
    var v: UInt64 = 0
    // Style values carry a bare hex ("2F7A4F"), but an app may write the colour the way a
    // person writes one. Scanner stops at the '#' and leaves 0, so a hash-prefixed colour
    // used to come out black rather than wrong-looking; on Android the same value crashed
    // the process. Both accept both now.
    let t = h.hasPrefix("#") ? String(h.dropFirst()) : h
    Scanner(string: t).scanHexInt64(&v)
    return UIColor(red: CGFloat((v >> 16) & 0xff) / 255, green: CGFloat((v >> 8) & 0xff) / 255,
                   blue: CGFloat(v & 0xff) / 255, alpha: 1)
}

// The battery reading, in one place. watch() and current() answer the same payload
// because they call the same function; two copies of this would drift the first time
// one of them learned about a new battery state.
func chuksBatteryNow() -> String {
    UIDevice.current.isBatteryMonitoringEnabled = true
    let d = UIDevice.current
    let lvl = d.batteryLevel < 0 ? -1 : Int((d.batteryLevel * 100).rounded())
    let chg = (d.batteryState == .charging || d.batteryState == .full) ? 1 : 0
    // The state UIKit already reports, no longer collapsed into the 1/0 above. iOS
    // has no "plugged in but not charging" state of its own; Android does.
    let state: String
    switch d.batteryState {
    case .unplugged: state = "unplugged"
    case .charging:  state = "charging"
    case .full:      state = "full"
    default:         state = "unknown"
    }
    let low = ProcessInfo.processInfo.isLowPowerModeEnabled ? 1 : 0
    return "\(lvl),\(chg),\(state),\(low)"
}

// The transport a network path is on, for watch() and current() alike.
func chuksPathString(_ path: NWPath) -> String {
    // "transport,reachable". Reachable is whether the path is satisfied, which is
    // the most iOS offers without sending a probe; Android's half validates the
    // network for real. A tunnel shows up as an interface of type .other whose name
    // is utun/ipsec/ppp, so that is how a VPN is told apart from "other".
    let reachable = path.status == .satisfied ? 1 : 0
    if path.status != .satisfied { return "none,0" }
    if path.usesInterfaceType(.wifi) { return "wifi,\(reachable)" }
    if path.usesInterfaceType(.cellular) { return "cellular,\(reachable)" }
    if path.usesInterfaceType(.wiredEthernet) { return "ethernet,\(reachable)" }
    let tunnel = path.availableInterfaces.contains { i in
        i.name.hasPrefix("utun") || i.name.hasPrefix("ipsec") || i.name.hasPrefix("ppp")
    }
    if tunnel { return "vpn,\(reachable)" }
    return "other,\(reachable)"
}

// Whether ONE named motion sensor exists here. Asked by Motion.available() before a
// screen commits to showing a readout it may never be able to fill.
func chuksSensorAvailable(_ name: String, _ motion: CMMotionManager) -> Bool {
    switch name {
    case "accelerometer": return motion.isAccelerometerAvailable
    case "gyroscope":     return motion.isGyroAvailable
    case "magnetometer":  return motion.isMagnetometerAvailable
    case "barometer":     return CMAltimeter.isRelativeAltitudeAvailable()
    // There is no query for the proximity sensor. Enabling monitoring and reading the
    // flag back is the documented way: it stays false on a device without one.
    case "proximity":
        UIDevice.current.isProximityMonitoringEnabled = true
        let has = UIDevice.current.isProximityMonitoringEnabled
        UIDevice.current.isProximityMonitoringEnabled = false
        return has
    // iOS exposes no public ambient-light API, which is why motion.light fails here.
    case "light": return false
    default: return false     // an unknown name is "no", not a crash
    }
}

// Orientation capability helpers: the current interface orientation as a string, and a
// lock through the mask the AppDelegate reports to UIKit.
var chuksOrientationMask: UIInterfaceOrientationMask = .all
// "coarse,edge", where edge is where the TOP OF THE DEVICE points. UIKit's interface
// orientations name the landscapes by where the home button is, which is the opposite
// edge, so .landscapeLeft (home button on the left) has the top pointing RIGHT. The
// Chuks word is defined by the device so the two platforms can agree on it.
func currentOrientationString() -> String {
    let io = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first?.interfaceOrientation
    switch io {
    case .portraitUpsideDown: return "portrait,down"
    case .landscapeLeft:      return "landscape,right"
    case .landscapeRight:     return "landscape,left"
    default:                  return "portrait,up"
    }
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

// CMR mode (built with -D CMR against libcmr): the Chuks VM runs in-process and
// interprets a source bundle (packed by chukspack), instead of the AOT-compiled
// app. libcmr exposes the same chuks_* C ABI, so the only addition is loading the
// baked cmr.bundle at startup via chuks_cmr_boot.
#if CMR
func cmrBootBundle() {
    guard let u = Bundle.main.url(forResource: "cmr", withExtension: "bundle"),
          let data = try? Data(contentsOf: u) else {
        NSLog("CMR: cmr.bundle missing from app"); return
    }
    let rc = data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Int32 in
        chuks_cmr_boot(UnsafeMutablePointer(mutating: raw.bindMemory(to: CChar.self).baseAddress), Int32(data.count))
    }
    NSLog("CMR boot rc=%d (%d bytes)", rc, data.count)
    cmrBootErrorPending = rc != 0 ? cmrLastErrorText() : ""   // shown by the VC after its first mount
}
// The reason the last boot/reload failed, from the VM (type error, parse error,
// runtime panic). Fetched only on the error path; the buffer is freed after read.
func cmrLastErrorText() -> String {
    guard let c = chuks_cmr_last_error() else { return "" }
    let s = String(cString: c); chuks_free_str(c); return s
}
var cmrBootErrorPending = ""   // set at initial boot, consumed once the view exists
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
func cnAuthStr(_ s: CNAuthorizationStatus) -> String {
    if #available(iOS 18.0, *), s == .limited { return "granted" }
    switch s { case .authorized: return "granted"; case .denied: return "denied"; case .restricted: return "restricted"; default: return "undetermined" }
}
func ekAuthStr(_ s: EKAuthorizationStatus) -> String {
    switch s {
    case .authorized: return "granted"
    case .denied: return "denied"
    case .restricted: return "restricted"
    default:
        if #available(iOS 17.0, *) { if s == .fullAccess || s == .writeOnly { return "granted" } }
        return "undetermined"
    }
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
    init(once: Bool, background: Bool = false, onFix: @escaping (String) -> Void, onErr: @escaping (String) -> Void) {
        self.once = once; self.onFix = onFix; self.onErr = onErr
        super.init(); mgr.delegate = self
        if background {
            // Keep delivering with the screen off. Two things are required and one is a
            // deliberate choice:
            //
            // allowsBackgroundLocationUpdates needs "location" in UIBackgroundModes, and
            // iOS CRASHES the app at this line if it is missing rather than returning an
            // error, so the build writes it from app.json.
            //
            // showsBackgroundLocationIndicator puts the blue bar in the status bar. It is
            // on deliberately: tracking someone with nothing on screen to say so is what
            // both platforms are built to prevent, and the bar is also the user's way back
            // into the app.
            //
            // WHEN IN USE is enough for this, and is what we ask for. "Always" exists for
            // geofencing while the app is not running, costs a second and more alarming
            // prompt, and invites App Store review questions we would have no answer to.
            mgr.allowsBackgroundLocationUpdates = true
            mgr.showsBackgroundLocationIndicator = true
        }
        // Continuous route tracking (a walk, a run, a ride) needs more than the default
        // setup: the navigation-grade accuracy class, an activity type so Core Location
        // tunes its filtering for a person on foot, and auto-pause OFF (iOS otherwise
        // stops updates when it decides you have stopped, silently losing the middle of
        // a walk). `once` callers get a single fix, so they keep the cheaper class.
        mgr.desiredAccuracy = once ? kCLLocationAccuracyBest : kCLLocationAccuracyBestForNavigation
        if !once {
            mgr.activityType = .fitness
            mgr.pausesLocationUpdatesAutomatically = false
        }
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
        // Deliver EVERY fix in the batch. Core Location coalesces updates, so keeping
        // only `locs.last` threw away route points (and the distance between them).
        // Two kinds of fix are dropped rather than forwarded:
        //   - horizontalAccuracy < 0 marks an invalid fix, and its coordinate is meaningless;
        //   - a fix older than 5s is a CACHED one. The first callback after start is
        //     usually cached, so forwarding it draws a phantom leg from wherever the
        //     phone last had a fix, which can be streets (or cities) away.
        let now = Date()
        var delivered = false
        for l in locs {
            if l.horizontalAccuracy < 0 { continue }
            if now.timeIntervalSince(l.timestamp) > 5 { continue }
            let c = l.coordinate
            onFix("\(c.latitude),\(c.longitude),\(l.horizontalAccuracy),\(l.altitude),\(l.speed),\(l.course)")
            delivered = true
        }
        if once && delivered { m.stopUpdatingLocation() }
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

// ── Audio player ─────────────────────────────────────────────────────────────
// One AVPlayer per Chuks AudioPlayer, observed the way expo-audio observes it:
// KVO on the item's status for ready/failed, a periodic time observer for the
// playhead (never a poll), the did-play-to-end notification for finish and loop,
// and the buffer-empty / likely-to-keep-up pair for buffering. Every change is
// pushed through `emit` as the one status string the Chuks side documents.
final class ChuksAudioPlayer {
    let id: String
    let player: AVPlayer
    var emit: ((String) -> Void)?
    var loop = false
    var rate: Float = 1.0
    var volume: Float = 1.0
    var state = "loading"
    var error = ""
    var buffering = false
    var wantPlay = false                  // play() arrived before the item was ready
    var wasPlayingBeforeInterruption = false
    private var timeObs: Any?
    private var kvo: [NSKeyValueObservation] = []
    private var endObs: NSObjectProtocol?

    init(id: String, url: URL) {
        self.id = id
        let item = AVPlayerItem(url: url)
        item.audioTimePitchAlgorithm = .timeDomain     // rate changes keep the pitch
        player = AVPlayer(playerItem: item)
        player.actionAtItemEnd = .pause
        kvo.append(item.observe(\.status, options: [.new]) { [weak self] it, _ in
            guard let self = self else { return }
            switch it.status {
            case .readyToPlay:
                if self.state == "loading" { self.state = "ready" }
                if self.wantPlay { self.wantPlay = false; self.start() }
                self.push()
            case .failed:
                self.state = "error"
                self.error = it.error?.localizedDescription ?? "cannot play this source"
                self.push()
            default: break
            }
        })
        kvo.append(item.observe(\.isPlaybackBufferEmpty, options: [.new]) { [weak self] it, _ in
            if it.isPlaybackBufferEmpty { self?.buffering = true; self?.push() }
        })
        kvo.append(item.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { [weak self] it, _ in
            if it.isPlaybackLikelyToKeepUp { self?.buffering = false; self?.push() }
        })
        endObs = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            guard let self = self else { return }
            if self.loop {
                self.player.seek(to: .zero) { _ in self.start() }
            } else {
                self.state = "ended"; self.push()
            }
        }
        // A quarter-second cadence while playing is enough for any progress bar and
        // is the cadence the platform delivers, so nothing here wakes on a timer.
        timeObs = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 4), queue: .main) { [weak self] _ in
            guard let self = self, self.state == "playing" else { return }
            self.push()
        }
    }

    private func start() {
        player.playImmediately(atRate: rate)
        state = "playing"
        push()
    }
    func play() {
        if player.currentItem?.status == .readyToPlay { start() } else { wantPlay = true }
    }
    func pause() { wantPlay = false; player.pause(); if state == "playing" { state = "paused"; push() } }
    func stop() { pause(); player.seek(to: .zero); push() }
    func seek(ms: Int64) {
        player.seek(to: CMTime(value: ms, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in self?.push() }
    }
    func setVolume(_ v: Float) { volume = max(0, min(1, v)); player.volume = volume; push() }
    func setRate(_ r: Float) {
        rate = max(0.25, min(4, r))
        if state == "playing" { player.rate = rate }
        push()
    }

    func status() -> String {
        let pos = CMTimeGetSeconds(player.currentTime())
        let dur = player.currentItem?.duration.seconds ?? 0
        let posMs = pos.isFinite ? Int(pos * 1000) : 0
        let durMs = dur.isFinite ? Int(dur * 1000) : 0
        return "\(state),\(posMs),\(durMs),\(rate),\(volume),\(buffering ? 1 : 0),\(error)"
    }
    func push() { emit?(status()) }

    func release() {
        player.pause()
        if let t = timeObs { player.removeTimeObserver(t) }
        timeObs = nil
        kvo.forEach { $0.invalidate() }; kvo = []
        if let e = endObs { NotificationCenter.default.removeObserver(e) }
        endObs = nil
        emit = nil
        player.replaceCurrentItem(with: nil)
    }
}

// Show notifications even while the app is foregrounded (iOS otherwise suppresses
// the banner for the active app), and receive the tap.
//
// The tap that LAUNCHED the app arrives here before any Chuks code has run, let alone
// subscribed. It is held in `pending` and handed to the first onResponse subscriber,
// so a screen that subscribes on mount does not miss the reason it was opened. Same
// shape as the launch URL in Linking.onURL.
final class NotifDelegate: NSObject, UNUserNotificationCenterDelegate {
    var onResponse: ((String) -> Void)?
    var pending: String?
    func userNotificationCenter(_ c: UNUserNotificationCenter, willPresent n: UNNotification,
                                withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        // .list is what keeps a foreground-delivered notification in Notification
        // Center after its banner goes. Without it the banner was the only trace, and
        // a user who looked away for five seconds had nothing to tap.
        done([.banner, .list, .sound])
    }
    func userNotificationCenter(_ c: UNUserNotificationCenter, didReceive r: UNNotificationResponse,
                                withCompletionHandler done: @escaping () -> Void) {
        let id = r.notification.request.identifier
        let data = r.notification.request.content.userInfo["data"] as? String ?? ""
        let payload = id + "\t" + data
        if let cb = onResponse { cb(payload) } else { pending = payload }
        done()
    }
}
let notifDelegate = NotifDelegate()

// A pending request's next fire time as epoch milliseconds, whichever trigger it has.
func chuksNotifFireAt(_ req: UNNotificationRequest) -> Int64 {
    var date: Date? = nil
    if let t = req.trigger as? UNTimeIntervalNotificationTrigger { date = t.nextTriggerDate() }
    else if let t = req.trigger as? UNCalendarNotificationTrigger { date = t.nextTriggerDate() }
    return Int64((date?.timeIntervalSince1970 ?? 0) * 1000)
}

// ── Host wake ────────────────────────────────────────────────────────────────
// The engine calls this (from a background goroutine, via the chuks_set_wake C
// callback) when a spawned Chuks task has posted work to the render thread. It is
// @convention(c): no captures, so it reaches the live controller through a file
// global and hops to the main thread. Coalesced: a burst of messages schedules at
// most one pending main-thread pump.
// Text arriving on the P|/V| channels, restored.
//
// The engine escapes a backslash and the two line endings before putting text into a
// newline-delimited stream, because a newline inside a label used to end the op early
// and leave the rest standing as a line the host would then RUN. See escText in
// core/ui.chuks. One left-to-right scan, because search-and-replace would corrupt a
// label ending in a real backslash.
func chuksUnescapeText(_ s: String) -> String {
    if !s.contains("\\") { return s }
    var out = ""
    var i = s.startIndex
    while i < s.endIndex {
        let c = s[i]
        if c == "\\" {
            let n = s.index(after: i)
            if n < s.endIndex {
                switch s[n] {
                case "\\": out.append("\\")
                case "n": out.append("\n")
                case "r": out.append("\r")
                default: out.append(s[n])
                }
                i = s.index(after: n)
                continue
            }
        }
        out.append(c)
        i = s.index(after: i)
    }
    return out
}

private weak var gChuksWakeVC: CardsVC?
private let gWakeLock = NSLock()
private var gWakeScheduled = false

func chuksWakeThunk() {
    gWakeLock.lock()
    if gWakeScheduled { gWakeLock.unlock(); return }
    gWakeScheduled = true
    gWakeLock.unlock()
    DispatchQueue.main.async {
        gWakeLock.lock(); gWakeScheduled = false; gWakeLock.unlock()
        gChuksWakeVC?.pumpWake()
    }
}

final class CardsVC: UIViewController, UIScrollViewDelegate, UITextFieldDelegate, UITextViewDelegate, UIGestureRecognizerDelegate, UIContextMenuInteractionDelegate, MKMapViewDelegate, ChuksModuleHost, ChuksViewHost {
    let N: Int32 = 1000

    // The two lockstep trees, keyed by Chuks node id.
    var views: [String: UIView] = [:]
    var ynodes: [String: YGNodeRef] = [:]
    // Incremental apply via Yoga's HasNewLayout: relayout() writes a view's frame only when
    // Yoga recomputed its layout, so an incremental update touches a handful of views instead
    // of the whole tree. `needsFrame` is a belt-and-suspenders set of just-created views that
    // must get their frame at least once even if Yoga's flag says unchanged.
    var needsFrame = Set<String>()
    /// Capabilities that installed packages provide, consulted for any command the
    /// framework's own switch does not claim. Built lazily, so an app with no native
    /// package never constructs it.
    lazy var packageModules = ChuksModuleRegistry(host: self)
    /// Nodes already reported as non-finite, so the warning fires once, not every tick.
    var warnedNonFinite = Set<String>()
    // Layout timing (env CHUKS_LAYOUT_TIMING=1): logs Yoga compute vs frame-apply µs per
    // relayout, to decide whether moving YGNodeCalculateLayout off the UI thread is warranted.
    let layoutTiming = ProcessInfo.processInfo.environment["CHUKS_LAYOUT_TIMING"] != nil
    var layoutComputeUs: Double = 0
    let config: YGConfigRef = {
        let c: YGConfigRef = YGConfigNew()
        // Opt into Yoga's 1.x "errata" layout behaviors. The modern default changed the
        // cross-axis measure/stretch semantics so a measured Text no longer re-wraps to its
        // stretched width (it keeps its single-line height); the classic behavior re-measures
        // it at the resolved width, so Text wraps WITHOUT needing an explicit width set.
        YGConfigSetErrata(c, YGErrata(rawValue: 2147483647)!)   // YGErrataAll
        return c
    }()

    // Discovered from the Chuks tree (not hardcoded): the scroll region + its content.
    var listScroll: UIScrollView?
    var scrollContentIds: [String: String] = [:]      // scroll node id -> its content node id
    var horizScrollIds = Set<String>()                // which of those scroll sideways
    var scrollId = ""
    var listHoriz = false            // the tracked list scrolls horizontally (report x, not y)
    var stickBottomOn = false        // Scroll stickBottom: keep pinned to newest (chat)
    var stickPrevH: CGFloat = 0      // previous content height, to tell if the user was at the bottom
    var contentId = ""

    let header = UILabel()                              // host diagnostics only (not app UI)
    var timer: Timer?
    var frame = 0
    var taps: [UITapGestureRecognizer: String] = [:]
    var pillIds = Set<String>()   // views wanting a "full" (pill) corner radius, clamped to height/2 after layout
    var cornerRadii: [String: CGFloat] = [:]   // requested numeric corner radius per id; clamped to min(w,h)/2 in relayout
                                               // (so `radius: size` renders a circle on iOS, matching Android — never a diamond)
    var videoPlayers: [String: AVPlayer] = [:]        // per-node ATTACHED (visible) players
    var videoPlayerKey: [String: String] = [:]        // node id -> resource key (for pooling)
    var videoPool: [String: [AVPlayer]] = [:]         // idle, still-primed players by resource
    var videoObs: [ObjectIdentifier: NSObjectProtocol] = [:]  // player -> loop observer (persists across reuse)
    var videoNoLoop = Set<ObjectIdentifier>()         // players whose Video set loop=false (checked in the end observer)
    let videoPoolCap = 16                             // bound idle players kept warm
    // Adaptive viewport throttle. A viewport push is a SYNCHRONOUS engine round trip.
    // Native code answers in microseconds, so pushing every scroll frame is free; the
    // on-device dev runtime interprets, costing milliseconds, and pushing every frame
    // there leaves the engine's window trailing the real offset far enough that every
    // mounted row sits off-screen and the list renders BLANK until the backlog drains
    // (seconds, on a fast fling). So: measure the round trip and, when it is slow,
    // push at most once per ~3x its cost, with a final push once scrolling settles.
    var vpCost: CFTimeInterval = 0                    // smoothed cost of one push
    var vpLastPush: CFTimeInterval = 0
    var vpSettleScheduled = false
    var vsyncLink: CADisplayLink?
    var vpDirty = false
    var vsyncParity = false
    // Where a scroll frame's time actually goes, so an over-budget frame can be
    // attributed instead of guessed: engine+apply on frames that produced mutations,
    // Yoga layout + frame copy on those same frames, and the engine call alone on
    // frames that produced nothing (the common case between window shifts).
    var stageMaxEngine: CFTimeInterval = 0
    var stageMaxLayout: CFTimeInterval = 0
    var stageMaxIdle: CFTimeInterval = 0
    var stageFrames = 0
    var stageMaxCall: CFTimeInterval = 0     // the engine round trip alone
    var stageMaxApply: CFTimeInterval = 0    // applying the mutation stream to views
    var stageMaxBytes = 0                    // largest mutation stream in a frame
    var stageMaxTickWork: CFTimeInterval = 0 // worst synchronous work inside one tick
    var stageWorstInterval: CFTimeInterval = 0
    var stageWorkOnWorst: CFTimeInterval = 0 // our work on the frame that took longest
    var stageMaxCommit: CFTimeInterval = 0   // commit + render after our work returned
    var inViewportSync = false                        // reentrancy guard: relayout() sets contentSize, which can
                                                      // clamp the offset and re-fire scrollViewDidScroll synchronously.
                                                      // Without this the two call each other until the stack overflows.
    // Perf harness: auto-scroll the feed while a CADisplayLink measures real frame times.
    var displayLink: CADisplayLink?
    // Per-frame animation driver: ticks the engine every display frame while physics
    // (decay/spring) is live. Gated by FA|1 / FA|0 in the mutation stream, so it runs
    // only during an animation, never idle.
    var frameDriver: CADisplayLink?
    var perfActive = false
    var perfLastTs: CFTimeInterval = 0
    var perfFrames = 0, perfJanky = 0, perfMaxPlayers = 0
    var perfMaxFrame: Double = 0, perfSumTime: Double = 0
    var perfLog: [String] = []                        // every velocity's result line, for on-screen + file readout
    // Velocity sweep: ramp the fling speed to find the breaking point.
    // BENCH_LONG=1 repeats the sweep so a memory trend has room to show whether it
    // plateaus (heap sizing) or climbs without bound (a leak).
    let perfVels: [CGFloat] = ProcessInfo.processInfo.environment["BENCH_LONG"] == "1"
        ? Array(repeating: [120, 180, 240, 300, 360, 480], count: 4).flatMap { $0 }
        : [120, 180, 240, 300, 360, 480]
    var perfVelIdx = 0
    var perfDir: CGFloat = 1
    let perfPhaseFrames = 240   // ~4s per velocity phase
    var perfWarmup = 150        // unmeasured frames to prime players + steady state
    var buttonActions: [UIButton: String] = [:]
    var fieldActions: [UITextField: String] = [:]
    var fieldSubmit: [UITextField: String] = [:]   // onSubmit tag (return key)
    var fieldFocus: [UITextField: String] = [:]    // onFocus tag (began editing)
    var fieldBlur: [UITextField: String] = [:]     // onBlur tag (ended editing)
    var fieldMaxLen: [UITextField: Int] = [:]      // maxLength (enforced in shouldChangeCharacters)
    var switchActions: [UISwitch: String] = [:]
    var sliderActions: [UISlider: String] = [:]
    var sliderStep: [UISlider: Float] = [:]   // snap value to multiples of this (0 = continuous)
    var sliderDoneAction: [UISlider: String] = [:]   // onSlidingComplete tag ("<id>:slidedone")
    var scrollOnScroll: [UIScrollView: String] = [:] // Scroll onScroll tag ("<id>:scroll")
    var scrollLastPos: [UIScrollView: Int] = [:]     // last reported scroll offset (pt), to dedupe
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
    var glassViews: [String: UIVisualEffectView] = [:]                   // id -> Liquid Glass backing view
    var glassPending: Set<String> = []                                   // glass never built yet (no frame at style time)
    var glassBuiltFor: [String: UIUserInterfaceStyle] = [:]              // the appearance each material was built against
    var glassFallbackColor: [String: UIColor] = [:]                      // the `bg` a glass surface falls back to
    var themeFadeView: UIView?                                           // frozen frame of the old theme, faded out
    var gradLayers: [String: ChuksGradientView] = [:]                    // id -> linear-gradient background view
    var blurViews: [String: UIVisualEffectView] = [:]                    // id -> backdrop-blur material view
    var blurSpec: [String: (intensity: Int, tint: String)] = [:]         // last applied blur, to rebuild on either part changing
    var gradSpec: [String: (colors: String, angle: Int, stops: String)] = [:]   // last applied gradient, to rebuild on any part changing
    var refreshActions: [UIRefreshControl: String] = [:]                 // pull-to-refresh control -> onRefresh action
    var alertIds: Set<String> = []                                       // Alert node ids (native alerts)
    var alertData: [String: [String]] = [:]                              // id -> [title, message, confirm, cancel]
    var alertActions: [String: String] = [:]                             // id -> button-dispatch action
    var presentedAlert: String? = nil                                    // the Alert id currently on screen
    var pressOpacity: [String: CGFloat] = [:]                              // id -> Pressable active alpha (0-1)
    var pressGestures: [UILongPressGestureRecognizer: (String, CGFloat, String)] = [:]   // gesture -> (action, alpha, id)
    var longPressActions: [String: String] = [:]                          // id -> onLongPress action
    var pressInActions: [String: String] = [:]                            // id -> onPressIn action
    var pressOutActions: [String: String] = [:]                           // id -> onPressOut action
    var pressLongTimers: [ObjectIdentifier: Timer] = [:]                  // gesture -> pending long-press timer
    var pressLongFired = Set<ObjectIdentifier>()                          // gestures whose long-press already fired
    var disabledIds = Set<String>()                                       // ids whose disabled=1 (block fire)
    var mediaLoad: [String: String] = [:]                                 // id -> onLoad action (Image/Video)
    var mediaError: [String: String] = [:]                                // id -> onError action (Image)
    var mediaEnd: [String: String] = [:]                                  // id -> onEnd action (Video)
    var mediaProgress: [String: String] = [:]                             // id -> onProgress action (Video)
    var imageTint: [String: UIColor] = [:]                                // id -> Image tintColor (template render)
    var imageBlur: [String: CGFloat] = [:]                                // id -> Image blur radius (px)
    var imageFilter: [String: String] = [:]                              // id -> Image photo-filter preset
    // Text typography that needs an attributed string (rebuilt from the raw text on any
    // text/style change): raw string (pre-transform), decoration, letter spacing, line
    // height, and case transform. `label.font` holds size/weight/italic/family directly.
    var labelRaw: [String: String] = [:]        // Text raw string, before uppercase/etc
    var labelDeco: [String: String] = [:]       // underline | strike
    var labelKern: [String: CGFloat] = [:]      // tracking (letter spacing, px)
    var labelLead: [String: CGFloat] = [:]      // leading (target line height, px)
    var labelTransform: [String: String] = [:]  // upper | lower | cap
    var dashBorders: [String: (CGFloat, UIColor, Bool)] = [:]                     // id -> (width, color, dotted)
    var sideBorders: [String: (CGFloat, CGFloat, CGFloat, CGFloat, UIColor)] = [:] // id -> (t, r, b, l, color)
    var imageOpChain: [String: String] = [:]                             // id -> Image GPU op-chain (JSON)
    var imageOriginal: [String: UIImage] = [:]                           // id -> pre-filter image, so a filter/op swap re-applies from the original
    var imageSrc: [String: String] = [:]                                 // id -> local image source (file:// or bundled asset), for sized re-decode
    var imageDecodedDim: [String: Int] = [:]                             // id -> power-of-two px bucket last decoded at (guards relayout re-decode)
    let maxImageDim: CGFloat = 2560                                       // safety cap on decoded image side (px)
    var imageSpinners: [String: UIActivityIndicatorView] = [:]           // id -> loading spinner overlay
    var videoPosters: [String: UIImageView] = [:]                        // id -> poster overlay (until first frame)
    var posterObs: [String: NSKeyValueObservation] = [:]                 // id -> readyForDisplay observation
    var pressLongDelay: [String: TimeInterval] = [:]                      // id -> onLongPress hold time (s)
    var videoSeek: [String: Int] = [:]                                    // id -> last-applied seek (seconds)
    var videoTimeObservers: [String: Any] = [:]                           // id -> periodic time observer token
    var videoLastSec: [String: Int] = [:]                                 // id -> last whole second reported to onProgress
    var videoCtrlVCs: [String: AVPlayerViewController] = [:]              // id -> native player+controls VC (controls: true)
    var modalIds: Set<String> = []                                        // Modal node ids (full-screen overlays)
    var activeModal: String? = nil                                        // the currently-visible Modal
    var sheetModals: Set<String> = []                                     // Modal ids with position=bottom (draggable sheets)
    var modalActions: [String: String] = [:]                             // Modal id -> onDismiss action
    var sheetBg: UIView? = nil                                            // host-drawn sheet surface (rounded top, behind content)
    var sheetHandle: UIView? = nil                                        // host-drawn grab handle pill
    var sheetPan: UIPanGestureRecognizer? = nil                          // drag-to-dismiss recognizer on the sheet
    var shownSheet: String? = nil                                        // the sheet currently on screen (nil = none); a change drives the slide-up
    var connected = false                               // dev mode: is the VM server reachable?
    var cmrDevBase = ""                                 // CMR dev build: "http://<host>:7799" (cmr-dev.txt); empty => baked bundle
    var cmrVersion = 0                                  // last bundle version booted (for /hmr long-poll)

    // ---- engine calls: cgo (prod) or HTTP to the VM dev server (dev) -------
    // Each returns the mutation stream; nil means the server is down (reloading).
    func drainStr() -> String {
        guard let c = chuks_drain() else { return "" }
        let s = String(cString: c); chuks_free_str(c); return s
    }
    func eSetup() {
        #if CMR
        if !cmrDevBoot() { cmrBootBundle() }   // dev: fetch bundle over HTTP + hot reload; else the baked bundle
        #endif
        if !DEV_MODE { chuks_set_count(N) }
    }   // chuks_* bridge auto-runs chuks_init; dev server self-inits on boot
    func eMount() -> String? {
        if DEV_MODE { return devHTTP("/mount", "") }
        _ = chuks_mount(); return drainStr()
    }
    func eTick() -> String? {
        if DEV_MODE { return devHTTP("/tick", "") }
        _ = chuks_tick(); return drainStr()
    }
    // System back (the left-edge swipe here; Android's button/gesture on that host).
    // Returns true when the app consumed it.
    // Tell the engine a running task is out of time, so Background.expired() flips and
    // a handler can checkpoint instead of losing the batch.
    // ---- State restoration --------------------------------------------------
    //
    // The engine already serializes the route stack AND every useState cell, because
    // hot reload needs exactly that. So restoring puts the user back on the screen they
    // left WITH what they had typed and where they had scrolled, not merely on the right
    // screen. All that was missing was somewhere to keep it between launches.
    var stateFile: URL { appDir().appendingPathComponent("chuks-state.json") }
    // Written at launch and removed on a clean suspend. Finding it at launch means the
    // last run ended without suspending, which usually means it crashed, and restoring
    // straight back onto a screen that crashes can trap someone in a loop. React
    // Navigation's docs warn about exactly this.
    var runMarkerFile: URL { appDir().appendingPathComponent("chuks-running") }

    func eSaveState() -> String? {
        if DEV_MODE { return nil }
        return String(cString: chuks_saveState())
    }
    func eLoadState(_ data: String) {
        if DEV_MODE { return }
        _ = data.withCString { chuks_loadState(UnsafeMutablePointer(mutating: $0)) }
    }

    /// Persist on the way out. Called when the app leaves the screen, not on termination:
    /// iOS does not promise to run anything when it kills a suspended app.
    func persistState() {
        guard restoreWindow > 0, let s = eSaveState(), !s.isEmpty else { return }
        let doc = ["at": Int(Date().timeIntervalSince1970), "state": s] as [String: Any]
        if let data = try? JSONSerialization.data(withJSONObject: doc) {
            try? data.write(to: stateFile)
        }
        try? FileManager.default.removeItem(at: runMarkerFile)   // we suspended cleanly
    }

    /// Decide whether to restore, and do it. Called once, before the first mount.
    ///
    /// Four things have to be true, and each of them is a real failure if ignored:
    /// restoration is enabled; the app was not opened by a deep link (a link is a
    /// deliberate destination and must beat where the user happened to be); the last run
    /// suspended cleanly; and the state is recent enough that going back there is
    /// helpful rather than confusing.
    func restoreStateIfAppropriate() {
        let crashed = FileManager.default.fileExists(atPath: runMarkerFile.path)
        try? "1".write(to: runMarkerFile, atomically: true, encoding: .utf8)   // arm for this run

        guard restoreWindow > 0 else { return }
        guard lastURL == nil else { return }          // a deep link wins
        if crashed {
            try? FileManager.default.removeItem(at: stateFile)
            NSLog("chuks-state: not restoring, the previous run did not suspend cleanly")
            return
        }
        guard let data = try? Data(contentsOf: stateFile),
              let doc = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let at = doc["at"] as? Int, let saved = doc["state"] as? String else { return }
        let age = Int(Date().timeIntervalSince1970) - at
        if age > restoreWindow {
            try? FileManager.default.removeItem(at: stateFile)
            return                                     // stale: start fresh
        }
        eLoadState(saved)
    }

    func eDispatchTaskExpired() -> String? {
        if DEV_MODE { return nil }
        _ = "__bgexpire__".withCString { chuks_dispatch(UnsafeMutablePointer(mutating: $0)) }
        return drainStr()
    }
    func eBack() -> Bool {
        if DEV_MODE { return (devHTTP("/back", "") ?? "").isEmpty == false }
        let handled = chuks_back() > 0
        let s = drainStr()               // drainStr returns "" when there is nothing
        if !s.isEmpty { apply(s); relayout() }
        return handled
    }
    func eViewport(_ top: Int32, _ h: Int32, _ w: Int32) -> String? {
        if DEV_MODE { return devHTTP("/viewport", "\(top) \(h) \(w)") }
        _ = chuks_setViewport(top, h, w); return drainStr()
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
        NotificationCenter.default.addObserver(self, selector: #selector(kbHide(_:)),
            name: UIResponder.keyboardWillHideNotification, object: nil)
        // Moving focus between fields with the keyboard already up changes its frame
        // without a new Show, so re-evaluate the overlap for the newly focused field.
        NotificationCenter.default.addObserver(self, selector: #selector(kbShow(_:)),
            name: UIResponder.keyboardWillChangeFrameNotification, object: nil)

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
        // Report the safe-area insets BEFORE the first mount. They are normally pushed
        // from viewDidLayoutSubviews, which runs AFTER this, so the first tree was built
        // with zero insets and drew under the status bar until the next tick corrected
        // it. This view is not in a window yet, so take them from the scene's window —
        // the same reason the hot-reload path re-sends them to a fresh VM.
        if let w = UIApplication.shared.connectedScenes
                     .compactMap({ ($0 as? UIWindowScene)?.windows.first }).first {
            let ins = w.safeAreaInsets
            if ins.top > 0 || ins.bottom > 0 { lastInsets = ins; eInsets(ins) }
        }
        // Require a non-empty mount before marking connected: a momentarily unreachable
        // dev server returns nil, but a race can also return an empty body — either way
        // leaving connected=false lets step() keep retrying instead of stranding the app
        // on a blank screen with no recovery (AOT is in-process, so it never hits this).
        // BEFORE the first mount: loadState replaces the route stack and the useState
        // cells, so it has to happen while there is still nothing on screen. Restoring
        // afterwards would mean building the wrong screen and then throwing it away.
        restoreStateIfAppropriate()
        #if CMR
        // A boot that failed to compile / crashed has no VM to mount: show the dev
        // overlay with the reason and skip eMount (mounting a dead VM strands the app).
        if !cmrBootErrorPending.isEmpty {
            showDevError(cmrBootErrorPending)
        } else if let s = eMount(), !s.isEmpty { apply(s); connected = true }   // build the app tree
        #else
        if let s = eMount(), !s.isEmpty { apply(s); connected = true }   // build the app tree
        #endif
        // (dev: if the server isn't up yet / returned empty, step() reconnects + remounts)

        // Register the host wake: a spawned Chuks task that posts to the render thread
        // (dispatchAsync) fires this so we tick immediately instead of on the heartbeat.
        installBackSwipe()
        gChuksWakeVC = self
        chuks_set_wake(unsafeBitCast(chuksWakeThunk as (@convention(c) () -> Void), to: UnsafeMutableRawPointer.self))

        if !(BENCHMARK_MODE && ProcessInfo.processInfo.environment["NO_HEARTBEAT"] == "1") {
            timer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { [weak self] _ in self?.step() }
        }
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
        if let bar = sbColorView {                     // keep the status-bar fill covering the top inset
            bar.frame = CGRect(x: 0, y: 0, width: view.bounds.width, height: ins.top)
            view.bringSubviewToFront(bar)
        }
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
        guard let sc = listScroll else {
            // No scroll/list on screen: still report the full root viewport so
            // viewportWidth()/viewportHeight() are populated (e.g. for a Text wrap width).
            // Edge-to-edge: the viewport is the full screen (the app pads by safe insets).
            let w = Int32(view.bounds.width)
            let h = Int32(view.bounds.height)
            if w <= 0 || h <= 0 { return false }
            guard let s = eViewport(0, h, w) else { connected = false; return false }
            if !s.isEmpty { apply(s); return true }
            return false
        }
        // top is the scroll offset along the MAIN axis (x for a horizontal list, y otherwise);
        // height/width are ALWAYS the true viewport dimensions (never swapped), so
        // viewportWidth()/viewportHeight() stay correct. The reconciler picks vpW vs vpH as the
        // windowing extent per the list's orientation.
        let top = Int32(max(0, listHoriz ? sc.contentOffset.x : sc.contentOffset.y))
        let vh = Int32(sc.bounds.height); let vw = Int32(sc.bounds.width)
        if vh <= 0 || vw <= 0 { return false }
        let _tc0 = BENCHMARK_MODE ? CACurrentMediaTime() : 0
        guard let s = eViewport(top, vh, vw) else { connected = false; return false }
        let _tc1 = BENCHMARK_MODE ? CACurrentMediaTime() : 0
        if BENCHMARK_MODE, _tc1 - _tc0 > stageMaxCall { stageMaxCall = _tc1 - _tc0 }
        if !s.isEmpty {
            apply(s)
            if BENCHMARK_MODE {
                let a = CACurrentMediaTime() - _tc1
                if a > stageMaxApply { stageMaxApply = a }
                if s.utf8.count > stageMaxBytes { stageMaxBytes = s.utf8.count }
            }
            return true
        }
        return false
    }

    // The scroll took the touch: cancel any press in flight so the row un-dims and its
    // onPress does NOT fire, exactly like a touchable whose touch the scroll steals.
    // (RCTSurfaceTouchHandler does this with setEnabled:NO/YES; same trick here.)
    func scrollViewWillBeginDragging(_ sv: UIScrollView) {
        for (g, entry) in pressGestures where g.state == .began || g.state == .changed {
            if let v = g.view { UIView.animate(withDuration: 0.09) { v.alpha = 1.0 } }
            pressLongTimers[ObjectIdentifier(g)]?.invalidate()
            pressLongTimers[ObjectIdentifier(g)] = nil
            if let pout = pressOutActions[entry.2] { fire(pout) }
            g.isEnabled = false
            g.isEnabled = true
        }
    }

    // Per-frame viewport work runs on our OWN display link, not inside the scroll
    // callback. scrollViewDidScroll fires from within UIScrollView's layout pass, which
    // is already late in the frame, and it can fire more than once per frame; doing the
    // engine + layout there left too little of the 16.7ms budget for the commit, so a
    // frame occasionally missed vsync and landed a whole interval late. Marking the
    // viewport dirty and servicing it once per vsync does the same work at a predictable
    // point in the frame.
    func startVsyncPump() {
        if vsyncLink != nil { return }
        let dl = CADisplayLink(target: self, selector: #selector(vsyncTick))
        dl.add(to: .main, forMode: .common)
        vsyncLink = dl
    }
    @objc func vsyncTick() {
        // TEMPORARY EXPERIMENT: halve the per-frame main-thread load to test whether the
        // dropped frames are a throughput problem. If jank collapses, moving the engine
        // off the main thread is the fix; if it does not, threading would buy nothing.
        if BENCHMARK_MODE && ProcessInfo.processInfo.environment["HALF_RATE"] == "1" {
            vsyncParity = !vsyncParity
            if vsyncParity { return }
        }
        guard vpDirty else { return }
        vpDirty = false
        serviceViewport()
    }
    // The actual work, shared by the pump and by any caller that needs it synchronously.
    func serviceViewport() {
        if inViewportSync { return }
        inViewportSync = true
        defer { inViewportSync = false }
        let t0 = CACurrentMediaTime()
        if BENCHMARK_MODE {
            let produced = pushViewport()
            let tApplied = CACurrentMediaTime()
            if produced { relayout() }
            let tLaid = CACurrentMediaTime()
            if produced {
                let a = tApplied - t0, l = tLaid - tApplied
                if a > stageMaxEngine { stageMaxEngine = a }
                if l > stageMaxLayout { stageMaxLayout = l }
                stageFrames += 1
            } else if tApplied - t0 > stageMaxIdle {
                stageMaxIdle = tApplied - t0
            }
        } else {
            if pushViewport() { relayout() }
        }
        let spent = CACurrentMediaTime() - t0
        vpCost = vpCost == 0 ? spent : (vpCost * 0.7 + spent * 0.3)
        vpLastPush = CACurrentMediaTime()
    }

    func scrollViewDidScroll(_ sv: UIScrollView) {
        // relayout() below can nudge contentSize/offset and re-enter this delegate synchronously.
        // Skip the re-entrant call: the outer relayout already positioned for the current offset,
        // and the next real scroll frame picks up any newer offset. Prevents unbounded recursion.
        vpDirty = true            // serviced on the next vsync by vsyncTick()
        startVsyncPump()
        if !perfActive { headerText("scroll \(Int(sv.contentOffset.y))pt") }
        // Scroll onScroll: report the offset along the scrolling axis (points) when it changes.
        if let tag = scrollOnScroll[sv] {
            let horiz = sv.contentSize.width > sv.bounds.width + 1
            let pts = Int((horiz ? sv.contentOffset.x : sv.contentOffset.y).rounded())
            if scrollLastPos[sv] != pts {
                scrollLastPos[sv] = pts
                if let s = eInput(tag, String(pts)) { apply(s); relayout() } else { connected = false }
            }
        }
    }

    // A throttled-away scroll frame must not be the last word: once the offset stops
    // changing, push it for real so the window matches where the list actually is.
    func scheduleViewportSettle(_ sv: UIScrollView) {
        if vpSettleScheduled { return }
        vpSettleScheduled = true
        let at = sv.contentOffset
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self, weak sv] in
            guard let self = self, let sv = sv else { return }
            self.vpSettleScheduled = false
            if sv.contentOffset == at {          // settled: this is the offset to render
                if self.inViewportSync { return }
                self.inViewportSync = true
                defer { self.inViewportSync = false }
                if self.pushViewport() { self.relayout() }
                self.vpLastPush = CACurrentMediaTime()
            } else {
                self.scheduleViewportSettle(sv)  // still moving: check again
            }
        }
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
        stageMaxEngine = 0; stageMaxLayout = 0; stageMaxIdle = 0; stageFrames = 0
        stageMaxCall = 0; stageMaxApply = 0; stageMaxBytes = 0
        stageMaxTickWork = 0; stageWorstInterval = 0; stageWorkOnWorst = 0; stageMaxCommit = 0
    }
    @objc func perfTick() {
        let now = CACurrentMediaTime(), dt = now - perfLastTs; perfLastTs = now
        let _tickStart = now
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
        let ourWork = CACurrentMediaTime() - _tickStart
        if ourWork > stageMaxTickWork { stageMaxTickWork = ourWork }
        if dt > stageWorstInterval { stageWorstInterval = dt; stageWorkOnWorst = ourWork }
        // Everything AFTER we return: Core Animation's commit, UIKit layout/display, and
        // the render pass. The completion block fires when this frame's transaction is
        // done, so the delta is the part of the frame no reconciler change can touch.
        CATransaction.setCompletionBlock { [weak self] in
            guard let self = self else { return }
            let after = CACurrentMediaTime() - _tickStart - ourWork
            if after > self.stageMaxCommit { self.stageMaxCommit = after }
        }
        if perfFrames >= perfPhaseFrames { logPhase(vel) }
    }
    func logPhase(_ vel: CGFloat) {
        let n = max(1, perfFrames - 1)
        let avg = Double(n) / max(0.0001, perfSumTime)
        // Census of the per-node host tables alongside memory: cells are recycled, so
        // these must stay FLAT. If memory climbs while they stay flat, the growth is on
        // the engine side of the process, not in the host's bookkeeping.
        let msg = String(format: "BENCHMARK CHUKS vel=%d: avg %.0f fps | worst frame %.1f ms | janky(<50fps) %d/%d | %d video players peak | mem %.0f MB | views %d ynodes %d taps %d press %d",
                         Int(vel), avg, perfMaxFrame * 1000, perfJanky, n, perfMaxPlayers, physFootprintMB(),
                         views.count, ynodes.count, taps.count, pressGestures.count)
            + String(format: " | stages: engine %.1f ms, apply %.1f ms, layout %.1f ms, idle %.1f ms, bytes %d, mutating %d",
                     stageMaxCall * 1000, stageMaxApply * 1000, stageMaxLayout * 1000, stageMaxIdle * 1000,
                     stageMaxBytes, stageFrames)
            + String(format: " | worst frame %.1f ms of which OUR work %.1f ms; worst work %.1f ms; worst commit+render %.1f ms",
                     stageWorstInterval * 1000, stageWorkOnWorst * 1000, stageMaxTickWork * 1000, stageMaxCommit * 1000)
        print(msg); NSLog(msg); perfLog.append(msg)
        postBench(msg)   // same collector the RN/Flutter builds report to
        header.numberOfLines = 0; header.text = perfLog.joined(separator: "\n")   // keep all lines on screen
        perfVelIdx += 1
        if perfVelIdx >= perfVels.count { stopPerf(); return }
        resetPhase()
    }
    // Report one benchmark line to the collector on the Mac. A release build on a
    // physical device has no reachable console, and every runtime under comparison
    // needs the SAME reporting path or the comparison measures the reporting.
    func postBench(_ line: String) {
        guard let url = URL(string: "http://192.168.1.195:4100/") else { return }
        var r = URLRequest(url: url); r.httpMethod = "POST"; r.httpBody = line.data(using: .utf8)
        URLSession.shared.dataTask(with: r).resume()
    }

    func stopPerf() {
        perfActive = false; displayLink?.invalidate(); displayLink = nil
        print("BENCHMARK CHUKS: sweep done")
        // Settled reading: idle (no scrolling, no rendering) then measure again. A heap
        // that grew only to hold churn gives most of it back here; a leak does not.
        DispatchQueue.main.asyncAfter(deadline: .now() + 15.0) { [weak self] in
            guard let self = self else { return }
            let m = String(format: "BENCHMARK CHUKS settled(+15s idle): mem %.0f MB | views %d ynodes %d",
                           physFootprintMB(), self.views.count, self.ynodes.count)
            print(m); NSLog(m)
        }
        // Persist to the app's Documents so the results can be pulled off a real device
        // (headless syslog capture is unreliable on a locked/untrusted phone).
        let out = (perfLog + ["sweep done"]).joined(separator: "\n") + "\n"
        if let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            try? out.write(to: dir.appendingPathComponent("bench_results.txt"), atomically: true, encoding: .utf8)
        }
    }

    func step() {
        // dev mode: if the server went away (a reload), keep trying to reconnect,
        // then remount from a fresh /mount -- with the engine's state restored.
        if DEV_MODE && !connected {
            guard let s = eMount(), !s.isEmpty else { headerText("dev server down — reloading…"); return }
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

    // Called on the main thread when a background Chuks task posted work (via the
    // wake callback). A tick drains the engine's async queue and re-renders, so the
    // result paints this frame instead of waiting for the 0.4s heartbeat.
    func pumpWake() {
        if DEV_MODE && !connected { return }   // let step() own the reconnect path
        guard let s = eTick() else { return }
        if !s.isEmpty { apply(s); relayout() }
    }

    // Start/stop the per-frame animation driver (FA|1 / FA|0). While on, a CADisplayLink
    // ticks the engine every display frame so decay/spring physics advance at 60fps; the
    // engine emits FA|0 when it settles, which stops the link here.
    func setFrameDriver(_ on: Bool) {
        if on {
            if frameDriver == nil {
                let dl = CADisplayLink(target: self, selector: #selector(frameTick))
                dl.add(to: .main, forMode: .common); frameDriver = dl
            }
        } else {
            frameDriver?.invalidate(); frameDriver = nil
        }
    }
    @objc func frameTick() { pumpWake() }

    // Tear down the view + Yoga trees and rebuild from a fresh mount stream. Used
    // on hot reload: the host process stays alive, only the tree is rebuilt.
    func remount(_ mountStream: String) {
        for (_, v) in views { v.removeFromSuperview() }
        if let app = ynodes["app"] { YGNodeFreeRecursive(app) }   // frees the whole subtree
        views.removeAll(); ynodes.removeAll()
        taps.removeAll(); buttonActions.removeAll(); fieldActions.removeAll(); switchActions.removeAll(); sliderActions.removeAll()
        fieldSubmit.removeAll(); fieldFocus.removeAll(); fieldBlur.removeAll(); fieldMaxLen.removeAll()
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

    #if CMR
    // ---- CMR dev hot reload -------------------------------------------------
    // A CMR dev build (build-cmr.sh DEV=1) bundles cmr-dev.txt = "<host>:<port>" of
    // `chukspack serve`. On boot we fetch GET /bundle and boot the on-device VM with
    // it; a background poll of GET /hmr?since=N re-boots + remounts in place on each
    // .chuks change. The VM runs on the device; only the SOURCE crosses HTTP.
    func cmrDevBoot() -> Bool {
        guard let u = Bundle.main.url(forResource: "cmr-dev", withExtension: "txt"),
              let s = try? String(contentsOf: u, encoding: .utf8) else { return false }
        let host = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if host.isEmpty { return false }
        cmrDevBase = "http://\(host)"
        let (data, ver) = cmrFetchBundle()
        guard let data = data else { NSLog("CMR dev: %@ unreachable, using baked bundle", cmrDevBase); return false }
        cmrBootData(data); cmrVersion = ver
        NSLog("CMR dev boot v=%d (%d bytes) from %@", ver, data.count, cmrDevBase)
        startCmrHmr()
        return true
    }
    func cmrBootData(_ data: Data) {
        let rc = data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Int32 in
            chuks_cmr_boot(UnsafeMutablePointer(mutating: raw.bindMemory(to: CChar.self).baseAddress), Int32(data.count))
        }
        cmrBootErrorPending = rc != 0 ? cmrLastErrorText() : ""
    }
    // GET /bundle -> (data, X-CMR-Version). Synchronous (semaphore).
    func cmrFetchBundle() -> (Data?, Int) {
        guard let url = URL(string: "\(cmrDevBase)/bundle") else { return (nil, cmrVersion) }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 8)
        req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        let sem = DispatchSemaphore(value: 0)
        var out: Data? = nil; var ver = cmrVersion; var code = -1
        URLSession.shared.dataTask(with: req) { d, resp, _ in
            if let h = resp as? HTTPURLResponse {
                code = h.statusCode
                if h.statusCode == 200, let d = d { out = d }
                if let v = h.value(forHTTPHeaderField: "X-CMR-Version").flatMap({ Int($0) }) { ver = v }
            }
            sem.signal()
        }.resume()
        _ = sem.wait(timeout: .now() + 9)
        NSLog("CMRDBG fetchBundle %@ -> code=%d bytes=%d ver=%d", url.absoluteString, code, out?.count ?? 0, ver)
        return (out, ver)
    }
    // GET /hmr?since=N -> new version (!= since), or 0 on 204/error. Long-poll.
    func cmrPollHmr(_ since: Int) -> Int {
        guard let url = URL(string: "\(cmrDevBase)/hmr?since=\(since)") else { return 0 }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 35)
        req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        let sem = DispatchSemaphore(value: 0)
        var v = 0
        URLSession.shared.dataTask(with: req) { d, resp, _ in
            if let h = resp as? HTTPURLResponse, h.statusCode == 200, let d = d,
               let n = Int((String(data: d, encoding: .utf8) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)) { v = n }
            sem.signal()
        }.resume()
        _ = sem.wait(timeout: .now() + 36)
        return v
    }
    func startCmrHmr() {
        DispatchQueue.global(qos: .utility).async { [weak self] in
            while let self = self {
                let v = self.cmrPollHmr(self.cmrVersion)
                if v != self.cmrVersion && v > 0 {
                    // Fetch the delta with the CURRENT (old) version so the server
                    // sends only the modules changed since it. Advance cmrVersion HERE,
                    // in the poll thread, before dispatching the reload — otherwise the
                    // next poll (running before the async main-thread reload sets it)
                    // still sees the old version and re-fetches the same delta.
                    let (data, ver, isDelta) = self.cmrFetchDelta(self.cmrVersion)
                    self.cmrVersion = ver
                    if let data = data { DispatchQueue.main.async { self.cmrReloadInPlace(data, ver, isDelta) } }
                } else {
                    Thread.sleep(forTimeInterval: 0.15)
                }
            }
        }
    }
    // GET /delta?since=N -> (payload, X-CMR-Version, isDelta). A delta (isDelta=true)
    // carries only the modules edited since N (~5KB); a full bundle (isDelta=false) is
    // sent when the device is fresh or the server restarted. Synchronous (semaphore).
    func cmrFetchDelta(_ since: Int) -> (Data?, Int, Bool) {
        guard let url = URL(string: "\(cmrDevBase)/delta?since=\(since)") else { return (nil, cmrVersion, false) }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
        req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        let sem = DispatchSemaphore(value: 0)
        var out: Data? = nil; var ver = cmrVersion; var isDelta = false
        URLSession.shared.dataTask(with: req) { d, resp, _ in
            if let h = resp as? HTTPURLResponse, h.statusCode == 200, let d = d {
                out = d
                if let v = h.value(forHTTPHeaderField: "X-CMR-Version").flatMap({ Int($0) }) { ver = v }
                isDelta = (h.value(forHTTPHeaderField: "X-CMR-Delta") ?? "0") == "1"
            }
            sem.signal()
        }.resume()
        _ = sem.wait(timeout: .now() + 11)
        return (out, ver, isDelta)
    }
    func cmrApplyDelta(_ data: Data) -> Int32 {
        return data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Int32 in
            chuks_cmr_apply_delta(UnsafeMutablePointer(mutating: raw.bindMemory(to: CChar.self).baseAddress), Int32(data.count))
        }
    }
    // In-place reload (no VC recreate, no blank): merge the delta (or reboot with a
    // full bundle), then rebuild the tree via remount(). State/nav are carried across.
    func cmrReloadInPlace(_ data: Data, _ ver: Int, _ isDelta: Bool) {
        setFrameDriver(false)
        let saved = cmrSaveState()          // cells + nav from the OLD VM
        let rc = isDelta ? cmrApplyDelta(data)
                         : data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Int32 in
                             chuks_cmr_boot(UnsafeMutablePointer(mutating: raw.bindMemory(to: CChar.self).baseAddress), Int32(data.count))
                           }
        cmrVersion = ver
        NSLog("CMR reload %@ rc=%d v=%d (%d bytes)", isDelta ? "delta" : "full", rc, ver, data.count)
        if rc != 0 {
            // A live edit failed to compile: keep the saved state, show the reason, and
            // wait for the next good save (which dismisses the overlay and restores state).
            cmrPendingState = saved
            showDevError(cmrLastErrorText())
            return
        }
        cmrLoadState(cmrPendingState.isEmpty ? saved : cmrPendingState)   // restore before mount
        cmrPendingState = ""
        dismissDevError()
        // A hot reload swaps in a FRESH VM whose safe-area insets are zero. The change-guard
        // in viewDidLayoutSubviews (ins == lastInsets) would skip re-sending them, so the new
        // VM would lay out edge-to-edge (content under the status bar, tab bar under the home
        // indicator). Force-resend the current insets to the fresh VM before mounting.
        let ins = view.safeAreaInsets; lastInsets = ins; eInsets(ins)
        if let s = eMount() { remount(s); _ = pushViewport(); relayout() }
    }
    var cmrPendingState = ""   // app state kept across a failed reload, restored on the fix

    // ---- Dev error overlay ----------------------------------------------------
    // A boot / hot-reload that fails to compile or crashes shows this over the app: a
    // dark card with a red header naming the error class and the exact message +
    // file:line underneath (from the VM via cmrLastErrorText). Dismissed on the next
    // clean reload. Dev-only; a shipped app never carries an unfixed error.
    weak var devErrorView: UIView?
    // Renders the structured JSON payload from chuks_cmr_last_error as a full-screen dev
    // error screen: an error-class badge, the message, the developer's relative file:line,
    // a source code frame with the offending line highlighted, and a clean call stack.
    // Built SYNCHRONOUSLY, frame-based (the host runs no Auto Layout pass; a deferred add
    // or a separate window did not composite, but a synchronous self.view subview does).
    func showDevError(_ message: String) {
        dismissDevError()
        let b = self.view.bounds.isEmpty ? UIScreen.main.bounds : self.view.bounds

        // Parse the JSON payload; fall back to a bare message if it is not JSON.
        var cls = "Error", msg = message, file = "", line = 0, amber = false
        var frameRows: [(Int, String, Bool)] = []
        var stack: [(String, Int)] = []
        if let data = message.data(using: .utf8),
           let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            cls = o["class"] as? String ?? "Error"
            msg = o["message"] as? String ?? ""
            file = o["file"] as? String ?? ""
            line = o["line"] as? Int ?? 0
            amber = cls.lowercased().hasPrefix("type")
            if let fr = o["frame"] as? [[String: Any]] {
                frameRows = fr.map { (($0["n"] as? Int) ?? 0, ($0["t"] as? String) ?? "", ($0["hot"] as? Bool) ?? false) }
            }
            if let st = o["stack"] as? [[String: Any]] {
                stack = st.map { (($0["file"] as? String) ?? "", ($0["line"] as? Int) ?? 0) }
            }
        }

        let red = UIColor(red: 0.941, green: 0.380, blue: 0.427, alpha: 1)      // #F0616D
        let amberC = UIColor(red: 0.961, green: 0.647, blue: 0.145, alpha: 1)   // #F5A524
        let txt = UIColor(red: 0.914, green: 0.929, blue: 0.953, alpha: 1)      // #E9EDF3
        let dim = UIColor(red: 0.412, green: 0.447, blue: 0.498, alpha: 1)      // #69727F
        let link = UIColor(red: 0.302, green: 0.816, blue: 0.541, alpha: 1)     // #4DD08A
        let gutter = UIColor(red: 0.290, green: 0.329, blue: 0.392, alpha: 1)   // #4A5464
        let frameBg = UIColor(red: 0.067, green: 0.082, blue: 0.110, alpha: 1)  // #11151C
        let lineC = UIColor(red: 0.137, green: 0.165, blue: 0.208, alpha: 1)    // #232A35
        let cardBg = UIColor(red: 0.082, green: 0.102, blue: 0.133, alpha: 1)   // #151A22
        let codeCol = UIColor(red: 0.776, green: 0.812, blue: 0.859, alpha: 1)  // #C6CFDB
        let mono = UIFont.monospacedSystemFont(ofSize: 12.5, weight: .regular)

        let scrim = UIView(frame: b)
        scrim.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scrim.backgroundColor = UIColor(red: 0.051, green: 0.063, blue: 0.086, alpha: 1) // #0D1016

        let scroll = UIScrollView(frame: b)
        scroll.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scroll.alwaysBounceVertical = true
        scrim.addSubview(scroll)

        let padX: CGFloat = 22
        let contentW = b.width - padX * 2
        let safeTop = self.view.safeAreaInsets.top
        var y: CGFloat = safeTop > 0 ? safeTop + 20 : 52

        func measure(_ s: String, _ font: UIFont, _ w: CGFloat, lines: Int = 0) -> CGFloat {
            let bound = (s as NSString).boundingRect(with: CGSize(width: w, height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: [.font: font], context: nil)
            let h = ceil(bound.height)
            return lines > 0 ? min(h, font.lineHeight * CGFloat(lines) + 2) : h
        }

        // badge (pill sized to its text)
        let badgeText = "⚠  " + cls.uppercased()
        let badgeFont = UIFont.systemFont(ofSize: 11.5, weight: .bold)
        let badgeW = ceil((badgeText as NSString).size(withAttributes: [.font: badgeFont]).width) + 24
        let badge = UILabel(frame: CGRect(x: padX, y: y, width: badgeW, height: 28))
        badge.text = badgeText; badge.font = badgeFont; badge.textColor = .white; badge.textAlignment = .center
        badge.backgroundColor = amber ? amberC : red
        badge.layer.cornerRadius = 14; badge.clipsToBounds = true
        scroll.addSubview(badge); y += 28 + 16

        // message
        let msgFont = UIFont.systemFont(ofSize: 22, weight: .bold)
        let msgText = msg.isEmpty ? "Unknown error" : msg
        let msgH = measure(msgText, msgFont, contentW)
        let msgLbl = UILabel(frame: CGRect(x: padX, y: y, width: contentW, height: msgH))
        msgLbl.numberOfLines = 0; msgLbl.text = msgText; msgLbl.font = msgFont; msgLbl.textColor = txt
        scroll.addSubview(msgLbl); y += msgH + 6

        // location
        if !file.isEmpty {
            let loc = UILabel(frame: CGRect(x: padX, y: y, width: contentW, height: 20))
            loc.text = "\(file):\(line)"; loc.font = UIFont.monospacedSystemFont(ofSize: 13.5, weight: .medium); loc.textColor = link
            scroll.addSubview(loc); y += 20 + 14
        }

        // code frame: a fixed line-number gutter + a HORIZONTALLY scrollable code column,
        // so a long line can be scrolled to instead of being truncated.
        if !frameRows.isEmpty {
            let rowH: CGFloat = 24, padV: CGFloat = 12, gutterW: CGFloat = 44
            let boxH = CGFloat(frameRows.count) * rowH + padV * 2
            let box = UIView(frame: CGRect(x: padX, y: y, width: contentW, height: boxH))
            box.backgroundColor = frameBg; box.layer.cornerRadius = 14; box.layer.borderWidth = 1
            box.layer.borderColor = lineC.cgColor; box.clipsToBounds = true

            let hs = UIScrollView(frame: CGRect(x: gutterW, y: 0, width: contentW - gutterW, height: boxH))
            hs.showsHorizontalScrollIndicator = false; hs.bounces = false
            box.addSubview(hs)
            var maxTextW: CGFloat = 0
            for (_, t, _) in frameRows { maxTextW = max(maxTextW, (t as NSString).size(withAttributes: [.font: mono]).width) }
            let codeW = max(hs.bounds.width, maxTextW + 28)   // hot bar fills the frame even for short lines

            var ry = padV
            for (n, t, hot) in frameRows {
                if hot {
                    let hlG = UIView(frame: CGRect(x: 0, y: ry, width: gutterW, height: rowH))
                    hlG.backgroundColor = red.withAlphaComponent(0.14); box.addSubview(hlG)
                    let hlC = UIView(frame: CGRect(x: 0, y: ry, width: codeW, height: rowH))
                    hlC.backgroundColor = red.withAlphaComponent(0.14); hs.addSubview(hlC)
                }
                let g = UILabel(frame: CGRect(x: 0, y: ry, width: gutterW - 10, height: rowH))
                g.text = "\(n)"; g.font = mono; g.textColor = hot ? red : gutter; g.textAlignment = .right
                box.addSubview(g)
                let c = UILabel(frame: CGRect(x: 12, y: ry, width: codeW - 12, height: rowH))
                c.text = t; c.font = mono; c.textColor = hot ? .white : codeCol
                hs.addSubview(c)
                ry += rowH
            }
            hs.contentSize = CGSize(width: codeW, height: boxH)
            scroll.addSubview(box); y += boxH + 20
        }

        // call stack
        if !stack.isEmpty {
            let sh = UILabel(frame: CGRect(x: padX + 2, y: y, width: contentW, height: 16))
            sh.text = "CALL STACK"; sh.font = UIFont.systemFont(ofSize: 11, weight: .bold); sh.textColor = dim
            sh.attributedText = NSAttributedString(string: "CALL STACK", attributes: [.font: sh.font!, .foregroundColor: dim, .kern: 1.3])
            scroll.addSubview(sh); y += 16 + 8
            for (sf, sl) in stack {
                let row = UILabel(frame: CGRect(x: padX, y: y, width: contentW, height: 38))
                row.text = "  \(sf):\(sl)"; row.font = UIFont.monospacedSystemFont(ofSize: 12.5, weight: .regular); row.textColor = link
                row.backgroundColor = cardBg; row.layer.cornerRadius = 10; row.clipsToBounds = true
                scroll.addSubview(row); y += 38 + 3
            }
        }

        // hint
        let hintFont = UIFont.systemFont(ofSize: 12.5)
        let hintText = "Fix the error and save to reload. Hot reload keeps your state."
        let hintH2 = measure(hintText, hintFont, contentW)
        let hint = UILabel(frame: CGRect(x: padX, y: y + 24, width: contentW, height: hintH2))
        hint.numberOfLines = 0; hint.text = hintText; hint.font = hintFont; hint.textColor = dim
        scroll.addSubview(hint); y += 24 + hintH2 + 28

        scroll.contentSize = CGSize(width: b.width, height: max(y, b.height + 1))
        self.view.addSubview(scrim)
        self.view.bringSubviewToFront(scrim)
        self.devErrorView = scrim
    }
    func dismissDevError() {
        devErrorView?.removeFromSuperview(); devErrorView = nil
    }
    // Serialize / restore the app's state (useState cells + navigation stack) around
    // a reload, so an edit keeps your current screen instead of resetting to route 0.
    func cmrSaveState() -> String {
        guard let c = chuks_cmr_save_state() else { return "" }
        let s = String(cString: c); chuks_free_str(c); return s
    }
    func cmrLoadState(_ state: String) {
        state.withCString { chuks_cmr_load_state(UnsafeMutablePointer(mutating: $0)) }
    }
    #endif

    func headerText(_ line2: String) {
        header.text = "Chuks app (100% Chuks): \(N) cards, \(views.count) live views\n\(line2)"
    }

    // return key dismisses the keyboard
    // Route polyline renderer for a Map with a `path` (the walk route).
    func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
        if let line = overlay as? MKPolyline {
            let r = MKPolylineRenderer(polyline: line)
            r.strokeColor = UIColor(red: 0.18, green: 0.83, blue: 0.55, alpha: 1)   // route stroke: green
            r.lineWidth = 4
            r.lineCap = .round
            return r
        }
        return MKOverlayRenderer(overlay: overlay)
    }
    func textFieldShouldReturn(_ tf: UITextField) -> Bool {
        if let t = fieldSubmit[tf] { fire(t) }   // onSubmit (return key)
        tf.resignFirstResponder(); return true
    }
    func textFieldDidBeginEditing(_ tf: UITextField) { if let t = fieldFocus[tf] { fire(t) } }
    func textFieldDidEndEditing(_ tf: UITextField) { if let t = fieldBlur[tf] { fire(t) } }
    // Enforce maxLength on a single-line field.
    func textField(_ tf: UITextField, shouldChangeCharactersIn range: NSRange, replacementString string: String) -> Bool {
        guard let max = fieldMaxLen[tf], max >= 0 else { return true }
        let cur = tf.text ?? ""
        let next = (cur as NSString).replacingCharacters(in: range, with: string)
        return next.count <= max
    }

    // The iOS way back: a swipe from the left edge. This fires the same event Android's
    // system back does, so an app writes ONE handler. What it does not give you is the
    // interactive drag with parallax and cancel-on-release; that comes from a real
    // UINavigationController driving its own interactivePopGestureRecognizer (which is
    // how SwiftUI and react-native-screens get it) and is a separate piece of work.
    // Ask UIKit to let OUR left-edge gesture win over the system's edge handling. Without
    // this the first touch of an edge swipe is withheld from the app (the event log shows
    // systemGestureStateChange with shouldSend: 0) and the recognizer never starts.
    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge { .left }

    func installBackSwipe() {
        let g = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(handleBackSwipe(_:)))
        g.edges = .left
        g.delegate = self
        view.addGestureRecognizer(g)
    }
    // An INTERACTIVE pop: the top screen follows the finger, the one beneath it slides
    // in behind, and letting go part-way puts it back. The engine mounts the top two
    // routes as full-screen sibling containers under "app" (see NavStack.render), which
    // is what makes this possible at all -- with only the top screen mounted there is
    // nothing to reveal, and the best you can do is fire back on release.
    //
    // The two containers are simply the last two subviews of the app root, so this
    // needs no extra protocol: the stack's shape is already in the view tree. When
    // there are not two (the root screen), it falls back to the release-threshold
    // behaviour so an app that handles back itself still gets the gesture.
    var swipeTop: UIView?
    var swipeBelow: UIView?
    // The container the gesture drags in. "" = the app stack's root ("app"); a tab that
    // owns its history announces its own container, so the pair that moves is the pair
    // the user can see rather than the shell sitting behind it.
    var stackHostId: String = ""
    // How recently the app must have been backgrounded for its state to be restored, in
    // seconds. 0 disables restoration. Read from app.json at build time.
    var restoreWindow: Int {
        (Bundle.main.object(forInfoDictionaryKey: "ChuksStateRestoreWindow") as? Int) ?? 1800
    }

    // ---- Background tasks ---------------------------------------------------
    //
    // The engine hands us a token per defined task (bg.define). When the OS wakes us we
    // fire that token, then PUMP the engine until the handler reports back, because a
    // handler that awaits produces its result on a later turn and the display link that
    // normally drives those turns is stopped while we are in the background.
    var bgTokens: [String: String] = [:]        // task name -> engine token
    var bgRunning: [String: BGTask] = [:]       // name -> the OS task awaiting completion
    var bgPumpTimer: Timer?
    // Identifiers the build put in BGTaskSchedulerPermittedIdentifiers. BGTaskScheduler
    // rejects anything not listed there, so this is the authoritative set.
    static var permittedTaskIds: [String] {
        (Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String]) ?? []
    }

    /// The OS woke us for `name`. Fire the Chuks handler and keep the engine turning
    /// until it answers, or until the OS takes the time back.
    func runBackgroundTask(_ name: String, _ task: BGTask) {
        bgRunning[name] = task
        // The OS can pull the plug at any point. Tell the engine so a handler can
        // checkpoint (Background.expired()) rather than losing a half-done batch.
        task.expirationHandler = { [weak self] in
            guard let self = self else { return }
            _ = self.eDispatchTaskExpired()
            self.finishBackgroundTask(name, success: false)
        }
        guard let token = bgTokens[name] else {
            // Mounted but the app never defined this task: nothing to run, and saying so
            // is better than leaving the OS waiting for a completion that never comes.
            task.setTaskCompleted(success: false)
            bgRunning[name] = nil
            return
        }
        _ = eResolve(token, name)
        startBackgroundPump()
    }

    /// Turn the engine on a timer while a task runs. Cheap, and only alive for the
    /// seconds the OS has granted us.
    func startBackgroundPump() {
        if bgPumpTimer != nil { return }
        bgPumpTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            if self.bgRunning.isEmpty { self.stopBackgroundPump(); return }
            if let s = self.eTick(), !s.isEmpty { self.apply(s) }
        }
    }
    func stopBackgroundPump() { bgPumpTimer?.invalidate(); bgPumpTimer = nil }

    /// The engine reported a result (bg.result), or the OS expired us.
    func finishBackgroundTask(_ name: String, success: Bool) {
        guard let task = bgRunning[name] else { return }
        bgRunning[name] = nil
        task.setTaskCompleted(success: success)
        if bgRunning.isEmpty { stopBackgroundPump() }
    }

    /// Schedule one request. `kind` is "refresh" or "processing"; iOS honours constraints
    /// only on the latter, which is why the two shapes stay distinct all the way down.
    func scheduleBackgroundTask(_ name: String, _ delay: Int, _ kind: String, _ constraints: [String: String]) {
        guard Self.permittedTaskIds.contains(name) else {
            NSLog("chuks-bg: \"%@\" is not in BGTaskSchedulerPermittedIdentifiers; add it to backgroundTasks in app.json", name)
            return
        }
        let begin = Date(timeIntervalSinceNow: TimeInterval(max(0, delay)))
        let request: BGTaskRequest
        if kind == "processing" {
            let r = BGProcessingTaskRequest(identifier: name)
            // The only two constraints iOS can enforce. Everything else the app asked for
            // is reported at build time rather than silently dropped here.
            r.requiresNetworkConnectivity = (constraints["network"] ?? "none") != "none"
            r.requiresExternalPower = constraints["charging"] == "1"
            request = r
        } else {
            request = BGAppRefreshTaskRequest(identifier: name)
        }
        request.earliestBeginDate = begin
        do { try BGTaskScheduler.shared.submit(request) }
        catch {
            // Code 1 is "unavailable", and it has two causes that need different answers.
            // The raw error says neither, and a developer seeing it on a simulator would
            // reasonably conclude their code is broken.
            let ns = error as NSError
            if ns.domain == "BGTaskSchedulerErrorDomain" && ns.code == 1 {
                #if targetEnvironment(simulator)
                NSLog("chuks-bg: \"%@\" was not scheduled: BGTaskScheduler does not run on the simulator. Test background tasks on a device.", name)
                #else
                NSLog("chuks-bg: \"%@\" was not scheduled: background activity is switched off for this app (Settings > General > Background App Refresh). Background.status reports this as \"restricted\".", name)
                #endif
            } else {
                NSLog("chuks-bg: could not schedule %@: %@", name, String(describing: error))
            }
        }
    }
    let swipeParallax: CGFloat = 0.28      // how far the revealed screen sits left, as a fraction of width
    @objc func handleBackSwipe(_ g: UIScreenEdgePanGestureRecognizer) {
        let w = max(1, view.bounds.width)
        switch g.state {
        case .began:
            let host = stackHostId.isEmpty ? views["app"] : (views[stackHostId] ?? views["app"])
            let kids = host?.subviews ?? []
            guard kids.count >= 2 else { swipeTop = nil; swipeBelow = nil; return }
            swipeTop = kids[kids.count - 1]
            swipeBelow = kids[kids.count - 2]
            // Park the revealed screen at its start offset. It is fully covered right
            // now, so this never shows as a jump.
            swipeBelow?.transform = CGAffineTransform(translationX: -w * swipeParallax, y: 0)
        case .changed:
            guard let top = swipeTop, let below = swipeBelow else { return }
            let t = min(w, max(0, g.translation(in: g.view).x))
            top.transform = CGAffineTransform(translationX: t, y: 0)
            below.transform = CGAffineTransform(translationX: -w * swipeParallax * (1 - t / w), y: 0)
        case .ended, .cancelled, .failed:
            guard let top = swipeTop, let below = swipeBelow else {
                if g.state == .ended {          // no stack to pop: the app may still handle back
                    let dx = g.translation(in: g.view).x, vx = g.velocity(in: g.view).x
                    if dx > 60 || (dx > 20 && vx > 300) { _ = eBack() }
                }
                return
            }
            swipeTop = nil; swipeBelow = nil
            let t = max(0, g.translation(in: g.view).x), vx = g.velocity(in: g.view).x
            // Committed: past the half-way-ish mark, or short but thrown (the thresholds
            // UIKit uses for its own pop feel right here too).
            let commit = (g.state == .ended) && (t > w * 0.4 || (t > 20 && vx > 300))
            let rest = max(0.12, min(0.35, Double((w - t) / max(300, vx))))   // shorter throw, shorter animation
            UIView.animate(withDuration: rest, delay: 0, options: [.curveEaseOut], animations: {
                top.transform = CGAffineTransform(translationX: commit ? w : 0, y: 0)
                below.transform = commit ? .identity : CGAffineTransform(translationX: -w * self.swipeParallax, y: 0)
            }, completion: { _ in
                // Whatever happened, both screens go back to untransformed: on a commit
                // the top one is about to be torn down and the revealed one becomes the
                // top; on a cancel the revealed one is covered again.
                top.transform = .identity
                below.transform = .identity
                if commit { _ = self.eBack() }
            })
        default: break
        }
    }

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
    // A continuous Gesture recognizer inside a Scroll should WIN over the scroll: the
    // scroll's pan waits for ours to fail, so a drag that starts on the Gesture drags it
    // instead of scrolling (a drag elsewhere never involves ours, so the scroll is normal).
    // Delegate-driven (called at recognition time), so no attach-order race.
    func gestureRecognizer(_ g: UIGestureRecognizer, shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
        // g is one of OUR Gesture recognizers (delegate === self). If `other` is an
        // enclosing scroll's pan, return true so the scroll must wait for ours to fail.
        return (g is UIPanGestureRecognizer) && (other.view is UIScrollView)
    }

    // Keyboard avoidance. The keyboard OVERLAYS the app; it does not resize it. Shrinking
    // the whole tree by the keyboard height (what this used to do) lifts EVERY
    // bottom-anchored thing, so typing in a field at the top of the screen dragged the tab
    // bar up over the keyboard -- which no platform does: a tab bar stays put and the
    // keyboard covers it.
    //
    // So lift only what is actually covered, and only as much as it takes:
    //   - focused field inside a Scroll: give that scroll a bottom inset and scroll the
    //     field into view (what UIKit itself does). Nothing else moves.
    //   - focused field not in a scroll (a chat composer pinned to the bottom): shift the
    //     tree by exactly the overlap, so the composer clears the keyboard and no more.
    //   - focused field already above the keyboard: nothing moves at all.
    var kbHeight: CGFloat = 0            // the SHIFT applied to the tree, not the keyboard height
    var kbScroll: UIScrollView?          // the scroll we inset, to undo on hide
    @objc func kbShow(_ n: Notification) {
        guard let end = (n.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue else { return }
        let kbTop = view.bounds.height - max(0, end.height)          // keyboard's top edge, in view coords
        let dur = (n.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25
        // The focused field: we own every input view, so find it rather than reaching for
        // a private first-responder API.
        var field: UIView? = nil
        for (_, v) in views where (v is UITextField || v is UITextView) && v.isFirstResponder { field = v; break }
        guard let f = field else { return }
        // Measure against the UNLIFTED layout: the tree is already shifted by kbHeight, so
        // add it back. The keyboard sends several notifications (show, then frame changes
        // as it settles or as focus moves), and without this each pass would measure the
        // field where the previous pass put it -- the lift would collapse to nothing.
        let fFrame = f.convert(f.bounds, to: view)
        let overlap = max(0, fFrame.maxY + kbHeight + 8 - kbTop)     // 8pt of breathing room

        var scroll: UIScrollView? = nil                              // nearest scrollable ancestor
        var p: UIView? = f.superview
        while let cur = p { if let sc = cur as? UIScrollView { scroll = sc; break }; p = cur.superview }

        if let sc = scroll {
            let inset = max(0, end.height - view.safeAreaInsets.bottom)
            let isNew = (kbScroll !== sc)                             // scroll only on the way in
            kbScroll = sc
            sc.contentInset.bottom = inset
            sc.verticalScrollIndicatorInsets.bottom = inset
            if isNew && overlap > 0 { sc.setContentOffset(CGPoint(x: sc.contentOffset.x, y: sc.contentOffset.y + overlap), animated: true) }
            if kbHeight != 0 { kbHeight = 0; UIView.animate(withDuration: dur) { self.relayout() } }
            return
        }
        if overlap == kbHeight { return }
        kbHeight = overlap
        UIView.animate(withDuration: dur) { self.relayout() }
    }
    @objc func kbHide(_ n: Notification) {
        let dur = (n.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25
        if let sc = kbScroll {
            kbScroll = nil
            UIView.animate(withDuration: dur) { sc.contentInset.bottom = 0; sc.verticalScrollIndicatorInsets.bottom = 0 }
        }
        if kbHeight == 0 { return }
        kbHeight = 0
        UIView.animate(withDuration: dur) { self.relayout() }
    }

    // ---- apply a Chuks mutation stream to both trees -----------------------
    // Status bar contrast follows the app theme (set in syncChrome from the app bg).
    private var statusBarStyle: UIStatusBarStyle = .lightContent   // theme-derived (auto)
    private var sbStyleOverride: UIStatusBarStyle? = nil           // explicit StatusBar style
    private var sbHiddenOverride = false                           // explicit StatusBar hidden
    private var sbColorView: UIView?                               // StatusBar background fill (top safe area)
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
            case "P" where f.count >= 3: setText(f[1], chuksUnescapeText(f[2...].joined(separator: "|")))   // rejoin: text may contain '|'
            case "V" where f.count >= 3: setFieldValue(f[1], chuksUnescapeText(f[2...].joined(separator: "|")))   // controlled value (may contain '|')
            case "T" where f.count >= 3: bindAction(f[1], action: f[2])
            case "TS" where f.count >= 2: if let tf = views[f[1]] as? UITextField { fieldSubmit[tf] = f[1] + ":submit" }
            case "TF" where f.count >= 2: if let tf = views[f[1]] as? UITextField { fieldFocus[tf] = f[1] + ":focus" }
            case "TB" where f.count >= 2: if let tf = views[f[1]] as? UITextField { fieldBlur[tf] = f[1] + ":blur" }
            case "TL" where f.count >= 2: longPressActions[f[1]] = f[1] + ":longpress"   // Pressable onLongPress
            case "TPI" where f.count >= 2: pressInActions[f[1]] = f[1] + ":pressin"       // Pressable onPressIn
            case "TPO" where f.count >= 2: pressOutActions[f[1]] = f[1] + ":pressout"     // Pressable onPressOut
            case "ML" where f.count >= 2: mediaLoad[f[1]] = f[1] + ":load"                // Image/Video onLoad
            case "ME" where f.count >= 2: mediaError[f[1]] = f[1] + ":error"              // Image onError
            case "MN" where f.count >= 2: mediaEnd[f[1]] = f[1] + ":end"                  // Video onEnd
            case "MP" where f.count >= 2: mediaProgress[f[1]] = f[1] + ":progress"; addVideoProgress(f[1])   // Video onProgress
            case "SC" where f.count >= 2: if let sl = views[f[1]] as? UISlider { sliderDoneAction[sl] = f[1] + ":slidedone" }   // Slider onSlidingComplete
            case "SS" where f.count >= 2: if let sc = views[f[1]] as? UIScrollView { scrollOnScroll[sc] = f[1] + ":scroll" }   // Scroll onScroll
            case "IF" where f.count >= 3:   // Image GPU op-chain (JSON may contain '|', so rejoin)
                imageOpChain[f[1]] = f[2...].joined(separator: "|")
                if let iv = views[f[1]] as? UIImageView { applyOps(iv, f[1]) }
            case "LS" where f.count >= 3: scrollListTo(f[1], y: CGFloat(Int(f[2]) ?? 0))   // scrollToIndex/scrollToEnd
            case "I" where f.count >= 4: insert(f[1], parent: f[2], index: Int(f[3]) ?? 0)
            case "R" where f.count >= 2: remove(f[1])
            // LV|<id>: the engine names the LIVE list, i.e. the one on the screen that is
            // on top. Several screens are mounted at once, so "the most recently created
            // scroll view" is the wrong guess: the covered screen's list would receive the
            // viewport reports and scroll itself. An empty id means the top screen has no
            // list, and the viewport is the plain root.
            case "SK":
                // Which container holds the two screens a back gesture drags. Empty means
                // the app stack's root, which is what this assumed before a tab could own
                // its own history.
                stackHostId = f.count >= 2 ? f[1] : ""
            case "LV":
                let lid = f.count >= 2 ? f[1] : ""
                if lid.isEmpty { listScroll = nil; scrollId = ""; contentId = "" }
                else if let sc = views[lid] as? UIScrollView {
                    listScroll = sc; scrollId = lid; contentId = lid + ".0"
                    // From the recorded axis, not inferred from contentSize: at LV time the
                    // layout pass may not have sized this scroll yet, so the inference could
                    // read a horizontal list as vertical and report the wrong viewport.
                    listHoriz = horizScrollIds.contains(lid)
                }
            case "FA" where f.count >= 2: setFrameDriver(f[1] == "1")   // per-frame physics on/off
            case "X" where f.count >= 3:
                // Async host->engine command: X|token|capability|args. Run AFTER this
                // apply() finishes (main.async), so a sync capability's resolve() doesn't
                // re-enter apply() mid-parse. args may contain '|'.
                let token = f[1], cap = f[2]
                // The arguments are JSON, so a raw pipe inside them is safe here: they
                // are everything after the third one, joined back together.
                let raw = f.count >= 4 ? f[3...].joined(separator: "|") : ""
                DispatchQueue.main.async { [weak self] in
                    guard let self = self else { return }
                    // Parsed once, centrally, rather than by each capability. `args` is
                    // the single argument as a string, which is all a one-argument
                    // capability ever wanted; `a` reads the rest by name.
                    let a = ChuksArgs(raw, cap, token, self)
                    self.handleCommand(token, cap, a.str, a)
                }
            default: break
            }
        }
        syncChrome()
        finishThemeFade()
    }

    /// Dissolve the frozen old-theme frame, once the new one is fully in place.
    ///
    /// Deferred to the next runloop turn so the fade starts against a laid-out tree
    /// rather than a half-applied one; starting it inside the same batch shows the old
    /// frame dissolving into an unfinished layout, which looks worse than the hard cut
    /// it replaces.
    private func finishThemeFade() {
        guard let snap = themeFadeView else { return }
        themeFadeView = nil
        DispatchQueue.main.async { [weak self] in
            self?.relayout()
            UIView.animate(withDuration: 0.28, delay: 0, options: [.curveEaseInOut], animations: {
                snap.alpha = 0
            }, completion: { _ in snap.removeFromSuperview() })
        }
    }

    // Deliver a native capability result back to the engine and apply the re-render.
    // Like every other apply path, re-run Yoga layout after applying: a stream tick
    // that changes a label's width (a growing counter, live data) must re-measure
    // its node, or the frame lags the text and truncates it for a frame (the jank).
    /// ChuksModuleHost: a module presents from this controller, which IS the app's
    /// root view controller, the same one the framework's own capabilities present from.
    var presenter: UIViewController { self }

    /// ChuksModuleHost: a module's stream is torn down through the same map the
    /// framework's own streams use, so `__cancel__` releases both alike.
    func onCancel(_ token: String, _ teardown: @escaping () -> Void) { streamTeardown[token] = teardown }

    func resolve(_ token: String, _ payload: String) { if let s = eResolve(token, payload) { apply(s); relayout() } }
    // Report a capability failure back to the engine (fires the request's onErr).
    // Token "0" means the caller passed no callback, so the engine allocated nothing and
    // there is no closure anywhere to hand this to. The engine's own unhandled-failure
    // warning cannot reach these, because there is no token for it to fail: a
    // fire-and-forget capability has nowhere to report to BY CONSTRUCTION. The host is
    // the last place that still knows both the capability and the reason, so it says so
    // here rather than letting the failure evaporate.
    func fail(_ token: String, _ message: String) {
        if token == "0" {
            os_log("%{public}@", log: chuksLog, type: .error,
                   "chuks warning: \(dispatchingCap.isEmpty ? "a capability" : dispatchingCap) failed and nothing is listening: \"\(message)\". It was called without a callback, so nothing could be told.")
            return
        }
        if let s = eFail(token, message) { apply(s); relayout() }
    }
    // The capability currently being dispatched, so a failure can name itself.
    var dispatchingCap: String = ""

    // Live native subscriptions (stream token -> timer/observer), for teardown.
    var activeStreams: [String: Timer] = [:]
    var streamTeardown: [String: () -> Void] = [:]   // real OS streams: unregister closure, run on __cancel__
    var orientationTokens = Set<String>()   // orientation.watch tokens, so a lock can re-emit the new value
    var locFixes: [String: LocFix] = [:]    // live Location managers, keyed by token (once + watch)
    let motion = CMMotionManager()          // one shared motion manager; sensors fan out to token sets
    var accelTokens = Set<String>()
    var gyroTokens = Set<String>()
    var magTokens = Set<String>()
    // View kinds packages supply: the types by kind, and the live instances by node id.
    lazy var packageViewTypes: [String: ChuksNativeView.Type] = {
        var m: [String: ChuksNativeView.Type] = [:]
        for t in chuksPackageViews() { m[t.kind] = t }
        return m
    }()
    var packageViews: [String: ChuksNativeView] = [:]

    var mediaCoord: MediaCoordinator? = nil   // retains the picker/camera delegate while presented
    var urlTokens = Set<String>()             // linking.onurl subscribers
    var notifTokens = Set<String>()   // Notifications.onResponse subscribers
    var lastURL: String? = nil                // the deep link that opened the app (delivered to late subscribers)
    // A deep link arrived (launch or subsequent open): store it and emit to subscribers.
    func receiveURL(_ u: String) { lastURL = u; for t in urlTokens { resolve(t, u) } }
    // Audio: one ChuksAudioPlayer per Chuks AudioPlayer, watch tokens per player, and
    // the interruption observers installed on the first create. Capped, because a
    // player holds a decoder and a screen that forgets release() would otherwise find
    // out from the platform, somewhere less legible than a status string.
    var audioPlayers: [String: ChuksAudioPlayer] = [:]
    var audioWatchers: [String: Set<String>] = [:]      // player id -> stream tokens
    var audioInterruptionObs: [NSObjectProtocol] = []
    static let audioCap = 32
    var audioRecorder: AVAudioRecorder? = nil   // mic recording (Tier C)
    var cameraController: CameraController? = nil   // live CameraView session (for camera.capturePreview)
    var ble: BleManager? = nil          // CoreBluetooth central (lazy)
    var nfc: NfcReader? = nil           // CoreNFC reader (lazy)
    var recURL: URL? = nil
    let speech = AVSpeechSynthesizer()  // text-to-speech (Tier B)
    let pedometer = CMPedometer()       // step counter / distance / pace (Pedometer)
    let altimeter = CMAltimeter()       // barometer: pressure + relative altitude
    var proximityTokens = Set<String>() // motion.proximity subscribers (UIDevice proximity)

    // Execute a native capability requested via an `X|` command (F3). This host IS a
    // UIViewController, so capabilities that present UI (e.g. presentShare) present
    // directly.
    func ensureBle() {
        if ble == nil {
            let m = BleManager()
            m.onResolve = { [weak self] t, p in DispatchQueue.main.async { self?.resolve(t, p) } }
            m.onFail = { [weak self] t, msg in DispatchQueue.main.async { self?.fail(t, msg) } }
            ble = m
        }
    }
    func ensureNfc() {
        if nfc == nil {
            let n = NfcReader()
            n.onResolve = { [weak self] t, p in DispatchQueue.main.async { self?.resolve(t, p) } }
            n.onFail = { [weak self] t, msg in DispatchQueue.main.async { self?.fail(t, msg) } }
            nfc = n
        }
    }

    // Pedometer -> "steps,distanceMeters,paceSecPerMeter,cadenceStepsPerSec,floorsUp,floorsDown"
    static func pedStr(_ d: CMPedometerData) -> String {
        let steps = d.numberOfSteps.intValue
        let dist = d.distance?.doubleValue ?? 0
        let pace = d.currentPace?.doubleValue ?? 0          // seconds per meter
        let cadence = d.currentCadence?.doubleValue ?? 0    // steps per second
        let up = d.floorsAscended?.intValue ?? 0
        let down = d.floorsDescended?.intValue ?? 0
        return "\(steps),\(dist),\(pace),\(cadence),\(up),\(down)"
    }

    // The live view tree, for Debug.viewTree. Node ids are structural paths ("app.0.1"),
    // so sorting them puts a parent before its children and the dot count is the depth:
    // the tree draws itself without walking UIKit's hierarchy. The frame is the one Yoga
    // resolved, which is the number a layout question is actually about.
    let detachedMark = "\u{0000}detached"

    func viewTreeDump() -> String {
        // Asked before the first layout pass, every frame would read 0 and the dump
        // would call the whole tree ZERO-SIZED, which is a lie that looks exactly like
        // the bug this tool exists to find. Say what is actually true instead.
        guard let rootView = views["app"], rootView.frame.size.width > 0 else {
            return "(no frames yet: the first layout pass has not run, so nothing has a resolved size)"
        }
        // The app's own tree first. Ids sort as strings, and a recycled list cell is
        // named ".0.cellN", so a plain sort puts a pool of detached cells above the
        // screen you are looking at and fills the first screenful with them. They are
        // real views and worth seeing, so they follow under a heading rather than
        // being dropped.
        let rooted = views.keys.filter { $0 == "app" || $0.hasPrefix("app.") }.sorted()
        let detached = views.keys.filter { !($0 == "app" || $0.hasPrefix("app.")) }.sorted()
        var out = ""
        for id in rooted + (detached.isEmpty ? [] : [detachedMark]) + detached {
            if id == detachedMark {
                out += "-- not attached to the app root (recycled list cells, torn-down screens) --\n"
                continue
            }
            guard let v = views[id] else { continue }
            let depth = id.filter { $0 == "." }.count
            let pad = String(repeating: "  ", count: depth)
            let f = v.frame
            let cls = String(describing: type(of: v))
            var line = "\(pad)\(id)  \(cls)  \(Int(f.origin.x)),\(Int(f.origin.y)) \(Int(f.size.width))x\(Int(f.size.height))"
            if v.isHidden { line += "  hidden" }
            if f.size.width == 0 || f.size.height == 0 { line += "  ZERO-SIZED" }
            if let t = (v as? UILabel)?.text, !t.isEmpty {
                line += "  \"" + (t.count > 30 ? String(t.prefix(30)) + "…" : t) + "\""
            }
            out += line + "\n"
        }
        return out.isEmpty ? "(no views)" : out
    }

    func handleCommand(_ token: String, _ cap: String, _ args: String, _ a: ChuksArgs) {
        dispatchingCap = cap
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
            let emit = { [weak self] in self?.resolve(token, chuksBatteryNow()) }
            let o1 = NotificationCenter.default.addObserver(forName: UIDevice.batteryLevelDidChangeNotification, object: nil, queue: .main) { _ in emit() }
            let o2 = NotificationCenter.default.addObserver(forName: UIDevice.batteryStateDidChangeNotification, object: nil, queue: .main) { _ in emit() }
            // Low Power Mode is a battery fact too, and it changes on its own schedule.
            let o3 = NotificationCenter.default.addObserver(forName: .NSProcessInfoPowerStateDidChange, object: nil, queue: .main) { _ in emit() }
            streamTeardown[token] = { for o in [o1, o2, o3] { NotificationCenter.default.removeObserver(o) } }
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
                let s = chuksPathString(path)
                DispatchQueue.main.async { self?.resolve(token, s) }
            }
            mon.start(queue: DispatchQueue.global(qos: .utility))
            streamTeardown[token] = { mon.cancel() }

        // ---- One-shot reads of live OS state --------------------------------
        // The value the matching watch() would fire right now. Before these, reading one
        // value meant opening a stream and cancelling it, which is a teardown to forget.
        case "battery.current":
            resolve(token, chuksBatteryNow())
        case "battery.available":
            // The Simulator hands back a fixed placeholder rather than a reading, and
            // reports it as a negative level.
            UIDevice.current.isBatteryMonitoringEnabled = true
            resolve(token, UIDevice.current.batteryLevel >= 0 ? "1" : "0")
        case "appstate.current":
            let st = UIApplication.shared.applicationState
            resolve(token, st == .active ? "active" : st == .background ? "background" : "inactive")
        case "network.current":
            // NWPath has no reliable synchronous read before the monitor has started, so
            // take the first update and stop. It arrives in well under a frame.
            let one = NWPathMonitor()
            var answered = false
            one.pathUpdateHandler = { [weak self] path in
                if answered { return }
                answered = true
                let s = chuksPathString(path)
                DispatchQueue.main.async { self?.resolve(token, s); one.cancel() }
            }
            one.start(queue: DispatchQueue.global(qos: .utility))
        case "orientation.current":
            resolve(token, currentOrientationString())

        // ---- Availability -----------------------------------------------------
        // "Does this device have the hardware", asked before a feature is offered
        // rather than discovered from a stream that never fires.
        case "torch.available":
            resolve(token, (AVCaptureDevice.default(for: .video)?.hasTorch ?? false) ? "1" : "0")
        case "motion.available":
            resolve(token, chuksSensorAvailable(args, motion) ? "1" : "0")
        case "recorder.available":
            resolve(token, AVAudioSession.sharedInstance().isInputAvailable ? "1" : "0")
        case "tts.available":
            // A synthesizer with a voice for the device language. iOS always ships one,
            // but the check is what makes the question answerable on both platforms.
            let lang = AVSpeechSynthesisVoice.currentLanguageCode()
            resolve(token, AVSpeechSynthesisVoice(language: lang) != nil ? "1" : "0")
        case "ble.available":
            // Deliberately NOT a CoreBluetooth state read: constructing a
            // CBCentralManager is what triggers the Bluetooth permission prompt, and an
            // availability check that prompts is not an availability check. Every iOS
            // device the deployment target admits has a BLE radio; the Simulator has
            // none. ble.state still reports "unsupported" for anyone who wants the
            // radio's own answer.
            #if targetEnvironment(simulator)
            resolve(token, "0")
            #else
            resolve(token, "1")
            #endif
        case "location.once":
            let fix = LocFix(once: true,
                onFix: { [weak self] s in self?.resolve(token, s); self?.locFixes[token] = nil },
                onErr: { [weak self] m in self?.fail(token, m); self?.locFixes[token] = nil })
            locFixes[token] = fix; fix.start()
        case "location.watch", "location.watchBackground":
            let bg = (cap == "location.watchBackground")
            // The notification text in args is Android's business: iOS shows its own blue
            // indicator and gives an app no say in it.
            if bg && !(Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []).contains("location") {
                // Setting allowsBackgroundLocationUpdates without the mode CRASHES, so
                // refuse with a message that says what to add rather than taking the app
                // down at a line the developer never wrote.
                fail(token, "background location needs \"location\" in UIBackgroundModes: add \"backgroundLocation\": true to app.json")
                break
            }
            let fix = LocFix(once: false, background: bg,
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
        case "motion.proximity":
            // UIDevice proximity: a boolean near/far. Enable monitoring, observe the change
            // notification, and emit the current state immediately.
            proximityTokens.insert(token)
            UIDevice.current.isProximityMonitoringEnabled = true
            let o = NotificationCenter.default.addObserver(forName: UIDevice.proximityStateDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
                let s = UIDevice.current.proximityState ? "near" : "far"
                for t in self?.proximityTokens ?? [] { self?.resolve(t, s) }
            }
            resolve(token, UIDevice.current.proximityState ? "near" : "far")
            streamTeardown[token] = { [weak self] in
                self?.proximityTokens.remove(token)
                NotificationCenter.default.removeObserver(o)
                if self?.proximityTokens.isEmpty == true { UIDevice.current.isProximityMonitoringEnabled = false }
            }
        case "motion.light":
            // iOS exposes no public ambient-light API (only ARKit's private estimate).
            fail(token, "ambient light unavailable on iOS")
        case "motion.barometer":
            guard CMAltimeter.isRelativeAltitudeAvailable() else { fail(token, "barometer unavailable"); break }
            altimeter.startRelativeAltitudeUpdates(to: .main) { [weak self] data, _ in
                guard let d = data else { return }
                // pressure is kPa -> hPa (*10); relativeAltitude is meters from start.
                self?.resolve(token, "\(d.pressure.doubleValue * 10.0),\(d.relativeAltitude.doubleValue)")
            }
            streamTeardown[token] = { [weak self] in self?.altimeter.stopRelativeAltitudeUpdates() }
        case "pedometer.available":
            resolve(token, CMPedometer.isStepCountingAvailable() ? "true" : "false")
        case "pedometer.watch":
            guard CMPedometer.isStepCountingAvailable() else { fail(token, "step counting unavailable"); break }
            pedometer.startUpdates(from: Date()) { [weak self] data, err in
                DispatchQueue.main.async {
                    if let e = err { self?.fail(token, e.localizedDescription); return }
                    guard let d = data else { return }
                    self?.resolve(token, Self.pedStr(d))
                }
            }
            streamTeardown[token] = { [weak self] in self?.pedometer.stopUpdates() }
        case "pedometer.query":
            guard CMPedometer.isStepCountingAvailable() else { fail(token, "step counting unavailable"); break }
            guard let secs = a.num() else { break }
            let from = Date(timeIntervalSinceNow: -secs)
            pedometer.queryPedometerData(from: from, to: Date()) { [weak self] data, err in
                DispatchQueue.main.async {
                    if let e = err { self?.fail(token, e.localizedDescription); return }
                    guard let d = data else { self?.fail(token, "no pedometer data"); return }
                    let dist = d.distance?.doubleValue ?? 0
                    self?.resolve(token, "\(d.numberOfSteps.intValue),\(dist)")
                }
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
        case "deviceinfo.id":
            resolve(token, UIDevice.current.identifierForVendor?.uuidString ?? "")
        case "deviceinfo.appid":
            resolve(token, Bundle.main.bundleIdentifier ?? "")
        case "deviceinfo.appname":
            let name = (Bundle.main.infoDictionary?["CFBundleDisplayName"] as? String)
                ?? (Bundle.main.infoDictionary?["CFBundleName"] as? String) ?? ""
            resolve(token, name)
        case "deviceinfo.installtime":
            // iOS exposes no install-time API; the document directory's creation date is
            // the standard proxy (created at first launch, reset on reinstall).
            var ms = ""
            if let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first,
               let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
               let d = attrs[.creationDate] as? Date {
                ms = String(Int64(d.timeIntervalSince1970 * 1000))
            }
            resolve(token, ms)
        case "contacts.list":
            var contactsOK = CNContactStore.authorizationStatus(for: .contacts) == .authorized
            if #available(iOS 18.0, *) { contactsOK = contactsOK || CNContactStore.authorizationStatus(for: .contacts) == .limited }
            guard contactsOK else { fail(token, "contacts permission denied"); break }
            let store = CNContactStore()
            DispatchQueue.global(qos: .userInitiated).async {
                let keys = [CNContactGivenNameKey, CNContactFamilyNameKey, CNContactPhoneNumbersKey, CNContactEmailAddressesKey] as [CNKeyDescriptor]
                var lines: [String] = []
                do {
                    try store.enumerateContacts(with: CNContactFetchRequest(keysToFetch: keys)) { c, _ in
                        let name = "\(c.givenName) \(c.familyName)".trimmingCharacters(in: .whitespaces)
                        let phones = c.phoneNumbers.map { $0.value.stringValue }.joined(separator: ";")
                        let emails = c.emailAddresses.map { String($0.value) }.joined(separator: ";")
                        lines.append("\(name)\t\(phones)\t\(emails)")
                    }
                    DispatchQueue.main.async { self.resolve(token, lines.joined(separator: "\n")) }
                } catch { DispatchQueue.main.async { self.fail(token, "read failed") } }
            }
        case "calendar.upcoming":
            guard let days = a.num() else { break }
            let store = EKEventStore()
            let pred = store.predicateForEvents(withStart: Date(), end: Date(timeIntervalSinceNow: days * 86400), calendars: nil)
            let lines = store.events(matching: pred).sorted { $0.startDate < $1.startDate }.map {
                "\($0.title ?? "")\t\(Int($0.startDate.timeIntervalSince1970 * 1000))\t\(Int($0.endDate.timeIntervalSince1970 * 1000))"
            }
            resolve(token, lines.joined(separator: "\n"))
        case "calendar.create":
            guard let startMin = a.num("startInMin"), let durMin = a.num("durationMin") else { break }
            let store = EKEventStore()
            guard let cal = store.defaultCalendarForNewEvents else { fail(token, "no writable calendar"); break }
            let ev = EKEvent(eventStore: store)
            ev.title = a.s("title"); ev.calendar = cal
            ev.startDate = Date(timeIntervalSinceNow: startMin * 60)
            ev.endDate = Date(timeIntervalSinceNow: startMin * 60 + durMin * 60)
            do { try store.save(ev, span: .thisEvent); resolve(token, ev.eventIdentifier ?? "ok") }
            catch { fail(token, "save failed: \(error.localizedDescription)") }
        case "linking.opensettings":
            if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
        case "bg.define":
            // args is the task name. Keep the token so a wake can fire this handler, and
            // release it if the app ever cancels the subscription.
            bgTokens[args] = token
            streamTeardown[token] = { [weak self] in self?.bgTokens[args] = nil }
        case "bg.result":
            // name, then "1" or "0": the handler finished, so the OS can be told.
            finishBackgroundTask(a.s("name"), success: a.bool("ok"))
        case "bg.periodic", "bg.once", "bg.processing":
            // Same three arguments whichever of the three schedulers this is.
            guard let secs = a.int("seconds") else { break }
            var cons: [String: String] = [:]
            for pair in a.s("constraints").components(separatedBy: ";") where !pair.isEmpty {
                let kv = pair.components(separatedBy: "=")
                if kv.count == 2 { cons[kv[0]] = kv[1] }
            }
            // iOS has no periodic scheduler: a refresh task is submitted again after each
            // run, which is what BGTaskScheduler expects you to do anyway. `periodic`
            // therefore means the same thing here, it is just re-armed rather than
            // repeating on its own.
            scheduleBackgroundTask(a.s("name"), secs, cap == "bg.processing" ? "processing" : "refresh", cons)
        case "bg.cancel":
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: args)
        case "bg.cancelAll":
            BGTaskScheduler.shared.cancelAllTaskRequests()
        case "bg.status":
            // Whether the user or the system has switched background activity off matters
            // more than whether a request is pending: when it is off, nothing will run.
            let refreshOff = UIApplication.shared.backgroundRefreshStatus != .available
            if refreshOff { resolve(token, "restricted"); break }
            BGTaskScheduler.shared.getPendingTaskRequests { [weak self] reqs in
                let pending = reqs.contains { $0.identifier == args }
                DispatchQueue.main.async { self?.resolve(token, pending ? "scheduled" : "notScheduled") }
            }
        case "linking.onurl":
            urlTokens.insert(token)
            streamTeardown[token] = { [weak self] in self?.urlTokens.remove(token) }
            if let u = lastURL { resolve(token, u) }   // deliver the launch URL to a late subscriber
        case "mediapicker.image":
            let coord = MediaCoordinator(done: { [weak self] p in self?.mediaCoord = nil; self?.resolve(token, p) },
                                         cancel: { [weak self] m in self?.mediaCoord = nil; self?.fail(token, m) })
            mediaCoord = coord
            var cfg = PHPickerConfiguration(); cfg.filter = .images; cfg.selectionLimit = 1
            let pk = PHPickerViewController(configuration: cfg); pk.delegate = coord
            present(pk, animated: true)
        case "camera.photo":
            if !UIImagePickerController.isSourceTypeAvailable(.camera) { fail(token, "camera unavailable"); break }
            let coord = MediaCoordinator(done: { [weak self] p in self?.mediaCoord = nil; self?.resolve(token, p) },
                                         cancel: { [weak self] m in self?.mediaCoord = nil; self?.fail(token, m) })
            mediaCoord = coord
            let pk = UIImagePickerController(); pk.sourceType = .camera; pk.delegate = coord
            present(pk, animated: true)
        case "mediapicker.save":
            let path = args.hasPrefix("file://") ? String(args.dropFirst(7)) : args
            guard let img = UIImage(contentsOfFile: path) else { fail(token, "no such image"); break }
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { [weak self] status in
                guard status == .authorized || status == .limited else { DispatchQueue.main.async { self?.fail(token, "photos permission denied") }; return }
                PHPhotoLibrary.shared().performChanges({ PHAssetChangeRequest.creationRequestForAsset(from: img) },
                    completionHandler: { ok, err in DispatchQueue.main.async { ok ? self?.resolve(token, "ok") : self?.fail(token, err?.localizedDescription ?? "save failed") } })
            }
        case "camera.capturePreview":
            guard let ctrl = cameraController else { fail(token, "no CameraView on screen"); break }
            ctrl.capture(ok: { [weak self] p in self?.resolve(token, p) }, err: { [weak self] m in self?.fail(token, m) })
        case "ble.state":
            ensureBle(); ble!.stateToken = token; ble!.emitState()
            streamTeardown[token] = { [weak self] in self?.ble?.stateToken = nil }
        case "ble.scan":
            ensureBle(); ble!.startScan(token)
            streamTeardown[token] = { [weak self] in self?.ble?.stopScan() }
        case "ble.connect":
            ensureBle(); ble!.connect(args, ok: { [weak self] p in self?.resolve(token, p) }, err: { [weak self] m in self?.fail(token, m) })
        case "ble.disconnect":
            ble?.disconnect(args)
        case "ble.read":
            ensureBle(); ble!.read(a.s("deviceId"), a.s("service"), a.s("characteristic"), ok: { [weak self] p in self?.resolve(token, p) }, err: { [weak self] m in self?.fail(token, m) })
        case "ble.write":
            ensureBle(); ble!.write(a.s("deviceId"), a.s("service"), a.s("characteristic"), a.s("valueHex"), ok: { [weak self] p in self?.resolve(token, p) }, err: { [weak self] m in self?.fail(token, m) })
        case "ble.subscribe":
            ensureBle()
            ble!.subscribe(a.s("deviceId"), a.s("service"), a.s("characteristic"), token: token, err: { [weak self] m in self?.fail(token, m) })
            streamTeardown[token] = { [weak self] in self?.ble?.unsubscribe(token) }
        case "nfc.available":
            ensureNfc(); resolve(token, nfc!.available ? "1" : "0")
        case "nfc.read":
            ensureNfc(); nfc!.read(token)
        case "nfc.write":
            ensureNfc(); nfc!.write(args, token)
        case "biometrics.available":
            resolve(token, LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) ? "1" : "0")
        // The two halves of "available", asked separately. LAContext answers both
        // through one call and its error code: notAvailable is no sensor,
        // notEnrolled is a sensor with nothing on it, lockout is enrolled but
        // temporarily refused (still hardware, still enrolled).
        case "biometrics.hardware":
            var e: NSError?
            let ok = LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &e)
            let has = ok || (e.map { LAError.Code(rawValue: $0.code) != .biometryNotAvailable } ?? false)
            resolve(token, has ? "1" : "0")
        case "biometrics.enrolled":
            var e: NSError?
            let ok = LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &e)
            let enrolled = ok || (e.map { LAError.Code(rawValue: $0.code) == .biometryLockout } ?? false)
            resolve(token, enrolled ? "1" : "0")
        case "biometrics.types":
            // biometryType is only populated after canEvaluatePolicy has run.
            let ctx = LAContext()
            _ = ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
            switch ctx.biometryType {
            case .faceID:  resolve(token, "face")
            case .touchID: resolve(token, "fingerprint")
            default:       resolve(token, "")
            }
        case "biometrics.level":
            // iOS has no weak biometric: Face ID and Touch ID are both strong. Below
            // that is the passcode, and below that nothing.
            let ctx = LAContext()
            if ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) {
                resolve(token, "strong")
            } else if ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) {
                resolve(token, "secret")
            } else {
                resolve(token, "none")
            }
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
        case "debug.viewTree": resolve(token, viewTreeDump())
        case "debug.fail": fail(token, "simulated native failure")
        case "permission.status": permStatus(args, token)
        case "permission.request": permRequest(args, token)
        case "fs.write":
            // The content arrives as an ordinary field: the wire packs arguments, so a
            // multi-line body needs no encoding of its own any more.
            try? a.s("content").write(to: appFile(a.s("name")), atomically: true, encoding: .utf8)
        case "fs.read":
            if let s = try? String(contentsOf: appFile(args), encoding: .utf8) { resolve(token, s) }
            else { fail(token, "no such file: \(args)") }
        case "fs.list":
            let names = (try? FileManager.default.contentsOfDirectory(atPath: appDir().path)) ?? []
            resolve(token, names.joined(separator: "\n"))
        case "fs.delete": try? FileManager.default.removeItem(at: appFile(args))
        case "secure.set":
            keychainSet(a.s("key"), a.s("value"))
        case "secure.get":
            if let v = keychainGet(args) { resolve(token, v) } else { fail(token, "no such key: \(args)") }
        case "secure.delete": keychainDelete(args)
        case "notif.notify", "notif.schedule":
            let content = UNMutableNotificationContent()
            content.title = a.s("title"); content.body = a.s("body")
            content.sound = .default
            content.userInfo = ["data": a.s("data")]
            let id = a.s("id").isEmpty ? UUID().uuidString : a.s("id")
            var trigger: UNNotificationTrigger? = nil
            if cap == "notif.schedule" {
                let inSeconds = a.num("inSeconds") ?? 0
                let atMs = a.num("atMs") ?? 0
                if atMs > 0 {
                    // A calendar trigger for an absolute time. Second precision is all
                    // the OS offers; a date already past fires at once.
                    let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second],
                                                                from: Date(timeIntervalSince1970: atMs / 1000))
                    trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
                } else {
                    // The interval trigger refuses 0; a non-positive delay means "now".
                    trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, inSeconds), repeats: false)
                }
            }
            // The same id replaces the earlier request, pending or delivered, which is
            // how a notification updates in place.
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
        case "notif.cancel":
            UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [args])
        case "notif.cancelAll":
            UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
        case "notif.dismiss":
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [args])
        case "notif.dismissAll":
            UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        case "notif.scheduled":
            UNUserNotificationCenter.current().getPendingNotificationRequests { [weak self] reqs in
                let lines = reqs.map { "\($0.identifier)\t\($0.content.title)\t\(chuksNotifFireAt($0))" }
                DispatchQueue.main.async { self?.resolve(token, lines.joined(separator: "\n")) }
            }
        case "notif.setBadge":
            let n = Int(a.num() ?? 0)
            if #available(iOS 16.0, *) { UNUserNotificationCenter.current().setBadgeCount(n) }
            else { UIApplication.shared.applicationIconBadgeNumber = n }
        case "notif.badge":
            resolve(token, String(UIApplication.shared.applicationIconBadgeNumber))
        case "notif.onResponse":
            // Every subscriber hears every tap; the delegate has one hook, which fans
            // out over the token set. The launch tap, if any, goes to the first one.
            notifTokens.insert(token)
            notifDelegate.onResponse = { [weak self] p in for t in self?.notifTokens ?? [] { self?.resolve(t, p) } }
            streamTeardown[token] = { [weak self] in
                self?.notifTokens.remove(token)
                if self?.notifTokens.isEmpty == true { notifDelegate.onResponse = nil }
            }
            if let p = notifDelegate.pending { notifDelegate.pending = nil; resolve(token, p) }
        case "notif.channel":
            break   // Android's concept; iOS has no channels
        case "audio.mode":
            // How this app's sound sits with everyone else's. Set before the first player.
            let session = AVAudioSession.sharedInstance()
            switch args {
            case "ambient": try? session.setCategory(.ambient, options: [.mixWithOthers])
            case "duck":    try? session.setCategory(.playback, options: [.duckOthers])
            default:        try? session.setCategory(.playback)
            }
            try? session.setActive(true)
        case "audio.create":
            let id = a.s("id"), src = a.s("src")
            if audioPlayers.count >= CardsVC.audioCap {
                // Answer through the status the watcher will read, not a crash.
                let msg = "too many players (\(CardsVC.audioCap)); release() the ones you are done with"
                for t in audioWatchers[id] ?? [] { resolve(t, "error,0,0,1,1,0,\(msg)") }
                os_log("%{public}@", log: chuksLog, type: .error, "Audio: " + msg)
                break
            }
            let url: URL?
            if src.hasPrefix("file://") { url = URL(fileURLWithPath: String(src.dropFirst(7))) }
            else if src.hasPrefix("http://") || src.hasPrefix("https://") { url = URL(string: src) }
            else { url = bundledAssetURL(src) }
            guard let u = url else {
                for t in audioWatchers[id] ?? [] { resolve(t, "error,0,0,1,1,0,\"\(src)\" is not a file, a URL, or a bundled asset") }
                break
            }
            if audioInterruptionObs.isEmpty { installAudioInterruptionHandling() }
            if AVAudioSession.sharedInstance().category == .soloAmbient {
                // The default category never activates; give playback a real one so
                // the silent switch does not silence a player nobody asked to be silent.
                try? AVAudioSession.sharedInstance().setCategory(.playback)
            }
            try? AVAudioSession.sharedInstance().setActive(true)
            audioPlayers[id]?.release()
            let p = ChuksAudioPlayer(id: id, url: u)
            p.emit = { [weak self] st in for t in self?.audioWatchers[id] ?? [] { self?.resolve(t, st) } }
            audioPlayers[id] = p
        case "audio.play":    audioPlayers[a.s("id")]?.play()
        case "audio.pause":   audioPlayers[a.s("id")]?.pause()
        case "audio.stop":    audioPlayers[a.s("id")]?.stop()
        case "audio.seek":    audioPlayers[a.s("id")]?.seek(ms: Int64(a.num("ms") ?? 0))
        case "audio.volume":  audioPlayers[a.s("id")]?.setVolume(Float(a.num("v") ?? 1))
        case "audio.rate":    audioPlayers[a.s("id")]?.setRate(Float(a.num("r") ?? 1))
        case "audio.loop":    audioPlayers[a.s("id")]?.loop = a.bool("on")
        case "audio.status":
            resolve(token, audioPlayers[a.s("id")]?.status() ?? "error,0,0,1,1,0,no such player (released, or never created)")
        case "audio.watch":
            let id = a.s("id")
            audioWatchers[id, default: []].insert(token)
            streamTeardown[token] = { [weak self] in self?.audioWatchers[id]?.remove(token) }
            if let p = audioPlayers[id] { resolve(token, p.status()) }
        case "audio.release":
            let id = a.s("id")
            audioPlayers[id]?.release()
            audioPlayers[id] = nil
            audioWatchers[id] = nil
        case "recorder.start":
            guard AVAudioSession.sharedInstance().recordPermission == .granted else { fail(token, "microphone permission denied"); break }
            let url = appDir().appendingPathComponent("rec-\(UUID().uuidString).m4a")
            let settings: [String: Any] = [AVFormatIDKey: Int(kAudioFormatMPEG4AAC), AVSampleRateKey: 44100,
                                           AVNumberOfChannelsKey: 1, AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue]
            do {
                try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default)
                try AVAudioSession.sharedInstance().setActive(true)
                let r = try AVAudioRecorder(url: url, settings: settings)
                r.isMeteringEnabled = true; r.record()
                audioRecorder = r; recURL = url
            } catch { fail(token, "record failed: \(error.localizedDescription)") }
        case "recorder.stop":
            guard let r = audioRecorder, let url = recURL else { fail(token, "not recording"); break }
            r.stop(); audioRecorder = nil
            try? AVAudioSession.sharedInstance().setActive(false)
            resolve(token, "file://" + url.path)
        case "recorder.levels":
            let t = Timer.scheduledTimer(withTimeInterval: 0.08, repeats: true) { [weak self] _ in
                guard let r = self?.audioRecorder else { return }
                r.updateMeters()
                let lin = pow(10, r.averagePower(forChannel: 0) / 20)   // dB (-160..0) -> linear 0..1
                self?.resolve(token, String(format: "%.3f", max(0, min(1, lin))))
            }
            activeStreams[token] = t
        case "tts.speak":
            try? AVAudioSession.sharedInstance().setCategory(.playback)
            try? AVAudioSession.sharedInstance().setActive(true)
            if speech.isSpeaking { speech.stopSpeaking(at: .immediate) }
            speech.speak(AVSpeechUtterance(string: args))
        case "tts.stop": speech.stopSpeaking(at: .immediate)
        case "tts.isSpeaking": resolve(token, speech.isSpeaking ? "1" : "0")
        // The framework noticed something the app probably did not mean. Logged
        // natively because the engine's own println reaches neither the device console
        // nor idevicesyslog.
        case "dev.warn": os_log("%{public}@", log: chuksLog, type: .error, "chuks warning: " + args)
        // Which appearance the APP is in, which is not the same question as which
        // appearance the phone is in. Every adaptive thing UIKit draws resolves against
        // the system trait unless told otherwise, so an app switched to its own light
        // theme on a phone left in dark mode got the DARK glass material: a dark slab in
        // a light app. Overriding on the window covers glass, blur and anything else
        // adaptive in one place rather than per view.
        case "appearance.set":
            // Freeze the CURRENT frame on top before anything changes, and dissolve it
            // once the whole batch has landed. A theme switch is not one property
            // animating, it is every colour in the tree replaced at once, plus every
            // glass surface torn down and rebuilt to pick up the new trait: nothing in
            // that is individually animatable, and done bare it reads as a hard cut with
            // a flash where the materials pop back. One crossfade over the lot is both
            // smoother and cheaper than trying to tween the parts.
            if themeFadeView == nil, let snap = view.snapshotView(afterScreenUpdates: false) {
                snap.frame = view.bounds
                snap.isUserInteractionEnabled = false
                snap.tag = CHUKS_DECOR_TAG
                view.addSubview(snap)
                themeFadeView = snap
            }
            let style: UIUserInterfaceStyle = args == "light" ? .light : (args == "dark" ? .dark : .unspecified)
            view.window?.overrideUserInterfaceStyle = style
            view.overrideUserInterfaceStyle = style
            // A live material keeps the trait it was built with, so every glass surface
            // needs rebuilding. Nothing is cleared here: activateGlass compares each
            // surface against the CURRENT trait on every layout pass and rebuilds what no
            // longer matches, which is why this only has to ask for a layout. Next runloop
            // turn, so the trait set just above has actually propagated.
            DispatchQueue.main.async { [weak self] in self?.relayout() }
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
        case "haptics.vibrate": if let ms = a.int() { hapticBuzz(ms) }
        case "haptics.pattern": hapticPattern(args)
        case "torch.set": setTorch(args == "1")
        case "brightness.set": if let v = a.num() { UIScreen.main.brightness = CGFloat(max(0, min(1, v))) }
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
        default:
            // Not a framework capability. An installed package may claim this namespace.
            // If none does the command is unknown, and saying nothing was the worst
            // answer available: a misspelt capability, one a package forgot to declare,
            // and one that simply does not exist on this platform all behaved like a
            // call that quietly worked. Route it through fail(), which reaches the app
            // when somebody is listening and the log when nobody is.
            if !packageModules.handle(token, cap, args, a) {
                fail(token, "no capability named \(cap) on iOS. Check the spelling, or whether the package that provides it declares this platform.")
            }
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

    // A phone call or Siri pauses every player and, when the OS says it is over and
    // asks us to, resumes the ones that were playing. Headphones being unplugged
    // pauses and does not resume, because that is what the user expects.
    private func installAudioInterruptionHandling() {
        let nc = NotificationCenter.default
        audioInterruptionObs.append(nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
            guard let self = self,
                  let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            switch type {
            case .began:
                for p in self.audioPlayers.values {
                    p.wasPlayingBeforeInterruption = p.state == "playing"
                    if p.wasPlayingBeforeInterruption { p.pause() }
                }
            case .ended:
                let opts = AVAudioSession.InterruptionOptions(rawValue: n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
                if opts.contains(.shouldResume) {
                    try? AVAudioSession.sharedInstance().setActive(true)
                    for p in self.audioPlayers.values where p.wasPlayingBeforeInterruption { p.play() }
                }
                for p in self.audioPlayers.values { p.wasPlayingBeforeInterruption = false }
            @unknown default: break
            }
        })
        audioInterruptionObs.append(nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] n in
            guard let raw = n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable else { return }
            guard let self = self else { return }
            for p in self.audioPlayers.values { p.pause() }
        })
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
        case "contacts": resolve(token, cnAuthStr(CNContactStore.authorizationStatus(for: .contacts)))
        case "calendar": resolve(token, ekAuthStr(EKEventStore.authorizationStatus(for: .event)))
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
        case "contacts": CNContactStore().requestAccess(for: .contacts) { g, _ in DispatchQueue.main.async { self.resolve(token, g ? "granted" : "denied") } }
        case "calendar":
            let ek = EKEventStore()
            if #available(iOS 17.0, *) { ek.requestFullAccessToEvents { g, _ in DispatchQueue.main.async { self.resolve(token, g ? "granted" : "denied") } } }
            else { ek.requestAccess(to: .event) { g, _ in DispatchQueue.main.async { self.resolve(token, g ? "granted" : "denied") } } }
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

    // A controlled TextInput's value. Set the native text ONLY when it differs, so
    // re-emitting the same value (every keystroke, since the field is controlled) does
    // not move the cursor. Clearing (value = "") resets the field.
    func setFieldValue(_ id: String, _ value: String) {
        if let tf = views[id] as? UITextField { if tf.text != value { tf.text = value } }
        else if let tv = views[id] as? UITextView { if tv.text != value { tv.text = value } }
    }

    // List scrollToIndex/scrollToEnd: scroll the list's UIScrollView to content-offset y,
    // clamped to the scrollable range. Deferred to the next runloop so the content-size /
    // layout emitted in the same batch is applied before we scroll (otherwise maxOffset is
    // stale). NOT animated on purpose: a big jump lands in an unmounted region, and the
    // scrollViewDidScroll -> mount -> relayout that follows would cancel an in-flight animated
    // scroll partway (a small already-mounted jump survives, a large one aborts near the top).
    func scrollListTo(_ id: String, y: CGFloat) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self, let sc = self.views[id] as? UIScrollView else { return }
            if self.listHoriz && sc == self.listScroll {   // horizontal list: y is really the x offset
                let maxX = max(0, sc.contentSize.width - sc.bounds.width)
                sc.setContentOffset(CGPoint(x: min(max(0, y), maxX), y: 0), animated: false)
            } else {
                let maxY = max(0, sc.contentSize.height - sc.bounds.height)
                sc.setContentOffset(CGPoint(x: 0, y: min(max(0, y), maxY)), animated: false)
            }
        }
    }

    func setText(_ id: String, _ t: String) {
        // A package view's "text" is its props, as JSON. Layout and background have
        // already been applied by the framework; this is the package's own half.
        if let pv = packageViews[id] {
            pv.apply(ChuksArgs(t, type(of: pv).kind, "0", self))
            return
        }
        if gestureIds.contains(id) {   // a Gesture's "text" is its continuous-recognizer list ("pan,pinch,rotate")
            attachContinuousGestures(id, t)
            return
        }
        if let vv = views[id] as? VideoView {   // a Video's "text" is an optional poster URL shown until the first frame
            if !t.isEmpty && videoPosters[id] == nil {
                let iv = UIImageView(); iv.contentMode = .scaleAspectFill; iv.clipsToBounds = true
                iv.frame = vv.bounds; iv.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                vv.addSubview(iv); videoPosters[id] = iv
                loadRemoteImage(t, into: iv)
            }
            return
        }
        if selectIds.contains(id) {                                  // a Select's "text" is its tab-joined options
            selectOptions[id] = t.components(separatedBy: "\t")
            rebuildSelectMenu(id)
            return
        }
        if menuIds.contains(id) { menuData[id] = t.components(separatedBy: "\t"); rebuildMenu(id); return }   // [label, items...]
        if contextMenuIds.contains(id) { contextMenuData[id] = t.components(separatedBy: "\t"); return }        // items
        if alertIds.contains(id) { alertData[id] = t.components(separatedBy: "\t"); return }   // Alert's tab-joined fields
        if let iv = bgImageViews[id], t.hasPrefix("http") { loadRemoteImage(t, into: iv, id: id); return }   // ImageBackground URL
        if let iv = views[id] as? UIImageView, t.hasPrefix("http") { loadRemoteImage(t, into: iv, id: id); return }   // remote Image URL
        if let iv = views[id] as? UIImageView, t.hasPrefix("file://") {   // picked/captured local file
            imageSrc[id] = t; imageDecodedDim[id] = nil
            ensureSizedImage(id, UIScreen.main.bounds.width * UIScreen.main.scale)   // provisional; relayout() refines to the frame
            fireMedia(iv.image != nil ? mediaLoad[id] : mediaError[id]); return
        }
        if let iv = views[id] as? UIImageView, !t.isEmpty {   // bundled local asset (e.g. chuks-logo.png)
            imageSrc[id] = t; imageDecodedDim[id] = nil
            ensureSizedImage(id, UIScreen.main.bounds.width * UIScreen.main.scale)
            fireMedia(iv.image != nil ? mediaLoad[id] : mediaError[id]); return
        }
        if bgImageViews[id] != nil, !t.isEmpty {              // bundled ImageBackground asset
            imageSrc[id] = t; imageDecodedDim[id] = nil
            ensureSizedImage(id, UIScreen.main.bounds.width * UIScreen.main.scale)
            return
        }
        if let cv = views[id] as? CameraPreviewUIView { cv.controller?.configure(t.isEmpty ? "back" : t); return }   // CameraView facing
        if let wv = views[id] as? WKWebView { if let u = URL(string: t) { wv.load(URLRequest(url: u)) }; return }   // WebView URL
        if let cv = views[id] as? CanvasView { cv.shapes = t; return }                // Canvas's "text" is the shape list
        if let mv = views[id] as? MKMapView {                                        // Map's "text" is "lat,lng,zoom" or "lat,lng,zoom|route"
            mv.delegate = self                                                       // for the polyline renderer
            let parts = t.components(separatedBy: "|")
            let p = parts[0].components(separatedBy: ",")
            mv.removeAnnotations(mv.annotations)
            mv.removeOverlays(mv.overlays)
            // Route polyline: "p1lat,p1lng p2lat,p2lng ...". Draw it and fit the map to it.
            if parts.count >= 2, !parts[1].isEmpty {
                var coords: [CLLocationCoordinate2D] = []
                for pt in parts[1].split(separator: " ") {
                    let ll = pt.split(separator: ",")
                    if ll.count == 2, let la = Double(ll[0]), let lo = Double(ll[1]) {
                        coords.append(CLLocationCoordinate2D(latitude: la, longitude: lo))
                    }
                }
                if coords.count >= 2 {
                    let line = MKPolyline(coordinates: coords, count: coords.count)
                    mv.addOverlay(line)
                    // start (green) + end (checkered) markers
                    let start = MKPointAnnotation(); start.coordinate = coords.first!; start.title = "Start"
                    let end = MKPointAnnotation(); end.coordinate = coords.last!; end.title = "Finish"
                    mv.addAnnotations([start, end])
                    // fit to the route with padding
                    mv.setVisibleMapRect(line.boundingMapRect, edgePadding: UIEdgeInsets(top: 30, left: 30, bottom: 30, right: 30), animated: false)
                    return
                }
            }
            if p.count == 3, let lat = Double(p[0]), let lng = Double(p[1]), let z = Double(p[2]) {
                let span = 360.0 / pow(2.0, z)                                        // degrees visible at this zoom
                let region = MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: lat, longitude: lng),
                                                span: MKCoordinateSpan(latitudeDelta: span, longitudeDelta: span))
                mv.setRegion(region, animated: false)
                let pin = MKPointAnnotation(); pin.coordinate = CLLocationCoordinate2D(latitude: lat, longitude: lng)
                mv.addAnnotation(pin)
            }
            return
        }
        if let dp = views[id] as? UIDatePicker {                                     // DatePicker's "text" is its ISO value
            if let d = Self.parseISO(t, mode: datePickerModes[dp] ?? "date") { dp.date = d }
            return
        }
        if views[id] is UILabel { labelRaw[id] = t; refreshLabel(id) }
        else if let b = views[id] as? UIButton { b.setTitle(t, for: .normal) }
        else if let tf = views[id] as? UITextField { tf.placeholder = t }
        else if let tv = views[id] as? UITextView { textAreaPlaceholders[tv]?.text = t }   // TextArea placeholder
    }

    // Rebuild a label's rendered text from its raw string + font + typography. Called on
    // every text OR style change so `l.font`/`l.textColor` (set by style()) and the raw
    // text (set by setText) always compose. Plain path when no attributed attrs are set.
    func refreshLabel(_ id: String) {
        guard let l = views[id] as? UILabel else { return }
        var text = labelRaw[id] ?? l.text ?? ""
        switch labelTransform[id] {                       // text-transform
        case "upper": text = text.uppercased()
        case "lower": text = text.lowercased()
        case "cap":   text = text.capitalized
        default: break
        }
        let deco = labelDeco[id], kern = labelKern[id], lead = labelLead[id]
        if deco == nil && kern == nil && lead == nil {
            l.text = text                                  // no attributed attrs needed
        } else {
            var attrs: [NSAttributedString.Key: Any] = [.font: l.font as Any]
            if let c = l.textColor { attrs[.foregroundColor] = c }
            if let k = kern { attrs[.kern] = k }            // tracking
            if deco == "underline" { attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue }
            if deco == "strike" { attrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
            if let ld = lead {                              // leading (target line height)
                let ps = NSMutableParagraphStyle()
                ps.minimumLineHeight = ld; ps.maximumLineHeight = ld
                ps.alignment = l.textAlignment
                attrs[.paragraphStyle] = ps
            }
            l.attributedText = NSAttributedString(string: text, attributes: attrs)
        }
        if let n = ynodes[id] { YGNodeMarkDirty(n) }
    }

    func make(_ id: String, _ kind: String) {
        if views[id] != nil { return }
        let v: UIView
        let n = YGNodeNewWithConfig(config)
        switch kind {
        case "Text":
            let l = SelectableLabel(); l.font = .systemFont(ofSize: 14); l.numberOfLines = 0; v = l   // 0 = wrap to as many lines as fit the measured (Yoga) width
            YGNodeSetContext(n, Unmanaged.passUnretained(l).toOpaque())
            YGNodeSetMeasureFunc(n, measureText)
        case "Image":
            let iv = UIImageView(); iv.contentMode = .scaleAspectFit; iv.clipsToBounds = true; v = iv
        case "ImageBackground":
            let box = UIView(); box.clipsToBounds = true
            let iv = UIImageView(); iv.contentMode = .scaleAspectFill; iv.clipsToBounds = true
            iv.frame = box.bounds; iv.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            iv.tag = CHUKS_DECOR_TAG
            box.addSubview(iv)                              // background, behind children (inserted later)
            bgImageViews[id] = iv; v = box
        case "Video":
            let vv = VideoView(); vv.playerLayer.videoGravity = .resizeAspectFill; vv.clipsToBounds = true; v = vv
        case "VideoControls":
            // Apple's complete native player UI (transport, scrubber, fullscreen, AirPlay, PiP,
            // subtitle/audio menus) via AVPlayerViewController — added as a child VC of the host.
            let vc = AVPlayerViewController(); vc.showsPlaybackControls = true
            vc.videoGravity = .resizeAspectFill   // cover (default resizeMode), no letterbox bars
            vc.view.backgroundColor = .black; vc.view.clipsToBounds = true
            addChild(vc); vc.didMove(toParent: self)
            videoCtrlVCs[id] = vc; v = vc.view
        case "CameraView":
            let ctrl = cameraController ?? CameraController()
            cameraController = ctrl
            ctrl.configure("back")                    // facing (node text) refined in setText
            ctrl.previewView.clipsToBounds = true; v = ctrl.previewView
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
            sl.addTarget(self, action: #selector(sliderDone(_:)), for: [.touchUpInside, .touchUpOutside])
            v = sl
        case "DatePicker", "DatePickerInline":   // same UIDatePicker; the "dpd" style picks compact/inline/wheels
            let dp = UIDatePicker()
            if #available(iOS 14.0, *) { dp.preferredDatePickerStyle = .compact }   // a tappable native field
            dp.datePickerMode = .date                                               // refined by the "dp" style
            dp.contentHorizontalAlignment = .leading                                // hug the leading edge (match Android)
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
        case "Scroll", "HScroll":   // one UIScrollView for both axes; the `horiz` style picks x vs y
            let sc = UIScrollView(); sc.delegate = self; sc.keyboardDismissMode = .onDrag
            sc.showsVerticalScrollIndicator = true
            // Mark the Yoga node as a scroll container so its content is laid out at its full
            // (overflowing) size along the scroll axis instead of being clamped to the scroll's
            // own bounds. Without this, the content's top/bottom become unreachable.
            YGNodeStyleSetOverflow(n, YGOverflow.scroll)
            // NB: creation does NOT make this the live list any more -- the engine says
            // which one is live with LV|, since several screens can be mounted at once.
            if kind == "HScroll" { horizScrollIds.insert(id) } else { horizScrollIds.remove(id) }
            if listScroll == nil { listScroll = sc; scrollId = id; listHoriz = (kind == "HScroll") }
            v = sc
        case "Modal":
            let mv = UIView(); mv.backgroundColor = UIColor(white: 0, alpha: 0.5)   // scrim
            mv.isHidden = true                     // shown when mvis=1
            modalIds.insert(id); v = mv
        case "Alert":
            let a = UIView(); a.isUserInteractionEnabled = false   // invisible placeholder; the OS alert shows on avis=1
            alertIds.insert(id); v = a
        default:
            // A kind the framework does not know may be one a package claims. Only then
            // do we look: an unknown kind is otherwise a plain container, exactly as
            // before, so an app with no view packages pays one dictionary miss.
            if let t = packageViewTypes[kind] {
                let pv = t.init(host: self)
                packageViews[id] = pv
                v = pv.view
            } else {
                v = HitSlopView()   // a plain container that can also carry a Pressable hitSlop
            }
        }
        v.translatesAutoresizingMaskIntoConstraints = true   // we drive .frame directly
        views[id] = v
        ynodes[id] = n
        needsFrame.insert(id)   // a fresh view must get its frame on the next relayout
    }

    // Reset a node's Yoga LAYOUT style to Yoga defaults before (re)applying a style
    // string. Node ids are STRUCTURAL POSITIONS (e.g. "app.1.0.0.1.0"), so on a screen
    // swap the reconciler REUSES the node at each position in a new role — and it emits
    // the COMPLETE non-default style for that new role (Style.str), never a partial diff.
    // Geometry the node's PREVIOUS role set (an explicit width/height, flex-basis, margin,
    // min/max, absolute position, …) is simply ABSENT from the new string, and the apply
    // loop below only SETS the keys it sees — it never clears an omitted one. Left uncleared,
    // that stale geometry corrupts layout: e.g. the first stat tile in a row reuses a Home
    // node and keeps sizing that stops it taking its flex-grow share, rendering under-sized
    // while its freshly-created siblings are correct. Clearing first makes the incoming
    // string fully describe the node — the model Fabric guarantees by committing a fresh
    // clone per frame. Only style()-managed geometry is touched; props set in make() (Scroll
    // overflow, Text measure func) and non-layout visuals are left intact.
    func resetLayoutStyle(_ n: YGNodeRef) {
        YGNodeStyleSetFlexDirection(n, YGFlexDirection.column)
        YGNodeStyleSetJustifyContent(n, YGJustify.flexStart)
        YGNodeStyleSetAlignItems(n, YGAlign.stretch)     // Yoga (and flexbox) default cross-axis
        YGNodeStyleSetAlignSelf(n, YGAlign.auto)
        YGNodeStyleSetFlexGrow(n, 0)
        YGNodeStyleSetFlexBasisAuto(n)
        YGNodeStyleSetWidthAuto(n)                        // also clears any wpct
        YGNodeStyleSetHeightAuto(n)                       // also clears any hpct
        YGNodeStyleSetMinWidth(n, Float.nan);  YGNodeStyleSetMaxWidth(n, Float.nan)
        YGNodeStyleSetMinHeight(n, Float.nan); YGNodeStyleSetMaxHeight(n, Float.nan)
        YGNodeStyleSetAspectRatio(n, Float.nan)
        for e in [YGEdge.all, .horizontal, .vertical, .top, .right, .bottom, .left] {
            YGNodeStyleSetPadding(n, e, Float.nan)
            YGNodeStyleSetMargin(n, e, Float.nan)
            YGNodeStyleSetPosition(n, e, Float.nan)
        }
        YGNodeStyleSetPositionType(n, YGPositionType.relative)   // Yoga default (Style.h)
        YGNodeStyleSetGap(n, YGGutter.all, 0)
        YGNodeStyleSetDisplay(n, YGDisplay.flex)
        YGNodeStyleSetFlexWrap(n, YGWrap.noWrap)
    }

    // Put a blur material behind the view's children. Intensity selects the material
    // thickness rather than a radius: UIKit's blur is a system material, and picking the
    // nearest one keeps it looking native and stays cheap, where an animator-driven
    // fractional blur costs a render pass per frame.
    func applyBlur(_ id: String, _ v: UIView, _ spec: (intensity: Int, tint: String)) {
        let style: UIBlurEffect.Style
        switch spec.tint {
        case "light": style = spec.intensity < 34 ? .systemUltraThinMaterialLight
                            : (spec.intensity < 67 ? .systemThinMaterialLight : .systemMaterialLight)
        case "dark":  style = spec.intensity < 34 ? .systemUltraThinMaterialDark
                            : (spec.intensity < 67 ? .systemThinMaterialDark : .systemMaterialDark)
        default:      style = spec.intensity < 34 ? .systemUltraThinMaterial
                            : (spec.intensity < 67 ? .systemThinMaterial : .systemMaterial)
        }
        if let bv = blurViews[id] { bv.effect = UIBlurEffect(style: style); return }
        let bv = UIVisualEffectView(effect: UIBlurEffect(style: style))
        bv.isUserInteractionEnabled = false
        bv.tag = CHUKS_DECOR_TAG                 // counted by the child-insert offset
        bv.frame = v.bounds
        bv.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        bv.clipsToBounds = true
        bv.layer.cornerRadius = v.layer.cornerRadius
        v.insertSubview(bv, at: 0)
        blurViews[id] = bv
    }

    // Paint a linear gradient as the view's background. It goes in at sublayer index 0, so
    // it sits above the view's own background color and below every child, which is what a
    // background should do. A CALayer does not autoresize, so the frame pass re-sizes it
    // and copies the (already clamped) corner radius; without that a gradient view would
    // keep its first frame and square corners.
    func applyGradient(_ id: String, _ v: UIView, _ spec: (colors: String, angle: Int, stops: String)) {
        let hexes = spec.colors.split(separator: ",").map(String.init).filter { !$0.isEmpty }
        guard hexes.count >= 2 else {            // one color is a fill, not a gradient
            if let gv = gradLayers[id] { gv.removeFromSuperview(); gradLayers[id] = nil }
            return
        }
        let gv: ChuksGradientView
        if let existing = gradLayers[id] { gv = existing } else {
            gv = ChuksGradientView()
            gv.isUserInteractionEnabled = false          // never swallow a tap meant for a child
            gv.tag = CHUKS_DECOR_TAG
            gv.frame = v.bounds
            gv.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            gv.clipsToBounds = true
            v.insertSubview(gv, at: 0)                   // behind every child, like the glass view
            gradLayers[id] = gv
        }
        let gl = gv.grad
        gl.colors = hexes.map { hexColor($0).cgColor }
        let locs = spec.stops.split(separator: ",").compactMap { Double($0) }
        gl.locations = (locs.count == hexes.count) ? locs.map { NSNumber(value: $0 / 100) } : nil
        // 0 degrees runs top to bottom and the angle increases clockwise, so 90 runs left
        // to right. Both endpoints sit on a unit vector through the centre.
        let rad = Double(spec.angle) * .pi / 180
        let dx = sin(rad) / 2, dy = cos(rad) / 2
        gl.startPoint = CGPoint(x: 0.5 - dx, y: 0.5 - dy)
        gl.endPoint   = CGPoint(x: 0.5 + dx, y: 0.5 + dy)
        gv.layer.cornerRadius = v.layer.cornerRadius
    }

    // The paint/layer sibling of resetLayoutStyle: clear the decorative UIView/CALayer
    // properties that style() sets conditionally, so a reused node doesn't keep its
    // previous role's background, border, corner radius, shadow, dashed/side borders or
    // glass. Same reasoning and safety as resetLayoutStyle: style() runs only when the
    // COMPLETE new style is being applied (the reconciler emits Style.str() as a whole,
    // not a diff), so every prop the node currently wants is in the incoming string and
    // gets re-established right after this clear — clearing first just drops the residue.
    // A kind change already remove+remounts the node (reconcile), so this only ever runs
    // on SAME-kind reuse, where make()'s structural defaults still apply: background and
    // clipping are reset only for the generic container/Text views (HitSlopView / UILabel),
    // leaving the Modal scrim, ImageBackground box and media views' make()-set bg/clip intact.
    func resetPaintStyle(_ id: String, _ v: UIView) {
        if v is HitSlopView || v is UILabel {   // generic View/Pressable + Text; NOT Modal/media/ImageBackground
            v.backgroundColor = .clear
            v.clipsToBounds = false
        }
        // Text properties are neither layout nor the paint props above, but they too must
        // be reset on a reused node: Style.str() omits defaults, so a role that relies on
        // default alignment/font/lines would otherwise inherit the previous role's values
        // (e.g. a reused label keeping textAlignment=.center). Reset to make()'s defaults;
        // style() re-applies whatever the new role sets.
        if let l = v as? UILabel {
            l.textAlignment = .left
            l.numberOfLines = 0
            l.lineBreakMode = .byTruncatingTail
            l.font = .systemFont(ofSize: 14)
            l.textColor = .label
        }
        v.layer.borderWidth = 0
        v.layer.borderColor = nil
        pillIds.remove(id); cornerRadii[id] = nil
        v.layer.cornerRadius = 0
        v.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner, .layerMinXMaxYCorner, .layerMaxXMaxYCorner]
        v.layer.shadowOpacity = 0
        if dashBorders[id] != nil || sideBorders[id] != nil {
            dashBorders[id] = nil; sideBorders[id] = nil
            v.layer.sublayers?.filter { ($0.name ?? "").hasPrefix("chuksDashBorder") || ($0.name ?? "").hasPrefix("chuksSideBorder") }.forEach { $0.removeFromSuperlayer() }
        }
        if let gv = glassViews[id] { gv.removeFromSuperview(); glassViews[id] = nil }
        if let gv = gradLayers[id] { gv.removeFromSuperview(); gradLayers[id] = nil; gradSpec[id] = nil }
        if let bv = blurViews[id] { bv.removeFromSuperview(); blurViews[id] = nil; blurSpec[id] = nil }
        // ── Reused-node completeness ──────────────────────────────────────────────
        // Style.str() omits default-valued props, and the reconciler reuses a native
        // view for whatever new role sits at a tree position, so every prop that style()
        // applies ONLY-when-present must be reset here or a reused node inherits the prior
        // role's value (the stale-state bug class; docs/ui-update-model-vs-rn.md). Kept in
        // lockstep with the setters in style(); the durable fix is to materialize these
        // defaults in the reconciler. (transform + alpha are reset in the post-loop block
        // where `anim` is known, so a reset never fights an in-flight animation.)
        v.layer.zPosition = 0                                        // z
        v.layer.shadowRadius = 0; v.layer.shadowOffset = .zero       // shadow (opacity already 0 above)
        if !modalIds.contains(id) { v.isHidden = false }             // hidden/mvis (modal drives its own)
        disabledIds.remove(id)                                       // dis: interaction gate + alpha
        pressOpacity[id] = nil; pressLongDelay[id] = nil             // Pressable active-alpha / long-press
        imageTint[id] = nil; imageBlur[id] = nil; imageFilter[id] = nil   // Image tint/blur/filter (recycle re-drives the pixels)
        (v as? UITextView)?.textColor = .label                      // fg on a TextView
        if let b = v as? UIButton { b.isEnabled = true; b.setTitleColor(.label, for: .normal) }   // dis + fg
        if let sw = v as? UISwitch { sw.isEnabled = true; sw.onTintColor = nil; sw.thumbTintColor = nil }   // dis + bg/swtc
        if let sl = v as? UISlider { sl.isEnabled = true; sl.minimumTrackTintColor = nil; sl.thumbTintColor = nil }   // dis + fg
        (v as? UIProgressView)?.progressTintColor = nil             // fg
        if let slbl = v as? SelectableLabel { slbl.selectable = false }   // sel
        if let h = v as? HitSlopView { h.hitSlop = 0 }              // hitslop
        if let iv = v as? UIImageView { iv.tintColor = nil; iv.contentMode = .scaleAspectFill }   // tint / rmode
        if let tf = v as? UITextField {                             // Input config: keyboard/secure/caps/etc.
            tf.textColor = .label
            tf.isSecureTextEntry = false
            tf.keyboardType = .default
            tf.returnKeyType = .default
            tf.autocorrectionType = .default
            tf.autocapitalizationType = .sentences
            tf.isEnabled = true
            fieldMaxLen[tf] = nil
        }
        // Label typography (kern/leading/decoration/transform) is baked into attributedText
        // by refreshLabel, so clearing the dicts alone leaves a stale attributed string;
        // re-run refreshLabel when any were set to rebuild plain text.
        if labelKern[id] != nil || labelLead[id] != nil || labelDeco[id] != nil || labelTransform[id] != nil {
            labelKern[id] = nil; labelLead[id] = nil; labelDeco[id] = nil; labelTransform[id] = nil
            refreshLabel(id)
        }
    }

    // parse "k=v;k=v" -> Yoga style (layout) + UIView style (visual)
    func style(_ id: String, _ s: String) {
        guard let n = ynodes[id], let v = views[id] else { return }
        // Clear stale layout geometry from any prior role before applying the (complete)
        // incoming style — see resetLayoutStyle. The app/modal roots re-receive their
        // width/height from relayout() (which always runs after apply), so clearing them
        // here is safe.
        resetLayoutStyle(n)
        resetPaintStyle(id, v)
        let label = v as? UILabel
        let btn = v as? UIButton
        let field = v as? UITextField
        let imgView = v as? UIImageView
        let hasShadow = s.contains("shadow=")   // a shadow needs masksToBounds=false, so don't clip
        var fs: CGFloat = 14, fw: UIFont.Weight = .regular
        var customFont = ""   // a registered font family (e.g. an icon font)
        var italicFont = false // italic trait, folded into the font at the end
        var labelDirty = false // a typography key changed -> rebuild the attributed text
        // per-corner radius (rtl/rtr/rbr/rbl): collect, apply maskedCorners after the loop
        var rc = (tl: CGFloat(-1), tr: CGFloat(-1), br: CGFloat(-1), bl: CGFloat(-1))
        // border: collect width/color/style + per-side widths; a dashed/dotted or per-side
        // border is drawn by a sublayer in relayout (needs the laid-out frame).
        var borderStyle = "", borderColorHex = ""
        var bwSide = (t: CGFloat(-1), r: CGFloat(-1), b: CGFloat(-1), l: CGFloat(-1))
        // animation: collect transform + opacity across the loop, apply (animated) after
        var tx: CGFloat = 0, ty: CGFloat = 0, sc: CGFloat = 1, rot: CGFloat = 0
        var hasTransform = false, opacity: CGFloat? = nil, animMs = -1, animEz = ""
        for kv in s.split(separator: ";") {
            let p = kv.split(separator: "="); guard p.count == 2 else { continue }
            let k = String(p[0]), val = String(p[1])
            let f = Float(val) ?? 0
            switch k {
            case "d":   YGNodeStyleSetFlexDirection(n, val == "row" ? YGFlexDirection.row : (val == "row-reverse" ? YGFlexDirection.rowReverse : (val == "col-reverse" ? YGFlexDirection.columnReverse : YGFlexDirection.column)))
            case "j":   YGNodeStyleSetJustifyContent(n, justify(val))
            case "a":   YGNodeStyleSetAlignItems(n, align(val))
            case "g":     YGNodeStyleSetFlexGrow(n, f)
            case "basis": YGNodeStyleSetFlexBasis(n, f)
            case "w":   YGNodeStyleSetWidth(n, f)
                        // scale the ~20pt indicator up/down to the requested diameter
                        (v as? UIActivityIndicatorView)?.transform = CGAffineTransform(scaleX: CGFloat(f) / 20, y: CGFloat(f) / 20)
            case "h":   YGNodeStyleSetHeight(n, f)
            case "p":   YGNodeStyleSetPadding(n, YGEdge.all, f)
            case "px":  YGNodeStyleSetPadding(n, YGEdge.horizontal, f)   // horizontal padding
            case "py":  YGNodeStyleSetPadding(n, YGEdge.vertical, f)     // vertical padding
            case "pt":  YGNodeStyleSetPadding(n, YGEdge.top, f)
            case "pr":  YGNodeStyleSetPadding(n, YGEdge.right, f)
            case "pb":  YGNodeStyleSetPadding(n, YGEdge.bottom, f)
            case "pl":  YGNodeStyleSetPadding(n, YGEdge.left, f)
            case "mt":  YGNodeStyleSetMargin(n, YGEdge.top, f)           // per-side margin
            case "mr":  YGNodeStyleSetMargin(n, YGEdge.right, f)
            case "mb":  YGNodeStyleSetMargin(n, YGEdge.bottom, f)
            case "ml":  YGNodeStyleSetMargin(n, YGEdge.left, f)
            case "minw": YGNodeStyleSetMinWidth(n, f)                    // min/max size
            case "maxw": YGNodeStyleSetMaxWidth(n, f)
            case "minh": YGNodeStyleSetMinHeight(n, f)
            case "maxh": YGNodeStyleSetMaxHeight(n, f)
            case "wpct": YGNodeStyleSetWidthPercent(n, f)                // percent size (w-full/w-1/2)
            case "hpct": YGNodeStyleSetHeightPercent(n, f)
            case "aspect": YGNodeStyleSetAspectRatio(n, f / 100)         // aspect-square/video
            case "gap": YGNodeStyleSetGap(n, YGGutter.all, f)
            case "press": pressOpacity[id] = CGFloat(f) / 100     // Pressable active alpha
            case "nlines": if let l = label { l.numberOfLines = max(0, Int(f)); if let n = ynodes[id] { YGNodeMarkDirty(n) } }   // Text: cap lines (nlines=-1 means no cap -> 0 = unlimited)
            case "ellip": if let l = label {                       // Text truncation mode
                switch val { case "head": l.lineBreakMode = .byTruncatingHead; case "middle": l.lineBreakMode = .byTruncatingMiddle
                case "clip": l.lineBreakMode = .byClipping; default: l.lineBreakMode = .byTruncatingTail } }
            case "tint": if let iv = v as? UIImageView {   // Image tintColor: template render + tint
                let c = hexColor(val); imageTint[id] = c; iv.tintColor = c
                if let img = iv.image { iv.image = img.withRenderingMode(.alwaysTemplate) } }
            case "seek": if let vv = v as? VideoView { seekVideo(id, to: Int(f)) }   // Video seek (seconds)
            case "vctrl": break   // handled by the VideoControls kind (native AVPlayerViewController)
            case "dis":   // disabled: dim + block interaction (checked at fire time, since bindAction re-enables interaction)
                v.alpha = (val == "1") ? 0.4 : 1.0
                if let b = v as? UIButton { b.isEnabled = (val != "1") }
                if let sw = v as? UISwitch { sw.isEnabled = (val != "1") }   // native block: a disabled toggle won't flip
                if let sl = v as? UISlider { sl.isEnabled = (val != "1") }
                if val == "1" { disabledIds.insert(id) } else { disabledIds.remove(id) }
            case "sec": (v as? UITextField)?.isSecureTextEntry = (val == "1")   // password field
            case "kbt": if let tf = field {
                switch val { case "email": tf.keyboardType = .emailAddress; case "number": tf.keyboardType = .numberPad
                case "decimal": tf.keyboardType = .decimalPad; case "phone": tf.keyboardType = .phonePad
                case "url": tf.keyboardType = .URL; default: tf.keyboardType = .default } }
            case "ret": if let tf = field {
                switch val { case "done": tf.returnKeyType = .done; case "send": tf.returnKeyType = .send
                case "search": tf.returnKeyType = .search; case "next": tf.returnKeyType = .next
                case "go": tf.returnKeyType = .go; default: tf.returnKeyType = .default } }
            case "edit": field?.isEnabled = (val == "1")
            case "afoc": if val == "1", let tf = field { DispatchQueue.main.async { tf.becomeFirstResponder() } }
            case "acap": if let tf = field {
                switch val { case "none": tf.autocapitalizationType = .none; case "sentences": tf.autocapitalizationType = .sentences
                case "words": tf.autocapitalizationType = .words; case "characters": tf.autocapitalizationType = .allCharacters; default: break } }
            case "acor": field?.autocorrectionType = (val == "1") ? .yes : .no
            case "maxlen": if let tf = field { fieldMaxLen[tf] = Int(val) ?? -1 }
            case "sbh": sbHiddenOverride = (val == "1"); setNeedsStatusBarAppearanceUpdate()
            case "sbstyle":
                sbStyleOverride = (val == "light") ? .lightContent : (val == "dark" ? .darkContent : nil)
                setNeedsStatusBarAppearanceUpdate()
            case "sbcolor":
                if val.isEmpty { sbColorView?.removeFromSuperview(); sbColorView = nil }
                else {
                    let bar = sbColorView ?? { let x = UIView(); x.isUserInteractionEnabled = false; view.addSubview(x); sbColorView = x; return x }()
                    bar.backgroundColor = hexColor(val); view.setNeedsLayout()
                }
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
            case "slstep": if let sl = v as? UISlider { sliderStep[sl] = f }

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
            case "bg":
                if let sw = v as? UISwitch { sw.onTintColor = hexColor(val) }
                else if glassViews[id] != nil || glassPending.contains(id) {
                    // A glass surface's `bg` is what it falls back to, not what sits
                    // behind it: an opaque colour behind the material is exactly what the
                    // material would sample, and the surface would read as a flat panel.
                    glassFallbackColor[id] = hexColor(val)
                    if UIAccessibility.isReduceTransparencyEnabled { v.backgroundColor = hexColor(val) }
                } else { v.backgroundColor = hexColor(val) }
            case "swtc": (v as? UISwitch)?.thumbTintColor = hexColor(val)   // Switch thumb (knob) color
            case "fg":  label?.textColor = hexColor(val); btn?.setTitleColor(hexColor(val), for: .normal)
                        field?.textColor = hexColor(val); imgView?.tintColor = hexColor(val)
                        (v as? UIActivityIndicatorView)?.color = hexColor(val)
                        (v as? UITextView)?.textColor = hexColor(val)
                        (v as? UIProgressView)?.progressTintColor = hexColor(val)
                        if let sl = v as? UISlider { sl.minimumTrackTintColor = hexColor(val); sl.thumbTintColor = hexColor(val) }
            case "fs":  fs = CGFloat(f)
            case "fw":  fw = weightOf(val)
            case "ta":  label?.textAlignment = (val == "right") ? .right : (val == "center" ? .center : (val == "justify" ? .justified : .left)); labelDirty = true
            case "font": customFont = val
            case "fontfam": customFont = val                 // font-family (same mechanism)
            case "italic": italicFont = (val == "1"); labelDirty = true
            case "deco":  if val == "none" { labelDeco[id] = nil } else { labelDeco[id] = val }; labelDirty = true   // underline/strike
            case "txform": if val == "none" { labelTransform[id] = nil } else { labelTransform[id] = val }; labelDirty = true   // upper/lower/cap
            case "tracking": labelKern[id] = CGFloat(f); labelDirty = true      // letter spacing
            case "leading": labelLead[id] = CGFloat(f); labelDirty = true       // line height
            case "hidden":                                   // display:none — pull out of layout AND hide
                let hid = (val == "1")
                v.isHidden = hid
                YGNodeStyleSetDisplay(n, hid ? YGDisplay.none : YGDisplay.flex)
            case "overflow": if !hasShadow { v.clipsToBounds = (val == "hidden") }   // overflow-hidden clips
            case "self":  YGNodeStyleSetAlignSelf(n, align(val))                // align-self
            case "z":     v.layer.zPosition = CGFloat(f)                        // z-index
            case "rtl":   rc.tl = CGFloat(f)
            case "rtr":   rc.tr = CGFloat(f)
            case "rbr":   rc.br = CGFloat(f)
            case "rbl":   rc.bl = CGFloat(f)
            case "img": imgView?.image = UIImage(systemName: val)
            case "rmode":
                let mode: UIView.ContentMode = (val == "contain") ? .scaleAspectFit : (val == "center" ? .center : .scaleAspectFill)
                (v as? UIImageView)?.contentMode = mode
                bgImageViews[id]?.contentMode = mode
            case "vid":
                // VideoControls: a standalone AVPlayer (not pooled) driven by the native UI. The
                // pooled path below is skipped for it (its view isn't a VideoView).
                if let vc = videoCtrlVCs[id], videoPlayers[id] == nil, !val.isEmpty {
                    let url: URL? = val.hasPrefix("http") ? URL(string: val)
                                  : val.hasPrefix("file://") ? URL(fileURLWithPath: String(val.dropFirst(7)))
                                  : bundledAssetURL(val)
                    if let url = url {
                        let p = AVPlayer(url: url); vc.player = p; videoPlayers[id] = p; videoPlayerKey[id] = val; p.play()
                    }
                }
                // Attach a muted, looping player when a Video node mounts. Reuse an
                // idle player from the pool (its item + loop observer are still primed)
                // instead of rebuilding an AVPlayerItem/AVPlayer on the main thread --
                // that synchronous setup was the scroll-churn bottleneck under a fast
                // fling. Only cold-start (empty pool) pays the build cost.
                if let vv = v as? VideoView, videoPlayers[id] == nil {
                    var player: AVPlayer? = nil
                    if var idle = videoPool[val], !idle.isEmpty {
                        player = idle.removeLast(); videoPool[val] = idle       // reuse (no alloc)
                    } else {
                        // http(s) -> remote; file:// -> captured/downloaded; else a bundled asset.
                        let url: URL? = val.hasPrefix("http") ? URL(string: val)
                                      : val.hasPrefix("file://") ? URL(fileURLWithPath: String(val.dropFirst(7)))
                                      : bundledAssetURL(val)
                        if let url = url {
                            let item = AVPlayerItem(url: url)
                            let p = AVPlayer(playerItem: item); p.isMuted = true; p.actionAtItemEnd = .none
                            let obs = NotificationCenter.default.addObserver(
                                forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self, weak p] _ in
                                    guard let self = self, let p = p else { return }
                                    if !self.videoNoLoop.contains(ObjectIdentifier(p)) { p.seek(to: .zero); p.play() }
                                    else if let id = self.videoPlayers.first(where: { $0.value === p })?.key {
                                        self.fireMedia(self.mediaEnd[id])   // reached the end (not looping): onEnd
                                    }
                            }
                            videoObs[ObjectIdentifier(p)] = obs
                            player = p
                        }
                    }
                    if let player = player {
                        vv.playerLayer.player = player
                        videoPlayers[id] = player; videoPlayerKey[id] = val
                        if mediaProgress[id] != nil { addVideoProgress(id) }   // onProgress may have registered already
                        // poster: remove the overlay once the first frame is ready to display.
                        if videoPosters[id] != nil {
                            posterObs[id] = vv.playerLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) { [weak self] layer, _ in
                                if layer.isReadyForDisplay { self?.videoPosters[id]?.removeFromSuperview(); self?.videoPosters[id] = nil; self?.posterObs[id] = nil }
                            }
                        }
                        player.play()   // default autoplay; a later vplay=0 pauses it
                    }
                }
            case "vplay":   // controllable playback: vplay=0 pauses (feed cells drive this)
                if let p = videoPlayers[id] { if val == "0" { p.pause() } else { p.play() } }
            case "vmute":
                videoPlayers[id]?.isMuted = (val != "0")
            case "vfit":
                (v as? VideoView)?.playerLayer.videoGravity = (val == "contain") ? .resizeAspect : .resizeAspectFill
                videoCtrlVCs[id]?.videoGravity = (val == "contain") ? .resizeAspect : .resizeAspectFill
            case "paging":   // List/Scroll snap-per-screen (video feeds)
                (v as? UIScrollView)?.isPagingEnabled = (val == "1")
            case "stick": if v is UIScrollView { stickBottomOn = (val == "1") }
            case "horiz": if let sc = v as? UIScrollView {   // horizontal list (carousel): report x + scroll sideways
                listHoriz = (val == "1")
                sc.showsHorizontalScrollIndicator = false
                sc.showsVerticalScrollIndicator = false
            }
            case "vvol": videoPlayers[id]?.volume = f / 100                 // Video volume 0-100
            case "vrate": if let p = videoPlayers[id], p.rate != 0 { p.rate = f / 100 }   // playback speed while playing
            case "ldelay": pressLongDelay[id] = TimeInterval(f) / 1000       // onLongPress hold time (ms)
            case "blur": if let iv = v as? UIImageView { imageBlur[id] = CGFloat(f); applyBlur(iv, id) }   // Image blur (px)
            case "filt": if let iv = v as? UIImageView { imageFilter[id] = val; applyFilter(iv, id) }      // Image photo filter
            case "sel": if let l = v as? SelectableLabel { l.selectable = (val == "1"); if val == "1" { l.enableSelection() } }   // Text selectable
            case "hitslop": if let h = v as? HitSlopView { h.hitSlop = CGFloat(f) }   // Pressable enlarged tap area
            case "spin": if val == "1", let iv = v as? UIImageView, iv.image == nil { showImageSpinner(id, iv) }   // Image loading spinner
            case "vloop":
                if let p = videoPlayers[id] {
                    if val == "0" { videoNoLoop.insert(ObjectIdentifier(p)) } else { videoNoLoop.remove(ObjectIdentifier(p)) }
                }
            case "r":   v.clipsToBounds = !hasShadow
                        // A huge radius (rounded-full) is a pill: clamp to height/2 in
                        // relayout once the frame is known; a raw 9999 makes the layer
                        // path degenerate and the view stops drawing.
                        if f >= 9999 { pillIds.insert(id); cornerRadii[id] = nil }
                        else {
                            // Store the REQUESTED radius and clamp to min(w,h)/2 in relayout
                            // (and provisionally now, if the frame is known). Applying an
                            // unclamped radius > half the size overlaps the corner arcs into
                            // a diamond on iOS; Android clamps, so clamping here matches it.
                            pillIds.remove(id); cornerRadii[id] = CGFloat(f)
                            let b = v.bounds
                            v.layer.cornerRadius = b == .zero ? CGFloat(f) : min(CGFloat(f), min(b.width, b.height) / 2)
                        }
            case "glass":   // Liquid Glass: a UIGlassEffect view behind the content (blur fallback)
                if val == "1" {
                    if glassViews[id] == nil {
                        // Created EMPTY. UIGlassEffect does not render if it is applied
                        // before the view has been laid out, and at style time the frame
                        // is usually still zero: that is why this looked like a flat grey
                        // slab rather than glass. activateGlass() attaches the effect from
                        // the layout pass, once there is a real frame to refract into.
                        let gv = UIVisualEffectView(effect: nil)
                        gv.isUserInteractionEnabled = false
                        gv.tag = CHUKS_DECOR_TAG
                        gv.frame = v.bounds; gv.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                        gv.clipsToBounds = true; gv.layer.cornerRadius = v.layer.cornerRadius
                        v.insertSubview(gv, at: 0)
                        if let had = v.backgroundColor, had != .clear { glassFallbackColor[id] = had }
                        v.backgroundColor = .clear
                        glassViews[id] = gv
                        glassPending.insert(id)
                    }
                } else {
                    glassViews[id]?.removeFromSuperview(); glassViews[id] = nil
                    glassPending.remove(id); glassFallbackColor[id] = nil
                }
            // Linear gradient background. The three parts arrive as separate style keys, so
            // each one re-applies the whole spec rather than trying to patch a live layer.
            // Backdrop blur: a real UIVisualEffectView, so the system samples what is
            // behind the view in the window. Both parts arrive as separate keys, so each
            // re-applies the whole spec.
            case "bkblur", "bktint":
                var bs = blurSpec[id] ?? (intensity: 60, tint: "system")
                if k == "bkblur" { bs.intensity = Int(val) ?? 60 }
                if k == "bktint" { bs.tint = val }
                blurSpec[id] = bs
                applyBlur(id, v, bs)
            case "grad", "gradang", "gradstop":
                var spec = gradSpec[id] ?? (colors: "", angle: 0, stops: "")
                if k == "grad" { spec.colors = val }
                if k == "gradang" { spec.angle = Int(val) ?? 0 }
                if k == "gradstop" { spec.stops = val }
                gradSpec[id] = spec
                applyGradient(id, v, spec)
            case "pos": if val == "abs" { YGNodeStyleSetPositionType(n, YGPositionType.absolute) }
            case "top":   YGNodeStyleSetPosition(n, YGEdge.top, f)
            case "bottom": YGNodeStyleSetPosition(n, YGEdge.bottom, f)
            case "left":  YGNodeStyleSetPosition(n, YGEdge.left, f)
            case "right": YGNodeStyleSetPosition(n, YGEdge.right, f)
            case "bw":  v.layer.borderWidth = CGFloat(f)
            case "bc":  v.layer.borderColor = hexColor(val).cgColor; borderColorHex = val
            case "bstyle": borderStyle = val                      // solid | dashed | dotted
            case "bwt": bwSide.t = CGFloat(f)
            case "bwr": bwSide.r = CGFloat(f)
            case "bwb": bwSide.b = CGFloat(f)
            case "bwl": bwSide.l = CGFloat(f)
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
        } else {
            // No transform/opacity/anim keys this role: clear any left by a previous role
            // on a reused node. Safe here because an animating node always carries the
            // transform keys or `anim`, so this branch never interrupts an animation.
            if !v.transform.isIdentity { v.transform = .identity }
            if v.alpha != 1.0 && !disabledIds.contains(id) { v.alpha = 1.0 }
        }
        var font: UIFont = customFont.isEmpty
            ? .systemFont(ofSize: fs, weight: fw)
            : (UIFont(name: customFont, size: fs) ?? .systemFont(ofSize: fs, weight: fw))
        if italicFont, let d = font.fontDescriptor.withSymbolicTraits(font.fontDescriptor.symbolicTraits.union(.traitItalic)) {
            font = UIFont(descriptor: d, size: fs)
        }
        if let l = label {
            l.font = font
            // A font change (e.g. a tab label going regular -> semibold when active)
            // changes the text's measured size, but Yoga only re-runs its measure func
            // for DIRTY nodes. setText marks dirty; a style-only font change must too,
            // or the label keeps its old width and the wider text truncates to "…".
            YGNodeMarkDirty(n)
            // Recompose the attributed text so a font/color/typography change is reflected.
            if labelDirty || labelDeco[id] != nil || labelKern[id] != nil || labelLead[id] != nil || labelTransform[id] != nil {
                refreshLabel(id)
            }
        }
        btn?.titleLabel?.font = font
        field?.font = font
        // Per-corner radius (rounded-t-*, rounded-bl-*, …): one radius on the selected
        // corners via maskedCorners (Tailwind sets equal radii per corner group).
        if rc.tl >= 0 || rc.tr >= 0 || rc.br >= 0 || rc.bl >= 0 {
            var corners: CACornerMask = []
            var radius: CGFloat = 0
            if rc.tl >= 0 { corners.insert(.layerMinXMinYCorner); radius = max(radius, rc.tl) }
            if rc.tr >= 0 { corners.insert(.layerMaxXMinYCorner); radius = max(radius, rc.tr) }
            if rc.br >= 0 { corners.insert(.layerMaxXMaxYCorner); radius = max(radius, rc.br) }
            if rc.bl >= 0 { corners.insert(.layerMinXMaxYCorner); radius = max(radius, rc.bl) }
            v.layer.maskedCorners = corners
            v.layer.cornerRadius = radius
            v.clipsToBounds = !hasShadow
        }
        // Dashed/dotted border -> a shape sublayer in relayout; clear the solid layer border.
        if (borderStyle == "dashed" || borderStyle == "dotted"), v.layer.borderWidth > 0, !borderColorHex.isEmpty {
            dashBorders[id] = (v.layer.borderWidth, hexColor(borderColorHex), borderStyle == "dotted")
            v.layer.borderWidth = 0
        } else { dashBorders[id] = nil }
        // Per-side border -> edge sublayers in relayout.
        if (bwSide.t >= 0 || bwSide.r >= 0 || bwSide.b >= 0 || bwSide.l >= 0), !borderColorHex.isEmpty {
            sideBorders[id] = (max(0, bwSide.t), max(0, bwSide.r), max(0, bwSide.b), max(0, bwSide.l), hexColor(borderColorHex))
        } else { sideBorders[id] = nil }
        // Border props are PAINT-ONLY: never call setNeedsLayout here. Doing so from
        // inside a style-apply (which itself runs during the root's viewDidLayoutSubviews
        // -> pushViewport -> apply cycle) re-arms a full layout pass before the current
        // one commits, and updateBorderLayers churns a fresh CALayer burst each pass, so
        // an incrementally-mounted side/dash border never converges and freezes the app.
        // Draw immediately if the node is already laid out; otherwise the relayout that
        // follows every apply() draws it (see relayout -> updateBorderLayers).
        if (dashBorders[id] != nil || sideBorders[id] != nil), v.bounds != .zero { updateBorderLayers(id, v) }
    }

    // Draw dashed/dotted and per-side borders as sublayers, sized to the laid-out frame.
    // Called from relayout after each view's frame is set.
    func updateBorderLayers(_ id: String, _ v: UIView) {
        let dashKey = "chuksDashBorder", sideKey = "chuksSideBorder"
        if let (w, col, dotted) = dashBorders[id] {
            let sl = (v.layer.sublayers?.first { $0.name == dashKey } as? CAShapeLayer) ?? {
                let l = CAShapeLayer(); l.name = dashKey; l.fillColor = nil; v.layer.addSublayer(l); return l }()
            sl.frame = v.bounds
            sl.path = UIBezierPath(roundedRect: v.bounds.insetBy(dx: w / 2, dy: w / 2),
                                   cornerRadius: max(0, v.layer.cornerRadius - w / 2)).cgPath
            sl.lineWidth = w; sl.strokeColor = col.cgColor
            sl.lineDashPattern = dotted ? [0.01, NSNumber(value: Double(w * 2))]
                                        : [NSNumber(value: Double(w * 3)), NSNumber(value: Double(w * 2))]
            sl.lineCap = dotted ? .round : .butt
        } else { v.layer.sublayers?.first { $0.name == dashKey }?.removeFromSuperlayer() }
        // Per-side borders: reuse ONE persistent named layer per edge, updated in place.
        // (The old code removed + re-allocated every edge layer on each relayout, which
        // is the CALayer/autorelease burst that made the freeze loop so violent.)
        if let (t, r, b, l, col) = sideBorders[id] {
            let W = v.bounds.width, H = v.bounds.height
            func edge(_ suffix: String, _ present: Bool, _ rect: CGRect) {
                let name = sideKey + suffix
                let existing = v.layer.sublayers?.first { $0.name == name }
                if !present { existing?.removeFromSuperlayer(); return }
                let e = existing ?? { let l = CALayer(); l.name = name; v.layer.addSublayer(l); return l }()
                e.frame = rect; e.backgroundColor = col.cgColor
            }
            edge(".t", t > 0, CGRect(x: 0, y: 0, width: W, height: t))
            edge(".b", b > 0, CGRect(x: 0, y: H - b, width: W, height: b))
            edge(".l", l > 0, CGRect(x: 0, y: 0, width: l, height: H))
            edge(".r", r > 0, CGRect(x: W - r, y: 0, width: r, height: H))
        } else {
            v.layer.sublayers?.filter { ($0.name ?? "").hasPrefix(sideKey) }.forEach { $0.removeFromSuperlayer() }
        }
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
        if parent == scrollId { contentId = id }             // the live list's content node
        // Every scroll's FIRST child is its content node. Recorded per id because a screen
        // can hold several scrolls at once (a vertical Scroll containing a Carousel, whose
        // horizontal list is itself a scroll), and only one of them can be the "live list".
        if views[parent] is UIScrollView, scrollContentIds[parent] == nil { scrollContentIds[parent] = id }
        guard let pv = views[parent], let pn = ynodes[parent] else { return }
        // Skip every decoration already sitting at the back (bg image, glass, gradient), or
        // the child lands underneath it. Counting the tagged prefix rather than testing one
        // dictionary keeps this correct as decorations are added.
        let base = pv.subviews.prefix { $0.tag == CHUKS_DECOR_TAG }.count
        let i = min(index + base, pv.subviews.count)
        pv.insertSubview(child, at: i)
        // A node id can be reused across a kind change (e.g. a Text becomes a container via
        // conditional rendering / hot reload). Yoga aborts if you add a child to a node that
        // still has a text measure func, so clear it first.
        if YGNodeHasMeasureFunc(pn) { YGNodeSetMeasureFunc(pn, nil) }
        // Yoga aborts if `cn` still has an owner (a hot reload can re-emit an insert for a
        // node already parented). Detach it from its old owner first (reconciler re-parent).
        if let owner = YGNodeGetOwner(cn) { YGNodeRemoveChild(owner, cn) }
        YGNodeInsertChild(pn, cn, min(index, YGNodeGetChildCount(pn)))
    }

    // Recycle a subtree: detach + free its Yoga nodes, remove the views, and drop
    // every id under this prefix from both maps (and its recognizers/actions).
    func remove(_ id: String) {
        // Unknown id: nothing to tear down. Every mount now emits R| before C| (a mount
        // must not inherit a stale subtree at the same path id), so this is the hot case
        // and must stay a dictionary miss -- never the O(views) prefix sweep below.
        if views[id] == nil && ynodes[id] == nil { return }
        if let pv = packageViews.removeValue(forKey: id) { pv.destroy() }
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
            if let tf = views[k] as? UITextField { fieldActions[tf] = nil; fieldSubmit[tf] = nil; fieldFocus[tf] = nil; fieldBlur[tf] = nil; fieldMaxLen[tf] = nil }
            if let sw = views[k] as? UISwitch { switchActions[sw] = nil }
            if let sl = views[k] as? UISlider { sliderActions[sl] = nil; sliderStep[sl] = nil; sliderDoneAction[sl] = nil }
            if let sc = views[k] as? UIScrollView { scrollOnScroll[sc] = nil; scrollLastPos[sc] = nil }
            scrollContentIds.removeValue(forKey: k); horizScrollIds.remove(k)   // per-scroll content sizing
            if let dp = views[k] as? UIDatePicker { datePickerActions[dp] = nil; datePickerModes[dp] = nil }
            if let tv = views[k] as? UITextView { textAreaActions[tv] = nil; textAreaPlaceholders[tv] = nil }
            if selectIds.contains(k) { selectIds.remove(k); selectOptions[k] = nil; selectSel[k] = nil; selectActions[k] = nil }
            if gestureIds.contains(k) { gestureIds.remove(k); gestureActions[k] = nil; gestureContAttached.remove(k) }
            if menuIds.contains(k) { menuIds.remove(k); menuData[k] = nil; menuActions[k] = nil }
            if contextMenuIds.contains(k) { contextMenuIds.remove(k); contextMenuData[k] = nil; contextMenuActions[k] = nil }
            if alertIds.contains(k) { alertIds.remove(k); alertData[k] = nil; alertActions[k] = nil; if presentedAlert == k { presentedAlert = nil } }
            bgImageViews[k] = nil
            imageSrc[k] = nil; imageDecodedDim[k] = nil
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
            if let cv = views[k] as? CameraPreviewUIView {   // stop the live camera when its node leaves
                cv.controller?.stop()
                if cameraController === cv.controller { cameraController = nil }
            }
            pillIds.remove(k); cornerRadii[k] = nil
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
        // Reset any tap/press recognizer WE previously attached to this view before
        // (re)binding. Node ids are reused when a screen swaps in place, so a tappable
        // card can become an inert row at the same id; without this the old recognizer
        // survives and keeps firing the previous role's action (RN resets a recycled
        // view to its defaults — this is that reset). Only our own recognizers are
        // touched (guarded by the taps/pressGestures maps), so a Text's copy long-press
        // or a Gesture node's recognizers are preserved.
        for g in (v.gestureRecognizers ?? []) {
            if let t = g as? UITapGestureRecognizer, taps[t] != nil { taps[t] = nil; v.removeGestureRecognizer(t) }
            else if let lp = g as? UILongPressGestureRecognizer, pressGestures[lp] != nil {
                // A press that is IN FLIGHT when we rebind never gets its .ended:
                // removing the recognizer cancels the touch silently, so the press dim
                // would stick on the view forever (and that press is lost -- RN cancels
                // it the same way when a view is recycled mid-touch). Undo the visual
                // and drop any pending long-press so it can't fire on a stale binding.
                let gid = ObjectIdentifier(lp)
                pressLongTimers[gid]?.invalidate(); pressLongTimers[gid] = nil
                pressLongFired.remove(gid)
                if (lp.state == .began || lp.state == .changed), !disabledIds.contains(id) { v.alpha = 1.0 }
                pressGestures[lp] = nil; v.removeGestureRecognizer(lp)
            }
        }
        if action.isEmpty { return }   // binding removed: leave the view non-interactive (no recognizer)
        if let ao = pressOpacity[id] {                          // Pressable: press-feedback gesture, not a plain tap
            let g = UILongPressGestureRecognizer(target: self, action: #selector(handlePress(_:)))
            g.minimumPressDuration = 0
            // A press must never block an enclosing scroll. RN's touch handler sets
            // cancelsTouchesInView = NO, refuses to prevent any other recognizer, and
            // cancels its own touches when an ancestor recognizer takes over; that is
            // what lets you drag a list by starting on a row. We do the same: the press
            // stays instant (minimumPressDuration 0, so the dim is immediate), it never
            // delays or prevents the pan, and scrollViewWillBeginDragging cancels it.
            g.cancelsTouchesInView = false
            g.delegate = self
            v.addGestureRecognizer(g)
            pressGestures[g] = (action, ao, id)
            return
        }
        let g = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        v.addGestureRecognizer(g)
        taps[g] = action
    }

    @objc func handleButton(_ b: UIButton) { if let a = buttonActions[b] { fire(a) } }
    @objc func handleTap(_ g: UITapGestureRecognizer) { if let a = taps[g], !disabledIds.contains(a) { fire(a) } }
    // A native value event from a Switch node: dispatch, then the re-render syncs the
    // control back to Chuks state (the controlled pattern, like Input).
    @objc func handleSwitch(_ sw: UISwitch) { if let a = switchActions[sw] { fire(a) } }
    // Pressable: dim on touch-down, restore on release/cancel, fire only if the touch
    // ended inside the view (TouchableOpacity semantics). minimumPressDuration=0 makes
    // .began fire the instant the finger lands.
    @objc func handlePress(_ g: UILongPressGestureRecognizer) {
        guard let v = g.view, let (action, ao, id) = pressGestures[g] else { return }
        if disabledIds.contains(id) { return }   // disabled Pressable: no dim, no fire
        let gid = ObjectIdentifier(g)
        switch g.state {
        case .began:
            UIView.animate(withDuration: 0.09) { v.alpha = ao }
            if let pin = pressInActions[id] { fire(pin) }
            // onLongPress: after the delay, fire it and suppress the release's onPress.
            if let lp = longPressActions[id] {
                pressLongFired.remove(gid)
                pressLongTimers[gid]?.invalidate()
                pressLongTimers[gid] = Timer.scheduledTimer(withTimeInterval: pressLongDelay[id] ?? 0.5, repeats: false) { [weak self] _ in
                    guard let self = self else { return }
                    self.pressLongFired.insert(gid); self.fire(lp)
                }
            }
        case .ended, .cancelled, .failed:
            UIView.animate(withDuration: 0.09) { v.alpha = 1.0 }
            pressLongTimers[gid]?.invalidate(); pressLongTimers[gid] = nil
            if let po = pressOutActions[id] { fire(po) }
            // Fire onPress only on a real release inside, and not if a long-press already fired.
            if g.state == .ended && v.bounds.contains(g.location(in: v)) && !pressLongFired.contains(gid) { fire(action) }
            pressLongFired.remove(gid)
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
        if let step = sliderStep[sl], step > 0 {   // snap the thumb + value to step multiples
            sl.value = sl.minimumValue + (((sl.value - sl.minimumValue) / step).rounded() * step)
        }
        let val = String(Int(sl.value.rounded()))
        guard let s = eInput(action, val) else { connected = false; return }
        apply(s)
        relayout()
        headerText("slider \(action)=\(val)")
    }

    // Drag ended (finger lifted): fire onSlidingComplete once with the final value.
    @objc func sliderDone(_ sl: UISlider) {
        guard let action = sliderDoneAction[sl] else { return }
        if let step = sliderStep[sl], step > 0 {   // report the snapped final value
            sl.value = sl.minimumValue + (((sl.value - sl.minimumValue) / step).rounded() * step)
        }
        let val = String(Int(sl.value.rounded()))
        guard let s = eInput(action, val) else { connected = false; return }
        apply(s); relayout()
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

    // Attach the requested CONTINUOUS recognizers (pan/pinch/rotate) to a Gesture view,
    // once. They stream an encoded value string every move; pinch+rotate recognize
    // simultaneously (via the delegate) so a two-finger transform reports both.
    var gestureContAttached: Set<String> = []
    func attachContinuousGestures(_ id: String, _ list: String) {
        guard !list.isEmpty, !gestureContAttached.contains(id), let gv = views[id] else { return }
        gestureContAttached.insert(id)
        let kinds = list.components(separatedBy: ",")
        if kinds.contains("pan") {
            let g = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:))); g.delegate = self; gv.addGestureRecognizer(g)
        }
        if kinds.contains("pinch") {
            let g = UIPinchGestureRecognizer(target: self, action: #selector(handlePinch(_:))); g.delegate = self; gv.addGestureRecognizer(g)
        }
        if kinds.contains("rotate") {
            let g = UIRotationGestureRecognizer(target: self, action: #selector(handleRotate(_:))); g.delegate = self; gv.addGestureRecognizer(g)
        }
    }
    private func gPhase(_ s: UIGestureRecognizer.State) -> Int { s == .began ? 0 : (s == .changed ? 1 : 2) }
    @objc func handlePan(_ g: UIPanGestureRecognizer) {
        // Measure in the window, not g.view: inside a Scroll the view's own coordinate
        // space can shift and zero out translation(in: g.view).
        let t = g.translation(in: g.view?.window), v = g.velocity(in: g.view?.window)
        dispatchGesture(g.view, "pan:\(gPhase(g.state)),\(Int(t.x)),\(Int(t.y)),\(Int(v.x)),\(Int(v.y))")
    }
    @objc func handlePinch(_ g: UIPinchGestureRecognizer) {
        dispatchGesture(g.view, "pinch:\(gPhase(g.state)),\(Int(g.scale * 100)),\(Int(g.velocity * 100))")
    }
    @objc func handleRotate(_ g: UIRotationGestureRecognizer) {
        let deg = g.rotation * 180.0 / .pi, vdeg = g.velocity * 180.0 / .pi
        dispatchGesture(g.view, "rotate:\(gPhase(g.state)),\(Int(deg)),\(Int(vdeg))")
    }

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
            // Encoding: title, message, promptFlag, placeholder, promptValue, buttonsPipe.
            let f = self.alertData[id] ?? []
            let title = f.count > 0 ? f[0] : "", msg = f.count > 1 ? f[1] : ""
            let isPrompt = f.count > 2 && f[2] == "1"
            let placeholder = f.count > 3 ? f[3] : "", promptValue = f.count > 4 ? f[4] : ""
            let ac = UIAlertController(title: title.isEmpty ? nil : title,
                                       message: msg.isEmpty ? nil : msg, preferredStyle: .alert)
            if isPrompt {
                ac.addTextField { tf in tf.placeholder = placeholder; tf.text = promptValue }
            }
            // Fields from index 5 on are the buttons, in tap-index order; "!"=destructive, "~"=cancel.
            let labels = f.count > 5 ? Array(f[5...]) : ["OK"]
            for (i, raw) in labels.enumerated() {
                var label = raw, style: UIAlertAction.Style = .default
                if label.hasPrefix("!") { style = .destructive; label.removeFirst() }
                else if label.hasPrefix("~") { style = .cancel; label.removeFirst() }
                ac.addAction(UIAlertAction(title: label, style: style) { _ in
                    self.presentedAlert = nil
                    let text = isPrompt ? (ac.textFields?.first?.text ?? "") : ""
                    self.alertDispatch(id, isPrompt ? "\(i)\t\(text)" : "\(i)")
                })
            }
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
    // (re)decode a LOCAL image downsampled to the view's display size.
    // Called provisionally at screen width on load, then refined from the real frame
    // in relayout(). A power-of-two px bucket skips redundant re-decodes.
    func ensureSizedImage(_ id: String, _ targetPx: CGFloat) {
        guard let src = imageSrc[id] else { return }
        guard let iv = (views[id] as? UIImageView) ?? bgImageViews[id] else { return }
        let target = max(1, min(targetPx, maxImageDim))
        let ti = max(1, Int(target))
        let bucket = 1 << (Int.bitWidth - 1 - ti.leadingZeroBitCount)   // highest one bit
        if imageDecodedDim[id] == bucket { return }
        let path: String? = src.hasPrefix("file://") ? String(src.dropFirst(7))
            : bundledAssetURL(src)?.path
        guard let p = path, let img = downsampledImage(path: p, maxPixel: target) else { return }
        iv.image = img
        imageOriginal[id] = nil; applyFilter(iv, id); applyBlur(iv, id); applyOps(iv, id)
        imageDecodedDim[id] = bucket
    }

    func loadRemoteImage(_ urlStr: String, into iv: UIImageView, id: String = "") {
        // Bounded LRU + disk + off-main decode (feed-grade), with tag cancellation so a
        // recycled cell never gets a late image for a URL it no longer shows.
        if let cached = ChuksImageLoader.shared.cached(urlStr) { iv.image = tinted(cached, id); imageOriginal[id] = nil; applyFilter(iv, id); applyBlur(iv, id); applyOps(iv, id); hideImageSpinner(id); fireMedia(mediaLoad[id]); return }
        let wanted = urlStr.hashValue
        iv.tag = wanted
        ChuksImageLoader.shared.load(urlStr) { [weak self] img in
            guard let self = self else { return }
            if iv.tag == wanted { iv.image = self.tinted(img, id); self.imageOriginal[id] = nil; self.applyFilter(iv, id); self.applyBlur(iv, id); self.applyOps(iv, id) }
            self.hideImageSpinner(id)
            self.fireMedia(self.mediaLoad[id])
        } fail: { [weak self] in self?.hideImageSpinner(id); self?.fireMedia(self?.mediaError[id]) }
    }
    // Image loading spinner: a centered activity indicator over the image view until it loads.
    func showImageSpinner(_ id: String, _ iv: UIImageView) {
        if imageSpinners[id] != nil { return }
        let sp = UIActivityIndicatorView(style: .medium); sp.color = .lightGray
        sp.translatesAutoresizingMaskIntoConstraints = false; sp.startAnimating()
        iv.addSubview(sp)
        NSLayoutConstraint.activate([sp.centerXAnchor.constraint(equalTo: iv.centerXAnchor), sp.centerYAnchor.constraint(equalTo: iv.centerYAnchor)])
        imageSpinners[id] = sp
    }
    func hideImageSpinner(_ id: String) { imageSpinners[id]?.removeFromSuperview(); imageSpinners[id] = nil }
    // Render as a template (for tintColor) when the node has a tint, else leave the image as-is.
    func tinted(_ img: UIImage?, _ id: String) -> UIImage? { imageTint[id] != nil ? img?.withRenderingMode(.alwaysTemplate) : img }
    // Image blurRadius: Gaussian-blur the current image in place (cropped to the original extent).
    func applyBlur(_ iv: UIImageView, _ id: String) {
        guard let r = imageBlur[id], r > 0, let img = iv.image, let ci = CIImage(image: img),
              let filter = CIFilter(name: "CIGaussianBlur") else { return }
        filter.setValue(ci, forKey: kCIInputImageKey); filter.setValue(r, forKey: kCIInputRadiusKey)
        guard let out = filter.outputImage, let cg = ciContext.createCGImage(out, from: ci.extent) else { return }
        iv.image = UIImage(cgImage: cg)
    }
    // Image `filter`: a GPU photo-filter preset via Core Image (CIPhotoEffect family +
    // temperature). Re-applies from the pre-filter original so switching presets doesn't
    // stack (needed for a capture filter picker).
    func applyFilter(_ iv: UIImageView, _ id: String) {
        let name = imageFilter[id] ?? ""
        if name.isEmpty || name == "normal" || name == "none" { if let orig = imageOriginal[id] { iv.image = orig }; return }
        let base = imageOriginal[id] ?? iv.image
        if imageOriginal[id] == nil, let b = base { imageOriginal[id] = b }
        guard let img = base, let ci = CIImage(image: img) else { return }
        var out: CIImage? = nil
        switch name {
        case "mono":  out = ci.applyingFilter("CIPhotoEffectMono")
        case "noir":  out = ci.applyingFilter("CIPhotoEffectNoir")
        case "sepia": out = ci.applyingFilter("CISepiaTone", parameters: [kCIInputIntensityKey: 0.9])
        case "vivid": out = ci.applyingFilter("CIPhotoEffectChrome")
        case "fade":  out = ci.applyingFilter("CIPhotoEffectFade")
        case "cool":  out = ci.applyingFilter("CITemperatureAndTint", parameters: ["inputNeutral": CIVector(x: 6500, y: 0), "inputTargetNeutral": CIVector(x: 9000, y: 0)])
        case "warm":  out = ci.applyingFilter("CITemperatureAndTint", parameters: ["inputNeutral": CIVector(x: 6500, y: 0), "inputTargetNeutral": CIVector(x: 4200, y: 0)])
        default: return
        }
        guard let o = out, let cg = ciContext.createCGImage(o, from: ci.extent) else { return }
        iv.image = UIImage(cgImage: cg)
    }
    // Image `ops`: run the GPU op-chain (from effects.chain) on the GPU, from the pre-filter
    // original so switching chains does not stack. Supersedes the preset filter when set.
    func applyOps(_ iv: UIImageView, _ id: String) {
        let json = imageOpChain[id] ?? ""
        if json.isEmpty { return }
        let base = imageOriginal[id] ?? iv.image
        if imageOriginal[id] == nil, let b = base { imageOriginal[id] = b }
        guard let img = base else { return }
        iv.image = runOpChain(img, json)
    }
    let ciContext = CIContext()
    // Dispatch a media event action (onLoad/onError/onEnd) if one is registered for the node.
    func fireMedia(_ action: String?) { if let a = action { DispatchQueue.main.async { [weak self] in self?.fire(a) } } }

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
    // A native event carrying a value (onProgress time, etc.) -> the engine, then apply.
    func fireValue(_ action: String, _ value: String) {
        guard let s = eInput(action, value) else { connected = false; return }
        apply(s); relayout()
    }
    // Video seek: jump to `seconds` when it changes (a controlled prop). Tracked per id so a
    // style re-emit that didn't change the seek target doesn't re-seek.
    func seekVideo(_ id: String, to seconds: Int) {
        if videoSeek[id] == seconds { return }
        videoSeek[id] = seconds
        videoPlayers[id]?.seek(to: CMTime(seconds: Double(seconds), preferredTimescale: 600))
    }
    // onProgress: attach a periodic observer, but fire only when the WHOLE SECOND changes and
    // stays within the clip (throttles the re-render to ~1/sec and never reports a stray time
    // past the end). One observer per id.
    func addVideoProgress(_ id: String) {
        guard let p = videoPlayers[id], videoTimeObservers[id] == nil else { return }
        let tok = p.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self, weak p] _ in
            guard let self = self, let p = p, let a = self.mediaProgress[id] else { return }
            let sec = Int(p.currentTime().seconds)
            let dur = p.currentItem?.duration.seconds ?? 0
            if sec < 0 || (dur.isFinite && Double(sec) > dur + 1) { return }   // ignore out-of-range ticks
            if self.videoLastSec[id] == sec { return }                        // dedup to whole seconds
            self.videoLastSec[id] = sec
            self.fireValue(a, "\(sec)")
        }
        videoTimeObservers[id] = tok
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
    /// Yoga's "undefined" IS NaN, and Yoga hands it back for any node a layout pass did
    /// not resolve. CoreAnimation raises CALayerInvalidGeometry on a NaN or infinite
    /// geometry value, and an uncaught Objective-C exception in Swift aborts the process,
    /// so one unresolved node anywhere in the tree kills the app on the next vsync tick.
    ///
    /// React Native carries the same guard for the same reason ("CALayer will crash if we
    /// pass NaN or Inf values"), and skips the update rather than substituting a value:
    /// a view's previous frame is a better guess than zero, and for a view created this
    /// pass the previous frame is already zero.
    ///
    /// Returns nil when the node is not safe to place, having logged which node it was
    /// (once per node, so a persistently bad node cannot flood the log at 60Hz).
    private func yogaFrame(_ n: YGNodeRef, _ id: String) -> CGRect? {
        let l = YGNodeLayoutGetLeft(n), t = YGNodeLayoutGetTop(n)
        let w = YGNodeLayoutGetWidth(n), h = YGNodeLayoutGetHeight(n)
        guard l.isFinite, t.isFinite, w.isFinite, h.isFinite else {
            if warnedNonFinite.insert(id).inserted {
                NSLog("chuks-layout: node %@ has a non-finite frame (%f,%f %fx%f) and was not placed", id, l, t, w, h)
            }
            return nil
        }
        return CGRect(x: CGFloat(l), y: CGFloat(t), width: CGFloat(w), height: CGFloat(h))
    }

    /// Attach the Liquid Glass material, once the view has a real frame, and REATTACH it
    /// whenever the appearance it was built against is no longer the current one.
    ///
    /// Driven by comparing the trait rather than by a flag set when the theme changes.
    /// The flag version raced: the rebuild and the layout pass are both async, so a
    /// rebuild that landed AFTER the activations left surfaces cleared with nothing
    /// re-applied, and the bar simply vanished. Comparing state cannot race, and any pass
    /// that misses is corrected by the next one.
    ///
    /// Everything here is a workaround for the same fact: UIGlassEffect is laid out, not
    /// merely drawn, so it renders nothing useful until the view it belongs to has a size.
    /// Applying it at style time gives an opaque panel that looks like a mistake, which is
    /// exactly what it looked like.
    ///
    /// The three moves, in order, all of them load-bearing:
    ///   1. clear to a plain UIVisualEffect first, so UIKit tears the old effect down;
    ///      re-assigning over a live effect does not take.
    ///   2. shape it with cornerConfiguration rather than layer.cornerRadius, so the
    ///      material's edge refracts along the corner instead of being clipped square.
    ///   3. assign `effect` again at the end. Configuring the effect object after it is
    ///      attached does nothing until it is re-set.
    ///
    /// Below iOS 26 there is no glass, so this falls back to the thinnest blur material,
    /// which is the nearest honest thing the platform has.
    private func activateGlass(_ id: String, _ gv: UIVisualEffectView, _ fr: CGRect) {
        guard fr.width > 0, fr.height > 0 else { return }
        let want = view.traitCollection.userInterfaceStyle
        // Already correct for this appearance: nothing to do, and this runs every layout
        // pass so the early exit matters.
        if !glassPending.contains(id), glassBuiltFor[id] == want, gv.effect != nil { return }
        glassPending.remove(id)
        glassBuiltFor[id] = want

        if UIAccessibility.isReduceTransparencyEnabled {
            gv.removeFromSuperview(); glassViews[id] = nil; glassBuiltFor[id] = nil
            if let host = views[id] { host.backgroundColor = glassFallbackColor[id] }
            return
        }
        gv.effect = UIVisualEffect()
        if #available(iOS 26.0, *), NSClassFromString("UIGlassEffect") != nil {
            let eff = UIGlassEffect(style: .regular)
            eff.isInteractive = false          // a backdrop; the children own the touches
            let r = UICornerRadius(floatLiteral: gv.layer.cornerRadius)
            gv.cornerConfiguration = .corners(topLeftRadius: r, topRightRadius: r,
                                              bottomLeftRadius: r, bottomRightRadius: r)
            gv.layer.cornerCurve = .continuous
            gv.effect = eff
        } else {
            gv.effect = UIBlurEffect(style: .systemUltraThinMaterial)
        }
    }

    /// A Yoga measurement for something that needs a number rather than a skip, such as a
    /// scroll's content size. Undefined collapses to zero, which is inert.
    private func yogaSize(_ v: Float) -> CGFloat { v.isFinite ? CGFloat(v) : 0 }

    func relayout() {
        guard let app = ynodes["app"] else { return }
        let insets = view.safeAreaInsets
        // Below the benchmark header when shown; otherwise just below the safe-area top
        // (status bar / notch). Without this the app slides up under the status bar,
        // since a hidden header has a .zero frame.
        // Edge-to-edge: lay the tree over the FULL screen (under the status bar and home
        // indicator); the app pads its content by safeTop()/safeBottom() (reported via
        // setInsets), so an inset is applied ONCE, not twice. Matches the Android host.
        // Without this the tree is inset AND the app pads -> a gap top and bottom.
        let topY: CGFloat = 0
        let W = Float(view.bounds.width)
        let H = Float(view.bounds.height - topY - kbHeight)   // kbHeight: only the keyboard OVERLAP of a focused bottom field
        if W <= 0 || H <= 0 { return }
        YGNodeStyleSetWidth(app, W); YGNodeStyleSetHeight(app, H)
        let _tc = layoutTiming ? CACurrentMediaTime() : 0
        YGNodeCalculateLayout(app, W, H, YGDirection.LTR)
        if layoutTiming { layoutComputeUs = (CACurrentMediaTime() - _tc) * 1_000_000 }
        // A visible Modal is a separate full-screen Yoga root (over the whole window,
        // safe area included) — lay it out before copying frames.
        if let mid = activeModal, let mn = ynodes[mid], views[mid]?.isHidden == false {
            let fw = Float(view.bounds.width), fh = Float(view.bounds.height)
            YGNodeStyleSetWidth(mn, fw); YGNodeStyleSetHeight(mn, fh)
            YGNodeCalculateLayout(mn, fw, fh, YGDirection.LTR)
        }
        let _ta = layoutTiming ? CACurrentMediaTime() : 0
        var _applied = 0
        for (id, n) in ynodes {
            if modalIds.contains(id) { continue }   // modal overlay node has no app-tree layout (placed below)
            // Incremental apply: skip nodes Yoga did not re-lay-out this pass (unless the view
            // was just created). Yoga sets HasNewLayout on every node it recomputes (a fresh
            // insert dirties it), so an unchanged subtree costs nothing here.
            if !YGNodeGetHasNewLayout(n) && !needsFrame.contains(id) { continue }
            YGNodeSetHasNewLayout(n, false)
            _applied += 1
            guard let fr = yogaFrame(n, id) else { continue }
            if let vv = views[id] {
                // Setting .frame on a view with a non-identity transform corrupts it
                // (frame is transform-affected); position such views via bounds + center.
                if vv.transform.isIdentity { vv.frame = fr }
                else { vv.bounds = CGRect(origin: .zero, size: fr.size); vv.center = CGPoint(x: fr.midX, y: fr.midY) }
            }
            // now that the frame is known, decode the image to its display size.
            if imageSrc[id] != nil { ensureSizedImage(id, max(fr.width, fr.height) * UIScreen.main.scale) }
            if pillIds.contains(id) { views[id]?.layer.cornerRadius = min(fr.width, fr.height) / 2 }
            else if let rr = cornerRadii[id] { views[id]?.layer.cornerRadius = min(rr, min(fr.width, fr.height) / 2) }   // clamp numeric radius: never a diamond
            if dashBorders[id] != nil || sideBorders[id] != nil, let vv = views[id] { updateBorderLayers(id, vv) }
            if let gv = glassViews[id] {
                gv.layer.cornerRadius = views[id]?.layer.cornerRadius ?? 0   // match the view's rounding
                activateGlass(id, gv, fr)
            }
            if let gv = gradLayers[id] { gv.layer.cornerRadius = views[id]?.layer.cornerRadius ?? 0 }   // match the clamped rounding
            if let bv = blurViews[id] { bv.layer.cornerRadius = views[id]?.layer.cornerRadius ?? 0 }
        }
        needsFrame.removeAll()   // consumed for this pass
        if layoutTiming {
            let applyUs = (CACurrentMediaTime() - _ta) * 1_000_000
            NSLog("chuks-layout compute=%.0fus apply=%.0fus nodes=%d applied=%d", layoutComputeUs, applyUs, ynodes.count, _applied)
        }
        // place the whole Chuks app edge-to-edge (below the diagnostics header if shown)
        views["app"]?.frame = CGRect(x: 0, y: topY, width: CGFloat(W), height: CGFloat(H))
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
        // the scroll's content size comes from its content node's laid-out size. A horizontal
        // list's content node has an explicit WIDTH but no height (its rows are abs), so pin the
        // content height to the scroll's own height — it scrolls sideways only, rows never clip.
        // Size EVERY scroll from its own content node. This used to run only for the single
        // "live list", which is a different job: live means whose window follows the user's
        // scrolling, and only a List/SectionList claims it. So a plain Scroll that contained a
        // List (a Carousel, say) lost the slot to it and was left with no content size at all,
        // which reads as a screen that simply will not scroll.
        for (sid, cid) in scrollContentIds {
            guard let sv = views[sid] as? UIScrollView, let cnode = ynodes[cid] else { continue }
            let w = yogaSize(YGNodeLayoutGetWidth(cnode)), h = yogaSize(YGNodeLayoutGetHeight(cnode))
            sv.contentSize = CGSize(width: w, height: horizScrollIds.contains(sid) ? sv.bounds.height : h)
        }
        if let sc = listScroll, let cn = ynodes[contentId] {
            let cw = yogaSize(YGNodeLayoutGetWidth(cn)), chh = yogaSize(YGNodeLayoutGetHeight(cn))
            sc.contentSize = CGSize(width: cw, height: listHoriz ? sc.bounds.height : chh)
            // stickBottom (chat): if the user was at the bottom before this layout, stay pinned
            // to the new bottom (a new message, or the keyboard opening and shrinking the view).
            if stickBottomOn {
                let newH = sc.contentSize.height
                let wasAtBottom = sc.contentOffset.y + sc.bounds.height >= stickPrevH - 40
                if wasAtBottom && newH > sc.bounds.height {
                    sc.setContentOffset(CGPoint(x: 0, y: newH - sc.bounds.height), animated: false)
                }
                stickPrevH = newH
            }
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
