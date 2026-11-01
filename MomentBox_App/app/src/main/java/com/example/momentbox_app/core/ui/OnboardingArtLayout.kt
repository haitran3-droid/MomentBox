package com.example.momentbox_app.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class OnboardingArtLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radius = 32 * resources.displayMetrics.density

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val angle = Math.toRadians(160.0)
        val dx = sin(angle).toFloat()
        val dy = -cos(angle).toFloat()
        val length = abs(w * dx) + abs(h * dy)
        paint.shader = LinearGradient(
            w / 2f - dx * length / 2, h / 2f - dy * length / 2,
            w / 2f + dx * length / 2, h / 2f + dy * length / 2,
            Color.rgb(238, 234, 254), Color.rgb(255, 233, 226), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
        super.onDraw(canvas)
    }
}