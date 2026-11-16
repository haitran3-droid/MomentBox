package com.ntq.training.momentbox.feature.reminder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.graphics.withTranslation
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ReminderBackdropLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gradient: Shader? = null

    private data class Star(val x: Float, val y: Float, val radius: Float, val shader: Shader)

    private val stars = run {
        val density = resources.displayMetrics.density
        listOf(
            Triple(40f, 60f, 1.5f),
            Triple(120f, 110f, 1f),
            Triple(300f, 80f, 1.5f),
            Triple(250f, 180f, 1f),
            Triple(80f, 220f, 1f),
            Triple(320f, 260f, 1.5f)
        ).map { (x, y, r) ->
            Star(
                x * density, y * density, r * density,
                RadialGradient(
                    x * density,
                    y * density,
                    r * density,
                    intArrayOf(0xB3FFFFFF.toInt(), Color.TRANSPARENT),
                    null,
                    Shader.TileMode.CLAMP
                )
            )
        }
    }

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val angle = Math.toRadians(170.0)
        val dx = sin(angle).toFloat()
        val dy = -cos(angle).toFloat()
        val length = abs(w * dx) + abs(h * dy)
        gradient = LinearGradient(
            w / 2f - dx * length / 2,
            h / 2f - dy * length / 2,
            w / 2f + dx * length / 2,
            h / 2f + dy * length / 2,
            intArrayOf(Color.rgb(30, 27, 75), Color.rgb(59, 44, 122), Color.rgb(107, 63, 158)),
            floatArrayOf(0f, .55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.shader = gradient
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        canvas.withTranslation(y = paddingTop.toFloat()) {
            // Tái dùng điểm/shader đã tạo: vẽ lại clock không tạo rác GC trong onDraw.
            var index = 0
            while (index < stars.size) {
                val star = stars[index++]
                paint.shader = star.shader
                drawCircle(star.x, star.y, star.radius, paint)
            }
        }
        paint.shader = null
    }
}