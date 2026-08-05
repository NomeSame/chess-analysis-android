package com.example.chessanalysis.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        val whiteGlyphs = charArrayOf('♜', '♞', '♝', '♛', '♚', '♝', '♞', '♜')
        val blackGlyphs = charArrayOf('♖', '♘', '♗', '♕', '♔', '♗', '♘', '♖')
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = cell * 0.78f
        }
        fun drawRow(row: Int, chars: CharArray) {
            for (c in 0 until 8) {
                paint.color = if (chars[c].isUpperCase()) Color.BLACK else Color.WHITE
                val fm = paint.fontMetrics
                val cy = row * cell + cell / 2f - (fm.ascent + fm.descent) / 2f
                canvas.drawText(chars[c].toString(), c * cell + cell / 2f, cy, paint)
            }
        }
        drawRow(0, blackGlyphs)   // black back rank
        drawRow(1, "♟♟♟♟♟♟♟♟".toCharArray())
        drawRow(6, "♙♙♙♙♙♙♙♙".toCharArray())
        drawRow(7, whiteGlyphs)   // white back rank
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
        assertTrue("white king present", fen.contains("K"))
        assertTrue("black king present", fen.contains("k"))
    }

    @Test
    fun recognize_reportsHighConfidence_forCleanBoard() {
        val result = ScreenshotImporter.recognize(drawBoard())
        assertNotNull(result)
        assertFalse("unexpected uncertainty flag", result!!.uncertain)
        assertTrue("confidence should be decent", result.perspectiveConfidence >= 0.7f)
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
