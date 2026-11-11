package com.ntq.training.momentbox.feature.journal.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.feature.journal.stats.MoodDistributionItem
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var items: List<MoodDistributionItem> = emptyList()
    private var centerMainText: String = "0"
    private var centerSubText: String = context.getString(R.string.stats_card_entries)
    private var defaultMainText: String = "0"
    private var defaultSubText: String = context.getString(R.string.stats_card_entries)
    private var selectedMood: Int? = null

    private var animationProgress: Float = 1.0f
    private var animator: ValueAnimator? = null
    internal val isAnimating: Boolean get() = animator?.isRunning == true

    var onSectorClickListener: ((mood: Int?) -> Unit)? = null

    // === CÔNG CỤ VẼ CANVAS ===
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val centerMainTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = context.getColor(R.color.text_primary)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            19.8f,
            resources.displayMetrics
        )
        try {
            typeface = ResourcesCompat.getFont(context, R.font.be_vietnam_pro_bold)
        } catch (_: Exception) {
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private val centerSubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = context.getColor(R.color.text_tertiary)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            9.9f,
            resources.displayMetrics
        )
        try {
            typeface = ResourcesCompat.getFont(context, R.font.be_vietnam_pro_regular)
        } catch (_: Exception) {
            typeface = Typeface.DEFAULT
        }
    }

    private val arcBounds = RectF()
    private val strokeWidthDp = 14.4f * resources.displayMetrics.density

    fun setData(
        distribution: List<MoodDistributionItem>,
        totalCount: Int,
        subText: String = context.getString(R.string.stats_card_entries)
    ) {
        if (items == distribution && defaultMainText == totalCount.toString() && defaultSubText == subText) return
        items = distribution
        accessibility.invalidateRoot()
        defaultMainText = totalCount.toString()
        defaultSubText = subText
        centerMainText = defaultMainText
        centerSubText = defaultSubText
        selectedMood = null

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

        val size =
            minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (size <= 0f) return

        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val radius = (size / 2f) - (strokeWidthDp / 2f) - (4.4f * resources.displayMetrics.density)

        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val totalCount = items.sumOf { it.count }

        if (totalCount == 0) {
            // Vẽ vòng cung rỗng mờ khi chưa có dữ liệu
            arcPaint.color = context.getColor(R.color.outline)
            arcPaint.strokeWidth = strokeWidthDp
            canvas.drawCircle(cx, cy, radius, arcPaint)
        } else {
            var currentAngle = -90f // Bắt đầu từ 12 giờ
            val gapAngle =
                if (items.count { it.count > 0 } <= 1) 0f else 2.5f // Khoảng trống giữa các phân đoạn

            for (item in items) {
                if (item.count <= 0) continue

                val fullSweep = (item.count.toFloat() / totalCount) * 360f
                val sweep = (fullSweep - gapAngle).coerceAtLeast(0f) * animationProgress
                if (sweep <= 0f) continue

                val isSelected = selectedMood == item.mood
                arcPaint.color = getMoodColor(item.mood)
                arcPaint.strokeWidth = if (isSelected) strokeWidthDp * 1.2f else strokeWidthDp

                canvas.drawArc(arcBounds, currentAngle, sweep, false, arcPaint)
                currentAngle += fullSweep
            }
        }

        // Vẽ chữ ở tâm
        val mainTextY =
            cy - ((centerMainTextPaint.descent() + centerMainTextPaint.ascent()) / 2f) - (5.4f * resources.displayMetrics.density)
        canvas.drawText(centerMainText, cx, mainTextY, centerMainTextPaint)

        val subTextY = mainTextY + (14.4f * resources.displayMetrics.density)
        canvas.drawText(centerSubText, cx, subTextY, centerSubTextPaint)
    }

    private fun hitSector(x: Float, y: Float): Int? {
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val radius = minOf(
            width - paddingLeft - paddingRight,
            height - paddingTop - paddingBottom
        ) / 2f - strokeWidthDp / 2f - 4.4f * resources.displayMetrics.density
        val dx = x - cx;
        val dy = y - cy
        val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (distance !in (radius - strokeWidthDp * .6f)..(radius + strokeWidthDp * .6f)) return null
        val total = items.sumOf { it.count }
        if (total <= 0) return null
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
        if (angle < 0) angle += 360f
        var start = 0f
        val gap = if (items.count { it.count > 0 } <= 1) 0f else 2.5f
        for (item in items) {
            val full = item.count.toFloat() / total * 360f
            if (item.count > 0 && angle >= start && angle < start + (full - gap).coerceAtLeast(0f) * animationProgress) return item.mood
            start += full
        }
        return null
    }

    private fun selectMood(mood: Int) {
        val item = items.firstOrNull { it.mood == mood && it.count > 0 } ?: return
        selectedMood = if (selectedMood == mood) null else mood
        centerMainText = if (selectedMood == null) defaultMainText else item.count.toString()
        centerSubText = if (selectedMood == null) defaultSubText else "${item.percentage}%"
        invalidate(); accessibility.invalidateRoot(); performClick(); onSectorClickListener?.invoke(
            selectedMood
        )
    }

    private fun sectorNodes(): List<CanvasNode> {
        val total = items.sumOf { it.count }
        if (total == 0 || width == 0 || height == 0) return emptyList()
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val radius = minOf(
            width - paddingLeft - paddingRight,
            height - paddingTop - paddingBottom
        ) / 2f - 4.4f * resources.displayMetrics.density
        var angle = -90f
        return items.filter { it.count > 0 }.map { item ->
            val sweep = item.count.toFloat() / total * 360f
            val points = (0..12).flatMap { index ->
                val a = Math.toRadians((angle + sweep * index / 12).toDouble())
                listOf(
                    (cx + cos(a) * radius).toFloat() to (cy + sin(a) * radius).toFloat(),
                    (cx + cos(a) * (radius - strokeWidthDp)).toFloat() to (cy + sin(a) * (radius - strokeWidthDp)).toFloat()
                )
            }
            angle += sweep
            val rect = Rect(
                points.minOf { it.first }.toInt(),
                points.minOf { it.second }.toInt(),
                points.maxOf { it.first }.toInt().coerceAtLeast(1),
                points.maxOf { it.second }.toInt().coerceAtLeast(1)
            )
            val label = context.getString(
                R.string.journal_sector_description,
                resources.getStringArray(R.array.journal_mood_names)[item.mood - 1],
                item.count,
                item.percentage
            )
            CanvasNode(item.mood, rect, label, selected = selectedMood == item.mood)
        }
    }

    private val accessibility = CanvasAccessibility(this, ::sectorNodes, ::hitSector, ::selectMood)

    init {
        ViewCompat.setAccessibilityDelegate(this, accessibility); isFocusable =
            true
    }

    override fun dispatchHoverEvent(event: MotionEvent) =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent) =
        accessibility.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onFocusChanged(gain: Boolean, direction: Int, rect: Rect?) {
        super.onFocusChanged(gain, direction, rect); accessibility.onFocusChanged(
            gain,
            direction,
            rect
        )
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) hitSector(
            event.x,
            event.y
        )?.let { selectMood(it); return true }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
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
