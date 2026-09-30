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
    val decisiveWinPct: Double = 70.0,
    val dominantMoveWinPctGap: Double = 11.0,
    val rescueWinPct: Double = 55.0,
    val excellentWinPctDrop: Double = 1.8,
    val goodWinPctDrop: Double = 5.0,
    val inaccuracyWinPctDrop: Double = 10.0,
    val borderlineInaccuracyWinPctDrop: Double = 10.5,
    val borderlineInaccuracyMaxCpLoss: Int = 125,
    val mistakeWinPctDrop: Double = 24.0,
    val decisiveBlunderWinPctDrop: Double = 40.0,
    val newlyForcedMateMistakeWinPct: Double = 5.0,
    val newlyForcedMateBlunderWinPct: Double = 18.0,
    val winningGoodBestWinPct: Double = 84.0,
    val winningGoodPlayedWinPct: Double = 79.0,
    val winningGoodMaxCpLoss: Int = 110,
    val winningGoodMinWinPctDrop: Double = 5.0,
    val balancedGoodMaxAbsBestCp: Int = 60,
    val balancedGoodMaxCpLoss: Int = 55,
    val balancedGoodMaxWinPctDrop: Double = 5.5,
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
            val isBest = e.playedMoveUci != null && e.playedMoveUci == e.bestMoveUci
            val onlyMove = (bestWin - secondWin) >= thresholds.onlyMoveWinPctGap
            val nearBest = playedWin >= bestWin - thresholds.nearBestWinPctDrop
            val exactBestWithinSearchNoise = isBest &&
                playedWin >= bestWin - thresholds.exactBestSearchNoiseWinPctDrop
            val soundSacrifice = playedWin >= bestWin - thresholds.excellentWinPctDrop

            // A delivered checkmate is always Best. It must win before sacrifice/only-move rules,
            // because the terminal result is stronger evidence than search-rank heuristics.
            if (e.playedMate == 0) return BEST

            // A sound material offer can also be Brilliant in an already winning tactical attack.
            // Independent before/after searches fluctuate slightly, hence the wider soundness
            // window. Even an exact root best move is not Brilliant when the after-search shows a
            // larger loss; this prevents unstable shallow sacrifices from being promoted.
            if (materialSacrificed && playedWin >= 50.0 && soundSacrifice) return BRILLIANT

            // H-fix3: Great on unique move OR band-jump (lost→drawn or drawn→won), not strictly the top engine move.
            fun band(w: Double) = if (w < 33.0) 0 else if (w <= 67.0) 1 else 2
            val bandJump = band(bestWin) > band(secondWin)
            val dominantWinningMove = isBest && nearBest && bestWin > thresholds.decisiveWinPct &&
                secondWin <= thresholds.decisiveWinPct &&
                bestWin - secondWin >= thresholds.dominantMoveWinPctGap
            val onlyDrawingRescue = exactBestWithinSearchNoise && bestWin >= thresholds.rescueWinPct &&
                bestWin <= 67.0 && secondWin < 33.0
            val exactWinningBandCrossing = isBest && drop <= thresholds.exactBandCrossingSearchNoiseWinPctDrop &&
                bestWin > 67.0 && bestWin <= thresholds.decisiveWinPct && secondWin <= 67.0 &&
                bestWin - secondWin >= thresholds.dominantMoveWinPctGap
            val greatCandidate = (nearBest && (onlyMove || bandJump)) || dominantWinningMove ||
                onlyDrawingRescue || exactWinningBandCrossing
            // Simply collecting a hanging queen is Best, not Great. This is based on the board in
            // the supplied FEN, never on SAN text, game identity, or a particular square.
            if (greatCandidate && !capturesQueen(e)) return GREAT

            // When two continuations are effectively tied in an already won position, an exact
            // rank-1 move can be Excellent rather than uniquely Best. Equal trades use the stable
            // win-percentage tie; a tiny raw root gap also covers harmless search-order noise.
            // Material-winning captures remain Best unless the raw alternatives are truly tied.
            val rawAlternativeGap = if (e.bestCp != null && e.secondCp != null) {
                kotlin.math.abs(e.bestCp - e.secondCp)
            } else null
            val equivalentEqualTrade = bestWin - secondWin <= thresholds.equivalentBestWinPctGap &&
                isEvenTradeCapture(e)
            val equivalentSearchTie = rawAlternativeGap != null &&
                rawAlternativeGap <= thresholds.equivalentBestMaxCpGap &&
                drop >= thresholds.equivalentBestMinWinPctDrop
            if (isBest && drop > 0.0 && bestWin >= thresholds.equivalentBestMinWinPct &&
                (equivalentEqualTrade || equivalentSearchTie)) return EXCELLENT

            if (isBest) return BEST

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

            val rawCpLoss = if (e.bestCp != null && e.playedCp != null) {
                (e.bestCp - e.playedCp).coerceAtLeast(0)
            } else null

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

        private fun capturesQueen(e: EvalInfo): Boolean {
            return capturedPiece(e)?.equals('q', ignoreCase = true) == true
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

        private fun capturedPiece(e: EvalInfo): Char? {
            val move = e.playedMoveUci ?: return null
            if (move.length < 4) return null
            return pieceAt(e.fenBefore, move.substring(2, 4))
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
