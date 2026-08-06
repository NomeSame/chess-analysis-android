package com.example.chessanalysis.engine

import android.util.Log
import com.example.chessanalysis.BuildConfig

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
    /**
     * [nCtx] must hold the whole prompt AND the answer. The grounded coach prompt measures ~1700
     * characters (≈ 450 tokens) and the theory prompt is longer — against the old 512 there was no
     * room left for the reply, so the model was working right at (or past) the edge of its context.
     */
    fun load(modelPath: String, nCtx: Int = 2048, nThreads: Int = defaultThreads()): Boolean {
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

    /** Receives the answer while it is being generated. Called on the generating (background) thread. */
    fun interface TokenSink {
        fun onToken(chunk: String)
    }

    /**
     * Generate text for [prompt]. Returns null if the runner/model isn't ready. Blocking — call off
     * the UI thread; pass [onToken] to see the answer as it grows.
     *
     * J3: default maxTokens 32 (1–2 sentences is enough for a chess coach comment).
     * J5: strips [TPS:…] / [TIMEOUT] markers from the raw native output and exposes them via
     * [lastTokensPerSec] / [lastTimedOut].
     *
     * [timeoutMs] is a **budget, not a verdict**: it stops the generation, but the text produced so
     * far is returned normally. Discarding it (the old behaviour) threw away answers that were
     * finished except for the last few words.
     */
    @Synchronized
    fun generate(
        prompt: String,
        maxTokens: Int = 32,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        onToken: TokenSink? = null
    ): String? {
        if (!isAvailable || handle == 0L) return null
        val raw = try { nativeGenerate(handle, prompt, maxTokens, timeoutMs, onToken) } catch (t: Throwable) {
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

    /** Kept open while a model loaded via [loadFromUri] is in use — closing it invalidates the mapping. */
    private var openFd: android.os.ParcelFileDescriptor? = null

    /**
     * Load a model the user picked through the system file picker.
     *
     * A document URI has no filesystem path on Android 10+, and llama.cpp opens a path — so the
     * descriptor is opened here and handed over as `/proc/self/fd/N`, which resolves to the same
     * file. The descriptor stays open until [unload].
     */
    @Synchronized
    fun loadFromUri(ctx: android.content.Context, uri: android.net.Uri): Boolean {
        if (!isAvailable) return false
        if (handle != 0L && loadedModelPath == uri.toString()) return true
        val pfd = try { ctx.contentResolver.openFileDescriptor(uri, "r") } catch (t: Throwable) {
            Log.e(TAG, "cannot open $uri", t); null
        } ?: return false
        val ok = load("/proc/self/fd/${pfd.fd}")
        if (ok) {
            openFd?.let { try { it.close() } catch (_: Exception) {} }
            openFd = pfd
            loadedModelPath = uri.toString()
        } else {
            try { pfd.close() } catch (_: Exception) {}
        }
        return ok
    }

    @Synchronized
    fun unload() {
        if (handle != 0L) { nativeFree(handle); handle = 0L; loadedModelPath = null }
        openFd?.let { try { it.close() } catch (_: Exception) {} }
        openFd = null
    }

    /**
     * Cuts [text] back to the last finished sentence — used when the budget stopped the model
     * mid-word. Returns the text unchanged if there is no sentence end to cut at.
     */
    fun trimToLastSentence(text: String): String {
        val end = text.trimEnd().indexOfLast { it == '.' || it == '!' || it == '?' }
        return if (end >= 0) text.substring(0, end + 1) else text
    }

    /** Time budget for one generation; the partial answer is kept when it runs out. */
    const val DEFAULT_TIMEOUT_MS = 10_000

    private external fun nativeLoad(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeGenerate(
        handle: Long, prompt: String, maxTokens: Int, timeoutMs: Int, sink: TokenSink?
    ): String
    private external fun nativeFree(handle: Long)
}
