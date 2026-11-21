package com.ntq.training.momentbox.feature.player

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone

class PlayerChipRow @JvmOverloads constructor(c: Context, a: AttributeSet? = null) :
    ViewGroup(c, a) {
    private class Row(
        val children: MutableList<View> = mutableListOf(),
        var width: Int = 0,
        var height: Int = 0,
    )

    private val rowPool = mutableListOf<Row>()
    private var rowCount = 0

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val gap = (8 * resources.displayMetrics.density).toInt()
        rowCount = 0
        var totalHeight = 0
        var currentRow = acquireRow(0)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isGone) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val extra = if (currentRow.children.isEmpty()) 0 else gap
            if (currentRow.children.isNotEmpty() && currentRow.width + extra + child.measuredWidth > available) {
                totalHeight += currentRow.height
                rowCount++
                currentRow = acquireRow(rowCount)
            }
            if (currentRow.children.isNotEmpty()) currentRow.width += gap
            currentRow.children.add(child)
            currentRow.width += child.measuredWidth
            currentRow.height = maxOf(currentRow.height, child.measuredHeight)
        }
        if (currentRow.children.isNotEmpty()) {
            totalHeight += currentRow.height
            rowCount++
        }
        setMeasuredDimension(
            resolveSize(width, w),
            resolveSize(totalHeight + paddingTop + paddingBottom, h)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = width - paddingLeft - paddingRight
        val minGap = (8 * resources.displayMetrics.density).toInt()
        var y = paddingTop
        for (i in 0 until rowCount) {
            val row = rowPool[i]
            val gap =
                if (row.children.size > 1) minGap + (available - row.width) / (row.children.size - 1) else 0
            var x = paddingLeft
            for (j in row.children.indices) {
                val child = row.children[j]
                child.layout(
                    x,
                    y,
                    x + child.measuredWidth,
                    y + child.measuredHeight
                )
                x += child.measuredWidth + gap
            }
            y += row.height
        }
    }

    private fun acquireRow(index: Int): Row {
        val row = if (index < rowPool.size) {
            rowPool[index]
        } else {
            Row().also { rowPool.add(it) }
        }
        row.children.clear()
        row.width = 0
        row.height = 0
        return row
    }

    override fun generateDefaultLayoutParams() =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet) = LayoutParams(context, attrs)
}
