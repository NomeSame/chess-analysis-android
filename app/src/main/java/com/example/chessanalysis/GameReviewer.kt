package com.example.chessanalysis

import kotlin.math.abs

/**
 * Turns raw per-position engine evaluations (from [LiveAnalyzer.evaluatePositions]) into a
 * Chess.com-style game review: a class per played move, per-side counts + accuracy, and the
 * white-relative eval curve for the chart. Pure logic — no engine/Android dependency.
 */
class GameReviewer(private val explorer: LichessExplorer? = null) {

    /** Result of reviewing a full game. [perPly] index = ply (0-based, move that produced position i+1). */
    data class GameReview(
        val perPly: List<MoveClass>,
        val evalWhitePov: List<Int>,                       // one per position (0..n), cp from White's POV
        val counts: Map<Boolean, Map<MoveClass, Int>>,     // key: mover-is-white
        val accuracy: Map<Boolean, Double>,                // key: mover-is-white, 0..100
        val bestMovePerPos: List<String?>,                 // best UCI move available at each position (0..n)
        val openingTexts: Map<Int, String> = emptyMap(),   // ply → "GMs: e4 62%, d4 18%"
        val cpLosses: List<Int> = emptyList(),             // centipawn loss per ply (parallel to perPly)
        val tactics: List<TacticalChance> = emptyList(),
        val bestEvalPerPos: List<String?> = emptyList(),   // formatted eval of best move per ply
        val playedEvalPerPos: List<String?> = emptyList()  // formatted eval of played move per ply
    )

    private val MATE_CP = 2000  // chart magnitude for a mate score

    /**
     * @param fens  position FENs, index 0 = start, last = final position (size n+1 for n plies)
     * @param lines per-position rank-sorted MultiPV lines (same length as [fens])
     */
    fun review(fens: List<String>, lines: List<List<LiveAnalyzer.PvLine>>): GameReview {
        val n = minOf(fens.size, lines.size)
        val perPly = ArrayList<MoveClass>()
        val cpLosses = ArrayList<Int>()
        val evalWhitePov = ArrayList<Int>(n)
        val counts = hashMapOf(true to hashMapOf<MoveClass, Int>(), false to hashMapOf<MoveClass, Int>())
        val accSum = hashMapOf(true to 0.0, false to 0.0)
        val accCnt = hashMapOf(true to 0, false to 0)

        // White-POV eval curve (carry the last known value across terminal/empty positions).
        val bestMovePerPos = ArrayList<String?>(n)
        var lastWhite = 0
        for (i in 0 until n) {
            val top = lines[i].firstOrNull { it.rank == 1 }
            lastWhite = top?.let { whitePov(fens[i], it.cp, it.mate) } ?: lastWhite
            evalWhitePov.add(lastWhite)
            bestMovePerPos.add(top?.firstMove)
        }

        val openingTexts = mutableMapOf<Int, String>()
        val bestEvalPerPly = ArrayList<String?>(n - 1)
        val playedEvalPerPly = ArrayList<String?>(n - 1)

        for (i in 0 until n - 1) {
            val before = lines[i]
            val best = before.firstOrNull { it.rank == 1 }
            val second = before.firstOrNull { it.rank == 2 }
            val moverWhite = whiteToMove(fens[i])

            // Eval after the played move = next position's best, negated to the mover's POV.
            val after = lines[i + 1].firstOrNull { it.rank == 1 }
            val playedCp: Int?
            val playedMate: Int?
            if (after == null) {                       // terminal (mate/stalemate) → assume the played line held
                playedCp = best?.cp; playedMate = best?.mate
            } else {
                playedCp = after.cp?.let { -it }
                playedMate = after.mate?.let { -it }
            }

            val playedUci = playedMoveUci(fens[i], fens[i + 1], moverWhite)
            val info = EvalInfo(
                ply = i, fenBefore = fens[i],
                bestMoveUci = best?.firstMove, bestCp = best?.cp, bestMate = best?.mate,
                bestAlternative = best?.firstMove, bestAlternativeCp = best?.cp,
                secondCp = second?.cp, secondMate = second?.mate,
                playedMoveUci = playedUci, playedCp = playedCp, playedMate = playedMate
            )
            bestEvalPerPly.add(formatEval(best?.cp, best?.mate))
            playedEvalPerPly.add(formatEval(playedCp, playedMate))

            val sacrifice = isSacrifice(fens, i, moverWhite)
            val cls = MoveClass.classify(info, materialSacrificed = sacrifice)
            val cpLoss = ((best?.cp ?: 0) - (playedCp ?: 0)).coerceAtLeast(0)
            val cpCls = MoveClass.cpLossClassify(cpLoss)
            val combined = if (cls.ordinal > cpCls.ordinal) cls else cpCls
            cpLosses.add(cpLoss)
            perPly.add(combined)
            counts[moverWhite]!!.merge(combined, 1, Int::plus)

            val drop = (MoveClass.evalToWinPct(info.bestCp, info.bestMate) -
                        MoveClass.evalToWinPct(info.playedCp, info.playedMate)).coerceAtLeast(0.0)
            accSum[moverWhite] = accSum[moverWhite]!! + moveAccuracy(drop)
            accCnt[moverWhite] = accCnt[moverWhite]!! + 1

            // Opening book check: for early plies, query Lichess Masters Explorer
            if (i <= 15 && explorer != null) {
                val stats = explorer.query(fens[i])
                if (stats != null && stats.totalGames > 0) {
                    counts[moverWhite]!!.merge(combined, -1, Int::plus)
                    counts[moverWhite]!!.merge(MoveClass.BOOK, 1, Int::plus)
                    perPly[i] = MoveClass.BOOK
                    val top3 = stats.topMoves.take(3)
                    openingTexts[i] = "GMs: " + top3.joinToString(", ") {
                        "${it.san} ${"%.0f".format(it.playedPct)}%"
                    }
                }
            }
        }

        val accuracy = mapOf(
            true to (accSum[true]!! / accCnt[true]!!.coerceAtLeast(1)),
            false to (accSum[false]!! / accCnt[false]!!.coerceAtLeast(1))
        )
        val review = GameReview(perPly, evalWhitePov, counts.mapValues { it.value.toMap() }, accuracy, bestMovePerPos, openingTexts, cpLosses, bestEvalPerPos = bestEvalPerPly, playedEvalPerPos = playedEvalPerPly)
        val tactics = detectTactics(review, fens, lines)
        return review.copy(tactics = tactics)
    }

