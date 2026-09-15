package com.example.chessanalysis.engine

import com.example.chessanalysis.model.MoveClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewCalibrationTest {
    @Test
    fun comparesEveryCategoryForBothPlayersIncludingMissingZeroes() {
        val reference = ReviewClassCounts(
            white = mapOf(MoveClass.BEST to 3, MoveClass.GOOD to 1),
            black = mapOf(MoveClass.BOOK to 2)
        )
        val actual = ReviewClassCounts(
            white = mapOf(MoveClass.BEST to 1, MoveClass.EXCELLENT to 2),
            black = mapOf(MoveClass.BOOK to 2)
        )

        val result = ReviewCalibration.compare(reference, actual)

        assertEquals(-2, result.signedDifference.getValue(true).getValue(MoveClass.BEST).toLong())
        assertEquals(2, result.signedDifference.getValue(true).getValue(MoveClass.EXCELLENT).toLong())
        assertEquals(5, result.totalAbsoluteDifference)
        assertEquals(0, result.signedDifference.getValue(false).getValue(MoveClass.BOOK).toLong())
    }

    @Test
    fun validatesBothPlyTotalsAndRejectsNegativeReferenceCounts() {
        val invalid = ReviewClassCounts(
            white = mapOf(MoveClass.BEST to 2, MoveClass.GOOD to -1),
            black = mapOf(MoveClass.BOOK to 1)
        )

        val errors = ReviewCalibration.validate(invalid, whitePlies = 3, blackPlies = 2)

        assertTrue(errors.any { it.contains("negative") })
        assertTrue(errors.any { it.startsWith("white counts") })
        assertTrue(errors.any { it.startsWith("black counts") })
    }

    @Test
    fun acceptsCompleteReferenceWithImplicitZeroCategories() {
        val reference = ReviewClassCounts(
            white = mapOf(MoveClass.BRILLIANT to 1, MoveClass.BEST to 2),
            black = mapOf(MoveClass.INACCURACY to 2)
        )

        assertTrue(ReviewCalibration.validate(reference, whitePlies = 3, blackPlies = 2).isEmpty())
    }
}
