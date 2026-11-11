package com.ntq.training.momentbox.feature.crop

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.os.BundleCompat
import kotlin.math.roundToInt

class AngleRulerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    var degrees = 0
        private set

    var onAngle: ((Int) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var dragStartX = 0f

    private var dragStartDegrees = 0
    private var fade: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fade =
            LinearGradient(
                0f,
                0f,
                w.toFloat(),
                0f,
                intArrayOf(0x00FFFFFF, 0x73FFFFFF, 0x73FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, .2f, .8f, 1f),
                Shader.TileMode.CLAMP,
            )
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)

        val dp = resources.displayMetrics.density

        val gap = 9 * dp
        paint.shader = fade
        paint.strokeWidth = 1.5f * dp
        for (d in -45..45) {
            val xx = width / 2f + (d - degrees) * gap
            c.drawLine(xx, 0f, xx, minOf(height.toFloat(), 30 * dp), paint)
        }
        paint.shader = null
        paint.color = 0xFFFFD54F.toInt()
        paint.strokeWidth = 2.5f * dp
        c.drawLine(width / 2f, 0f, width / 2f, minOf(height.toFloat(), 36 * dp), paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = e.x
                dragStartDegrees = degrees
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                // Tính từ điểm bắt đầu gesture, không cộng từng số đã làm tròn: nhiều bước1dp phải
                // cho cùng góc với một bước45dp, và kéo ngược về điểm đầu phải về đúng góc ban đầu.
                val next =
                    (dragStartDegrees + (dragStartX - e.x) / (9 * resources.displayMetrics.density))
                        .roundToInt()
                        .coerceIn(-45, 45)
                if (next != degrees) {
                    degrees = next
                    if (next % 5 == 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onAngle?.invoke(next)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }

            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    fun setDegrees(value: Int) {
        degrees = value.coerceIn(-45, 45)
        invalidate()
    }

    override fun onSaveInstanceState(): Parcelable =
        Bundle().apply {
            putParcelable("parent", super.onSaveInstanceState())
            putInt("degrees", degrees)
        }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            super.onRestoreInstanceState(
                BundleCompat.getParcelable(
                    state,
                    "parent",
                    Parcelable::class.java,
                )
            )
            setDegrees(state.getInt("degrees"))
        } else super.onRestoreInstanceState(state)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