    /** Per-move accuracy from win% loss (lichess-style curve), clamped to [0,100]. */
    private fun moveAccuracy(winPctDrop: Double): Double =
        (103.1668 * Math.exp(-0.04354 * winPctDrop) - 3.1669).coerceIn(0.0, 100.0)

    // ---- chess helpers (FEN parsing) ----

    /** Convert a mover-POV score at [fen] to White's POV (mate folded to ±MATE_CP). */
    private fun whitePov(fen: String, cp: Int?, mate: Int?): Int {
        val sign = if (whiteToMove(fen)) 1 else -1
        val score = when {
            mate != null -> if (mate > 0) MATE_CP else -MATE_CP
            else -> cp ?: 0
        }
        return sign * score
    }

    private val pieceValue = mapOf('p' to 1, 'n' to 3, 'b' to 3, 'r' to 5, 'q' to 9, 'k' to 0)

    /** Mover-relative material balance (own minus opponent), in pawn units. */
    private fun materialBalance(fen: String, moverWhite: Boolean): Int {
        var bal = 0
        for (ch in fen.substringBefore(' ')) {
            val v = pieceValue[ch.lowercaseChar()] ?: continue
            val isWhite = ch.isUpperCase()
            bal += if (isWhite == moverWhite) v else -v
        }
        return bal
    }

    /**
     * Sacrifice heuristic for Brilliant: after the move AND the opponent's best reply (fen i+2),
     * the mover is down material vs before the move — i.e. material was given up for compensation.
     */
    private fun isSacrifice(fens: List<String>, i: Int, moverWhite: Boolean): Boolean {
        val after = fens.getOrNull(i + 2) ?: return false
        val before = materialBalance(fens[i], moverWhite)
        val later = materialBalance(after, moverWhite)
        return later <= before - 2
    }

    // ---- Phase C: tactic detection ----

    fun detectTactics(review: GameReview, fens: List<String>, lines: List<List<LiveAnalyzer.PvLine>>): List<TacticalChance> {
        val tactics = mutableListOf<TacticalChance>()
        for (i in review.cpLosses.indices) {
            val cpLoss = review.cpLosses[i]
            if (cpLoss < 80) continue
            val before = lines.getOrNull(i)?.firstOrNull { it.rank == 1 } ?: continue
            val bestCp = before.cp ?: 0
            if (bestCp < 150) continue
            val bestMove = before.firstMove ?: continue
            val playedUci = playedMoveUci(fens[i], fens[i + 1], whiteToMove(fens[i]))
            val desc = describeTactic(cpLoss, before.mate, bestCp)
            val checkPrefix = if (givesCheck(fens[i], bestMove)) "Check: " else ""
            tactics.add(TacticalChance(
                ply = i, fen = fens[i],
                missedMove = playedUci, bestMove = bestMove,
                cpLoss = cpLoss,
                description = "$checkPrefix$desc"
            ))
        }
        return tactics
    }

    private fun describeTactic(cpLoss: Int, bestMate: Int?, bestCp: Int): String = when {
        bestMate != null && bestMate > 0 -> "Mate in $bestMate"
        bestCp >= 1300 -> "Wins a queen"
        bestCp >= 900 -> "Wins a rook"
        bestCp >= 500 -> "Wins a minor piece"
        bestCp >= 300 -> "Wins a pawn"
        else -> "Missed +$bestCp cp"
    }

