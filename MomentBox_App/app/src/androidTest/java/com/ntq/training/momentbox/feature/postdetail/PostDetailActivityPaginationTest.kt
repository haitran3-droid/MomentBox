package com.ntq.training.momentbox.feature.postdetail

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.data.repository.AuthRepository
import com.ntq.training.momentbox.data.repository.FeedRepository
import com.ntq.training.momentbox.domain.model.Author
import com.ntq.training.momentbox.domain.model.Comment
import com.ntq.training.momentbox.domain.model.FeedItem
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.domain.model.Story
import com.ntq.training.momentbox.domain.model.User
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PostDetailActivityPaginationTest {

    @Test
    fun layoutAPaginatesRootCommentsAfterSortChangeRetriesOnPageTwoAndLoadsRepliesElevenTwelve() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MomentBoxApp
        val prevAuth = app.authRepository
        val prevFeed = app.feedRepository
        val fakeFeed = PaginatedFakeFeed()
        app.authRepository = LoggedInFakeAuth()
        app.feedRepository = fakeFeed

        try {
            val intent = Intent(app, PostDetailActivity::class.java).apply {
                putExtra(PostDetailActivity.EXTRA_POST_ID, fakeFeed.postId)
                putExtra(PostDetailActivity.EXTRA_LAYOUT_VERSION, "A")
            }
            ActivityScenario.launch<PostDetailActivity>(intent).use { scenario ->
                waitUntil(5000) {
                    var ready = false
                    scenario.onActivity { activity ->
                        ready = findTextView(activity, "Parent 10") != null
                    }
                    ready
                }

                // 1. Reply to Parent 10 while server fails (503 before save).
                fakeFeed.failNextSubmit = true
                scenario.onActivity { activity ->
                    val parentView = findTextView(activity, "Parent 10")!!
                    val itemRoot = parentView.parent.parent.parent.parent as View
                    itemRoot.findViewById<View>(R.id.btnReplyComment).performClick()
                    val input = activity.findViewById<EditText>(R.id.etCommentInputA)
                    input.append("950101")
                    activity.findViewById<View>(R.id.btnSendCommentA).performClick()
                }
                waitUntil(5000) {
                    var failedShown = false
                    scenario.onActivity { activity ->
                        failedShown = findTextView(activity, activity.getString(R.string.comment_send_failed)) != null
                    }
                    failedShown
                }

                // 2. Đổi chế độ sắp xếp sang Mới nhất -> Parent 10 chuyển xuống trang 2.
                Espresso.closeSoftKeyboard()
                SystemClock.sleep(250)
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.btnSortComments).performClick()
                }
                SystemClock.sleep(350)
                onView(withText(R.string.sort_newest)).inRoot(isPlatformPopup()).perform(click())
                waitUntil(5000) {
                    fakeFeed.rootRequests.contains(1 to "newest") &&
                        run {
                            var loaded = false
                            scenario.onActivity { activity ->
                                loaded = findTextView(activity, "Root 122") != null &&
                                    findTextView(activity, "Parent 10") == null
                            }
                            loaded
                        }
                }

                // 3. Scroll NestedScrollView to bottom multiple times -> loads page 2 once, no infinite requests.
                repeat(3) {
                    scenario.onActivity { activity ->
                        val scroll = activity.findViewById<NestedScrollView>(R.id.nestedScrollViewA)
                        val bottom = (scroll.getChildAt(0)?.height ?: 0).coerceAtLeast(1)
                        scroll.scrollTo(0, bottom)
                    }
                    SystemClock.sleep(250)
                }

                waitUntil(5000) {
                    var foundParentAndFailed = false
                    scenario.onActivity { activity ->
                        foundParentAndFailed = findTextView(activity, "Parent 10") != null &&
                            findTextView(activity, activity.getString(R.string.comment_send_failed)) != null
                    }
                    foundParentAndFailed
                }

                // Scroll to bottom again after page 2 finished to confirm no page 3 / infinite loop.
                repeat(3) {
                    scenario.onActivity { activity ->
                        val scroll = activity.findViewById<NestedScrollView>(R.id.nestedScrollViewA)
                        scroll.scrollTo(0, scroll.getChildAt(0)?.height ?: 0)
                    }
                    SystemClock.sleep(150)
                }
                assertEquals(
                    listOf(1 to "newest", 2 to "newest"),
                    fakeFeed.rootRequests.filter { it.second == "newest" }
                )

                // 4. Retry failed reply on page 2 -> sends exact parentId and same clientId.
                fakeFeed.failNextSubmit = false
                scenario.onActivity { activity ->
                    val retry = findTextView(activity, activity.getString(R.string.comment_send_failed))
                    assertNotNull(retry)
                    retry!!.performClick()
                }
                waitUntil(5000) { fakeFeed.submissions.size == 2 && fakeFeed.confirmedReplyCount == 13 }
                assertEquals(fakeFeed.parentId, fakeFeed.submissions[0].parentId)
                assertEquals(fakeFeed.parentId, fakeFeed.submissions[1].parentId)
                assertNotNull(fakeFeed.submissions[0].clientId)
                assertEquals(fakeFeed.submissions[0].clientId, fakeFeed.submissions[1].clientId)

                // 5. Load replies page 1 (1..10) and page 2 (11..12) on Layout A after sort refresh.
                fakeFeed.replyPageRequests.clear()
                repeat(2) {
                    waitUntil(5000) {
                        var hasMore = false
                        scenario.onActivity { activity ->
                            val scroll = activity.findViewById<NestedScrollView>(R.id.nestedScrollViewA)
                            scroll.scrollTo(0, scroll.getChildAt(0)?.height ?: 0)
                            hasMore = findTextView(activity, activity.getString(R.string.detail_more_replies)) != null
                        }
                        hasMore
                    }
                    scenario.onActivity { activity ->
                        findTextView(activity, activity.getString(R.string.detail_more_replies))!!.performClick()
                    }
                    SystemClock.sleep(250)
                }
                waitUntil(5000) {
                    var hasElevenTwelve = false
                    scenario.onActivity { activity ->
                        val scroll = activity.findViewById<NestedScrollView>(R.id.nestedScrollViewA)
                        scroll.scrollTo(0, scroll.getChildAt(0)?.height ?: 0)
                        hasElevenTwelve = findTextView(activity, "Child 11") != null &&
                            findTextView(activity, "Child 12") != null
                    }
                    hasElevenTwelve
                }
                assertEquals(listOf(1, 2), fakeFeed.replyPageRequests)
            }
        } finally {
            app.authRepository = prevAuth
            app.feedRepository = prevFeed
        }
    }

    @Test
    fun layoutBPaginatesRootCommentsOnceAndLoadsRepliesElevenTwelve() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MomentBoxApp
        val prevAuth = app.authRepository
        val prevFeed = app.feedRepository
        val fakeFeed = PaginatedFakeFeed()
        app.authRepository = LoggedInFakeAuth()
        app.feedRepository = fakeFeed

        try {
            val intent = Intent(app, PostDetailActivity::class.java).apply {
                putExtra(PostDetailActivity.EXTRA_POST_ID, fakeFeed.postId)
                putExtra(PostDetailActivity.EXTRA_LAYOUT_VERSION, "B")
            }
            ActivityScenario.launch<PostDetailActivity>(intent).use { scenario ->
                waitUntil(5000) {
                    var ready = false
                    scenario.onActivity { activity -> ready = findTextView(activity, "Parent 10") != null }
                    ready
                }

                Espresso.closeSoftKeyboard()
                SystemClock.sleep(250)
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.btnSortComments).performClick()
                }
                SystemClock.sleep(350)
                onView(withText(R.string.sort_newest)).inRoot(isPlatformPopup()).perform(click())
                waitUntil(5000) { fakeFeed.rootRequests.contains(1 to "newest") }

                repeat(3) {
                    scenario.onActivity { activity ->
                        val rv = activity.findViewById<RecyclerView>(R.id.rvPostDetail)
                        val count = rv.adapter?.itemCount ?: 0
                        if (count > 0) rv.scrollToPosition(count - 1)
                    }
                    SystemClock.sleep(250)
                }
                waitUntil(5000) { fakeFeed.rootRequests.contains(2 to "newest") }
                repeat(2) {
                    scenario.onActivity { activity ->
                        val rv = activity.findViewById<RecyclerView>(R.id.rvPostDetail)
                        val count = rv.adapter?.itemCount ?: 0
                        if (count > 0) rv.scrollToPosition(count - 1)
                    }
                    SystemClock.sleep(150)
                }
                assertEquals(
                    listOf(1 to "newest", 2 to "newest"),
                    fakeFeed.rootRequests.filter { it.second == "newest" }
                )

                // Expand Parent 10 on page 2 and load page 2 of its replies (11..12).
                waitUntil(5000) {
                    var visible = false
                    scenario.onActivity { activity ->
                        val rv = activity.findViewById<RecyclerView>(R.id.rvPostDetail)
                        val count = rv.adapter?.itemCount ?: 0
                        if (count > 0) rv.scrollToPosition(count - 1)
                        visible = findTextView(activity, "Parent 10") != null
                    }
                    visible
                }
                scenario.onActivity { activity ->
                    val parentView = findTextView(activity, "Parent 10")!!
                    val itemRoot = parentView.parent.parent.parent.parent as View
                    itemRoot.findViewById<View>(R.id.layoutExpandReplies).performClick()
                }
                waitUntil(5000) { fakeFeed.replyPageRequests.contains(1) }
                waitUntil(5000) {
                    var clicked = false
                    scenario.onActivity { activity ->
                        val rv = activity.findViewById<RecyclerView>(R.id.rvPostDetail)
                        val count = rv.adapter?.itemCount ?: 0
                        if (count > 0) rv.scrollToPosition(count - 1)
                        val more = findTextView(activity, activity.getString(R.string.detail_more_replies))
                        if (more != null) {
                            more.performClick()
                            clicked = true
                        }
                    }
                    clicked
                }
                waitUntil(5000) { fakeFeed.replyPageRequests.contains(2) }
                assertTrue(fakeFeed.replyPageRequests == listOf(1, 2))
            }
        } finally {
            app.authRepository = prevAuth
            app.feedRepository = prevFeed
        }
    }

    private fun findTextView(activity: PostDetailActivity, text: String): TextView? {
        val root = activity.window.decorView
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is TextView && view.visibility == View.VISIBLE && view.text?.toString() == text) {
                return view
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
            }
        }
        return null
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", condition())
    }

    private data class SubmitCall(
        val content: String,
        val parentId: Long?,
        val clientId: String?
    )

    private class LoggedInFakeAuth : AuthRepository {
        private val demoUser = User(1, "demo", "Demo", "demo@momentbox.dev", null)
        override suspend fun login(account: String, secret: String, rememberMe: Boolean) = Result.success(demoUser)
        override suspend fun isLoggedIn() = true
        override suspend fun getSavedUser() = demoUser
        override suspend fun getMe() = Result.success(demoUser)
        override suspend fun logout() = Result.success(Unit)
    }

    private class PaginatedFakeFeed : FeedRepository {
        val postId = 42L
        val parentId = 10L
        private val author = Author(1, "demo", "Demo", null)
        private val initialPost = Post(
            id = postId,
            type = "TEXT",
            author = author,
            caption = "23-root pagination post",
            commentCount = 35,
            allowComments = true,
            createdAt = "2026-11-21T00:00:00Z"
        )

        override val postsMapFlow = MutableStateFlow(mapOf(postId to initialPost))
        val rootRequests: MutableList<Pair<Int, String>> = Collections.synchronizedList(mutableListOf())
        val replyPageRequests: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val submissions: MutableList<SubmitCall> = Collections.synchronizedList(mutableListOf())
        @Volatile var failNextSubmit = false
        @Volatile var confirmedReplyCount = 12

        private val otherRoots = (101L..122L).map { id ->
            Comment(
                id = id,
                postId = postId,
                author = author,
                content = "Root $id",
                likeCount = 0,
                createdAt = "2026-11-21T00:00:00Z"
            )
        }
        private val replies = Collections.synchronizedList(
            (1..12).map { index ->
                Comment(
                    id = 500L + index,
                    postId = postId,
                    parentId = parentId,
                    author = author,
                    content = "Child $index",
                    createdAt = "2026-11-21T00:00:00Z"
                )
            }.toMutableList()
        )

        override fun observePost(postId: Long) = postsMapFlow.map { it[postId] }
        override suspend fun getPostDetail(postId: Long) = Result.success(postsMapFlow.value.getValue(postId))
        override suspend fun loadFollowingState(userId: Long) = Result.success(false)

        override suspend fun getComments(
            postId: Long,
            page: Int,
            pageSize: Int,
            sort: String,
            parentId: Long?
        ): Result<List<Comment>> {
            if (parentId == this.parentId) {
                replyPageRequests += page
                val from = (page - 1) * pageSize
                return Result.success(replies.toList().drop(from).take(pageSize))
            }
            if (parentId != null) return Result.success(emptyList())

            rootRequests += page to sort
            val targetParent = Comment(
                id = this.parentId,
                postId = postId,
                author = author,
                content = "Parent 10",
                likeCount = 99,
                replyCount = confirmedReplyCount,
                createdAt = "2026-11-21T00:00:00Z"
            )
            val ordered = if (sort == "newest") {
                otherRoots.sortedByDescending { it.id } + targetParent
            } else {
                listOf(targetParent) + otherRoots
            }
            val from = (page - 1) * pageSize
            return Result.success(ordered.drop(from).take(pageSize))
        }

        override suspend fun postComment(
            postId: Long,
            content: String,
            parentId: Long?,
            clientId: String?
        ): Result<Comment> {
            submissions += SubmitCall(content, parentId, clientId)
            if (failNextSubmit) return Result.failure(IllegalStateException("503 before save"))

            val nextParentCount = if (parentId == this.parentId) confirmedReplyCount + 1 else null
            val created = Comment(
                id = 900L,
                postId = postId,
                parentId = parentId,
                author = author,
                content = content,
                createdAt = "2026-11-21T00:01:00Z",
                clientId = clientId,
                serverParentReplyCount = nextParentCount
            )
            if (parentId == this.parentId) {
                confirmedReplyCount++
                replies += created
            }

            val updatedPost = postsMapFlow.value.getValue(postId).let {
                it.copy(commentCount = it.commentCount + 1)
            }
            postsMapFlow.value = postsMapFlow.value + (postId to updatedPost)
            return Result.success(created)
        }

        override suspend fun getStories() = Result.success(emptyList<Story>())
        override suspend fun getFeed(page: Int, pageSize: Int) = Result.success(emptyList<FeedItem>())
        override suspend fun toggleLike(postId: Long) = Result.success(postsMapFlow.value.getValue(postId))
        override suspend fun toggleBookmark(postId: Long) = Result.success(postsMapFlow.value.getValue(postId))
        override suspend fun deletePost(postId: Long) = Result.success(Unit)
        override suspend fun toggleFollowUser(userId: Long, currentFollowing: Boolean) = Result.success(false)
        override suspend fun toggleLikeComment(commentId: Long, currentLiked: Boolean) = Result.success(!currentLiked)
        override fun clearCache() = Unit
    }
}
