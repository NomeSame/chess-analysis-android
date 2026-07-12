package com.example.chessanalysis.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.util.Log
import com.example.chessanalysis.model.SoundTheme
import java.io.File
import kotlin.jvm.Volatile

class SoundManager(private val context: Context) {
    var soundPool: SoundPool? = null
    private var sndMove = 0
    private var sndCapture = 0
    private var sndCastle = 0
    private var sndCheck = 0
    private var sndCheckmate = 0
    var pieceSoundsEnabled = true
    var soundTheme = SoundTheme.CLASSIC
    @Volatile
    var soundPoolReady = false

    private val soundsDir get() = File(context.filesDir, "sounds")

    fun init() {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            soundPool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
            soundPoolReady = false
            soundPool!!.setOnLoadCompleteListener { _, _, status ->
                if (status == 0) soundPoolReady = true
                else Log.e("Sound", "load failed status=$status")
            }
        } catch (e: Exception) {
            Log.e("Sound", "SoundPool init failed", e)
            soundPool = null
        }
    }

    fun loadTheme(theme: SoundTheme) {
        soundTheme = theme
        val sp = soundPool ?: return
        intArrayOf(sndMove, sndCapture, sndCastle, sndCheck, sndCheckmate).forEach { if (it != 0) sp.unload(it) }
        soundPoolReady = false
        sndMove = loadSample(theme, "move")
        sndCapture = loadSample(theme, "capture")
        sndCastle = loadSample(theme, "castle")
        sndCheck = loadSample(theme, "check")
        sndCheckmate = loadSample(theme, "checkmate", "check")
    }

    fun playMoveSound(isCapture: Boolean, isCastle: Boolean, isCheck: Boolean, isCheckmate: Boolean) {
        if (!pieceSoundsEnabled) return
        val sp = soundPool ?: return
        if (!soundPoolReady) { Log.w("Sound", "pool not ready yet"); return }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) { Log.d("Sound", "media volume 0"); return }
        try {
            val snd = when {
                isCheckmate -> sndCheckmate
                isCastle -> sndCastle
                isCheck -> sndCheck
                isCapture -> sndCapture
                else -> sndMove
            }
            val sid = sp.play(snd, 1f, 1f, 0, 0, 1f)
            if (sid == 0) Log.w("Sound", "play returned 0")
        } catch (e: Exception) { Log.e("Sound", "play failed", e) }
    }

    private fun loadSample(theme: SoundTheme, vararg names: String): Int {
        val sp = soundPool ?: return 0
        for (n in names) {
            val f = File(soundsDir, theme.prefix + n + ".ogg")
            if (f.exists()) runCatching { return sp.load(f.absolutePath, 1) }
            val res = context.resources.getIdentifier(theme.prefix + n, "raw", context.packageName)
            if (res != 0) return sp.load(context, res, 1)
        }
        for (n in names) {
            val res = context.resources.getIdentifier(n, "raw", context.packageName)
            if (res != 0) return sp.load(context, res, 1)
        }
        return sp.load(context, com.example.chessanalysis.R.raw.move, 1)
    }

    fun release() {
        soundPool?.release()
        soundPool = null
    }
}