    private fun givesCheck(fen: String, uci: String): Boolean {
        if (uci.length < 4) return false
        val fromCol = uci[0] - 'a'; val fromRow = '8' - uci[1]
        val toCol = uci[2] - 'a'; val toRow = '8' - uci[3]
        val promo = if (uci.length >= 5) uci[4] else null
        val board = parseBoard(fen).toMutableList()
        val fromIdx = fromRow * 8 + fromCol
        val toIdx = toRow * 8 + toCol
        val piece = board[fromIdx] ?: return false
        board[toIdx] = piece
        board[fromIdx] = null
        if (promo != null) board[toIdx] = if (piece.isUpperCase()) promo.uppercaseChar() else promo
        val enemyKing = if (piece.isUpperCase()) 'k' else 'K'
        val kingIdx = board.indexOf(enemyKing)
        if (kingIdx < 0) return false
        return isAttacked(board.toTypedArray(), kingIdx, piece.isUpperCase())
    }

    private fun isAttacked(board: Array<Char?>, sqIdx: Int, byWhite: Boolean): Boolean {
        val row = sqIdx / 8; val col = sqIdx % 8
        fun hasPiece(r: Int, c: Int, type: Char, white: Boolean): Boolean {
            if (r !in 0..7 || c !in 0..7) return false
            val p = board[r * 8 + c] ?: return false
            return p.uppercaseChar() == type && p.isUpperCase() == white
        }
        val enemy = !byWhite
        val pawnDir = if (byWhite) -1 else 1
        if (hasPiece(row + pawnDir, col - 1, 'P', enemy) || hasPiece(row + pawnDir, col + 1, 'P', enemy)) return true
        for (dr in listOf(-2, -1, 1, 2)) for (dc in listOf(-2, -1, 1, 2)) {
            if (abs(dr) == abs(dc)) continue
            if (hasPiece(row + dr, col + dc, 'N', enemy)) return true
        }
        for ((dr, dc) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
            for (step in 1..7) {
                val r = row + dr * step; val c = col + dc * step
                if (r !in 0..7 || c !in 0..7) break
                val p = board[r * 8 + c]
                if (p == null) continue
                if ((p.uppercaseChar() == 'R' || p.uppercaseChar() == 'Q') && p.isUpperCase() == enemy) return true
                break
            }
        }
        for ((dr, dc) in listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)) {
            for (step in 1..7) {
                val r = row + dr * step; val c = col + dc * step
                if (r !in 0..7 || c !in 0..7) break
                val p = board[r * 8 + c]
                if (p == null) continue
                if ((p.uppercaseChar() == 'B' || p.uppercaseChar() == 'Q') && p.isUpperCase() == enemy) return true
                break
            }
        }
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            if (hasPiece(row + dr, col + dc, 'K', enemy)) return true
        }
        return false
    }

    private fun formatEval(cp: Int?, mate: Int?): String? = when {
        mate != null -> if (mate > 0) "M$mate" else "-M${-mate}"
        cp != null -> "%+.1f".format(cp / 100.0)
        else -> null
    }

    companion object {
        /** Public: the UCI move that turns [fenBefore] into [fenAfter] (for live single-move classification). */
        fun playedUci(fenBefore: String, fenAfter: String): String? =
            playedMoveUci(fenBefore, fenAfter, whiteToMove(fenBefore))

        private fun whiteToMove(fen: String): Boolean = fen.split(" ").getOrNull(1) != "b"

        private fun sqName(idx: Int): String = "${'a' + (idx % 8)}${8 - idx / 8}"

        /** Parse the placement field of a FEN into a 64-cell array. */
        private fun parseBoard(fen: String): Array<Char?> {
            val board = arrayOfNulls<Char>(64)
            val rows = fen.substringBefore(' ').split("/")
            for (r in 0 until 8) {
                var c = 0
                for (ch in rows.getOrElse(r) { "" }) {
                    if (ch.isDigit()) c += ch - '0' else { if (c < 8) board[r * 8 + c] = ch; c++ }
                }
            }
            return board
        }

        /** Reconstruct the played UCI move by diffing two position FENs. */
        private fun playedMoveUci(fenA: String, fenB: String, moverWhite: Boolean): String? {
            val a = parseBoard(fenA); val b = parseBoard(fenB)
            val vacated = ArrayList<Int>(); val filled = ArrayList<Int>()
            for (s in 0 until 64) {
                if (a[s] == b[s]) continue
                val aMover = a[s]?.let { it.isUpperCase() == moverWhite } ?: false
                val bMover = b[s]?.let { it.isUpperCase() == moverWhite } ?: false
                if (aMover && !bMover) vacated.add(s)
                if (bMover && !aMover) filled.add(s)
            }
            if (vacated.isEmpty() || filled.isEmpty()) return null
            val from = if (vacated.size > 1) vacated.firstOrNull { a[it]?.uppercaseChar() == 'K' } ?: vacated[0] else vacated[0]
            val to = if (filled.size > 1) filled.firstOrNull { b[it]?.uppercaseChar() == 'K' } ?: filled[0] else filled[0]
            val promo = if (a[from]?.uppercaseChar() == 'P' && b[to]?.uppercaseChar() != 'P')
                b[to]?.lowercaseChar()?.toString() ?: "" else ""
            return sqName(from) + sqName(to) + promo
        }
    }
}
