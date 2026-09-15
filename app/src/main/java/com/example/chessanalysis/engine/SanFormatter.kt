package com.example.chessanalysis.engine

import kotlin.math.abs

/** Converts one verified legal FEN transition to Standard Algebraic Notation. */
object SanFormatter {
    fun fromPositions(fenBefore: String, fenAfter: String): String? {
        val before = Position.parse(fenBefore) ?: return null
        val after = Position.parse(fenAfter) ?: return null
        val uci = GameReviewer.playedUci(fenBefore, fenAfter) ?: return null
        if (uci.length !in 4..5) return null
        val from = squareIndex(uci.substring(0, 2)) ?: return null
        val to = squareIndex(uci.substring(2, 4)) ?: return null
        val piece = before.board[from] ?: return null
        if (piece.isUpperCase() != before.whiteToMove) return null
        val promotion = uci.getOrNull(4)?.uppercaseChar()
        if (to !in before.legalMovesFrom(from)) return null
        val applied = before.apply(from, to, promotion) ?: return null
        if (!applied.board.contentEquals(after.board) || after.whiteToMove == before.whiteToMove) return null

        val type = piece.uppercaseChar()
        val san = if (type == 'K' && abs(from % 8 - to % 8) == 2) {
            if (to % 8 == 6) "O-O" else "O-O-O"
        } else {
            buildString {
                if (type != 'P') append(type)
                val alternatives = before.board.indices.filter { candidate ->
                    candidate != from &&
                        before.board[candidate]?.uppercaseChar() == type &&
                        before.board[candidate]?.isUpperCase() == before.whiteToMove &&
                        to in before.legalMovesFrom(candidate)
                }
                if (type != 'P' && alternatives.isNotEmpty()) {
                    val sameFile = alternatives.any { it % 8 == from % 8 }
                    val sameRank = alternatives.any { it / 8 == from / 8 }
                    when {
                        !sameFile -> append(fileChar(from))
                        !sameRank -> append(rankChar(from))
                        else -> { append(fileChar(from)); append(rankChar(from)) }
                    }
                }
                val capture = before.board[to] != null ||
                    (type == 'P' && from % 8 != to % 8 && before.enPassant == to)
                if (type == 'P' && capture) append(fileChar(from))
                if (capture) append('x')
                append(squareName(to))
                if (type == 'P' && to / 8 in listOf(0, 7)) {
                    val promoted = promotion ?: after.board[to]?.uppercaseChar() ?: return null
                    if (promoted !in "QRBN") return null
                    append('=').append(promoted)
                }
            }
        }

        val inCheck = after.isInCheck(after.whiteToMove)
        return san + when {
            inCheck && !after.hasAnyLegalMove() -> "#"
            inCheck -> "+"
            else -> ""
        }
    }

    private fun squareIndex(name: String): Int? {
        if (name.length != 2 || name[0] !in 'a'..'h' || name[1] !in '1'..'8') return null
        return (8 - (name[1] - '0')) * 8 + (name[0] - 'a')
    }

    private fun squareName(index: Int): String = "${fileChar(index)}${rankChar(index)}"
    private fun fileChar(index: Int): Char = 'a' + index % 8
    private fun rankChar(index: Int): Char = '8' - index / 8

