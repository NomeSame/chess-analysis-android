package com.example.chessanalysis.ui

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.chessanalysis.model.MoveClass
import com.example.chessanalysis.state.MoveItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MoveListAdapterTest {

    @Test
    fun `all review classes use their screenshot-style symbol and color`() {
        MoveClass.entries.forEach { moveClass ->
            val style = MoveBadgeStyle.forClass(moveClass)
            assertEquals(moveClass.symbol, style?.symbol)
            assertEquals(moveClass.color, style?.color)
        }
        assertNull(MoveBadgeStyle.forClass(null))
    }

    @Test
    fun `quality is rendered as a separate circular badge`() {
        val context = RuntimeEnvironment.getApplication()
        val parent = RecyclerView(context)
        val adapter = MoveListAdapter(
            listOf(MoveItem(1, "17. d3", false, MoveClass.BRILLIANT))
        ) {}

        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)

        assertEquals("17. d3", holder.moveText.text.toString())
        assertEquals(1, holder.moveText.maxLines)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, holder.container.layoutParams.width)
        assertEquals("!!", holder.badge.text.toString())
        assertEquals(View.VISIBLE, holder.badge.visibility)
        assertEquals("Brilliant", holder.badge.contentDescription.toString())
        assertEquals(
            MoveClass.BRILLIANT.color,
            (holder.badge.background as GradientDrawable).color?.defaultColor
        )
    }

    @Test
    fun `card width grows with the complete move label`() {
        val context = RuntimeEnvironment.getApplication()
        val parent = RecyclerView(context)
        val adapter = MoveListAdapter(
            listOf(
                MoveItem(1, "1. e4", false, MoveClass.GREAT),
                MoveItem(2, "123. e4", false, MoveClass.GREAT)
            )
        ) {}
        val shortHolder = adapter.onCreateViewHolder(parent, 0)
        val longHolder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(shortHolder, 0)
        adapter.onBindViewHolder(longHolder, 1)

        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        shortHolder.container.measure(unspecified, unspecified)
        longHolder.container.measure(unspecified, unspecified)

        assertTrue(longHolder.container.measuredWidth > shortHolder.container.measuredWidth)
    }
}
