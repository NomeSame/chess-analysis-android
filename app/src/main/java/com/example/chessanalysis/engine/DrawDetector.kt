package com.example.chessanalysis.engine

import com.example.chessanalysis.ui.ChessBoardView

object DrawDetector {

    fun isFiftyMoves(halfMoveClock: Int): Boolean = halfMoveClock >= 100

    fun isThreefoldRepetition(positionHistory: List<String>): Boolean {
        if (positionHistory.size < 4) return false
        val seen = mutableMapOf<String, Int>()
        for (fen in positionHistory) {
            val parts = fen.split(" ")
            val key = parts.take(4).joinToString(" ")
            seen[key] = (seen[key] ?: 0) + 1
            if (seen[key] == 3) return true
        }
        return false
    }

    fun isInsufficientMaterial(board: Array<Array<ChessBoardView.Piece?>>): Boolean {
        val pieces = mutableListOf<ChessBoardView.Piece>()
        for (row in 0..7) for (col in 0..7) {
            board[row][col]?.let { pieces.add(it) }
        }
        if (pieces.size > 3) return false
        if (pieces.size == 2) {
            val hasKing = pieces.any { it.type == 'K' }
            val hasKing2 = pieces.all { it.type == 'K' }
            return hasKing && hasKing2
        }
        if (pieces.size == 3) {
            val bishops = pieces.count { it.type == 'B' }
            val knights = pieces.count { it.type == 'N' }
            return (bishops == 1 && knights == 0) || (knights == 1 && bishops == 0)
        }
        return false
    }
}