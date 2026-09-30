package com.example.chessanalysis.engine

import com.example.chessanalysis.model.MoveClass
import com.example.chessanalysis.model.TacticKind
import com.example.chessanalysis.model.TacticalChance
import org.junit.Assert.assertEquals
import org.junit.Test

class GameReviewSnapshotMapperTest {
    @Test
    fun `snapshot roundtrip preserves every review UI field`() {
        val review = GameReviewer.GameReview(
            perPly = listOf(MoveClass.BRILLIANT),
            evalWhitePov = listOf(0, 125),
            counts = mapOf(true to mapOf(MoveClass.BRILLIANT to 1), false to emptyMap()),
            accuracy = mapOf(true to 99.5, false to 0.0),
            bestMovePerPos = listOf("e2e4", null),
            openingTexts = mapOf(0 to "GMs: e4 62%"),
            cpLosses = listOf(0),
            tactics = listOf(TacticalChance(0, "fen", "d2d4", "e2e4", 125, TacticKind.MISSED_CP, description = "chance")),
            bestEvalPerPos = listOf("+1.25"),
            playedEvalPerPos = listOf("+1.20"),
            bestPvPerPos = listOf(listOf("e2e4", "e7e5"), emptyList())
        )

        val snapshot = GameReviewSnapshotMapper.snapshot(review).validate(positionCount = 2)

        assertEquals(review, GameReviewSnapshotMapper.restore(snapshot))
    }
}
