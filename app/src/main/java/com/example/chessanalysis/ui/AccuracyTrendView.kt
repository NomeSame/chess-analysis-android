package com.example.chessanalysis.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class AccuracyTrendView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var data: List<Double> = emptyList()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.rgb(0x19, 0x76, 0xD2)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(0x21, 0x96, 0xF3); strokeWidth = 3f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(0x55, 0x55, 0x55); strokeWidth = 1f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x99, 0x99, 0x99); textSize = 28f
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.rgb(0x2A, 0x2A, 0x2A)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.rgb(0x21, 0x96, 0xF3)
    }

    fun setData(values: List<Double>) {
        data = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val padL = 40f; val padR = 16f; val padT = 16f; val padB = 24f
        val plotW = w - padL - padR
        val plotH = h - padT - padB

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (plotW <= 0 || plotH <= 0) return

        canvas.drawLine(padL, padT, padL, h - padB, gridPaint)
        canvas.drawLine(padL, h - padB, w - padR, h - padB, gridPaint)

        for (pct in listOf(0, 25, 50, 75, 100)) {
            val y = padT + plotH * (1f - pct / 100f)
            canvas.drawLine(padL, y, w - padR, y, gridPaint)
            canvas.drawText("$pct", 4f, y + 8f, textPaint)
        }

        if (data.size < 2) return

        fun xAt(i: Int): Float = padL + plotW * i / (data.size - 1)
        fun yAt(v: Double): Float = padT + plotH * (1f - v.toFloat() / 100f).coerceIn(0f, 1f)

        val area = Path()
        area.moveTo(xAt(0), yAt(data[0]))
        for (i in 1 until data.size) area.lineTo(xAt(i), yAt(data[i]))
        area.lineTo(xAt(data.lastIndex), h - padB)
        area.lineTo(xAt(0), h - padB)
        area.close()
        canvas.drawPath(area, fillPaint)

        val line = Path()
        line.moveTo(xAt(0), yAt(data[0]))
        for (i in 1 until data.size) line.lineTo(xAt(i), yAt(data[i]))
        canvas.drawPath(line, linePaint)

        val dotR = 6f
        for (i in data.indices) canvas.drawCircle(xAt(i), yAt(data[i]), dotR, dotPaint)
    }
}