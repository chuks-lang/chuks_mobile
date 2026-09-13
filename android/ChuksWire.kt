// One field of a capability payload, escaped.
//
// A capability that answers several things joins records with a newline and the fields
// of a record with a tab. A value inside a field can contain either: a filename may
// hold a newline (legal on both platforms), a contact may be saved with a tab in the
// name, an app chooses its own notification ids and secure-store keys. Left raw, such a
// value shifted the columns or invented a row: three files whose names held a newline
// listed as four.
//
// Escaping keeps the record and field COUNT right and keeps the value recoverable,
// where the clean() this replaces substituted spaces and lost what was there.
// Undone by wireFields() in core/native.chuks.
package com.chuks.app

object ChuksWire {
    fun esc(s: String?): String = escape(s, false)
    /** An item of a ";"-joined field (a contact's phones, emails): the semicolon is
     *  escaped a step further, and undone by wireList rather than wireFields, so it
     *  survives the field split and dies at the list split. */
    fun escItem(s: String?): String = escape(s, true)
    private fun escape(s: String?, listItem: Boolean): String {
        val v = s ?: return ""
        if (!v.contains('\\') && !v.contains('\t') && !v.contains('\n') && !v.contains('\r') && !(listItem && v.contains(';'))) return v
        val b = StringBuilder(v.length + 8)
        for (c in v) when {
            c == '\\' -> b.append("\\\\")
            c == '\t' -> b.append("\\t")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == ';' && listItem -> b.append("\\;")
            else -> b.append(c)
        }
        return b.toString()
    }
}
