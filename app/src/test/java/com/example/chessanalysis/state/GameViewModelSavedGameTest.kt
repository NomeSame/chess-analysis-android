package com.example.chessanalysis.state

import com.example.chessanalysis.controller.GamePlayController
import com.example.chessanalysis.data.PgnImporter
import com.example.chessanalysis.model.LiveGameSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameViewModelSavedGameTest {
    private val e4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

    @Test
    fun `restore selects last position and resets non-live modes`() {
        val model = GameViewModel().apply {
            analysisMode = true
            reviewMode = true
            theoryMode = true
            exploring = true
            gameOverShown = true
            liveSessionActive = false
            currentPgnGame = PgnImporter.parse("1. e4 *")
        }
        assertTrue(model.currentPgnGame != null)
        model.restoreLiveSnapshot(snapshot())
        assertEquals(e4, model.currentFen)
        assertEquals(1, model.viewIndex)
        assertEquals(listOf(null, 6 to 4), model.moveFromHistory)
        assertTrue(model.vsEngine)
        assertTrue(model.engineIsWhite)
        assertEquals(1875, model.gameElo)
        assertFalse(model.analysisMode || model.reviewMode || model.theoryMode || model.exploring)
        assertFalse(model.gameOverShown)
        assertTrue(model.liveSessionActive)
        assertNull(model.currentPgnGame)
    }

    @Test
    fun `dirty revision only clears for newest persisted revision`() {
        val model = GameViewModel()
        val first = model.markLiveSessionDirty()
        model.markLiveSessionDirty()
        model.markLiveSessionPersisted(first)
        assertTrue(model.liveSessionDirty)
        model.markLiveSessionPersisted(model.liveSessionRevision)
        assertFalse(model.liveSessionDirty)
    }

    private fun snapshot() = LiveGameSnapshot(
        GamePlayController.START_FEN,
        listOf(GamePlayController.START_FEN, e4),
        listOf(null, 6 to 4),
        "1. e4 *\n",
        true,
        true,
        1875
    )
}
