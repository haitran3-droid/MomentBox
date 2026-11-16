package com.ntq.training.momentbox.feature.reminder

import android.app.AlarmManager
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderSystemEventTest {
    @Test
    fun rebuildsScheduleForSystemChanges() {
        listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        ).forEach { action -> assertEquals(ReminderSystemEvent.REBUILD, ReminderSystemEvent.fromAction(action)) }
    }

    @Test
    fun reconcilesWhenExactAlarmPermissionChanges() {
        assertEquals(
            ReminderSystemEvent.RECONCILE,
            ReminderSystemEvent.fromAction(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        )
        assertNull(ReminderSystemEvent.fromAction("BOOT_COMPLETED"))
        assertNull(ReminderSystemEvent.fromAction(null))
    }
}
