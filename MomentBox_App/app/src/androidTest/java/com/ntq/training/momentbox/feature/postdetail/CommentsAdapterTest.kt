package com.ntq.training.momentbox.feature.postdetail

import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.domain.model.Author
import com.ntq.training.momentbox.domain.model.Comment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommentsAdapterTest {
    @Test
    fun closingCommentsBlocksReplyAndRetryOnExistingRowsAndKeepsReadingAvailable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_MomentBox)
            val parent = FrameLayout(context)
            val rootComment = Comment(
                id = 1, postId = 10, author = Author(2, "author", "Author", null),
                content = "Existing", createdAt = "2026-01-01T00:00:00Z"
            )
            val failedReply = rootComment.copy(id = -1, parentId = 1, content = "Reply", sendFailed = true)
            val failedRoot = rootComment.copy(id = -2, content = "Unsent", sendFailed = true)
            val thread = rootComment.copy(replies = listOf(failedReply), isRepliesExpanded = true)
            val replies = mutableListOf<Long>()
            val retries = mutableListOf<Long>()
            val expanded = mutableListOf<Long>()
            val adapter = CommentsAdapter(
                onLikeClick = {}, onReplyClick = { replies += it.id },
                onExpandRepliesClick = { expanded += it.id }, onRetryClick = { retries += it.id }
            )
            adapter.commentsAllowed = true
            adapter.submitCommentsList(listOf(thread, failedRoot))
            val holders = (0 until adapter.itemCount).map { index ->
                adapter.onCreateViewHolder(parent, adapter.getItemViewType(index)).also {
                    adapter.onBindViewHolder(it, index)
                }
            }
            val root = (holders[0] as CommentsAdapter.RootCommentViewHolder).binding
            val child = (holders[1] as CommentsAdapter.ReplyCommentViewHolder).binding
            val unsent = (holders[2] as CommentsAdapter.RootCommentViewHolder).binding

            adapter.commentsAllowed = false
            // The permission changes before RecyclerView gets its next layout pass.
            root.btnReplyComment.performClick()
            child.tvReplyTime.performClick()
            unsent.tvCommentTime.performClick()
            assertTrue(replies.isEmpty())
            assertTrue(retries.isEmpty())

            holders.forEachIndexed { index, holder -> adapter.onBindViewHolder(holder, index) }
            assertEquals(View.GONE, root.btnReplyComment.visibility)
            assertFalse(root.btnReplyComment.isEnabled)
            for (status in listOf(child.tvReplyTime, unsent.tvCommentTime)) {
                assertFalse(status.isClickable)
                assertFalse(status.isFocusable)
                assertEquals(context.getString(R.string.comment_not_sent), status.text.toString())
            }
            assertEquals("Existing", root.tvCommentContent.text.toString())
            assertEquals("Reply", child.tvReplyContent.text.toString())
            root.layoutExpandReplies.performClick()
            assertEquals(listOf(rootComment.id), expanded)

            adapter.commentsAllowed = true
            holders.forEachIndexed { index, holder -> adapter.onBindViewHolder(holder, index) }
            assertEquals(View.VISIBLE, root.btnReplyComment.visibility)
            assertTrue(root.btnReplyComment.isEnabled)
            root.btnReplyComment.performClick()
            child.tvReplyTime.performClick()
            unsent.tvCommentTime.performClick()
            assertEquals(listOf(rootComment.id), replies)
            assertEquals(listOf(failedReply.id, failedRoot.id), retries)
        }
    }
}
