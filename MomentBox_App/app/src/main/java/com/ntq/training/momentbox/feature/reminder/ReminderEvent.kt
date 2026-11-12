package com.ntq.training.momentbox.feature.reminder

import java.time.LocalDate

enum class ReminderKind{DAILY, SNOOZE}
data class ReminderEvent(
    val kind: ReminderKind,
    val revision: Long,
    val dueEpochMillis: Long
){
    init {
        require(revision >= 0L)
        require(dueEpochMillis > 0L)
    }
}

data class ReminderSnapshot(
    val settings: ReminderPreferences = ReminderPreferences(),
    val dailyDue: Long? = null,
    val snoozeDue: Long? = null,
    val lastDailyDate: LocalDate? = null,
    val active: ReminderEvent? = null
)