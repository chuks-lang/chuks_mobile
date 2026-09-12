// A content provider that hands other apps files from ONE directory of this app's
// cache, by URI, for as long as the grant lasts. This is what the clipboard and the
// share sheet need: another app cannot open our file by path, only through a
// content:// URI we serve and a permission grant on it.
//
// androidx.core's FileProvider does the same and is not available to a build with no
// Gradle, so this is the fifty lines of it that matter. Only files directly under
// cache/shared are served; a URI naming anything else, or walking up with "..", is
// refused, so a grant on one file is a grant on that file.
package com.chuks.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

class ChuksFileProvider : ContentProvider() {
    companion object {
        const val DIR = "shared"
        fun authority(ctx: Context) = ctx.packageName + ".files"
        fun dir(ctx: Context): File = File(ctx.cacheDir, DIR).also { it.mkdirs() }
        /** The content URI for a file placed in dir(ctx). */
        fun uriFor(ctx: Context, f: File): Uri = Uri.Builder().scheme("content").authority(authority(ctx)).appendPath(f.name).build()
        /** Copy any readable file into the served directory under a fresh name, and answer its URI. */
        fun stage(ctx: Context, src: File): Uri {
            val ext = src.extension.ifEmpty { "bin" }
            val dst = File(dir(ctx), "chuks-" + System.nanoTime() + "." + ext)
            src.inputStream().use { i -> dst.outputStream().use { i.copyTo(it) } }
            return uriFor(ctx, dst)
        }
    }

    private fun fileFor(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (uri.pathSegments.size != 1 || name.contains("/") || name == "." || name == "..") return null
        val f = File(dir(ctx), name)
        return if (f.isFile) f else null
    }

    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String? {
        val ext = fileFor(uri)?.extension?.lowercase() ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } })
        return c
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = 0
}
