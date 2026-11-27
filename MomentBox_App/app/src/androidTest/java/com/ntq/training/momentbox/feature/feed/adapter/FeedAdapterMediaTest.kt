package com.ntq.training.momentbox.feature.feed.adapter

import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.domain.model.Author
import com.ntq.training.momentbox.domain.model.FeedItem
import com.ntq.training.momentbox.domain.model.MediaItem
import com.ntq.training.momentbox.domain.model.Post
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedAdapterMediaTest {

    @Test
    fun mixedPhotoAndVoicePostDisplaysAudioDurationFromAudioMediaItem() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_MomentBox)
            val parent = FrameLayout(context)
            val mixedVoicePost = Post(
                id = 501,
                type = "VOICE",
                author = Author(1, "minhanh", "Nguyễn Minh Anh", null),
                caption = "Buổi chiều làm việc cuối tuần",
                media = listOf(
                    MediaItem(type = "image", url = "http://127.0.0.1:3000/uploads/photo.jpg"),
                    MediaItem(type = "audio", url = "http://127.0.0.1:3000/uploads/voice.m4a", durationMs = 5689L)
                ),
                createdAt = "2026-11-27T07:26:18.120Z"
            )
            val adapter = createAdapter()
            adapter.submitList(listOf(FeedItem.PostItem(mixedVoicePost)))

            val holder = adapter.onCreateViewHolder(parent, FeedAdapter.VIEW_TYPE_POST_VOICE)
                as FeedAdapter.VoicePostViewHolder
            holder.bind(mixedVoicePost, emptyList())

            assertEquals("00:00 / 00:05", holder.binding.tvVoiceTime.text.toString())
        }
    }

    @Test
    fun mixedPhotoAndVideoPostDisplaysVideoDurationFromVideoMediaItem() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_MomentBox)
            val parent = FrameLayout(context)
            val mixedVideoPost = Post(
                id = 502,
                type = "VIDEO",
                author = Author(1, "minhanh", "Nguyễn Minh Anh", null),
                caption = "Mixed video post",
                media = listOf(
                    MediaItem(type = "image", url = "http://127.0.0.1:3000/uploads/photo.jpg"),
                    MediaItem(type = "video", url = "http://127.0.0.1:3000/uploads/clip.mp4", durationMs = 15000L)
                ),
                createdAt = "2026-11-27T07:26:18.120Z"
            )
            val adapter = createAdapter()
            adapter.submitList(listOf(FeedItem.PostItem(mixedVideoPost)))

            val holder = adapter.onCreateViewHolder(parent, FeedAdapter.VIEW_TYPE_POST_VIDEO)
                as FeedAdapter.VideoPostViewHolder
            holder.bind(mixedVideoPost, emptyList())

            assertEquals(View.VISIBLE, holder.binding.tvVideoDuration.visibility)
            assertEquals("0:15", holder.binding.tvVideoDuration.text.toString())
        }
    }

    private fun createAdapter(): FeedAdapter = FeedAdapter(
        recycledViewPool = RecyclerView.RecycledViewPool(),
        onLikeClick = {},
        onBookmarkClick = {},
        onCommentClick = {},
        onPostClick = {},
        onShareClick = {},
        onMoreClick = {},
        onStoryClick = {},
        onAddStoryClick = {},
        onSuggestionFollowClick = {},
        onSuggestionDismissClick = {},
        onVoicePlayClick = {},
        onVoiceSeek = { _, _ -> },
        onVoiceSpeedClick = {},
        onVideoMuteToggle = { _, _ -> },
        onVideoFullscreenClick = {},
        onFooterRetryClick = {}
    )
}
