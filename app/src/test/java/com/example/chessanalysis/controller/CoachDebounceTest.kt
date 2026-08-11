package com.example.chessanalysis.controller

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coach must not fire on a position a user only passes through. [CoachCommentController.COACH_DEBOUNCE_MS]
 * waits after the LAST position change, so a prompt only goes out once the user has stayed on a move
 * for at least this long. The product requirement is "at least 1 second".
 */
class CoachDebounceTest {

    @Test
    fun `prompt waits at least one second on a position`() {
        assertTrue(
            "COACH_DEBOUNCE_MS must be >= 1000ms (user stays ≥1s on a position before the LLM prompt is sent)",
            CoachCommentController.COACH_DEBOUNCE_MS >= 1000L
        )
    }
}