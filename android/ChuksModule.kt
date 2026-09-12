// The seam a Chuks package uses to add a native capability. The Android half of
// ios/ChuksModule.swift, and deliberately the same shape: a package that adds a
// capability writes one class per platform against these two interfaces, and nothing
// else in either host changes.
//
// A capability reaches the host as `X|token|namespace.name|args` and is dispatched by
// name. What the framework implements is a branch in MainActivity's `when`; what a
// PACKAGE implements is a module registered here, owning a namespace of its own.
package com.chuks.app

import android.app.Activity

/// What a module is handed: two answer channels and the Activity, which is what an
/// Android capability needs for permissions, system services and intents.
/// A command's arguments, already parsed.
///
/// They cross as JSON, so they arrive as themselves: a string is a string, a number is a
/// number, and several arguments are an object that names them. Reading one by name is
/// the whole API, and a value that is not what the capability expects is reported by name
/// rather than defaulted to zero.
class ChuksArgs(raw: String, private val cap: String, private val token: String,
                private val host: ChuksArgFailer?) {
    private var obj: org.json.JSONObject? = null
    private var scalar: Any? = null

    /** The single argument as a string, which is what a one-argument capability wants. */
    val str: String

    init {
        var text = ""
        if (raw.isNotEmpty()) {
            try {
                when (val v = org.json.JSONTokener(raw).nextValue()) {
                    is org.json.JSONObject -> obj = v
                    else -> { scalar = v; text = textOf(v) }
                }
            } catch (e: Throwable) { /* not JSON: no arguments */ }
        }
        str = text
    }

    private fun textOf(v: Any?): String = when (v) {
        null -> ""
        is Boolean -> if (v) "1" else "0"
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    private fun raw(key: String): Any? =
        if (key.isEmpty()) scalar else obj?.opt(key)?.takeIf { it != org.json.JSONObject.NULL }

    /** A named string argument. */
    fun s(key: String, fallback: String = ""): String {
        val v = raw(key) ?: return fallback
        return textOf(v)
    }
    /** A named number, or the single argument when `key` is empty. Null, and a message
     *  naming the argument, when it is not a number. */
    fun num(key: String = ""): Double? {
        val v = raw(key)
        if (v is Number) return v.toDouble()
        if (v is String) v.toDoubleOrNull()?.let { return it }
        host?.argBad(token, cap, if (key.isEmpty()) "argument" else key, "number",
                     if (v == null) "(missing)" else textOf(v))
        return null
    }
    fun int(key: String = ""): Int? = num(key)?.toInt()
    /** A named boolean, or the single argument. A missing one is false, since every
     *  boolean argument here means "turn this on", and absent means do not. */
    fun bool(key: String = ""): Boolean = when (val v = raw(key)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "1" || v == "true"
        else -> false
    }
    /** Whether the command carried this argument at all. */
    fun has(key: String): Boolean = raw(key) != null
}

/// How a capability reports an argument it could not use.
interface ChuksArgFailer {
    /** Fail the token. Reaches Chuks through the error channel, not as a value. */
    fun fail(token: String, message: String)

    /** An argument arrived that is not the type the capability expects. */
    fun argBad(token: String, cap: String, name: String, type: String, raw: String) {
        val message = "$cap: $name should be a $type, got \"$raw\""
        // A fire-and-forget command has no token to fail, so it says so in the log
        // rather than vanishing.
        if (token == "0") android.util.Log.w("chuks", message) else fail(token, message)
    }
}

interface ChuksModuleHost : ChuksArgFailer {
    /** Answer the token that asked. Fires the Chuks callback with this payload. */
    fun resolve(token: String, payload: String)
    /** The Activity a capability needs for getSystemService, permissions and intents. */
    val activity: Activity
    /** Register cleanup for a STREAMING capability. Chuks cancels a token when the
     *  subscription is cancelled or its component unmounts, and the host runs this then.
     *  Without it a module's listener would outlive the screen that asked. */
    fun onCancel(token: String, teardown: () -> Unit)
    /** Ask for an Android runtime permission and answer the token with "granted" or
     *  "denied" when the user decides. The result arrives in the Activity's
     *  onRequestPermissionsResult, which only the host can receive, so a module cannot
     *  do this itself. Pass the full permission strings, e.g. android.Manifest.permission.
     *  BODY_SENSORS. */
    fun requestPermission(token: String, permissions: Array<String>)
}

/// A view kind a package supplies. The Android half of the protocol in
/// ios/ChuksModule.swift, and deliberately the same shape.
///
/// `apply` is called on every prop change AND when a recycled list cell is rebound to a
/// different row, so it must set every property it cares about rather than only the ones
/// that look different. A view that skips a property inherits the previous row's value,
/// which is the oldest bug in this framework.
interface ChuksNativeView {
    /** The view the host puts in the tree. Created once, with the instance. */
    val view: android.view.View
    /** The package's own props, already parsed. Layout and background are the
     *  framework's business and have been applied already. */
    fun apply(a: ChuksArgs)
    /** The node left the tree. Stop timers, close sessions, release what you hold. */
    fun destroy() {}
}

/// What a view is handed. Deliberately small: a view draws, reports what the user did,
/// and does not answer requests. One of these per view, bound to the node that owns it,
/// so a view never has to know its own id.
interface ChuksViewHost {
    /** The Activity, for a view that needs a Context or opens something. */
    val activity: Activity
    /** Tell Chuks the user did something. `name` is the event the app registered on the
     *  component ("change", "scan", "regionChange"); `value` is handed to that closure.
     *  A name nothing is listening for costs a map miss and does nothing, so a view may
     *  report freely without knowing what the app subscribed to. */
    fun emit(name: String, value: String)
}

/// One package's native capability. The namespace it claims is declared in the package's
/// chuks.json and baked into the generated registry, not read from here, so the build can
/// know it without loading the class.
interface ChuksNativeModule {
    /** Return true when the command was handled. False leaves it unanswered, which is
     *  what an unknown name inside a claimed namespace should do.
     *
     *  `args` is the single argument as a string, which is all a one-argument
     *  capability wants; `a` reads several of them by name, already parsed and typed. */
    fun handle(token: String, cap: String, args: String, a: ChuksArgs): Boolean
}

/// Routes a command to whichever installed package claims its namespace.
///
/// Modules are constructed on first use, never at launch: building a Health Connect
/// client or a Bluetooth adapter has side effects, and an app that installs a package
/// but never calls it should not pay them.
class ChuksModuleRegistry(private val host: ChuksModuleHost) {
    private val live = HashMap<String, ChuksNativeModule>()
    private val factories = ChuksPackageModules.factories()

    /** True when a package answered; false means no package claims this namespace. */
    fun handle(token: String, cap: String, args: String, a: ChuksArgs): Boolean {
        val ns = cap.substringBefore('.', "")
        if (ns.isEmpty()) return false
        val m = live[ns] ?: factories[ns]?.invoke(host)?.also { live[ns] = it } ?: return false
        return m.handle(token, cap, args, a)
    }
}
