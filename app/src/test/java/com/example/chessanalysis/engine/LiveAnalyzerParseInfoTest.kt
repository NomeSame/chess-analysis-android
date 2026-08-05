package com.example.chessanalysis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveAnalyzerParseInfoTest {

    // parseInfo is a pure instance method that never touches the engine (native lib unavailable on JVM),
    // so allocate a LiveAnalyzer instance without running its constructor or StockfishEngine's <clinit>.
    private val analyzer: LiveAnalyzer = allocateLiveAnalyzer()

    private fun parseInfo(line: String): LiveAnalyzer.PvLine? {
        val m = LiveAnalyzer::class.java.getDeclaredMethod("parseInfo", String::class.java)
        m.isAccessible = true
        return m.invoke(analyzer, line) as LiveAnalyzer.PvLine?
    }

    private fun allocateLiveAnalyzer(): LiveAnalyzer {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").also { it.isAccessible = true }
        val unsafe = theUnsafe.get(null)
        val alloc = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return alloc.invoke(unsafe, LiveAnalyzer::class.java) as LiveAnalyzer
    }

    @Test
    fun `parses cp score with multipv and pv`() {
        val line = "info depth 22 seldepth 30 multipv 1 score cp 45 nodes 123 nps 1000 time 500 pv e2e4 e7e5 g1f3"
        val pl = parseInfo(line)
        assertEquals(1, pl!!.rank)
        assertEquals(45, pl.cp!!.toLong())
        assertNull(pl.mate)
        assertEquals("e2e4", pl.firstMove)
        assertEquals(listOf("e2e4", "e7e5", "g1f3"), pl.pv)
        assertEquals(22, pl.reachedDepth)
    }

    @Test
    fun `parses mate score as negative`() {
        val line = "info depth 30 multipv 2 score mate -2 pv g7g8q h7h8"
        val pl = parseInfo(line)
        assertEquals(2, pl!!.rank)
        assertNull(pl.cp)
        assertEquals(-2, pl.mate!!.toLong())
        assertEquals("g7g8q", pl.firstMove)
    }

    @Test
    fun `returns null when pv seen before multipv`() {
        // If the PV token arrives while rank is still < 1 -> null (engine protocol guarantee).
        val line = "info depth 15 pv e2e4 e7e5"
        assertNull(parseInfo(line))
    }

    @Test
    fun `returns line with no pv if multipv present but score after`() {
        val line = "info depth 12 multipv 1 score cp 12"
        val pl = parseInfo(line)
        assertEquals(1, pl!!.rank)
        assertEquals(12, pl.cp!!.toLong())
        assertNull(pl.firstMove)
        assertEquals(0, pl.pv.size.toLong())
    }

    @Test
    fun `non-info line returns null`() {
        assertNull(parseInfo("bestmove e2e4"))
        assertNull(parseInfo("uciok"))
    }

    @Test
    fun `depth defaults to 22 when absent`() {
        val line = "info multipv 3 score cp -30 pv a2a3"
        val pl = parseInfo(line)
        assertEquals(3, pl!!.rank)
        assertEquals(22, pl.reachedDepth)
    }

    @Test
    fun `pv stops at non-move token`() {
        val line = "info depth 20 multipv 1 score cp 5 pv e2e4 e7e5 currmove g1f3"
        val pl = parseInfo(line)
        assertEquals(listOf("e2e4", "e7e5"), pl!!.pv)
        assertEquals("e2e4", pl.firstMove)
    }
}
