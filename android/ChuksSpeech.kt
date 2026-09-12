// Text-to-speech for Chuks Mobile, the Android half. One engine, the ids the app
// gave each utterance, and the events both platforms report the same way.
//
// The traps:
//
//   1. The engine initialises asynchronously and can fail (no engine installed, or a
//      voice pack missing). Anything asked before onInit is held and replayed, and
//      available() is answered from the language check, never assumed.
//   2. Rate and pitch are set on the engine, not the utterance, so they are applied
//      just before each speak(); volume travels with the utterance as a param.
//   3. Android has no pause. onRangeStart (API 26) reports the offset of the word
//      being spoken; pause stops the engine and remembers the offset, and resume
//      speaks the rest of the text from there. Below 26 a resume starts over.
//   4. Every callback arrives on the engine's thread; each is posted to the main
//      thread before it reaches the engine, where the app's callbacks run.
//   5. A stop() answers onStop (API 23) or nothing at all for the utterances it
//      cancelled, depending on the engine, so the cancelled ids are reported here
//      from the queue this class keeps, exactly once each.
package com.chuks.app

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

class ChuksSpeech(private val ctx: Context, private val emit: (String) -> Unit, private val settle: (String, String?) -> Unit) {
    private class Utt(val id: String, val text: String, val lang: String, val rate: Float, val pitch: Float, val volume: Float, val voice: String, val waiter: String?)
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private val handler = Handler(Looper.getMainLooper())
    private val onReady = ArrayList<() -> Unit>()
    private val queue = ArrayList<Utt>()          // in order; queue[0] is the one speaking
    private var paused: Utt? = null
    private var pausedAt = 0
    private var lastRangeStart = 0
    // Ids whose onStop is a pause, not a cancellation: the engine answers a stop() with
    // one onStop per queued utterance, asynchronously, and those must not end them.
    private val stopIsPause = HashSet<String>()
    // A resumed utterance is a new engine utterance speaking the remainder: its start
    // is not reported again and its ranges are shifted by where the pause was, so the
    // app sees one utterance from start to done, as on iOS.
    private val resumeOffset = HashMap<String, Int>()

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(ctx) { status ->
            handler.post {
                ready = status == TextToSpeech.SUCCESS
                failed = !ready
                if (ready) {
                    tts?.setLanguage(Locale.getDefault())
                    tts?.setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    tts?.setOnUtteranceProgressListener(listener)
                }
                val run = ArrayList(onReady); onReady.clear()
                for (r in run) r()
            }
        }
    }
    /** Run once the engine is up (or has failed); at once if it already is. */
    fun whenReady(run: () -> Unit) { ensure(); if (ready || failed) run() else onReady.add(run) }

    fun hasVoice(): Boolean {
        val t = tts ?: return false
        if (!ready) return false
        val r = try { t.isLanguageAvailable(Locale.getDefault()) } catch (e: Exception) { -2 }
        return r == TextToSpeech.LANG_AVAILABLE || r == TextToSpeech.LANG_COUNTRY_AVAILABLE || r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String) { handler.post { if (!resumeOffset.containsKey(id)) emit("start,$id") } }
        override fun onDone(id: String) { handler.post { finish(id, "done", null) } }
        @Deprecated("replaced by onError(id, code)") override fun onError(id: String) { handler.post { finish(id, "error", "synthesis failed") } }
        override fun onError(id: String, code: Int) { handler.post { finish(id, "error", "synthesis failed ($code)") } }
        override fun onStop(id: String, interrupted: Boolean) { handler.post { if (!stopIsPause.remove(id)) finish(id, "canceled", "canceled") } }
        override fun onRangeStart(id: String, start: Int, end: Int, frame: Int) {
            handler.post {
                val off = resumeOffset[id] ?: 0
                lastRangeStart = start + off
                emit("progress,$id,${start + off},${end + off}")
            }
        }
    }

    private fun finish(id: String, event: String, fail: String?) {
        val i = queue.indexOfFirst { it.id == id }
        if (i < 0) return   // already reported (a stop reports its own), or unknown
        val u = queue.removeAt(i)
        resumeOffset.remove(id)
        emit("$event,$id" + if (event == "error") ",${fail ?: ""}" else "")
        u.waiter?.let { settle(it, fail) }
    }

    fun speak(id: String, text: String, lang: String, rate: Float, pitch: Float, volume: Float, voice: String, queueIt: Boolean, waiter: String?) {
        whenReady {
            if (!ready) { emit("error,$id,no speech engine"); waiter?.let { settle(it, "no speech engine") }; return@whenReady }
            if (!queueIt) cancelAll()
            val u = Utt(id, text, lang, rate, pitch, volume, voice, waiter)
            queue.add(u)
            start(u, 0, if (queueIt) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH)
        }
    }

    private fun start(u: Utt, from: Int, mode: Int) {
        val t = tts ?: return
        t.setSpeechRate(u.rate.coerceIn(0.1f, 4f))
        t.setPitch(u.pitch.coerceIn(0.5f, 2f))
        var applied = false
        if (u.voice.isNotEmpty()) { t.voices?.firstOrNull { it.name == u.voice }?.let { t.voice = it; applied = true } }
        if (!applied) t.setLanguage(if (u.lang.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(u.lang))
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, u.volume.coerceIn(0f, 1f)) }
        t.speak(if (from > 0 && from < u.text.length) u.text.substring(from) else u.text, mode, params, u.id)
    }

    /** Stop everything and report each cancelled id once (trap 5). */
    private fun cancelAll() {
        val had = ArrayList(queue); queue.clear()
        paused = null; stopIsPause.clear(); resumeOffset.clear()
        tts?.stop()
        for (u in had) { emit("canceled,${u.id}"); u.waiter?.let { settle(it, "canceled") } }
    }
    fun stop() = cancelAll()

    fun pause() {
        val cur = queue.firstOrNull() ?: return
        if (paused != null) return
        paused = cur; pausedAt = if (Build.VERSION.SDK_INT >= 26) lastRangeStart else 0
        // The queue stays as it is; the engine is stopped, and the onStop it will send
        // for every queued id is a pause, not an end (trap 3).
        for (u in queue) stopIsPause.add(u.id)
        tts?.stop()
        emit("paused,${cur.id}")
    }
    fun resume() {
        val p = paused ?: return
        paused = null
        emit("resumed,${p.id}")
        // Speak the remainder, then whatever was queued behind it.
        val rest = ArrayList(queue.drop(1))
        resumeOffset[p.id] = pausedAt
        start(p, pausedAt, TextToSpeech.QUEUE_FLUSH)
        for (u in rest) start(u, 0, TextToSpeech.QUEUE_ADD)
    }

    fun isSpeaking(): Boolean = paused == null && (tts?.isSpeaking == true)
    fun status(): String = if (paused != null) "paused" else if (tts?.isSpeaking == true) "speaking" else "idle"

    /** "id\tname\tlanguage\tquality" per voice. */
    fun voices(): String {
        val t = tts ?: return ""
        val vs: Set<Voice> = try { t.voices ?: emptySet() } catch (e: Exception) { emptySet() }
        return vs.sortedBy { it.name }.joinToString("\n") { v ->
            val q = when { v.quality >= Voice.QUALITY_VERY_HIGH -> "premium"; v.quality >= Voice.QUALITY_HIGH -> "enhanced"; else -> "default" }
            "${ChuksWire.esc(v.name)}\t${ChuksWire.esc(v.name)}\t${v.locale.toLanguageTag()}\t$q"
        }
    }
}
