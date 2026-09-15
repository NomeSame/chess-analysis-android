package com.example.chessanalysis.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class SavedGameTemplateTest {
    private val fen = "8/8/8/8/8/8/8/K6k w - - 0 1"

    @Test
    fun `valid initial snapshot is accepted and copied`() {
        val mutableFens = mutableListOf(fen)
        val snapshot = LiveGameSnapshot(fen, mutableFens, listOf(null), "*", false, false, 1500)
        val copy = snapshot.validate().immutableCopy()
        mutableFens.add(fen)
        assertEquals(1, copy.fens.size)
        assertNotSame(snapshot.fens, copy.fens)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty history is rejected`() {
        LiveGameSnapshot(fen, emptyList(), emptyList(), "*", false, false, 1500).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `different history lengths are rejected`() {
        LiveGameSnapshot(fen, listOf(fen), emptyList(), "*", false, false, 1500).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-null initial origin is rejected`() {
        LiveGameSnapshot(fen, listOf(fen), listOf(7 to 0), "*", false, false, 1500).validate()
    }
}
