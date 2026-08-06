package com.example.chessanalysis.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The time budget stops generation mid-word; [LlamaRunner.trimToLastSentence] is what keeps that
 * answer usable instead of throwing it away.
 */
class LlamaRunnerTrimTest {

    @Test
    fun `cuts back to the last finished sentence`() {
        assertEquals(
            "White gave up a pawn here. The engine prefers Nf3.",
            LlamaRunner.trimToLastSentence("White gave up a pawn here. The engine prefers Nf3. Black can then")
        )
    }

    @Test
    fun `keeps a complete sentence unchanged`() {
        val s = "A solid developing move."
        assertEquals(s, LlamaRunner.trimToLastSentence(s))
    }

    @Test
    fun `question and exclamation end a sentence too`() {
        assertEquals("Why not Qh5?", LlamaRunner.trimToLastSentence("Why not Qh5? The idea is"))
        assertEquals("Mate in two!", LlamaRunner.trimToLastSentence("Mate in two! The king is"))
    }

    @Test
    fun `text without any sentence end survives whole`() {
        // Better a half sentence than an empty coach panel.
        assertEquals("a strong central push", LlamaRunner.trimToLastSentence("a strong central push"))
        assertEquals("", LlamaRunner.trimToLastSentence(""))
    }

    @Test
    fun `trailing whitespace after the last period is dropped`() {
        assertEquals("Good move.", LlamaRunner.trimToLastSentence("Good move.  \n "))
    }
}
