// Chuks Preview — the generic runtime host (Expo Go for Chuks Mobile).
//
// It links no app engine of its own. You point it at a running `chuks dev` server by
// scanning the QR that server prints (or typing the address), and it renders whatever
// app the server serves over HTTP, hot-reloading as you edit. It reuses the entire
// rendering machinery in ChuksAppSwiftUI.swift (Scene, NodeView, RootView, devHTTP);
// this file only adds the connect screen and drives `devServerHost` at runtime.
//
// Built with -D CHUKS_PREVIEW (so ChuksAppSwiftUI.swift drops its own @main) and -D DEV
// (so the engine bridge always talks to the dev server instead of a linked library).
#if CHUKS_PREVIEW
import SwiftUI
import UIKit
import AVFoundation
import AudioToolbox

// ─── Parsing ────────────────────────────────────────────────────────────────
// Turn a scanned/typed value into a "host:port" the dev bridge can reach. Accepts
// "chuks://192.168.1.5:7799", "192.168.1.5:7799", "192.168.1.5" (adds :7799), and
// tolerates a trailing path or slash.
func parseDevServer(_ raw: String) -> String? {
    var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if s.isEmpty { return nil }
    for prefix in ["chuks://", "http://", "https://"] {
        if s.lowercased().hasPrefix(prefix) { s = String(s.dropFirst(prefix.count)); break }
    }
    if let slash = s.firstIndex(of: "/") { s = String(s[..<slash]) }
    if s.isEmpty { return nil }
    if !s.contains(":") { s += ":7799" }
    return s
}

// ─── App entry ──────────────────────────────────────────────────────────────
// Two render gates share one connect screen. The SwiftUI gate (here) hands off to
// RootView; the UIKit gate (bottom of file, -D CHUKS_PREVIEW_UIKIT) hands off to CardsVC.
// Only the host being compiled defines RootView/Scene (SwiftUI) or CardsVC (UIKit), so
// each gate is behind the matching flag.
#if !CHUKS_PREVIEW_UIKIT
@main
struct ChuksPreviewApp: App {
    @UIApplicationDelegateAdaptor(ChuksOrientationDelegate.self) var appDelegate   // orientation lock mask
    var body: some SwiftUI.Scene {
        WindowGroup { PreviewGate() }
    }
}

// Shows the connect screen until a server is chosen, then hands off to RootView, which
// boots the Scene against `devServerHost`. "Disconnect" returns here with a fresh Scene.
struct PreviewGate: View {
    @State private var connected = false
    @State private var sceneKey = UUID()   // changing it makes a brand-new Scene on reconnect

    var body: some View {
        Group {
            if connected {
                ConnectedHost(onDisconnect: { connected = false; sceneKey = UUID() })
                    .id(sceneKey)
            } else {
                ConnectView(onConnect: connect)
            }
        }
        // A chuks:// deep link (scanned on a real device, or `simctl openurl`) connects
        // straight through, no manual entry.
        .onOpenURL { url in
            if let host = parseDevServer(url.absoluteString) { connect(host) }
        }
        // Env override for scripted / CI launches: SIMCTL_CHILD_CHUKS_PREVIEW_HOST=host:port.
        .onAppear {
            if !connected, let h = ProcessInfo.processInfo.environment["CHUKS_PREVIEW_HOST"],
               let host = parseDevServer(h) { connect(host) }
        }
    }

    private func connect(_ host: String) {
        devServerHost = host
        UserDefaults.standard.set(host, forKey: "chuks.preview.lastHost")
        connected = true
    }
}

// A fresh Scene + the shared RootView, plus a small floating "disconnect" control.
// The control lives inside the safe area (clears the notch/status bar) and carries its
// own contrasting chip so it stays visible over any app background, light or dark.
struct ConnectedHost: View {
    @StateObject private var scene = Scene()
    let onDisconnect: () -> Void
    var body: some View {
        ZStack {
            RootView(scene: scene)
            VStack {
                HStack {
                    Spacer()
                    Button(action: onDisconnect) {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundColor(.white)
                            .frame(width: 34, height: 34)
                            .background(Color.black.opacity(0.55))
                            .clipShape(Circle())
                            .overlay(Circle().stroke(Color.white.opacity(0.3), lineWidth: 1))
                            .shadow(color: .black.opacity(0.35), radius: 4, y: 1)
                    }
                    .accessibilityLabel("Disconnect")
                }
                Spacer()
            }
            .padding(.top, 6)
            .padding(.trailing, 14)
        }
    }
}
#endif

