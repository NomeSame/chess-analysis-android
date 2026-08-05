package com.example.chessanalysis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ChessBoardViewTest {

    private lateinit var view: ChessBoardView

    @Before
    fun setup() {
        view = ChessBoardView(RuntimeEnvironment.getApplication())
    }

    private fun setFen(fen: String) {
        view.setFen(fen)
    }

    @Test
    fun setFenThenGetFenRoundTrips() {
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        setFen(fen)
        assertEquals(fen, view.getFen())
    }

    @Test
    fun emptyBoardFenRoundTrips() {
        val fen = "8/8/8/8/8/8/8/8 w - - 0 1"
        setFen(fen)
        assertEquals(fen, view.getFen())
    }

    @Test
    fun makeMoveUpdatesBoardAndSideToMove() {
        setFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        view.makeMove(6, 4, 4, 4) // e2-e4
        val fen = view.getFen()
        assertTrue(fen.startsWith("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR"))
        assertTrue(fen.contains(" b "))
    }

    @Test
    fun generateLegalMovesForCentralWhitePawn() {
        setFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        // e2 = row 6, col 4
        val moves = view.generateLegalMoves(6, 4)
        assertTrue(moves.contains(4 to 4))  // e4
        assertTrue(moves.contains(5 to 4))  // e3
        assertEquals(2, moves.size)
    }

    @Test
    fun knightHasEightLegalMovesFromStart() {
        setFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        // g1 = row 7, col 6
        assertEquals(2, view.generateLegalMoves(7, 6).size) // f3, h3 only
    }

    @Test
    fun kingCannotMoveIntoCheck() {
        // White king e1, black rook e8 attacking down the e-file.
        setFen("4r3/8/8/8/8/8/8/4K3 w - - 0 1")
        val moves = view.generateLegalMoves(7, 4) // e1
        // e2 blocked by attack; d1/d2/f1/f2 legal (rook only covers e-file + 8th rank)
        assertFalse(moves.contains(6 to 4)) // e2 NOT legal
        assertTrue(moves.contains(7 to 3)) // d1 legal
        assertTrue(moves.contains(6 to 3)) // d2
        assertTrue(moves.contains(7 to 5)) // f1
        assertTrue(moves.contains(6 to 5)) // f2
    }

    @Test
    fun pinnedPieceCannotMoveOffPinningLine() {
        // White rook e2 is pinned to king e1 by black rook e8. It may only move on the e-file
        // between them (e3..e7) or capture the pinner (e8); lateral moves leave the king exposed.
        setFen("4r3/8/8/8/8/8/4R3/4K3 w - - 0 1")
        val moves = view.generateLegalMoves(6, 4) // e2 rook
        assertFalse(moves.contains(6 to 3)) // d2 illegal (leaves pin line)
        assertFalse(moves.contains(6 to 5)) // f2 illegal
        assertTrue(moves.contains(0 to 4)) // e8 capture legal (removes the pinner)
        assertTrue(moves.contains(5 to 4)) // e3 legal
        assertTrue(moves.contains(3 to 4)) // e5 legal
        assertTrue(moves.contains(1 to 4)) // e7 legal
    }

    @Test
    fun castlingKingsideMovesRook() {
        setFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQK2R w KQkq - 0 1")
        // White king e1 with empty f1,g1; rook h1.
        val moves = view.generateLegalMoves(7, 4)
        assertTrue(moves.contains(7 to 6)) // O-O available
        view.makeMove(7, 4, 7, 6)
        // King on g1, rook on f1.
        assertTrue(view.getFen().startsWith("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQ1RK1"))
    }

    @Test
    fun enPassantCaptureDetected() {
        // White pawn e5, black pawn d5 just double-stepped (ep square d6).
        setFen("rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3")
        val moves = view.generateLegalMoves(3, 4) // e5 pawn
        assertTrue(moves.contains(2 to 3)) // exd6 ep capture
    }

    @Test
    fun checkmateDetected() {
        // Scholar's mate final position: black king e8 in check from Qf7, no escape.
        setFen("r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4")
        assertTrue(view.isCheckmate())
        assertFalse(view.isStalemate())
    }

    @Test
    fun stalemateDetected() {
        // Classic stalemate: black king a8, white queen b6, black to move (a8 can't move, not in check).
        setFen("k7/8/1Q6/8/8/8/8/K7 b - - 0 1")
        assertTrue(view.isStalemate())
        assertFalse(view.isCheckmate())
    }

    @Test
    fun checkButNotMateIsNeitherCheckmateNorStalemate() {
        // White rook gives check on e-file but black king can escape.
        setFen("4k3/8/8/8/8/8/4R3/4K3 b - - 0 1")
        assertFalse(view.isCheckmate())
        assertFalse(view.isStalemate())
        assertTrue(view.isInCheck(false))
    }

    @Test
    fun computeCastlingRightsFromStart() {
        setFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        assertEquals("KQkq", view.computeCastlingRights())
    }

    @Test
    fun computeCastlingRightsEmptyBoard() {
        setFen("8/8/8/8/8/8/8/8 w - - 0 1")
        assertEquals("", view.computeCastlingRights())
    }

    @Test
    fun promotionUsesDefaultQueen() {
        setFen("4k3/6P1/8/8/8/8/8/4K3 w - - 0 1")
        view.makeMove(1, 6, 0, 6) // g7-g8
        val fen = view.getFen()
        assertTrue(fen.startsWith("4k1Q1/8/8/8/8/8/8/4K3"))
    }

    @Test
    fun halfMoveClockResetsOnPawnMove() {
        setFen("4k3/8/8/8/8/8/8/4K3 w - - 40 10")
        // no pawn moves here; move king instead
        view.makeMove(7, 4, 7, 3)
        assertTrue(view.getFen().contains(" 41 "))
    }
}
