package com.ntq.training.momentbox.feature.reminder

import android.app.AlarmManager
import android.content.Intent

internal enum class ReminderSystemEvent {
    REBUILD,
    RECONCILE;

    companion object {
        fun fromAction(action: String?): ReminderSystemEvent? = when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> REBUILD

            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> RECONCILE

            else -> null
        }
    }
}
