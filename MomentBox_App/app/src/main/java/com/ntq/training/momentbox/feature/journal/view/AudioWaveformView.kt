package com.ntq.training.momentbox.feature.journal.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ntq.training.momentbox.R
import kotlin.math.max

class AudioWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.wave_unplayed)
        style = Paint.Style.FILL
    }

    private var waveformData: List<Int> = emptyList()

    fun setWaveform(data: List<Int>?) {
        waveformData = data.orEmpty()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (waveformData.isEmpty()) {
            // Chưa có payload phân tích: đường trung tính, không giả waveform giọng nói.
            canvas.drawRect(0f, h / 2f, w, h / 2f + resources.displayMetrics.density, paint)
            return
        }
        val data = waveformData
        val barCount = data.size
        val density = resources.displayMetrics.density
        val minBarHeight = 3f * density
        val barCornerRadius = 1.5f * density

        val totalGapRatio = 0.4f // Khoảng trống giữa các cột = 40% bề rộng cột
        val barWidth = w / (barCount + (barCount - 1) * totalGapRatio)
        val gapWidth = barWidth * totalGapRatio

        var currentX = 0f
        for (amplitude in data) {
            val normalizedAmp = (amplitude.coerceIn(5, 100) / 100f)
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
                paint
            )
            currentX += barWidth + gapWidth
        }
    }
}
