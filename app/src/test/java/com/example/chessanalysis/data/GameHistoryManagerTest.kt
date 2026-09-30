package com.example.chessanalysis.data

import android.content.Context
import com.example.chessanalysis.controller.GamePlayController
import com.example.chessanalysis.model.GameReviewSnapshot
import com.example.chessanalysis.model.MoveClass
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GameHistoryManagerTest {
    private lateinit var context: Context
    private val start = GamePlayController.START_FEN
    private val after = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        GameHistoryManager.clearAll(context)
    }

    @After fun tearDown() = GameHistoryManager.clearAll(context)

    @Test
    fun `completed review snapshot survives history roundtrip`() {
        val review = snapshot()
        GameHistoryManager.saveGame(context, listOf(start, after), listOf(null, 6 to 4), 16, review = review)

        assertEquals(review, GameHistoryManager.loadAll(context).single().review)
    }

    @Test
    fun `legacy analyzed record is upgraded with review snapshot`() {
        val fens = listOf(start, after)
        val origins = listOf(null, 6 to 4)
        GameHistoryManager.saveGame(
            context, fens, origins, 16,
            accuracy = mapOf("white" to 99.0, "black" to 0.0),
            counts = mapOf("white" to mapOf("BEST" to 1), "black" to emptyMap())
        )
        assertNull(GameHistoryManager.loadAll(context).single().review)

        GameHistoryManager.updateGame(context, fens, origins, 16, review = snapshot())

        assertEquals(snapshot(), GameHistoryManager.loadAll(context).single().review)
    }

    private fun snapshot() = GameReviewSnapshot(
        perPly = listOf(MoveClass.BEST),
        evalWhitePov = listOf(0, 20),
        counts = mapOf(true to mapOf(MoveClass.BEST to 1), false to emptyMap()),
        accuracy = mapOf(true to 99.0, false to 0.0),
        bestMovePerPos = listOf("e2e4", null),
        openingTexts = emptyMap(),
        cpLosses = listOf(0),
        tactics = emptyList(),
        bestEvalPerPos = listOf("+0.20"),
        playedEvalPerPos = listOf("+0.20"),
        bestPvPerPos = listOf(listOf("e2e4"), emptyList())
    )
}
