package com.ntq.training.momentbox.feature.journal.stats

import com.ntq.training.momentbox.data.local.db.JournalEntryWithDetails
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

enum class StatsPeriod {
    WEEK,
    MONTH,
    YEAR
}

data class MoodDistributionItem(
    val mood: Int,
    val count: Int,
    val percentage: Int
)

data class TrendPoint(
    val dayOfMonth: Int,
    val mood: Int,
    val dateEpochMs: Long,
    val dayOffset: Int = 0
)

data class TagItem(
    val tag: String,
    val count: Int
)

/** Dữ liệu insight thuần Kotlin; Activity chịu trách nhiệm chọn Resources theo locale. */
data class JournalInsight(val empty: Boolean, val frequentDay: Int = 1, val count: Int = 0, val improvementPct: Int? = null)

data class JournalStatsResult(
    val period: StatsPeriod,
    val totalEntries: Int,
    val deltaEntriesVsPreviousPeriod: Int,
    val currentStreakDays: Int,
    val totalWords: Int,
    val totalAudioMinutes: Long,
    val moodDistribution: List<MoodDistributionItem>,
    val trendPoints: List<TrendPoint>,
    val topTags: List<TagItem>,
    val remainingTagsCount: Int,
    val insight: JournalInsight,
    val dateRangeText: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate
)

