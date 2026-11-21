package com.ntq.training.momentbox.feature.journal.stats

import com.ntq.training.momentbox.data.local.db.JournalEntryWithDetails
import com.ntq.training.momentbox.data.repository.JournalStatsSnapshot
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
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
data class JournalInsight(
    val empty: Boolean,
    val frequentDay: Int = 1,
    val count: Int = 0,
    val improvementPct: Int? = null
)

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
     * Tính toán toàn bộ chỉ số cho một kỳ cụ thể ([period]) dựa trên snapshot bài viết hợp lệ.
     */
    fun calculateStats(
        snapshot: JournalStatsSnapshot,
        period: StatsPeriod,
        anchorDate: LocalDate = LocalDate.now(zoneId),
        today: LocalDate = anchorDate
    ): JournalStatsResult {
        val (currentStart, fullEnd) = getPeriodDateRange(period, anchorDate)
        val currentEnd = minOf(fullEnd, today)
        val currentPeriodEntries = snapshot.entries
        val totalEntries = currentPeriodEntries.size
        val deltaEntries = totalEntries - snapshot.previousCount

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

        // 2. Phân bố cảm xúc (1..5)
        val moodCounts = IntArray(6) // index 1..5
        for (item in currentPeriodEntries) {
            val mood = item.entry.mood.coerceIn(1, 5)
            moodCounts[mood]++
        }
        // Largest remainder bảo toàn tổng 100%; round độc lập có thể thành 101% (7/5/3/2/1).
        val percentages = IntArray(6)
        if (totalEntries > 0) {
            for (mood in 1..5) {
                percentages[mood] = (moodCounts[mood] * 100L / totalEntries).toInt()
            }
            val remainderOrder = (1..5).sortedWith(
                compareByDescending<Int> { moodCounts[it] * 100L % totalEntries }.thenBy { it }
            )
            repeat(100 - percentages.sum()) {
                percentages[remainderOrder[it]]++
            }
        }
        val moodDistribution = (1..5).map { mood ->
            MoodDistributionItem(mood, moodCounts[mood], percentages[mood])
        }

        // 3. Xu hướng cảm xúc theo ngày (Trend Points)
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

        // 4. Top 3 nhãn phổ biến nhất
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
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
            )
            .map { TagItem(it.key, it.value) }

        val top3Tags = sortedTags.take(3)
        val remainingTagsCount = (sortedTags.size - 3).coerceAtLeast(0)

        // 5. Tạo câu thông tin thông minh (Smart Insight)
        val insight = generateSmartInsight(currentPeriodEntries)

        // 6. Nhãn khoảng ngày
        val dateRangeText =
            "${currentStart.dayOfMonth}/${currentStart.monthValue} – ${currentEnd.dayOfMonth}/${currentEnd.monthValue}"

        return JournalStatsResult(
            period = period,
            totalEntries = totalEntries,
            deltaEntriesVsPreviousPeriod = deltaEntries,
            currentStreakDays = snapshot.streakDays,
            totalWords = totalWords,
            totalAudioMinutes = totalAudioMinutes,
            moodDistribution = moodDistribution,
            trendPoints = trendPoints,
            topTags = top3Tags,
            remainingTagsCount = remainingTagsCount,
            insight = insight,
            dateRangeText = dateRangeText,
            periodStart = currentStart,
            periodEnd = currentEnd
        )
    }

    /**
     * Tính chuỗi ngày viết liên tục hiện tại từ danh sách bài viết theo [zoneId].
     * Nếu bài viết gần nhất trước [today] hơn 1 ngày (strictly before yesterday), chuỗi hiện tại là 0.
     */
    fun calculateCurrentStreak(
        allEntries: List<JournalEntryWithDetails>,
        today: LocalDate = LocalDate.now(zoneId)
    ): Int {
        val writtenDates = allEntries.map {
            Instant.ofEpochMilli(it.entry.createdAt).atZone(zoneId).toLocalDate()
        }
        return calculateCurrentStreak(writtenDates, today)
    }

    /**
     * Tính chuỗi ngày viết liên tục dài nhất trong lịch sử từ danh sách bài viết theo [zoneId].
     */
    fun calculateLongestStreak(
        allEntries: List<JournalEntryWithDetails>,
        today: LocalDate = LocalDate.now(zoneId)
    ): Int {
        val writtenDates = allEntries.map {
            Instant.ofEpochMilli(it.entry.createdAt).atZone(zoneId).toLocalDate()
        }
        return calculateLongestStreak(writtenDates, today)
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

    internal fun getPeriodDateRange(period: StatsPeriod, anchor: LocalDate): Pair<LocalDate, LocalDate> {
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

    companion object {
        /**
         * Tính chuỗi ngày viết liên tục hiện tại.
         * Nếu ngày có bài viết gần nhất nhỏ hơn `today.minusDays(1)` (hôm qua), chuỗi hiện tại phải về 0.
         */
        fun calculateCurrentStreak(
            writtenDates: Collection<LocalDate>,
            today: LocalDate
        ): Int {
            val validDates = writtenDates.filterNot { it.isAfter(today) }.toSet()
            val latestDate = validDates.maxOrNull() ?: return 0
            if (latestDate.isBefore(today.minusDays(1))) return 0

            var day = latestDate
            var streak = 0
            while (day in validDates) {
                streak++
                day = day.minusDays(1)
            }
            return streak
        }

        /**
         * Tính chuỗi ngày viết liên tục dài nhất trong lịch sử (không bị reset khi đứt chuỗi hiện tại).
         */
        fun calculateLongestStreak(
            writtenDates: Collection<LocalDate>,
            today: LocalDate
        ): Int {
            val sortedDates = writtenDates
                .asSequence()
                .filterNot { it.isAfter(today) }
                .distinct()
                .sorted()
                .toList()
            if (sortedDates.isEmpty()) return 0

            var longest = 1
            var current = 1
            for (i in 1 until sortedDates.size) {
                if (sortedDates[i] == sortedDates[i - 1].plusDays(1)) {
                    current++
                    if (current > longest) {
                        longest = current
                    }
                } else {
                    current = 1
                }
            }
            return longest
        }
    }
}
