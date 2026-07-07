package com.example.chessanalysis.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/** A horizontal bar showing one candidate move: "rank. move ........ eval", with a
 *  white-relative fill behind it (green = good for white, red = good for black). */
class MoveEvalBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var rankText = ""
    private var moveText = "—"
    private var evalText = ""
    private var whiteScore = 0f   // centipawns, white-positive

    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x2A, 0x2A, 0x2A) }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xF0, 0xF0, 0xF0) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    fun set(rank: String, move: String, eval: String, score: Float) {
        rankText = rank; moveText = move; evalText = eval; whiteScore = score
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2.4f

        // Two-tone bar: white portion ∝ white-relative score; better side dominates.
        val clamped = whiteScore.coerceIn(-600f, 600f)
        val ratio = (clamped + 600f) / 1200f
        val splitX = w * ratio

        // Black base, then white fill clipped to the white share.
        canvas.drawRoundRect(0f, 0f, w, h, r, r, black)
        canvas.save()
        canvas.clipRect(0f, 0f, splitX, h)
        canvas.drawRoundRect(0f, 0f, w, h, r, r, white)
        canvas.restore()

        val pad = h * 0.30f
        text.textSize = h * 0.42f
        val baseline = h / 2f - (text.descent() + text.ascent()) / 2f
        // Text color contrasts with whichever region it sits on.
        text.color = if (pad < splitX) Color.BLACK else Color.WHITE
        text.textAlign = Paint.Align.LEFT
        val label = if (rankText.isEmpty()) moveText else "$rankText  $moveText"
        canvas.drawText(label, pad, baseline, text)
        text.color = if (w - pad < splitX) Color.BLACK else Color.WHITE
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(evalText, w - pad, baseline, text)
    }
}
