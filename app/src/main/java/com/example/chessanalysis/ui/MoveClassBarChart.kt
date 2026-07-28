package com.example.chessanalysis.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.chessanalysis.model.MoveClass

class MoveClassBarChart @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Bar(val cls: MoveClass, val count: Int)

    private var bars: List<Bar> = emptyList()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.rgb(0x2A, 0x2A, 0x2A)
    }
    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.rgb(0x44, 0x44, 0x44)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xCC, 0xCC, 0xCC); textSize = 26f
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 26f
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val barRect = RectF()

    fun setData(counts: Map<String, Int>) {
        bars = MoveClass.entries
            .filter { counts.containsKey(it.name) && (counts[it.name] ?: 0) > 0 }
            .map { Bar(it, counts[it.name] ?: 0) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val padL = 100f; val padR = 40f; val padT = 8f; val padB = 8f
        val barH = 28f; val gap = 6f
        val totalH = bars.size * (barH + gap)

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val maxCount = bars.maxOfOrNull { it.count } ?: return
        val plotW = w - padL - padR

        var y = padT + (h - padT - padB - totalH) / 2f
        for (bar in bars) {
            val bx = padL
            val by = y
            val bw = if (maxCount > 0) plotW * bar.count / maxCount else 0f

            canvas.drawText(bar.cls.symbol, 4f, by + barH - 6f, labelPaint)
            canvas.drawRect(bx, by, bx + plotW, by + barH, barBgPaint)
            if (bw > 0f) {
                barPaint.color = bar.cls.color
                canvas.drawRect(bx, by, bx + bw, by + barH, barPaint)
            }
            canvas.drawText(bar.count.toString(), bx + plotW + 6f, by + barH - 6f, countPaint)

            y += barH + gap
        }
    }
}