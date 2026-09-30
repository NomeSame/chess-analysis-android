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
    fun `review exposes the raw inputs and final class for every played ply`() {
        val before = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val after = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        val review = reviewer.review(
            listOf(before, after),
            listOf(listOf(pv(1, cp = 100, first = "e2e4")), listOf(pv(1, cp = -90, first = "e7e5")))
        )

        val measurement = review.measurements.single()
        assertEquals(before, measurement.fenBefore)
        assertEquals("e2e4", measurement.playedMoveUci)
        assertEquals("e2e4", measurement.bestMoveUci)
        assertEquals(100L, measurement.bestCp?.toLong() ?: -1L)
        assertEquals(90L, measurement.playedCp?.toLong() ?: -1L)
        assertEquals(review.cpLosses.single().toLong(), measurement.cpLoss.toLong())
        assertEquals(review.perPly.single(), measurement.moveClass)
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

    @Test
    fun `Cincinnatusss forced combination marks the three reference brilliants`() {
        val fens = listOf(
            "rn2r1k1/pp2q1pp/2p2p2/2PpNQ2/3P1P2/2b2K2/P5PP/3R1B1R w - - 2 17",
            "rn2r1k1/pp2q1pp/2p2p2/2PpNQ2/3P1P2/2bB1K2/P5PP/3R3R b - - 3 17",
            "rn2r1k1/pp2q2p/2p2pp1/2PpNQ2/3P1P2/2bB1K2/P5PP/3R3R w - - 0 18",
            "rn2r1k1/pp2q2p/2p2pN1/2Pp1Q2/3P1P2/2bB1K2/P5PP/3R3R b - - 0 18",
            "rn2r1k1/pp2q3/2p2pp1/2Pp1Q2/3P1P2/2bB1K2/P5PP/3R3R w - - 0 19",
            "rn2r1k1/pp2q3/2p2pQ1/2Pp4/3P1P2/2bB1K2/P5PP/3R3R b - - 0 19",
            "rn2rk2/pp2q3/2p2pQ1/2Pp4/3P1P2/2bB1K2/P5PP/3R3R w - - 1 20",
            "rn2rk2/pp2q3/2p2pQ1/2Pp4/3P1P2/2bB1K2/P5PP/3RR3 b - - 2 20",
            "rn2rk2/pp2q3/2p2pQ1/2Pp4/3P1P2/3B1K2/P5PP/3Rb3 w - - 0 21",
            "rn2rk2/pp2q3/2p2pQ1/2Pp4/3P1P2/3B1K2/P5PP/4R3 b - - 0 21",
            "rn2rk2/pp3q2/2p2pQ1/2Pp4/3P1P2/3B1K2/P5PP/4R3 w - - 1 22"
        )
        val bd3Line = listOf(
            "f1d3", "g7g6", "e5g6", "e7e3", "f3g4", "b8d7", "f5d7", "h7g6",
            "d3g6", "e8e7", "d7f5", "e3e2", "g4h4", "e2g2", "d1g1", "c3e1",
            "g1e1", "g2f2"
        )
        val knightExchangeLine = listOf("e5g6", "h7g6", "f5g6", "g8f8")
        val lines = listOf(
            listOf(pv(1, cp = 0, first = "f1d3").copy(pv = bd3Line), pv(2, cp = -243, first = "e5g4")),
            listOf(pv(1, cp = 0, first = "g7g6")),
            listOf(
                pv(1, cp = 0, first = "e5g6").copy(pv = knightExchangeLine),
                pv(2, cp = -388, first = "e5g4")
            ),
            listOf(pv(1, cp = 0, first = "h7g6")),
            listOf(pv(1, cp = 0, first = "f5g6")),
            listOf(pv(1, cp = 0, first = "g8f8")),
            listOf(
                pv(1, cp = 0, first = "d1e1").copy(pv = listOf("d1e1", "c3e1", "h1e1")),
                pv(2, cp = 0, first = "h1e1").copy(pv = listOf("h1e1", "c3e1", "d1e1"))
            ),
            listOf(pv(1, cp = 0, first = "c3e1")),
            listOf(
                pv(1, cp = 0, first = "d1e1").copy(pv = listOf("d1e1", "e7e1", "g6f6")),
                pv(2, cp = -336, first = "d1b1")
            ),
            listOf(pv(1, cp = 0, first = "e7e1")),
            listOf(pv(1, cp = 0, first = "g6f6"))
        )

        val review = reviewer.review(fens, lines)

        assertEquals(MoveClass.BRILLIANT, review.perPly[0])  // 17.Bd3
        assertTrue("18.Nxg6 is only a knight-for-two-pawns exchange", review.perPly[2] != MoveClass.BRILLIANT)
        assertEquals(MoveClass.BRILLIANT, review.perPly[6])  // 20.Rhe1
        assertTrue("21.Rxe1 PV sacrifice was not detected: ${review.measurements[8]}", review.measurements[8].materialSacrificed)
        assertEquals(MoveClass.BRILLIANT, review.perPly[8])  // 21.Rxe1
        assertEquals(3, review.perPly.count { it == MoveClass.BRILLIANT })
    }

    @Test
    fun `quiet best knight offer to a pawn can be brilliant when declined`() {
        val before = "3r2k1/pp2bppp/2n5/8/2PpQ3/P5Pq/1P3P2/RN1R2K1 w - - 4 22"
        val after = "3r2k1/pp2bppp/2n5/8/2PpQ3/P1N3Pq/1P3P2/R2R2K1 b - - 5 22"
        val review = reviewer.review(
            listOf(before, after),
            listOf(
                listOf(pv(1, cp = 145, first = "b1c3"), pv(2, cp = 45, first = "b1d2")),
                listOf(pv(1, cp = -137, first = "e7d6"))
            )
        )

        assertTrue(review.measurements.single().materialSacrificed)
        assertEquals(MoveClass.BRILLIANT, review.perPly.single())
    }

    @Test
    fun `defended rook is not a sacrifice merely because queen can capture it`() {
        val before = "2kr1r2/1pp3Q1/p1np1n2/2b1pq2/8/P1NP4/1PP2PPP/R1B2RK1 b - - 0 16"
        val after = "2kr2r1/1pp3Q1/p1np1n2/2b1pq2/8/P1NP4/1PP2PPP/R1B2RK1 w - - 1 17"
        val review = reviewer.review(
            listOf(before, after),
            listOf(
                listOf(pv(1, cp = 645, first = "f8g8"), pv(2, cp = 572, first = "f8h8")),
                listOf(pv(1, cp = -646, first = "g7h6"))
            )
        )

        assertTrue(!review.measurements.single().materialSacrificed)
        assertEquals(MoveClass.BEST, review.perPly.single())
    }

    @Test
    fun `conversion from a full piece material lead is not brilliant`() {
        val before = "3rk1nr/p4ppp/1pn5/b3P1P1/b2P1P2/2P3P1/P3N1B1/R3K2R b KQk - 0 18"
        val after = "3rk1nr/p4ppp/1p6/b3P1P1/b2n1P2/2P3P1/P3N1B1/R3K2R w KQk - 0 19"
        val line = listOf("c6d4", "e2d4", "a5c3", "e1e2", "c3d4")
        val review = reviewer.review(
            listOf(before, after),
            listOf(
                listOf(pv(1, cp = 580, first = "c6d4").copy(pv = line), pv(2, cp = 430, first = "d8d4")),
                listOf(pv(1, cp = -560, first = "e2d4"))
            )
        )

        assertTrue(!review.measurements.single().materialSacrificed)
        assertEquals(MoveClass.BEST, review.perPly.single())
    }

    @Test
    fun `quiet move from equal to winning is not promoted to brilliant`() {
        val before = "rn2rk2/pp3q2/2p2pQ1/2Pp4/3P1P2/3B1K2/P5PP/4R3 w - - 1 22"
        val after = "rn2rk2/pp3q2/2p2p1Q/2Pp4/3P1P2/3B1K2/P5PP/4R3 b - - 2 22"
        val review = reviewer.review(
            listOf(before, after),
            listOf(
                listOf(pv(1, cp = 964, first = "g6h6"), pv(2, cp = 155, first = "g6g7")),
                listOf(pv(1, cp = -1194, first = "f8g8"))
            )
        )

        assertTrue(review.perPly.single() != MoveClass.BRILLIANT)
    }
}
