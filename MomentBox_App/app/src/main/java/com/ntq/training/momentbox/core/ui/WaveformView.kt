package com.ntq.training.momentbox.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.ntq.training.momentbox.R
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private var barWidth = 3f * density
    private var barGap = 2f * density
    private var barMinHeight = 4f * density
    private var barRadius = 1.5f * density
    private var displayCount: Int? = null
    private var displayHeight = .85f
    private var sourceAmplitudes: List<Float> = emptyList()

    private var progress: Float = 0f
    private var amplitudes: List<Float> = emptyList()
    private val defaultBarCount = 44

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        style = Paint.Style.FILL
    }

    private val unplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.wave_unplayed)
        style = Paint.Style.FILL
    }

    private val barRect = RectF()
    var onSeekListener: ((Float) -> Unit)? = null

    init {
        generateDefaultAmplitudes(seed = 3)
    }

    fun setProgress(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (progress != clamped) {
            progress = clamped
            invalidate()
        }
    }

    fun getProgress(): Float = progress

    fun setAmplitudes(amps: List<Float>) {
        if (sourceAmplitudes == amps && (amps.isNotEmpty() || amplitudes.isEmpty())) return
        sourceAmplitudes = amps.toList()
        val count = displayCount
        if (count == null || amps.isEmpty()) amplitudes = amps.toList() else {
            val maximum = amps.maxOrNull()?.coerceAtLeast(.001f) ?: 1f
            amplitudes = List(count) { i ->
                val x = i.toFloat() * (amps.size - 1) / (count - 1);
                val lower = x.toInt();
                val upper = (lower + 1).coerceAtMost(amps.lastIndex)
                ((amps[lower] + (amps[upper] - amps[lower]) * (x - lower)) / maximum).coerceIn(
                    0f,
                    1f
                )
            }
        }
        invalidate()
    }

    fun configureBars(count: Int, gapDp: Float, heightFraction: Float) {
        require(count in 2..120 && gapDp in 0f..5f && heightFraction in 0f..1f)
        displayCount = count; barGap = gapDp * density; displayHeight =
            heightFraction; barMinHeight = 3 * density
    }

    fun generateDefaultAmplitudes(seed: Int = 3) {
        val rnd = Random(seed.toLong())
        val list = ArrayList<Float>(defaultBarCount)
        for (i in 0 until defaultBarCount) {
            // Dải sóng mềm mại có đỉnh ở giữa và thấp hơn ở hai đầu
            val base = 0.2f + 0.6f * sin(i.toDouble() / defaultBarCount * Math.PI).toFloat()
            val noise = (rnd.nextFloat() - 0.5f) * 0.4f
            list.add((base + noise).coerceIn(0.15f, 1.0f))
        }
        amplitudes = list
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minHeight = (32f * density).toInt()
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val finalHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> min(minHeight, heightSize)
            else -> minHeight
        }

        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(widthSize, finalHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (amplitudes.isEmpty()) return

        val contentWidth = width - paddingLeft - paddingRight
        val contentHeight = height - paddingTop - paddingBottom
        if (contentWidth <= 0 || contentHeight <= 0) return

        val count = amplitudes.size
        val totalGaps = (count - 1) * barGap
        var actualBarWidth = (contentWidth - totalGaps) / count
        if (actualBarWidth < 1f * density) {
            actualBarWidth = 1f * density
        }
        val centerY = paddingTop + contentHeight / 2f
        var progressX = paddingLeft + contentWidth * progress

        for (i in 0 until count) {
            val left = paddingLeft + i * (actualBarWidth + barGap)
            val right = left + actualBarWidth
            val amp = amplitudes[i].coerceIn(0.1f, 1f)
            val barH = max(barMinHeight, contentHeight * amp * displayHeight)
            val top = centerY - barH / 2f
            val bottom = centerY + barH / 2f

            barRect.set(left, top, right, bottom)
            val barCenter = (left + right) / 2f
            val paint = if (barCenter <= progressX) playedPaint else unplayedPaint
            canvas.drawRoundRect(barRect, barRadius, barRadius, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val contentWidth = width - paddingLeft - paddingRight
                if (contentWidth > 0) {
                    val touchX = (event.x - paddingLeft).coerceIn(0f, contentWidth.toFloat())
                    val newProgress = touchX / contentWidth
                    setProgress(newProgress)
                    onSeekListener?.invoke(newProgress)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}