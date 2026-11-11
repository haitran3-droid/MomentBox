package com.ntq.training.momentbox.feature.journal

import android.graphics.Canvas
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ntq.training.momentbox.R

class JournalStickyHeader(
    private val entries: JournalEntriesAdapter,
    private val prefixCount: Int
) : RecyclerView.ItemDecoration() {
    private var cached: Pair<JournalListItem.Header, RecyclerView.ViewHolder>? = null
    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val first =
            (parent.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: return
        if (first < prefixCount) return
        val items = entries.currentList
        val position = (first - prefixCount).coerceAtMost(items.lastIndex)
        if (position < 0) return
        val index =
            (position downTo 0).firstOrNull { items[it] is JournalListItem.Header } ?: return
        val header = items[index] as JournalListItem.Header
        val holder =
            cached?.takeIf { it.first == header }?.second ?: entries.createViewHolder(parent, 0)
                .also {
                    entries.bindViewHolder(it, index); cached = header to it
                }
        val view = holder.itemView
        view.setBackgroundColor(parent.context.getColor(R.color.background))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, parent.width, view.measuredHeight)
        var top = 0f
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val childIndex = parent.getChildAdapterPosition(child) - prefixCount
            if (childIndex > index && items.getOrNull(childIndex) is JournalListItem.Header) top =
                minOf(0f, child.top - view.height.toFloat())
        }
        canvas.save(); canvas.translate(0f, top); view.draw(canvas); canvas.restore()
    }
}
