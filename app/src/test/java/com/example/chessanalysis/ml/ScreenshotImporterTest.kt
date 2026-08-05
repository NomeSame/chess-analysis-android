package com.example.chessanalysis.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenshotImporterTest {

    @Test
    fun `flipFen is its own inverse`() {
        val fens = listOf(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "4k3/8/8/8/8/8/8/4K3 b - - 0 1",
            "r1bk3r/ppp2Npp/2n5/2b1p3/2B1P3/3P4/PPP2qPP/R1BQ1RK1 w - - 0 13",
            "8/8/8/8/8/8/8/8 w - - 0 1"
        )
        for (fen in fens) {
            assertEquals(fen, ScreenshotImporter.flipFen(ScreenshotImporter.flipFen(fen)))
        }
    }

    @Test
    fun `flipFen rotates placement 180 degrees`() {
        // Rook on a1 (row7 col0) -> 180° rotation puts it on h8 (row0 col7), last char of rank 8.
        val fen = "7k/8/8/8/8/8/8/R5K1 w - - 0 1"
        val flipped = ScreenshotImporter.flipFen(fen)
        val ranks = flipped.substringBefore(' ').split("/")
        assertEquals("R", ranks[0].last().toString())
    }

    @Test
    fun `flipFen rotates a king on e1 to d8`() {
        val flipped = ScreenshotImporter.flipFen("8/8/8/8/8/8/8/4K3 w - - 0 1")
        assertEquals("3K4/8/8/8/8/8/8/8 w - - 0 1", flipped)
    }

    @Test
    fun `flipFen keeps side to move and clocks unchanged`() {
        val fen = "8/8/8/8/8/8/8/K6k b KQkq - 17 42"
        val flipped = ScreenshotImporter.flipFen(fen)
        assertNotNull(flipped)
        val parts = flipped.split(" ")
        assertEquals("b", parts[1])
        assertEquals("17", parts[4])
        assertEquals("42", parts[5])
    }

    @Test
    fun `findPeriodicRange finds 8x8 grid in periodic profile`() {
        // Build a profile with strong period-8 peaks (8*cell = 8*12 = 96, len 128).
        val len = 128
        val cell = 12
        val profile = FloatArray(len) { 1.0f }
        // peak cells every `cell`, valley between -> diff>0
        for (k in 0 until len step cell) profile[k] = 5.0f
        val range = invokeFindPeriodicRange(profile, len)
        assertNotNull(range)
    }

    @Test
    fun `findPeriodicRange returns null for too-short profile`() {
        assertNull(invokeFindPeriodicRange(FloatArray(8) { 1.0f }, 8))
    }

    @Test
    fun `findPeriodicRange returns null for flat profile`() {
        val len = 128
        val flat = FloatArray(len) { 3.0f }
        assertNull(invokeFindPeriodicRange(flat, len))
    }

    private fun invokeFindPeriodicRange(profile: FloatArray, len: Int): Pair<Int, Int>? {
        val m = ScreenshotImporter::class.java.getDeclaredMethod("findPeriodicRange", FloatArray::class.java, Int::class.javaPrimitiveType)
        m.isAccessible = true
        val res = m.invoke(ScreenshotImporter, profile, len)
        return when (res) {
            null -> null
            else -> {
                val a = res.javaClass.getDeclaredField("first")
                val b = res.javaClass.getDeclaredField("second")
                a.isAccessible = true; b.isAccessible = true
                Pair((a.get(res) as Int), (b.get(res) as Int))
            }
        }
    }
}