// ─── UIKit render gate ────────────────────────────────────────────────────────
// The Preview built with the UIKit engine: a UIKit @main that shows the shared SwiftUI
// ConnectView (hosted) until a server is chosen, then swaps the root to CardsVC.
#if CHUKS_PREVIEW_UIKIT
@main
class ChuksPreviewAppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ a: UIApplication, didFinishLaunchingWithOptions o: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let w = UIWindow(frame: UIScreen.main.bounds); window = w
        // Env override for scripted launches, then a chuks:// launch URL, else the connect screen.
        if let h = ProcessInfo.processInfo.environment["CHUKS_PREVIEW_HOST"], let host = parseDevServer(h) {
            connect(host)
        } else if let url = o?[.url] as? URL, let host = parseDevServer(url.absoluteString) {
            connect(host)
        } else {
            window?.rootViewController = UIHostingController(
                rootView: ConnectView(onConnect: { [weak self] host in self?.connect(host) }))
        }
        w.makeKeyAndVisible()
        return true
    }
    // chuks:// deep link while running.
    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        guard let host = parseDevServer(url.absoluteString) else { return false }
        connect(host); return true
    }
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        chuksOrientationMask   // Orientation.lockTo() drives this
    }
    private func connect(_ host: String) {
        devServerHost = host
        UserDefaults.standard.set(host, forKey: "chuks.preview.lastHost")
        window?.rootViewController = CardsVC()
    }
}
#endif

// ─── Connect screen ─────────────────────────────────────────────────────────
struct ConnectView: View {
    let onConnect: (String) -> Void
    @State private var manual = ""
    @State private var showScanner = false
    @State private var error: String?
    private let last = UserDefaults.standard.string(forKey: "chuks.preview.lastHost")

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(hex: "0B1120"), Color(hex: "111a2e")],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            VStack(spacing: 22) {
                Spacer()
                VStack(spacing: 10) {
                    ChuksMark().frame(width: 72, height: 72)
                    Text("Chuks Preview").font(.system(size: 28, weight: .bold)).foregroundColor(.white)
                    Text("Run your Chuks app on this device.\nScan the QR from `chuks dev`.")
                        .font(.system(size: 15)).multilineTextAlignment(.center)
                        .foregroundColor(Color.white.opacity(0.6))
                }

                Button(action: { error = nil; showScanner = true }) {
                    HStack(spacing: 10) {
                        Image(systemName: "qrcode.viewfinder").font(.system(size: 20, weight: .semibold))
                        Text("Scan QR code").font(.system(size: 17, weight: .semibold))
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 15)
                    .background(Color(hex: "4F7DFF")).foregroundColor(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }

                HStack {
                    // Custom placeholder overlay: the default one is near-invisible on the
                    // dark field, so draw our own at a legible opacity when empty.
                    ZStack(alignment: .leading) {
                        if manual.isEmpty {
                            Text("192.168.1.5:7799").foregroundColor(Color.white.opacity(0.45))
                        }
                        TextField("", text: $manual)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .keyboardType(.URL).submitLabel(.go)
                            .foregroundColor(.white)
                            .onSubmit(connectManual)
                    }
                    .padding(.horizontal, 14).padding(.vertical, 13)
                    .background(Color.white.opacity(0.08))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    Button("Connect", action: connectManual)
                        .font(.system(size: 16, weight: .semibold)).foregroundColor(Color(hex: "4F7DFF"))
                }

                if let last = last, !last.isEmpty {
                    Button(action: { connect(last) }) {
                        Label("Reconnect to \(last)", systemImage: "clock.arrow.circlepath")
                            .font(.system(size: 14)).foregroundColor(Color.white.opacity(0.55))
                    }
                }
                if let error = error {
                    Text(error).font(.system(size: 13)).foregroundColor(Color(hex: "FF6B6B"))
                }
                Spacer()
                Text("On the same Wi-Fi as your computer")
                    .font(.system(size: 12)).foregroundColor(Color.white.opacity(0.35))
                    .padding(.bottom, 8)
            }
            .padding(.horizontal, 28)
        }
        .sheet(isPresented: $showScanner) {
            QRScanner(onScan: { value in
                showScanner = false
                if let host = parseDevServer(value) { connect(host) }
                else { error = "That QR is not a Chuks dev server." }
            }, onCancel: { showScanner = false })
        }
    }

    private func connectManual() {
        guard let host = parseDevServer(manual) else { error = "Enter a host like 192.168.1.5:7799"; return }
        connect(host)
    }
    private func connect(_ host: String) { error = nil; onConnect(host) }
}

