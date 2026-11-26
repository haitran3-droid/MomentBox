package com.ntq.training.momentbox.feature.journal.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.ntq.training.momentbox.R
import kotlin.math.max

class AudioWaveformView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val playedPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.primary)
            style = Paint.Style.FILL
        }

    private val unplayedPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.wave_unplayed)
            style = Paint.Style.FILL
        }

    private var waveformData: List<Int> = emptyList()
    private var displayedData: List<Int> = emptyList()
    private var cachedWidth = -1
    private var cachedMaxBars = -1
    private var progress: Float = 0f

    var onSeekListener: ((Float) -> Unit)? = null

    fun setWaveform(data: List<Int>?) {
        val next = data.orEmpty()
        if (waveformData == next) return
        waveformData = next
        cachedWidth = -1
        cachedMaxBars = -1
        invalidate()
    }

    fun setProgress(value: Float) {
        val clamped = clampProgress(value)
        if (progress != clamped) {
            progress = clamped
            invalidate()
        }
    }

    fun getProgress(): Float = progress

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        if (waveformData.isEmpty()) {
            // Chưa có payload phân tích: đường trung tính, vẫn hiển thị phần đã phát.
            val top = h / 2f
            val bottom = top + density
            canvas.drawRect(0f, top, w, bottom, unplayedPaint)
            if (progress > 0f) {
                canvas.drawRect(0f, top, w * progress, bottom, playedPaint)
            }
            return
        }

        val maxBars = (w / (MIN_SLOT_WIDTH_DP * density)).toInt().coerceIn(1, MAX_VISIBLE_BARS)
        if (width != cachedWidth || maxBars != cachedMaxBars) {
            cachedWidth = width
            cachedMaxBars = maxBars
            displayedData = downsample(waveformData, maxBars)
        }

        val data = displayedData
        if (data.isEmpty()) return
        val barCount = data.size
        val minBarHeight = 3f * density
        val barCornerRadius = 1.5f * density

        val totalGapRatio = 0.4f // Khoảng trống giữa các cột = 40% bề rộng cột
        val barWidth = w / (barCount + (barCount - 1) * totalGapRatio)
        val gapWidth = barWidth * totalGapRatio

        var currentX = 0f
        for (amplitude in data) {
            val normalizedAmp = amplitude.coerceIn(5, 100) / 100f
            val barHeight = max(minBarHeight, normalizedAmp * h)
            val top = (h - barHeight) / 2f
            val bottom = top + barHeight
            val barCenterX = currentX + barWidth / 2f
            val barPaint = if (isBarPlayed(barCenterX, w, progress)) playedPaint else unplayedPaint

            canvas.drawRoundRect(
                currentX,
                top,
                currentX + barWidth,
                bottom,
                barCornerRadius,
                barCornerRadius,
                barPaint,
            )
            currentX += barWidth + gapWidth
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val seekListener = onSeekListener ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val ratio = touchRatio(event.x, width)
                setProgress(ratio)
                seekListener(ratio)
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
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

    companion object {
        internal const val MAX_VISIBLE_BARS = 100
        private const val MIN_SLOT_WIDTH_DP = 3.5f

        internal fun clampProgress(value: Float): Float =
            if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

        internal fun isBarPlayed(barCenterX: Float, totalWidth: Float, progress: Float): Boolean {
            if (totalWidth <= 0f || !progress.isFinite() || progress <= 0f) return false
            if (progress >= 1f) return true
            return barCenterX <= totalWidth * progress
        }

        internal fun touchRatio(touchX: Float, totalWidth: Int): Float {
            if (totalWidth <= 0 || !touchX.isFinite()) return 0f
            return (touchX / totalWidth.toFloat()).coerceIn(0f, 1f)
        }

        /**
         * Downsamples an amplitude series to at most [maxBars] bars using peak-per-bucket
         * aggregation so 60s–300s recordings (up to 6,000 samples) remain crisp and readable.
         */
        internal fun downsample(
            samples: List<Int>,
            maxBars: Int = MAX_VISIBLE_BARS,
        ): List<Int> {
            if (samples.isEmpty() || maxBars <= 0) return emptyList()
            if (samples.size <= maxBars) return samples
            val total = samples.size
            val result = ArrayList<Int>(maxBars)
            for (i in 0 until maxBars) {
                val start = ((i.toLong() * total) / maxBars).toInt()
                val end =
                    (((i + 1).toLong() * total) / maxBars)
                        .toInt()
                        .coerceIn(start + 1, total)
                var peak = samples[start]
                for (j in (start + 1) until end) {
                    if (samples[j] > peak) peak = samples[j]
                }
                result.add(peak)
            }
            return result
        }
    }
}
