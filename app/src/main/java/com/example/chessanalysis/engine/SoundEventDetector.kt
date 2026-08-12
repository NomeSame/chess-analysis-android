package com.example.chessanalysis.engine

/**
 * Pure sound-event detector: decides which move sound belongs to the transition
 * [fenBefore] → [fenAfter]. No Android imports — runs in plain JVM unit tests.
 *
 * Contract (matches SoundManager.playMoveSound priority):
 *  - CHECKMATE if the side to move in [fenAfter] is checkmated (always also a check).
 *  - CASTLE if a king moved two files sideways (kingside or queenside, either colour).
 *  - CHECK if the side to move in [fenAfter] is in check.
 *  - CAPTURE if a piece was captured (incl. en passant).
 *  - MOVE otherwise (a quiet, legal move).
 *
 * The result depends ONLY on the two positions, not on how the user arrived at them —
 * forward navigation, backward navigation and undo of the same move all report the
 * same event, because they all pass the same fenBefore/fenAfter pair.
 */
object SoundEventDetector {

    enum class SoundEvent { MOVE, CAPTURE, CASTLE, CHECK, CHECKMATE }

    fun detect(fenBefore: String, fenAfter: String): SoundEvent {
        val before = parseBoard(fenBefore)
        val after = parseBoard(fenAfter)
        val isCapture = after.count { it != null } < before.count { it != null }
        val isCastle = kingShiftedTwoFiles(before, after)
        val moverWhite = whiteToMove(fenAfter)
        val isCheck = isKingAttacked(after, moverWhite)
        val isMate = isCheck && !hasLegalMove(after, moverWhite, epTarget(fenAfter))
        return when {
            isMate -> SoundEvent.CHECKMATE
            isCastle -> SoundEvent.CASTLE
            isCheck -> SoundEvent.CHECK
            isCapture -> SoundEvent.CAPTURE
            else -> SoundEvent.MOVE
        }
    }

    /** A king changed column by at least 2 → castling (no other move does that). */
    private fun kingShiftedTwoFiles(before: Array<Char?>, after: Array<Char?>): Boolean {
        var from = -1; var to = -1
        for (s in 0 until 64) {
            if (before[s] == after[s]) continue
            val beforePiece = before[s]
            val afterPiece = after[s]
            if (beforePiece != null && beforePiece.uppercaseChar() == 'K') from = s
            if (afterPiece != null && afterPiece.uppercaseChar() == 'K') to = s
        }
        return from >= 0 && to >= 0 && kotlin.math.abs(to % 8 - from % 8) >= 2
    }

    // ---- pure FEN position evaluation --------------------------------------------------------

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

    private fun whiteToMove(fen: String): Boolean = fen.split(" ").getOrNull(1) != "b"

    /** En-passant target square from FEN field 4, as 0..63 index, or -1. */
    private fun epTarget(fen: String): Int {
        val ep = fen.split(" ").getOrNull(3) ?: "-"
        if (ep == "-" || ep.length < 2) return -1
        val col = ep[0] - 'a'
        val row = 8 - (ep[1] - '0')
        return if (col in 0..7 && row in 0..7) row * 8 + col else -1
    }

    private fun isKingAttacked(board: Array<Char?>, whiteToMove: Boolean): Boolean {
        val king = if (whiteToMove) 'K' else 'k'
        val idx = board.indexOf(king)
        if (idx < 0) return false
        return isAttacked(board, idx, !whiteToMove)
    }

