package com.ntq.training.momentbox.feature.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class RecordButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    var recording = false

    var progress = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)

        val radius = minOf(width, height) / 2f

        val cx = width / 2f

        val cy = height / 2f
        paint.color = 0xFFF7CDCF.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5 * resources.displayMetrics.density
        c.drawCircle(cx, cy, radius - paint.strokeWidth / 2, paint)
        paint.color = 0xFFE5484D.toInt()
        c.drawArc(
            RectF(
                cx - radius + paint.strokeWidth / 2,
                cy - radius + paint.strokeWidth / 2,
                cx + radius - paint.strokeWidth / 2,
                cy + radius - paint.strokeWidth / 2,
            ),
            -90f,
            360 * progress,
            false,
            paint,
        )
        paint.style = Paint.Style.FILL
        c.drawCircle(cx, cy, radius - 12 * resources.displayMetrics.density, paint)
        paint.color = Color.WHITE
        if (recording) {
            val w = 4 * resources.displayMetrics.density
            c.drawRoundRect(
                cx - 2 * w,
                cy - 10 * w / 4,
                cx - w,
                cy + 10 * w / 4,
                w / 2,
                w / 2,
                paint,
            )
            c.drawRoundRect(
                cx + w,
                cy - 10 * w / 4,
                cx + 2 * w,
                cy + 10 * w / 4,
                w / 2,
                w / 2,
                paint,
            )
        } else c.drawCircle(cx, cy, 10 * resources.displayMetrics.density, paint)
    }
}
