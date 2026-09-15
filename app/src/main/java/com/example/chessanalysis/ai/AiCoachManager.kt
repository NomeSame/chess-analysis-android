package com.example.chessanalysis.ai

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.chessanalysis.data.ModelStorage
import com.example.chessanalysis.engine.LlamaRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

enum class AiCoachMode(val key: String) {
    NONE("NONE"),
    GEMMA_1B("GEMMA_1B"),
    GEMMA_3B("GEMMA_3B"),
    API_KEY("API_KEY"),
    LICHESS("LICHESS")
}

enum class ApiProvider(
    val label: String, val url: String,
    val defaultBaseUrl: String, val defaultModel: String
) {
    CLAUDE("Claude (Anthropic)", "https://console.anthropic.com", "https://api.anthropic.com/v1", "claude-opus-4-8"),
    OPENAI("OpenAI", "https://platform.openai.com", "https://api.openai.com/v1", "gpt-4o-mini"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/keys", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
    GOOGLE("Google Gemini", "https://aistudio.google.com/app/apikey", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.1-pro-preview"),
    NVIDIA("NVIDIA NIM", "https://build.nvidia.com", "https://integrate.api.nvidia.com/v1", "deepseek-ai/deepseek-v4-pro-0813"),
    CUSTOM("Custom / LM Studio", "https://lmstudio.ai/docs/api/server", "http://localhost:1234/v1", "local-model")
}

data class ApiProviderDetection(val provider: ApiProvider, val modelChain: List<String>)

data class ModelInfo(
    val mode: AiCoachMode,
    val fileName: String,
    val downloadUrl: String,
    val expectedSizeMb: Int,
    val ramRequirementGb: Double,
    val minRamGb: Double
)

object AiCoachManager {
    private const val PREFS_NAME = "ai_coach_secure"
    private const val KEY_MODE = "ai_coach_mode"
    private const val KEY_API_KEY = "ai_coach_api_key"
    private const val KEY_API_PROVIDER = "ai_coach_api_provider"
    private const val KEY_API_BASE_URL = "ai_coach_api_base_url"
    private const val KEY_API_MODEL = "ai_coach_api_model"
    private const val KEY_API_FALLBACK_MODELS = "ai_coach_api_fallback_models"
    private const val KEY_DOWNLOADED_1B = "gemma_1b_downloaded"
    private const val KEY_DOWNLOADED_3B = "gemma_3b_downloaded"
    private const val KEY_LICHESS_BANNER_SEEN = "lichess_banner_seen"
    private const val KEY_CUSTOM_MODEL_URI = "ai_coach_custom_model_uri"
    private const val KEY_CUSTOM_MODEL_NAME = "ai_coach_custom_model_name"
    private const val TAG = "AiCoach"

    private var _prefs: SharedPreferences? = null
    private var _context: Context? = null

    private fun prefs(ctx: Context): SharedPreferences {
        if (_prefs == null) {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            _prefs = EncryptedSharedPreferences.create(
                ctx, PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
        return _prefs!!
    }

    fun init(context: Context) {
        _context = context.applicationContext
    }

    // GGUF models, run on-device via the bundled llama.cpp runner ([LlamaRunner]).
    // Public HuggingFace community repo (no login/token). resolve/main/<file> = direct download.
    private val models = mapOf(
        AiCoachMode.GEMMA_1B to ModelInfo(
            mode = AiCoachMode.GEMMA_1B,
            fileName = "gemma-3-1B.gguf",  // local save name; URL below uses the repo's real filename
            downloadUrl = "https://huggingface.co/lmstudio-community/gemma-3-1B-it-qat-GGUF/resolve/main/gemma-3-1B-it-QAT-Q4_0.gguf",
            expectedSizeMb = 720,
            ramRequirementGb = 1.5,
            minRamGb = 1.0
        )
    )

    fun getModelInfo(mode: AiCoachMode): ModelInfo? = models[mode]

    fun getActiveMode(ctx: Context): AiCoachMode {
        val modeStr = prefs(ctx).getString(KEY_MODE, AiCoachMode.NONE.key) ?: AiCoachMode.NONE.key
        val mode = AiCoachMode.entries.firstOrNull { it.key == modeStr } ?: AiCoachMode.NONE
        if (mode == AiCoachMode.GEMMA_1B && !isModelDownloaded(ctx, AiCoachMode.GEMMA_1B)) {
            return AiCoachMode.LICHESS
        }
        if (mode == AiCoachMode.GEMMA_3B && !isModelDownloaded(ctx, AiCoachMode.GEMMA_3B)) {
            return AiCoachMode.LICHESS
        }
        return mode
    }

    fun getActiveModeRaw(ctx: Context): AiCoachMode {
        val modeStr = prefs(ctx).getString(KEY_MODE, AiCoachMode.NONE.key) ?: AiCoachMode.NONE.key
        return AiCoachMode.entries.firstOrNull { it.key == modeStr } ?: AiCoachMode.NONE
    }

    fun setActiveMode(ctx: Context, mode: AiCoachMode) {
        prefs(ctx).edit().putString(KEY_MODE, mode.key).apply()
    }

    fun isModelDownloaded(ctx: Context, mode: AiCoachMode): Boolean = modelFile(ctx, mode) != null

    /** The downloaded model file, wherever it lives (see [ModelStorage]), or null if absent. */
    fun modelFile(ctx: Context, mode: AiCoachMode): File? {
        val info = models[mode] ?: return null
        return ModelStorage.findModel(ctx, info.fileName)
    }

    // --- Manually picked model (folder chosen by the user via SAF) -------------------------------

    /**
     * A model the user pointed the app at instead of the built-in download. Stored as a document
     * URI because a folder picked through SAF has no usable filesystem path on Android 10+.
     */
    fun customModelUri(ctx: Context): String? =
        prefs(ctx).getString(KEY_CUSTOM_MODEL_URI, null)?.takeIf { it.isNotBlank() }

    fun setCustomModelUri(ctx: Context, uri: String?, displayName: String? = null) {
        prefs(ctx).edit()
            .putString(KEY_CUSTOM_MODEL_URI, uri)
            .putString(KEY_CUSTOM_MODEL_NAME, displayName)
            .apply()
    }

    fun customModelName(ctx: Context): String? =
        prefs(ctx).getString(KEY_CUSTOM_MODEL_NAME, null)?.takeIf { it.isNotBlank() }

    /**
     * If the active mode is an on-device Gemma model that's downloaded, load it into [LlamaRunner].
     * Returns true if a model is loaded and ready. No-op (false) when the runner is disabled in this
     * build or the model isn't present. Blocking — call off the UI thread.
     */
    fun ensureModelLoaded(ctx: Context): Boolean {
        val mode = getActiveModeRaw(ctx)
        if (mode != AiCoachMode.GEMMA_1B && mode != AiCoachMode.GEMMA_3B) return false
        if (!LlamaRunner.isAvailable) return false
        // A manually picked model wins over the downloaded one — that's why the user picked it.
        customModelUri(ctx)?.let { return LlamaRunner.loadFromUri(ctx, android.net.Uri.parse(it)) }
        val f = modelFile(ctx, mode) ?: return false
        return LlamaRunner.load(f.absolutePath)
    }

    suspend fun downloadModel(
        ctx: Context,
        mode: AiCoachMode,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val info = models[mode] ?: throw IllegalArgumentException("Unknown model: $mode")
        if (ModelStorage.findModel(ctx, info.fileName) != null) {
            onProgress(100)
            return@withContext
        }
        val sink = ModelStorage.openForWrite(ctx, info.fileName)
        val url = URL(info.downloadUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        try {
            conn.connect()
            val totalSize = conn.contentLength.coerceAtLeast(1)
            val input = conn.inputStream
            val output = sink.stream
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L
            var lastProgress = 0
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                val pct = (totalRead * 100 / totalSize).toInt().coerceIn(0, 100)
                if (pct > lastProgress) {
                    lastProgress = pct
                    onProgress(pct)
                }
            }
            input.close()
            // A connection that dies mid-transfer also ends the read loop — a short file here means
            // a truncated model, which would later fail to load with an unhelpful native error.
            if (conn.contentLength > 0 && totalRead < conn.contentLength) {
                throw java.io.IOException("Download incomplete: $totalRead of ${conn.contentLength} bytes")
            }
            sink.finish(info.fileName)
            onProgress(100)
            // Mark as downloaded
            when (mode) {
                AiCoachMode.GEMMA_1B -> prefs(ctx).edit().putBoolean(KEY_DOWNLOADED_1B, true).apply()
                AiCoachMode.GEMMA_3B -> prefs(ctx).edit().putBoolean(KEY_DOWNLOADED_3B, true).apply()
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            sink.discard()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    fun getApiProvider(ctx: Context): ApiProvider {
        val name = prefs(ctx).getString(KEY_API_PROVIDER, ApiProvider.CLAUDE.name) ?: ApiProvider.CLAUDE.name
        return ApiProvider.entries.firstOrNull { it.name == name } ?: ApiProvider.CLAUDE
    }

    fun setApiProvider(ctx: Context, provider: ApiProvider) {
        prefs(ctx).edit().putString(KEY_API_PROVIDER, provider.name).apply()
    }

    fun getApiKey(ctx: Context): String {
        return prefs(ctx).getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(ctx: Context, key: String) {
        prefs(ctx).edit().putString(KEY_API_KEY, key).apply()
    }

    /** Base URL (e.g. http://host:1234/v1). Falls back to the active provider's default when empty. */
    fun getApiBaseUrl(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_API_BASE_URL, "") ?: ""
        return if (v.isBlank()) getApiProvider(ctx).defaultBaseUrl else v
    }

    fun setApiBaseUrl(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_API_BASE_URL, v).apply()
    }

    /** Model name to request. Falls back to the active provider's default when empty. */
    fun getApiModel(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_API_MODEL, "") ?: ""
        return if (v.isBlank()) getApiProvider(ctx).defaultModel else v
    }

    fun setApiModel(ctx: Context, v: String) {
        // A hand-picked model is intentional; do not silently append a previous provider route.
        prefs(ctx).edit().putString(KEY_API_MODEL, v).remove(KEY_API_FALLBACK_MODELS).apply()
    }

    fun getApiModelChain(ctx: Context): List<String> {
        val saved = prefs(ctx).getString(KEY_API_FALLBACK_MODELS, null)
            ?.let { raw -> runCatching { JSONArray(raw) }.getOrNull() }
            ?.let { array -> List(array.length()) { array.optString(it) }.filter { it.isNotBlank() } }
            .orEmpty()
        return if (saved.isNotEmpty()) saved else listOf(getApiModel(ctx))
    }

    /**
     * Tests only the official endpoint for a key whose prefix identifies Google/NVIDIA, or for the
     * provider explicitly selected in the dialog.  This avoids sending an arbitrary third-party key
     * to another vendor merely to guess its origin.
     */
    fun detectKnownProvider(key: String, selectedProvider: ApiProvider? = null): ApiProviderDetection? {
        val candidates = when {
            selectedProvider == ApiProvider.GOOGLE || selectedProvider == ApiProvider.NVIDIA -> listOf(selectedProvider)
            ApiFallbackRouter.isLikelyGoogleKey(key) -> listOf(ApiProvider.GOOGLE)
            ApiFallbackRouter.isLikelyNvidiaKey(key) -> listOf(ApiProvider.NVIDIA)
            else -> emptyList()
        }
        return candidates.firstNotNullOfOrNull { provider ->
            val modelIds = probeOpenAiModels(provider, key) ?: return@firstNotNullOfOrNull null
            val chain = ApiFallbackRouter.availableChain(provider, modelIds)
            // A reachable key can have a restricted catalog; preserve the documented route only
            // when at least one of its models is actually available to this key.
            if (chain.isEmpty()) null else ApiProviderDetection(provider, chain)
        }
    }

    fun applyProviderDetection(ctx: Context, detection: ApiProviderDetection) {
        prefs(ctx).edit()
            .putString(KEY_API_PROVIDER, detection.provider.name)
            .putString(KEY_API_BASE_URL, detection.provider.defaultBaseUrl)
            .putString(KEY_API_MODEL, detection.modelChain.first())
            .putString(KEY_API_FALLBACK_MODELS, JSONArray(detection.modelChain).toString())
            .apply()
    }

    /**
     * Blocking chat completion against the configured API provider. Returns the assistant text or null.
     * Claude uses the Anthropic Messages API; OpenAI/OpenRouter/Custom (LM Studio) use the OpenAI-compatible
     * /chat/completions schema (the same Bearer key field carries the LM Studio token). Call off the UI thread.
     */
    fun apiChat(ctx: Context, system: String, user: String, maxTokens: Int = 200): String? =
        try { apiChatThrowing(ctx, system, user, maxTokens) } catch (e: Exception) { Log.e(TAG, "apiChat failed", e); null }

    /** Same as [apiChat] but throws on failure (so the test button can surface the real error). */
    private fun apiChatThrowing(ctx: Context, system: String, user: String, maxTokens: Int): String? {
        val key = getApiKey(ctx)
        if (key.isBlank()) throw IllegalStateException("no key")
        val baseUrl = getApiBaseUrl(ctx).trimEnd('/')
        val provider = getApiProvider(ctx)
        val models = getApiModelChain(ctx)
        var lastFailure: Exception? = null
        for ((index, model) in models.withIndex()) {
            try {
                return if (provider == ApiProvider.CLAUDE) anthropicChat(baseUrl, key, model, system, user, maxTokens)
                else openAiChat(baseUrl, key, model, system, user, maxTokens)
            } catch (e: ApiRequestException) {
                lastFailure = e
                if (index == models.lastIndex || !ApiFallbackRouter.shouldTryNext(e.statusCode)) throw e
            }
        }
        throw lastFailure ?: IllegalStateException("no configured model")
    }

    /** Connection test: returns "ok" on success, otherwise the provider's real error message. Call off the UI thread. */
    fun apiTest(ctx: Context): String = try {
        val out = apiChatThrowing(ctx, "You reply with one word.", "ping", 5)
        if (out.isNullOrBlank()) "empty response" else "ok"
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    private fun postJson(conn: HttpURLConnection, body: JSONObject): String {
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 90000  // large local models can be slow to generate
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw ApiRequestException(code, text.take(300))
        return text
    }

    private fun probeOpenAiModels(provider: ApiProvider, key: String): List<String>? = try {
        val conn = URL("${provider.defaultBaseUrl.trimEnd('/')}/models").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Authorization", "Bearer $key")
        if (provider == ApiProvider.GOOGLE) conn.setRequestProperty("x-goog-api-client", "chess-analysis-app/1")
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) null
        else {
            JSONObject(text).optJSONArray("data")?.let { data ->
                List(data.length()) { data.optJSONObject(it)?.optString("id").orEmpty() }
                    .filter { it.isNotBlank() }
            }
        }
    } catch (_: Exception) {
        null
    }

    private class ApiRequestException(val statusCode: Int, response: String) :
        java.io.IOException("HTTP $statusCode: $response")

    private fun openAiChat(baseUrl: String, key: String, model: String, system: String, user: String, maxTokens: Int): String? {
        val conn = URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $key")
        val msgs = JSONArray()
        if (system.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", system))
        msgs.put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", 0.35)
            put("messages", msgs)
        }
        val resp = postJson(conn, body)
        conn.disconnect()
        val msg = JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        // Reasoning models often wrap chain-of-thought in <think>…</think> or use a separate field.
        var content = msg.optString("content", "")
            .replace(Regex("(?s)<think>.*?</think>"), "").trim()
        if (content.isBlank()) content = msg.optString("reasoning_content", "").trim()
        return content
    }

    private fun anthropicChat(baseUrl: String, key: String, model: String, system: String, user: String, maxTokens: Int): String? {
        val conn = URL("$baseUrl/messages").openConnection() as HttpURLConnection
        conn.setRequestProperty("x-api-key", key)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            if (system.isNotBlank()) put("system", system)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        }
        val resp = postJson(conn, body)
        conn.disconnect()
        val content = JSONObject(resp).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString().trim()
    }

    fun isLichessBannerSeen(ctx: Context): Boolean {
        return prefs(ctx).getBoolean(KEY_LICHESS_BANNER_SEEN, false)
    }

    fun setLichessBannerSeen(ctx: Context, seen: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LICHESS_BANNER_SEEN, seen).apply()
    }

    fun getTotalRamMb(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / (1024 * 1024)
    }

    fun isFallbackActive(ctx: Context): Boolean {
        val raw = getActiveModeRaw(ctx)
        return (raw == AiCoachMode.GEMMA_1B || raw == AiCoachMode.GEMMA_3B) &&
               !isModelDownloaded(ctx, raw)
    }
}
