package com.example.chessanalysis.engine

import com.example.chessanalysis.model.MoveClass

/**
 * Pure aggregate comparison for calibrating a game review against an external reference.
 *
 * This deliberately accepts counts only: it never receives player names, game IDs, FEN hashes,
 * or move numbers. It therefore cannot become a route for game-specific product rules.
 */
data class ReviewClassCounts(
    val white: Map<MoveClass, Int>,
    val black: Map<MoveClass, Int>
)

data class ReviewCalibrationResult(
    /** Sum of absolute category differences across both players. Zero is an exact aggregate match. */
    val totalAbsoluteDifference: Int,
    /** Signed value: positive means the app assigns this category too often. */
    val signedDifference: Map<Boolean, Map<MoveClass, Int>>
)

object ReviewCalibration {
    fun compare(reference: ReviewClassCounts, actual: ReviewClassCounts): ReviewCalibrationResult {
        var absoluteTotal = 0
        val signed = linkedMapOf<Boolean, Map<MoveClass, Int>>()
        for ((isWhite, referenceSide, actualSide) in listOf(
            Triple(true, reference.white, actual.white),
            Triple(false, reference.black, actual.black)
        )) {
            val side = linkedMapOf<MoveClass, Int>()
            for (moveClass in MoveClass.entries) {
                val delta = actualSide[moveClass].orZero() - referenceSide[moveClass].orZero()
                side[moveClass] = delta
                absoluteTotal += kotlin.math.abs(delta)
            }
            signed[isWhite] = side
        }
        return ReviewCalibrationResult(absoluteTotal, signed)
    }

    /**
     * Validates that a manually transcribed reference is complete before it is used for calibration.
     * The returned messages are intentionally suitable for a test/report instead of failing silently.
     */
    fun validate(reference: ReviewClassCounts, whitePlies: Int, blackPlies: Int): List<String> = buildList {
        validateSide("white", reference.white, whitePlies)
        validateSide("black", reference.black, blackPlies)
    }

    private fun MutableList<String>.validateSide(
        sideName: String,
        counts: Map<MoveClass, Int>,
        expectedPlies: Int
    ) {
        val negative = counts.filterValues { it < 0 }.keys
        if (negative.isNotEmpty()) add("$sideName has negative counts: $negative")
        val total = MoveClass.entries.sumOf { counts[it].orZero() }
        if (total != expectedPlies) add("$sideName counts $total plies, expected $expectedPlies")
    }

    private fun Int?.orZero(): Int = this ?: 0
}
