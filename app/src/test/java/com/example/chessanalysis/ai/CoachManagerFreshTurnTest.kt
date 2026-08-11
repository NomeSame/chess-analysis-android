package com.example.chessanalysis.ai

import com.example.chessanalysis.model.MoveClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each viewed move must be a FRESH single-turn conversation: the on-device runner clears its KV cache
 * per generate() (llama_jni.cpp) and the API call sends exactly one system+user pair. So the model never
 * sees any context from earlier moves — only the current position's Stockfish verdict. These tests pin
 * the Kotlin side of that contract: the prompt carries exclusively the current move's data.
 */
class CoachManagerFreshTurnTest {

    private val ctx = CoachManager.Ctx(
        fenBefore = "rnbqkbnr/pppp1ppp/8/4p3/2B1P3/8/PPPP1PPP/RNBQK1NR b KQkq - 0 2",
        fullmove = 2,
        moverWhite = false,
        playedUci = "e7e5",
        cls = MoveClass.GOOD,
        bestUci = "e7e5",
        bestEval = "0.5",
        playedEval = "0.5",
        openingText = null
    )

    @Test
    fun `single-turn prompt - one user turn, no prior model turn`() {
        val prompt = CoachManager.buildPrompt(ctx)
        // Gemma template: exactly one <start_of_turn>user with the system instructions merged in,
        // then the fresh <start_of_turn>model opener. No history block from previous moves.
        assertEquals(1, Regex("<start_of_turn>user").findAll(prompt).count())
        assertEquals(1, Regex("<start_of_turn>model").findAll(prompt).count())
        assertEquals(1, Regex("<end_of_turn>").findAll(prompt).count())
        assertTrue(prompt.endsWith("<start_of_turn>model\n"))
    }

    @Test
    fun `context contains only the current move - no other ply bleeds in`() {
        val user = CoachManager.buildUser(ctx)
        assertTrue(user.contains("Move 2"))
        assertTrue(user.contains("e7→e5"))         // the played move in THIS position (rendered by describeMove)
        assertTrue(user.contains("0.5"))           // the engine eval for THIS position
        assertFalse(user.contains("Move 1"))
        assertFalse(user.contains("Move 3"))
        // No field from a previous or later position may appear.
        assertFalse(user.contains("previous"))
        assertFalse(user.contains("history"))
    }

    @Test
    fun `different moves produce separate conversations`() {
        // Both variants get fully distinct data (played move + best move + eval) so that a bleed
        // would be visible in the other conversation's text.
        val ctxA = ctx.copy(fullmove = 2, playedUci = "e7e5", bestUci = "e7e5",
            bestEval = "0.5", playedEval = "0.5", cls = MoveClass.GOOD)
        val ctxB = ctx.copy(fullmove = 3, playedUci = "g8f6", bestUci = "g8f6",
            bestEval = "1.2", playedEval = "0.8", cls = MoveClass.BEST)
        val a = CoachManager.buildUser(ctxA)
        val b = CoachManager.buildUser(ctxB)
        // Conversation A must be completely free of the data used in B, and vice versa.
        assertFalse(a.contains("g8→f6"))
        assertTrue(a.contains("e7→e5"))
        assertFalse(b.contains("e7→e5"))
        assertTrue(b.contains("g8→f6"))
        assertTrue(a.contains("0.5"))
        assertTrue(b.contains("1.2"))
        assertFalse(b.contains("0.5"))
    }
}