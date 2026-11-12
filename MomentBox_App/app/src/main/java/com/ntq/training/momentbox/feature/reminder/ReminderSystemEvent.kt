package com.ntq.training.momentbox.feature.reminder

enum class ReminderSystemEvent {
    REBUILD,
    RECONCILE;

    companion object {
        fun fromAction(action: String?): ReminderSystemEvent? = when (action) {
            "BOOT_COMPLETED", "MY_PACKAGE_REPLACED", "TIME_SET", "TIMEZONE_CHANGED" -> REBUILD
            "SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> RECONCILE
            else -> null
        }
    }
}
