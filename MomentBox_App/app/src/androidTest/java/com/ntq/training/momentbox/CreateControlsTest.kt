package com.ntq.training.momentbox

import android.content.Intent
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ntq.training.momentbox.feature.recorder.RecorderActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateControlsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun longAlbumLeavesNextButtonFullyVisibleAt320dpWithLargeText() {
        instrumentation.runOnMainSync {
            val target = instrumentation.targetContext
            val largeText = Configuration(target.resources.configuration).apply { fontScale = 1.5f }
            val context = ContextThemeWrapper(target.createConfigurationContext(largeText), R.style.Theme_MomentBox)
            val root = LayoutInflater.from(context).inflate(R.layout.fragment_gallery, null)
            val album = root.findViewById<TextView>(R.id.gallery_album)
            val next = root.findViewById<TextView>(R.id.gallery_next)
            album.text = "MomentBox vacation photos and videos ".repeat(8)
            val width = (320 * context.resources.displayMetrics.density).toInt()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            root.layout(0, 0, width, root.measuredHeight)
            assertTrue(next.width >= next.paint.measureText(next.text.toString()))
            assertTrue(next.left >= 0 && next.right <= width)
            assertEquals(1, album.lineCount)
            assertTrue(album.layout.getEllipsisCount(0) > 0)
        }
    }

    @Test
    fun idleRecorderOffersStartRecording() {
        val intent = Intent(instrumentation.targetContext, RecorderActivity::class.java)
        ActivityScenario.launch<RecorderActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val button = activity.findViewById<View>(R.id.record_button)
                assertEquals(activity.getString(R.string.m4_start_recording), button.contentDescription)
            }
        }
    }
}
