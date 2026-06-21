package com.example.chessanalysis

import android.graphics.Color

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

        /** Classify by centipawn loss alone (0 = perfect, positive = loss for mover). */
        fun cpLossClassify(cpLoss: Int): MoveClass = when {
            cpLoss < 20 -> GOOD
            cpLoss < 50 -> INACCURACY
            cpLoss < 100 -> MISTAKE
            else -> BLUNDER
        }

        /** Classify a played move by engine eval (BOOK handled by caller). */
        fun classify(e: EvalInfo, materialSacrificed: Boolean = false, ply: Int = e.ply): MoveClass {
            val bestWin = evalToWinPct(e.bestCp, e.bestMate)
            val playedWin = evalToWinPct(e.playedCp, e.playedMate)
            val secondWin = evalToWinPct(e.secondCp, e.secondMate)

            val drop = (bestWin - playedWin).coerceAtLeast(0.0)
            val isBest = e.playedMoveUci != null && e.playedMoveUci == e.bestMoveUci
            val onlyMove = (bestWin - secondWin) >= 12.0

            return when {
                isBest && materialSacrificed && playedWin >= 50.0 -> BRILLIANT
                isBest && onlyMove -> GREAT
                isBest -> BEST
                drop < 2.0 -> EXCELLENT
                drop < 5.0 -> GOOD
                drop < 10.0 -> INACCURACY
                drop < 20.0 -> if (bestWin >= 75.0 && playedWin < 55.0) MISS else MISTAKE
                else -> if (bestWin >= 75.0 && playedWin < 55.0) MISS else BLUNDER
            }
        }
    }
}
