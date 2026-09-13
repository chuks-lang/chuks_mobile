// Audio playback for Chuks Mobile, the Android half: one MediaPlayer per Chuks
// AudioPlayer, audio focus, and the status string both platforms agree on.
//
// The traps, and what is done about them:
//
//   1. MediaPlayer has no periodic position callback. While a player is playing, a
//      Handler runnable pushes status four times a second; it stops the moment the
//      player does, so an idle player costs nothing.
//   2. Setting playbackParams on a paused MediaPlayer STARTS it. The speed is kept and
//      applied only while playing, and re-applied on every start.
//   3. A prepared-but-not-started player must not be read for position/duration
//      before onPrepared, and a released one must never be touched: every call goes
//      through the state the player itself tracks, not the OS's.
//   4. MediaPlayer instances are a small fixed pool of hardware decoders. The count is
//      capped, and the 33rd create is answered with state "error" rather than a
//      failure somewhere less legible. release() is the app's duty and this is what
//      happens when it is forgotten.
//   5. Audio focus is the interruption story. A call takes focus: LOSS_TRANSIENT
//      pauses what is playing and GAIN brings it back; plain LOSS pauses for good;
//      CAN_DUCK lowers the level and GAIN restores it. Headphones being unplugged is
//      ACTION_AUDIO_BECOMING_NOISY, and pauses without resuming, as the user expects.
package com.chuks.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper

class ChuksAudioPlayer(val id: String, private val emit: (String) -> Unit) {
    val mp = MediaPlayer()
    var state = "loading"
    var error = ""
    var buffering = false
    var looping = false
    var speed = 1.0f
    var level = 1.0f
    var wantPlay = false
    var wasPlayingBeforeFocusLoss = false
    private var ducked = false
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { if (state == "playing") { push(); handler.postDelayed(this, 250) } }
    }

    fun load(ctx: Context, src: String) {
        try {
            when {
                src.startsWith("file://") -> mp.setDataSource(src.substring(7))
                src.startsWith("http://") || src.startsWith("https://") -> mp.setDataSource(src)
                else -> { val afd = ctx.assets.openFd(src); mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length); afd.close() }
            }
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setOnPreparedListener {
                if (state == "loading") state = "ready"
                if (wantPlay) { wantPlay = false; start() } else push()
            }
            mp.setOnCompletionListener {
                // isLooping restarts natively and never reaches here, so this is the end.
                if (state == "playing") { state = "ended"; handler.removeCallbacks(ticker); push() }
            }
            mp.setOnErrorListener { _, what, extra ->
                state = "error"; error = "MediaPlayer error $what/$extra"; handler.removeCallbacks(ticker); push(); true
            }
            mp.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> { buffering = true; push() }
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> { buffering = false; push() }
                }
                false
            }
            mp.setOnSeekCompleteListener { push() }
            mp.prepareAsync()
        } catch (e: Exception) {
            state = "error"; error = "\"$src\" is not a file, a URL, or a bundled asset (${e.message})"; push()
        }
    }

    private fun start() {
        try {
            mp.start()
            if (Build.VERSION.SDK_INT >= 23 && speed != 1.0f) mp.playbackParams = mp.playbackParams.setSpeed(speed)
            state = "playing"
            handler.removeCallbacks(ticker); handler.postDelayed(ticker, 250)
            push()
        } catch (e: IllegalStateException) { state = "error"; error = "cannot start: ${e.message}"; push() }
    }
    fun play() {
        if (state == "error") return
        if (state == "loading") { wantPlay = true; return }
        start()
    }
    fun pause() {
        wantPlay = false
        if (state == "playing") { try { mp.pause() } catch (e: IllegalStateException) {}; state = "paused"; handler.removeCallbacks(ticker); push() }
    }
    fun stop() { pause(); try { mp.seekTo(0) } catch (e: IllegalStateException) {}; push() }
    fun seek(ms: Int) {
        if (state == "loading" || state == "error") return
        try { if (Build.VERSION.SDK_INT >= 26) mp.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST) else mp.seekTo(ms) } catch (e: IllegalStateException) {}
    }
    fun setVolume(v: Float) { level = v.coerceIn(0f, 1f); applyVolume(); push() }
    private fun applyVolume() { try { val v = if (ducked) level * 0.2f else level; mp.setVolume(v, v) } catch (e: IllegalStateException) {} }
    fun setRate(r: Float) {
        speed = r.coerceIn(0.25f, 4f)
        // Trap 2: only touch playbackParams while playing.
        if (state == "playing" && Build.VERSION.SDK_INT >= 23) try { mp.playbackParams = mp.playbackParams.setSpeed(speed) } catch (e: Exception) {}
        push()
    }
    fun setLoop(on: Boolean) { looping = on; try { mp.isLooping = on } catch (e: IllegalStateException) {} }
    fun duck(on: Boolean) { ducked = on; applyVolume() }

    fun status(): String {
        val pos = if (state == "loading" || state == "error") 0 else try { mp.currentPosition } catch (e: IllegalStateException) { 0 }
        val dur = if (state == "loading" || state == "error") 0 else try { maxOf(mp.duration, 0) } catch (e: IllegalStateException) { 0 }
        return "$state,$pos,$dur,$speed,$level,${if (buffering) 1 else 0},$error"
    }
    fun push() = emit(status())

    fun release() {
        handler.removeCallbacks(ticker)
        try { mp.reset() } catch (e: Exception) {}
        mp.release()
    }
}

object ChuksAudio {
    const val CAP = 32
    val players = HashMap<String, ChuksAudioPlayer>()
    var mode = "playback"
    private var focusReq: AudioFocusRequest? = null
    private var noisy: BroadcastReceiver? = null
    private var listening = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> for (p in players.values) { p.wasPlayingBeforeFocusLoss = false; p.pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> for (p in players.values) { p.wasPlayingBeforeFocusLoss = p.state == "playing"; p.pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> for (p in players.values) p.duck(true)
            AudioManager.AUDIOFOCUS_GAIN -> for (p in players.values) {
                p.duck(false)
                if (p.wasPlayingBeforeFocusLoss) { p.wasPlayingBeforeFocusLoss = false; p.play() }
            }
        }
    }

    /** Ask for focus the way the mode says. "ambient" mixes and asks for nothing. */
    fun requestFocus(ctx: Context) {
        if (mode == "ambient") return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val gain = if (mode == "duck") AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(focusListener).build()
            focusReq = req
            am.requestAudioFocus(req)
        } else @Suppress("DEPRECATION") am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, gain)
    }

    fun listen(ctx: Context) {
        if (listening) return
        listening = true
        noisy = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { for (p in players.values) p.pause() }
        }
        ctx.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }
}
