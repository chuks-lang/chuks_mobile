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
/// How a generated argument decoder reports an argument it could not use. Split out from
/// the module host so the generated code depends on the smallest possible thing.
interface ChuksArgFailer {
    /** Fail the token. Reaches Chuks through the error channel, not as a value. */
    fun fail(token: String, message: String)

    /** The command carried fewer arguments than the capability takes. Almost always a
     *  call site and a decoder that have drifted apart, which is what generating both
     *  from one declaration is meant to prevent. */
    fun argMissing(token: String, cap: String, want: Int, got: Int) {
        report(token, "$cap takes $want argument(s), got $got")
    }
    /** An argument arrived that is not the type the capability declared. */
    fun argBad(token: String, cap: String, name: String, type: String, raw: String) {
        report(token, "$cap: $name should be ${if (type == "int") "an" else "a"} $type, got \"$raw\"")
    }
    /** A fire-and-forget command has no token to fail, so it says so in the log rather
     *  than vanishing. */
    private fun report(token: String, message: String) {
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

/// One package's native capability. The namespace it claims is declared in the package's
/// chuks.json and baked into the generated registry, not read from here, so the build can
/// know it without loading the class.
interface ChuksNativeModule {
    /** Return true when the command was handled. False leaves it unanswered, which is
     *  what an unknown name inside a claimed namespace should do.
     *
     *  `args` is the first argument and `fields` is all of them, already unpacked from
     *  the wire format. A one-argument capability reads `args` and ignores the rest; a
     *  capability taking several reads `fields`, and never has to pick a separator or
     *  worry about what a user might type into one. */
    fun handle(token: String, cap: String, args: String, fields: List<String>): Boolean
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
    fun handle(token: String, cap: String, args: String, fields: List<String>): Boolean {
        val ns = cap.substringBefore('.', "")
        if (ns.isEmpty()) return false
        val m = live[ns] ?: factories[ns]?.invoke(host)?.also { live[ns] = it } ?: return false
        return m.handle(token, cap, args, fields)
    }
}
