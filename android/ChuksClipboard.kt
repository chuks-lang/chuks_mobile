// Clipboard for Chuks Mobile, the Android half beyond plain text. The traps:
//
//   1. What is on the clipboard is asked of primaryClipDescription, never the clip:
//      since Android 10 a background app reads null, and reading the clip from a
//      focused one is what the OS may announce to the user (Android 12 shows a toast
//      for a read from another app's copy). The description is free and silent.
//   2. Android has no URL clip type. A text that parses as http(s) counts as a URL,
//      which is what a browser's copy of an address is; the "url" kind is that test.
//   3. An image goes on the clipboard as a content URI, which other apps open through
//      the file provider, with a read grant on the clip; the bytes sit in the served
//      cache directory until the next copy replaces them.
//   4. Reading an image is the clip's URI opened through the resolver, which honours
//      the other app's grant; a photo copied from a gallery reads the same way.
package com.chuks.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Patterns
import java.io.File

object ChuksClipboard {
    private fun isUrl(s: CharSequence?) = s != null && (s.startsWith("http://") || s.startsWith("https://")) && Patterns.WEB_URL.matcher(s.trim()).matches()

    /** "text,url,image" in that order, from the description alone (traps 1 and 2). */
    fun kinds(ctx: Context, cm: ClipboardManager): String {
        val d = cm.primaryClipDescription ?: return ""
        val kinds = ArrayList<String>()
        val text = d.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || d.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        if (text) kinds.add("text")
        // The URL test needs the text; the clip is read only when the description says
        // there is text, and only for that test.
        if (text) { val t = try { cm.primaryClip?.getItemAt(0)?.coerceToText(ctx) } catch (e: Exception) { null }; if (isUrl(t)) kinds.add("url") }
        if (d.hasMimeType("image/*")) kinds.add("image")
        return kinds.joinToString(",")
    }

    /** Null on success, else why (trap 3). */
    fun setImage(ctx: Context, cm: ClipboardManager, path: String): String? {
        val src = ChuksFiles.resolve(ctx, path)
        if (!src.isFile) return "no such file: $path"
        val bmp = android.graphics.BitmapFactory.decodeFile(src.absolutePath) ?: return "not an image: $path"
        bmp.recycle()
        val uri = ChuksFileProvider.stage(ctx, src)
        val clip = ClipData.newUri(ctx.contentResolver, "image", uri)
        cm.setPrimaryClip(clip)
        return null
    }

    /** A file:// path to a JPEG in the cache, or "" when there is no image (trap 4). */
    fun getImage(ctx: Context, cm: ClipboardManager): String {
        val d = cm.primaryClipDescription ?: return ""
        if (!d.hasMimeType("image/*")) return ""
        val uri = cm.primaryClip?.getItemAt(0)?.uri ?: return ""
        val input = ctx.contentResolver.openInputStream(uri) ?: return ""
        val bmp = input.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return ""
        val f = File(ctx.cacheDir, "clipboard-" + System.nanoTime() + ".jpg")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        return "file://" + f.absolutePath
    }

    fun clear(cm: ClipboardManager) {
        if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}
