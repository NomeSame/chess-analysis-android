package com.example.chessanalysis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SanFormatterTest {
    @Test
    fun `formats pawn move and capture`() {
        assertEquals("e4", SanFormatter.fromPositions(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        ))
        assertEquals("exd5", SanFormatter.fromPositions(
            "4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1",
            "4k3/8/8/3P4/8/8/8/4K3 b - - 0 1"
        ))
    }

    @Test
    fun `formats castling promotion check and mate`() {
        assertEquals("O-O", SanFormatter.fromPositions(
            "4k2r/8/8/8/8/8/8/R3K2R w KQk - 0 1",
            "4k2r/8/8/8/8/8/8/R4RK1 b k - 1 1"
        ))
        assertEquals("e8=Q+", SanFormatter.fromPositions(
            "k7/4P3/8/8/8/8/8/7K w - - 0 1",
            "k3Q3/8/8/8/8/8/8/7K b - - 0 1"
        ))
        assertEquals("Qh4#", SanFormatter.fromPositions(
            "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2",
            "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3"
        ))
    }

    @Test
    fun `disambiguates same-rank knights by file`() {
        assertEquals("Nbd2", SanFormatter.fromPositions(
            "7k/8/8/8/8/8/8/1N3N1K w - - 0 1",
            "7k/8/8/8/8/8/3N4/5N1K b - - 1 1"
        ))
    }

    @Test
    fun `elroy knight development is disambiguated as Nbc3`() {
        assertEquals("Nbc3", SanFormatter.fromPositions(
            "r1bqkb1r/ppp2ppp/2n1pn2/3p4/3P1B2/4P3/PPP1NPPP/RN1QKB1R w KQkq d6 0 5",
            "r1bqkb1r/ppp2ppp/2n1pn2/3p4/3P1B2/2N1P3/PPP1NPPP/R2QKB1R b KQkq - 1 5"
        ))
    }

    @Test
    fun `rejects transition that is not one legal move`() {
        assertNull(SanFormatter.fromPositions(
            "8/8/8/8/8/8/8/K6k w - - 0 1",
            "8/8/8/8/8/8/8/K6k b - - 0 1"
        ))
    }
}
