// Background location on Android: a foreground service, because that is the only way the
// platform allows it.
//
// The notification is not a design choice we can revisit. Android grants location with
// the screen off only to a foreground service, and a foreground service must show a
// notification for its whole life. That is the deal: tell the user what you are doing and
// you may keep tracking. Any framework offering silent background location is either
// using a hole Google periodically closes, or is about to stop working.
//
// LocationManager rather than the fused provider, for the same reason the scheduler is
// JobScheduler rather than WorkManager: fused lives in Google Play services, which needs
// Gradle, which this toolchain does not use. LocationManager is a platform API and needs
// nothing.
package com.chuks.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log

/// Shared with the Activity: the service delivers fixes, the Activity owns the engine
/// token they belong to.
object ChuksLocation {
    var token: String = ""
    /// Set by the Activity so a fix can reach the engine from the service. The engine is
    /// in the same process either way, so nothing is serialized or marshalled.
    var deliver: ((String, String) -> Unit)? = null
    var running = false
}

class ChuksLocationService : Service() {
    private var lm: LocationManager? = null
    private var listener: LocationListener? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("title") ?: "Location"
        val body = intent?.getStringExtra("body") ?: ""
        startInForeground(title, body)

        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = manager
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            deliverError("location unavailable")
            stopSelf()
            return START_NOT_STICKY
        }
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) { deliverFix(loc) }
            override fun onProviderDisabled(p: String) {}
            override fun onProviderEnabled(p: String) {}
            @Deprecated("kept for older API levels")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        listener = l
        try {
            manager.requestLocationUpdates(provider, 1000L, 0f, l, Looper.getMainLooper())
        } catch (e: SecurityException) {
            deliverError("location permission denied")
            stopSelf()
            return START_NOT_STICKY
        }
        ChuksLocation.running = true
        // START_STICKY: if Android kills us under pressure mid-walk, restart and keep
        // recording rather than silently ending the session.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.let { l -> try { lm?.removeUpdates(l) } catch (e: Throwable) {} }
        listener = null
        ChuksLocation.running = false
    }

    private fun deliverFix(loc: Location) {
        // Same six fields as location.watch, so app code parsing a fix does not care
        // which of the two produced it.
        val s = "${loc.latitude},${loc.longitude},${loc.accuracy},${loc.altitude},${loc.speed},${loc.bearing}"
        ChuksLocation.deliver?.invoke(ChuksLocation.token, s)
    }
    private fun deliverError(msg: String) {
        Log.w("chuks-location", msg)
    }

    private fun startInForeground(title: String, body: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // LOW rather than DEFAULT: the notification has to be visible, it does not
            // have to make a sound every time tracking starts.
            val ch = NotificationChannel(CHANNEL, "Location tracking", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        // Tapping it returns to the app, which is what a user reaches for when they see it.
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val pi = if (open != null)
            PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE) else null

        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setContentTitle(title)
        b.setContentText(body)
        b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
        b.setOngoing(true)
        if (pi != null) b.setContentIntent(pi)

        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14 requires the type, and REFUSES to start the service without it.
            startForeground(NOTIF_ID, b.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, b.build())
        }
    }

    companion object {
        const val CHANNEL = "chuks.location"
        const val NOTIF_ID = 4711
    }
}
