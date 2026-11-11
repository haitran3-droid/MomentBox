package com.ntq.training.momentbox.feature.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ntq.training.momentbox.R

class ChatTypingView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG);
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private fun updateAnimation() {
        if (!isAttachedToWindow || !isShown) {
            animator?.cancel(); return
        }
        val current = animator ?: ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000; repeatCount = ValueAnimator.INFINITE; addUpdateListener {
            phase = it.animatedValue as Float; invalidate()
        }
        }.also { animator = it }
        if (!current.isStarted) current.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility); updateAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density; paint.color =
            ContextCompat.getColor(
                context,
                R.color.surface
            )
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 18 * d, 18 * d, paint)
        paint.color = ContextCompat.getColor(
            context,
            R.color.text_tertiary
        )
        repeat(3) { i ->
            paint.alpha =
                (90 + 165 * (.5 + .5 * kotlin.math.sin(2 * Math.PI * (phase - i * .2)))).toInt()
                    .coerceIn(0, 255); canvas.drawCircle(
            (17.5f + 11 * i) * d,
            height / 2f,
            3.5f * d,
            paint
        )
        }
    }
}
