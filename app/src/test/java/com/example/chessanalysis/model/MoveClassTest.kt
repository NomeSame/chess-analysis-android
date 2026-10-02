package com.example.chessanalysis.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveClassTest {

    @Test
    fun calibratedThresholdsAreOneGlobalConfiguration() {
        assertEquals(
            MoveClassificationThresholds(
                onlyMoveWinPctGap = 25.0,
                nearBestWinPctDrop = 0.5,
                exactBestSearchNoiseWinPctDrop = 1.0,
                exactBandCrossingSearchNoiseWinPctDrop = 2.0,
                equivalentBestWinPctGap = 0.5,
                equivalentBestMinWinPct = 85.0,
                equivalentBestMaxCpGap = 10,
                equivalentBestMinWinPctDrop = 0.1,
                equivalentBestMaxWinPctDrop = 1.1,
                equivalentBestTinyRootGapCp = 2,
                equivalentBestTinyCpLoss = 3,
                equivalentBestTinyWinPctDrop = 0.3,
                balancedDevelopmentMaxAbsCp = 50,
                positionalExcellentMaxAbsBestCp = 100,
                positionalExcellentMaxCpLoss = 35,
                positionalExcellentMaxWinPctDrop = 3.0,
                positionalGoodMaxAbsBestCp = 130,
                positionalGoodMaxCpLoss = 65,
                positionalGoodMaxWinPctDrop = 6.1,
                castlingInaccuracyMinCpLoss = 45,
                castlingGoodMinWinPctDrop = 1.8,
                alternatePieceSameTargetGoodMinCpLoss = 15,
                alternatePieceSameTargetGoodMinWinPctDrop = 1.0,
                alternatePieceSameTargetMissMinCpLoss = 250,
                alternatePieceSameTargetMissMinWinPctDrop = 20.0,
                decisiveWinPct = 70.0,
                dominantMoveWinPctGap = 11.0,
                rescueWinPct = 55.0,
                exactBestSacrificeWinPctDrop = 3.0,
                greatResourceMaxWinPct = 67.0,
                greatResourceMinWinPctGap = 15.0,
                greatResourceMaxWinPctDrop = 5.0,
                greatWinningMinWinPct = 80.0,
                greatWinningMinWinPctGap = 15.0,
                greatWinningMaxWinPctDrop = 1.5,
                greatPawnResourceMaxWinPctDrop = 1.4,
                greatForcingAttackMinWinPctGap = 3.0,
                greatForcingAttackMaxWinPctDrop = 1.0,
                greatCheckingForkMinWinPct = 80.0,
                greatCheckingForkMaxWinPct = 95.0,
                greatCheckingForkMinWinPctDrop = 5.0,
                greatCheckingForkMaxWinPctDrop = 6.0,
                greatEquivalentForcingMaxWinPctDifference = 0.5,
                greatEquivalentForcingMinWinPct = 45.0,
                greatEquivalentForcingMaxWinPct = 55.0,
                excellentWinPctDrop = 1.9,
                goodWinPctDrop = 5.0,
                inaccuracyWinPctDrop = 10.0,
                borderlineInaccuracyWinPctDrop = 11.1,
                borderlineInaccuracyMaxCpLoss = 125,
                mistakeWinPctDrop = 24.0,
                decisiveBlunderWinPctDrop = 40.0,
                newlyForcedMateMistakeWinPct = 5.0,
                newlyForcedMateBlunderWinPct = 18.0,
                winningGoodBestWinPct = 84.0,
                winningGoodPlayedWinPct = 79.0,
                winningGoodMaxCpLoss = 110,
                winningGoodMinWinPctDrop = 5.0,
                balancedGoodMaxAbsBestCp = 70,
                balancedGoodMaxCpLoss = 65,
                balancedGoodMaxWinPctDrop = 6.1,
                winningMissBestWinPct = 91.0,
                winningMissPlayedWinPct = 85.0,
                winningMissMinCpLoss = 150,
                winningMissMinWinPctDrop = 5.0,
                missBestWinPct = 60.0,
                missPlayedWinPct = 55.0
            ),
            MoveClassificationThresholds()
        )
    }

    @Test
    fun calibratedOnlyMoveGapDoesNotPromoteAnOrdinaryBestMoveToGreat() {
        val e = info(bestCp = 100, playedCp = 100, secondCp = 50)

        assertEquals(MoveClass.BEST, MoveClass.classify(e))
    }

    @Test
    fun calibratedNearBestWindowRejectsAVisibleDropForGreat() {
        val e = info(
            bestCp = 200,
            playedCp = 190,
            secondCp = -400,
            bestMove = "d2d4",
            playedMove = "e2e4"
        )

        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(e))
    }

    @Test
    fun calibratedMissBandRecognizesAThrownAwayModerateAdvantage() {
        val e = info(
            bestCp = 140,
            playedCp = 0,
            secondCp = 130,
            bestMove = "d2d4",
            playedMove = "e2e4"
        )

        assertEquals(MoveClass.MISS, MoveClass.classify(e))
    }

    @Test
    fun calibratedMistakeCeilingAvoidsPrematureBlunder() {
        val e = info(
            bestCp = 0,
            playedCp = -270,
            secondCp = -10,
            bestMove = "d2d4",
            playedMove = "e2e4"
        )

        assertEquals(MoveClass.MISTAKE, MoveClass.classify(e))
    }


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
    fun decisiveDropWhenWinningIsBlunderRatherThanMiss() {
        // A Miss is a missed opportunity, not a replacement label for a decisive 80-point collapse.
        val e = info(bestCp = 400, playedCp = -600, secondCp = 380, bestMove = "d2d4", playedMove = "e2e4")
        assertEquals(MoveClass.BLUNDER, MoveClass.classify(e))
    }

    @Test
    fun bigDropFromBalancedIsBlunder() {
        // best 100 (~59%), played -600 (~6%): bestWin < 65 -> BLUNDER.
        val e = info(bestCp = 100, playedCp = -600, secondCp = 90, bestMove = "d2d4", playedMove = "e2e4")
        assertEquals(MoveClass.BLUNDER, MoveClass.classify(e))
    }

    @Test
    fun checkmateDeliveredIsAlwaysBest() {
        // A delivered mate remains BEST even if another mating move was ranked first.
        val e = info(bestMate = 2, playedMate = 0, bestMove = "qg6", playedMove = "d1h5")
        assertEquals(MoveClass.BEST, MoveClass.classify(e))
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
    fun exactBestMoveCrossingSeventyPercentBandIsGreat() {
        val e = info(bestCp = 464, playedCp = 487, secondCp = 218, bestMove = "f3g5", playedMove = "f3g5")

        assertEquals(MoveClass.GREAT, MoveClass.classify(e))
    }

    @Test
    fun subElevenPointGapRemainsBestWhenReferenceGameHasNoGreatMoves() {
        // LMsquared7 13...Bd7: a strong best move, but the second choice is not far enough behind.
        val e = info(bestCp = 331, playedCp = 332, secondCp = 201, bestMove = "g4d7", playedMove = "g4d7")

        assertEquals(MoveClass.BEST, MoveClass.classify(e))
    }

    @Test
    fun uniqueExactBestMoveToleratesSmallIndependentSearchNoise() {
        val e = info(bestCp = 88, playedCp = 79, secondCp = -261, bestMove = "d4e4", playedMove = "d4e4")

        assertEquals(MoveClass.GREAT, MoveClass.classify(e))
    }

    @Test
    fun narrowDrawingEdgeWithoutEnoughWinningChanceRemainsBest() {
        // mcnsena 49.Qxd3: collecting the bishop is Best, not a critical-resource Great.
        val e = info(bestCp = 0, playedCp = 0, secondCp = -289, bestMove = "a3d3", playedMove = "a3d3")
            .copy(fenBefore = "5rk1/5r1p/6pP/8/4P3/Q2b4/6P1/7K w - - 0 49")

        assertEquals(MoveClass.BEST, MoveClass.classify(e))
    }

    @Test
    fun decisiveExactBestMoveWithInferiorAlternativeIsGreat() {
        // no-op 10.d5.
        val e = info(bestCp = 495, playedCp = 502, secondCp = 206, bestMove = "d4d5", playedMove = "d4d5")

        assertEquals(MoveClass.GREAT, MoveClass.classify(e))
    }

    @Test
    fun exactBestCrossingIntoWinningBandToleratesIndependentSearchNoise() {
        // no-op 4.cxd5: the exact best move is the sole meaningful winning continuation.
        val e = info(bestCp = 192, playedCp = 197, secondCp = -4, bestMove = "c4d5", playedMove = "c4d5")
            .copy(fenBefore = "r2qkbnr/pppbpppp/2n5/3p4/Q1PP4/8/PP2PPPP/RNB1KBNR w KQkq - 3 4")

        assertEquals(MoveClass.GREAT, MoveClass.classify(e))
    }

    @Test
    fun bandCrossingNoiseRuleDoesNotPromoteAlreadyDecisiveOrNarrowAlternatives() {
        // mcnsena 39.Rxd3 is already clearly winning; Tadeas-2010 6...Nf6 has no meaningful gap.
        val alreadyDecisive = info(bestCp = 418, playedCp = 415, secondCp = -42, bestMove = "f3d3", playedMove = "f3d3")
            .copy(fenBefore = "4brkr/Q6p/6p1/8/P1p1P3/3q1R2/6PP/4R2K w - - 9 39")
        val narrowBoundary = info(bestCp = 199, playedCp = 190, secondCp = 179, bestMove = "g8f6", playedMove = "g8f6")

        assertEquals(MoveClass.BEST, MoveClass.classify(alreadyDecisive))
        assertEquals(MoveClass.BEST, MoveClass.classify(narrowBoundary))
    }

    @Test
    fun collectingHangingQueenIsBestRatherThanGreat() {
        // no-op 14.Bxc4: c4 contains the opponent queen in the actual pre-move FEN.
        val e = info(bestCp = 545, playedCp = 546, secondCp = 82, bestMove = "f1c4", playedMove = "f1c4")
            .copy(fenBefore = "r3kbnr/pp3ppp/2N5/6B1/2q1P3/8/PP3PPP/RN2KB1R w KQkq - 0 14")

        assertEquals(MoveClass.BEST, MoveClass.classify(e))
    }

    @Test
    fun tacticalPawnCaptureCanStillBeGreat() {
        // ItsTh3Bot 9.Qxh6 captures a pawn, not a hanging queen.
        val e = info(bestCp = 405, playedCp = 456, secondCp = 157, bestMove = "d2h6", playedMove = "d2h6")
            .copy(fenBefore = "r2q1rk1/ppp2p1p/2npb2p/2b1p3/2B1P3/3P1N1P/PPPQ1PP1/RN3RK1 w - - 2 9")

        assertEquals(MoveClass.GREAT, MoveClass.classify(e))
    }

    @Test
    fun nonBestMinorForPawnOfferIsExcellentRatherThanBrilliant() {
        // Chris301172 11.Nxh7: a non-best knight-for-pawn offer, confirmed Excellent.
        val e = info(bestCp = 18, playedCp = 26, secondCp = 12, bestMove = "b2b4", playedMove = "g5h7")
            .copy(fenBefore = "2kr1r2/1pp1qppp/p1np1n2/2b1p1N1/2B1P1b1/P1N3Q1/1PPP1PPP/R1B2RK1 w - - 0 11")

        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(e, materialSacrificed = true))
    }

    @Test
    fun exactBestSacrificeToleratesThreePointIndependentSearchDrop() {
        // RollMat 22.Nc3: exact root best and a confirmed Brilliant material offer.
        val e = info(bestCp = 150, playedCp = 118, secondCp = 82, bestMove = "b1c3", playedMove = "b1c3")
            .copy(fenBefore = "3r2k1/pp2bppp/2n5/8/2PpQ3/P5Pq/1P3P2/RN1R2K1 w - - 4 22")

        assertEquals(MoveClass.BRILLIANT, MoveClass.classify(e, materialSacrificed = true))
    }

    @Test
    fun criticalDrawingResourceCanBeGreatWithSearchNoise() {
        // gaeremj 15...Qa1+: the exact resource remains Great despite a 3.5-point after-search delta.
        val e = info(bestCp = 38, playedCp = 0, secondCp = -249, bestMove = "b2a1", playedMove = "b2a1")
            .copy(fenBefore = "r3k2r/pp1n1pp1/2p1p1bp/6B1/1R1Pp1PN/4P2P/1qPQ1P2/4KB1R b Kkq - 1 15")

        assertEquals(MoveClass.GREAT, MoveClass.classify(e, materialSacrificed = true))
    }

    @Test
    fun narrowAlternativeGapRemainsBest() {
        // nick0586 17...Nc6: confirmed Best, despite being Stockfish's exact rank-1 move.
        val e = info(bestCp = -157, playedCp = -149, secondCp = -197, bestMove = "b8c6", playedMove = "b8c6")
            .copy(fenBefore = "rn3rk1/pp3pp1/4p2p/2p1P2b/3P1q1b/P2B1N1P/1P1NQPP1/R4RK1 b - - 4 17")

        assertEquals(MoveClass.BEST, MoveClass.classify(e))
    }

    @Test
    fun midBandOrdinaryCaptureRemainsBest() {
        // Cincinnatusss 13...Bc3: confirmed Best, not a decisive winning or rescue resource.
        val e = info(bestCp = 228, playedCp = 229, secondCp = 87, bestMove = "d2c3", playedMove = "d2c3")
            .copy(fenBefore = "rn1q1rk1/pp3ppp/2p5/2PpNQ2/3P4/2P5/P2bKPPP/R4B1R b - - 0 13")

        assertEquals(MoveClass.BEST, MoveClass.classify(e))
    }

    @Test
    fun lowBandExactResourceCanBeGreat() {
        // nick0586 5...Bg6: confirmed Great while preserving a difficult position.
        val e = info(bestCp = -107, playedCp = -133, secondCp = -199, bestMove = "f5g6", playedMove = "f5g6")
            .copy(fenBefore = "rn1qkbnr/pp2pppp/2p5/5b2/2pPPB2/2N5/PP3PPP/R2QKBNR b KQkq e3 0 5")

        assertEquals(MoveClass.GREAT, MoveClass.classify(e))
    }

    @Test
    fun tacticalPawnCaptureNearDrawnBandCanBeGreat() {
        // splasher3000 8.Bxc7: confirmed Great even though depth-16 alternatives are close.
        val e = info(bestCp = 140, playedCp = 127, secondCp = 126, bestMove = "f4c7", playedMove = "f4c7")
            .copy(fenBefore = "r3k2r/pppbqppp/2n1pn2/3p4/1b1P1B2/2NBP3/PPP1NPPP/R2Q1RK1 w kq - 6 8")

        assertEquals(MoveClass.GREAT, MoveClass.classify(e))
    }

    @Test
    fun forcedMateCaptureRemainsBest() {
        // scubadilla 20...Qxg3+: confirmed Best while continuing an existing forced mate.
        val e = info(
            bestMate = 2, playedMate = 1, secondCp = -347,
            bestMove = "d6g3", playedMove = "d6g3"
        ).copy(fenBefore = "r7/pp1k1pp1/2pqp3/3p4/1P1P4/P3P1Pp/2P5/R1Q2RK1 b - - 0 20")

        assertEquals(MoveClass.BEST, MoveClass.classify(e))
    }

    @Test
    fun confirmedExactBestCapturesRemainBest() {
        val bestMoves = listOf(
            // RollMat 10.Qxd5 and 19.Bxd8.
            info(bestCp = 66, playedCp = 67, secondCp = -355, bestMove = "f3d5", playedMove = "f3d5")
                .copy(fenBefore = "rn1qk2r/pp3ppp/3b4/3np3/8/2P2Q2/PP1P1PPP/RNB1K2R w KQkq - 0 10"),
            info(bestCp = 0, playedCp = 0, secondCp = -413, bestMove = "g5d8", playedMove = "g5d8")
                .copy(fenBefore = "3r1rk1/pp3ppp/2nb4/3Q2B1/2Pp4/P5P1/1P3P1q/RN1R1K2 w - - 1 19"),
            // LMsquared7 12...Qxg5 and splasher3000 10...Qxd6.
            info(bestCp = 142, playedCp = 143, secondCp = -353, bestMove = "g6g5", playedMove = "g6g5")
                .copy(fenBefore = "rn2k1nr/p4ppp/1pp3q1/b3N1B1/Q3P1b1/2PP2P1/P3NPBP/R3K2R b KQkq - 0 12"),
            info(bestCp = -144, playedCp = -148, secondCp = -632, bestMove = "e7d6", playedMove = "e7d6")
                .copy(fenBefore = "r4rk1/pp1bqppp/2nBpn2/3p4/3P4/2NBP1N1/PPP2PPP/R2Q1RK1 b - - 0 10"),
            // Tadeas-2010 14...Qxa1 and Cincinnatusss 18.Nxg6.
            info(bestCp = 245, playedCp = 257, secondCp = 129, bestMove = "b2a1", playedMove = "b2a1")
                .copy(fenBefore = "r3kb1r/pp1bpppp/8/2pP4/2P5/5N2/PqnBKPPP/RQ3B1R b kq - 3 14"),
            info(bestCp = 0, playedCp = 0, secondCp = -363, bestMove = "e5g6", playedMove = "e5g6")
                .copy(fenBefore = "rn2r1k1/pp2q2p/2p2pp1/2PpNQ2/3P1P2/2bB1K2/P5PP/3R3R w - - 0 18"),
            // Cincinnatusss 19.Qxg6+ and RollMat 16...Qxh2+.
            info(bestCp = 0, playedCp = 0, secondCp = -510, bestMove = "f5g6", playedMove = "f5g6")
                .copy(fenBefore = "rn2r1k1/pp2q3/2p2pp1/2Pp1Q2/3P1P2/2bB1K2/P5PP/3R3R w - - 0 19"),
            info(bestCp = 528, playedCp = 491, secondCp = 83, bestMove = "e5h2", playedMove = "e5h2")
                .copy(fenBefore = "r4rk1/pp3ppp/2nb4/3Qq1B1/2Pp4/P7/1P3PPP/RN1R2K1 b - - 4 16"),
            // mcnsena 31...Bxd5.
            info(bestCp = 250, playedCp = 253, secondCp = 80, bestMove = "c6d5", playedMove = "c6d5")
                .copy(fenBefore = "5rkr/7p/Q1b3p1/3P4/2p5/P3P3/5qPP/R3R2K b - - 2 31")
        )

        bestMoves.forEach { assertEquals(MoveClass.BEST, MoveClass.classify(it)) }
    }

    @Test
    fun forcingPawnAdvancesCanBeGreat() {
        val greatMoves = listOf(
            // Cincinnatusss 15...f6 attacks the knight on e5.
            info(bestCp = 421, playedCp = 433, secondCp = 366, bestMove = "f7f6", playedMove = "f7f6")
                .copy(fenBefore = "rn3rk1/pp2qppp/2p5/2PpNQ2/3P1P2/2b5/P3K1PP/3R1B1R b - f3 0 15"),
            // Cincinnatusss 17...g6 attacks the queen on f5.
            info(bestCp = 0, playedCp = 0, secondCp = -115, bestMove = "g7g6", playedMove = "g7g6")
                .copy(fenBefore = "rn2r1k1/pp2q1pp/2p2p2/2PpNQ2/3P1P2/2bB1K2/P5PP/3R3R b - - 3 17")
        )

        greatMoves.forEach { assertEquals(MoveClass.GREAT, MoveClass.classify(it)) }
    }

    @Test
    fun checkingForkMoveCanBeGreat() {
        // RollMat 25.Qd5+ checks the king and simultaneously attacks the knight on c6.
        val e = info(bestCp = 516, playedCp = 404, secondCp = 415, bestMove = "e4d5", playedMove = "e4d5")
            .copy(fenBefore = "4r3/ppN1bkpp/2n2p2/8/2PpQ3/P5Pq/1P3P2/R2R2K1 w - - 4 25")

        assertEquals(MoveClass.GREAT, MoveClass.classify(e))
    }

    @Test
    fun equivalentForcingRookMoveCanBeGreat() {
        // mcnsena 47...R6f7 attacks the queen while preserving the same drawn evaluation as
        // Stockfish's root-ranked rook continuation.
        val e = info(bestCp = 0, playedCp = 0, secondCp = 0, bestMove = "f6f1", playedMove = "f6f7")
            .copy(fenBefore = "5rk1/4Q2p/5rpP/8/4P3/3R4/2b3P1/7K b - - 2 47")

        assertEquals(MoveClass.GREAT, MoveClass.classify(e))
    }

    @Test
    fun confirmedOrdinaryExactBestMovesAreNotGreat() {
        val ordinaryBestMoves = listOf(
            // Chris301172 15...Qxf5: collecting material in a won position.
            info(bestCp = 618, playedCp = 622, secondCp = 350, bestMove = "e6f5", playedMove = "e6f5")
                .copy(fenBefore = "2kr1r2/1pp3Q1/p1npqn2/2b1pP2/8/P1N5/1PPP1PPP/R1B2RK1 b - - 0 15"),
            // nick0586 6.Bxc4: an ordinary bishop capture, not a tactical Great resource.
            info(bestCp = 133, playedCp = 116, secondCp = 103, bestMove = "f1c4", playedMove = "f1c4")
                .copy(fenBefore = "rn1qkbnr/pp2pppp/2p3b1/8/2pPPB2/2N5/PP3PPP/R2QKBNR w KQkq - 1 6"),
            // no-op 11.dxc6: taking a minor piece is Best rather than Great.
            info(bestCp = 510, playedCp = 521, secondCp = 269, bestMove = "d5c6", playedMove = "d5c6")
                .copy(fenBefore = "r3kbnr/pp1b1ppp/2n3q1/3Pp1B1/2Q1P3/5N2/PP3PPP/RN2KB1R w KQkq - 1 11"),
            // elroy416 19...Nd6: a low-band knight move is not promoted by band crossing alone.
            info(bestCp = -135, playedCp = -85, secondCp = -232, bestMove = "e4d6", playedMove = "e4d6")
                .copy(fenBefore = "r4rk1/ppp1Qppp/8/3P1b2/2Bpn3/P7/1PP3PP/2K1R3 b - - 5 19")
        )

        ordinaryBestMoves.forEach { assertEquals(MoveClass.BEST, MoveClass.classify(it)) }
    }

    @Test
    fun mateContinuationCaptureWithCpRootScoreRemainsBest() {
        // Cincinnatusss 24.Qxg7: the independent after-search sees mate, while the
        // root search still reports a numeric score. It is Best, not Great.
        val e = info(
            bestCp = 893, playedMate = 11, secondCp = -326,
            bestMove = "h6g7", playedMove = "h6g7"
        ).copy(fenBefore = "rn2k3/pp4q1/2p2p1Q/2Pp4/3P1P2/3B1K2/P5PP/8 w - - 0 24")

        assertEquals(MoveClass.BEST, MoveClass.classify(e))
    }

    @Test
    fun shorteningAlreadyForcedMateIsInaccuracy() {
        val e = info(bestMate = -9, playedMate = -1, secondMate = -6, bestMove = "b2f2", playedMove = "f8g8")

        assertEquals(MoveClass.INACCURACY, MoveClass.classify(e))
    }

    @Test
    fun newlyForcedMateFromMeaningfulChancesIsBlunder() {
        // scubadilla 20.fxg3 and Bei-64 22...Rf8 sit just above this calibrated band.
        val e = info(bestCp = -406, playedMate = -10, bestMove = "c8b8", playedMove = "h8f8")

        assertEquals(MoveClass.BLUNDER, MoveClass.classify(e))
    }

    @Test
    fun newlyForcedMateWhenAlreadyNearlyLostIsMistake() {
        // ItsTh3Bot 10...Nd4 and Chris301172 19.Kh1 remain mistakes, not blunders.
        val e = info(bestCp = -487, playedMate = -1, bestMove = "f7f6", playedMove = "c6d4")

        assertEquals(MoveClass.MISTAKE, MoveClass.classify(e))
    }

    @Test
    fun newlyForcedMateBelowFivePercentDoesNotOverrideDropBand() {
        val e = info(bestCp = -813, playedMate = -1, bestMove = "g6f5", playedMove = "g6g5")

        assertEquals(MoveClass.GOOD, MoveClass.classify(e))
    }

    @Test
    fun substantialMissedConversionWhileStillWinningIsMiss() {
        // Bei-64 14.e5; the deeper depth-24 audit confirms a stable +6.79 to +4.71 loss.
        val e = info(bestCp = 653, playedCp = 493, secondCp = 649, bestMove = "a2a3", playedMove = "e4e5")

        assertEquals(MoveClass.MISS, MoveClass.classify(e))
    }

    @Test
    fun modestLossInsideLargeAdvantageIsGood() {
        // Bei-64 16.Nc3.
        val e = info(bestCp = 469, playedCp = 365, secondCp = 463, bestMove = "a2a3", playedMove = "b1c3")

        assertEquals(MoveClass.GOOD, MoveClass.classify(e))
    }

    @Test
    fun modestLossNearEqualityIsGood() {
        // no-op 3.Qa4: a 55 cp loss from an almost equal position is still Good.
        val e = info(bestCp = 59, playedCp = 4, secondCp = 53, bestMove = "g1f3", playedMove = "d1a4")

        assertEquals(MoveClass.GOOD, MoveClass.classify(e, cpLoss = 55))
    }

    @Test
    fun largerLossOutsideEqualityMarginRemainsInaccuracy() {
        // no-op 7.e4: the larger 98 cp concession is outside the narrow balanced-position rule.
        val e = info(bestCp = 117, playedCp = 19, secondCp = 88, bestMove = "g1f3", playedMove = "e2e4")

        assertEquals(MoveClass.INACCURACY, MoveClass.classify(e, cpLoss = 98))
    }

    @Test
    fun nearbyWinningInaccuracyExamplesDoNotBecomeMisses() {
        val inaccuracies = listOf(
            // Coves24 14.Qxa8, bhenejme_alanwalker_fan 22...Qh3,
            // gaeremj 21...Be4+, Tadeas-2010 13...Nc2+.
            info(bestCp = 551, playedCp = 415, secondCp = 545, bestMove = "f3d3", playedMove = "f3a8"),
            info(bestCp = 606, playedCp = 448, secondCp = 519, bestMove = "h5f4", playedMove = "h5h3"),
            info(bestCp = 432, playedCp = 294, secondCp = 342, bestMove = "c5e4", playedMove = "a6e4"),
            info(bestCp = 348, playedCp = 220, secondCp = 248, bestMove = "b2a1", playedMove = "d4c2")
        )

        inaccuracies.forEach { assertEquals(MoveClass.INACCURACY, MoveClass.classify(it)) }
    }

    @Test
    fun calibratedExcellentBandCoversConfirmedNoOpMoves() {
        val excellentMoves = listOf(
            info(bestCp = 533, playedCp = 492, secondCp = 493, bestMove = "b1c3", playedMove = "f3e5"),
            info(bestCp = 522, playedCp = 483, secondCp = 522, bestMove = "c3a4", playedMove = "d1d3"),
            info(bestCp = 569, playedCp = 528, secondCp = 520, bestMove = "c3e4", playedMove = "g2g4")
        )

        excellentMoves.forEach { assertEquals(MoveClass.EXCELLENT, MoveClass.classify(it)) }
    }

    @Test
    fun equivalentBestEqualTradeInWonPositionIsExcellent() {
        // no-op 13.Nxc6: depth 23 keeps 13.Nxc6 and 13.Be6 exactly tied at +5.39.
        val e = info(bestCp = 534, playedCp = 527, secondCp = 522, bestMove = "e5c6", playedMove = "e5c6")
            .copy(fenBefore = "r3kbnr/pp3ppp/2b1q3/4N1B1/2Q1P3/8/PP3PPP/RN2KB1R w KQkq - 1 13")

        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(e))
    }

    @Test
    fun equivalentBestMaterialWinningCaptureRemainsBest() {
        // nick0586 35...Bxg5 wins a pawn with a bishop; it is not an equal trade.
        val e = info(bestCp = 711, playedCp = 701, secondCp = 695, bestMove = "d2g5", playedMove = "d2g5")
            .copy(fenBefore = "6k1/5pp1/4p3/4P1Pp/2RN4/r4PKP/1r1b4/8 b - - 4 35")

        assertEquals(MoveClass.BEST, MoveClass.classify(e))
    }

    @Test
    fun nearBestCastlingContinuationInWonPositionIsExcellent() {
        // no-op 16.O-O: the hash-isolated root search prefers queenside castling,
        // while kingside castling remains a close Excellent continuation.
        val e = info(bestCp = 557, playedCp = 536, secondCp = 539, bestMove = "e1c1", playedMove = "e1g1")
            .copy(fenBefore = "r3kbnr/pp3ppp/2b1q3/4N1B1/2Q1P3/2N5/PP3PPP/R3KB1R w KQkq - 3 16")

        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(e))
    }

    @Test
    fun confirmedNoOpExactBestMovesRemainBest() {
        val bestMoves = listOf(
            info(bestCp = 144, playedCp = 145, secondCp = 155, bestMove = "c1g5", playedMove = "c1g5"),
            info(bestCp = 515, playedCp = 519, secondCp = 511, bestMove = "a1d1", playedMove = "a1d1"),
            info(bestCp = 562, playedCp = 557, secondCp = 531, bestMove = "d3d8", playedMove = "d3d8"),
            info(bestCp = 565, playedCp = 573, secondCp = 540, bestMove = "d1d8", playedMove = "d1d8")
        )

        bestMoves.forEach { assertEquals(MoveClass.BEST, MoveClass.classify(it)) }
    }

    @Test
    fun hashCleanNoOpGoodMovesRemainGood() {
        val goodMoves = listOf(
            info(bestCp = 67, playedCp = 2, secondCp = 49, bestMove = "g1f3", playedMove = "d1a4"),
            info(bestCp = 26, playedCp = -31, secondCp = -2, bestMove = "d4e5", playedMove = "g1f3")
        )

        goodMoves.forEach { assertEquals(MoveClass.GOOD, MoveClass.classify(it, cpLoss = it.bestCp!! - it.playedCp!!)) }
    }

    @Test
    fun hashCleanNoOpEquivalentMovesMatchBestAndExcellentLabels() {
        val bestMoves = listOf(
            info(bestCp = 143, playedCp = 153, secondCp = 143, bestMove = "b1c3", playedMove = "c1g5")
                .copy(fenBefore = "r3kbnr/pp1b1ppp/2n2q2/4p3/2QPP3/5N2/PP3PPP/RNB1KB1R w KQkq - 2 9"),
            info(bestCp = 518, playedCp = 504, secondCp = 512, bestMove = "a1d1", playedMove = "a1d1")
                .copy(fenBefore = "r3k2r/p4p1p/2p2p2/8/1bB1P3/2N5/PP3PPP/R4RK1 w kq - 0 18"),
            info(bestCp = 518, playedCp = 515, secondCp = 510, bestMove = "d3d1", playedMove = "f1d1")
                .copy(fenBefore = "3r1rk1/p4p1p/2p2p2/8/1bB1P3/2NR4/PP3PPP/5RK1 w - - 4 20"),
            info(bestCp = 541, playedCp = 540, secondCp = 539, bestMove = "g1f1", playedMove = "d1d8")
                .copy(fenBefore = "3r2k1/p4p1p/2p2p2/b7/2B1P3/2N5/PP3PPP/3R2K1 w - - 0 22"),
            info(bestCp = 531, playedCp = 512, secondCp = 509, bestMove = "g2g3", playedMove = "e4f5")
                .copy(fenBefore = "3b2k1/p4p1p/2p5/5p2/2B1P2P/2N5/PP3PP1/6K1 w - - 0 24")
        )
        val excellentMoves = listOf(
            info(bestCp = 556, playedCp = 510, secondCp = 547, bestMove = "e4e5", playedMove = "g5f6"),
            info(bestCp = 550, playedCp = 511, secondCp = 538, bestMove = "g2g3", playedMove = "h2h4"),
            info(bestCp = 519, playedCp = 523, secondCp = 519, bestMove = "c4e2", playedMove = "g1g2")
                .copy(fenBefore = "6k1/p4p1p/2p2b2/5P2/2B3P1/2N5/PP3P2/6K1 w - - 1 26"),
            info(bestCp = 539, playedCp = 533, secondCp = 534, bestMove = "c4d3", playedMove = "f2f4")
        )

        bestMoves.forEach { assertEquals(MoveClass.BEST, MoveClass.classify(it)) }
        excellentMoves.forEach { assertEquals(MoveClass.EXCELLENT, MoveClass.classify(it)) }
    }

    @Test
    fun itsTh3BotPositiveMoveBoundariesMatchReference() {
        val excellentMoves = listOf(
            info(bestCp = 116, playedCp = 111, secondCp = 109, bestMove = "g1f3", playedMove = "g1f3")
                .copy(fenBefore = "rnbqk2r/pppp1ppp/7n/2b1p3/2B1P3/7P/PPPP1PP1/RNBQK1NR w KQkq - 1 4"),
            info(bestCp = 75, playedCp = 44, secondCp = 66, bestMove = "b1c3", playedMove = "d1d2")
                .copy(fenBefore = "rn1q1rk1/ppp2p1p/3pb2p/2b1p3/2B1P3/3P1N1P/PPP2PP1/RN1Q1RK1 w - - 0 8")
        )
        val good = info(bestCp = 134, playedCp = 107, secondCp = 105, bestMove = "d2d4", playedMove = "d2d3")

        excellentMoves.forEach { assertEquals(MoveClass.EXCELLENT, MoveClass.classify(it)) }
        assertEquals(MoveClass.GOOD, MoveClass.classify(good))
    }

    @Test
    fun scubadillaConfirmedPositiveMoveBoundariesMatchReference() {
        val bestMoves = listOf(
            // 4.Bf4: Stockfish's first two root choices differ by only 1 cp; the played move
            // loses 3 cp in the independent search and remains an equivalent Best move.
            info(bestCp = 9, playedCp = 6, secondCp = 8, bestMove = "f3h4", playedMove = "c1f4")
                .copy(fenBefore = "rn1qkbnr/pp2pppp/2p5/3p1b2/3P4/2N2N2/PPP1PPPP/R1BQKB1R w KQkq - 2 4"),
            // 13.g3: an exact, quiet pawn move remains Best despite a close root alternative.
            info(bestCp = -143, playedCp = -149, secondCp = -144, bestMove = "g2g3", playedMove = "g2g3")
                .copy(fenBefore = "rn2k2r/pp3pp1/2pqpn2/3pN3/3P4/P1N1P2p/1PP2PP1/R2Q1RK1 w kq - 0 13")
        )
        val excellentMoves = listOf(
            // 6.Bxd6 is an equal minor-piece trade among effectively tied root choices.
            info(bestCp = 17, playedCp = 14, secondCp = 7, bestMove = "f4d6", playedMove = "f4d6")
                .copy(fenBefore = "rn1qk1nr/pp3ppp/2pbp3/3p1b2/3P1B2/2N1PN2/PPP2PPP/R2QKB1R w KQkq - 1 6"),
            info(bestCp = 20, playedCp = 3, secondCp = 16, bestMove = "d1d3", playedMove = "h2h3")
                .copy(fenBefore = "rn2k2r/pp3pp1/2pqpnp1/3p4/3P4/2N1PN2/PPP2PPP/R2Q1RK1 w kq - 0 10")
        )
        val goodMoves = listOf(
            info(bestCp = 37, playedCp = 17, secondCp = 22, bestMove = "d3g6", playedMove = "e1g1")
                .copy(fenBefore = "rn2k1nr/pp3ppp/2pqp1b1/3p4/3P4/2NBPN2/PPP2PPP/R2QK2R w KQkq - 2 8"),
            info(bestCp = -160, playedCp = -175, secondCp = -165, bestMove = "d1e2", playedMove = "c3e2")
                .copy(fenBefore = "r7/pp1k1pp1/2pqpn2/3p3r/1P1P4/P1N1P1Pp/2P2P2/R2Q1RK1 w - - 1 16")
        )
        val missedRecapture = info(
            bestCp = -98, playedCp = -397, secondCp = -400,
            bestMove = "f2g3", playedMove = "e2g3"
        ).copy(fenBefore = "r7/pp1k1pp1/2pqp3/3p2r1/1P1P4/P3P1np/2P1NP2/R1Q2RK1 w - - 0 19")

        bestMoves.forEach { assertEquals(MoveClass.BEST, MoveClass.classify(it)) }
        excellentMoves.forEach { assertEquals(MoveClass.EXCELLENT, MoveClass.classify(it)) }
        goodMoves.forEach { assertEquals(MoveClass.GOOD, MoveClass.classify(it)) }
        assertEquals(MoveClass.MISS, MoveClass.classify(missedRecapture))
    }

    @Test
    fun scubadillaConfirmedEqualTradeIsExcellent() {
        // 9.Bxg6: an equal bishop trade, +35 to +14 cp, with a +18 cp alternative.
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(balancedBishopTrade()))
    }

    private fun balancedBishopTrade() = info(
        bestCp = 35, playedCp = 14, secondCp = 18, bestMove = "d3g6", playedMove = "d3g6"
    ).copy(fenBefore = "rn2k2r/pp3ppp/2pqpnb1/3p4/3P4/2NBPN2/PPP2PPP/R2Q1RK1 w kq - 4 9")

    @Test
    fun balancedTradeRulePreservesConfirmedBestMovesAndForcedReply() {
        val bestMoves = listOf(
            // scubadilla 5.e3 and 7.Bd3 are confirmed Best, without an equal capture.
            info(bestCp = 14, playedCp = 8, secondCp = 2, bestMove = "e2e3", playedMove = "e2e3")
                .copy(fenBefore = "rn1qkbnr/pp3ppp/2p1p3/3p1b2/3P1B2/2N2N2/PPP1PPPP/R2QKB1R w KQkq - 0 5"),
            info(bestCp = 22, playedCp = 13, secondCp = 11, bestMove = "f1d3", playedMove = "f1d3")
                .copy(fenBefore = "rn2k1nr/pp3ppp/2pqp3/3p1b2/3P4/2N1PN2/PPP2PPP/R2QKB1R w KQkq - 0 7"),
            // 21.Kh1 is Forced in the reference; Best remains the app's mapping, not a
            // confirmed external Best label. Mate evaluations cannot enter the cp trade rule.
            info(bestMate = -1, playedMate = -1, bestMove = "g1h1", playedMove = "g1h1")
                .copy(fenBefore = "r7/pp1k1pp1/2p1p3/3p4/1P1P4/P3P1qp/2P5/R1Q2RK1 w - - 0 21"),
            // Same engine scores but winning a pawn is not an equal trade.
            balancedBishopTrade().copy(fenBefore = balancedBishopTrade().fenBefore.replace("pnb1", "pnp1")),
            // An equal trade with a significantly worse alternative stays uniquely Best.
            balancedBishopTrade().copy(secondCp = -100)
        )
        bestMoves.forEach { assertEquals(MoveClass.BEST, MoveClass.classify(it)) }
    }

    @Test
    fun balancedTradeExcellentBudgetsIncludeBoundaryAndExcludeOutside() {
        val e = balancedBishopTrade()
        val drop = MoveClass.cpToWinPct(35) - MoveClass.cpToWinPct(14)
        val gap = MoveClass.cpToWinPct(35) - MoveClass.cpToWinPct(18)
        val defaults = MoveClassificationThresholds()
        val limits = listOf(
            defaults.copy(positionalExcellentMaxAbsBestCp = 35) to
                defaults.copy(positionalExcellentMaxAbsBestCp = 34),
            defaults.copy(positionalExcellentMaxCpLoss = 21) to
                defaults.copy(positionalExcellentMaxCpLoss = 20),
            defaults.copy(positionalExcellentMaxWinPctDrop = drop) to
                defaults.copy(positionalExcellentMaxWinPctDrop = drop - 0.0001),
            defaults.copy(excellentWinPctDrop = drop) to
                defaults.copy(excellentWinPctDrop = drop + 0.0001)
        )
        limits.forEach { (boundary, outside) ->
            assertEquals(MoveClass.EXCELLENT, MoveClass.classify(e, thresholds = boundary))
            assertEquals(MoveClass.BEST, MoveClass.classify(e, thresholds = outside))
        }
        // Isolate the alternative-gap boundary using the configured drop floor.
        val tiedTrade = e.copy(playedCp = 19)
        val rootLimits = defaults.copy(excellentWinPctDrop = 1.0,
            positionalExcellentMaxWinPctDrop = gap)
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(tiedTrade, thresholds = rootLimits))
        assertEquals(MoveClass.BEST, MoveClass.classify(tiedTrade,
            thresholds = rootLimits.copy(positionalExcellentMaxWinPctDrop = gap - 0.0001)))
    }

    @Test
    fun balancedTradeDoesNotOverrideSoundSacrifice() {
        assertEquals(MoveClass.BRILLIANT,
            MoveClass.classify(balancedBishopTrade(), materialSacrificed = true))
    }

    @Test
    fun castlingThatMissesLargerOpportunityCanBeInaccuracy() {
        // ItsTh3Bot 5.O-O: confirmed Inaccuracy, unlike no-op 16.O-O with only 21 cp loss.
        val e = info(bestCp = 147, playedCp = 101, secondCp = 137, bestMove = "d2d4", playedMove = "e1g1")
            .copy(fenBefore = "rnbqk2r/ppp2ppp/3p3n/2b1p3/2B1P3/5N1P/PPPP1PP1/RNBQK2R w KQkq - 0 5")

        assertEquals(MoveClass.INACCURACY, MoveClass.classify(e))
    }

    @Test
    fun quietPieceMoveWithCloseRootAlternativesCanBeGood() {
        // ItsTh3Bot 7.Bxh6: confirmed Good; the bishop trades for the knight on h6,
        // while the two root alternatives are only 7 cp apart.
        val e = info(bestCp = 122, playedCp = 61, secondCp = 115, bestMove = "c1g5", playedMove = "c1h6")
            .copy(fenBefore = "rn1q1rk1/ppp2ppp/3pb2n/2b1p3/2B1P3/3P1N1P/PPP2PP1/RNBQ1RK1 w - - 1 7")

        assertEquals(MoveClass.GOOD, MoveClass.classify(e))
    }

    @Test
    fun tenPointExpectedScoreLossRemainsInaccuracy() {
        // no-op 5.Qc4 lies just across the former 10.0 boundary, but remains in the
        // same calibrated error band as the neighboring corpus positions.
        val e = info(bestCp = 244, playedCp = 121, secondCp = 166, bestMove = "d1a4", playedMove = "d1c4")

        assertEquals(MoveClass.INACCURACY, MoveClass.classify(e, cpLoss = 123))
    }

    @Test
    fun borderlineExpectedScoreLossWithLargerCpLossRemainsMistake() {
        // nick0586 17.Ned2 has a similar expected-score loss but exceeds the narrow
        // centipawn margin, so the Qc4 boundary must not reclassify it.
        val e = info(bestCp = 266, playedCp = 138, secondCp = 250, bestMove = "e3d5", playedMove = "f3d2")

        assertEquals(MoveClass.MISTAKE, MoveClass.classify(e, cpLoss = 128))
    }

    @Test
    fun borderlineExpectedScoreLossWithModerateCpLossCanBeInaccuracy() {
        // ItsTh3Bot 2...Nh6: confirmed Inaccuracy at 120 cp and 10.89 Win-% loss.
        val e = info(bestCp = 5, playedCp = -115, secondCp = -18, bestMove = "g8f6", playedMove = "g8h6")
            .copy(fenBefore = "rnbqkbnr/pppp1ppp/8/4p3/2B1P3/8/PPPP1PPP/RNBQK1NR b KQkq - 1 2")

        assertEquals(MoveClass.INACCURACY, MoveClass.classify(e, cpLoss = 120))
    }

    @Test
    fun scubadillaPawnPushIsBorderlineInaccuracy() {
        val e = info(bestCp = -3, playedCp = -125, secondCp = -11,
            bestMove = "e8g8", playedMove = "g6g5")
            .copy(fenBefore = "rn2k2r/pp3pp1/2pqpnp1/3p4/3P4/2N1PN1P/PPP2PP1/R2Q1RK1 b kq - 0 10")
        assertEquals(MoveClass.INACCURACY, MoveClass.classify(e))
        // Wider expected-score tolerance applies only to moderate raw losses.
        assertEquals(MoveClass.MISTAKE, MoveClass.classify(e.copy(playedCp = -129)))
    }

    @Test
    fun borderlineInaccuracyLimitsHaveExplicitInclusiveCpAndExclusiveDropBounds() {
        val e = info(bestCp = -3, playedCp = -125, secondCp = -11,
            bestMove = "e8g8", playedMove = "g6g5")
        val drop = MoveClass.cpToWinPct(-3) - MoveClass.cpToWinPct(-125)
        val defaults = MoveClassificationThresholds()
        assertEquals(MoveClass.INACCURACY, MoveClass.classify(e,
            thresholds = defaults.copy(borderlineInaccuracyMaxCpLoss = 122)))
        assertEquals(MoveClass.MISTAKE, MoveClass.classify(e,
            thresholds = defaults.copy(borderlineInaccuracyMaxCpLoss = 121)))
        assertEquals(MoveClass.MISTAKE, MoveClass.classify(e,
            thresholds = defaults.copy(borderlineInaccuracyWinPctDrop = drop - 0.0001)))
        assertEquals(MoveClass.MISTAKE, MoveClass.classify(e,
            thresholds = defaults.copy(borderlineInaccuracyWinPctDrop = drop)))
        assertEquals(MoveClass.INACCURACY, MoveClass.classify(e,
            thresholds = defaults.copy(borderlineInaccuracyWinPctDrop = drop + 0.0001)))
    }

    @Test
    fun boundedDevelopmentMatchesConfirmedBestReferences() {
        val bestMoves = listOf(
            exactBalancedDevelopment(),
            defensivePawnDevelopment(),
            info(bestCp = -8, playedCp = -17, secondCp = -13, bestMove = "g8f6", playedMove = "f8d6")
                .copy(fenBefore = "rn1qkbnr/pp3ppp/2p1p3/3p1b2/3P1B2/2N1PN2/PPP2PPP/R2QKB1R b KQkq - 0 5"),
            info(bestCp = -75, playedCp = -77, secondCp = -84, bestMove = "g5f4", playedMove = "e2e3")
                .copy(fenBefore = "r2qkb1r/pp1n1ppp/2p1pnb1/3p2B1/3P2P1/P1N2N1P/1PP1PP2/R2QKB1R w KQkq - 2 8"),
            equivalentMinorDevelopment()
        )
        bestMoves.forEach { assertEquals(MoveClass.BEST, MoveClass.classify(it)) }
    }

    private fun exactBalancedDevelopment() = info(
        bestCp = 5, playedCp = -9, secondCp = 3, bestMove = "c8f5", playedMove = "c8f5"
    ).copy(fenBefore = "rnbqkbnr/pp2pppp/2p5/3p4/3P4/2N2N2/PPP1PPPP/R1BQKB1R b KQkq - 1 3")

    private fun defensivePawnDevelopment() = info(
        bestCp = -6, playedCp = -14, secondCp = -12, bestMove = "g8f6", playedMove = "e7e6"
    ).copy(fenBefore = "rn1qkbnr/pp2pppp/2p5/3p1b2/3P1B2/2N2N2/PPP1PPPP/R2QKB1R b KQkq - 3 4")

    private fun equivalentMinorDevelopment() = info(
        bestCp = 16, playedCp = 9, secondCp = 14, bestMove = "g1f3", playedMove = "b1c3"
    ).copy(fenBefore = "rn1qkbnr/pp3ppp/2p5/2Pp1b2/3P4/8/PP3PPP/RNBQKBNR w KQkq - 1 6")

    @Test
    fun confirmedExcellentDevelopmentCounterexamplesRemainExcellent() {
        val excellentMoves = listOf(
            // nick0586 4.Nc3: exact root tie, but outside the near-equality budget.
            info(bestCp = 60, playedCp = 51, secondCp = 58, bestMove = "b1c3", playedMove = "b1c3")
                .copy(fenBefore = "rn1qkbnr/pp2pppp/2p5/3p1b2/2PP1B2/8/PP2PPPP/RN1QKBNR w KQkq - 0 4"),
            // elroy416 5.Nbc3: the same b1 knight could instead develop to d2.
            info(bestCp = -9, playedCp = -15, secondCp = -16, bestMove = "b1d2", playedMove = "b1c3")
                .copy(fenBefore = "r1bqkb1r/ppp2ppp/2n1pn2/3p4/3P1B2/4P3/PPP1NPPP/RN1QKB1R w KQkq d6 0 5"),
            // splasher3000 3.e3: a quiet pawn alternative while already slightly ahead.
            info(bestCp = 45, playedCp = 39, secondCp = 39, bestMove = "g1f3", playedMove = "e2e3")
                .copy(fenBefore = "r1bqkb1r/pppppppp/2n2n2/8/3P1B2/8/PPP1PPPP/RN1QKBNR w KQkq - 3 3"),
            // splasher3000 6...Bd7: root alternative moves an already developed bishop.
            info(bestCp = -24, playedCp = -34, secondCp = -28, bestMove = "b4d6", playedMove = "c8d7")
                .copy(fenBefore = "r1bqk2r/ppp2ppp/2n1pn2/3p4/1b1P1B2/2NBP3/PPP1NPPP/R2QK2R b KQkq - 3 6")
        )
        excellentMoves.forEach { assertEquals(MoveClass.EXCELLENT, MoveClass.classify(it)) }
    }

    @Test
    fun exactDevelopmentEqualityAndRootTieHaveBoundaries() {
        val e = exactBalancedDevelopment()
        for (cp in listOf(49, 50, 51)) {
            assertEquals(if (cp <= 50) MoveClass.BEST else MoveClass.EXCELLENT,
                MoveClass.classify(e.copy(bestCp = cp, secondCp = cp - 2, playedCp = cp - 14)))
        }
        assertEquals(MoveClass.BEST, MoveClass.classify(e.copy(secondCp = 4)))
        assertEquals(MoveClass.BEST, MoveClass.classify(e.copy(secondCp = 3)))
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(e.copy(secondCp = 2)))
        val drop = MoveClass.cpToWinPct(5) - MoveClass.cpToWinPct(-9)
        val defaults = MoveClassificationThresholds()
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(e,
            thresholds = defaults.copy(excellentWinPctDrop = drop - 0.0001)))
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(e,
            thresholds = defaults.copy(excellentWinPctDrop = drop)))
        assertEquals(MoveClass.BEST, MoveClass.classify(e,
            thresholds = defaults.copy(excellentWinPctDrop = drop + 0.0001)))
    }

    @Test
    fun developmentAlternativeLossAndRootGapBudgetsAreInclusive() {
        val e = defensivePawnDevelopment()
        val defaults = MoveClassificationThresholds()
        assertEquals(MoveClass.BEST, MoveClass.classify(e,
            thresholds = defaults.copy(equivalentBestMaxCpGap = 8)))
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(e,
            thresholds = defaults.copy(equivalentBestMaxCpGap = 7)))
        val smallerLoss = e.copy(playedCp = -8)
        assertEquals(MoveClass.BEST, MoveClass.classify(smallerLoss,
            thresholds = defaults.copy(equivalentBestMaxCpGap = 6)))
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(smallerLoss,
            thresholds = defaults.copy(equivalentBestMaxCpGap = 5)))
        val drop = MoveClass.cpToWinPct(-6) - MoveClass.cpToWinPct(-14)
        assertEquals(MoveClass.BEST, MoveClass.classify(e,
            thresholds = defaults.copy(exactBestSearchNoiseWinPctDrop = drop)))
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(e,
            thresholds = defaults.copy(exactBestSearchNoiseWinPctDrop = drop - 0.0001)))
    }

    @Test
    fun capturesAndAlreadyDevelopedAlternativesAreNotEquivalentHomeDevelopment() {
        val e = equivalentMinorDevelopment()
        val capture = e.copy(fenBefore = e.fenBefore.replace("3P4/8/", "3P4/2p5/"))
        val developedRoot = e.copy(bestMoveUci = "g3f5",
            fenBefore = "rn1qkbnr/pp3ppp/2p5/2Pp1b2/3P4/6N1/PP3PPP/RNBQKB1R w KQkq - 1 6")
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(capture))
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(developedRoot))
    }

    @Test
    fun soundSacrificeKeepsPriorityOverEquivalentHomeDevelopment() {
        assertEquals(MoveClass.BRILLIANT,
            MoveClass.classify(equivalentMinorDevelopment(), materialSacrificed = true))
    }

    private fun quietBishopRetreat() = EvalInfo(
        ply = 13,
        fenBefore = "rn2k1nr/pp3ppp/2pqp3/3p1b2/3P4/2NBPN2/PPP2PPP/R2QK2R b KQkq - 1 7",
        bestMoveUci = "g8f6", bestCp = -13, bestMate = null,
        secondCp = -14, secondMate = null,
        playedMoveUci = "f5g6", playedCp = -37, playedMate = null
    )

    private fun exactQuietQueenAttack() = EvalInfo(
        ply = 33,
        fenBefore = "r7/pp1k1pp1/2pqpn2/3p2r1/1P1P4/P3P1Pp/2PQNP2/R4RK1 b - - 4 17",
        bestMoveUci = "f6e4", bestCp = 195, bestMate = null,
        secondCp = 194, secondMate = null,
        playedMoveUci = "f6e4", playedCp = 187, playedMate = null
    )

    @Test
    fun quietMinorRetreatDoesNotReceiveExtendedExcellentBudget() {
        val e = quietBishopRetreat()
        assertEquals(MoveClass.GOOD, MoveClass.classify(e))
        // Genuine nearby reference: developing Nf6 still receives Excellent.
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(EvalInfo(
            ply = 15,
            fenBefore = "rn2k1nr/pp3ppp/2pqp1b1/3p4/3P4/2NBPN2/PPP2PPP/R2Q1RK1 b kq - 3 8",
            bestMoveUci = "g6h5", bestCp = -17, bestMate = null,
            secondCp = -26, secondMate = null,
            playedMoveUci = "g8f6", playedCp = -35, playedMate = null)))
        // Color symmetry: a white retreat uses the same rule, not a black-only exception.
        val mirrored = e.copy(
            fenBefore = "r2qk2r/ppp2ppp/2nbpn2/3p4/3P1B2/2PQP3/PP3PPP/RN2K1NR w KQkq - 1 7",
            bestMoveUci = "g1f3", playedMoveUci = "f4g3")
        assertEquals(MoveClass.GOOD, MoveClass.classify(mirrored))
    }

    @Test
    fun minorRetreatStillUsesOrdinaryExcellentBoundary() {
        val e = quietBishopRetreat()
        val drop = MoveClass.cpToWinPct(-13) - MoveClass.cpToWinPct(-37)
        val defaults = MoveClassificationThresholds()
        assertEquals(MoveClass.GOOD, MoveClass.classify(e,
            thresholds = defaults.copy(excellentWinPctDrop = drop - 0.0001)))
        assertEquals(MoveClass.GOOD, MoveClass.classify(e,
            thresholds = defaults.copy(excellentWinPctDrop = drop)))
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(e,
            thresholds = defaults.copy(excellentWinPctDrop = drop + 0.0001)))
    }

    @Test
    fun exactQuietQueenAttackIsNotInterchangeableSearchTie() {
        val e = exactQuietQueenAttack()
        assertEquals(MoveClass.BEST, MoveClass.classify(e))
        // Removing the attacked queen restores ordinary root-tie classification.
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(e.copy(
            fenBefore = e.fenBefore.replace("2PQNP2", "2QPNP2"))))
        // An attack does not promote a different, non-rank-1 continuation.
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(e.copy(bestMoveUci = "e6e5")))
    }

    @Test
    fun exactQueenAttackNoiseBudgetIsInclusive() {
        val e = exactQuietQueenAttack()
        val drop = MoveClass.cpToWinPct(195) - MoveClass.cpToWinPct(187)
        val defaults = MoveClassificationThresholds()
        assertEquals(MoveClass.EXCELLENT, MoveClass.classify(e,
            thresholds = defaults.copy(exactBestSearchNoiseWinPctDrop = drop - 0.0001)))
        assertEquals(MoveClass.BEST, MoveClass.classify(e,
            thresholds = defaults.copy(exactBestSearchNoiseWinPctDrop = drop)))
        assertEquals(MoveClass.BEST, MoveClass.classify(e,
            thresholds = defaults.copy(exactBestSearchNoiseWinPctDrop = drop + 0.0001)))
    }

    @Test
    fun soundSacrificeKeepsPriorityOverRetreatAndQueenAttackRules() {
        assertEquals(MoveClass.BRILLIANT,
            MoveClass.classify(exactQuietQueenAttack(), materialSacrificed = true))
        assertEquals(MoveClass.BRILLIANT,
            MoveClass.classify(quietBishopRetreat().copy(bestCp = 40, playedCp = 20),
                materialSacrificed = true))
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
    fun equivalentNearBestSacrificeCanBeBrilliant() {
        val e = info(
            bestCp = 0,
            playedCp = 0,
            secondCp = 0,
            bestMove = "d1e1",
            playedMove = "h1e1"
        )

        assertEquals(MoveClass.BRILLIANT, MoveClass.classify(e, materialSacrificed = true))
    }

    @Test
    fun soundSacrificeToleratesSmallIndependentSearchNoise() {
        val e = info(
            bestCp = 665,
            playedCp = 630,
            secondCp = 620,
            bestMove = "f5g4",
            playedMove = "g8g2"
        )

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
        // Huge win-drop but small cp-loss -> the drop tier (BLUNDER) still governs.
        val e = info(bestCp = 400, playedCp = -600, secondCp = 380, bestMove = "d2d4", playedMove = "e2e4")
        assertEquals(MoveClass.BLUNDER, MoveClass.classify(e, cpLoss = 10))
    }
}
