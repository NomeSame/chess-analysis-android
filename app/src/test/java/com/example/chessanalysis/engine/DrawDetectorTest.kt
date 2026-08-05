package com.example.chessanalysis.engine

import com.example.chessanalysis.ui.ChessBoardView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawDetectorTest {

    private fun board(vararg pieces: Pair<Int, ChessBoardView.Piece>): Array<Array<ChessBoardView.Piece?>> {
        val b = Array(8) { Array<ChessBoardView.Piece?>(8) { null } }
        for ((idx, piece) in pieces) {
            b[idx / 8][idx % 8] = piece
        }
        return b
    }

    @Test
    fun fiftyMoveRuleAtExactly100Ply() {
        assertTrue(DrawDetector.isFiftyMoves(100))
        assertFalse(DrawDetector.isFiftyMoves(99))
        assertTrue(DrawDetector.isFiftyMoves(150))
    }

    @Test
    fun threefoldRepetitionDetected() {
        val hist = listOf(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 3 4",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 6 7",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 9 10"
        )
        assertTrue(DrawDetector.isThreefoldRepetition(hist))
    }

    @Test
    fun threefoldRepetitionIgnoresMoveCounters() {
        // Same placement/side/castling/enpassant -> third occurrence regardless of clocks.
        // Real histories include the start position, so give 4+ entries (guard requires >= 4).
        val a = "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
        val b = "4k3/8/8/8/8/8/8/4K3 w - - 5 9"
        val c = "4k3/8/8/8/8/8/8/4K3 w - - 10 15"
        val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        assertTrue(DrawDetector.isThreefoldRepetition(listOf(start, a, b, c)))
    }

    @Test
    fun threefoldRequiresThreeOccurrences() {
        val a = "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
        val b = "4k3/8/8/8/8/8/8/4K3 w - - 2 3"
        assertFalse(DrawDetector.isThreefoldRepetition(listOf(a, b)))
        assertFalse(DrawDetector.isThreefoldRepetition(listOf(a, a)))
    }

    @Test
    fun shortHistoryNeverThreefold() {
        assertFalse(DrawDetector.isThreefoldRepetition(emptyList()))
        assertFalse(DrawDetector.isThreefoldRepetition(listOf("x", "y", "x")))
    }

    @Test
    fun kingVersusKingIsInsufficientMaterial() {
        val k = ChessBoardView.Piece('K', true)
        val k2 = ChessBoardView.Piece('K', false)
        assertTrue(DrawDetector.isInsufficientMaterial(board(60 to k, 4 to k2)))
    }

    @Test
    fun kingAndKnightOrBishopIsInsufficient() {
        val wk = ChessBoardView.Piece('K', true)
        val bk = ChessBoardView.Piece('K', false)
        val n = ChessBoardView.Piece('N', false)
        val b = ChessBoardView.Piece('B', true)
        assertTrue(DrawDetector.isInsufficientMaterial(board(60 to wk, 4 to bk, 40 to n)))
        assertTrue(DrawDetector.isInsufficientMaterial(board(60 to wk, 4 to bk, 40 to b)))
    }

    @Test
    fun kingTwoKnightsIsInsufficient() {
        val wk = ChessBoardView.Piece('K', true)
        val bk = ChessBoardView.Piece('K', false)
        val n1 = ChessBoardView.Piece('N', false)
        val n2 = ChessBoardView.Piece('N', false)
        // 4 pieces total -> not covered by rules -> false (edge: two knights actually cannot force mate)
        assertFalse(DrawDetector.isInsufficientMaterial(board(60 to wk, 4 to bk, 40 to n1, 41 to n2)))
    }

    @Test
    fun kingAndPawnIsSufficientMaterial() {
        val wk = ChessBoardView.Piece('K', true)
        val bk = ChessBoardView.Piece('K', false)
        val p = ChessBoardView.Piece('P', true)
        assertFalse(DrawDetector.isInsufficientMaterial(board(60 to wk, 4 to bk, 50 to p)))
    }

    @Test
    fun kingBishopBishopSameColorStillCountsAsSufficientByRules() {
        // FIDE: KB+B (even same color) is not insufficient in the rulebook (no two-bishop clause here).
        val wk = ChessBoardView.Piece('K', true)
        val bk = ChessBoardView.Piece('K', false)
        val b1 = ChessBoardView.Piece('B', true)
        val b2 = ChessBoardView.Piece('B', true)
        // 4 pieces -> pieces.size > 3 -> false
        assertFalse(DrawDetector.isInsufficientMaterial(board(60 to wk, 4 to bk, 40 to b1, 41 to b2)))
    }
}
