package com.ntq.training.momentbox

import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ntq.training.momentbox.feature.reminder.AndroidReminderAlarms
import com.ntq.training.momentbox.feature.reminder.ReminderActivity
import com.ntq.training.momentbox.feature.reminder.ReminderEvent
import com.ntq.training.momentbox.feature.reminder.ReminderKind
import com.ntq.training.momentbox.feature.reminder.ReminderTiming
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderNotificationDeviceTest {
    @Test
    fun snoozingRemovesTheRingingNotificationAndKeepsTheNextAlarm() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MomentBoxApp
        val store = app.reminderStore
        val previous = store.snapshot()
        val caps = AndroidReminderAlarms(app).capabilities()
        assumeTrue("Requires an unused reminder with exact alarms and notifications enabled",
            !previous.settings.enabled && previous.active == null && caps.notifications && caps.exact)
        val manager = app.getSystemService(NotificationManager::class.java)
        fun notices() = manager.activeNotifications.filter { it.id == ReminderActivity.NOTIFICATION_ID }
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                val settings = store.configure(true, previous.settings.minuteOfDay)
                val now = Instant.now()
                val event = ReminderEvent(ReminderKind.SNOOZE, settings.settings.revision, now.toEpochMilli())
                assertTrue(store.schedule(event))
                assertTrue(store.claim(event, now, ZoneId.systemDefault()))
                assertTrue(app.reminderNotifications.show(event, ReminderTiming.EXACT))
                assertTrue(notices().any { it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 })
                ActivityScenario.launch<ReminderActivity>(ReminderActivity.intent(app, event)).use { screen ->
                    screen.onActivity { it.findViewById<android.view.View>(R.id.reminderSnooze).performClick() }
                    withTimeout(5000) {
                        while (store.snapshot().snoozeDue == null || notices().isNotEmpty()) delay(80)
                    }
                    // A detached foreground notification can reappear after the service stops.
                    delay(600)
                    assertTrue(notices().isEmpty())
                    assertTrue(requireNotNull(store.snapshot().snoozeDue) > now.toEpochMilli())
                }
            } finally {
                app.reminderScheduler.configure(false, previous.settings.minuteOfDay)
            }
        }
    }
}
