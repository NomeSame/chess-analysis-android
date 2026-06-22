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
    fun load(modelPath: String, nCtx: Int = 1024, nThreads: Int = defaultThreads()): Boolean {
        if (!isAvailable) return false
        if (handle != 0L && loadedModelPath == modelPath) return true
        if (handle != 0L) { nativeFree(handle); handle = 0L; loadedModelPath = null }
        val h = try { nativeLoad(modelPath, nCtx, nThreads) } catch (t: Throwable) {
            Log.e(TAG, "load failed", t); 0L
        }
        if (h != 0L) { handle = h; loadedModelPath = modelPath }
        return h != 0L
    }

    /** Generate text for [prompt]. Returns null if the runner/model isn't ready. Blocking — call off the UI thread. */
    @Synchronized
    fun generate(prompt: String, maxTokens: Int = 256): String? {
        if (!isAvailable || handle == 0L) return null
        return try { nativeGenerate(handle, prompt, maxTokens) } catch (t: Throwable) {
            Log.e(TAG, "generate failed", t); null
        }
    }

    @Synchronized
    fun unload() {
        if (handle != 0L) { nativeFree(handle); handle = 0L; loadedModelPath = null }
    }

    private external fun nativeLoad(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String
    private external fun nativeFree(handle: Long)
}
