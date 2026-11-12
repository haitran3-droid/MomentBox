package com.ntq.training.momentbox.feature.journal.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.data.local.db.DayMood
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

class MoodCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // === TRẠNG THÁI HIỂN THỊ LỊCH ===
    private val clock = (context.applicationContext as? MomentBoxApp)?.journalClock ?: Clock.systemDefaultZone()
    private var displayedYearMonth: YearMonth = YearMonth.now(clock)
    private val currentRealDate: LocalDate = LocalDate.now(clock)
    private var selectedDate: LocalDate? = null

    // Bản đồ lưu trữ cảm xúc theo ngày (key là ngày trong tháng 1..31, value là DayMood)
    private val dayMoodMap = mutableMapOf<Int, DayMood>()

    // Callback khi người dùng chạm chọn một ngày trên lịch
    var onDayClickListener: ((date: LocalDate, hasEntry: Boolean) -> Unit)? = null

    // Callback khi tháng thay đổi (để cập nhật thanh tiêu đề ngoài Card)
    var onMonthChangedListener: ((YearMonth) -> Unit)? = null

    // === CÁC CÔNG CỤ VẼ CANVAS (PAINTS) ===
    private val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = context.getColor(R.color.text_tertiary)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        try {
            typeface = ResourcesCompat.getFont(context, R.font.be_vietnam_pro_semibold)
        } catch (_: Exception) {
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private val dayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13.5f, resources.displayMetrics)
        try {
            typeface = ResourcesCompat.getFont(context, R.font.be_vietnam_pro_semibold)
        } catch (_: Exception) {
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private val cellBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val todayBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.primary)
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val selectedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.text_primary)
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    // === THÔNG SỐ HÌNH HỌC TÍNH TOÁN ===
    private val cellRect = RectF()
    private val cellPadding = 3.5f * resources.displayMetrics.density
    private val cornerRadius = 9f * resources.displayMetrics.density
    private val headerHeight = 28f * resources.displayMetrics.density

    private val daysOfWeekLabels = resources.getStringArray(R.array.journal_weekdays_short).toList()

    // Gesture detector để hỗ trợ vuốt chuyển tháng
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null) return false
            val diffX = e2.x - e1.x
            val diffY = e2.y - e1.y
            if (abs(diffX) > abs(diffY) && abs(diffX) > 100 && abs(velocityX) > 100) {
                if (diffX > 0) {
                    goToPreviousMonth()
                } else {
                    goToNextMonth()
                }
                return true
            }
            return false
        }
    })

    /**
     * Cung cấp dữ liệu cảm xúc mới cho tháng đang hiển thị.
     */
    fun setDayMoods(moods: List<DayMood>) {
        dayMoodMap.clear()
        for (dm in moods) {
            try {
                val date = LocalDate.parse(dm.day)
                if (date.year == displayedYearMonth.year && date.monthValue == displayedYearMonth.monthValue) {
                    dayMoodMap[date.dayOfMonth] = dm
                }
            } catch (_: Exception) {}
        }
        invalidate()
    }

    /**
     * Chuyển đến tháng cụ thể.
     */
    fun setYearMonth(yearMonth: YearMonth) {
        val maxMonth = YearMonth.from(currentRealDate)
        if (yearMonth > maxMonth) return // Không cho phép sang tháng tương lai
        displayedYearMonth = yearMonth
        dayMoodMap.clear()
        requestLayout()
        invalidate()
        accessibility.invalidateRoot()
        onMonthChangedListener?.invoke(displayedYearMonth)
    }

    /** RecyclerView không lưu hierarchy của từng child; VM là nguồn month/selection bền qua recreate. */
    fun restoreCalendarState(month: YearMonth, selected: LocalDate?) {
        if (displayedYearMonth == month && selectedDate == selected) return
        displayedYearMonth = month
        selectedDate = selected
        requestLayout()
        invalidate()
    }

    fun getYearMonth(): YearMonth = displayedYearMonth

    fun goToPreviousMonth() {
        setYearMonth(displayedYearMonth.minusMonths(1))
    }

    fun goToNextMonth() {
        val nextMonth = displayedYearMonth.plusMonths(1)
        val maxMonth = YearMonth.from(currentRealDate)
        if (nextMonth <= maxMonth) {
            setYearMonth(nextMonth)
        }
    }

    fun canGoToNextMonth(): Boolean {
        return displayedYearMonth < YearMonth.from(currentRealDate)
    }

    fun setSelectedDate(date: LocalDate?) {
        selectedDate = date
        invalidate()
    }

    /**
     * Tính toán số tuần thực tế cần hiển thị cho tháng hiện tại.
     */
    private fun calculateTotalWeeks(): Int {
        val firstDayOfMonth = displayedYearMonth.atDay(1)
        val firstDayOfWeekIndex = (firstDayOfMonth.dayOfWeek.value - 1) // Thứ Hai = 0 .. Chủ Nhật = 6
        val totalDays = displayedYearMonth.lengthOfMonth()
        val totalCells = firstDayOfWeekIndex + totalDays
        return (totalCells + 6) / 7
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val cellSize = availableWidth / 7f
        val weeks = calculateTotalWeeks()
        val desiredHeight = (paddingTop + paddingBottom + headerHeight + (weeks * cellSize)).toInt()
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        if (availableWidth <= 0) return
        val cellSize = availableWidth / 7f

        // 1. Vẽ hàng tiêu đề thứ trong tuần (T2 .. CN)
        val headerY = paddingTop + (headerHeight / 2f) - ((headerTextPaint.descent() + headerTextPaint.ascent()) / 2f)
        for (i in 0 until 7) {
            val cx = paddingLeft + (i * cellSize) + (cellSize / 2f)
            canvas.drawText(daysOfWeekLabels[i], cx, headerY, headerTextPaint)
        }

        // 2. Vẽ lưới các ngày trong tháng
        val firstDayOfMonth = displayedYearMonth.atDay(1)
        val startOffset = firstDayOfMonth.dayOfWeek.value - 1 // Thứ Hai = 0
        val daysInMonth = displayedYearMonth.lengthOfMonth()
        val gridStartY = paddingTop + headerHeight

        for (day in 1..daysInMonth) {
            val cellIndex = startOffset + (day - 1)
            val col = cellIndex % 7
            val row = cellIndex / 7

            val left = paddingLeft + (col * cellSize) + cellPadding
            val top = gridStartY + (row * cellSize) + cellPadding
            val right = paddingLeft + ((col + 1) * cellSize) - cellPadding
            val bottom = gridStartY + ((row + 1) * cellSize) - cellPadding

            cellRect.set(left, top, right, bottom)

            val thisDate = displayedYearMonth.atDay(day)
            val isFuture = thisDate.isAfter(currentRealDate)
            val isToday = thisDate.isEqual(currentRealDate)
            val isSelected = selectedDate != null && thisDate.isEqual(selectedDate)
            val dayMood = dayMoodMap[day]

            val cx = cellRect.centerX()
            val cy = cellRect.centerY()
            // [Intern]: Đĩa mood và viền hôm nay hình tròn bán kính 50% theo mockup/CSS 00_head:162 (F22)
            val discRadius = minOf(15f * resources.displayMetrics.density, minOf(cellRect.width(), cellRect.height()) / 2f)

            // Vẽ đĩa tròn nền cảm xúc
            if (dayMood != null && !isFuture) {
                cellBackgroundPaint.color = getMoodLightColor(dayMood.mood)
                canvas.drawCircle(cx, cy, discRadius, cellBackgroundPaint)
            }

            // Vẽ viền tròn hôm nay
            if (isToday) {
                canvas.drawCircle(cx, cy, discRadius, todayBorderPaint)
            }

            // Vẽ viền tròn đang chọn nếu có
            if (isSelected && !isToday) {
                canvas.drawCircle(cx, cy, discRadius, selectedBorderPaint)
            }

            // Vẽ chữ số ngày
            if (isFuture) {
                dayTextPaint.color = context.getColor(R.color.text_tertiary)
                dayTextPaint.alpha = 80 // Làm mờ ngày tương lai
            } else if (dayMood != null) {
                dayTextPaint.color = getMoodTextColor(dayMood.mood)
                dayTextPaint.alpha = 255
            } else {
                dayTextPaint.color = context.getColor(R.color.text_primary)
                dayTextPaint.alpha = 255
            }

            val textY = cellRect.centerY() - ((dayTextPaint.descent() + dayTextPaint.ascent()) / 2f)
            canvas.drawText(day.toString(), cellRect.centerX(), textY, dayTextPaint)
        }
    }


    private fun dateNodes(): List<CanvasNode> {
        val cell = (width - paddingLeft - paddingRight) / 7f
        if (cell <= 0) return emptyList()
        val offset = displayedYearMonth.atDay(1).dayOfWeek.value - 1
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(resources.configuration.locales[0])
        return (1..displayedYearMonth.lengthOfMonth()).map { day ->
            val index = offset + day - 1
            val left = paddingLeft + (index % 7) * cell
            val top = paddingTop + headerHeight + (index / 7) * cell
            val date = displayedYearMonth.atDay(day)
            val mood = dayMoodMap[day]?.mood
            val label = if (mood == null) date.format(formatter) else context.getString(R.string.journal_day_mood_description,
                date.format(formatter), resources.getStringArray(R.array.journal_mood_names)[mood.coerceIn(1, 5) - 1])
            CanvasNode(day, Rect(left.toInt(), top.toInt(), (left + cell).toInt(), (top + cell).toInt()), label,
                !date.isAfter(currentRealDate), date == selectedDate)
        }
    }
    private fun activateDay(day: Int) {
        val date = displayedYearMonth.atDay(day)
        if (date.isAfter(currentRealDate)) return
        selectedDate = date
        invalidate()
        accessibility.invalidateRoot()
        performClick()
        onDayClickListener?.invoke(date, dayMoodMap.containsKey(day))
    }
    private val accessibility = CanvasAccessibility(this, ::dateNodes,
        { x, y -> dateNodes().firstOrNull { it.bounds.contains(x.toInt(), y.toInt()) }?.id }, ::activateDay)
    init {
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        isFocusable = true
    }
    override fun dispatchHoverEvent(event: MotionEvent) = accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    override fun dispatchKeyEvent(event: KeyEvent) = accessibility.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    override fun onFocusChanged(gain: Boolean, direction: Int, rect: Rect?) {
        super.onFocusChanged(gain, direction, rect)
        accessibility.onFocusChanged(gain, direction, rect)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gestureDetector.onTouchEvent(event)) {
            return true
        }

        if (event.action == MotionEvent.ACTION_UP) {
            val node = dateNodes().firstOrNull { it.bounds.contains(event.x.toInt(), event.y.toInt()) }
            if (node?.enabled == true) {
                activateDay(node.id)
                return true
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun getMoodLightColor(mood: Int): Int {
        return when (mood) {
            1 -> context.getColor(R.color.mood_1_great_light)
            2 -> context.getColor(R.color.mood_2_good_light)
            3 -> context.getColor(R.color.mood_3_okay_light)
            4 -> context.getColor(R.color.mood_4_sad_light)
            5 -> context.getColor(R.color.mood_5_terrible_light)
            else -> context.getColor(R.color.mood_3_okay_light)
        }
    }

    private fun getMoodTextColor(mood: Int): Int {
        return when (mood) {
            1 -> context.getColor(R.color.mood_1_text)
            2 -> context.getColor(R.color.mood_2_text)
            3 -> context.getColor(R.color.mood_3_text)
            4 -> context.getColor(R.color.mood_4_text)
            5 -> context.getColor(R.color.mood_5_text)
            else -> context.getColor(R.color.mood_3_text)
        }
    }

    // === LƯU VÀ KHÔI PHỤC TRẠNG THÁI VIEW (CONFIGURATION CHANGES) ===

    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        val bundle = Bundle()
        bundle.putParcelable("SUPER_STATE", superState)
        bundle.putInt("YEAR", displayedYearMonth.year)
        bundle.putInt("MONTH", displayedYearMonth.monthValue)
        selectedDate?.let { bundle.putString("SELECTED_DATE", it.toString()) }
        return bundle
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            val superState = BundleCompat.getParcelable(state, "SUPER_STATE", Parcelable::class.java)
            super.onRestoreInstanceState(superState)
            val year = state.getInt("YEAR", currentRealDate.year)
            val month = state.getInt("MONTH", currentRealDate.monthValue)
            displayedYearMonth = YearMonth.of(year, month)
            val selectedStr = state.getString("SELECTED_DATE")
            selectedDate = if (selectedStr != null) LocalDate.parse(selectedStr) else null
            requestLayout()
            invalidate()
            // [Intern]: Đồng bộ tháng với ViewModel và Header sau khi khôi phục trạng thái (F11)
            onMonthChangedListener?.invoke(displayedYearMonth)
        } else {
            super.onRestoreInstanceState(state)
        }
    }
}
