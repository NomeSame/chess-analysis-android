package com.example.chessanalysis.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveClassTest {

    private fun info(
        bestCp: Int? = null, bestMate: Int? = null,
        playedCp: Int? = null, playedMate: Int? = null,
        bestMove: String? = "e2e4", playedMove: String? = "e2e4",
        secondCp: Int? = null, secondMate: Int? = null
    ) = EvalInfo(
        ply = 0, fenBefore = "",
        bestMoveUci = bestMove, bestCp = bestCp, bestMate = bestMate,
        secondCp = secondCp, secondMate = secondMate,
        playedMoveUci = playedMove, playedCp = playedCp, playedMate = playedMate
    )

    @Test
    fun cpToWinPctIsMonotonicAndClamped() {
        assertTrue(MoveClass.cpToWinPct(0) > 49.9)
        assertTrue(MoveClass.cpToWinPct(1000) > MoveClass.cpToWinPct(500))
        assertTrue(MoveClass.cpToWinPct(-1000) < MoveClass.cpToWinPct(-500))
        assertTrue(MoveClass.cpToWinPct(100000) <= 100.0)
        assertTrue(MoveClass.cpToWinPct(-100000) >= 0.0)
    }

    @Test
    fun evalToWinPctMatePositiveIs100() {
        assertEquals(100.0, MoveClass.evalToWinPct(null, 1), 0.001)
        assertEquals(100.0, MoveClass.evalToWinPct(null, 5), 0.001)
        assertEquals(0.0, MoveClass.evalToWinPct(null, -1), 0.001)
    }

    @Test
    fun playingBestMoveIsBest() {
        val e = info(bestCp = 100, playedCp = 100, bestMove = "e2e4", playedMove = "e2e4")
        assertEquals(MoveClass.BEST, MoveClass.classify(e))
    }

    @Test
    fun bigDropWhenWinningIsMiss() {
        // best 400 (~87%), played -600 (~6%): H-fix1 -> MISS (huge drop while already winning).
        val e = info(bestCp = 400, playedCp = -600, secondCp = 380, bestMove = "d2d4", playedMove = "e2e4")
        assertEquals(MoveClass.MISS, MoveClass.classify(e))
    }

    @Test
    fun bigDropFromBalancedIsBlunder() {
        // best 100 (~59%), played -600 (~6%): bestWin < 65 -> BLUNDER.
        val e = info(bestCp = 100, playedCp = -600, secondCp = 90, bestMove = "d2d4", playedMove = "e2e4")
        assertEquals(MoveClass.BLUNDER, MoveClass.classify(e))
    }

    @Test
    fun checkmateDeliveredIsNeverBelowGreat() {
        // playedMate=0 (mate delivered), not the fastest best move -> GREAT
        val e = info(bestMate = 2, playedMate = 0, bestMove = "qg6", playedMove = "d1h5")
        assertTrue(
            MoveClass.classify(e) == MoveClass.GREAT || MoveClass.classify(e) == MoveClass.BEST
        )
    }

    @Test
    fun checkmateAndIsBestGivesBest() {
        // Best move AND mate; second-best is also a mate so not "unique" -> isBest branch -> BEST.
        val e = info(bestMate = 1, playedMate = 0, bestMove = "d1h5", playedMove = "d1h5", secondMate = 1)
        assertEquals(MoveClass.BEST, MoveClass.classify(e))
    }

    @Test
    fun uniqueMoveOrBandJumpIsGreat() {
        // Only move (second much worse) and near best -> GREAT
        val e = info(bestCp = 120, playedCp = 115, secondCp = -200)
        assertEquals(MoveClass.GREAT, MoveClass.classify(e))
    }

    @Test
    fun tinyDropIsExcellent() {
        // Not the best move (different UCI), small drop -> EXCELLENT.
        val e = info(bestCp = 100, playedCp = 98, secondCp = 80, bestMove = "d2d4", playedMove = "e2e4")
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(e))
    }

    @Test
    fun brilliantRequiresSacrificeAndAdvantage() {
        val e = info(bestCp = 150, playedCp = 300, secondCp = -50)
        assertEquals(MoveClass.BRILLIANT, MoveClass.classify(e, materialSacrificed = true))
    }

    @Test
    fun sacrificeWithoutWinIsNotBrilliant() {
        val e = info(bestCp = -100, playedCp = -90, secondCp = -120)
        assertTrue(MoveClass.classify(e, materialSacrificed = true) != MoveClass.BRILLIANT)
    }

    @Test
    fun cpLossTierCapsAtMistake() {
        assertEquals(MoveClass.EXCELLENT, MoveClass.cpLossClassify(49))
        assertEquals(MoveClass.GOOD, MoveClass.cpLossClassify(50))
        assertEquals(MoveClass.GOOD, MoveClass.cpLossClassify(99))
        assertEquals(MoveClass.INACCURACY, MoveClass.cpLossClassify(100))
        assertEquals(MoveClass.MISTAKE, MoveClass.cpLossClassify(300))
        assertEquals(MoveClass.MISTAKE, MoveClass.cpLossClassify(99999))
    }

    @Test
    fun worseOfCombinesDropAndCpLoss() {
        // Huge win-drop but small cp-loss -> the drop tier (MISS) still governs.
        val e = info(bestCp = 400, playedCp = -600, secondCp = 380, bestMove = "d2d4", playedMove = "e2e4")
        assertEquals(MoveClass.MISS, MoveClass.classify(e, cpLoss = 10))
    }
}
