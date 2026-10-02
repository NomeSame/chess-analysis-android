package com.example.chessanalysis.state

import com.example.chessanalysis.controller.GamePlayController
import com.example.chessanalysis.engine.GameReviewer
import com.example.chessanalysis.model.MoveClass
import org.junit.Assert.assertEquals
import org.junit.Test

class GameViewModelMoveItemsTest {
    private val e4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
    private val e5 = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2"

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

    @Test
    fun `black move cards include the complete move number`() {
        val model = GameViewModel().apply {
            positionHistory.clear()
            positionHistory.addAll(listOf(GamePlayController.START_FEN, e4, e5))
        }
        val review = GameReviewer.GameReview(
            perPly = listOf(MoveClass.BEST, MoveClass.GOOD),
            evalWhitePov = listOf(0, 20, 10),
            counts = emptyMap(),
            accuracy = emptyMap(),
            bestMovePerPos = listOf("e2e4", "e7e5", null)
        )

        val blackMove = model.buildMoveItems(review)[2]

        assertEquals("1. e5", blackMove.displayText)
        assertEquals(MoveClass.GOOD, blackMove.moveClass)
    }
}
