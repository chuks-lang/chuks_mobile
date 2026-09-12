// Calendar for Chuks Mobile, the Android half. One event is
// "title\tstartMs\tendMs\tid\tcalendarId\tlocation\tnotes\tallDay", the same line iOS makes.
//
// The traps, most recorded by expo-calendar:
//
//   1. A range read goes through the Instances table, not Events. Instances is where
//      the provider expands a repeating event into its occurrences; Events has one
//      row with an RRULE and DTSTART of the first, so querying it by time misses every
//      repeat after the first. iOS expands occurrences too, so this is what keeps a
//      week view the same on both.
//   2. An all-day event is stored in UTC with EVENT_TIMEZONE "UTC" and DTEND a whole
//      day later; a timed one in the device's zone. Writing an all-day event in local
//      time shifts it by the offset on every device east or west of Greenwich.
//   3. A repeating event has DURATION and no DTEND; an insert with both is refused.
//      Only DTEND is written here, which makes every event this API writes a single
//      one, as on iOS.
//   4. An alarm is a Reminders row keyed by EVENT_ID plus HAS_ALARM=1 on the event;
//      changing it means deleting the rows and writing one, since the provider does
//      not update reminders by event.
//   5. The system form is ACTION_INSERT on Events.CONTENT_URI with extras; it needs no
//      permission and answers no result, so the app is told "" when it closes.
package com.chuks.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import java.util.TimeZone

object ChuksCalendar {
    private fun clean(s: String?) = (s ?: "").replace('\t', ' ').replace('\n', ' ')

    private val INST_PROJ = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
        CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.ALL_DAY)
    private val EV_PROJ = arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
        CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.EVENT_LOCATION,
        CalendarContract.Events.DESCRIPTION, CalendarContract.Events.ALL_DAY)

    private fun line(c: android.database.Cursor): String =
        "${clean(c.getString(0))}\t${c.getLong(1)}\t${c.getLong(2)}\t${c.getLong(3)}\t${c.getLong(4)}\t${clean(c.getString(5))}\t${clean(c.getString(6))}\t${if (c.getInt(7) != 0) 1 else 0}"

    /** Occurrences between two times (trap 1), soonest first, one calendar when given. */
    fun events(ctx: Context, fromMs: Long, toMs: Long, calendarId: String): String {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { b -> ContentUris.appendId(b, fromMs); ContentUris.appendId(b, toMs); b.build() }
        val sel = if (calendarId.isEmpty()) null else "${CalendarContract.Instances.CALENDAR_ID}=?"
        val args = if (calendarId.isEmpty()) null else arrayOf(calendarId)
        val out = ArrayList<String>()
        ctx.contentResolver.query(uri, INST_PROJ, sel, args, "${CalendarContract.Instances.BEGIN} ASC")?.use { c -> while (c.moveToNext()) out.add(line(c)) }
        return out.joinToString("\n")
    }

    fun get(ctx: Context, id: String): String? {
        val idL = id.toLongOrNull() ?: return null
        ctx.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, idL), EV_PROJ, null, null, null)?.use { c ->
            // A deleted event lingers with DELETED=1 until the sync adapter purges it;
            // the Events URI hides those by default, so a miss here is a real miss.
            if (c.moveToFirst()) return line(c)
        }
        return null
    }

    /** "id\ttitle\twritable\tcolor" per calendar. */
    fun calendars(ctx: Context): String {
        val out = ArrayList<String>()
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CALENDAR_COLOR),
            null, null, "${CalendarContract.Calendars._ID} ASC")?.use { c ->
            while (c.moveToNext()) {
                val writable = c.getInt(2) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                val color = String.format("%06X", c.getInt(3) and 0xFFFFFF)
                out.add("${c.getLong(0)}\t${clean(c.getString(1))}\t${if (writable) 1 else 0}\t$color")
            }
        }
        return out.joinToString("\n")
    }

    /** The first writable calendar's id, or -1. */
    fun defaultCalendar(ctx: Context): Long {
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?", arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC")?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return -1
    }

    fun writable(ctx: Context, calendarId: Long): Boolean? {
        ctx.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
            arrayOf(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getInt(0) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
        }
        return null
    }

    /** The fields add and update share (traps 2 and 3). */
    private fun values(title: String, startMs: Long, endMs: Long, location: String, notes: String, allDay: Boolean, alarmMin: Long): ContentValues =
        ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DESCRIPTION, notes)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            if (allDay) {
                val day = 86_400_000L
                val s = startMs - Math.floorMod(startMs, day)
                var e = endMs - Math.floorMod(endMs, day)
                if (e <= s) e = s + day
                put(CalendarContract.Events.DTSTART, s); put(CalendarContract.Events.DTEND, e)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                // Whole seconds, as EventKit stores them, so a time reads back the same
                // on both platforms.
                put(CalendarContract.Events.DTSTART, startMs - startMs % 1000); put(CalendarContract.Events.DTEND, endMs - endMs % 1000)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            put(CalendarContract.Events.HAS_ALARM, if (alarmMin >= 0) 1 else 0)
        }

    private fun setAlarm(ctx: Context, eventId: Long, alarmMin: Long) {
        val cr = ctx.contentResolver
        cr.delete(CalendarContract.Reminders.CONTENT_URI, "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(eventId.toString()))
        if (alarmMin >= 0) {
            cr.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, alarmMin)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
    }

    fun add(ctx: Context, calendarId: Long, title: String, startMs: Long, endMs: Long, location: String, notes: String, allDay: Boolean, alarmMin: Long): Long {
        val v = values(title, startMs, endMs, location, notes, allDay, alarmMin)
        v.put(CalendarContract.Events.CALENDAR_ID, calendarId)
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: throw IllegalStateException("insert refused")
        val id = uri.lastPathSegment?.toLongOrNull() ?: throw IllegalStateException("no id answered")
        setAlarm(ctx, id, alarmMin)
        return id
    }

    fun update(ctx: Context, id: String, title: String, startMs: Long, endMs: Long, location: String, notes: String, allDay: Boolean, alarmMin: Long): Boolean {
        val idL = id.toLongOrNull() ?: return false
        val n = ctx.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, idL), values(title, startMs, endMs, location, notes, allDay, alarmMin), null, null)
        if (n == 0) return false
        setAlarm(ctx, idL, alarmMin)
        return true
    }

    fun delete(ctx: Context, id: String): Boolean {
        val idL = id.toLongOrNull() ?: return false
        return ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, idL), null, null) > 0
    }

    /** The system add-event form (trap 5). */
    fun composeIntent(title: String, startMs: Long, endMs: Long, location: String, notes: String): Intent =
        Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            .putExtra(CalendarContract.Events.DESCRIPTION, notes)

    class Watch(private val ctx: Context, private val emit: () -> Unit) {
        private val handler = Handler(Looper.getMainLooper())
        private var quiet = false
        private var more = false
        private val settle = Runnable { quiet = false; if (more) { more = false; emit() } }
        private val observer = object : ContentObserver(handler) {
            override fun onChange(self: Boolean) {
                if (quiet) { more = true; return }
                quiet = true; handler.postDelayed(settle, 300); emit()
            }
        }
        fun start() = ctx.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        fun stop() { handler.removeCallbacks(settle); ctx.contentResolver.unregisterContentObserver(observer) }
    }
}
