package com.ntq.training.momentbox

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import com.google.gson.Gson
import com.ntq.training.momentbox.data.local.db.JournalAttachmentEntity
import com.ntq.training.momentbox.data.local.db.JournalEntryEntity
import com.ntq.training.momentbox.data.local.prefs.JournalFilterState
import com.ntq.training.momentbox.data.local.prefs.JournalDataStore
import com.ntq.training.momentbox.data.local.prefs.JournalDraft
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.feature.journal.editor.JournalEditorActivity
import com.ntq.training.momentbox.feature.journal.stats.JournalStatsActivity
import java.io.File
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in because the sample journal entries are intentionally retained on the test device. */
@RunWith(AndroidJUnit4::class)
class M3JournalDeviceTest {
    @Test
    fun savingAndClearingExistingEntryDraftPreservesLegacyNewDraft() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val preferences = PreferenceDataStoreFactory.create(scope = scope) { file }
            val legacy = JournalDraft(title = "Unfinished new entry")
            preferences.edit { it[stringPreferencesKey("journal_draft")] = Gson().toJson(legacy) }
            val drafts = JournalDataStore(context, preferences)
            drafts.saveDraft(JournalDraft(entryId = 42, title = "Editing entry 42"))
            assertEquals("Editing entry 42", drafts.getDraft(42)?.title)
            drafts.clearDraft(42)
            assertEquals(legacy, drafts.getDraft(null))
            assertEquals(listOf(legacy), drafts.getAllDrafts().toList())
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun seedAndExerciseJournal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("seedM3") == "true")
        val app = instrumentation.targetContext.applicationContext as MomentBoxApp
        val journal = app.journalRepository
        val firstId = runBlocking {
            if (!app.authRepository.isLoggedIn()) {
                check(app.settingsDataStore.getDataSource() == SettingsDataStore.SOURCE_MB_SERVER)
                app.authRepository.login("minhanh@momentbox.dev", "123456", true).getOrThrow()
            }
            val existing = journal.observeEntries(TAG, JournalFilterState.DEFAULT, limit = SAMPLE_COUNT)
                .first().associateBy { it.entry.title }
            val now = ZonedDateTime.now()
            val ids = (1..SAMPLE_COUNT).map { number ->
                val title = "M3 QA ${number.toString().padStart(2, '0')}"
                existing[title]?.entry?.id ?: run {
                    val timestamp = now.minusDays((number - 1).toLong()).toInstant().toEpochMilli()
                    journal.saveEntry(
                        entry = JournalEntryEntity(
                            title = title,
                            content = "Sample entry for journal search, filters and statistics.",
                            mood = (number - 1) % 5 + 1,
                            createdAt = timestamp,
                            updatedAt = timestamp,
                            isPinned = number == 1 || number == 9,
                            wordCount = 9
                        ),
                        attachments = if (number == 1) sampleAttachments(app) else emptyList(),
                        tags = listOf(TAG, if (number % 2 == 0) "travel" else "daily")
                    ).getOrThrow()
                }
            }
            assertEquals(SAMPLE_COUNT, journal.countEntries(TAG, JournalFilterState.DEFAULT).first())
            assertEquals(20, journal.observeEntries(TAG, JournalFilterState.DEFAULT).first().size)
            val filter = JournalFilterState(selectedMoods = listOf(1), hasPhoto = true, hasAudio = true)
            assertEquals(1, journal.countEntries(TAG, filter).first())
            assertEquals(2, journal.getEntryById(ids.first())?.attachments?.size)
            journal.softDelete(ids.last()).getOrThrow()
            assertEquals(SAMPLE_COUNT - 1, journal.countEntries(TAG, JournalFilterState.DEFAULT).first())
            journal.undoDelete(ids.last()).getOrThrow()
            assertEquals(SAMPLE_COUNT, journal.countEntries(TAG, JournalFilterState.DEFAULT).first())
            ids.first()
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.tabJournal)).perform(click())
            scenario.onActivity {
                assertTrue(it.findViewById<View>(R.id.rvJournalEntries).isShown)
                assertTrue(it.findViewById<View>(R.id.moodCalendarView).isShown)
            }
            onView(withId(R.id.btnFilter)).perform(click())
            onView(withId(R.id.chipMood1)).perform(click())
            onView(withId(R.id.chipMood2)).perform(click())
            onView(withId(R.id.btnResetFilter)).perform(click())
            assertUnchecked(R.id.chipMood1)
            assertUnchecked(R.id.chipMood2)
        }
        ActivityScenario.launch<JournalEditorActivity>(
            Intent(app, JournalEditorActivity::class.java).putExtra(JournalEditorActivity.EXTRA_ENTRY_ID, firstId)
        ).use { scenario ->
            waitForText(scenario, R.id.et_title) { it == "M3 QA 01" }
            scenario.onActivity {
                assertTrue(it.findViewById<View>(R.id.layout_audio_attachment).isShown)
                assertTrue(it.findViewById<View>(R.id.layout_photos_section).isShown)
            }
        }
        ActivityScenario.launch(JournalStatsActivity::class.java).use { scenario ->
            waitForText(scenario, R.id.tvEntriesCount) { (it.toIntOrNull() ?: 0) > 0 }
            scenario.onActivity {
                assertNotNull(it.findViewById<View>(R.id.donutChartView))
                assertNotNull(it.findViewById<View>(R.id.lineChartView))
            }
        }
    }

    private fun assertUnchecked(id: Int) {
        onView(withId(id)).check { view, error ->
            if (error != null) throw error
            assertFalse((view as Chip).isChecked)
        }
    }

    private fun <T : android.app.Activity> waitForText(
        scenario: ActivityScenario<T>,
        id: Int,
        matches: (String) -> Boolean
    ) {
        repeat(100) {
            var value = ""
            scenario.onActivity { value = it.findViewById<TextView>(id).text.toString() }
            if (matches(value)) return
            Thread.sleep(100)
        }
        error("Timed out waiting for view " + id)
    }

    private suspend fun sampleAttachments(app: MomentBoxApp): List<JournalAttachmentEntity> {
        val photo = File.createTempFile("m3-qa-", ".png", app.cacheDir)
        val audio = File.createTempFile("m3-qa-", ".wav", app.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(80, 120, 170))
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            app.assets.open("ambient-rain.wav").use { input ->
                audio.outputStream().use { input.copyTo(it) }
            }
            val journal = app.journalRepository
            val imagePath = journal.stageAttachmentFile(photo.path, isAudio = false).getOrThrow()
            val audioPath = journal.stageAttachmentFile(audio.path, isAudio = true).getOrThrow()
            val metadata = requireNotNull(journal.readAudioMetadata(audioPath))
            return listOf(
                JournalAttachmentEntity(entryId = 0, type = "image", localPath = imagePath),
                JournalAttachmentEntity(
                    entryId = 0,
                    type = "audio",
                    localPath = audioPath,
                    durationMs = metadata.durationMs,
                    waveform = metadata.waveform,
                    sortOrder = 1
                )
            )
        } finally {
            photo.delete()
            audio.delete()
        }
    }

    private companion object {
        const val TAG = "m3-qa"
        const val SAMPLE_COUNT = 24
    }
}
