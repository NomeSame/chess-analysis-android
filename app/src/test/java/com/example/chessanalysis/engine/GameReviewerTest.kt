package com.example.chessanalysis.engine

import com.example.chessanalysis.model.MoveClass
import com.example.chessanalysis.model.TacticKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameReviewerTest {

    private val reviewer = GameReviewer(null)

    private fun pv(rank: Int, cp: Int? = null, mate: Int? = null, first: String? = null): LiveAnalyzer.PvLine =
        LiveAnalyzer.PvLine(rank, cp, mate, first, listOfNotNull(first), 22)

    @Test
    fun `empty game yields empty review`() {
        val r = reviewer.review(emptyList(), emptyList())
        assertTrue(r.perPly.isEmpty())
        assertTrue(r.evalWhitePov.isEmpty())
    }

    @Test
    fun `single move played eval is negated to mover pov`() {
        // Position 0: white to move, best = cp +100 (white better). Played move lands on position 1
        // where best for black = cp -200 (i.e. from black POV +200 = white worse) -> mover POV after move = -(-200)? 
        // after.cp is from BLACK's POV (black to move in pos 1) = -200 means white better by 200.
        // playedCp = -(-200) = 200 -> white gained. Then cpLoss = best(100) - played(200) clamped to 0.
        val fens = listOf(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        )
        val lines = listOf(
            listOf(pv(1, cp = 100, first = "e2e4")),
            listOf(pv(1, cp = -200, first = "e7e5"))
        )
        val r = reviewer.review(fens, lines)
        assertEquals(1, r.perPly.size)
        assertEquals(0, r.cpLosses[0].toLong())
        // Eval curve: position 0 white POV = +100; position 1 white POV = whiteToMove? no -> -1 * (-200) = 200.
        assertEquals(100, r.evalWhitePov[0].toLong())
        assertEquals(200, r.evalWhitePov[1].toLong())
    }

    @Test
    fun `checkmate delivery sets playedMate zero`() {
        // Scholar's mate: 4.Qxf7# — fens[1] is the checkmate position (black to move, king in check, no moves).
        val fens = listOf(
            "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNBQK1NR w KQkq - 4 4",
            "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4"
        )
        // Position 1 has NO engine lines (terminal) -> reviewer must detect mate itself.
        val lines = listOf(
            listOf(pv(1, mate = 1, first = "d1h5"), pv(2, cp = 0, first = "g8f6")),
            emptyList()
        )
        val r = reviewer.review(fens, lines)
        assertEquals(1, r.perPly.size)
        val cls = r.perPly[0]
        assertTrue(
            "mate should be at least GREAT, was $cls",
            cls == MoveClass.GREAT || cls == MoveClass.BEST || cls == MoveClass.BRILLIANT
        )
        assertEquals(0, r.cpLosses[0].toLong())
    }

    @Test
    fun `stalemate does not produce mate classification`() {
        // Real stalemate: black king h8, white queen g6, black to move, not in check, no legal moves.
        val stalemate = "7k/8/8/6Q1/8/8/8/4K3 b - - 0 1"
        val fens = listOf(
            "7k/8/8/6Q1/8/8/8/4K3 w - - 0 1",
            stalemate
        )
        // Terminal position (no engine lines) -> reviewer's isCheckmateFen must say "not mate" (false/stalemate).
        val lines = listOf(
            listOf(pv(1, cp = 100, first = "g6g7")),
            emptyList()
        )
        val r = reviewer.review(fens, lines)
        assertEquals(1, r.perPly.size)
        // Stalemate path: playedCp=0, playedMate=null -> NOT mate classification (not GREAT/BEST).
        assertTrue(r.perPly[0] != MoveClass.GREAT)
        // best was +100 (win%), stalemate = 0 cp -> loss of 100 cp.
        assertEquals(100, r.cpLosses[0].toLong())
    }

    @Test
    fun `accuracy decreases with bigger winPct drop`() {
        val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val pos2 = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        // Small drop (best 100, played 90): high accuracy
        val small = reviewer.review(
            listOf(start, pos2),
            listOf(listOf(pv(1, cp = 100, first = "e2e4")), listOf(pv(1, cp = -90, first = "e7e5")))
        )
        // Large drop (best 100, played -900): low accuracy
        val large = reviewer.review(
            listOf(start, pos2),
            listOf(listOf(pv(1, cp = 100, first = "e2e4")), listOf(pv(1, cp = 900, first = "e7e5")))
        )
        assertTrue(small.accuracy[true]!! > large.accuracy[true]!!)
        assertTrue(small.accuracy[true]!! in 0.0..100.0)
        assertTrue(large.accuracy[true]!! in 0.0..100.0)
    }

    @Test
    fun `playedMoveUci reconstructs a normal move`() {
        val before = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val after = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        assertEquals("e2e4", GameReviewer.playedUci(before, after))
    }

    @Test
    fun `playedMoveUci detects promotion`() {
        val before = "4k3/6P1/8/8/8/8/8/4K3 w - - 0 1"
        val after = "4k1Q1/8/8/8/8/8/8/4K3 b - - 0 1"
        assertEquals("g7g8q", GameReviewer.playedUci(before, after))
    }

    @Test
    fun `detectTactics flags big miss and labels kind`() {
        val fens = listOf(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        )
        // best move wins a queen (cp 900+), played move gives it all back (cpLoss 900)
        val lines = listOf(
            listOf(pv(1, cp = 900, first = "d1h5")),
            listOf(pv(1, cp = 0, first = "e7e6"))
        )
        val base = reviewer.review(fens, lines)
        val tactics = reviewer.detectTactics(base, fens, lines)
        assertEquals(1, tactics.size)
        assertEquals(TacticKind.WIN_QUEEN, tactics[0].kind)
    }

    @Test
    fun `mate score in best line detected as MATE tactic`() {
        val fens = listOf(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 1"
        )
        val lines = listOf(
            listOf(pv(1, mate = 2, first = "d1h5")),
            listOf(pv(1, cp = -50, first = "g8f6"))
        )
        val base = reviewer.review(fens, lines)
        val tactics = reviewer.detectTactics(base, fens, lines)
        assertTrue(tactics.any { it.kind == TacticKind.MATE && it.mateIn == 2 })
    }

    @Test
    fun `played eval sign is converted to mover POV`() {
        // White plays a move; the next position is black to move with best cp = -250 (black POV)
        // meaning white is +250 ahead. Mover POV after the move = -(-250) = +250.
        val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val pos2 = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        val r = reviewer.review(
            listOf(start, pos2),
            listOf(
                listOf(pv(1, cp = 100, first = "e2e4")),
                listOf(pv(1, cp = -250, first = "e7e5"))
            )
        )
        // best cp 100 (mover) → played cp +250 (mover): cpLoss = max(0, 100-250) = 0.
        assertEquals(0, r.cpLosses[0].toLong())
        // Eval curve: position 1 is black-to-move so sign flips: whitePov = -1 * (-250) = +250.
        assertEquals(250, r.evalWhitePov[1].toLong())
    }
}
