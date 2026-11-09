package com.ntq.training.momentbox.feature.postdetail

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.domain.model.Author
import com.ntq.training.momentbox.domain.model.Post
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PostHeaderAdapterTest {
    @Test
    fun likingPostRendersRedHeartOnTheSameHeader() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var renderedColor = Color.TRANSPARENT
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_MomentBox)
            val post = Post(
                id = 1,
                type = "TEXT",
                author = Author(2, "author", "Author", null),
                caption = "Post",
                createdAt = "2026-11-09T00:00:00Z"
            )
            val adapter = PostHeaderAdapter(
                onLikeClick = {},
                onBookmarkClick = {},
                onShareClick = {},
                onFollowClick = {},
                onSortClick = {}
            )
            try {
                val holder = adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(0))
                adapter.setPost(post)
                adapter.onBindViewHolder(holder, 0)
                adapter.setPost(post.copy(isLiked = true, likeCount = 1))
                adapter.onBindViewHolder(holder, 0)

                val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
                val heart = holder.binding.ivDetailLike.drawable
                heart.setBounds(0, 0, bitmap.width, bitmap.height)
                heart.draw(Canvas(bitmap))
                renderedColor = bitmap.getPixel(12, 12)
            } finally {
                adapter.release()
            }
        }
        assertEquals(Color.rgb(244, 63, 94), renderedColor)
    }
}
