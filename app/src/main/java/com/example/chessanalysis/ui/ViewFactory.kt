package com.example.chessanalysis.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object ViewFactory {

    // Lichess-inspired color palette
    private const val ACCENT = 0xFF769656.toInt()
    private const val ACCENT_DARK = 0xFF66834A.toInt()
    private const val SURFACE = 0xFFF7F8F5.toInt()
    private const val BORDER = 0xFFD9DFD4.toInt()
    private const val TEXT_PRIMARY = 0xFF30352D.toInt()
    private const val TEXT_SECONDARY = 0xFF737B6E.toInt()

    /**
     * Creates a modern action button.
     * Filled buttons use the chess-green accent.
     * Outlined buttons use a soft neutral surface.
     */
    fun makeActionButton(
        text: String,
        filled: Boolean,
        ctx: Context
    ): Button {

        val d = ctx.resources.displayMetrics.density

        return Button(ctx).apply {

            this.text = text
            isAllCaps = false
            textSize = 15f
            gravity = Gravity.CENTER

            setTypeface(
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
            )

            stateListAnimator = null
            elevation = 0f

            minimumHeight = (46 * d).toInt()
            minimumWidth = 0

            setPadding(
                (16 * d).toInt(),
                (11 * d).toInt(),
                (16 * d).toInt(),
                (11 * d).toInt()
            )

            background = GradientDrawable().apply {
                cornerRadius = 10 * d

                if (filled) {
                    setColor(ACCENT)
                } else {
                    setColor(SURFACE)
                    setStroke(
                        (1 * d).toInt().coerceAtLeast(1),
                        ACCENT
                    )
                }
            }

            setTextColor(
                if (filled) Color.WHITE else ACCENT_DARK
            )

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (
                    (if (filled) 14 else 9) * d
                ).toInt()
            }
        }
    }

    /**
     * Creates a clean section heading.
     */
    fun sectionLabel(
        text: String,
        topPad: Int,
        ctx: Context
    ): TextView = TextView(ctx).apply {

        this.text = text
        textSize = 13f

        setTypeface(
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        )

        setTextColor(TEXT_SECONDARY)

        letterSpacing = 0.02f

        setPadding(
            (2 * ctx.resources.displayMetrics.density).toInt(),
            topPad,
            0,
            (6 * ctx.resources.displayMetrics.density).toInt()
        )
    }

    /**
     * Darkens a color slightly.
     */
    fun darken(c: Int): Int {

        val factor = 0.82f

        return Color.rgb(
            (Color.red(c) * factor).toInt(),
            (Color.green(c) * factor).toInt(),
            (Color.blue(c) * factor).toInt()
        )
    }

    /**
     * Background for board and piece style pickers.
     */
    fun pickerBg(
        base: Int,
        selected: Boolean,
        ctx: Context
    ): GradientDrawable {

        val d = ctx.resources.displayMetrics.density

        return GradientDrawable().apply {

            cornerRadius = 9 * d

            if (selected) {
                setColor(darken(base))
                setStroke(
                    (2 * d).toInt().coerceAtLeast(1),
                    ACCENT
                )
            } else {
                setColor(base)
                setStroke(
                    (1 * d).toInt().coerceAtLeast(1),
                    BORDER
                )
            }
        }
    }

    /**
     * Background for segmented controls such as language selection.
     *
     * left = true    -> left segment
     * middle = true  -> center segment
     * both false     -> right segment
     *
     * The middle argument is optional to preserve compatibility
     * with existing calls.
     */
    fun segBg(
        selected: Boolean,
        left: Boolean,
        density: Float,
        middle: Boolean = false
    ): GradientDrawable = GradientDrawable().apply {

        val radius = 8 * density

        cornerRadii = when {
            left -> floatArrayOf(
                radius, radius,
                0f, 0f,
                0f, 0f,
                radius, radius
            )

            middle -> floatArrayOf(
                0f, 0f,
                0f, 0f,
                0f, 0f,
                0f, 0f
            )

            else -> floatArrayOf(
                0f, 0f,
                radius, radius,
                radius, radius,
                0f, 0f
            )
        }

        setColor(
            if (selected) ACCENT else SURFACE
        )

        setStroke(
            (1 * density).toInt().coerceAtLeast(1),
            ACCENT
        )
    }
}
