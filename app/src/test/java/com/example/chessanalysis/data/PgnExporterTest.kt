package com.example.chessanalysis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PgnExporterTest {

    @Test
    fun `exports standard game with move numbers and result`() {
        val game = PgnImporter.Game(
            startFen = PgnImporter.START_FEN,
            sanMoves = listOf("e4", "e5", "Nf3", "Nc6"),
            tags = mapOf("Event" to "X", "Result" to "1-0")
        )
        val out = PgnExporter.export(game)
        assertTrue(out.contains("[Event \"X\"]"))
        assertTrue(out.contains("[Result \"1-0\"]"))
        assertTrue(out.contains("1. e4 e5 2. Nf3 Nc6"))
        assertTrue(out.endsWith("1-0\n"))
    }

    @Test
    fun `does not emit FEN tag for standard start`() {
        val game = PgnImporter.Game(
            startFen = PgnImporter.START_FEN,
            sanMoves = listOf("e4"),
            tags = emptyMap()
        )
        val out = PgnExporter.export(game)
        assertTrue(!out.contains("[FEN"))
    }

    @Test
    fun `emits FEN tag for custom start`() {
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val game = PgnImporter.Game(startFen = fen, sanMoves = listOf("Bb5"), tags = emptyMap())
        val out = PgnExporter.export(game)
        assertTrue(out.contains("[FEN \"$fen\"]"))
    }

    @Test
    fun `odd number of moves does not end with dangling space`() {
        val game = PgnImporter.Game(
            startFen = PgnImporter.START_FEN,
            sanMoves = listOf("e4", "e5", "Nf3"),
            tags = emptyMap()
        )
        val out = PgnExporter.export(game)
        assertTrue(out.contains("1. e4 e5 2. Nf3"))
        assertTrue(!out.endsWith(" "))
    }

    @Test
    fun `roundtrip via importer preserves moves`() {
        val pgn = "1. e4 e5 2. Nf3 Nc6 1-0"
        val game = PgnImporter.parse(pgn)!!
        val exported = PgnExporter.export(game)
        val reparsed = PgnImporter.parse(exported)!!
        assertEquals(game.sanMoves, reparsed.sanMoves)
        assertEquals("1-0", reparsed.tags["Result"])
    }
}
