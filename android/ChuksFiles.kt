// Files for Chuks Mobile, the Android half. Path resolution, the listing and info
// formats both platforms agree on, and the three slow operations (copy, move,
// download) which run off the main thread.
//
// The traps:
//
//   1. A path is relative to filesDir unless it is absolute or file://. A picked photo
//      or a recording comes back from its capability as file:///..., and must read
//      back through the same class without the app stripping anything.
//   2. Base64 for bytes, standard alphabet, no line breaks, so the string that comes
//      out of iOS and the one that comes out of Android are the same string.
//   3. Copy, move and download go file to file through a bounded pool: two threads,
//      so a screen that fires twenty downloads queues them instead of opening twenty
//      sockets. Each answer is posted back to the main thread, where the engine lives.
//   4. A download goes to a sibling temp file and is renamed into place on success,
//      so a failed download never leaves a half-written file under the final name.
//   5. Nothing to delete is not an error; deleting a directory deletes its contents.
//   6. An empty path is refused by everything that writes, moves or deletes: it would
//      resolve to filesDir itself, and one bad argument must not take the app's
//      storage with it.
package com.chuks.app

import android.content.Context
import android.os.StatFs
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object ChuksFiles {
    private val pool = Executors.newFixedThreadPool(2)

    fun resolve(ctx: Context, path: String): File = when {
        path.startsWith("file://") -> File(path.substring(7))
        path.startsWith("/") -> File(path)
        else -> File(ctx.filesDir, path)
    }
    private fun mkParent(f: File) { f.parentFile?.mkdirs() }
    /** An empty path would name filesDir itself, and no app means that (trap 6). */
    private fun empty(path: String) = path.isEmpty() || path == "/" || path == "file://"

    fun write(ctx: Context, path: String, text: String): String? = if (empty(path)) "path is empty" else try {
        val f = resolve(ctx, path); mkParent(f); f.writeText(text); null
    } catch (e: Exception) { "cannot write $path: ${e.message}" }

    fun writeB64(ctx: Context, path: String, b64: String): String? {
        if (empty(path)) return "path is empty"
        val bytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (e: IllegalArgumentException) { return "content is not base64" }
        return try { val f = resolve(ctx, path); mkParent(f); f.writeBytes(bytes); null }
               catch (e: Exception) { "cannot write $path: ${e.message}" }
    }

    fun readB64(ctx: Context, path: String): String? {
        val f = resolve(ctx, path)
        return if (f.isFile) Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) else null
    }

    /** One name per line, sorted, directories with a trailing "/". Null if not a directory. */
    fun list(ctx: Context, dir: String): String? {
        val d = resolve(ctx, dir)
        val names = d.listFiles() ?: return null
        return names.sortedBy { it.name }.joinToString("\n") { ChuksWire.esc(if (it.isDirectory) it.name + "/" else it.name) }
    }

    fun delete(ctx: Context, path: String): String? {
        if (empty(path)) return "path is empty"
        val f = resolve(ctx, path)
        if (!f.exists()) return null
        return if (f.deleteRecursively()) null else "cannot delete $path"
    }

    /** "kind,sizeBytes,modifiedMs" with kind file, dir or none. */
    fun info(ctx: Context, path: String): String {
        val f = resolve(ctx, path)
        return when {
            f.isDirectory -> "dir,0,${f.lastModified()}"
            f.isFile -> "file,${f.length()},${f.lastModified()}"
            else -> "none,0,0"
        }
    }

    fun mkdir(ctx: Context, path: String): String? {
        if (empty(path)) return "path is empty"
        val f = resolve(ctx, path)
        return if (f.isDirectory || f.mkdirs()) null else "cannot create $path"
    }

    /** Copy or move on the pool; `done` gets null or a message, on the main thread. */
    fun transfer(ctx: Context, src: String, dst: String, move: Boolean, post: (Runnable) -> Unit, done: (String?) -> Unit) {
        if (empty(src) || empty(dst)) { done("path is empty"); return }
        val s = resolve(ctx, src); val d = resolve(ctx, dst)
        pool.execute {
            val msg: String? = try {
                if (!s.exists()) throw java.io.FileNotFoundException("no such file")
                mkParent(d)
                if (d.exists()) d.deleteRecursively()
                if (move) { if (!s.renameTo(d)) { s.copyRecursively(d, overwrite = true); s.deleteRecursively() } }
                else if (s.isDirectory) s.copyRecursively(d, overwrite = true) else s.copyTo(d, overwrite = true)
                null
            } catch (e: Exception) { "cannot ${if (move) "move" else "copy"} $src: ${e.message}" }
            post(Runnable { done(msg) })
        }
    }

    /** Streamed download to `dst`; `done` gets "path,bytes" and null, or "" and a message. */
    fun download(ctx: Context, url: String, dst: String, post: (Runnable) -> Unit, done: (String, String?) -> Unit) {
        if (empty(dst)) { done("", "path is empty"); return }
        val d = resolve(ctx, dst)
        pool.execute {
            var result = ""; var msg: String? = null
            val tmp = File(d.parentFile, d.name + ".part-" + System.nanoTime())
            try {
                mkParent(d)
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code >= 400) throw java.io.IOException("HTTP $code")
                var n = 0L
                conn.inputStream.use { input -> FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val r = input.read(buf); if (r < 0) break; out.write(buf, 0, r); n += r }
                } }
                if (d.exists()) d.delete()
                if (!tmp.renameTo(d)) throw java.io.IOException("cannot move into place")
                result = d.absolutePath + "," + n
            } catch (e: Exception) {
                tmp.delete()
                msg = "download failed: ${e.message}"
            }
            post(Runnable { done(result, msg) })
        }
    }

    /** "freeBytes,totalBytes" for the volume filesDir is on. */
    fun diskSpace(ctx: Context): String {
        val st = StatFs(ctx.filesDir.absolutePath)
        return "${st.availableBytes},${st.totalBytes}"
    }

    fun dir(ctx: Context, kind: String): String = when (kind) {
        "cache" -> ctx.cacheDir.absolutePath
        "temp" -> File(ctx.cacheDir, "tmp").also { it.mkdirs() }.absolutePath
        else -> ctx.filesDir.absolutePath
    }
}
