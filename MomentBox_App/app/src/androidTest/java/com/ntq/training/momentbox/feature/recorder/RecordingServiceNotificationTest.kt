package com.ntq.training.momentbox.feature.recorder

import android.app.Notification
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingServiceNotificationTest {

    @Test
    fun pausedNotificationHidesRelativeTimestampAndShowsFrozenDuration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val service = RecordingService()
            val attach = ContextWrapper::class.java.getDeclaredMethod(
                "attachBaseContext",
                android.content.Context::class.java
            )
            attach.isAccessible = true
            attach.invoke(service, instrumentation.targetContext)

            val paused = service.notification(
                RecordingSnapshot(
                    session = "s1",
                    phase = RecordPhase.PAUSED,
                    elapsedMs = 65_000L
                )
            )
            assertFalse(
                "Paused notification must hide relative timestamp to avoid shifting/misleading '1m' label",
                paused.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true)
            )
            assertFalse(paused.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false))
            val text = paused.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            assertTrue("Expected frozen duration '01:05' in paused text, got '$text'", text.contains("01:05"))

            val recording = service.notification(
                RecordingSnapshot(
                    session = "s1",
                    phase = RecordPhase.RECORDING,
                    elapsedMs = 12_000L
                )
            )
            assertTrue(recording.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, false))
            assertTrue(recording.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false))
        }
    }
}
