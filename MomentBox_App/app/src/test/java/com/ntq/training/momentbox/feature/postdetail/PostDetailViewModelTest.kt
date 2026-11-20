package com.ntq.training.momentbox.feature.postdetail

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.ntq.training.momentbox.data.repository.FeedRepository
import com.ntq.training.momentbox.domain.model.Author
import com.ntq.training.momentbox.domain.model.Comment
import com.ntq.training.momentbox.domain.model.Post
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostDetailViewModelTest {
    @Test
    fun retryAfterFirstPageFailureStillRequestsPageOne() = runTest {
        val repo = FakeFeed()
        repo.pages = { _, _ -> Result.failure(IllegalStateException("offline")) }
        withViewModel(repo) { viewModel ->
            runCurrent()
            assertTrue(viewModel.commentLoadFailed.value)
            viewModel.loadComments()
            runCurrent()
            assertEquals(listOf(1), repo.rootPages)

            repo.pages = { _, _ -> Result.success(listOf(comment)) }
            viewModel.loadComments(retry = true)
            runCurrent()
            assertEquals(listOf(1, 1), repo.rootPages)
            assertEquals(listOf(comment), viewModel.comments.value)
            assertFalse(viewModel.commentLoadFailed.value)
        }
    }

    @Test
    fun staleSecondTapDoesNotApplyTheSameOptimisticLikeTwice() = runTest {
        val repo = FakeFeed()
        val server = CompletableDeferred<Result<Boolean>>()
        repo.likeResult = { server.await() }
        withViewModel(repo) { viewModel ->
            runCurrent()
            viewModel.toggleLikeComment(comment)
            viewModel.toggleLikeComment(comment)
            runCurrent()
            assertEquals(listOf(false), repo.likes)
            assertEquals(1, viewModel.comments.value.single().likeCount)

            server.complete(Result.success(true))
            runCurrent()
            assertTrue(viewModel.comments.value.single().isLiked)
            assertEquals(1, viewModel.comments.value.single().likeCount)
        }
    }

    @Test
    fun collapsingRepliesDuringLoadKeepsThemCollapsed() = runTest {
        val repo = FakeFeed()
        val replies = CompletableDeferred<Result<List<Comment>>>()
        repo.pages = { _, parent -> if (parent == null) Result.success(listOf(comment)) else replies.await() }
        withViewModel(repo) { viewModel ->
            runCurrent()
            viewModel.expandReplies(comment)
            runCurrent()
            viewModel.expandReplies(comment)
            replies.complete(Result.success(listOf(comment.copy(id = 2, parentId = 1))))
            runCurrent()
            val row = viewModel.comments.value.single()
            assertFalse(row.isRepliesExpanded)
            assertEquals(listOf(2L), row.replies.map { it.id })
        }
    }

    @Test
    fun closedPostRejectsNewCommentsAndKeepsExistingCommentsReadable() = runTest {
        val repo = FakeFeed(post.copy(allowComments = false))
        withViewModel(repo) { viewModel ->
            runCurrent()
            viewModel.sendComment("Blocked", comment.author)
            assertEquals(listOf(comment), viewModel.comments.value)
            runCurrent()
            assertTrue(repo.submissions.isEmpty())
        }
    }

    @Test
    fun missingPostBlocksWritingUntilAnOpenPostIsAvailable() = runTest {
        val repo = FakeFeed(null)
        withViewModel(repo) { viewModel ->
            runCurrent()
            viewModel.sendComment("Before load", comment.author)
            runCurrent()
            assertTrue(repo.submissions.isEmpty())

            repo.postsMapFlow.value = mapOf(post.id to post)
            runCurrent()
            viewModel.sendComment("After load", comment.author)
            runCurrent()
            assertEquals(listOf("After load" to null), repo.submissions)
        }
    }

    @Test
    fun closingCommentsClearsActiveReplyAndRejectsStaleReplyActions() = runTest {
        val repo = FakeFeed()
        withViewModel(repo) { viewModel ->
            runCurrent()
            viewModel.setReplyingTo(comment)
            assertEquals(comment, viewModel.replyingTo.value)

            repo.postsMapFlow.value = mapOf(post.id to post.copy(allowComments = false))
            // A click from an old row can arrive before collectors render the new permission.
            viewModel.sendComment("Queued reply", comment.author)
            assertEquals(listOf(comment), viewModel.comments.value)
            runCurrent()
            assertNull(viewModel.replyingTo.value)
            viewModel.setReplyingTo(comment)
            assertNull(viewModel.replyingTo.value)
            viewModel.sendComment("Stale reply", comment.author)
            runCurrent()
            assertEquals(listOf(comment), viewModel.comments.value)
            assertTrue(repo.submissions.isEmpty())
        }
    }

    @Test
    fun failedReplyCanOnlyBeRetriedWhileCommentsAreOpen() = runTest {
        val repo = FakeFeed()
        withViewModel(repo) { viewModel ->
            runCurrent()
            repo.failSubmission = true
            viewModel.setReplyingTo(comment)
            viewModel.sendComment("Reply", comment.author)
            runCurrent()
            val failed = viewModel.comments.value.single().replies.single()
            assertTrue(failed.sendFailed)
            val request = "Reply" to comment.id
            assertEquals(listOf(request), repo.submissions)

            repo.postsMapFlow.value = mapOf(post.id to post.copy(allowComments = false))
            runCurrent()
            repo.failSubmission = false
            viewModel.retryComment(failed)
            assertEquals(failed, viewModel.comments.value.single().replies.single())
            runCurrent()
            assertEquals(listOf(request), repo.submissions)

            repo.postsMapFlow.value = mapOf(post.id to post)
            runCurrent()
            viewModel.retryComment(failed)
            runCurrent()
            assertEquals(listOf(request, request), repo.submissions)
            assertFalse(viewModel.comments.value.single().replies.single().sendFailed)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.withViewModel(
        repo: FakeFeed,
        check: (PostDetailViewModel) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val provider = ViewModelProvider.create(store, PostDetailViewModel.Factory(repo, 1))
            check(provider[PostDetailViewModel::class])
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private class FakeFeed(initialPost: Post? = post) : FeedRepository {
        override val postsMapFlow = MutableStateFlow(initialPost?.let { mapOf(it.id to it) } ?: emptyMap())
        val submissions = mutableListOf<Pair<String, Long?>>()
        var failSubmission = false
        val rootPages = mutableListOf<Int>()
        val likes = mutableListOf<Boolean>()
        var pages: suspend (Int, Long?) -> Result<List<Comment>> = { _, _ -> Result.success(listOf(comment)) }
        var likeResult: suspend () -> Result<Boolean> = { Result.success(true) }

        override fun observePost(postId: Long) = postsMapFlow.map { it[postId] }
        override suspend fun getPostDetail(postId: Long) = runCatching { postsMapFlow.value.getValue(postId) }
        override suspend fun loadFollowingState(userId: Long) = Result.success(false)
        override suspend fun getComments(postId: Long, page: Int, pageSize: Int, sort: String, parentId: Long?): Result<List<Comment>> {
            if (parentId == null) rootPages += page
            return pages(page, parentId)
        }

        override suspend fun toggleLikeComment(commentId: Long, currentLiked: Boolean): Result<Boolean> {
            likes += currentLiked
            return likeResult()
        }

        override suspend fun getStories() = error("Unexpected request")
        override suspend fun getFeed(page: Int, pageSize: Int) = error("Unexpected request")
        override suspend fun toggleLike(postId: Long) = error("Unexpected request")
        override suspend fun toggleBookmark(postId: Long) = error("Unexpected request")
        override suspend fun deletePost(postId: Long) = error("Unexpected request")
        override suspend fun toggleFollowUser(userId: Long, currentFlowing: Boolean) = error("Unexpected request")
        override suspend fun postComment(postId: Long, content: String, parentId: Long?): Result<Comment> {
            submissions += content to parentId
            if (failSubmission) return Result.failure(IllegalStateException("offline"))
            return Result.success(comment.copy(id = 2, postId = postId, content = content, parentId = parentId))
        }

        override fun clearCache() = Unit
    }

    private companion object {
        val comment = Comment(
            id = 1,
            postId = 1,
            author = Author(1, "demo", "Demo", null),
            content = "Sample",
            createdAt = "2026-01-01T00:00:00Z"
        )
        val post = Post(
            id = comment.postId,
            type = "TEXT",
            author = comment.author,
            caption = "Post",
            createdAt = comment.createdAt
        )
    }
}
