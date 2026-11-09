package com.ntq.training.momentbox.feature.journal.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.feature.journal.stats.TrendPoint

class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var points: List<TrendPoint> = emptyList()
    private var totalDaysInPeriod: Int = 31
    private var periodStart: java.time.LocalDate? = null

    private var animationProgress: Float = 1.0f
    private var animator: ValueAnimator? = null
    internal val isAnimating: Boolean get() = animator?.isRunning == true

    // === CÔNG CỤ VẼ CANVAS ===
    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#EEEEF4")
        strokeWidth = 1f * resources.displayMetrics.density
    }

    private val linePathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.primary)
        strokeWidth = 2f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = context.getColor(R.color.text_tertiary)
        textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            10f,
            resources.displayMetrics
        )
        try {
            typeface = ResourcesCompat.getFont(context, R.font.be_vietnam_pro_regular)
        } catch (_: Exception) {
            typeface = Typeface.DEFAULT
        }
    }

    private val linePath = Path()
    private val areaPath = Path()

    fun setData(
        trendPoints: List<TrendPoint>,
        daysInPeriod: Int = 31,
        start: java.time.LocalDate? = null
    ) {
        if (points == trendPoints && totalDaysInPeriod == daysInPeriod && periodStart == start) return
        periodStart = start
        points = trendPoints
        totalDaysInPeriod = daysInPeriod.coerceAtLeast(1)

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animationProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val paddingBottomLabels = 16f * resources.displayMetrics.density
        val chartHeight = h - paddingBottomLabels
        val marginX = 8f * resources.displayMetrics.density
        val usableWidth = w - (2f * marginX)

        // 1. Vẽ 5 đường kẻ ngang cho 5 mức tâm trạng
        val yLevels = FloatArray(5)
        for (k in 0 until 5) {
            val y =
                8f * resources.displayMetrics.density + (k * (chartHeight - 16f * resources.displayMetrics.density) / 4f)
            yLevels[k] = y
            canvas.drawLine(0f, y, w, y, gridLinePaint)
        }

        // 2. Nếu có ít nhất 1 điểm dữ liệu, tính toán tọa độ
        if (points.isNotEmpty()) {
            val coords = mutableListOf<Triple<Float, Float, Int>>() // X, Y, mood

            for (p in points) {
                // [Intern]: Tọa độ X tỉ lệ theo số ngày tương đối so với periodStart (F19)
                val dayFraction = if (totalDaysInPeriod > 1) {
                    (p.dayOffset.toFloat() / (totalDaysInPeriod - 1).toFloat()).coerceIn(0f, 1f)
                } else {
                    0.5f
                }
                val cx = marginX + (dayFraction * usableWidth)

                // Tọa độ Y: Mood 1 ở yLevels[0], Mood 5 ở yLevels[4]
                val moodIndex = (p.mood - 1).coerceIn(0, 4)
                val targetY = yLevels[moodIndex]
                // Nội suy hiệu ứng xuất hiện từ đáy
                val cy = chartHeight - ((chartHeight - targetY) * animationProgress)

                coords.add(Triple(cx, cy, p.mood))
            }

            // 3. Xây dựng đường Path và Vùng Area
            linePath.reset()
            areaPath.reset()

            val first = coords.first()
            linePath.moveTo(first.first, first.second)
            areaPath.moveTo(first.first, chartHeight)
            areaPath.lineTo(first.first, first.second)

            for (i in 1 until coords.size) {
                val pt = coords[i]
                linePath.lineTo(pt.first, pt.second)
                areaPath.lineTo(pt.first, pt.second)
            }

            val last = coords.last()
            areaPath.lineTo(last.first, chartHeight)
            areaPath.close()

            // Dải màu gradient vùng dưới biểu đồ
            val primaryColor = context.getColor(R.color.primary)
            val topColor = Color.argb(
                46,
                Color.red(primaryColor),
                Color.green(primaryColor),
                Color.blue(primaryColor)
            )
            val bottomColor = Color.argb(
                0,
                Color.red(primaryColor),
                Color.green(primaryColor),
                Color.blue(primaryColor)
            )
            areaPaint.shader = LinearGradient(
                0f,
                0f,
                0f,
                chartHeight,
                topColor,
                bottomColor,
                Shader.TileMode.CLAMP
            )

            canvas.drawPath(areaPath, areaPaint)
            canvas.drawPath(linePath, linePathPaint)

            // 4. Vẽ các vòng tròn điểm (Points)
            val pointRadius = 3.6f * resources.displayMetrics.density
            for (pt in coords) {
                pointFillPaint.color = getMoodColor(pt.third)
                canvas.drawCircle(pt.first, pt.second, pointRadius, pointFillPaint)
                canvas.drawCircle(pt.first, pt.second, pointRadius, pointStrokePaint)
            }
        }

        // 5. Vẽ nhãn các mốc ngày bên dưới trục X
        val labelDays = if (totalDaysInPeriod <= 7) (1..totalDaysInPeriod).toList()
        else if (totalDaysInPeriod > 31 && periodStart != null) {
            (0..11).map { month ->
                java.time.temporal.ChronoUnit.DAYS.between(
                    periodStart,
                    periodStart!!.plusMonths(month.toLong()).withDayOfMonth(1)
                ).toInt() + 1
            }
                .filter { it in 1..totalDaysInPeriod }.filterIndexed { i, _ -> i % 2 == 0 }
        } else listOf(1, 5, 10, 15, 20, 25, totalDaysInPeriod).distinct()
            .filter { it <= totalDaysInPeriod }
            .filter { it == totalDaysInPeriod || totalDaysInPeriod - it >= 3 }


        val labelY = h - (2f * resources.displayMetrics.density)
        for (day in labelDays) {
            val fraction = if (totalDaysInPeriod > 1) {
                ((day - 1).toFloat() / (totalDaysInPeriod - 1).toFloat()).coerceIn(0f, 1f)
            } else {
                0.5f
            }
            val lx = marginX + (fraction * usableWidth)
            val date = periodStart?.plusDays(day.toLong() - 1)
            val label = when {
                date == null -> day.toString()
                totalDaysInPeriod > 31 -> date.format(
                    java.time.format.DateTimeFormatter.ofPattern(
                        "MMM",
                        resources.configuration.locales[0]
                    )
                )

                totalDaysInPeriod <= 7 -> date.format(
                    java.time.format.DateTimeFormatter.ofPattern(
                        "d/M",
                        resources.configuration.locales[0]
                    )
                )

                else -> date.dayOfMonth.toString()
            }
            canvas.drawText(label, lx, labelY, labelTextPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); super.onDetachedFromWindow()
    }

    private fun getMoodColor(mood: Int): Int {
        return when (mood) {
            1 -> context.getColor(R.color.mood_1_great)
            2 -> context.getColor(R.color.mood_2_good)
            3 -> context.getColor(R.color.mood_3_okay)
            4 -> context.getColor(R.color.mood_4_sad)
            5 -> context.getColor(R.color.mood_5_terrible)
            else -> context.getColor(R.color.mood_3_okay)
        }
    }
}