class JournalStatsCalculator(
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    /**
     * Tính toán toàn bộ chỉ số cho một kỳ cụ thể ([period]) dựa trên danh sách bài viết hợp lệ.
     */
    fun calculateStats(
        allValidEntries: List<JournalEntryWithDetails>,
        period: StatsPeriod,
        anchorDate: LocalDate = LocalDate.now(zoneId),
        today: LocalDate = anchorDate
    ): JournalStatsResult {
        val (currentStart, fullEnd) = getPeriodDateRange(period, anchorDate)
        val currentEnd = minOf(fullEnd, today)
        val (prevStart, prevEnd) = getPreviousPeriodDateRange(period, anchorDate)

        // Phân tách bài viết thuộc kỳ hiện tại và kỳ trước
        val currentPeriodEntries = mutableListOf<JournalEntryWithDetails>()
        var prevPeriodEntriesCount = 0

        for (item in allValidEntries) {
            val date = Instant.ofEpochMilli(item.entry.createdAt).atZone(zoneId).toLocalDate()
            if (!date.isBefore(currentStart) && !date.isAfter(currentEnd)) {
                currentPeriodEntries.add(item)
            } else if (!date.isBefore(prevStart) && !date.isAfter(prevEnd)) {
                prevPeriodEntriesCount++
            }
        }

        val totalEntries = currentPeriodEntries.size
        val deltaEntries = totalEntries - prevPeriodEntriesCount

        // 1. Tính toán số từ và phút ghi âm
        var totalWords = 0
        var totalAudioMs = 0L
        for (item in currentPeriodEntries) {
            totalWords += item.entry.wordCount
            for (att in item.attachments) {
                if (att.type.equals("audio", ignoreCase = true)) {
                    totalAudioMs += att.durationMs
                }
            }
        }
        val totalAudioMinutes = ((totalAudioMs + 30000L) / 60000L) // Làm tròn phút

        // 2. Tính chuỗi ngày viết liên tục (Streak)
        val streakDays = calculateStreak(allValidEntries, today)

        // 3. Phân bố cảm xúc (1..5)
        val moodCounts = IntArray(6) // index 1..5
        for (item in currentPeriodEntries) {
            val m = item.entry.mood.coerceIn(1, 5)
            moodCounts[m]++
        }
        // Largest remainder bảo toàn tổng 100%; round độc lập có thể thành 101% (7/5/3/2/1).
        val percentages = IntArray(6)
        if (totalEntries > 0) {
            for (mood in 1..5) percentages[mood] = (moodCounts[mood] * 100L / totalEntries).toInt()
            val remainderOrder = (1..5).sortedWith(compareByDescending<Int> { moodCounts[it] * 100L % totalEntries }.thenBy { it })
            repeat(100 - percentages.sum()) { percentages[remainderOrder[it]]++ }
        }
        val moodDistribution = (1..5).map { mood -> MoodDistributionItem(mood, moodCounts[mood], percentages[mood]) }

        // 4. Xu hướng cảm xúc theo ngày (Trend Points)
        val dailyLatestMood = mutableMapOf<LocalDate, Pair<Long, Int>>() // Date -> (createdAt, mood)
        for (item in currentPeriodEntries) {
            val date = Instant.ofEpochMilli(item.entry.createdAt).atZone(zoneId).toLocalDate()
            val existing = dailyLatestMood[date]
            if (existing == null || item.entry.createdAt > existing.first) {
                dailyLatestMood[date] = Pair(item.entry.createdAt, item.entry.mood)
            }
        }
        val trendPoints = dailyLatestMood.entries
            .sortedBy { it.key }
            .map { entry ->
                // [Intern]: Tính khoảng cách số ngày tính từ ngày bắt đầu kỳ để vẽ trục X chuẩn xác (F19)
                val dayOffset = ChronoUnit.DAYS.between(currentStart, entry.key).toInt()
                TrendPoint(
                    dayOfMonth = entry.key.dayOfMonth,
                    mood = entry.value.second,
                    dateEpochMs = entry.key.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                    dayOffset = dayOffset
                )
            }

        // 5. Top 3 nhãn phổ biến nhất
        val tagFrequency = mutableMapOf<String, Int>()
        for (item in currentPeriodEntries) {
            for (tagEntity in item.tags) {
                val cleanTag = tagEntity.tag.trim().removePrefix("#").trim()
                if (cleanTag.isNotBlank()) {
                    tagFrequency[cleanTag] = (tagFrequency[cleanTag] ?: 0) + 1
                }
            }
        }
        val sortedTags = tagFrequency.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { TagItem(it.key, it.value) }

        val top3Tags = sortedTags.take(3)
        val remainingTagsCount = (sortedTags.size - 3).coerceAtLeast(0)

        // 6. Tạo câu thông tin thông minh (Smart Insight)
        val insight = generateSmartInsight(currentPeriodEntries)

        // 7. Nhãn khoảng ngày
        val dateRangeText = "${currentStart.dayOfMonth}/${currentStart.monthValue} – ${currentEnd.dayOfMonth}/${currentEnd.monthValue}"

        return JournalStatsResult(
            period = period,
            totalEntries = totalEntries,
            deltaEntriesVsPreviousPeriod = deltaEntries,
            currentStreakDays = streakDays,
            totalWords = totalWords,
            totalAudioMinutes = totalAudioMinutes,
            moodDistribution = moodDistribution,
            trendPoints = trendPoints,
            topTags = top3Tags,
            remainingTagsCount = remainingTagsCount,
            insight = insight,
            dateRangeText = dateRangeText,
            periodStart = currentStart, periodEnd = currentEnd
        )
    }

    private fun calculateStreak(allEntries: List<JournalEntryWithDetails>, anchorDate: LocalDate): Int {
        if (allEntries.isEmpty()) return 0

        val distinctDays = allEntries.map {
            Instant.ofEpochMilli(it.entry.createdAt).atZone(zoneId).toLocalDate()
        }.filterNot { it.isAfter(anchorDate) }.toSet()

        // [Intern]: BA yêu cầu lấy các ngày có bài distinct theo thứ tự giảm dần rồi đếm từ ngày có bài gần nhất tới khi đứt (F12)
        val sortedDays = distinctDays.sortedDescending()
        if (sortedDays.isEmpty()) return 0

        var currentDay = sortedDays.first()
        var streak = 0
        while (distinctDays.contains(currentDay)) {
            streak++
            currentDay = currentDay.minusDays(1)
        }
        return streak
    }

    private fun generateSmartInsight(entries: List<JournalEntryWithDetails>): JournalInsight {
        if (entries.isEmpty()) {
            return JournalInsight(empty = true)
        }

        var weekendMoodSum = 0
        var weekendCount = 0
        var weekdayMoodSum = 0
        var weekdayCount = 0
        val dayOfWeekCounts = IntArray(8) // 1..7 (Thứ Hai .. Chủ Nhật)

        for (item in entries) {
            val date = Instant.ofEpochMilli(item.entry.createdAt).atZone(zoneId).toLocalDate()
            val dow = date.dayOfWeek.value // 1 (Mon) .. 7 (Sun)
            dayOfWeekCounts[dow]++

            val moodVal = item.entry.mood
            if (dow == 6 || dow == 7) {
                weekendMoodSum += moodVal
                weekendCount++
            } else {
                weekdayMoodSum += moodVal
                weekdayCount++
            }
        }

        // Tìm thứ viết nhiều nhất
        var mostFrequentDow = 1
        var maxCount = -1
        for (i in 1..7) {
            if (dayOfWeekCounts[i] > maxCount) {
                maxCount = dayOfWeekCounts[i]
                mostFrequentDow = i
            }
        }
        // So sánh tâm trạng cuối tuần vs ngày thường
        // Lưu ý: Tâm trạng 1 là Tuyệt vời nhất, 5 là Tệ nhất
        if (weekendCount > 0 && weekdayCount > 0) {
            val avgWeekend = weekendMoodSum.toDouble() / weekendCount
            val avgWeekday = weekdayMoodSum.toDouble() / weekdayCount
            if (avgWeekend < avgWeekday) {
                // Điểm trung bình cuối tuần nhỏ hơn -> cảm xúc tích cực hơn
                val improvementPct = (((avgWeekday - avgWeekend) / avgWeekday) * 100.0).roundToInt()
                return JournalInsight(false, mostFrequentDow, maxCount, improvementPct)
            }
        }

        return JournalInsight(false, mostFrequentDow, maxCount)
    }

    private fun getPeriodDateRange(period: StatsPeriod, anchor: LocalDate): Pair<LocalDate, LocalDate> {
        return when (period) {
            StatsPeriod.WEEK -> {
                val start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val end = anchor.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                Pair(start, end)
            }
            StatsPeriod.MONTH -> {
                val ym = YearMonth.from(anchor)
                Pair(ym.atDay(1), ym.atEndOfMonth())
            }
            StatsPeriod.YEAR -> {
                Pair(LocalDate.of(anchor.year, 1, 1), LocalDate.of(anchor.year, 12, 31))
            }
        }
    }

    private fun getPreviousPeriodDateRange(period: StatsPeriod, anchor: LocalDate): Pair<LocalDate, LocalDate> {
        return when (period) {
            StatsPeriod.WEEK -> {
                val prevAnchor = anchor.minusWeeks(1)
                getPeriodDateRange(StatsPeriod.WEEK, prevAnchor)
            }
            StatsPeriod.MONTH -> {
                val prevAnchor = anchor.minusMonths(1)
                getPeriodDateRange(StatsPeriod.MONTH, prevAnchor)
            }
            StatsPeriod.YEAR -> {
                val prevAnchor = anchor.minusYears(1)
                getPeriodDateRange(StatsPeriod.YEAR, prevAnchor)
            }
        }
    }
}
