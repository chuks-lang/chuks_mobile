// Microphone recording for Chuks Mobile, the Android half: MediaRecorder to an AAC
// file, with the status string both platforms agree on.
//
// The traps:
//
//   1. MediaRecorder has no duration and no position. The recorded time is kept here:
//      wall time since start, minus the time spent paused, which is what iOS's
//      currentTime answers.
//   2. maxAmplitude is the PEAK since the last call and resets on read, so two readers
//      (levels and the status ticker) would starve each other. One reader samples it
//      on a fixed tick and everyone else reads the sample.
//   3. A phone call does not pause a MediaRecorder; audio focus loss is the signal.
//      Focus is requested for the recording, and a transient loss pauses it and says
//      so, the way an iOS interruption does. It does not resume by itself.
//   4. stop() throws if nothing was captured (a stop right after start); the file is
//      then empty and is deleted rather than handed to the app.
package com.chuks.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

class ChuksRecorder(private val ctx: Context, private val emit: (String) -> Unit) {
    val watchers = HashSet<String>()
    private var mr: MediaRecorder? = null
    private var path: String? = null
    private var paused = false
    private var startedAt = 0L
    private var pausedAt = 0L
    private var pausedTotal = 0L
    private var sample = 0.0
    private val handler = Handler(Looper.getMainLooper())
    private var focusReq: AudioFocusRequest? = null
    private val ticker = object : Runnable {
        override fun run() {
            val r = mr ?: return
            if (!paused) {
                val amp = try { r.maxAmplitude } catch (e: Exception) { 0 }
                sample = (amp.toDouble() / 32767.0).coerceIn(0.0, 1.0)
            } else sample = 0.0
            if (watchers.isNotEmpty()) emit(status())
            handler.postDelayed(this, 250)
        }
    }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
    }

    /** Null on success, else why. */
    fun start(quality: String): String? {
        if (mr != null) return "already recording"
        val f = File(ctx.filesDir, "rec-${System.nanoTime()}.m4a")
        val (rate, bitrate) = when (quality) { "low" -> 22050 to 32000; "high" -> 48000 to 192000; else -> 44100 to 96000 }
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(rate)
            r.setAudioEncodingBitRate(bitrate)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            mr = r; path = f.absolutePath; paused = false
            startedAt = SystemClock.elapsedRealtime(); pausedTotal = 0L; sample = 0.0
            requestFocus()
            handler.removeCallbacks(ticker); handler.postDelayed(ticker, 250)
            null
        } catch (e: Exception) { r.release(); f.delete(); "record failed: ${e.message}" }
    }

    fun pause() {
        val r = mr ?: return
        if (paused) return
        try { r.pause() } catch (e: Exception) { return }
        paused = true; pausedAt = SystemClock.elapsedRealtime()
        if (watchers.isNotEmpty()) emit(status())
    }
    fun resume() {
        val r = mr ?: return
        if (!paused) return
        try { r.resume() } catch (e: Exception) { return }
        pausedTotal += SystemClock.elapsedRealtime() - pausedAt
        paused = false
        if (watchers.isNotEmpty()) emit(status())
    }

    /** The file path, or null when nothing was recording. */
    fun stop(): String? {
        val r = mr ?: return null
        val p = path
        var ok = true
        try { r.stop() } catch (e: Exception) { ok = false }   // trap 4
        finish(r)
        if (!ok) { p?.let { File(it).delete() } }
        return if (ok) p else ""
    }
    fun cancel() {
        val r = mr ?: return
        try { r.stop() } catch (e: Exception) {}
        finish(r)
        path?.let { File(it).delete() }
    }
    private fun finish(r: MediaRecorder) {
        r.release(); mr = null; paused = false
        handler.removeCallbacks(ticker)
        abandonFocus()
        if (watchers.isNotEmpty()) emit(status())
    }

    private fun durationMs(): Long {
        if (mr == null) return 0
        val now = if (paused) pausedAt else SystemClock.elapsedRealtime()
        return now - startedAt - pausedTotal
    }
    fun level(): Double = if (mr == null || paused) 0.0 else sample
    fun status(): String {
        val state = if (mr == null) "idle" else if (paused) "paused" else "recording"
        return "$state,${durationMs()}," + String.format(java.util.Locale.US, "%.3f", level())
    }

    private fun requestFocus() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusListener).build()
            focusReq = req; am.requestAudioFocus(req)
        } else @Suppress("DEPRECATION") am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
    }
    private fun abandonFocus() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) focusReq?.let { am.abandonAudioFocusRequest(it) } else @Suppress("DEPRECATION") am.abandonAudioFocus(focusListener)
        focusReq = null
    }
}
