package com.example.chessanalysis

import android.util.Log

/**
 * On-device GGUF inference via the bundled llama.cpp native runner.
 *
 * The native lib (`llama_jni`) is only built when the Gradle flag `aiCoachLlama=true`
 * (→ [BuildConfig.LLAMA_ENABLED]). When the runner is disabled or the lib is absent,
 * [isAvailable] is false and all calls are safe no-ops — the rest of the app builds and runs
 * unchanged, and the Gemma cards surface "disabled in this build".
 */
object LlamaRunner {

    private const val TAG = "LlamaRunner"

    /** True only if this build compiled the native runner AND the lib loaded successfully. */
    val isAvailable: Boolean

    init {
        var ok = false
        if (BuildConfig.LLAMA_ENABLED) {
            try {
                System.loadLibrary("llama_jni")
                ok = true
            } catch (t: Throwable) {
                Log.e(TAG, "llama_jni not loadable", t)
            }
        }
        isAvailable = ok
    }

    @Volatile private var handle: Long = 0L

    /** Model currently loaded (absolute path), or null. */
    @Volatile var loadedModelPath: String? = null
        private set

    val isModelLoaded: Boolean get() = handle != 0L

    /** Use the performance cores; too many threads on big.LITTLE phones hurts (efficiency-core contention). */
    private fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    /** Load a GGUF model. Returns true on success. Safe to call when [isAvailable] is false (returns false). */
    @Synchronized
    fun load(modelPath: String, nCtx: Int = 512, nThreads: Int = defaultThreads()): Boolean {
        if (!isAvailable) return false
        if (handle != 0L && loadedModelPath == modelPath) return true
        if (handle != 0L) { nativeFree(handle); handle = 0L; loadedModelPath = null }
        val h = try { nativeLoad(modelPath, nCtx, nThreads) } catch (t: Throwable) {
            Log.e(TAG, "load failed", t); 0L
        }
        if (h != 0L) { handle = h; loadedModelPath = modelPath }
        return h != 0L
    }

    // J5: last measured tokens/sec and timeout flag — readable by MainActivity after generate().
    @Volatile var lastTokensPerSec: Double = 0.0
        private set
    @Volatile var lastTimedOut: Boolean = false
        private set

    /**
     * Generate text for [prompt]. Returns null if the runner/model isn't ready. Blocking — call off the UI thread.
     * J3: default maxTokens reduced to 32 (1–2 sentences is enough for a chess coach comment).
     * J5: strips [TPS:…] / [TIMEOUT] markers from the raw native output and exposes them via
     * [lastTokensPerSec] / [lastTimedOut].
     */
    @Synchronized
    fun generate(prompt: String, maxTokens: Int = 32): String? {
        if (!isAvailable || handle == 0L) return null
        val raw = try { nativeGenerate(handle, prompt, maxTokens) } catch (t: Throwable) {
            Log.e(TAG, "generate failed", t); return null
        }
        lastTimedOut = raw.contains("\n[TIMEOUT]")
        val tpsMatch = Regex("""\[TPS:([\d.]+)\]""").find(raw)
        lastTokensPerSec = tpsMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return raw
            .replace("\n[TIMEOUT]", "")
            .replace(Regex("""\n\[TPS:[^\]]+\]"""), "")
            .trim()
    }

    @Synchronized
    fun unload() {
        if (handle != 0L) { nativeFree(handle); handle = 0L; loadedModelPath = null }
    }

    private external fun nativeLoad(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String
    private external fun nativeFree(handle: Long)
}
