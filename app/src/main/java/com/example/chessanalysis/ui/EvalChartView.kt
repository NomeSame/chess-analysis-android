package com.example.chessanalysis.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.chessanalysis.model.MoveClass
import kotlin.math.abs

/**
 * Chess.com-style eval curve: the white-relative evaluation over the course of the game.
 * White's advantage fills the area above the centerline (light), Black's below (dark).
 * x = ply (position index), y = white-relative centipawns clamped to ±[CLAMP].
 */
class EvalChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var data: List<Int> = emptyList()
    private var moves: List<MoveClass> = emptyList()   // perPly: moves[i] produced position i+1
    private var marker: Int = -1

    /** Position indices (0-based) that have tactical chances shown as red rings. */
    var tacticalPositions: Set<Int> = emptySet()

    /** Tap on a marquant-move dot or tactic ring → callback with the POSITION index to jump to. */
    var onPlySelected: ((Int) -> Unit)? = null

    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2f }
    private val tacticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.RED; strokeWidth = 3f * resources.displayMetrics.density
    }

    private val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(0xEE, 0xEE, 0xEE) }
    private val darkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(0x40, 0x40, 0x40) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(0x90, 0x90, 0x90); strokeWidth = 2f
    }
    private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(0x88, 0x88, 0x88); strokeWidth = 1f
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(0x21, 0x96, 0xF3); strokeWidth = 3f
    }

    fun setData(evalWhitePov: List<Int>) {
        data = evalWhitePov
        if (marker > data.lastIndex) marker = -1
        invalidate()
    }

    /** Per-ply classifications (size = positions-1); marquant ones become clickable dots. */
    fun setMoves(perPly: List<MoveClass>) {
        moves = perPly
        invalidate()
    }

    /** Highlight the currently viewed ply with a vertical marker (-1 = none). */
    fun setMarker(ply: Int) {
        marker = ply
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = h / 2f
        // Background = dark half (black advantage), then white area painted on top.
        canvas.drawRect(0f, 0f, w, h, darkFill)

        if (data.size < 2) {
            canvas.drawLine(0f, mid, w, mid, midPaint)
            return
        }

        fun xAt(i: Int): Float = if (data.size == 1) 0f else w * i / (data.size - 1)
        fun yAt(cp: Int): Float {
            val c = cp.coerceIn(-CLAMP, CLAMP).toFloat()
            return mid - (c / CLAMP) * mid
        }

        // White-area polygon: along the curve, then back along the bottom edge.
        val area = Path()
        area.moveTo(xAt(0), yAt(data[0]))
        for (i in 1 until data.size) area.lineTo(xAt(i), yAt(data[i]))
        area.lineTo(xAt(data.lastIndex), h)
        area.lineTo(xAt(0), h)
        area.close()
        canvas.drawPath(area, whiteFill)

        // Curve line.
        val line = Path()
        line.moveTo(xAt(0), yAt(data[0]))
        for (i in 1 until data.size) line.lineTo(xAt(i), yAt(data[i]))
        canvas.drawPath(line, linePaint)

        // Midline (eval 0).
        canvas.drawLine(0f, mid, w, mid, midPaint)

        // Current-ply marker.
        if (marker in data.indices) {
            val mx = xAt(marker)
            canvas.drawLine(mx, 0f, mx, h, markerPaint)
        }

        // Marquant-move dots (clickable): blunder/mistake/inaccuracy/miss/great/brilliant.
        val r = DOT_RADIUS_DP * resources.displayMetrics.density
        for (i in moves.indices) {
            val cls = moves[i]
            if (!isMarquant(cls)) continue
            val pos = i + 1
            if (pos !in data.indices) continue
            val cx = xAt(pos); val cy = yAt(data[pos])
            dotFill.color = cls.color
            canvas.drawCircle(cx, cy, r, dotFill)
            canvas.drawCircle(cx, cy, r, dotRing)
        }

        // Tactic rings (red) for tactical chances.
        for (pos in tacticalPositions) {
            if (pos !in data.indices) continue
            val cx = xAt(pos); val cy = yAt(data[pos])
            canvas.drawCircle(cx, cy, r + 2f, tacticPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && data.size >= 2) {
            val w = width.toFloat()
            fun xAt(i: Int): Float = w * i / (data.size - 1)
            val hitR = (DOT_RADIUS_DP + 8f) * resources.displayMetrics.density
            var bestPos = -1; var bestDx = Float.MAX_VALUE
            for (i in moves.indices) {
                if (!isMarquant(moves[i])) continue
                val pos = i + 1
                if (pos !in data.indices) continue
                val dx = abs(xAt(pos) - event.x)
                if (dx < bestDx) { bestDx = dx; bestPos = pos }
            }
            for (pos in tacticalPositions) {
                if (pos !in data.indices) continue
                val dx = abs(xAt(pos) - event.x)
                if (dx < bestDx) { bestDx = dx; bestPos = pos }
            }
            if (bestPos >= 0 && bestDx <= hitR) {
                performClick()
                onPlySelected?.invoke(bestPos)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun isMarquant(c: MoveClass): Boolean = when (c) {
        MoveClass.BRILLIANT, MoveClass.GREAT, MoveClass.INACCURACY,
        MoveClass.MISTAKE, MoveClass.MISS, MoveClass.BLUNDER -> true
        else -> false
    }

    companion object {
        private const val CLAMP = 1000      // display clamp in centipawns (±10 pawns)
        private const val DOT_RADIUS_DP = 4f
    }
}