    /** True if square [sq] is attacked by a piece of [byWhite]. */
    private fun isAttacked(board: Array<Char?>, sq: Int, byWhite: Boolean): Boolean {
        val row = sq / 8; val col = sq % 8
        fun at(r: Int, c: Int): Char? = if (r in 0..7 && c in 0..7) board[r * 8 + c] else null
        fun has(r: Int, c: Int, t: Char): Boolean {
            val p = at(r, c) ?: return false
            return p.uppercaseChar() == t && p.isUpperCase() == byWhite
        }
        val dir = if (byWhite) 1 else -1
        if (has(row + dir, col - 1, 'P') || has(row + dir, col + 1, 'P')) return true
        for (dr in listOf(-2, -1, 1, 2)) for (dc in listOf(-2, -1, 1, 2)) {
            if (kotlin.math.abs(dr) == kotlin.math.abs(dc)) continue
            if (has(row + dr, col + dc, 'N')) return true
        }
        for ((dr, dc) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
            for (step in 1..7) {
                val r = row + dr * step; val c = col + dc * step
                val p = at(r, c) ?: continue
                if ((p.uppercaseChar() == 'R' || p.uppercaseChar() == 'Q') && p.isUpperCase() == byWhite) return true
                break
            }
        }
        for ((dr, dc) in listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)) {
            for (step in 1..7) {
                val r = row + dr * step; val c = col + dc * step
                val p = at(r, c) ?: continue
                if ((p.uppercaseChar() == 'B' || p.uppercaseChar() == 'Q') && p.isUpperCase() == byWhite) return true
                break
            }
        }
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            if (has(row + dr, col + dc, 'K')) return true
        }
        return false
    }

    /** True if [white] has at least one legal move (king safety enforced). */
    private fun hasLegalMove(board: Array<Char?>, white: Boolean, ep: Int): Boolean {
        for (s in 0 until 64) {
            val p = board[s] ?: continue
            if (p.isUpperCase() != white) continue
            for (dest in pseudoDestinations(board, s, white, ep)) {
                val r = dest / 8; val c = dest % 8
                val copied = board.copyOf()
                copied[s] = null
                copied[r * 8 + c] = p
                // En-passant: the captured pawn sits one rank ahead of the target square (towards its
                // own back rank). White captures a pawn that moved 7→5 (captured pawn at ep+8), black
                // captures one that moved 2→4 (captured pawn at ep-8).
                if (ep in 0..63 && p.uppercaseChar() == 'P' && dest == ep) {
                    val captured = ep + (if (white) 8 else -8)
                    if (captured in 0..63) copied[captured] = null
                }
                if (!isKingAttacked(copied, white)) return true
            }
        }
        return false
    }

    /** Destinations a piece on [sq] can reach, without king-safety filtering. */
    private fun pseudoDestinations(board: Array<Char?>, sq: Int, white: Boolean, ep: Int): List<Int> {
        val p = board[sq] ?: return emptyList()
        val row = sq / 8; val col = sq % 8
        val out = ArrayList<Int>()
        fun emptyOrEnemy(r: Int, c: Int): Boolean {
            if (r !in 0..7 || c !in 0..7) return false
            val q = board[r * 8 + c] ?: return true
            return q.isUpperCase() != white
        }
        fun add(r: Int, c: Int) { if (emptyOrEnemy(r, c)) out.add(r * 8 + c) }
        fun ray(dr: Int, dc: Int) {
            for (step in 1..7) {
                val r = row + dr * step; val c = col + dc * step
                if (r !in 0..7 || c !in 0..7) break
                val q = board[r * 8 + c]
                if (q != null) {
                    if (q.isUpperCase() != white) out.add(r * 8 + c)
                    break
                }
                out.add(r * 8 + c)
            }
        }
        when (p.uppercaseChar()) {
            'P' -> {
                val dir = if (white) -1 else 1
                val startRow = if (white) 6 else 1
                val oneRow = row + dir
                if (oneRow in 0..7 && board[oneRow * 8 + col] == null) {
                    out.add(oneRow * 8 + col)
                    if (row == startRow && board[(row + 2 * dir) * 8 + col] == null) out.add((row + 2 * dir) * 8 + col)
                }
                for (dc in listOf(-1, 1)) {
                    val c = col + dc
                    if (oneRow in 0..7 && c in 0..7) {
                        val target = board[oneRow * 8 + c]
                        if (target != null && target.isUpperCase() != white) out.add(oneRow * 8 + c)
                        else if (target == null && oneRow * 8 + c == ep) out.add(ep)
                    }
                }
            }
            'N' -> for (dr in listOf(-2, -1, 1, 2)) for (dc in listOf(-2, -1, 1, 2)) {
                if (kotlin.math.abs(dr) == kotlin.math.abs(dc)) continue
                add(row + dr, col + dc)
            }
            'K' -> for (dr in -1..1) for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                add(row + dr, col + dc)
            }
            'R' -> for (d in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) ray(d.first, d.second)
            'B' -> for (d in listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)) ray(d.first, d.second)
            'Q' -> {
                for (d in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) ray(d.first, d.second)
                for (d in listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)) ray(d.first, d.second)
            }
        }
        return out
    }
}
