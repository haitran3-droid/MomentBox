package com.example.momentbox_app.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ntq.training.momentbox.R

class PageIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val dotHeight = 8f * density
    private val inactiveWidth = 8f * density
    private val activeWidth = 24f * density
    private val dotSpacing = 6f * density
    private val cornerRadius = dotHeight / 2f

    private var pageCount: Int = 3
    private var currentPosition: Int = 0
    private var positionOffset: Float = 0f

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.indicator_active)
        style = Paint.Style.FILL
    }

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.indicator_inactive)
        style = Paint.Style.FILL
    }

    private val rectF = RectF()

    fun setPageCount(count: Int) {
        if (pageCount != count) {
            pageCount = count
            requestLayout()
            invalidate()
        }
    }

    fun onPageScrolled(position: Int, offset: Float) {
        currentPosition = position
        positionOffset = offset
        invalidate()
    }

    fun setCurrentPage(position: Int) {
        currentPosition = position
        positionOffset = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = if (pageCount > 0) {
            activeWidth + (pageCount - 1) * inactiveWidth + (pageCount - 1) * dotSpacing + paddingLeft + paddingRight
        } else {
            0f
        }
        val totalHeight = dotHeight + paddingTop + paddingBottom

        val measuredWidth = resolveSize(totalWidth.toInt(), widthMeasureSpec)
        val measuredHeight = resolveSize(totalHeight.toInt(), heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pageCount <= 0) return

        var startX = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val bottom = top + dotHeight

        for (i in 0 until pageCount) {
            val width = when (i) {
                currentPosition -> {
                    // Expanding down to inactive or staying active
                    activeWidth - (activeWidth - inactiveWidth) * positionOffset
                }

                currentPosition + 1 -> {
                    // Growing from inactive to active
                    inactiveWidth + (activeWidth - inactiveWidth) * positionOffset
                }

                else -> inactiveWidth
            }

            val paint = if (i == currentPosition && positionOffset == 0f) {
                activePaint
            } else if (i == currentPosition) {
                // If interpolating, draw active
                activePaint
            } else if (i == currentPosition + 1 && positionOffset > 0.5f) {
                activePaint
            } else {
                inactivePaint
            }

            rectF.set(startX, top, startX + width, bottom)
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)

            startX += width + dotSpacing
        }
    }

    override fun onSaveInstanceState(): Parcelable {
        val bundle = Bundle()
        bundle.putParcelable("super_state", super.onSaveInstanceState())
        bundle.putInt("page_count", pageCount)
        bundle.putInt("current_position", currentPosition)
        bundle.putFloat("position_offset", positionOffset)
        return bundle
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        var restoredState = state
        if (state is Bundle) {
            pageCount = state.getInt("page_count", 3)
            currentPosition = state.getInt("current_position", 0)
            positionOffset = state.getFloat("position_offset", 0f)
            restoredState = state.getParcelable("super_state")
        }
        super.onRestoreInstanceState(restoredState)
    }
}