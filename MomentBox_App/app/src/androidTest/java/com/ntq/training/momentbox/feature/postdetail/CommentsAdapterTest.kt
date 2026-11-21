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
    fun expandedThreadOffersNextPageOrRetryAndKeepsPermissionToRead() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_MomentBox)
            val parent = FrameLayout(context)
            val thread = Comment(
                id = 17, postId = 1, author = Author(1, "author", "Author", null),
                content = "Parent", createdAt = "", replyCount = 11, isRepliesExpanded = true,
                nextReplyPage = 2
            )
            for ((row, expectedText) in listOf(
                thread to R.string.detail_more_replies,
                thread.copy(replyLoadFailed = true) to R.string.retry
            )) {
                var requested: Long? = null
                val adapter = CommentsAdapter({}, {}, {})
                adapter.onLoadMoreReplies = { requested = it }
                adapter.submitCommentsList(listOf(row))
                val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(1))
                adapter.onBindViewHolder(holder, 1)
                val button = holder.itemView as android.widget.TextView
                assertEquals(context.getString(expectedText), button.text.toString())
                assertTrue(button.performClick())
                assertEquals(row.id, requested)
            }
        }
    }

    @Test
    fun closingCommentsBlocksReplyAndRetryOnExistingRowsAndKeepsReadingAvailable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_MomentBox)
            val parent = FrameLayout(context)
            val rootComment = Comment(
                id = 1, postId = 10, author = Author(2, "author", "Author", null),
                content = "Existing", createdAt = "2026-01-01T00:00:00Z", replyCount = 1
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

    @Test
    fun pendingAndFailedRepliesDoNotInflateDisplayedReplyCount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_MomentBox)
            val parent = FrameLayout(context)
            val author = Author(2, "author", "Author", null)
            val failedReply = Comment(
                id = -1, postId = 10, parentId = 1, author = author,
                content = "Unsent reply", createdAt = "", sendFailed = true
            )

            // Parent with 0 server replies + 1 failed reply must hide expand toggle while still rendering the failed row.
            val zeroThread = Comment(
                id = 1, postId = 10, author = author, content = "Zero parent",
                createdAt = "", replyCount = 0, replies = listOf(failedReply),
                isRepliesExpanded = true, nextReplyPage = null
            )
            val adapter = CommentsAdapter({}, {}, {}, {})
            adapter.commentsAllowed = true
            adapter.submitCommentsList(listOf(zeroThread))
            assertEquals(2, adapter.itemCount)
            val zeroHolder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0)) as CommentsAdapter.RootCommentViewHolder
            adapter.onBindViewHolder(zeroHolder, 0)
            assertEquals(View.GONE, zeroHolder.binding.layoutExpandReplies.visibility)
            val zeroChildHolder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(1)) as CommentsAdapter.ReplyCommentViewHolder
            adapter.onBindViewHolder(zeroChildHolder, 1)
            assertEquals(context.getString(R.string.comment_send_failed), zeroChildHolder.binding.tvReplyTime.text.toString())

            // Parent with 12 confirmed replies + 1 failed reply must display 12 (not 13), then 13 once confirmed.
            val confirmedTwelve = (101L..112L).map {
                Comment(id = it, postId = 10, parentId = 2, author = author, content = "Child $it", createdAt = "")
            }
            val twelveThread = Comment(
                id = 2, postId = 10, author = author, content = "Twelve parent",
                createdAt = "", replyCount = 12, replies = confirmedTwelve + failedReply.copy(parentId = 2),
                isRepliesExpanded = true, nextReplyPage = null
            )
            val twelveAdapter = CommentsAdapter({}, {}, {}, {})
            twelveAdapter.commentsAllowed = true
            twelveAdapter.submitCommentsList(listOf(twelveThread))
            val twelveHolder = twelveAdapter.onCreateViewHolder(parent, twelveAdapter.getItemViewType(0)) as CommentsAdapter.RootCommentViewHolder
            twelveAdapter.onBindViewHolder(twelveHolder, 0)
            assertEquals(View.VISIBLE, twelveHolder.binding.layoutExpandReplies.visibility)
            assertEquals(
                context.resources.getQuantityString(R.plurals.hide_replies, 12, 12),
                twelveHolder.binding.tvExpandRepliesText.text.toString()
            )

            val confirmedThirteen = twelveThread.copy(
                replyCount = 13,
                replies = confirmedTwelve + Comment(
                    id = 113, postId = 10, parentId = 2, author = author,
                    content = "Unsent reply", createdAt = ""
                )
            )
            val thirteenAdapter = CommentsAdapter({}, {}, {}, {})
            thirteenAdapter.commentsAllowed = true
            thirteenAdapter.submitCommentsList(listOf(confirmedThirteen))
            val thirteenHolder = thirteenAdapter.onCreateViewHolder(parent, thirteenAdapter.getItemViewType(0)) as CommentsAdapter.RootCommentViewHolder
            thirteenAdapter.onBindViewHolder(thirteenHolder, 0)
            assertEquals(
                context.resources.getQuantityString(R.plurals.hide_replies, 13, 13),
                thirteenHolder.binding.tvExpandRepliesText.text.toString()
            )
        }
    }
}
