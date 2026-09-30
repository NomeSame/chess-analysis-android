package com.example.chessanalysis.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.chessanalysis.model.MoveClass
import com.example.chessanalysis.state.MoveItem

class MoveListAdapter(
    private val items: List<MoveItem>,
    private val onPositionSelected: (Int) -> Unit
) : RecyclerView.Adapter<MoveListAdapter.ViewHolder>() {

    var selectedPosition: Int = 0

    class ViewHolder(
        val container: LinearLayout,
        val moveText: TextView,
        val badge: TextView
    ) : RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context
        val moveText = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 1
        }
        val badge = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(context.dp(26), context.dp(26)).apply {
                marginStart = context.dp(6)
            }
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(14), context.dp(6), context.dp(12), context.dp(6))
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.WRAP_CONTENT,
                RecyclerView.LayoutParams.MATCH_PARENT
            )
            addView(moveText)
            addView(badge)
        }
        return ViewHolder(container, moveText, badge)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.moveText.text = item.displayText
        val selected = position == selectedPosition
        holder.moveText.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        holder.container.background = roundedBackground(
            if (selected) 0xFF1976D2.toInt()
            else if (item.isLast && position > 0) 0xFF424242.toInt()
            else 0xFF333333.toInt(),
            holder.container.context.dp(7).toFloat()
        )
        bindBadge(holder.badge, item.moveClass)
        holder.itemView.setOnClickListener { onPositionSelected(item.position) }
    }

    override fun getItemCount() = items.size

    private fun bindBadge(view: TextView, moveClass: MoveClass?) {
        val style = MoveBadgeStyle.forClass(moveClass)
        if (style == null) {
            view.visibility = View.GONE
            view.text = ""
            view.contentDescription = null
            return
        }
        view.visibility = View.VISIBLE
        view.text = style.symbol
        view.textSize = if (style.symbol.length > 1) 10.5f else 13f
        view.background = roundedBackground(style.color, view.context.dp(13).toFloat())
        view.contentDescription = moveClass?.label
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }
}

data class MoveBadgeStyle(val symbol: String, val color: Int) {
    companion object {
        fun forClass(moveClass: MoveClass?): MoveBadgeStyle? = moveClass?.let {
            MoveBadgeStyle(it.symbol, it.color)
        }
    }
}

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
