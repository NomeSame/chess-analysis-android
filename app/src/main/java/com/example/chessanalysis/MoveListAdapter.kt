package com.example.chessanalysis

import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class MoveItem(
    val position: Int,
    val displayText: String,
    val isLast: Boolean
)

class MoveListAdapter(
    private val items: List<MoveItem>,
    private val onPositionSelected: (Int) -> Unit
) : RecyclerView.Adapter<MoveListAdapter.ViewHolder>() {

    var selectedPosition: Int = 0

    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val tv = TextView(parent.context).apply {
            setPadding(12, 8, 12, 8)
            gravity = Gravity.CENTER_VERTICAL
            textSize = 14f
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.WRAP_CONTENT,
                RecyclerView.LayoutParams.MATCH_PARENT
            )
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.textView.text = item.displayText
        val sel = position == selectedPosition
        holder.textView.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        holder.textView.setBackgroundColor(
            if (sel) 0xFF1976D2.toInt()
            else if (item.isLast && position > 0) 0xFF424242.toInt()
            else 0xFF333333.toInt()
        )
        holder.itemView.setOnClickListener { onPositionSelected(item.position) }
    }

    override fun getItemCount() = items.size
}
