// The seam a Chuks package uses to add a native capability.
//
// A capability reaches the host as `X|token|namespace.name|args` and is dispatched by
// name. Everything the framework implements is a case in CardsVC's switch; everything a
// PACKAGE implements is a module registered here, owning a namespace of its own. To the
// engine the two are indistinguishable: a Chuks wrapper emits a command, something
// answers it.
//
// Packages exist for capabilities core should not carry. The line is not size (iOS
// system frameworks are dynamically linked, so an unused import costs an app almost
// nothing) but SIGNING: a capability needing an entitlement cannot live in core, because
// core has to stay buildable with the wildcard development profile that makes the dev
// loop painless, and a wildcard profile can never carry an entitlement. Camera,
// location and media stay in core deliberately; Health and NFC are the ones that cannot.
import UIKit
import Foundation

/// A command's arguments, already parsed.
///
/// They cross as JSON, so they arrive as themselves: a string is a string, a number is a
/// number, and several arguments are an object that names them. Reading one by name is
/// the whole API, and a value that is not what the capability expects is reported by
/// name rather than defaulted to zero, which is the difference between a capability that
/// says what is wrong and one that quietly acts on nothing.
public final class ChuksArgs {
    private let obj: [String: Any]
    private let scalar: Any?
    private let cap: String
    private let token: String
    private weak var host: AnyObject?

    /// The single argument as a string, which is what a one-argument capability wants.
    public let str: String

    public init(_ raw: String, _ cap: String, _ token: String, _ host: ChuksArgFailer?) {
        self.cap = cap; self.token = token; self.host = host
        var o: [String: Any] = [:]
        var sc: Any? = nil
        var text = ""
        if !raw.isEmpty, let d = raw.data(using: .utf8),
           let v = try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed]) {
            if let dict = v as? [String: Any] { o = dict } else { sc = v; text = ChuksArgs.text(v) }
        }
        self.obj = o; self.scalar = sc; self.str = text
    }

    private static func text(_ v: Any) -> String {
        if let s = v as? String { return s }
        if let b = v as? Bool { return b ? "1" : "0" }
        if let n = v as? NSNumber { return n.stringValue }
        return ""
    }

    /// A named string argument.
    public func s(_ key: String, _ fallback: String = "") -> String {
        if let v = obj[key] as? String { return v }
        if let v = obj[key] { return ChuksArgs.text(v) }
        return fallback
    }
    /// A named number, or the single argument when `key` is empty. Nil, and a message
    /// naming the argument, when it is not a number.
    public func num(_ key: String = "") -> Double? {
        let v: Any? = key.isEmpty ? scalar : obj[key]
        if let n = v as? NSNumber { return n.doubleValue }
        if let s = v as? String, let d = Double(s) { return d }
        (host as? ChuksArgFailer)?.argBad(token, cap, key.isEmpty ? "argument" : key, "number",
                                          v == nil ? "(missing)" : ChuksArgs.text(v!))
        return nil
    }
    public func int(_ key: String = "") -> Int? {
        guard let d = num(key) else { return nil }
        return Int(d)
    }
    /// A named boolean, or the single argument. A missing one is false, since every
    /// boolean argument here means "turn this on", and absent means do not.
    public func bool(_ key: String = "") -> Bool {
        let v: Any? = key.isEmpty ? scalar : obj[key]
        if let b = v as? Bool { return b }
        if let n = v as? NSNumber { return n.intValue != 0 }
        if let s = v as? String { return s == "1" || s == "true" }
        return false
    }
    /// Whether the command carried this argument at all.
    public func has(_ key: String) -> Bool { obj[key] != nil }
}

/// How a capability reports an argument it could not use.
public protocol ChuksArgFailer: AnyObject {
    /// Fail the token. Reaches Chuks through the error channel, not as a value.
    func fail(_ token: String, _ message: String)
}

public extension ChuksArgFailer {
    /// An argument arrived that is not the type the capability expects.
    func argBad(_ token: String, _ cap: String, _ name: String, _ type: String, _ raw: String) {
        let message = "\(cap): \(name) should be a \(type), got \"\(raw)\""
        // A fire-and-forget command has no token to fail, so it says so in the log
        // rather than vanishing.
        if token == "0" { NSLog("chuks: %@", message) } else { fail(token, message) }
    }
}

/// What a module is handed: two answer channels and somewhere to present from, which is
/// all any capability has ever needed from this host.
public protocol ChuksModuleHost: ChuksArgFailer {
    /// Answer the token that asked. Fires the Chuks callback with this payload.
    func resolve(_ token: String, _ payload: String)
    /// The view controller a capability presents from (a picker, a permission sheet).
    var presenter: UIViewController { get }
    /// Register cleanup for a STREAMING capability. Chuks cancels a token when the
    /// subscription is cancelled or its component unmounts, and the host runs this then.
    /// Without it a module's query or observer would outlive the screen that asked.
    func onCancel(_ token: String, _ teardown: @escaping () -> Void)
}

/// One package's native capability. `namespace` is the part before the dot in every
/// command it answers, so `health` claims `health.read`, `health.authorize` and the rest.
public protocol ChuksNativeModule: AnyObject {
    static var namespace: String { get }
    init(host: ChuksModuleHost)
    /// Return true when the command was handled. False leaves it unanswered, which is
    /// what an unknown name inside a claimed namespace should do.
    ///
    /// `args` is the single argument as a string, which is all a one-argument
    /// capability wants; `a` reads several of them by name, already parsed and typed.
    func handle(_ token: String, _ cap: String, _ args: String, _ a: ChuksArgs) -> Bool
}

/// Routes a command to whichever installed package claims its namespace.
///
/// Modules are constructed on first use, never at launch. Building a HealthKit store or
/// a Bluetooth central has side effects, and an app that installs a package but never
/// calls it should not pay them.
final class ChuksModuleRegistry {
    private unowned let host: ChuksModuleHost
    private var types: [String: ChuksNativeModule.Type] = [:]
    private var live: [String: ChuksNativeModule] = [:]

    init(host: ChuksModuleHost) {
        self.host = host
        for t in chuksPackageModules() { types[t.namespace] = t }
    }

    /// True when a package answered. False means no package claims this namespace and
    /// the caller should treat the command as unknown, exactly as before.
    func handle(_ token: String, _ cap: String, _ args: String, _ a: ChuksArgs) -> Bool {
        guard let dot = cap.firstIndex(of: ".") else { return false }
        let ns = String(cap[cap.startIndex..<dot])
        if live[ns] == nil {
            guard let t = types[ns] else { return false }
            live[ns] = t.init(host: host)
        }
        return live[ns]?.handle(token, cap, args, a) ?? false
    }
}
