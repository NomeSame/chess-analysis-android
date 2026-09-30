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
                decisiveWinPct = 70.0,
                dominantMoveWinPctGap = 11.0,
                rescueWinPct = 55.0,
                excellentWinPctDrop = 1.8,
                goodWinPctDrop = 5.0,
                inaccuracyWinPctDrop = 10.0,
                borderlineInaccuracyWinPctDrop = 10.5,
                borderlineInaccuracyMaxCpLoss = 125,
                mistakeWinPctDrop = 24.0,
                decisiveBlunderWinPctDrop = 40.0,
                newlyForcedMateMistakeWinPct = 5.0,
                newlyForcedMateBlunderWinPct = 18.0,
                winningGoodBestWinPct = 84.0,
                winningGoodPlayedWinPct = 79.0,
                winningGoodMaxCpLoss = 110,
                winningGoodMinWinPctDrop = 5.0,
                balancedGoodMaxAbsBestCp = 60,
                balancedGoodMaxCpLoss = 55,
                balancedGoodMaxWinPctDrop = 5.5,
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
        val e = info(bestCp = 100, playedCp = 100, secondCp = -100)

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
        // mcnsena 49.Qxd3: unique at this depth, but not a sufficiently strong rescue for Great.
        val e = info(bestCp = 14, playedCp = 7, secondCp = -208, bestMove = "a3d3", playedMove = "a3d3")

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
        val e = info(bestCp = 207, playedCp = 187, secondCp = 26, bestMove = "c4d5", playedMove = "c4d5")

        assertEquals(MoveClass.GREAT, MoveClass.classify(e))
    }

    @Test
    fun bandCrossingNoiseRuleDoesNotPromoteAlreadyDecisiveOrNarrowAlternatives() {
        // mcnsena 39.Rxd3 is already clearly winning; Tadeas-2010 6...Nf6 has no meaningful gap.
        val alreadyDecisive = info(bestCp = 440, playedCp = 426, secondCp = -56, bestMove = "a3d3", playedMove = "a3d3")
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
    fun exactBestNearTiedContinuationInWonPositionIsExcellent() {
        // no-op 16.O-O: the root alternatives differ by only 9 cp; the independent
        // after-move search reports a small non-zero fluctuation.
        val e = info(bestCp = 567, playedCp = 561, secondCp = 558, bestMove = "e1g1", playedMove = "e1g1")

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
