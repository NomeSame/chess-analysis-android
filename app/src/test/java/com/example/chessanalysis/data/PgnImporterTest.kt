package com.example.chessanalysis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PgnImporterTest {

    @Test
    fun `parses simple game with tags and moves`() {
        val pgn = """
            [Event "Test"]
            [White "Alice"]
            [Black "Bob"]
            [Result "1-0"]

            1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 1-0
        """.trimIndent()
        val game = PgnImporter.parse(pgn)
        assertNotNull(game)
        assertEquals(PgnImporter.START_FEN, game!!.startFen)
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6"), game.sanMoves)
        assertEquals("Alice", game.tags["White"])
        assertEquals("Bob", game.tags["Black"])
        assertEquals("1-0", game.tags["Result"])
    }

    @Test
    fun `parses FEN tag into startFen`() {
        val pgn = """
            [FEN "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"]
            [Result "*"]

            1. Nc6 2. Bb5
        """.trimIndent()
        val game = PgnImporter.parse(pgn)
        assertNotNull(game)
        assertEquals("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3", game!!.startFen)
    }

    @Test
    fun `strips variations comments NAGs and move numbers`() {
        val pgn = """
            [White "A"]

            1. e4 (1. d4 d5) e5 {comment with {nested braces} inside} 2. Nf3 $1 Nc6 3. Bb5 (3. Bc4 Bc5) a6
        """.trimIndent()
        val game = PgnImporter.parse(pgn)
        assertNotNull(game)
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6"), game!!.sanMoves)
    }

    @Test
    fun `strips line comments`() {
        val pgn = """
            [White "A"]

            1. e4 e5 ;this is a line comment
            2. Nf3 Nc6
        """.trimIndent()
        val game = PgnImporter.parse(pgn)
        assertNotNull(game)
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6"), game!!.sanMoves)
    }

    @Test
    fun `handles ellipsis move numbers for black`() {
        val pgn = """
            1. e4 e5 2. Nf3 Nc6 3... a6 4. Bb5
        """.trimIndent()
        val game = PgnImporter.parse(pgn)
        assertNotNull(game)
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6", "a6", "Bb5"), game!!.sanMoves)
    }

    @Test
    fun `empty or comment-only pgn returns null`() {
        assertNull(PgnImporter.parse(""))
        assertNull(PgnImporter.parse("   \n\n  "))
        assertNull(PgnImporter.parse("[White \"A\"]\n\n"))
    }

    @Test
    fun `result token not included as a move`() {
        val pgn = "1. e4 e5 0-1"
        val game = PgnImporter.parse(pgn)
        assertNotNull(game)
        assertEquals(listOf("e4", "e5"), game!!.sanMoves)
        assertEquals("0-1", game.tags["Result"])
    }

    @Test
    fun `castling checkmate and pawn promotion sans survive`() {
        val pgn = "1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 5. Nc3 a6 6. Be3 e5 7. Nb3 Be6 8. f3 Be7 9. Qd2 O-O 10. O-O-O Nbd7 11. g4 b5 12. g5 b4 13. Ne2 Ne8 14. f4 exf4 15. Nxf4 Qb6 16. Bb3 a5 17. Bh6 gxh6 18. Qxb4 axb4 19. Kb1 Ra3 20. Nxe6 fxe6 21. Nd3 d5 22. exd5 Nc5 23. Bc4 Nxd5 24. Nd4 Ne3 25. Qxb8+ Raxb8 26. Nxc6 Ne4 27. Nxb8 Nxb8 28. Bd5 Nxc2 29. Kxc2 Qc3+ 30. Kb1 Rxd5 31. Rxd5 e4 32. Rc5 Qxc5 33. Rb5 Qc2+ 34. Ka1 e3 35. Re1 e2 36. Rxe2 Qc1+ 37. Ka2 Qxa3+ 38. Kb1 Nd4 39. Re1 Nb3 40. Kc2 Qc3+ 41. Kd1 Qd3+ 42. Kc1 Nb3+ 43. Kb1 d4 44. Rb1 d3 45. Qf8+ Kxf8 46. Rd1 Qc2+ 47. Kc1 d2+ 48. Rd1 d1=Q+ 49. Rxd1 Qc3+ 50. Rd2 Qc1+ 0-1"
        val game = PgnImporter.parse(pgn)
        assertNotNull(game)
        assertTrue(game!!.sanMoves.contains("O-O"))
        assertTrue(game.sanMoves.contains("O-O-O"))
        assertTrue(game.sanMoves.contains("d1=Q+"))
        assertTrue(game.sanMoves.contains("Qxb8+"))
    }

    @Test
    fun `comment containing curly braces with nesting does not crash`() {
        val pgn = "1. e4 {comment {nested} still} e5"
        val game = PgnImporter.parse(pgn)
        assertNotNull(game)
        assertEquals(listOf("e4", "e5"), game!!.sanMoves)
    }

    @Test
    fun `no NAG or annotation survives`() {
        val pgn = "1. e4?! e5 $2 2. Nf3!? Nc6"
        val game = PgnImporter.parse(pgn)
        assertNotNull(game)
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6"), game!!.sanMoves)
    }
}
