package com.ntq.training.momentbox

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ntq.training.momentbox.core.media.VoiceAttachmentProgress
import com.ntq.training.momentbox.feature.journal.editor.JournalEditorActivity
import com.ntq.training.momentbox.feature.journal.view.AudioWaveformView
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalVoicePlaybackDeviceTest {

    @Test
    fun playingAudioAttachmentAdvancesWaveformAndElapsedTimeAndSurvivesUiStateUpdates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MomentBoxApp
        val tempAudio = File.createTempFile("voice-playback-test-", ".wav", app.cacheDir)
        try {
            app.assets.open("ambient-rain.wav").use { input ->
                tempAudio.outputStream().use { input.copyTo(it) }
            }
            val retriever = MediaMetadataRetriever()
            val durationMs =
                try {
                    retriever.setDataSource(tempAudio.absolutePath)
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                } finally {
                    retriever.release()
                }
            assertTrue("Expected non-empty test wav duration", durationMs >= 5_000L)
            val totalDurationText =
                VoiceAttachmentProgress.formatDuration(durationMs, Locale.getDefault())
            val waveform = ArrayList((1..40).map { 20 + (it * 13) % 75 })

            val intent =
                Intent(app, JournalEditorActivity::class.java).apply {
                    putExtra(JournalEditorActivity.EXTRA_ENTRY_ID, TEST_ENTRY_ID)
                    putExtra(JournalEditorActivity.EXTRA_AUDIO_URI, tempAudio.absolutePath)
                    putExtra(JournalEditorActivity.EXTRA_AUDIO_DURATION_MS, durationMs)
                    putIntegerArrayListExtra(JournalEditorActivity.EXTRA_AUDIO_WAVEFORM, waveform)
                }

            ActivityScenario.launch<JournalEditorActivity>(intent).use { scenario ->
                waitUntil(timeoutMs = 10_000L) {
                    var ready = false
                    scenario.onActivity { activity ->
                        val bar = activity.findViewById<View>(R.id.layout_audio_attachment)
                        val duration = activity.findViewById<TextView>(R.id.tv_audio_duration)
                        ready = bar.isShown && duration.text.toString() == totalDurationText
                    }
                    ready
                }

                var initialProgress = -1f
                scenario.onActivity { activity ->
                    initialProgress =
                        activity
                            .findViewById<AudioWaveformView>(R.id.view_audio_waveform)
                            .getProgress()
                }
                assertEquals(0f, initialProgress, 0.0001f)

                // Start playback
                onView(withId(R.id.btn_audio_play_pause)).perform(click())

                // Wait until waveform progress and elapsed timer both visibly advance past 1s.
                var playingProgress = 0f
                var playingTime = ""
                waitUntil(timeoutMs = 6_000L) {
                    scenario.onActivity { activity ->
                        playingProgress =
                            activity
                                .findViewById<AudioWaveformView>(R.id.view_audio_waveform)
                                .getProgress()
                        playingTime =
                            activity
                                .findViewById<TextView>(R.id.tv_audio_duration)
                                .text
                                .toString()
                    }
                    playingProgress > 0.05f &&
                        playingTime != totalDurationText &&
                        playingTime != "0:00"
                }

                // Trigger renderUiState via title edit while playing: progress/time must not reset.
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.et_title).setText("Playback check")
                }
                Thread.sleep(600L)

                var progressAfterEdit = 0f
                var timeAfterEdit = ""
                scenario.onActivity { activity ->
                    progressAfterEdit =
                        activity
                            .findViewById<AudioWaveformView>(R.id.view_audio_waveform)
                            .getProgress()
                    timeAfterEdit =
                        activity.findViewById<TextView>(R.id.tv_audio_duration).text.toString()
                }
                assertTrue(
                    "Expected progress to continue advancing after UI update ($progressAfterEdit >= $playingProgress)",
                    progressAfterEdit >= playingProgress,
                )
                assertNotEquals(
                    "Timer must not snap back to total duration while playing",
                    totalDurationText,
                    timeAfterEdit,
                )

                // Pause playback: waveform progress and elapsed time must remain frozen at paused point.
                onView(withId(R.id.btn_audio_play_pause)).perform(click())
                var pausedProgress = 0f
                var pausedTime = ""
                scenario.onActivity { activity ->
                    pausedProgress =
                        activity
                            .findViewById<AudioWaveformView>(R.id.view_audio_waveform)
                            .getProgress()
                    pausedTime =
                        activity.findViewById<TextView>(R.id.tv_audio_duration).text.toString()
                }
                assertTrue("Paused progress should remain > 0", pausedProgress > 0.05f)
                assertNotEquals(totalDurationText, pausedTime)

                Thread.sleep(500L)
                var progressLaterWhilePaused = 0f
                scenario.onActivity { activity ->
                    progressLaterWhilePaused =
                        activity
                            .findViewById<AudioWaveformView>(R.id.view_audio_waveform)
                            .getProgress()
                }
                assertEquals(pausedProgress, progressLaterWhilePaused, 0.0001f)
            }
        } finally {
            runBlocking {
                val draft = app.journalRepository.getDraft(TEST_ENTRY_ID)
                val paths =
                    draft?.attachments.orEmpty().map { it.localPath } +
                        draft?.cleanupPaths.orEmpty()
                app.journalRepository.clearDraft(TEST_ENTRY_ID)
                if (paths.isNotEmpty()) {
                    app.journalRepository.deleteUnreferencedFiles(paths)
                }
            }
            tempAudio.delete()
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100L)
        }
        error("Timed out after ${timeoutMs}ms waiting for playback state condition")
    }

    private companion object {
        const val TEST_ENTRY_ID = 999_999_999L
    }
}
