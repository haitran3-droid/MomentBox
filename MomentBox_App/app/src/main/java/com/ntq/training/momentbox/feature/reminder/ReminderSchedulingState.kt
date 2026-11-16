package com.ntq.training.momentbox.feature.reminder

enum class ReminderTiming { EXACT, INEXACT }
data class ReminderCapabilities(
    val notifications: Boolean,
    val exact: Boolean,
    val fullScreen: Boolean
)

data class ReminderScheduled(val event: ReminderEvent, val timing: ReminderTiming)
enum class ReminderScheduleStatus { CHECKING, OFF, NOTIFICATIONS_BLOCKED, SCHEDULED, ERROR }

data class ReminderSchedulingState(
    val status: ReminderScheduleStatus = ReminderScheduleStatus.CHECKING,
    val settings: ReminderPreferences = ReminderPreferences(),
    val capabilities: ReminderCapabilities? = null,
    val daily: ReminderScheduled? = null,
    val snooze: ReminderScheduled? = null,
    val lateRecovery: Boolean = false,
    val error: String? = null
)

interface ReminderAlarmBackend {
    fun capabilities(): ReminderCapabilities
    fun replace(event: ReminderEvent, preferred: ReminderTiming): ReminderTiming
    fun cancel(kind: ReminderKind)
}