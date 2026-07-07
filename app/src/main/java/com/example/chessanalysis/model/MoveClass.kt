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
            if (mate != null) return if (mate > 0) 100.0 else 0.0
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
        fun classify(e: EvalInfo, materialSacrificed: Boolean = false, cpLoss: Int? = null, ply: Int = e.ply): MoveClass {
            val bestWin = evalToWinPct(e.bestCp, e.bestMate)
            val playedWin = evalToWinPct(e.playedCp, e.playedMate)
            val secondWin = evalToWinPct(e.secondCp, e.secondMate)

            val drop = (bestWin - playedWin).coerceAtLeast(0.0)
            val isBest = e.playedMoveUci != null && e.playedMoveUci == e.bestMoveUci
            val onlyMove = (bestWin - secondWin) >= 12.0
            val nearBest = playedWin >= bestWin - 2.0

            // H-fix2: Brilliant only for healthy sacrifices that CREATE an advantage, not when already winning.
            if (isBest && materialSacrificed && playedWin >= 50.0 && nearBest && secondWin < 85.0) return BRILLIANT

            // H-fix3: Great on unique move OR band-jump (lost→drawn or drawn→won), not strictly the top engine move.
            fun band(w: Double) = if (w < 33.0) 0 else if (w <= 67.0) 1 else 2
            val bandJump = band(bestWin) > band(secondWin)
            if (nearBest && (onlyMove || bandJump)) return GREAT

            if (isBest) return BEST

            // H-fix1: BLUNDER threshold 25%→20% (chess.com Expected Points model).
            val winCls = when {
                drop < 2.0 -> EXCELLENT
                drop < 5.0 -> GOOD
                drop < 10.0 -> INACCURACY
                drop < 20.0 -> if (bestWin >= 65.0 && playedWin < 50.0) MISS else MISTAKE
                else -> if (bestWin >= 65.0 && playedWin < 50.0) MISS else BLUNDER
            }
            return if (cpLoss != null) worseOf(winCls, cpLossClassify(cpLoss)) else winCls
        }
    }
}
