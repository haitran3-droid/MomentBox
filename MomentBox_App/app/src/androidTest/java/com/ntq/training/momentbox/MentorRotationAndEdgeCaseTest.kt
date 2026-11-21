package com.ntq.training.momentbox

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.text.method.HideReturnsTransformationMethod
import android.util.SparseArray
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.createBitmap
import androidx.core.view.isVisible
import android.app.Activity
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButtonToggleGroup
import com.ntq.training.momentbox.core.media.StaticPcmSound
import com.ntq.training.momentbox.data.local.db.DayMood
import com.ntq.training.momentbox.feature.auth.AuthActivity
import com.ntq.training.momentbox.feature.create.CreateActivity
import com.ntq.training.momentbox.feature.editor.PhotoEditorFragment
import com.ntq.training.momentbox.feature.journal.stats.JournalStatsActivity
import com.ntq.training.momentbox.feature.journal.view.MoodCalendarView
import com.ntq.training.momentbox.feature.videoplayer.VideoPlayerActivity
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MentorRotationAndEdgeCaseTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun authScreenPreservesValidationErrorAndPasswordVisibilityAcrossRecreate() {
        ActivityScenario.launch(AuthActivity::class.java).use { scenario ->
            waitUntilWindowFocused(scenario)
            onView(withId(R.id.btnTogglePassword)).perform(scrollTo(), click())
            waitUntil {
                var visible = false
                scenario.onActivity { activity ->
                    visible = activity.findViewById<EditText>(R.id.etPassword).transformationMethod is
                        HideReturnsTransformationMethod
                }
                visible
            }
            onView(withId(R.id.btnLogin)).perform(scrollTo())
            scenario.onActivity { activity ->
                val loginButton = activity.findViewById<View>(R.id.btnLogin)
                val loc = IntArray(2)
                loginButton.getLocationOnScreen(loc)
                val x = loc[0] + loginButton.width / 2f
                val y = loc[1] + loginButton.height / 2f
                val downTime = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(downTime, downTime + 20, MotionEvent.ACTION_UP, x, y, 0)
                try {
                    activity.dispatchTouchEvent(down)
                    activity.dispatchTouchEvent(up)
                } finally {
                    down.recycle()
                    up.recycle()
                }
            }
            waitUntil {
                var validErrorShown = false
                scenario.onActivity { activity ->
                    validErrorShown = activity.findViewById<View>(R.id.layoutEmailError).isVisible &&
                        activity.findViewById<EditText>(R.id.etPassword).transformationMethod is
                        HideReturnsTransformationMethod
                }
                validErrorShown
            }

            scenario.recreate()
            waitUntilWindowFocused(scenario)

            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.layoutEmailError).isVisible)
                assertTrue(
                    activity.findViewById<EditText>(R.id.etPassword).transformationMethod is
                        HideReturnsTransformationMethod
                )
            }
        }
    }

    @Test
    fun journalStatsPreservesPeriodToggleAcrossRecreate() {
        ActivityScenario.launch(JournalStatsActivity::class.java).use { scenario ->
            waitUntilWindowFocused(scenario)
            onView(withId(R.id.btnPeriodWeek)).perform(click())
            waitUntil {
                var weekChecked = false
                scenario.onActivity { activity ->
                    weekChecked =
                        activity.findViewById<MaterialButtonToggleGroup>(R.id.tgPeriod).checkedButtonId ==
                        R.id.btnPeriodWeek
                }
                weekChecked
            }

            scenario.recreate()
            waitUntilWindowFocused(scenario)

            waitUntil {
                var restored = false
                scenario.onActivity { activity ->
                    val checkedId =
                        activity.findViewById<MaterialButtonToggleGroup>(R.id.tgPeriod).checkedButtonId
                    val rangeText = activity.findViewById<TextView>(R.id.tvPeriodRange).text.toString()
                    restored = checkedId == R.id.btnPeriodWeek && rangeText.isNotBlank()
                }
                restored
            }
        }
    }

    @Test
    fun moodCalendarViewHandlesStateAndLayoutAcrossOrientations() {
        instrumentation.runOnMainSync {
            val target = instrumentation.targetContext
            val targetMonth = YearMonth.of(2026, 10)
            val selectedDay = LocalDate.of(2026, 10, 15)
            val viewId = View.generateViewId()
            val portraitContext = ContextThemeWrapper(target, R.style.Theme_MomentBox)
            val calendar = MoodCalendarView(portraitContext).apply {
                id = viewId
                restoreCalendarState(targetMonth, selectedDay)
                setDayMoods(
                    listOf(
                        DayMood(day = "2026-10-15", mood = 1, createdAt = 1_760_500_000_000L),
                        DayMood(day = "2026-10-16", mood = 2, createdAt = 1_760_586_400_000L)
                    )
                )
            }
            val portraitWidth = (360 * target.resources.displayMetrics.density).toInt()
            calendar.measure(
                View.MeasureSpec.makeMeasureSpec(portraitWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            calendar.layout(0, 0, portraitWidth, calendar.measuredHeight)
            assertTrue(calendar.measuredWidth > 0 && calendar.measuredHeight > 0)

            val savedState = SparseArray<Parcelable>()
            calendar.saveHierarchyState(savedState)
            val landscapeConfig = Configuration(target.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
            val landscapeContext = ContextThemeWrapper(
                target.createConfigurationContext(landscapeConfig),
                R.style.Theme_MomentBox
            )
            val rotated = MoodCalendarView(landscapeContext).apply { id = viewId }
            rotated.restoreHierarchyState(savedState)
            val landscapeWidth = (640 * target.resources.displayMetrics.density).toInt()
            rotated.measure(
                View.MeasureSpec.makeMeasureSpec(landscapeWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            rotated.layout(0, 0, landscapeWidth, rotated.measuredHeight)
            assertTrue(rotated.measuredWidth > 0 && rotated.measuredHeight > 0)
            assertEquals(targetMonth, rotated.getYearMonth())
        }
    }

    @Test
    fun staticPcmSoundLoadsAndPlaysBothSfxAssets() {
        val context = instrumentation.targetContext
        StaticPcmSound(context, "sfx-shutter.wav").use { it.play() }
        StaticPcmSound(context, "sfx-tick.wav").use { it.play() }
    }

    @Test
    fun photoEditorAdjustAndUndoSyncSeekBarsAcrossRecreate() {
        val context = instrumentation.targetContext
        val tempImage = File.createTempFile("mentor_editor_", ".jpg", context.cacheDir)
        createBitmap(64, 64).apply { eraseColor(Color.MAGENTA) }.let { bitmap ->
            try {
                tempImage.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            } finally {
                bitmap.recycle()
            }
        }
        try {
            ActivityScenario.launch(CreateActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.setFragmentResult(
                        "create_cropped",
                        Bundle().apply {
                            putString("uri", Uri.fromFile(tempImage).toString())
                            putString("id", "mentor-editor-item")
                        }
                    )
                    activity.supportFragmentManager.executePendingTransactions()
                }
                waitUntil {
                    var ready = false
                    scenario.onActivity { activity ->
                        val fragment = activity.supportFragmentManager
                            .findFragmentById(R.id.create_host) as? PhotoEditorFragment
                        val saveBtn = fragment?.view?.findViewById<View>(R.id.editor_save)
                        ready = saveBtn?.isEnabled == true
                    }
                    ready
                }

                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.create_host)
                    root.findViewWithTag<View>("adjust").performClick()
                }
                instrumentation.waitForIdleSync()
                waitUntil {
                    var width = 0
                    scenario.onActivity { activity ->
                        width = activity.findViewById<SeekBar>(R.id.editor_brightness).width
                    }
                    width > 0
                }

                scenario.onActivity { activity ->
                    val brightness = activity.findViewById<SeekBar>(R.id.editor_brightness)
                    dragSeekBar(brightness, 0.75f)
                }
                instrumentation.waitForIdleSync()

                scenario.onActivity { activity ->
                    val undo = activity.findViewById<View>(R.id.editor_undo)
                    val brightness = activity.findViewById<SeekBar>(R.id.editor_brightness)
                    assertTrue(undo.isEnabled)
                    assertTrue(brightness.progress > 120)
                    undo.performClick()
                }
                instrumentation.waitForIdleSync()

                scenario.onActivity { activity ->
                    val brightness = activity.findViewById<SeekBar>(R.id.editor_brightness)
                    assertEquals(100, brightness.progress)
                    dragSeekBar(brightness, 0.78f)
                }
                instrumentation.waitForIdleSync()

                scenario.recreate()
                instrumentation.waitForIdleSync()

                waitUntil {
                    var restored = false
                    scenario.onActivity { activity ->
                        val brightness = activity.findViewById<SeekBar?>(R.id.editor_brightness)
                        restored = brightness != null && brightness.progress > 120
                    }
                    restored
                }
            }
        } finally {
            tempImage.delete()
        }
    }

    @Test
    fun videoPlayerSupportsLandscapeLayoutAndRecreateState() {
        instrumentation.runOnMainSync {
            val target = instrumentation.targetContext
            val landscapeConfig = Configuration(target.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
            val landscapeContext = ContextThemeWrapper(
                target.createConfigurationContext(landscapeConfig),
                R.style.Theme_MomentBox_VideoPlayer
            )
            val root = LayoutInflater.from(landscapeContext)
                .inflate(R.layout.activity_video_player, null)
            val widthPx = (960 * target.resources.displayMetrics.density).toInt()
            val heightPx = (480 * target.resources.displayMetrics.density).toInt()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, widthPx, heightPx)
            assertNotNull(root.findViewById<View>(R.id.playerViewFullscreen))
            assertNotNull(root.findViewById<View>(R.id.btnPlayPauseLarge))
            assertNotNull(root.findViewById<View>(R.id.tvCurrentTime))
            assertNotNull(root.findViewById<View>(R.id.tvTotalDuration))
            assertNotNull(root.findViewById<View>(R.id.seekBarVideo))
        }

        val intent = Intent(instrumentation.targetContext, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_VIDEO_URL, "http://127.0.0.1:1/mentor.mp4")
            putExtra(VideoPlayerActivity.EXTRA_POST_TITLE, "Mentor S06 Landscape")
            putExtra(VideoPlayerActivity.EXTRA_START_PLAY_WHEN_READY, false)
        }
        ActivityScenario.launch<VideoPlayerActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(
                    "Mentor S06 Landscape",
                    activity.findViewById<TextView>(R.id.tvVideoTitle).text.toString()
                )
            }

            scenario.recreate()
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                assertEquals(
                    "Mentor S06 Landscape",
                    activity.findViewById<TextView>(R.id.tvVideoTitle).text.toString()
                )
                assertNotNull(activity.findViewById<View>(R.id.btnPlayPauseLarge))
            }
        }
    }

    private fun dragSeekBar(seekBar: SeekBar, fraction: Float) {
        val targetX = (seekBar.width * fraction).coerceIn(1f, (seekBar.width - 1).toFloat())
        val midY = seekBar.height / 2f
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, targetX, midY, 0)
        val move = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_MOVE, targetX, midY, 0)
        val up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, targetX, midY, 0)
        try {
            seekBar.dispatchTouchEvent(down)
            seekBar.dispatchTouchEvent(move)
            seekBar.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            move.recycle()
            up.recycle()
        }
    }

    private fun <T : Activity> waitUntilWindowFocused(scenario: ActivityScenario<T>) {
        waitUntil {
            var focused = false
            scenario.onActivity { activity ->
                focused = activity.hasWindowFocus() && activity.window.decorView.width > 0
            }
            focused
        }
        instrumentation.waitForIdleSync()
    }

    private fun waitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        assertTrue("Condition was not satisfied within ${timeoutMs}ms", condition())
    }
}
