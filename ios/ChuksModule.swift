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

/// A view kind a package supplies.
///
/// The framework's own kinds (Text, Image, Scroll) are cases in the host's `make`. This
/// is the same thing from outside: the host creates one when a node of the package's
/// kind appears, hands it its props whenever they change, and tells it when the node
/// goes away.
///
/// `apply` is called on every prop change AND when a recycled list cell is rebound to a
/// different row, so it must set every property it cares about rather than only the ones
/// that look different. A view that skips a property inherits the previous row's value,
/// which is the oldest bug in this framework.
public protocol ChuksNativeView: AnyObject {
    /// The kind this claims, namespaced like a capability: "health.ring".
    static var kind: String { get }
    init(host: ChuksViewHost)
    /// The view the host puts in the tree. Created once, in init.
    var view: UIView { get }
    /// The package's own props, already parsed. Layout and background are the
    /// framework's business and have been applied already.
    func apply(_ a: ChuksArgs)
    /// Do something, once, because the app asked this instance to: scroll to an index,
    /// present a sheet, seek to a second, play it again. Props cannot say those: setting
    /// a prop to the value it already holds changes nothing, so "again" has no spelling.
    /// The name is the package's own and the arguments arrive parsed, like a capability's.
    func command(_ name: String, _ a: ChuksArgs)
    /// The node left the tree. Stop timers, close sessions, release what you hold.
    func destroy()
}

public extension ChuksNativeView {
    func command(_ name: String, _ a: ChuksArgs) {}
    func destroy() {}
}

/// A package view that knows how big it wants to be.
///
/// Conform to this and the app can place the view with no `w`/`h` at all: the layout asks
/// during its own pass, the way it asks a Text. A badge, a chip, a legend, an icon, a
/// chart that is as tall as its rows, anything whose size is a property of its content
/// rather than of the screen. An explicit `w`/`h` from the app still wins, as it does for
/// every other view.
public protocol ChuksMeasurableView: ChuksNativeView {
    /// The size this view wants for the width it is offered. `maxWidth` is
    /// `.greatestFiniteMagnitude` when the layout has not constrained it, so a view that
    /// wants its natural width can ignore the argument entirely.
    ///
    /// Called during layout, possibly several times in one pass, so it must be cheap and
    /// must not change the view tree. When the content changes and the answer would
    /// differ, call `invalidateSize()` on the host rather than measuring eagerly.
    func measure(maxWidth: CGFloat) -> CGSize
}

/// A package view that chooses which of its own layers holds the app's children.
///
/// By default a child goes straight into the view the package returned, which is right
/// for a plain container. Implement this when the package's root is not where children
/// belong: a card with its own decoration layer above them, a clipping or masking layer,
/// a view that wraps someone else's SDK surface.
///
/// The framework still LAYS the children out and writes their frames, in the package
/// root's coordinate space. So the layer you hand back must cover the root; use this to
/// choose the layer, not to move the children. Moving them is the layout's job, and the
/// app already controls it with ordinary layout props.
public protocol ChuksContainerView: ChuksNativeView {
    /// Put `child` at `index` among the children the app gave this view.
    func insertChild(_ child: UIView, at index: Int)
    /// Take it out again. Defaults to `child.removeFromSuperview()`.
    func removeChild(_ child: UIView)
}

public extension ChuksContainerView {
    func removeChild(_ child: UIView) { child.removeFromSuperview() }
}

/// What a view is handed. Deliberately small: a view draws, reports what the user did,
/// and does not answer requests. One of these per view, bound to the node that owns it,
/// so a view never has to know its own id.
public protocol ChuksViewHost: AnyObject {
    /// The view controller to present from, for a view that opens something.
    var presenter: UIViewController { get }
    /// Tell Chuks the user did something. `name` is the event the app registered on the
    /// component ("change", "scan", "regionChange"); `value` is handed to that closure.
    /// A name nothing is listening for costs a dictionary miss and does nothing, so a
    /// view may report freely without knowing what the app subscribed to.
    func emit(_ name: String, _ value: String)
    /// This view's content changed, and a self-sizing view would now answer `measure`
    /// differently. Cheap and idempotent: the layout re-runs once, on the next pass.
    /// Does nothing for a view that does not size itself.
    func invalidateSize()
}

public extension ChuksViewHost {
    /// Report an event that carries more than one thing: which star, which index, which
    /// region, and what it was before.
    ///
    /// The payload crosses as JSON, the same way a command's arguments arrive, and the
    /// package's Chuks half decodes it into a type it declares. Encoding it here rather
    /// than leaving the view to build a string is the point: a view that joins its
    /// values with a comma works until a value contains a comma, and every framework
    /// that leaves this to the author collects one of those bugs per kit.
    func emit(_ name: String, _ payload: [String: Any]) {
        guard JSONSerialization.isValidJSONObject(payload),
              let d = try? JSONSerialization.data(withJSONObject: payload),
              let s = String(data: d, encoding: .utf8) else {
            // A value JSON cannot carry (a UIView, a Date, NaN) is a programming error in
            // the package, and silence would make it a mystery in the app instead.
            emit(name, "{\"error\":\"chuks: \(name) payload is not JSON-encodable\"}")
            return
        }
        emit(name, s)
    }
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
