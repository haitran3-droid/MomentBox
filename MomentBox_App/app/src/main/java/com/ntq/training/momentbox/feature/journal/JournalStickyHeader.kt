package com.ntq.training.momentbox.feature.journal

import android.graphics.Canvas
import android.view.View
import androidx.core.graphics.withSave
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ntq.training.momentbox.R

class JournalStickyHeader(
    private val entries: JournalEntriesAdapter,
    private val prefixCount: Int
) : RecyclerView.ItemDecoration() {

    private var cached: Pair<JournalListItem.Header, RecyclerView.ViewHolder>? = null

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val layoutManager = parent.layoutManager as? LinearLayoutManager ?: return
        val first = layoutManager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || first < prefixCount) return

        val items = entries.currentList
        if (items.isEmpty()) return

        val position = (first - prefixCount).coerceAtMost(items.lastIndex)
        if (position < 0) return

        val index = (position downTo 0).firstOrNull { items[it] is JournalListItem.Header } ?: return
        val header = items[index] as JournalListItem.Header
        val holder = cached?.takeIf { it.first == header }?.second
            ?: (cached?.second ?: entries.createViewHolder(parent, 0)).also {
                entries.bindViewHolder(it, index)
                cached = header to it
            }

        val view = holder.itemView
        view.setBackgroundColor(parent.context.getColor(R.color.background))
        val availableWidth = (parent.width - parent.paddingLeft - parent.paddingRight).coerceAtLeast(0)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val headerHeight = view.measuredHeight
        val nextHeaderTop = findNextHeaderTop(parent, items, index)
        val topOffset = computeStickyHeaderOffset(
            headerHeight = headerHeight,
            nextHeaderTop = nextHeaderTop,
            parentPaddingTop = parent.paddingTop
        )

        canvas.withSave {
            if (parent.paddingTop > 0) {
                clipRect(
                    parent.paddingLeft,
                    parent.paddingTop,
                    parent.width - parent.paddingRight,
                    parent.height
                )
            }
            translate(parent.paddingLeft.toFloat(), parent.paddingTop + topOffset)
            view.draw(this)
        }
    }

    private fun findNextHeaderTop(
        parent: RecyclerView,
        items: List<JournalListItem>,
        currentHeaderIndex: Int
    ): Int? {
        var nearestTop: Int? = null
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val adapterPosition = parent.getChildAdapterPosition(child)
            if (adapterPosition == RecyclerView.NO_POSITION) continue
            val childIndex = adapterPosition - prefixCount
            if (childIndex > currentHeaderIndex && items.getOrNull(childIndex) is JournalListItem.Header) {
                val childTop = child.top
                if (nearestTop == null || childTop < nearestTop) {
                    nearestTop = childTop
                }
            }
        }
        return nearestTop
    }

    companion object {
        internal fun computeStickyHeaderOffset(
            headerHeight: Int,
            nextHeaderTop: Int?,
            parentPaddingTop: Int = 0
        ): Float {
            if (headerHeight <= 0 || nextHeaderTop == null) return 0f
            val relativeNextTop = nextHeaderTop - parentPaddingTop
            return if (relativeNextTop <= headerHeight) {
                (relativeNextTop - headerHeight).coerceAtLeast(-headerHeight).toFloat()
            } else {
                0f
            }
        }
    }
}
