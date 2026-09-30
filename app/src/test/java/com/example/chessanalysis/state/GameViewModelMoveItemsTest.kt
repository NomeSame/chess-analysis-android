package com.example.chessanalysis.state

import com.example.chessanalysis.controller.GamePlayController
import com.example.chessanalysis.engine.GameReviewer
import com.example.chessanalysis.model.MoveClass
import org.junit.Assert.assertEquals
import org.junit.Test

class GameViewModelMoveItemsTest {
    private val e4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

    @Test
    fun `move text and review class are exposed separately`() {
        val model = GameViewModel().apply {
            positionHistory.clear()
            positionHistory.addAll(listOf(GamePlayController.START_FEN, e4))
        }
        val review = GameReviewer.GameReview(
            perPly = listOf(MoveClass.BRILLIANT),
            evalWhitePov = listOf(0, 20),
            counts = emptyMap(),
            accuracy = emptyMap(),
            bestMovePerPos = listOf("e2e4", null)
        )

        val move = model.buildMoveItems(review)[1]

        assertEquals("1. e4", move.displayText)
        assertEquals(MoveClass.BRILLIANT, move.moveClass)
    }
}
