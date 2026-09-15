package com.example.chessanalysis.engine

import com.example.chessanalysis.data.PgnExporter
import com.example.chessanalysis.data.PgnImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PgnGameBuilderTest {
    @Test
    fun `builds parseable standard game from FEN history`() {
        val fens = listOf(
            PgnImporter.START_FEN,
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2"
        )
        val pgn = PgnExporter.export(PgnGameBuilder.build(fens, "Eröffnung \"A\""))
        assertTrue(pgn.contains("[Event \"Eröffnung \\\"A\\\"\"]"))
        val reparsed = PgnImporter.parse(pgn)!!
        assertEquals("Eröffnung \"A\"", reparsed.tags["Event"])
        assertEquals(listOf("e4", "e5"), reparsed.sanMoves)
    }

    @Test
    fun `custom black-to-move position emits setup tags and ellipsis move number`() {
        val before = "7k/8/8/8/8/8/8/K7 b - - 0 23"
        val after = "8/7k/8/8/8/8/8/K7 w - - 1 24"
        val pgn = PgnExporter.export(PgnGameBuilder.build(listOf(before, after)))
        assertTrue(pgn.contains("[SetUp \"1\"]"))
        assertTrue(pgn.contains("[FEN \"$before\"]"))
        assertTrue(pgn.contains("23... Kh7"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid transition fails whole build`() {
        PgnGameBuilder.build(listOf(PgnImporter.START_FEN, PgnImporter.START_FEN))
    }
}
