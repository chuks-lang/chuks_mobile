// Media for Chuks Mobile, the Android half of the library picker: the intent that
// opens it, bringing what was chosen into the app, and describing a media file.
//
// The traps:
//
//   1. The system Photo Picker (ACTION_PICK_IMAGES) exists on Android 13, and on 11
//      and 12 through Google Play services where present; it needs no permission and
//      shows the user only what they choose. Where it is not available the chooser
//      falls back to ACTION_GET_CONTENT, which is the same contract in older clothes.
//   2. Several picks arrive as clipData, one as data; both are handled, in order.
//   3. A picked photo is often 12 megapixels and 5 MB. An image is decoded with a
//      sample size that lands near maxSize, then scaled to it, then written as a JPEG
//      at the asked quality, with the EXIF rotation applied so the file is upright.
//   4. A video is copied byte for byte with an extension from its MIME type; its size
//      and duration come from MediaMetadataRetriever.
package com.chuks.app

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.media.ExifInterface
import java.io.File

object ChuksMedia {
    private fun degrees(e: ExifInterface): Int = when (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
    }
    /** The picker intent for a kind ("image", "video", "any") and a limit (0 = no limit). */
    fun pickIntent(kind: String, limit: Int): Intent {
        val mime = when (kind) { "image" -> "image/*"; "video" -> "video/*"; else -> "*/*" }
        val photoPicker = Build.VERSION.SDK_INT >= 33
        return if (photoPicker) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                if (kind != "any") type = mime
                if (limit != 1) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, if (limit <= 0) MediaStore.getPickImagesMaxLimit() else minOf(limit, MediaStore.getPickImagesMaxLimit()))
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = mime
                if (kind == "any") putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                addCategory(Intent.CATEGORY_OPENABLE)
                if (limit != 1) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
    }

    /** The URIs a picker result carries, in order (trap 2). */
    fun picked(data: Intent?): List<Uri> {
        val out = ArrayList<Uri>()
        val clip = data?.clipData
        if (clip != null) { for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) } }
        else data?.data?.let { out.add(it) }
        return out
    }

    /** Copy one picked item in; answer "path\tkind\twidth\theight\tbytes\tdurationMs" or null. */
    fun ingest(ctx: Context, uri: Uri, quality: Double, maxSize: Int): String? {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: ""
        val f: File = if (mime.startsWith("video/")) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp4"
            val dst = File(ctx.filesDir, "picked-${System.nanoTime()}.$ext")
            cr.openInputStream(uri)?.use { i -> dst.outputStream().use { i.copyTo(it) } } ?: return null
            dst
        } else {
            val dst = File(ctx.filesDir, "picked-${System.nanoTime()}.jpg")
            val bmp = decodeScaled(cr, uri, maxSize) ?: return null
            dst.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, (quality.coerceIn(0.05, 1.0) * 100).toInt(), it) }
            bmp.recycle()
            dst
        }
        return info(f)?.let { ChuksWire.esc(f.absolutePath) + "\t" + it }
    }

    // Trap 3: bounds first, a power-of-two sample near the target, then the exact scale
    // and the EXIF rotation.
    private fun decodeScaled(cr: ContentResolver, uri: Uri, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode answers null by design; only the stream can be missing.
        val probe = cr.openInputStream(uri) ?: return null
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        if (maxSize > 0) while (longest / (sample * 2) >= maxSize) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        if (maxSize > 0) {
            val l = maxOf(bmp.width, bmp.height)
            if (l > maxSize) {
                val k = maxSize.toFloat() / l
                val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
                if (scaled !== bmp) { bmp.recycle(); bmp = scaled }
            }
        }
        val rotation = try { cr.openInputStream(uri)?.use { degrees(ExifInterface(it)) } ?: 0 } catch (e: Exception) { 0 }
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) { bmp.recycle(); bmp = rotated }
        }
        return bmp
    }

    /** "kind\twidth\theight\tbytes\tdurationMs", or null for a file that is neither (trap 4). */
    fun info(f: File): String? {
        if (!f.isFile) return null
        val bytes = f.length()
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, b)
        if (b.outWidth > 0 && b.outHeight > 0) {
            val rot = try { degrees(ExifInterface(f.absolutePath)) } catch (e: Exception) { 0 }
            val (w, h) = if (rot == 90 || rot == 270) b.outHeight to b.outWidth else b.outWidth to b.outHeight
            return "image\t$w\t$h\t$bytes\t0"
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            return "video\t$w\t$h\t$bytes\t$ms"
        } catch (e: Exception) { return null } finally { try { r.release() } catch (e: Exception) {} }
    }

    /** Save an image or video file to the gallery; null on success, else why. */
    fun save(ctx: Context, f: File): String? {
        val kind = info(f)?.split("\t")?.get(0) ?: return "not an image or video: ${f.name}"
        val isVideo = kind == "video"
        val ext = f.extension.ifEmpty { if (isVideo) "mp4" else "jpg" }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: if (isVideo) "video/mp4" else "image/jpeg"
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "chuks-${System.currentTimeMillis()}.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies" else "Pictures")
        }
        val table = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = ctx.contentResolver.insert(table, values) ?: return "cannot create a gallery entry"
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: return "cannot write the gallery entry"
            null
        } catch (e: Exception) { "save failed: ${e.message}" }
    }
}
