package com.ntq.training.momentbox.feature.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.ntq.training.momentbox.R

class ChatWaveformView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var samples = emptyList<Float>()
    private var progress = 0f
    private var played = ContextCompat.getColor(c, R.color.primary)
    private var pending = ContextCompat.getColor(c, R.color.wave_unplayed)
    var seek: ((Float) -> Unit)? = null
    fun render(values: List<Float>, position: Float, own: Boolean) {
        samples = values
        progress = position.coerceIn(0f, 1f)
        played =
            if (own) Color.WHITE else ContextCompat.getColor(context, R.color.primary)
            pending =
            if (own) 0x80FFFFFF.toInt() else ContextCompat.getColor(
                context,
                R.color.wave_unplayed
            )
            invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        paint.color = pending
        if (samples.isEmpty()) {
            canvas.drawRect(0f, height / 2f, width.toFloat(), height / 2f + d, paint)
            return
        }
        val gap = 2 * d
        val bar = ((width - gap * (samples.size - 1)) / samples.size).coerceAtLeast(d)
        samples.forEachIndexed { i, amplitude ->
            val left = i * (bar + gap)
            val h = (height * amplitude.coerceIn(0f, 1f)).coerceAtLeast(3 * d)
            paint.color = if ((left + bar / 2) / width <= progress) played else pending
            canvas.drawRoundRect(
                left,
                (height - h) / 2,
                left + bar,
                (height + h) / 2,
                1.5f * d,
                1.5f * d,
                paint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (seek == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                seek?.invoke(
                    (event.x / width.coerceAtLeast(
                        1
                    )).coerceIn(0f, 1f)
                )
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
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