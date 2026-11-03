package com.example.momentbox_app.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SplashBackdropLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    init {
        // Cho phép ViewGroup thực thi phương thức onDraw
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radians = Math.toRadians(165.0)
        val dx = sin(radians).toFloat()
        val dy = -cos(radians).toFloat()
        val length = abs(w * dx) + abs(h * dy)
        paint.shader = LinearGradient(
            w / 2 - dx * length / 2, h / 2 - dy * length / 2,
            w / 2 + dx * length / 2, h / 2 + dy * length / 2,
            intArrayOf(Color.rgb(116, 100, 238), Color.rgb(91, 75, 219), Color.rgb(59, 45, 176)),
            floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        // CSS radial-gradient(circle) mặc định dùng khoảng cách tới góc xa nhất của hình vuông.
        paint.shader = RadialGradient(
            180 * density, paddingTop + 330 * density, 210 * density * sqrt(2f),
            intArrayOf(0x24FFFFFF, 0x00FFFFFF), floatArrayOf(0f, .65f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(
            w - 50 * density, paddingTop + 70 * density, 110 * density * sqrt(2f),
            intArrayOf(0x73FF7A59, 0x00FF7A59), floatArrayOf(0f, .7f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        super.onDraw(canvas)
    }
}