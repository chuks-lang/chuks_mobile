// Chuks Preview — the generic runtime host (Expo Go for Chuks Mobile).
//
// It links no app engine of its own. You point it at a running `chuks dev` server by
// scanning the QR that server prints (or typing the address), and it renders whatever
// app the server serves over HTTP, hot-reloading as you edit. It reuses the UIKit
// rendering machinery in ChuksApp.swift (CardsVC, the render gate, devHTTP); this file
// only adds the connect screen and drives `devServerHost` at runtime.
//
// Built with -D CHUKS_PREVIEW -D CHUKS_PREVIEW_UIKIT (so ChuksApp.swift drops its own
// @main) and -D DEV (so the engine bridge always talks to the dev server). The connect
// screen is a plain UIKit view controller (ConnectVC); the app content renders through
// the same UIKit host.
#if CHUKS_PREVIEW
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

// ─── UIKit render gate ────────────────────────────────────────────────────────
// The Preview built with the UIKit engine: a UIKit @main that shows the ConnectVC
// screen until a server is chosen, then swaps the root to CardsVC.
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
            window?.rootViewController = ConnectVC(onConnect: { [weak self] host in self?.connect(host) })
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

// ─── Connect screen (UIKit) ─────────────────────────────────────────────────
// A dark, centered screen: the Chuks mark, a "Scan QR code" button, a manual
// host field + Connect, an optional reconnect shortcut, and an error line.
final class ConnectVC: UIViewController, UITextFieldDelegate {
    private let onConnect: (String) -> Void
    private let last = UserDefaults.standard.string(forKey: "chuks.preview.lastHost")
    private let field = UITextField()
    private let errorLabel = UILabel()
    private let gradient = CAGradientLayer()

    init(onConnect: @escaping (String) -> Void) {
        self.onConnect = onConnect
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        gradient.colors = [UIColor(hex: "0B1120").cgColor, UIColor(hex: "111a2e").cgColor]
        gradient.startPoint = CGPoint(x: 0.5, y: 0); gradient.endPoint = CGPoint(x: 0.5, y: 1)
        view.layer.insertSublayer(gradient, at: 0)

        // The Chuks logo (bundled ChuksLogo.png, same image as the app icon) in a
        // rounded square; falls back to a blue tile if the asset is missing.
        let logo = UIImageView()
        logo.contentMode = .scaleAspectFit
        logo.clipsToBounds = true
        logo.layer.cornerRadius = 18
        if let p = Bundle.main.path(forResource: "ChuksLogo", ofType: "png"), let img = UIImage(contentsOfFile: p) {
            logo.image = img
        } else {
            logo.backgroundColor = UIColor(hex: "4F7DFF")
        }
        logo.translatesAutoresizingMaskIntoConstraints = false
        logo.widthAnchor.constraint(equalToConstant: 72).isActive = true
        logo.heightAnchor.constraint(equalToConstant: 72).isActive = true

        let title = UILabel()
        title.text = "Chuks Preview"
        title.font = .systemFont(ofSize: 28, weight: .bold)
        title.textColor = .white
        title.textAlignment = .center

        let subtitle = UILabel()
        subtitle.text = "Run your Chuks app on this device.\nScan the QR from `chuks dev`."
        subtitle.numberOfLines = 0
        subtitle.font = .systemFont(ofSize: 15)
        subtitle.textColor = UIColor.white.withAlphaComponent(0.6)
        subtitle.textAlignment = .center

        let header = UIStackView(arrangedSubviews: [logo, title, subtitle])
        header.axis = .vertical; header.alignment = .center; header.spacing = 10

        // Manual host field: a dark rounded field + a blue "Connect".
        field.attributedPlaceholder = NSAttributedString(
            string: "192.168.1.5:7799",
            attributes: [.foregroundColor: UIColor.white.withAlphaComponent(0.45)])
        field.textColor = .white
        field.keyboardType = .URL
        field.autocapitalizationType = .none
        field.autocorrectionType = .no
        field.returnKeyType = .go
        field.delegate = self
        field.backgroundColor = UIColor.white.withAlphaComponent(0.08)
        field.layer.cornerRadius = 12
        field.leftView = UIView(frame: CGRect(x: 0, y: 0, width: 14, height: 1)); field.leftViewMode = .always
        field.rightView = UIView(frame: CGRect(x: 0, y: 0, width: 14, height: 1)); field.rightViewMode = .always
        field.translatesAutoresizingMaskIntoConstraints = false
        field.heightAnchor.constraint(equalToConstant: 46).isActive = true

        let connect = UIButton(type: .system)
        connect.setTitle("Connect", for: .normal)
        connect.setTitleColor(UIColor(hex: "4F7DFF"), for: .normal)
        connect.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        connect.setContentHuggingPriority(.required, for: .horizontal)
        connect.setContentCompressionResistancePriority(.required, for: .horizontal)
        connect.addAction(UIAction { [weak self] _ in self?.connectManual() }, for: .touchUpInside)

        let row = UIStackView(arrangedSubviews: [field, connect])
        row.axis = .horizontal; row.alignment = .center; row.spacing = 8

        errorLabel.font = .systemFont(ofSize: 13)
        errorLabel.textColor = UIColor(hex: "FF6B6B")
        errorLabel.textAlignment = .center
        errorLabel.numberOfLines = 0
        errorLabel.isHidden = true

        let stack = UIStackView(arrangedSubviews: [header, makeScanButton(), row, errorLabel])
        stack.axis = .vertical; stack.alignment = .fill; stack.spacing = 22
        if let last = last, !last.isEmpty { stack.addArrangedSubview(makeReconnectButton(last)) }
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 28),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -28),
        ])

        let footer = UILabel()
        footer.text = "On the same Wi-Fi as your computer"
        footer.font = .systemFont(ofSize: 12)
        footer.textColor = UIColor.white.withAlphaComponent(0.35)
        footer.textAlignment = .center
        footer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(footer)
        NSLayoutConstraint.activate([
            footer.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            footer.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -8),
        ])
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        gradient.frame = view.bounds
    }

    // A filled, tappable "Scan QR code" button (a rounded view + an icon/label row).
    private func makeScanButton() -> UIView {
        let container = UIView()
        container.backgroundColor = UIColor(hex: "4F7DFF")
        container.layer.cornerRadius = 14
        let icon = UIImageView(image: UIImage(systemName: "qrcode.viewfinder"))
        icon.tintColor = .white
        icon.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 20, weight: .semibold)
        let label = UILabel(); label.text = "Scan QR code"
        label.font = .systemFont(ofSize: 17, weight: .semibold); label.textColor = .white
        let hs = UIStackView(arrangedSubviews: [icon, label])
        hs.axis = .horizontal; hs.spacing = 10; hs.alignment = .center
        hs.isUserInteractionEnabled = false
        hs.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(hs)
        NSLayoutConstraint.activate([
            hs.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            hs.topAnchor.constraint(equalTo: container.topAnchor, constant: 15),
            hs.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -15),
        ])
        container.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(scanTapped)))
        return container
    }

    // The optional "Reconnect to <host>" shortcut shown when a last host is known.
    private func makeReconnectButton(_ host: String) -> UIView {
        let icon = UIImageView(image: UIImage(systemName: "clock.arrow.circlepath"))
        icon.tintColor = UIColor.white.withAlphaComponent(0.55)
        icon.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 13)
        let label = UILabel(); label.text = "Reconnect to \(host)"
        label.font = .systemFont(ofSize: 14); label.textColor = UIColor.white.withAlphaComponent(0.55)
        let hs = UIStackView(arrangedSubviews: [icon, label])
        hs.axis = .horizontal; hs.spacing = 6; hs.alignment = .center
        hs.isUserInteractionEnabled = false
        hs.translatesAutoresizingMaskIntoConstraints = false
        let wrap = UIView()
        wrap.addSubview(hs)
        NSLayoutConstraint.activate([
            hs.centerXAnchor.constraint(equalTo: wrap.centerXAnchor),
            hs.topAnchor.constraint(equalTo: wrap.topAnchor),
            hs.bottomAnchor.constraint(equalTo: wrap.bottomAnchor),
        ])
        wrap.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(reconnectTapped)))
        return wrap
    }

    @objc private func scanTapped() {
        errorLabel.isHidden = true
        let vc = ScannerVC()
        vc.onScan = { [weak self] value in
            self?.dismiss(animated: true)
            guard let self = self else { return }
            if let host = parseDevServer(value) { self.onConnect(host) }
            else { self.showError("That QR is not a Chuks dev server.") }
        }
        vc.onCancel = { [weak self] in self?.dismiss(animated: true) }
        vc.modalPresentationStyle = .fullScreen
        present(vc, animated: true)
    }

    @objc private func reconnectTapped() {
        if let last = last, !last.isEmpty { onConnect(last) }
    }

    private func connectManual() {
        guard let host = parseDevServer(field.text ?? "") else {
            showError("Enter a host like 192.168.1.5:7799"); return
        }
        errorLabel.isHidden = true
        onConnect(host)
    }

    private func showError(_ msg: String) { errorLabel.text = msg; errorLabel.isHidden = false }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool { connectManual(); return true }
}

