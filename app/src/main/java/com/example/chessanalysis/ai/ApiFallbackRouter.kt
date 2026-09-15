package com.example.chessanalysis.ai

/**
 * Provider-owned default routes.  The lists deliberately live outside the UI: a quota or model
 * failure while coaching must not leave the user with a dead, single-model configuration.
 */
object ApiFallbackRouter {
    val googleFreeModels = listOf(
        "gemini-3.1-pro-preview",
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash",
        "gemini-2.5-pro",
        "gemini-2.5-flash",
        "gemma-4-31b-it",
        "gemma-4-26b-a4b-it",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-2.5-flash-lite"
    )

    val nvidiaFreeModels = listOf(
        "deepseek-ai/deepseek-v4-pro-0813",
        "nvidia/nemotron-3-ultra-550b-a55b",
        "nvidia/nemotron-3-super-120b-a12b",
        "meta/llama-3.2-90b-vision-instruct"
    )

    fun defaultChain(provider: ApiProvider): List<String> = when (provider) {
        ApiProvider.GOOGLE -> googleFreeModels
        ApiProvider.NVIDIA -> nvidiaFreeModels
        else -> listOf(provider.defaultModel)
    }

    /** Only use models the provider explicitly returned, unless it did not return a usable list. */
    fun availableChain(provider: ApiProvider, modelIds: Collection<String>): List<String> {
        val available = modelIds.map { it.removePrefix("models/") }.toSet()
        val defaults = defaultChain(provider)
        return if (available.isEmpty()) defaults else defaults.filter { it in available }
    }

    fun isLikelyGoogleKey(key: String): Boolean = key.startsWith("AIza")

    fun isLikelyNvidiaKey(key: String): Boolean = key.startsWith("nvapi-")

    /** Model/quota/transient server failures may be resolved by the next configured model. */
    fun shouldTryNext(statusCode: Int): Boolean =
        statusCode == 404 || statusCode == 408 || statusCode == 409 || statusCode == 425 ||
            statusCode == 429 || statusCode in 500..599
}
