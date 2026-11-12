package com.ntq.training.momentbox.feature.player

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

class PlayerChipRow @JvmOverloads constructor(c: Context, a: AttributeSet? = null) :
    ViewGroup(c, a) {
        private data class Row(val children: List<View>, val width: Int, val height: Int)

        private var rows = emptyList<Row>()
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
            val gap = (8 * resources.displayMetrics.density).toInt()
            val values = mutableListOf<Row>()
            var line = mutableListOf<View>()
            var used = 0
            var high = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                child.measure(
                MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
                val extra = if (line.isEmpty()) 0 else gap
                if (line.isNotEmpty() && used + extra + child.measuredWidth > available) {
                    values.add(Row(line.toList(), used, high))
                    line.clear()
                    used = 0
                    high = 0
                }
                if (line.isNotEmpty()) used += gap
                line.add(child)
                used += child.measuredWidth
                high = maxOf(high, child.measuredHeight)
            }
            if (line.isNotEmpty()) values.add(Row(line.toList(), used, high))
            rows = values
            setMeasuredDimension(
            resolveSize(width, w),
            resolveSize(values.sumOf { it.height } + paddingTop + paddingBottom, h)
        )
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val available = width - paddingLeft - paddingRight
            val minGap = (8 * resources.displayMetrics.density).toInt()
            var y = paddingTop
            rows.forEach { row ->
            val gap =
                if (row.children.size > 1) minGap + (available - row.width) / (row.children.size - 1) else 0
            var x = paddingLeft
            row.children.forEach { child ->
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

        override fun generateDefaultLayoutParams() =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

        override fun generateLayoutParams(attrs: AttributeSet) = LayoutParams(context, attrs)
    }