    private data class Position(
        val board: Array<Char?>,
        val whiteToMove: Boolean,
        val castling: String,
        val enPassant: Int?
    ) {
        fun legalMovesFrom(from: Int): Set<Int> {
            val piece = board.getOrNull(from) ?: return emptySet()
            if (piece.isUpperCase() != whiteToMove) return emptySet()
            return pseudoMoves(from).filterTo(linkedSetOf()) { to ->
                apply(from, to, null)?.isInCheck(whiteToMove) == false
            }
        }

        fun hasAnyLegalMove(): Boolean = board.indices.any { legalMovesFrom(it).isNotEmpty() }

        fun isInCheck(white: Boolean): Boolean {
            val king = board.indexOf(if (white) 'K' else 'k')
            return king >= 0 && isAttacked(king, !white)
        }

        fun apply(from: Int, to: Int, promotion: Char?): Position? {
            val piece = board.getOrNull(from) ?: return null
            val next = board.copyOf()
            val type = piece.uppercaseChar()
            if (type == 'P' && to == enPassant && board[to] == null && from % 8 != to % 8) {
                next[from / 8 * 8 + to % 8] = null
            }
            next[from] = null
            next[to] = if (type == 'P' && to / 8 in listOf(0, 7)) {
                val promoted = promotion ?: 'Q'
                if (promoted !in "QRBN") return null
                if (piece.isUpperCase()) promoted else promoted.lowercaseChar()
            } else piece
            if (type == 'K' && abs(from % 8 - to % 8) == 2) {
                val row = from / 8
                val kingSide = to % 8 == 6
                val rookFrom = row * 8 + if (kingSide) 7 else 0
                val rookTo = row * 8 + if (kingSide) 5 else 3
                next[rookTo] = next[rookFrom]
                next[rookFrom] = null
            }
            return Position(next, !whiteToMove, castling, null)
        }

        private fun pseudoMoves(from: Int): Set<Int> {
            val piece = board[from] ?: return emptySet()
            val white = piece.isUpperCase()
            val row = from / 8
            val col = from % 8
            val moves = linkedSetOf<Int>()
            fun add(rowTo: Int, colTo: Int) {
                if (rowTo !in 0..7 || colTo !in 0..7) return
                val target = board[rowTo * 8 + colTo]
                if (target == null || target.isUpperCase() != white) moves.add(rowTo * 8 + colTo)
            }
            fun slide(directions: List<Pair<Int, Int>>) {
                directions.forEach { (dr, dc) ->
                    var r = row + dr
                    var c = col + dc
                    while (r in 0..7 && c in 0..7) {
                        val target = board[r * 8 + c]
                        if (target == null) moves.add(r * 8 + c) else {
                            if (target.isUpperCase() != white) moves.add(r * 8 + c)
                            break
                        }
                        r += dr
                        c += dc
                    }
                }
            }
            when (piece.uppercaseChar()) {
                'P' -> {
                    val direction = if (white) -1 else 1
                    val nextRow = row + direction
                    if (nextRow in 0..7 && board[nextRow * 8 + col] == null) {
                        moves.add(nextRow * 8 + col)
                        val startRow = if (white) 6 else 1
                        val doubleRow = row + direction * 2
                        if (row == startRow && board[doubleRow * 8 + col] == null) moves.add(doubleRow * 8 + col)
                    }
                    for (dc in listOf(-1, 1)) {
                        val c = col + dc
                        if (nextRow !in 0..7 || c !in 0..7) continue
                        val targetIndex = nextRow * 8 + c
                        val target = board[targetIndex]
                        if ((target != null && target.isUpperCase() != white) || enPassant == targetIndex) {
                            moves.add(targetIndex)
                        }
                    }
                }
                'N' -> listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
                    .forEach { (dr, dc) -> add(row + dr, col + dc) }
                'B' -> slide(DIAGONALS)
                'R' -> slide(STRAIGHTS)
                'Q' -> slide(DIAGONALS + STRAIGHTS)
                'K' -> {
                    for (dr in -1..1) for (dc in -1..1) if (dr != 0 || dc != 0) add(row + dr, col + dc)
                    addCastlingMoves(from, white, moves)
                }
            }
            return moves
        }

        private fun addCastlingMoves(from: Int, white: Boolean, moves: MutableSet<Int>) {
            val row = if (white) 7 else 0
            if (from != row * 8 + 4 || isAttacked(from, !white)) return
            val kingSide = if (white) 'K' else 'k'
            if (kingSide in castling && board[row * 8 + 5] == null && board[row * 8 + 6] == null &&
                board[row * 8 + 7] == (if (white) 'R' else 'r') &&
                !isAttacked(row * 8 + 5, !white) && !isAttacked(row * 8 + 6, !white)) {
                moves.add(row * 8 + 6)
            }
            val queenSide = if (white) 'Q' else 'q'
            if (queenSide in castling && board[row * 8 + 1] == null && board[row * 8 + 2] == null &&
                board[row * 8 + 3] == null && board[row * 8] == (if (white) 'R' else 'r') &&
                !isAttacked(row * 8 + 3, !white) && !isAttacked(row * 8 + 2, !white)) {
                moves.add(row * 8 + 2)
            }
        }

        private fun isAttacked(square: Int, byWhite: Boolean): Boolean {
            val row = square / 8
            val col = square % 8
            val pawnRow = row + if (byWhite) 1 else -1
            for (dc in listOf(-1, 1)) if (pieceAt(pawnRow, col + dc) == if (byWhite) 'P' else 'p') return true
            for ((dr, dc) in KNIGHT_STEPS) if (pieceAt(row + dr, col + dc) == if (byWhite) 'N' else 'n') return true
            for (dr in -1..1) for (dc in -1..1) {
                if ((dr != 0 || dc != 0) && pieceAt(row + dr, col + dc) == if (byWhite) 'K' else 'k') return true
            }
            return attackedBySlider(row, col, byWhite, DIAGONALS, "BQ") ||
                attackedBySlider(row, col, byWhite, STRAIGHTS, "RQ")
        }

        private fun attackedBySlider(
            row: Int,
            col: Int,
            byWhite: Boolean,
            directions: List<Pair<Int, Int>>,
            types: String
        ): Boolean {
            directions.forEach { (dr, dc) ->
                var r = row + dr
                var c = col + dc
                while (r in 0..7 && c in 0..7) {
                    val piece = board[r * 8 + c]
                    if (piece != null) {
                        if (piece.isUpperCase() == byWhite && piece.uppercaseChar() in types) return true
                        break
                    }
                    r += dr
                    c += dc
                }
            }
            return false
        }

        private fun pieceAt(row: Int, col: Int): Char? =
            if (row in 0..7 && col in 0..7) board[row * 8 + col] else null

        companion object {
            private val DIAGONALS = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
            private val STRAIGHTS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
            private val KNIGHT_STEPS = listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)

            fun parse(fen: String): Position? {
                val fields = fen.trim().split(Regex("\\s+"))
                if (fields.size < 2 || fields[1] !in listOf("w", "b")) return null
                val rows = fields[0].split('/')
                if (rows.size != 8) return null
                val board = arrayOfNulls<Char>(64)
                for (row in 0..7) {
                    var col = 0
                    for (char in rows[row]) {
                        if (char.isDigit()) col += char.digitToInt() else {
                            if (char !in "pnbrqkPNBRQK" || col !in 0..7) return null
                            board[row * 8 + col++] = char
                        }
                    }
                    if (col != 8) return null
                }
                val castling = fields.getOrElse(2) { "-" }.let { if (it == "-") "" else it }
                val ep = fields.getOrNull(3)?.takeUnless { it == "-" }?.let { squareIndex(it) }
                return Position(board, fields[1] == "w", castling, ep)
            }
        }
    }
}
