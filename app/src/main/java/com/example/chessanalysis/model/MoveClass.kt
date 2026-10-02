package com.example.chessanalysis.model

import android.graphics.Color
import com.example.chessanalysis.data.OpeningStats

/** Engine evaluation around one played ply. All cp/mate are from the perspective of the side TO MOVE in fenBefore.
 *  cp = centipawns (null if it's a mate line); mate = moves-to-mate (signed; null if not mate).
 *  played* = evaluation of the position AFTER the actually played move, converted back to the moving side's POV. */
data class EvalInfo(
    val ply: Int,
    val fenBefore: String,
    val bestMoveUci: String?,
    val bestCp: Int?,
    val bestMate: Int?,
    val bestAlternative: String? = null,
    val bestAlternativeCp: Int? = null,
    val secondCp: Int?,
    val secondMate: Int?,
    val playedMoveUci: String?,
    val playedCp: Int?,
    val playedMate: Int?,
    val openingStats: OpeningStats? = null
)

/** Global review thresholds. One immutable configuration is applied to every game. */
data class MoveClassificationThresholds(
    val onlyMoveWinPctGap: Double = 25.0,
    val nearBestWinPctDrop: Double = 0.5,
    val exactBestSearchNoiseWinPctDrop: Double = 1.0,
    val exactBandCrossingSearchNoiseWinPctDrop: Double = 2.0,
    val equivalentBestWinPctGap: Double = 0.5,
    val equivalentBestMinWinPct: Double = 85.0,
    val equivalentBestMaxCpGap: Int = 10,
    val equivalentBestMinWinPctDrop: Double = 0.1,
    val equivalentBestMaxWinPctDrop: Double = 1.1,
    val equivalentBestTinyRootGapCp: Int = 2,
    val equivalentBestTinyCpLoss: Int = 3,
    val equivalentBestTinyWinPctDrop: Double = 0.3,
    val balancedDevelopmentMaxAbsCp: Int = 50,
    val positionalExcellentMaxAbsBestCp: Int = 100,
    val positionalExcellentMaxCpLoss: Int = 35,
    val positionalExcellentMaxWinPctDrop: Double = 3.0,
    val positionalGoodMaxAbsBestCp: Int = 130,
    val positionalGoodMaxCpLoss: Int = 65,
    val positionalGoodMaxWinPctDrop: Double = 6.1,
    val castlingInaccuracyMinCpLoss: Int = 45,
    val castlingGoodMinWinPctDrop: Double = 1.8,
    val alternatePieceSameTargetGoodMinCpLoss: Int = 15,
    val alternatePieceSameTargetGoodMinWinPctDrop: Double = 1.0,
    val alternatePieceSameTargetMissMinCpLoss: Int = 250,
    val alternatePieceSameTargetMissMinWinPctDrop: Double = 20.0,
    val decisiveWinPct: Double = 70.0,
    val dominantMoveWinPctGap: Double = 11.0,
    val rescueWinPct: Double = 55.0,
    val exactBestSacrificeWinPctDrop: Double = 3.0,
    val greatResourceMaxWinPct: Double = 67.0,
    val greatResourceMinWinPctGap: Double = 15.0,
    val greatResourceMaxWinPctDrop: Double = 5.0,
    val greatWinningMinWinPct: Double = 80.0,
    val greatWinningMinWinPctGap: Double = 15.0,
    val greatWinningMaxWinPctDrop: Double = 1.5,
    val greatPawnResourceMaxWinPctDrop: Double = 1.4,
    val greatForcingAttackMinWinPctGap: Double = 3.0,
    val greatForcingAttackMaxWinPctDrop: Double = 1.0,
    val greatCheckingForkMinWinPct: Double = 80.0,
    val greatCheckingForkMaxWinPct: Double = 95.0,
    val greatCheckingForkMinWinPctDrop: Double = 5.0,
    val greatCheckingForkMaxWinPctDrop: Double = 6.0,
    val greatEquivalentForcingMaxWinPctDifference: Double = 0.5,
    val greatEquivalentForcingMinWinPct: Double = 45.0,
    val greatEquivalentForcingMaxWinPct: Double = 55.0,
    val excellentWinPctDrop: Double = 1.9,
    val goodWinPctDrop: Double = 5.0,
    val inaccuracyWinPctDrop: Double = 10.0,
    val borderlineInaccuracyWinPctDrop: Double = 11.1,
    val borderlineInaccuracyMaxCpLoss: Int = 125,
    val mistakeWinPctDrop: Double = 24.0,
    val decisiveBlunderWinPctDrop: Double = 40.0,
    val newlyForcedMateMistakeWinPct: Double = 5.0,
    val newlyForcedMateBlunderWinPct: Double = 18.0,
    val winningGoodBestWinPct: Double = 84.0,
    val winningGoodPlayedWinPct: Double = 79.0,
    val winningGoodMaxCpLoss: Int = 110,
    val winningGoodMinWinPctDrop: Double = 5.0,
    val balancedGoodMaxAbsBestCp: Int = 70,
    val balancedGoodMaxCpLoss: Int = 65,
    val balancedGoodMaxWinPctDrop: Double = 6.1,
    val winningMissBestWinPct: Double = 91.0,
    val winningMissPlayedWinPct: Double = 85.0,
    val winningMissMinCpLoss: Int = 150,
    val winningMissMinWinPctDrop: Double = 5.0,
    val missBestWinPct: Double = 60.0,
    val missPlayedWinPct: Double = 55.0
)

