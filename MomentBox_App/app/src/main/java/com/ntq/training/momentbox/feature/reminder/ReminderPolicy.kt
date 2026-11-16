package com.ntq.training.momentbox.feature.reminder

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Cấu hình bất biến. Lưu phút trong ngày thay vì UTC để 21:00 vẫn là 21:00 sau đổi múi giờ.
 * Không dùng ngày hiện tại làm default trong constructor: caller phải truyền Clock/Instant rõ ràng. */
data class ReminderPreferences(
    val enabled: Boolean = false,
    val minuteOfDay: Int = 21 * 60,
    val skipDate: LocalDate? = null,
    val revision: Long = 0L
) {
    init {
        require(minuteOfDay in 0 until 24 * 60) { "Reminder minute must be a local minute of day" }
        require(revision >= 0L) { "Reminder revision must not be negative" }
    }
}

data class ReminderDayWindow(val date: LocalDate, val start: Instant, val endExclusive: Instant)

/** Policy thuần Kotlin; không tự đặt AlarmManager hay tạo timer trong Activity.
 * Tách phép tính giúp thử DST/midnight trên JVM mà không chỉnh giờ máy thật của người dùng. */
object ReminderPolicy {
    fun nextDaily(settings: ReminderPreferences, now: Instant, zone: ZoneId): Instant? {
        if (!settings.enabled) return null
        val time = LocalTime.of(settings.minuteOfDay / 60, settings.minuteOfDay % 60)
        var day = now.atZone(zone).toLocalDate()
        fun occurrence() = day.atTime(time).atZone(zone).toInstant()
        // atZone đẩy giờ trong khoảng DST bị mất về phía trước, chọn offset đầu khi giờ bị lặp.
        // Nếu lần đầu đã qua, chuyển ngày: không nhắc hai lần vào cùng ngày khi DST quay ngược.
        if (!occurrence().isAfter(now)) day = day.plusDays(1)
        if (settings.skipDate == day) day = day.plusDays(1)
        return occurrence()
    }

    fun dayWindow(now: Instant, zone: ZoneId): ReminderDayWindow {
        val day = now.atZone(zone).toLocalDate()
        // Khoảng Room [start, end) phải theo hai nửa đêm địa phương, không cộng cố định24h:
        // ngày đổi DST có thể dài23h hoặc25h; endExclusive tránh đếm entry của ngày sau.
        return ReminderDayWindow(
            day,
            day.atStartOfDay(zone).toInstant(),
            day.plusDays(1).atStartOfDay(zone).toInstant()
        )
    }

    fun snooze(now: Instant): Instant = now.plus(Duration.ofMinutes(10))
}
