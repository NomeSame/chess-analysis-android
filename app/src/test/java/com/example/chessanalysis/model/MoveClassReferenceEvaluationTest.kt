package com.example.chessanalysis.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Provider scores are diagnostic fixtures, never replacements for frozen engine measurements. */
class MoveClassReferenceEvaluationTest {
    @Test
    fun scubadillaRookMoveIsGoodWithReportedReferenceScores() {
        // User-reported Chess.com scores use White's perspective: e5 -1.91, Rg5 -1.51.
        // EvalInfo requires the mover's perspective, hence +191 and +151 for Black.
        // No second-variation score was supplied: do not borrow it from another engine run.
        val reference = EvalInfo(
            ply = 31,
            fenBefore = "r7/pp1k1pp1/2pqpn2/3p3r/1P1P4/P3P1Pp/2P1NP2/R2Q1RK1 b - - 2 16",
            bestMoveUci = "e6e5", bestCp = 191, bestMate = null,
            secondCp = null, secondMate = null,
            playedMoveUci = "h5g5", playedCp = 151, playedMate = null
        )
        assertEquals(40, reference.bestCp!! - reference.playedCp!!)
        assertEquals(MoveClass.GOOD, MoveClass.classify(reference))
        // Independent Depth-16 Stockfish values disagree with the reference, not with
        // the ordinary class bands. This explicitly keeps the unresolved distinction visible.
        val frozen = reference.copy(bestCp = 175, secondCp = 163, playedCp = 165)
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(frozen))
    }
}
