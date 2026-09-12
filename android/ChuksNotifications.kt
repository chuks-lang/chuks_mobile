// Local notifications for Chuks Mobile, the Android half. Posting, scheduling,
// cancelling, and delivering the tap back to the app.
//
// Scheduling is the part with traps, and this follows what expo-notifications
// learned in production:
//
//   1. The OS fires it, not the app. A scheduled notification is an AlarmManager alarm
//      whose receiver builds and posts the notification from a STORED request, so it
//      fires with the app killed. The request is written to SharedPreferences before
//      the alarm is armed, never after: an alarm whose request is missing would fire
//      into nothing.
//   2. Alarms do not survive a reboot. The store does, so on BOOT_COMPLETED (and on
//      every app start, which costs nothing) every stored request is re-armed.
//   3. Exact alarms are a permission since Android 12. Without SCHEDULE_EXACT_ALARM
//      (the "exactAlarms" kind in app.json) the alarm is setAndAllowWhileIdle, which
//      Doze may hold for minutes; with it, setExactAndAllowWhileIdle. Timing is a
//      floor either way.
//   4. Since Android 8 every notification belongs to a channel, and the channel, not
//      the notification, owns importance and sound. A default channel is created on
//      first use so a notification that names none still appears.
//   5. The tap that LAUNCHED the app arrives as the Activity's intent before any
//      Chuks code has run. It is held in `pending` for the first subscriber, the same
//      way the launch deep link is.
package com.chuks.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject

object ChuksNotif {
    const val DEFAULT_CHANNEL = "chuks"
    private const val PREFS = "chuks_notifications"
    private const val EXTRA_ID = "chuks_notif_id"
    private const val EXTRA_DATA = "chuks_notif_data"

    // A tap nobody has subscribed to yet, and the subscribers once there are some.
    var pending: String? = null
    var onResponse: ((String) -> Unit)? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    // Android wants an int id. A stable hash of the string is enough: the same id
    // replaces the same notification, which is the contract.
    private fun intId(id: String): Int = id.hashCode()

    // ---- channels ------------------------------------------------------------

    fun ensureChannel(ctx: Context, id: String, name: String, importance: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val level = when (importance) {
            "low"  -> NotificationManager.IMPORTANCE_LOW
            "high" -> NotificationManager.IMPORTANCE_HIGH
            else   -> NotificationManager.IMPORTANCE_DEFAULT
        }
        // createNotificationChannel is idempotent on id, and the importance of an
        // existing channel is not changed by calling it again: the OS owns that.
        nm(ctx).createNotificationChannel(NotificationChannel(id, name, level))
    }

    private fun channelOrDefault(ctx: Context, channel: String): String {
        if (channel.isNotEmpty()) return channel
        ensureChannel(ctx, DEFAULT_CHANNEL, "Notifications", "default")
        return DEFAULT_CHANNEL
    }

    // ---- posting -------------------------------------------------------------

    fun post(ctx: Context, id: String, title: String, body: String, data: String, channel: String) {
        val chan = channelOrDefault(ctx, channel)
        // Tapping opens (or resumes) the app with the id and data as extras. singleTask
        // means a running app gets onNewIntent, a cold one gets them in onCreate.
        val open = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_ID, id).putExtra(EXTRA_DATA, data)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(ctx, intId(id), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(ctx, chan)
                else @Suppress("DEPRECATION") Notification.Builder(ctx)
        val n = b.setContentTitle(title).setContentText(body)
                 .setSmallIcon(android.R.drawable.ic_dialog_info)
                 .setContentIntent(tap)
                 .setAutoCancel(true)
                 .build()
        nm(ctx).notify(intId(id), n)
    }

    fun dismiss(ctx: Context, id: String) = nm(ctx).cancel(intId(id))
    fun dismissAll(ctx: Context) = nm(ctx).cancelAll()

    // ---- scheduling ----------------------------------------------------------

    fun schedule(ctx: Context, id: String, title: String, body: String, atMs: Long, data: String, channel: String) {
        // Store first (trap 1), then arm.
        val rec = JSONObject().put("title", title).put("body", body).put("atMs", atMs)
                              .put("data", data).put("channel", channel)
        prefs(ctx).edit().putString(id, rec.toString()).apply()
        arm(ctx, id, atMs)
    }