// ─── QR scanner ───────────────────────────────────────────────────────────────
// A full-screen camera that reads a QR and hands its value back through onScan; the
// close button (or a failed camera) reports onCancel.
final class ScannerVC: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScan: ((String) -> Void)?
    var onCancel: (() -> Void)?
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var done = false

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
        // Close (cancel) button.
        let close = UIButton(type: .system)
        close.setImage(UIImage(systemName: "xmark.circle.fill"), for: .normal)
        close.tintColor = UIColor.white.withAlphaComponent(0.9)
        close.translatesAutoresizingMaskIntoConstraints = false
        close.addAction(UIAction { [weak self] _ in self?.onCancel?() }, for: .touchUpInside)
        view.addSubview(close)
        NSLayoutConstraint.activate([
            close.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            close.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            close.widthAnchor.constraint(equalToConstant: 32),
            close.heightAnchor.constraint(equalToConstant: 32),
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
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill; layer.frame = view.layer.bounds
        view.layer.insertSublayer(layer, at: 0)
        preview = layer
        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput objects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !done,
              let obj = objects.first as? AVMetadataMachineReadableCodeObject,
              let value = obj.stringValue else { return }
        done = true
        AudioServicesPlaySystemSound(1057)   // capture tick
        DispatchQueue.main.async { self.onScan?(value) }
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
private extension UIColor {
    convenience init(hex: String) {
        var h = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        if h.count == 3 { h = h.map { "\($0)\($0)" }.joined() }
        var v: UInt64 = 0; Scanner(string: h).scanHexInt64(&v)
        self.init(red: CGFloat((v >> 16) & 0xff) / 255,
                  green: CGFloat((v >> 8) & 0xff) / 255,
                  blue: CGFloat(v & 0xff) / 255, alpha: 1)
    }
}
#endif
