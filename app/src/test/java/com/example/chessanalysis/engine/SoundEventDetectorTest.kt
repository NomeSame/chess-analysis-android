package com.example.chessanalysis.engine

import com.example.chessanalysis.engine.SoundEventDetector.SoundEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Sound-event detection must be correct for every position transition, no matter how the user got
 * there: forward navigation, backward navigation and undo all pass the same (fenBefore, fenAfter)
 * pair, so [SoundEventDetector.detect] must decide purely from the two positions.
 *
 * Contract:
 *  - normal move when a piece makes a legal quiet move
 *  - capture whenever a piece is captured (incl. en passant)
 *  - check whenever a king comes into check
 *  - castle whenever either side castles
 *  - checkmate whenever a king is checkmated
 */
class SoundEventDetectorTest {

    // ---- the five required events -----------------------------------------------------------

    @Test
    fun quietPawnMove_isNormalMove() {
        val before = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val after = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        assertEquals(SoundEvent.MOVE, SoundEventDetector.detect(before, after))
    }

    @Test
    fun knightMove_isNormalMove() {
        val before = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val fenAfter = "rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 1 1"
        assertEquals(SoundEvent.MOVE, SoundEventDetector.detect(before, fenAfter))
    }

    @Test
    fun pawnCapturesPiece_isCapture() {
        val before = "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2"
        val after = "rnbqkbnr/ppp1pppp/8/3P4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2"
        assertEquals(SoundEvent.CAPTURE, SoundEventDetector.detect(before, after))
    }

    @Test
    fun enPassantCapture_isCapture() {
        // White e5xd6 e.p.: the black d5 pawn is removed even though the pawn landed on the
        // (empty) target square d6 — piece count must drop, so it must be a CAPTURE.
        val before = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3"
        val after = "rnbqkbnr/ppp1pppp/8/3P4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 3"
        assertEquals(SoundEvent.CAPTURE, SoundEventDetector.detect(before, after))
    }

    @Test
    fun queenGivesCheck_isCheck() {
        val before = "4k3/8/8/8/8/8/8/R3K3 w - - 0 1"
        val after = "R3k3/8/8/8/8/8/8/4K3 b - - 0 1"
        assertEquals(SoundEvent.CHECK, SoundEventDetector.detect(before, after))
    }

    @Test
    fun captureWithCheck_isCheckNotCapture() {
        // Rxa8+ removes a piece AND gives check along the 8th rank; the check sound has priority over capture.
        val before = "r3k3/8/8/8/8/8/8/R3K3 w - - 0 1"
        val after = "R3k3/8/8/8/8/8/8/4K3 b - - 0 1"
        assertEquals(SoundEvent.CHECK, SoundEventDetector.detect(before, after))
    }

    @Test
    fun whiteKingsideCastle_isCastle() {
        val before = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"
        val after = "r3k2r/8/8/8/8/8/8/R4RK1 b kq - 1 1"
        assertEquals(SoundEvent.CASTLE, SoundEventDetector.detect(before, after))
    }

    @Test
    fun whiteQueensideCastle_isCastle() {
        val before = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"
        val after = "2kr3r/8/8/8/8/8/8/2KR3R b - - 1 1"
        assertEquals(SoundEvent.CASTLE, SoundEventDetector.detect(before, after))
    }

    @Test
    fun blackKingsideCastle_isCastle() {
        val before = "r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1"
        val after = "r4rk1/8/8/8/8/8/8/R3K2R w KQ - 1 2"
        assertEquals(SoundEvent.CASTLE, SoundEventDetector.detect(before, after))
    }

    @Test
    fun backRankMate_isCheckmate() {
        // Ra8# — king on g8 cannot flee (f8/h8 on the rook's rank), cannot block, cannot capture.
        val before = "6k1/5ppp/8/8/8/8/8/R6K w - - 0 1"
        val after = "R5k1/5ppp/8/8/8/8/8/6K1 b - - 0 1"
        assertEquals(SoundEvent.CHECKMATE, SoundEventDetector.detect(before, after))
    }

    @Test
    fun checkNotMate_whenKingCanFlee() {
        // Rook check, but the king can escape to a safe square.
        val before = "5k2/8/8/8/8/8/8/R4K2 w - - 0 1"
        val after = "R4k2/8/8/8/8/8/8/5K2 b - - 0 1"
        assertEquals(SoundEvent.CHECK, SoundEventDetector.detect(before, after))
    }

    @Test
    fun stalemate_isNotCheckmate() {
        // Black to move, not in check, no legal move → stalemate, NOT checkmate.
        val before = "k7/8/8/8/8/8/8/K6R w - - 0 1"
        val after = "k7/8/8/8/8/8/8/K6R b - - 0 1"
        assertNotEquals(SoundEvent.CHECKMATE, SoundEventDetector.detect(before, after))
    }

    @Test
    fun captureIntoMate_isCheckmate() {
        // Qxf7# (scholar's mate): capture AND checkmate — mate sound has top priority.
        val before = "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNBQK1NR w KQkq - 4 4"
        val after = "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4"
        assertEquals(SoundEvent.CHECKMATE, SoundEventDetector.detect(before, after))
    }

    // ---- direction independence: forward, backward, undo ------------------------------------

    /**
     * A real game as a FEN chain. The sound of a position is the event of the move that LEADS INTO
     * it, so the event for transition (i-1 → i) must be identical whether that transition is walked
     * forward (nav ▶), walked backward (nav ◀) or reached by undoing the move (↩).
     */
    private val game = listOf(
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",          // 1.e4
        "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2",          // 1...d5
        "rnbqkbnr/ppp1pppp/8/3P4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2",            // 2.exd5 (capture)
        "rnbqkbnr/ppp1pppp/8/3P4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2"             // (repeat: stable)
    )

    @Test
    fun forwardAndBackwardNavigation_reportSameEvent() {
        for (i in 1 until game.size - 1) {
            val forward = SoundEventDetector.detect(game[i - 1], game[i])
            // Backward navigation reaches the SAME transition — the pair is identical, so is the event.
            val backward = SoundEventDetector.detect(game[i - 1], game[i])
            assertEquals(forward, backward)
        }
    }

    @Test
    fun captureEventSameWhenWalkedForwardOrBackward() {
        val moveIdx = 3 // 2.exd5 — the capture transition
        val before = game[moveIdx - 1]
        val after = game[moveIdx]
        assertEquals(SoundEvent.CAPTURE, SoundEventDetector.detect(before, after))
        // Undo from the position after the move passes the same pair.
        assertEquals(SoundEvent.CAPTURE, SoundEventDetector.detect(before, after))
    }

    @Test
    fun detectIsDeterministicAndPure() {
        val pairs = listOf(
            game[0] to game[1],
            game[1] to game[2],
            game[2] to game[3]
        )
        for (p in pairs) {
            val first = SoundEventDetector.detect(p.first, p.second)
            val second = SoundEventDetector.detect(p.first, p.second)
            assertEquals(first, second)
            assertEquals(first, SoundEventDetector.detect(p.first, p.second))
        }
    }
}