    private fun alarmIntent(ctx: Context, id: String): PendingIntent {
        val fire = Intent(ctx, ChuksNotifReceiver::class.java).setAction("com.chuks.NOTIFY").putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(ctx, intId(id), fire,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun arm(ctx: Context, id: String, atMs: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(ctx, id)
        val at = maxOf(atMs, System.currentTimeMillis() + 1000)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            // canScheduleExactAlarms said yes and the OS said no anyway (a policy change
            // between the two calls). Inexact is still a notification.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context, id: String) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(ctx, id))
        prefs(ctx).edit().remove(id).apply()
    }

    fun cancelAll(ctx: Context) {
        val p = prefs(ctx)
        for (id in p.all.keys) {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(ctx, id))
        }
        p.edit().clear().apply()
    }

    /** One line per pending request: "id\ttitle\tfireAtMs", oldest fire time first. */
    fun scheduled(ctx: Context): String {
        val rows = ArrayList<Triple<Long, String, String>>()
        for ((id, v) in prefs(ctx).all) {
            val rec = try { JSONObject(v as String) } catch (e: Exception) { continue }
            rows.add(Triple(rec.optLong("atMs"), id, rec.optString("title")))
        }
        rows.sortBy { it.first }
        return rows.joinToString("\n") { "${ChuksWire.esc(it.second)}\t${ChuksWire.esc(it.third)}\t${it.first}" }
    }

    /** Fire one stored request now: post it and forget it. Called by the receiver. */
    fun fire(ctx: Context, id: String) {
        android.util.Log.i("Chuks", "notif.fire: id=" + id + " pid=" + android.os.Process.myPid())
        val p = prefs(ctx)
        val v = p.getString(id, null) ?: return
        p.edit().remove(id).apply()
        val rec = try { JSONObject(v) } catch (e: Exception) { return }
        post(ctx, id, rec.optString("title"), rec.optString("body"), rec.optString("data"), rec.optString("channel"))
    }

    /** Re-arm everything in the store: after a reboot, and at every app start. A request
     *  whose time has passed while the device was off fires now rather than never. */
    fun rearmAll(ctx: Context) {
        for ((id, v) in prefs(ctx).all) {
            val rec = try { JSONObject(v as String) } catch (e: Exception) { continue }
            arm(ctx, id, rec.optLong("atMs"))
        }
    }

    // ---- the tap -------------------------------------------------------------

    /** If this intent is a notification tap, deliver it and answer true. */
    fun deliverTap(intent: Intent?): Boolean {
        android.util.Log.i("Chuks", "notif.deliverTap: extras=" + (intent?.extras?.keySet()?.joinToString(",") ?: "none") + " onResponse=" + (onResponse != null))
        val id = intent?.getStringExtra(EXTRA_ID) ?: return false
        val data = intent.getStringExtra(EXTRA_DATA) ?: ""
        // Clear them so a configuration change re-delivering the same intent does not
        // replay the tap.
        intent.removeExtra(EXTRA_ID); intent.removeExtra(EXTRA_DATA)
        val payload = id + "\t" + data
        val cb = onResponse
        if (cb != null) cb(payload) else pending = payload
        return true
    }

    // ---- badge ---------------------------------------------------------------
    // Android has no badge API. The count is kept so badge() answers what setBadge()
    // set, and shown as the notification number where the launcher chooses to.
    fun setBadge(ctx: Context, n: Int) { prefs(ctx).edit().putInt("__badge", n).apply() }
    fun badge(ctx: Context): Int = prefs(ctx).getInt("__badge", 0)
}

/** Fires a scheduled notification when its alarm goes off, and re-arms every stored
 *  one after a reboot. Runs with no Activity alive, which is the point. */
class ChuksNotifReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> ChuksNotif.rearmAll(ctx)
            "com.chuks.NOTIFY" -> intent.getStringExtra("chuks_notif_id")?.let { ChuksNotif.fire(ctx, it) }
        }
    }
}
