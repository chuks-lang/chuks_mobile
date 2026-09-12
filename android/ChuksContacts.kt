// Contacts for Chuks Mobile, the Android half. One contact is
// "name\tphones\temails\tid", phones and emails ";"-joined, the same line iOS makes.
//
// The traps:
//
//   1. The address book is three tables joined by contact id (Contacts, RawContacts,
//      Data). A list is ONE query over Data for the name, phone and email rows, folded
//      by contact id, not a query per contact: ten thousand contacts is one cursor.
//   2. The picker (ACTION_PICK) needs no permission, and the URI it answers carries a
//      temporary grant for what was picked: a whole card grants the card row (its
//      name), NOT its phone and email rows, which need READ_CONTACTS; a phone or
//      email pick grants that one data row, which is the no-permission way to get a
//      number. The "entities" read under a card is tried anyway, because some
//      providers grant it, and its refusal is the name-only answer.
//   3. A write is a batch of ContentProviderOperations against RawContacts and Data,
//      applied atomically. The new contact's id is the aggregate CONTACT_ID read back
//      from the raw contact the batch made, not the raw contact's own id.
//   4. Read and write are separate runtime permissions here; the "contacts" kind
//      declares both, and Permission.request("contacts") asks for both, so an app
//      that can list can add.
//   5. Change watching is a ContentObserver on the provider's AUTHORITY URI (it notifies
//      the root, and a sub-path observer never hears it); it fires in bursts, so it is
//      delivered at once and then coalesced: one more wake when a 300 ms burst settles.
package com.chuks.app

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import java.io.File

object ChuksContacts {
    private class Row(var name: String) { val phones = LinkedHashSet<String>(); val emails = LinkedHashSet<String>() }
    private fun clean(s: String?) = (s ?: "").replace('\t', ' ').replace('\n', ' ').replace(';', ',')
    private fun line(id: String, r: Row) = "${clean(r.name)}\t${r.phones.joinToString(";")}\t${r.emails.joinToString(";")}\t$id"

