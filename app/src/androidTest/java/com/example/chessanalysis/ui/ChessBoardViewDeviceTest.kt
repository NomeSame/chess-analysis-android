package com.example.chessanalysis.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device rendering smoke test for ChessBoardView: measures/lays out and draws to a real
 * bitmap-backed canvas (pixel paths that Robolectric shadows do not exercise) and verifies
 * FEN round-tripping on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class ChessBoardViewDeviceTest {

    private fun view(): ChessBoardView {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val v = ChessBoardView(context)
        v.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 800, 800)
        return v
    }

    private fun render(v: ChessBoardView) {
        val bmp = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        assertNotNull(bmp)
    }

    @Test
    fun setFen_getFen_roundtrip_onDevice() {
        val v = view()
        v.setFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        render(v)
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", v.getFen())
    }

    @Test
    fun draw_midgamePosition_doesNotCrash() {
        val v = view()
        v.setFen("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3")
        v.showLegalMoves = true
        v.evalScore = -1.2f
        v.render()
        render(v)
        assertEquals("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3", v.getFen())
    }

    @Test
    fun flippedBoard_rendersAndRoundtrips() {
        val v = view()
        v.flipBoard = true
        v.setFen("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
        render(v)
        assertEquals("4k3/8/8/8/8/8/8/4K3 w - - 0 1", v.getFen())
    }
}
