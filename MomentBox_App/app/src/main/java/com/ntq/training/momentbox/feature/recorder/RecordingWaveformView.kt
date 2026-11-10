package com.ntq.training.momentbox.feature.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class RecordingWaveformView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var samples: List<Int> = emptyList()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setWaveform(values: List<Int>) {
        samples = values.takeLast(90)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)

        val dp = resources.displayMetrics.density

        val mid = height / 2f

        val head = width / 2f
        paint.color = 0xFFD0CFDC.toInt()
        paint.strokeWidth = dp
        paint.pathEffect = DashPathEffect(floatArrayOf(4 * dp, 4 * dp), 0f)
        c.drawLine(head, mid, width.toFloat(), mid, paint)
        paint.pathEffect = null
        val spacing = 5 * dp
        samples.asReversed().forEachIndexed { i, v ->
            val x = head - i * spacing
            if (x >= 0) {
                paint.color = 0xFFE5484D.toInt()
                paint.alpha = (255 - i * 3).coerceIn(20, 255)

                val h = (v / 100f * height * .36f).coerceAtLeast(dp)
                c.drawRoundRect(
                    x - 1.5f * dp,
                    mid - h,
                    x + 1.5f * dp,
                    mid + h,
                    1.5f * dp,
                    1.5f * dp,
                    paint,
                )
            }
        }
        paint.alpha = 255
        paint.strokeWidth = dp
        paint.color = 0xFFE5484D.toInt()
        c.drawLine(head, 20 * dp, head, height - 20 * dp, paint)
        c.drawCircle(head, 20 * dp, 7 * dp, paint)
    }
}