    private val PROJ = arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME_PRIMARY,
                               ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1)
    private const val SEL_KINDS = "${ContactsContract.Data.MIMETYPE} IN (?,?)"
    private val SEL_ARGS = arrayOf(CommonDataKinds.Phone.CONTENT_ITEM_TYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE)

    /** Fold Data rows under `uri` into contacts, in name order (trap 1). */
    private fun fold(cr: ContentResolver, uri: Uri, selection: String?, args: Array<String>?): LinkedHashMap<String, Row> {
        val out = LinkedHashMap<String, Row>()
        cr.query(uri, PROJ, selection, args, ContactsContract.Data.DISPLAY_NAME_PRIMARY)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val r = out.getOrPut(id) { Row(c.getString(1) ?: "") }
                val v = c.getString(3) ?: continue
                when (c.getString(2)) {
                    CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> r.phones.add(clean(v))
                    CommonDataKinds.Email.CONTENT_ITEM_TYPE -> r.emails.add(clean(v))
                }
            }
        }
        return out
    }

    // Contacts with no phone and no email have no Data row of those kinds; they still
    // exist and belong in the list, so names come from the Contacts table too.
    private fun names(cr: ContentResolver): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        cr.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                 null, null, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)?.use { c ->
            while (c.moveToNext()) { out[c.getString(0) ?: continue] = c.getString(1) ?: "" }
        }
        return out
    }

    fun list(ctx: Context, query: String): String {
        val cr = ctx.contentResolver
        val rows = fold(cr, ContactsContract.Data.CONTENT_URI, SEL_KINDS, SEL_ARGS)
        val all = names(cr)
        val q = query.lowercase()
        val lines = ArrayList<String>()
        for ((id, name) in all) {
            val r = rows[id] ?: Row(name)
            if (r.name.isEmpty()) r.name = name
            val l = line(id, r)
            if (q.isEmpty() || l.lowercase().contains(q)) lines.add(l)
        }
        return lines.joinToString("\n")
    }

    fun get(ctx: Context, id: String): String? {
        val cr = ctx.contentResolver
        val rows = fold(cr, ContactsContract.Data.CONTENT_URI, "$SEL_KINDS AND ${ContactsContract.Data.CONTACT_ID}=?", SEL_ARGS + id)
        val name = names(cr)[id] ?: return null
        val r = rows[id] ?: Row(name)
        if (r.name.isEmpty()) r.name = name
        return line(id, r)
    }

    /** A picked contact, read through the picker's own URI grant (trap 2). */
    fun picked(ctx: Context, uri: Uri): String? {
        val cr = ctx.contentResolver
        val id = uri.lastPathSegment ?: return null
        var name = ""
        cr.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: "" }
        val r = Row(name)
        val entity = Uri.withAppendedPath(uri, ContactsContract.Contacts.Entity.CONTENT_DIRECTORY)
        try {
            cr.query(entity, arrayOf(ContactsContract.Contacts.Entity.MIMETYPE, ContactsContract.Contacts.Entity.DATA1), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val v = c.getString(1) ?: continue
                    when (c.getString(0)) {
                        CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> r.phones.add(clean(v))
                        CommonDataKinds.Email.CONTENT_ITEM_TYPE -> r.emails.add(clean(v))
                    }
                }
            }
        } catch (e: SecurityException) { /* the grant covered the card, not its rows: name only */ }
        return line(id, r)
    }

    /** One picked phone or email row, read through its own grant: the value alone. */
    fun pickedRow(ctx: Context, uri: Uri, kind: String): String? {
        val valCol = if (kind == "phone") CommonDataKinds.Phone.NUMBER else CommonDataKinds.Email.ADDRESS
        // Read by column name, not through the CommonDataKinds constants: Email's
        // DISPLAY_NAME is data4 (the name part of "Name <addr>"), not the contact's
        // name, which lives in display_name on the joined row.
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            fun col(vararg names: String): String { for (n in names) { val i = c.getColumnIndex(n); if (i >= 0 && !c.isNull(i)) return c.getString(i) ?: "" }; return "" }
            val name = clean(col(ContactsContract.Data.DISPLAY_NAME_PRIMARY, ContactsContract.Data.DISPLAY_NAME, "display_name_alt"))
            val v = clean(col(valCol, ContactsContract.Data.DATA1))
            val id = col(ContactsContract.Data.CONTACT_ID)
            return if (kind == "phone") "$name\t$v\t\t$id" else "$name\t\t$v\t$id"
        }
        return null
    }

    // ---- writes (trap 3) ---------------------------------------------------------

    private fun dataOps(rawIdOrBackRef: Any, name: String, phones: String, emails: String): ArrayList<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>()
        fun insert(): ContentProviderOperation.Builder {
            val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            if (rawIdOrBackRef is Int) b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIdOrBackRef)
            else b.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawIdOrBackRef as Long)
            return b
        }
        val parts = name.trim().split(" ", limit = 2)
        ops.add(insert().withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(CommonDataKinds.StructuredName.GIVEN_NAME, parts.getOrElse(0) { "" })
            .withValue(CommonDataKinds.StructuredName.FAMILY_NAME, parts.getOrElse(1) { "" })
            .withValue(CommonDataKinds.StructuredName.DISPLAY_NAME, name.trim()).build())
        for (p in phones.split(";").map { it.trim() }.filter { it.isNotEmpty() })
            ops.add(insert().withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.Phone.NUMBER, p).withValue(CommonDataKinds.Phone.TYPE, CommonDataKinds.Phone.TYPE_MOBILE).build())
        for (e in emails.split(";").map { it.trim() }.filter { it.isNotEmpty() })
            ops.add(insert().withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.Email.ADDRESS, e).withValue(CommonDataKinds.Email.TYPE, CommonDataKinds.Email.TYPE_HOME).build())
        return ops
    }

    /** Creates a contact and answers its aggregate id. */
    fun add(ctx: Context, name: String, phones: String, emails: String): String {
        val cr = ctx.contentResolver
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build())
        ops.addAll(dataOps(0, name, phones, emails))
        val res = cr.applyBatch(ContactsContract.AUTHORITY, ops)
        val rawUri = res.firstOrNull()?.uri ?: throw IllegalStateException("no raw contact created")
        cr.query(rawUri, arrayOf(ContactsContract.RawContacts.CONTACT_ID), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0).toString()
        }
        throw IllegalStateException("cannot read the new contact's id")
    }

    /** Replaces name, phones and emails on every raw contact behind the id. */
    fun update(ctx: Context, id: String, name: String, phones: String, emails: String): Boolean {
        val cr = ctx.contentResolver
        val raws = ArrayList<Long>()
        cr.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
                 "${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(id), null)?.use { c -> while (c.moveToNext()) raws.add(c.getLong(0)) }
        if (raws.isEmpty()) return false
        val ops = ArrayList<ContentProviderOperation>()
        for (raw in raws) {
            ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE} IN (?,?,?)",
                    arrayOf(raw.toString(), CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE)).build())
        }
        ops.addAll(dataOps(raws[0], name, phones, emails))
        cr.applyBatch(ContactsContract.AUTHORITY, ops)
        return true
    }

    fun delete(ctx: Context, id: String): Boolean {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id.toLongOrNull() ?: return false)
        return ctx.contentResolver.delete(uri, null, null) > 0
    }

    /** The thumbnail as a cached JPEG path, "" when the contact has none, null when the contact is gone. */
    fun photo(ctx: Context, id: String): String? {
        val idL = id.toLongOrNull() ?: return null
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, idL)
        var exists = false
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { exists = it.moveToFirst() }
        if (!exists) return null
        val input = ContactsContract.Contacts.openContactPhotoInputStream(ctx.contentResolver, uri, false) ?: return ""
        val f = File(ctx.cacheDir, "contact-$idL.jpg")
        input.use { i -> f.outputStream().use { i.copyTo(it) } }
        return "file://" + f.absolutePath
    }

    // ---- watching (trap 5) --------------------------------------------------------

    class Watch(private val ctx: Context, private val emit: () -> Unit) {
        private val handler = Handler(Looper.getMainLooper())
        private var quiet = false      // inside the 300 ms window after an emit
        private var more = false       // a change arrived inside the window
        private val settle = Runnable { quiet = false; if (more) { more = false; emit() } }
        private val observer = object : ContentObserver(handler) {
            // First change goes out at once; the burst behind it becomes one more emit
            // when the window closes. A change the app's own write caused counts too:
            // what it shows is stale either way.
            override fun onChange(self: Boolean) {
                if (quiet) { more = true; return }
                quiet = true; handler.postDelayed(settle, 300); emit()
            }
        }
        // The provider notifies on its AUTHORITY root, not on /contacts, and an observer
        // hears its URI and descendants, never ancestors: observe the root.
        fun start() = ctx.contentResolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, observer)
        fun stop() { handler.removeCallbacks(settle); ctx.contentResolver.unregisterContentObserver(observer) }
    }
}
