package com.example.chessanalysis.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object ViewFactory {
    fun makeActionButton(text: String, filled: Boolean, ctx: Context): Button {
        val d = ctx.resources.displayMetrics.density
        val accent = 0xFF1976D2.toInt()
        return Button(ctx).apply {
            this.text = text
            isAllCaps = false
            textSize = 16f
            stateListAnimator = null
            setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 8 * d
                if (filled) setColor(accent) else { setColor(Color.WHITE); setStroke((2 * d).toInt(), accent) }
            }
            setTextColor(if (filled) Color.WHITE else accent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ((if (filled) 16 else 10) * d).toInt() }
        }
    }

    fun sectionLabel(text: String, topPad: Int, ctx: Context): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFF757575.toInt())
        setPadding(0, topPad, 0, (4 * ctx.resources.displayMetrics.density).toInt())
    }

    fun darken(c: Int): Int {
        val f = 0.82f
        return Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    }

    fun pickerBg(base: Int, selected: Boolean, ctx: Context): GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = 6 * d
            if (selected) {
                setColor(darken(base))
                setStroke((3 * d).toInt(), 0xFF1976D2.toInt())
            } else {
                setColor(base)
                setStroke((1 * d).toInt(), 0xFFBDBDBD.toInt())
            }
        }
    }

    fun segBg(selected: Boolean, left: Boolean, density: Float): GradientDrawable = GradientDrawable().apply {
        val r = 6 * density
        cornerRadii = if (left) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        setColor(if (selected) 0xFF1976D2.toInt() else Color.WHITE)
        setStroke((1 * density).toInt(), 0xFF1976D2.toInt())
    }
}
