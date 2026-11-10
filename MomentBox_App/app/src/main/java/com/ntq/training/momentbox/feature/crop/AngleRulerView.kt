package com.ntq.training.momentbox.feature.crop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class AngleRulerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    var degrees = 0
        private set

    var onAngle: ((Int) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var x = 0f

    override fun onDraw(c: Canvas) {
        super.onDraw(c)

        val gap = 8 * resources.displayMetrics.density
        paint.strokeWidth = resources.displayMetrics.density
        for (d in -45..45) {
            val xx = width / 2f + (d - degrees) * gap
            paint.color = if (d == degrees) 0xFFFFD54F.toInt() else 0xFF777777.toInt()
            c.drawLine(xx, 8f, xx, if (d % 5 == 0) height - 8f else height * .65f, paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> x = e.x
            MotionEvent.ACTION_MOVE -> {
                val new =
                    (degrees + (x - e.x) / (8 * resources.displayMetrics.density))
                        .roundToInt()
                        .coerceIn(-45, 45)
                if (new != degrees) {
                    degrees = new
                    x = e.x
                    if (new % 5 == 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onAngle?.invoke(new)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    fun setDegrees(value: Int) {
        degrees = value.coerceIn(-45, 45)
        invalidate()
    }

    override fun onSaveInstanceState(): android.os.Parcelable =
        android.os.Bundle().apply {
            putParcelable("parent", super.onSaveInstanceState())
            putInt("degrees", degrees)
        }

    override fun onRestoreInstanceState(state: android.os.Parcelable?) {
        if (state is android.os.Bundle) {
            super.onRestoreInstanceState(
                androidx.core.os.BundleCompat.getParcelable(
                    state,
                    "parent",
                    android.os.Parcelable::class.java,
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
