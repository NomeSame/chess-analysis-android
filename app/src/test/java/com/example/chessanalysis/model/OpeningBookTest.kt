package com.example.chessanalysis.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningBookTest {

    @Test
    fun startPlacementMatchesStandardFen() {
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR", OpeningBook.START_PLACEMENT)
    }

    @Test
    fun anyBareFirstMoveIsBook() {
        assertTrue(OpeningBook.isBookPath(listOf("e2e4")))
        assertTrue(OpeningBook.isBookPath(listOf("d2d4")))
        assertTrue(OpeningBook.isBookPath(listOf("g1f3")))
    }

    @Test
    fun fullTheoryLineIsBook() {
        assertTrue(OpeningBook.isBookPath(listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6")))
    }

    @Test
    fun everyPrefixOfBookLineIsBook() {
        assertTrue(OpeningBook.isBookPath(listOf("e2e4", "e7e5", "g1f3")))
        assertTrue(OpeningBook.isBookPath(listOf("e2e4", "e7e5")))
    }

    @Test
    fun queenPawnC6BranchIsBookInBothMoveOrders() {
        val moveOrders = listOf(
            listOf("d2d4", "d7d5", "g1f3", "c7c6"),
            listOf("g1f3", "d7d5", "d2d4", "c7c6")
        )
        moveOrders.forEach { path ->
            (1..path.size).forEach { assertTrue(OpeningBook.isBookPath(path.take(it))) }
            // The shallow seed does not certify an arbitrary third move as theory.
            assertFalse(OpeningBook.isBookPath(path + "b1c3"))
            assertFalse(OpeningBook.isBookPath(path.dropLast(1) + "h7h5"))
        }
    }

    @Test
    fun acceleratedLondonE6IsBookButDoesNotCertifyLaterMoves() {
        val path = listOf("d2d4", "g8f6", "c1f4", "e7e6")
        (1..path.size).forEach { assertTrue(OpeningBook.isBookPath(path.take(it))) }
        assertFalse(OpeningBook.isBookPath(path + "e2e3"))
        assertFalse(OpeningBook.isBookPath(path.dropLast(1) + "h7h5"))
    }

    @Test
    fun offbeatDeviationIsNotBook() {
        // 2.h3 deviates from all known lines
        assertFalse(OpeningBook.isBookPath(listOf("e2e4", "e7e5", "h2h3")))
    }

    @Test
    fun emptyPathIsNotBook() {
        assertFalse(OpeningBook.isBookPath(emptyList()))
    }

    @Test
    fun unknownOpeningHasNoName() {
        // Seed book has no names until a downloaded book is installed.
        assertNull(OpeningBook.openingName(listOf("e2e4", "e7e5")))
    }
}
