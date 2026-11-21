package com.ntq.training.momentbox.feature.journal.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
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

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.wave_unplayed)
            style = Paint.Style.FILL
        }

    private var waveformData: List<Int> = emptyList()
    private var displayedData: List<Int> = emptyList()
    private var cachedWidth = -1
    private var cachedMaxBars = -1

    fun setWaveform(data: List<Int>?) {
        waveformData = data.orEmpty()
        cachedWidth = -1
        cachedMaxBars = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        if (waveformData.isEmpty()) {
            // Chưa có payload phân tích: đường trung tính, không giả waveform giọng nói.
            canvas.drawRect(0f, h / 2f, w, h / 2f + density, paint)
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

            canvas.drawRoundRect(
                currentX,
                top,
                currentX + barWidth,
                bottom,
                barCornerRadius,
                barCornerRadius,
                paint,
            )
            currentX += barWidth + gapWidth
        }
    }

    companion object {
        internal const val MAX_VISIBLE_BARS = 100
        private const val MIN_SLOT_WIDTH_DP = 3.5f

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
