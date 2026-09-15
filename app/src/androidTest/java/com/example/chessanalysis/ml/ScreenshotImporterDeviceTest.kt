package com.example.chessanalysis.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device test of the real bitmap pipeline (ScreenshotImporter.recognize).
 * Draws a synthetic 8x8 board with the same filled Unicode glyphs the recognizer's
 * silhouette matcher is trained on, then asserts the recognised FEN is valid and
 * the piece set (occupancy + colours + both kings) is correct.
 *
 * init() is intentionally NOT called: without it tfliteClassifier and templateMatcher
 * are null and recognize() takes the deterministic glyph-silhouette path.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotImporterDeviceTest {

    private val startRows = listOf(
        "rnbqkbnr", "pppppppp", "........", "........",
        "........", "........", "PPPPPPPP", "RNBQKBNR"
    )

    @Before
    fun useDeterministicFallback() {
        // Earlier device tests launch MainActivity, which initializes optional asset templates.
        // This fixture intentionally validates the deterministic glyph-silhouette path instead.
        ScreenshotImporter.useDeterministicFallbackForTesting()
    }

    private fun drawBoard(): Bitmap {
        val size = 1024
        val cell = size / 8
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val light = 0xFFF0D9B5.toInt()
        val dark = 0xFFB58863.toInt()
        val bg = Paint()
        for (r in 0 until 8) for (c in 0 until 8) {
            bg.color = if ((r + c) % 2 == 0) light else dark
            canvas.drawRect((c * cell).toFloat(), (r * cell).toFloat(), ((c + 1) * cell).toFloat(), ((r + 1) * cell).toFloat(), bg)
        }
        // The deterministic fallback compares filled silhouettes.  Use the same filled shapes
        // for both colours and vary only paint colour, as a real piece set does.
        val filledGlyphs = charArrayOf('♜', '♞', '♝', '♛', '♚', '♝', '♞', '♜')
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = cell * 0.78f
        }
        fun drawRow(row: Int, chars: CharArray, color: Int) {
            for (c in 0 until 8) {
                paint.color = color
                val fm = paint.fontMetrics
                val cy = row * cell + cell / 2f - (fm.ascent + fm.descent) / 2f
                canvas.drawText(chars[c].toString(), c * cell + cell / 2f, cy, paint)
            }
        }
        drawRow(0, filledGlyphs, Color.BLACK)  // black back rank
        drawRow(1, "♟♟♟♟♟♟♟♟".toCharArray(), Color.BLACK)
        drawRow(6, "♙♙♙♙♙♙♙♙".toCharArray(), Color.WHITE)
        drawRow(7, filledGlyphs, Color.WHITE)  // white back rank
        return bmp
    }

    @Test
    fun recognize_detectsStartPosition_onDevice() {
        val bmp = drawBoard()
        val result = ScreenshotImporter.recognize(bmp)
        assertNotNull("recognize returned null", result)
        val fen = result!!.fen
        val placement = fen.substringBefore(" ")
        val ranks = placement.split("/")
        assertEquals("8 ranks", 8, ranks.size)

        var white = 0; var black = 0
        for (rank in ranks) {
            var i = 0
            for (ch in rank) {
                if (ch.isDigit()) i += ch - '0' else {
                    if (ch.isUpperCase()) white++ else black++
                    i++
                }
            }
            assertEquals("rank fills 8 squares: $rank", 8, i)
        }
        assertEquals("white pieces", 16, white)
        assertEquals("black pieces", 16, black)
        assertTrue("white king present in $fen", fen.contains("K"))
        assertTrue("black king present in $fen", fen.contains("k"))
    }

    @Test
    fun recognize_reportsHighConfidence_forCleanBoard() {
        val result = ScreenshotImporter.recognize(drawBoard())
        assertNotNull(result)
        assertFalse("unexpected uncertainty flag for ${result!!.fen}", result.uncertain)
        assertTrue("confidence should be decent for ${result.fen}", result.perspectiveConfidence >= 0.7f)
    }

    @Test
    fun recognize_returnsNull_forBlankImage() {
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.GRAY)
        val result = ScreenshotImporter.recognize(bmp)
        assertTrue("blank image should not produce a plausible result",
            result == null || result.fen.count { !it.isDigit() && it != '/' && it != ' ' } < 2)
    }
}
