package com.ntq.training.momentbox.feature.compose

import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bumptech.glide.Glide
import com.ntq.training.momentbox.feature.create.SelectedMedia
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeMediaAdapterTest {
    @Test
    fun captionSnapshotsKeepThumbnailsBoundAndMediaChangesRefreshThem() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val adapter = ComposeMediaAdapter(Glide.with(instrumentation.targetContext), {}, {})
            var refreshes = 0
            var batchNotified = false
            fun markChanged() {
                if (!batchNotified) {
                    batchNotified = true
                    refreshes++
                }
            }
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = markChanged()

                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = markChanged()

                override fun onItemRangeChanged(
                    positionStart: Int,
                    itemCount: Int,
                    payload: Any?
                ) = markChanged()

                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = markChanged()

                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = markChanged()

                override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
                    markChanged()
            })
            val first = SelectedMedia("1", "file:///first.jpg", "image/jpeg")
            val second = SelectedMedia("2", "file:///second.jpg", "image/jpeg")
            val draft = ComposeDraft(media = listOf(first, second))
            batchNotified = false
            adapter.submitMedia(draft.media)
            assertEquals(1, refreshes)

            for (caption in listOf("a", "ab", "abc")) {
                batchNotified = false
                adapter.submitMedia(draft.copy(caption = caption).snapshot().media)
            }
            assertEquals(1, refreshes)

            batchNotified = false
            adapter.submitMedia(draft.move(0, 1).media)
            assertEquals(2, refreshes)
            batchNotified = false
            adapter.submitMedia(listOf(second.copy(uri = "file:///edited.jpg"), first))
            assertEquals(3, refreshes)
            batchNotified = false
            adapter.submitMedia(listOf(first))
            assertEquals(4, refreshes)
            assertEquals(2, adapter.itemCount) // One attachment and the add button.
        }
    }
}
