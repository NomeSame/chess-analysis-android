package com.example.chessanalysis

/**
 * Turns raw per-position engine evaluations (from [LiveAnalyzer.evaluatePositions]) into a
 * Chess.com-style game review: a class per played move, per-side counts + accuracy, and the
 * white-relative eval curve for the chart. Pure logic — no engine/Android dependency.
 */
object GameReviewer {

    /** Result of reviewing a full game. [perPly] index = ply (0-based, move that produced position i+1). */
    data class GameReview(
        val perPly: List<MoveClass>,
        val evalWhitePov: List<Int>,                       // one per position (0..n), cp from White's POV
        val counts: Map<Boolean, Map<MoveClass, Int>>,     // key: mover-is-white
        val accuracy: Map<Boolean, Double>                 // key: mover-is-white, 0..100
    )

    private const val MATE_CP = 2000  // chart magnitude for a mate score

    /**
     * @param fens  position FENs, index 0 = start, last = final position (size n+1 for n plies)
     * @param lines per-position rank-sorted MultiPV lines (same length as [fens])
     */
    fun review(fens: List<String>, lines: List<List<LiveAnalyzer.PvLine>>): GameReview {
        val n = minOf(fens.size, lines.size)
        val perPly = ArrayList<MoveClass>()
        val evalWhitePov = ArrayList<Int>(n)
        val counts = hashMapOf(true to hashMapOf<MoveClass, Int>(), false to hashMapOf<MoveClass, Int>())
        val accSum = hashMapOf(true to 0.0, false to 0.0)
        val accCnt = hashMapOf(true to 0, false to 0)

        // White-POV eval curve (carry the last known value across terminal/empty positions).
        var lastWhite = 0
        for (i in 0 until n) {
            val top = lines[i].firstOrNull { it.rank == 1 }
            lastWhite = top?.let { whitePov(fens[i], it.cp, it.mate) } ?: lastWhite
            evalWhitePov.add(lastWhite)
        }

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
                secondCp = second?.cp, secondMate = second?.mate,
                playedMoveUci = playedUci, playedCp = playedCp, playedMate = playedMate
            )

            val sacrifice = isSacrifice(fens, i, moverWhite)
            val cls = MoveClass.classify(info, materialSacrificed = sacrifice)
            perPly.add(cls)
            counts[moverWhite]!!.merge(cls, 1, Int::plus)

            val drop = (MoveClass.evalToWinPct(info.bestCp, info.bestMate) -
                        MoveClass.evalToWinPct(info.playedCp, info.playedMate)).coerceAtLeast(0.0)
            accSum[moverWhite] = accSum[moverWhite]!! + moveAccuracy(drop)
            accCnt[moverWhite] = accCnt[moverWhite]!! + 1
        }

        val accuracy = mapOf(
            true to (accSum[true]!! / accCnt[true]!!.coerceAtLeast(1)),
            false to (accSum[false]!! / accCnt[false]!!.coerceAtLeast(1))
        )
        return GameReview(perPly, evalWhitePov, counts.mapValues { it.value.toMap() }, accuracy)
    }

    /** Per-move accuracy from win% loss (lichess-style curve), clamped to [0,100]. */
    private fun moveAccuracy(winPctDrop: Double): Double =
        (103.1668 * Math.exp(-0.04354 * winPctDrop) - 3.1669).coerceIn(0.0, 100.0)

    // ---- chess helpers (FEN parsing) ----

    private fun whiteToMove(fen: String): Boolean = fen.split(" ").getOrNull(1) != "b"

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

    /** Parse the placement field of a FEN into a 64-cell array, index = row*8+col (row 0 = rank 8, col 0 = file a). */
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

    /** Public: the UCI move that turns [fenBefore] into [fenAfter] (for live single-move classification). */
    fun playedUci(fenBefore: String, fenAfter: String): String? =
        playedMoveUci(fenBefore, fenAfter, whiteToMove(fenBefore))

    private fun sqName(idx: Int): String = "${'a' + (idx % 8)}${8 - idx / 8}"

    /** Reconstruct the played UCI move by diffing two position FENs (handles captures, castling, promotion). */
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
        // Castling moves two of the mover's pieces → use the king's squares.
        val from = if (vacated.size > 1) vacated.firstOrNull { a[it]?.uppercaseChar() == 'K' } ?: vacated[0] else vacated[0]
        val to = if (filled.size > 1) filled.firstOrNull { b[it]?.uppercaseChar() == 'K' } ?: filled[0] else filled[0]
        // Promotion: pawn left, a non-pawn appeared on the target.
        val promo = if (a[from]?.uppercaseChar() == 'P' && b[to]?.uppercaseChar() != 'P')
            b[to]?.lowercaseChar()?.toString() ?: "" else ""
        return sqName(from) + sqName(to) + promo
    }
}