// The Chuks logo, loaded from the bundled ChuksLogo.png (the same image as the app icon),
// shown in a rounded square. Falls back to a blue tile if the asset is missing.
struct ChuksMark: View {
    private var logo: UIImage? {
        Bundle.main.path(forResource: "ChuksLogo", ofType: "png").flatMap(UIImage.init(contentsOfFile:))
    }
    var body: some View {
        Group {
            if let img = logo {
                Image(uiImage: img).resizable().scaledToFit()
            } else {
                RoundedRectangle(cornerRadius: 18).fill(Color(hex: "4F7DFF"))
            }
        }
    }
}

// ─── QR scanner ───────────────────────────────────────────────────────────────
struct QRScanner: UIViewControllerRepresentable {
    let onScan: (String) -> Void
    let onCancel: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onScan: onScan) }
    func makeUIViewController(context: Context) -> ScannerVC {
        let vc = ScannerVC(); vc.delegate = context.coordinator; return vc
    }
    func updateUIViewController(_ vc: ScannerVC, context: Context) {}

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        let onScan: (String) -> Void
        private var done = false
        init(onScan: @escaping (String) -> Void) { self.onScan = onScan }
        func metadataOutput(_ output: AVCaptureMetadataOutput,
                            didOutput objects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            guard !done,
                  let obj = objects.first as? AVMetadataMachineReadableCodeObject,
                  let value = obj.stringValue else { return }
            done = true
            AudioServicesPlaySystemSound(1057)   // capture tick
            DispatchQueue.main.async { self.onScan(value) }
        }
    }
}

final class ScannerVC: UIViewController {
    weak var delegate: AVCaptureMetadataOutputObjectsDelegate?
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            DispatchQueue.main.async { granted ? self?.configure() : self?.showNoCamera() }
        }
        // A visible reticle so the user knows where to aim.
        let box = UIView(); box.layer.borderColor = UIColor.white.cgColor
        box.layer.borderWidth = 3; box.layer.cornerRadius = 16
        box.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(box)
        NSLayoutConstraint.activate([
            box.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            box.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            box.widthAnchor.constraint(equalTo: view.widthAnchor, multiplier: 0.65),
            box.heightAnchor.constraint(equalTo: box.widthAnchor),
        ])
    }

    private func configure() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { showNoCamera(); return }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { showNoCamera(); return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(delegate, queue: .main)
        output.metadataObjectTypes = [.qr]
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill; layer.frame = view.layer.bounds
        view.layer.insertSublayer(layer, at: 0)
        preview = layer
        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }

    private func showNoCamera() {
        let l = UILabel()
        l.text = "Camera unavailable.\nEnter the server address instead."
        l.numberOfLines = 0; l.textAlignment = .center; l.textColor = .white
        l.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(l)
        NSLayoutConstraint.activate([
            l.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            l.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            l.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 32),
            l.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -32),
        ])
    }

    override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); preview?.frame = view.layer.bounds }
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning { session.stopRunning() }
    }
}

// Local hex helper so this file is self-contained even if the shared one is private.
private extension Color {
    init(hex: String) {
        var h = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        if h.count == 3 { h = h.map { "\($0)\($0)" }.joined() }
        var v: UInt64 = 0; Scanner(string: h).scanHexInt64(&v)
        self = Color(.sRGB,
                     red: Double((v >> 16) & 0xff) / 255,
                     green: Double((v >> 8) & 0xff) / 255,
                     blue: Double(v & 0xff) / 255, opacity: 1)
    }
}
#endif
