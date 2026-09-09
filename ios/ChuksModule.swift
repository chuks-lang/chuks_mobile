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

/// How a generated argument decoder reports an argument it could not use. Split out
/// from the module host so the generated code depends on the smallest possible thing.
public protocol ChuksArgFailer: AnyObject {
    /// Fail the token. Reaches Chuks through the error channel, not as a value.
    func fail(_ token: String, _ message: String)
}

public extension ChuksArgFailer {
    /// The command carried fewer arguments than the capability takes. Almost always a
    /// call site and a decoder that have drifted apart, which is what generating both
    /// from one declaration is meant to prevent.
    func argMissing(_ token: String, _ cap: String, _ want: Int, _ got: Int) {
        report(token, "\(cap) takes \(want) argument(s), got \(got)")
    }
    /// An argument arrived that is not the type the capability declared.
    func argBad(_ token: String, _ cap: String, _ name: String, _ type: String, _ raw: String) {
        report(token, "\(cap): \(name) should be \(type == "int" ? "an" : "a") \(type), got \"\(raw)\"")
    }
    /// A fire-and-forget command has no token to fail, so it says so in the log rather
    /// than vanishing.
    private func report(_ token: String, _ message: String) {
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
    /// `args` is the first argument and `fields` is all of them, already unpacked from
    /// the wire format. A one-argument capability reads `args` and ignores the rest; a
    /// capability taking several reads `fields`, and never has to pick a separator or
    /// worry about what a user might type into one.
    func handle(_ token: String, _ cap: String, _ args: String, _ fields: [String]) -> Bool
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
    func handle(_ token: String, _ cap: String, _ args: String, _ fields: [String]) -> Bool {
        guard let dot = cap.firstIndex(of: ".") else { return false }
        let ns = String(cap[cap.startIndex..<dot])
        if live[ns] == nil {
            guard let t = types[ns] else { return false }
            live[ns] = t.init(host: host)
        }
        return live[ns]?.handle(token, cap, args, fields) ?? false
    }
}
