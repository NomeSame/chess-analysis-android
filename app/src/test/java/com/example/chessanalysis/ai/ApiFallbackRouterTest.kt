package com.example.chessanalysis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiFallbackRouterTest {
    @Test fun googleRouteKeepsRoutingOrderAndOnlyAvailableModels() {
        val chain = ApiFallbackRouter.availableChain(ApiProvider.GOOGLE, listOf(
            "gemini-2.5-flash", "models/gemini-3.8-flash", "gemma-4-26b-a4b-it"
        ))

        assertEquals(listOf("gemini-3.8-flash", "gemini-2.5-flash", "gemma-4-26b-a4b-it"), chain)
    }

    @Test fun nvidiaRouteUsesConfiguredPriority() {
        val chain = ApiFallbackRouter.availableChain(ApiProvider.NVIDIA, listOf(
            "meta/llama-3.2-90b-vision-instruct", "nvidia/nemotron-3-ultra-550b-a55b"
        ))

        assertEquals(listOf("nvidia/nemotron-3-ultra-550b-a55b", "meta/llama-3.2-90b-vision-instruct"), chain)
    }

    @Test fun keyPrefixesAndRetryStatusesAreConservative() {
        assertTrue(ApiFallbackRouter.isLikelyGoogleKey("AIzaExample"))
        assertTrue(ApiFallbackRouter.isLikelyNvidiaKey("nvapi-example"))
        assertFalse(ApiFallbackRouter.isLikelyGoogleKey("sk-example"))
        assertTrue(ApiFallbackRouter.shouldTryNext(429))
        assertTrue(ApiFallbackRouter.shouldTryNext(503))
        assertFalse(ApiFallbackRouter.shouldTryNext(401))
    }
}
