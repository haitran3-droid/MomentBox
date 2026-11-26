package com.ntq.training.momentbox

import android.content.Intent
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ntq.training.momentbox.feature.journal.editor.JournalEditorActivity
import com.ntq.training.momentbox.feature.reminder.ReminderJournalRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderWriteMoodDeviceTest {

    @Test
    fun reminderRoutePreservesUnselectedAndSelectedMoodRoundTrip() {
        val now = System.currentTimeMillis()
        val emptyIntent = Intent()
        assertNull(ReminderJournalRoute.read(emptyIntent))

        val unselectedIntent =
            ReminderJournalRoute.put(Intent(), ReminderJournalRoute.MOOD_UNSELECTED, now)
        val unselectedCopy = Intent()
        ReminderJournalRoute.copy(unselectedIntent, unselectedCopy)
        val unselectedEntry = ReminderJournalRoute.take(unselectedCopy)
        assertNotNull(unselectedEntry)
        assertEquals(ReminderJournalRoute.MOOD_UNSELECTED, unselectedEntry!!.mood)
        assertNull(ReminderJournalRoute.read(unselectedCopy))

        val selectedIntent = ReminderJournalRoute.put(Intent(), 4, now)
        val selectedEntry = ReminderJournalRoute.take(selectedIntent)
        assertNotNull(selectedEntry)
        assertEquals(4, selectedEntry!!.mood)
    }

    @Test
    fun editorOpenedFromReminderWriteNowHasNoMoodSelectedUntilUserPicksOne() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MomentBoxApp
        val moodCircleIds =
            listOf(
                R.id.circle_mood_1,
                R.id.circle_mood_2,
                R.id.circle_mood_3,
                R.id.circle_mood_4,
                R.id.circle_mood_5,
            )

        val unselectedIntent =
            Intent(app, JournalEditorActivity::class.java).apply {
                putExtra(JournalEditorActivity.EXTRA_ENTRY_ID, TEST_ENTRY_ID)
                putExtra(JournalEditorActivity.EXTRA_MOOD, JournalEditorActivity.MOOD_UNSELECTED)
            }

        try {
            ActivityScenario.launch<JournalEditorActivity>(unselectedIntent).use { scenario ->
                waitUntil(timeoutMs = 5_000L) {
                    var ready = false
                    scenario.onActivity { activity ->
                        ready = activity.findViewById<EditText>(R.id.et_title).isEnabled
                    }
                    ready
                }

                // All 5 moods must start unselected (alpha = 0.75f) and Save stays disabled even with title.
                scenario.onActivity { activity ->
                    moodCircleIds.forEach { id ->
                        assertEquals(0.75f, activity.findViewById<View>(id).alpha, 0.01f)
                    }
                    activity.findViewById<EditText>(R.id.et_title).setText("K18 check")
                }

                var saveEnabledBeforeMood = true
                scenario.onActivity { activity ->
                    saveEnabledBeforeMood = activity.findViewById<View>(R.id.btn_save).isEnabled
                }
                assertFalse("Save must remain disabled until user chooses a mood", saveEnabledBeforeMood)

                // Selecting mood 3 highlights only mood 3 and enables Save.
                onView(withId(R.id.layout_mood_3)).perform(click())
                scenario.onActivity { activity ->
                    assertEquals(1.0f, activity.findViewById<View>(R.id.circle_mood_3).alpha, 0.01f)
                    assertEquals(0.75f, activity.findViewById<View>(R.id.circle_mood_1).alpha, 0.01f)
                    assertTrue(activity.findViewById<View>(R.id.btn_save).isEnabled)
                }
            }

            // Counter-check: opening with mood 4 pre-selects only mood 4.
            val selectedIntent =
                Intent(app, JournalEditorActivity::class.java).apply {
                    putExtra(JournalEditorActivity.EXTRA_ENTRY_ID, TEST_ENTRY_ID)
                    putExtra(JournalEditorActivity.EXTRA_MOOD, 4)
                }
            ActivityScenario.launch<JournalEditorActivity>(selectedIntent).use { scenario ->
                waitUntil(timeoutMs = 5_000L) {
                    var ready = false
                    scenario.onActivity { activity ->
                        ready = activity.findViewById<EditText>(R.id.et_title).isEnabled
                    }
                    ready
                }
                scenario.onActivity { activity ->
                    assertEquals(1.0f, activity.findViewById<View>(R.id.circle_mood_4).alpha, 0.01f)
                    assertEquals(0.75f, activity.findViewById<View>(R.id.circle_mood_1).alpha, 0.01f)
                }
            }
        } finally {
            runBlocking { app.journalRepository.clearDraft(TEST_ENTRY_ID) }
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100L)
        }
        error("Timed out after ${timeoutMs}ms waiting for editor state")
    }

    private companion object {
        const val TEST_ENTRY_ID = 999_999_998L
    }
}