/** Quality classification of a played move (ordered best to worst). */
enum class MoveClass(val symbol: String, val color: Int, val label: String) {
    BRILLIANT("!!", Color.parseColor("#26C2A3"), "Brilliant"),
    GREAT("!", Color.parseColor("#5B8BB0"), "Great"),
    BEST("★", Color.parseColor("#81B64C"), "Best"),
    EXCELLENT("👍", Color.parseColor("#81B64C"), "Excellent"),
    GOOD("✓", Color.parseColor("#95AF6F"), "Good"),
    BOOK("📖", Color.parseColor("#A88865"), "Book"),
    INACCURACY("?!", Color.parseColor("#F0C15C"), "Inaccuracy"),
    MISTAKE("?", Color.parseColor("#E58F2A"), "Mistake"),
    MISS("✗", Color.parseColor("#FF6B9D"), "Miss"),
    BLUNDER("??", Color.parseColor("#FA412D"), "Blunder");

    companion object {
        /** Logistic centipawn -> win% (mover POV), clamped to [0,100]. */
        fun cpToWinPct(cp: Int): Double {
            val w = 50.0 + 50.0 * (2.0 / (1.0 + Math.exp(-0.00368208 * cp)) - 1.0)
            return w.coerceIn(0.0, 100.0)
        }

        /** Convert an eval (cp or mate) to win% from the mover's POV. */
        fun evalToWinPct(cp: Int?, mate: Int?): Double {
            if (mate != null) return if (mate >= 0) 100.0 else 0.0
            return cpToWinPct(cp ?: 0)
        }

        /** Classify by centipawn loss alone — caps at MISTAKE; BLUNDER is only from win%-drop (EPM-style).
         *  Thresholds: Excellent < 50, Good < 100, Inaccuracy < 300, Mistake >= 300. */
        fun cpLossClassify(cpLoss: Int): MoveClass = when {
            cpLoss < 50 -> EXCELLENT
            cpLoss < 100 -> GOOD
            cpLoss < 300 -> INACCURACY
            else -> MISTAKE
        }

        /** The worse (lower-quality) of two classes. */
        private fun worseOf(a: MoveClass, b: MoveClass): MoveClass = if (a.ordinal >= b.ordinal) a else b

        /**
         * Classify a played move (BOOK handled by caller). Chess.com-style:
         * positive special cases (engine's own best move → Best/Great/Brilliant) win outright and are
         * never downgraded; for everything else the class is the WORSE of the win%-drop tier and the
         * cp-loss tier ([cpLoss], pass null on mate lines where cp-loss is meaningless).
         */
        fun classify(
            e: EvalInfo,
            materialSacrificed: Boolean = false,
            cpLoss: Int? = null,
            thresholds: MoveClassificationThresholds = MoveClassificationThresholds()
        ): MoveClass {
            val bestWin = evalToWinPct(e.bestCp, e.bestMate)
            val playedWin = evalToWinPct(e.playedCp, e.playedMate)
            val secondWin = evalToWinPct(e.secondCp, e.secondMate)

            val drop = (bestWin - playedWin).coerceAtLeast(0.0)
            val independentCpLoss = if (e.bestCp != null && e.playedCp != null) {
                (e.bestCp - e.playedCp).coerceAtLeast(0)
            } else null
            val isBest = e.playedMoveUci != null && e.playedMoveUci == e.bestMoveUci
            val soundSacrificeDrop = if (isBest) {
                thresholds.exactBestSacrificeWinPctDrop
            } else {
                thresholds.excellentWinPctDrop
            }
            val soundSacrifice = drop <= soundSacrificeDrop

            // A delivered checkmate is always Best. It must win before sacrifice/only-move rules,
            // because the terminal result is stronger evidence than search-rank heuristics.
            if (e.playedMate == 0) return BEST

            // A sound material offer can also be Brilliant in an already winning tactical attack.
            // Independent before/after searches fluctuate slightly, hence the wider soundness
            // window. Even an exact root best move is not Brilliant when the after-search shows a
            // larger loss; this prevents unstable shallow sacrifices from being promoted.
            val movingPiece = e.playedMoveUci?.takeIf { it.length >= 4 }
                ?.let { pieceAt(e.fenBefore, it.substring(0, 2)) }
            val capturedPiece = capturedPiece(e)
            val nonBestMinorForPawn = !isBest && movingPiece?.lowercaseChar() in setOf('n', 'b') &&
                capturedPiece?.lowercaseChar() == 'p'
            if (materialSacrificed && playedWin >= 50.0 && soundSacrifice &&
                !nonBestMinorForPawn) return BRILLIANT

            // Great normally requires an exact rank-1 resource. An equally evaluated forcing
            // continuation can also qualify when root ordering merely chose another tied move.
            // Drawn/lost rescue positions tolerate more independent-search noise than already
            // winning continuations. Capture structure filters ordinary material collection.
            val alternativeGap = bestWin - secondWin
            val hasSecondScore = e.secondCp != null || e.secondMate != null
            val criticalResource = hasSecondScore && isBest &&
                bestWin <= thresholds.greatResourceMaxWinPct &&
                alternativeGap >= thresholds.greatResourceMinWinPctGap &&
                drop <= thresholds.greatResourceMaxWinPctDrop
            val dominantWinningMove = hasSecondScore && isBest &&
                bestWin >= thresholds.greatWinningMinWinPct &&
                alternativeGap >= thresholds.greatWinningMinWinPctGap &&
                drop <= thresholds.greatWinningMaxWinPctDrop
            val resultBandRescue = hasSecondScore && isBest && bestWin >= 33.0 && bestWin <= 67.0 &&
                secondWin < 33.0 && alternativeGap >= 7.0 &&
                drop <= thresholds.greatResourceMaxWinPctDrop && movingPiece?.lowercaseChar() != 'n'
            val knightResource = hasSecondScore && isBest && movingPiece?.lowercaseChar() == 'n' &&
                capturedPiece == null && bestWin >= 50.0 && bestWin <= 60.0 &&
                alternativeGap >= 10.0 && drop <= thresholds.greatResourceMaxWinPctDrop
            val tacticalPawnResource = hasSecondScore && isBest &&
                movingPiece?.lowercaseChar() != 'p' && capturedPiece?.lowercaseChar() == 'p' &&
                bestWin >= 60.0 && bestWin <= 67.0 &&
                drop <= thresholds.greatPawnResourceMaxWinPctDrop
            val tacticalWinningCapture = hasSecondScore && isBest && capturedPiece != null &&
                capturedPiece.lowercaseChar() != 'q' && bestWin >= 80.0 && bestWin < 95.0 &&
                movingPiece?.lowercaseChar() != 'q' &&
                (movingPiece?.lowercaseChar() != 'p' || capturedPiece.lowercaseChar() == 'p') &&
                alternativeGap >= 8.0 && drop <= thresholds.greatWinningMaxWinPctDrop
            val attackedPieces = attackedEnemyPiecesAfterMove(e)
            val forcingPawnAttack = hasSecondScore && isBest && movingPiece?.lowercaseChar() == 'p' &&
                capturedPiece == null && attackedPieces.any { it.lowercaseChar() in setOf('n', 'b', 'r', 'q') } &&
                isSingleSquarePawnAdvance(e.playedMoveUci) &&
                isHomeRankPawnAdvance(e.playedMoveUci, movingPiece) &&
                (bestWin >= thresholds.greatWinningMinWinPct ||
                    attackedPieces.any { it.lowercaseChar() == 'q' }) &&
                alternativeGap >= thresholds.greatForcingAttackMinWinPctGap &&
                drop <= thresholds.greatForcingAttackMaxWinPctDrop
            val checkingForkMove = hasSecondScore && isBest && movingPiece?.lowercaseChar() == 'q' &&
                (capturedPiece == null || capturedPiece.lowercaseChar() == 'p') &&
                attackedPieces.any { it.lowercaseChar() == 'k' } &&
                attackedPieces.any { it.lowercaseChar() in setOf('n', 'b', 'r', 'q') } &&
                bestWin >= thresholds.greatCheckingForkMinWinPct &&
                bestWin < thresholds.greatCheckingForkMaxWinPct &&
                drop >= thresholds.greatCheckingForkMinWinPctDrop &&
                drop <= thresholds.greatCheckingForkMaxWinPctDrop
            val equivalentForcingRookMove = hasSecondScore && !isBest &&
                movingPiece?.lowercaseChar() == 'r' && capturedPiece == null &&
                attackedPieces.any { it.lowercaseChar() == 'q' } &&
                bestWin >= thresholds.greatEquivalentForcingMinWinPct &&
                bestWin <= thresholds.greatEquivalentForcingMaxWinPct &&
                kotlin.math.abs(bestWin - secondWin) <= thresholds.greatEquivalentForcingMaxWinPctDifference &&
                drop <= thresholds.greatEquivalentForcingMaxWinPctDifference
            val forcedMateCapture = e.playedMate != null && e.playedMate > 0 && capturedPiece != null
            if ((criticalResource || dominantWinningMove || resultBandRescue || knightResource ||
                    tacticalPawnResource || tacticalWinningCapture || forcingPawnAttack ||
                    checkingForkMove || equivalentForcingRookMove) && !forcedMateCapture &&
                isGreatEligibleCapture(e, movingPiece, capturedPiece, bestWin, secondWin)) return GREAT

            // When two continuations are effectively tied in an already won position, an exact
            // rank-1 move can be Excellent rather than uniquely Best. Equal trades use the stable
            // win-percentage tie; a tiny raw root gap also covers harmless search-order noise.
            // Material-winning captures remain Best unless the raw alternatives are truly tied.
            val rawAlternativeGap = if (e.bestCp != null && e.secondCp != null) {
                kotlin.math.abs(e.bestCp - e.secondCp)
            } else null
            // Near equality, exchanging root order between two home-rank minor pieces does
            // not establish a meaningful quality difference. A different destination for the
            // same piece is not equivalent development. Quiet defensive pawn moves are judged
            // separately; they must not inherit this tolerance while already ahead.
            val balancedDevelopment = e.bestCp != null &&
                kotlin.math.abs(e.bestCp) <= thresholds.balancedDevelopmentMaxAbsCp
            val quietHomeMinor = capturedPiece == null &&
                isHomeMinorDevelopment(e.fenBefore, e.playedMoveUci)
            val tiedExactDevelopment = isBest && balancedDevelopment && quietHomeMinor &&
                rawAlternativeGap != null &&
                rawAlternativeGap <= thresholds.equivalentBestTinyRootGapCp &&
                drop < thresholds.excellentWinPctDrop
            val equivalentMinorDevelopment = !isBest && balancedDevelopment && quietHomeMinor &&
                isHomeMinorDevelopment(e.fenBefore, e.bestMoveUci) &&
                e.playedMoveUci?.take(2) != e.bestMoveUci?.take(2)
            val defensivePawnDevelopment = !isBest && capturedPiece == null &&
                movingPiece?.lowercaseChar() == 'p' && e.bestCp != null && e.bestCp <= 0 &&
                kotlin.math.abs(e.bestCp) <= thresholds.positionalExcellentMaxAbsBestCp &&
                isHomeRankPawnAdvance(e.playedMoveUci, movingPiece) &&
                isSingleSquarePawnAdvance(e.playedMoveUci)
            val stableDevelopmentAlternative = (equivalentMinorDevelopment || defensivePawnDevelopment) &&
                rawAlternativeGap != null && rawAlternativeGap <= thresholds.equivalentBestMaxCpGap &&
                independentCpLoss != null && independentCpLoss <= thresholds.equivalentBestMaxCpGap &&
                drop <= thresholds.exactBestSearchNoiseWinPctDrop
            if (tiedExactDevelopment || stableDevelopmentAlternative) return BEST
            val equivalentEqualTrade = bestWin - secondWin <= thresholds.equivalentBestWinPctGap &&
                isEvenTradeCapture(e)
            // A quiet rank-1 queen attack is forcing, not an interchangeable quiet move.
            // Retain Best only inside the independent-search noise budget; captures and
            // higher-priority Brilliant/Great resources are handled above.
            val forcingExactQueenAttack = isBest && capturedPiece == null &&
                attackedPieces.any { it.lowercaseChar() == 'q' } &&
                drop <= thresholds.exactBestSearchNoiseWinPctDrop
            if (forcingExactQueenAttack) return BEST
            val equivalentSearchTie = rawAlternativeGap != null &&
                rawAlternativeGap <= thresholds.equivalentBestMaxCpGap &&
                drop >= thresholds.equivalentBestMinWinPctDrop &&
                movingPiece?.lowercaseChar() != 'p' &&
                (isEvenTradeCapture(e) || bestWin < thresholds.equivalentBestMinWinPct)
            // A balanced equal trade can show a modest independent-search loss even when
            // its root alternative is outside the tiny tie margin. Use the existing positional
            // Excellent budgets; larger losses or clearly separated alternatives remain Best.
            val balancedEqualTrade = isEvenTradeCapture(e) && e.bestCp != null &&
                kotlin.math.abs(e.bestCp) <= thresholds.positionalExcellentMaxAbsBestCp &&
                independentCpLoss != null &&
                independentCpLoss <= thresholds.positionalExcellentMaxCpLoss &&
                hasSecondScore &&
                kotlin.math.abs(alternativeGap) <= thresholds.positionalExcellentMaxWinPctDrop &&
                drop >= thresholds.excellentWinPctDrop &&
                drop <= thresholds.positionalExcellentMaxWinPctDrop
            if (isBest && drop > 0.0 &&
                (equivalentEqualTrade || equivalentSearchTie || balancedEqualTrade)) return EXCELLENT

            // Independent searches can order effectively tied continuations differently. Preserve
            // Best for a root tie, a near-equal winning capture, or the same destination reached by
            // an equivalent piece; quiet alternatives still remain Excellent.
            val nonBestRootTie = !isBest && rawAlternativeGap != null &&
                movingPiece?.lowercaseChar() != 'k' && !materialSacrificed &&
                ((rawAlternativeGap <= thresholds.equivalentBestMaxCpGap && drop == 0.0 &&
                    independentCpLoss == 0) ||
                    (rawAlternativeGap <= thresholds.equivalentBestTinyRootGapCp &&
                        independentCpLoss != null &&
                        independentCpLoss <= thresholds.equivalentBestTinyCpLoss &&
                        drop <= thresholds.equivalentBestTinyWinPctDrop))
            val nonBestWinningEquivalent = !isBest && bestWin >= thresholds.equivalentBestMinWinPct &&
                drop <= thresholds.equivalentBestMaxWinPctDrop &&
                ((capturedPiece != null && movingPiece?.lowercaseChar() in setOf('p', 'r')) ||
                    isEquivalentPieceAlternativeToSameSquare(e))
            if (nonBestRootTie || nonBestWinningEquivalent) return BEST

            // The extended Excellent loss budget must not upgrade a quiet minor retreat.
            // Such moves use the ordinary loss bands; a sufficiently small loss is still Excellent.
            val positionalExcellent = !isBest && movingPiece?.lowercaseChar() in setOf('n', 'b', 'r', 'q') &&
                capturedPiece == null && e.bestCp != null &&
                !isQuietMinorRetreat(e.fenBefore, e.playedMoveUci) &&
                kotlin.math.abs(e.bestCp) <= thresholds.positionalExcellentMaxAbsBestCp &&
                independentCpLoss != null && independentCpLoss <= thresholds.positionalExcellentMaxCpLoss &&
                rawAlternativeGap != null && rawAlternativeGap <= thresholds.equivalentBestMaxCpGap &&
                drop <= thresholds.positionalExcellentMaxWinPctDrop
            if (positionalExcellent) return EXCELLENT

            if (isBest) return BEST


            if (isCastlingMove(e, movingPiece) && independentCpLoss != null &&
                independentCpLoss >= thresholds.castlingInaccuracyMinCpLoss) return INACCURACY
            if (isCastlingMove(e, movingPiece) &&
                drop >= thresholds.castlingGoodMinWinPctDrop) return GOOD

            // If mate was already unavoidable, shortening it is an Inaccuracy rather than a new
            // decisive loss. A newly appearing mate still follows the calibrated loss bands below.
            if (e.bestMate != null && e.bestMate < 0 && e.playedMate != null && e.playedMate < 0 &&
                kotlin.math.abs(e.playedMate) < kotlin.math.abs(e.bestMate)) return INACCURACY

            // Entering a forced-mate line is judged by how much practical winning chance still
            // existed before the move. This separates a fresh decisive collapse from accelerating
            // a position that was already overwhelmingly lost.
            if (e.bestMate == null && e.playedMate != null && e.playedMate < 0) {
                if (bestWin >= thresholds.newlyForcedMateBlunderWinPct) return BLUNDER
                if (bestWin >= thresholds.newlyForcedMateMistakeWinPct) return MISTAKE
            }

            val rawCpLoss = independentCpLoss

            // Choosing a different piece for Stockfish's destination is a structural signal that
            // raw score bands alone miss. A small loss is Good; a large tactical collapse is a
            // Miss because the destination was found but the required recapturing piece was not.
            if (isDifferentPieceAlternativeToSameSquare(e) && rawCpLoss != null) {
                if (rawCpLoss >= thresholds.alternatePieceSameTargetMissMinCpLoss &&
                    drop >= thresholds.alternatePieceSameTargetMissMinWinPctDrop) return MISS
                if (rawCpLoss >= thresholds.alternatePieceSameTargetGoodMinCpLoss &&
                    drop >= thresholds.alternatePieceSameTargetGoodMinWinPctDrop) return GOOD
            }

            // In a clearly winning position, missing a substantial conversion while retaining a
            // large advantage is a Miss rather than a generic Inaccuracy. A smaller loss is
            // tolerated as Good because it does not materially endanger the winning position.
            if (rawCpLoss != null && bestWin >= thresholds.winningMissBestWinPct &&
                playedWin >= thresholds.winningMissPlayedWinPct &&
                rawCpLoss >= thresholds.winningMissMinCpLoss &&
                drop >= thresholds.winningMissMinWinPctDrop) return MISS
            if (rawCpLoss != null && bestWin >= thresholds.winningGoodBestWinPct &&
                playedWin >= thresholds.winningGoodPlayedWinPct &&
                rawCpLoss <= thresholds.winningGoodMaxCpLoss &&
                drop >= thresholds.winningGoodMinWinPctDrop) return GOOD

            // Near equality, a small evaluation slip remains Good when both the raw loss and the
            // expected-score loss stay inside a narrow margin. Larger positional or tactical
            // concessions continue into the normal Inaccuracy band.
            if (rawCpLoss != null && e.bestCp != null &&
                kotlin.math.abs(e.bestCp) <= thresholds.balancedGoodMaxAbsBestCp &&
                rawCpLoss <= thresholds.balancedGoodMaxCpLoss &&
                drop >= thresholds.goodWinPctDrop &&
                drop < thresholds.balancedGoodMaxWinPctDrop) return GOOD

            val positionalGood = rawCpLoss != null && e.bestCp != null &&
                movingPiece?.lowercaseChar() in setOf('n', 'b', 'r', 'q') &&
                (capturedPiece == null || isEvenTradeCapture(e)) &&
                kotlin.math.abs(e.bestCp) <= thresholds.positionalGoodMaxAbsBestCp &&
                rawCpLoss <= thresholds.positionalGoodMaxCpLoss &&
                rawAlternativeGap != null && rawAlternativeGap <= thresholds.equivalentBestMaxCpGap &&
                drop >= thresholds.goodWinPctDrop && drop < thresholds.positionalGoodMaxWinPctDrop
            if (positionalGood) return GOOD

            // A small centipawn loss can sit just beyond the ordinary expected-score boundary;
            // keep that narrow search-conversion margin in the Inaccuracy band without moving the
            // boundary for larger tactical losses.
            val inaccuracyCeiling = if (rawCpLoss != null &&
                rawCpLoss <= thresholds.borderlineInaccuracyMaxCpLoss) {
                thresholds.borderlineInaccuracyWinPctDrop
            } else thresholds.inaccuracyWinPctDrop

            // Global Expected-Points bands, calibrated on the frozen multi-game reference set.
            val winCls = when {
                drop < thresholds.excellentWinPctDrop -> EXCELLENT
                drop < thresholds.goodWinPctDrop -> GOOD
                drop < inaccuracyCeiling -> INACCURACY
                drop < thresholds.mistakeWinPctDrop -> {
                    if (bestWin >= thresholds.missBestWinPct && playedWin < thresholds.missPlayedWinPct) MISS else MISTAKE
                }
                else -> {
                    if (bestWin >= thresholds.missBestWinPct && playedWin < thresholds.missPlayedWinPct &&
                        drop < thresholds.decisiveBlunderWinPctDrop) MISS else BLUNDER
                }
            }
            return if (cpLoss != null) worseOf(winCls, cpLossClassify(cpLoss)) else winCls
        }

        private fun isGreatEligibleCapture(
            e: EvalInfo,
            movingPiece: Char?,
            capturedPiece: Char?,
            bestWin: Double,
            secondWin: Double
        ): Boolean {
            if (capturedPiece == null) return true
            return when (capturedPiece.lowercaseChar()) {
                // Dead-even pawn recaptures are usually ordinary Best moves.
                'p' -> bestWin < 48.0 || bestWin >= 55.0
                // A hanging queen remains Best. A tactical queen capture is Great only when the
                // alternative loses and the played continuation retains a large advantage.
                'q' -> movingPiece?.lowercaseChar() == 'q' && bestWin >= 80.0 && secondWin < 33.0
                // Other material captures need a substantial retained advantage; this prevents
                // obvious simplifying captures in balanced positions from becoming Great.
                else -> (bestWin >= 75.0 ||
                    (bestWin <= 67.0 && movingPiece?.lowercaseChar() == 'r')) &&
                    movingPiece != null && e.playedMoveUci != null
            }
        }

        private fun isEvenTradeCapture(e: EvalInfo): Boolean {
            val move = e.playedMoveUci ?: return false
            val moving = pieceAt(e.fenBefore, move.take(2)) ?: return false
            val captured = capturedPiece(e) ?: return false
            fun value(piece: Char): Int = when (piece.lowercaseChar()) {
                'p' -> 1
                'n', 'b' -> 3
                'r' -> 5
                'q' -> 9
                'k' -> 100
                else -> 0
            }
            return value(moving) > 0 && value(moving) == value(captured)
        }

        private fun isEquivalentPieceAlternativeToSameSquare(e: EvalInfo): Boolean {
            val played = e.playedMoveUci ?: return false
            val best = e.bestMoveUci ?: return false
            if (played.length < 4 || best.length < 4 || played.substring(2, 4) != best.substring(2, 4)) {
                return false
            }
            val playedPiece = pieceAt(e.fenBefore, played.substring(0, 2)) ?: return false
            val bestPiece = pieceAt(e.fenBefore, best.substring(0, 2)) ?: return false
            return playedPiece.lowercaseChar() == bestPiece.lowercaseChar()
        }

        private fun isDifferentPieceAlternativeToSameSquare(e: EvalInfo): Boolean {
            val played = e.playedMoveUci ?: return false
            val best = e.bestMoveUci ?: return false
            if (played.length < 4 || best.length < 4 || played.substring(2, 4) != best.substring(2, 4)) {
                return false
            }
            val playedPiece = pieceAt(e.fenBefore, played.substring(0, 2)) ?: return false
            val bestPiece = pieceAt(e.fenBefore, best.substring(0, 2)) ?: return false
            return playedPiece.lowercaseChar() != bestPiece.lowercaseChar()
        }

        private fun isCastlingMove(e: EvalInfo, movingPiece: Char?): Boolean {
            val move = e.playedMoveUci ?: return false
            if (move.length < 4 || movingPiece?.lowercaseChar() != 'k') return false
            return kotlin.math.abs(move[2] - move[0]) == 2
        }

        private fun isHomeMinorDevelopment(fen: String, move: String?): Boolean {
            if (move == null || move.length < 4) return false
            val piece = pieceAt(fen, move.take(2)) ?: return false
            if (piece.lowercaseChar() !in setOf('n', 'b')) return false
            return move[1] == (if (piece.isUpperCase()) '1' else '8') &&
                move[3] != move[1] && pieceAt(fen, move.substring(2, 4)) == null
        }

        /** A quiet minor-piece move toward its own back rank is not development. */
        private fun isQuietMinorRetreat(fen: String, move: String?): Boolean {
            if (move == null || move.length < 4) return false
            val piece = pieceAt(fen, move.take(2)) ?: return false
            if (piece.lowercaseChar() !in setOf('n', 'b') ||
                pieceAt(fen, move.substring(2, 4)) != null) return false
            val rankChange = move[3] - move[1]
            return if (piece.isUpperCase()) rankChange < 0 else rankChange > 0
        }

        private fun capturedPiece(e: EvalInfo): Char? {
            val move = e.playedMoveUci ?: return null
            if (move.length < 4) return null
            return pieceAt(e.fenBefore, move.substring(2, 4))
        }

        private fun isSingleSquarePawnAdvance(move: String?): Boolean {
            if (move == null || move.length < 4 || move[0] != move[2]) return false
            return kotlin.math.abs((move[3] - '0') - (move[1] - '0')) == 1
        }

        private fun isHomeRankPawnAdvance(move: String?, pawn: Char?): Boolean {
            if (move == null || move.length < 4 || pawn?.lowercaseChar() != 'p') return false
            return move[1] == if (pawn.isUpperCase()) '2' else '7'
        }

        /** Pieces attacked directly by the moved piece after applying the UCI move. */
        private fun attackedEnemyPiecesAfterMove(e: EvalInfo): List<Char> {
            val move = e.playedMoveUci ?: return emptyList()
            if (move.length < 4) return emptyList()
            val board = boardFromFen(e.fenBefore)
            val from = move.substring(0, 2)
            val to = move.substring(2, 4)
            val moving = board.remove(from) ?: return emptyList()
            board.remove(to)
            val promoted = move.getOrNull(4)?.let { if (moving.isUpperCase()) it.uppercaseChar() else it.lowercaseChar() }
            board[to] = promoted ?: moving
            return board.mapNotNull { (square, piece) ->
                if (piece.isUpperCase() == moving.isUpperCase()) null
                else piece.takeIf { attacksSquare(board, to, promoted ?: moving, square) }
            }
        }

        private fun boardFromFen(fen: String): MutableMap<String, Char> {
            val board = mutableMapOf<String, Char>()
            fen.substringBefore(' ').split('/').forEachIndexed { rankIndex, fenRank ->
                var file = 0
                for (token in fenRank) {
                    if (token.isDigit()) file += token.digitToInt()
                    else {
                        board["${('a'.code + file).toChar()}${8 - rankIndex}"] = token
                        file++
                    }
                }
            }
            return board
        }

        private fun attacksSquare(
            board: Map<String, Char>,
            from: String,
            piece: Char,
            target: String
        ): Boolean {
            val fromFile = from[0] - 'a'
            val fromRank = from[1] - '0'
            val targetFile = target[0] - 'a'
            val targetRank = target[1] - '0'
            val df = targetFile - fromFile
            val dr = targetRank - fromRank
            val absFile = kotlin.math.abs(df)
            val absRank = kotlin.math.abs(dr)
            return when (piece.lowercaseChar()) {
                'p' -> absFile == 1 && dr == if (piece.isUpperCase()) 1 else -1
                'n' -> (absFile == 1 && absRank == 2) || (absFile == 2 && absRank == 1)
                'k' -> maxOf(absFile, absRank) == 1
                'b' -> absFile == absRank && pathIsClear(board, fromFile, fromRank, df, dr)
                'r' -> (df == 0 || dr == 0) && pathIsClear(board, fromFile, fromRank, df, dr)
                'q' -> (df == 0 || dr == 0 || absFile == absRank) &&
                    pathIsClear(board, fromFile, fromRank, df, dr)
                else -> false
            }
        }

        private fun pathIsClear(
            board: Map<String, Char>,
            fromFile: Int,
            fromRank: Int,
            df: Int,
            dr: Int
        ): Boolean {
            val fileStep = df.compareTo(0)
            val rankStep = dr.compareTo(0)
            var file = fromFile + fileStep
            var rank = fromRank + rankStep
            val targetFile = fromFile + df
            val targetRank = fromRank + dr
            while (file != targetFile || rank != targetRank) {
                if (board.containsKey("${('a'.code + file).toChar()}$rank")) return false
                file += fileStep
                rank += rankStep
            }
            return true
        }

        private fun pieceAt(fen: String, square: String): Char? {
            if (square.length != 2) return null
            val file = square[0] - 'a'
            val rank = square[1] - '0'
            if (file !in 0..7 || rank !in 1..8) return null
            val fenRank = fen.substringBefore(' ').split('/').getOrNull(8 - rank) ?: return null
            var currentFile = 0
            for (piece in fenRank) {
                if (piece.isDigit()) {
                    currentFile += piece.digitToInt()
                } else {
                    if (currentFile == file) return piece
                    currentFile++
                }
            }
            return null
        }
    }
}
