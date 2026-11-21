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
    fun localReplyKeepsOlderPagesReachableAndRetryResumesAtFailedPage() = runTest {
        val repo = FakeFeed()
        val root = comment.copy(replyCount = 11)
        val requests = mutableListOf<Int>()
        var offline = true
        repo.pages = { page, parent ->
            if (parent == null) Result.success(listOf(root))
            else {
                requests += page
                when {
                    page == 1 -> Result.success((2L..11L).map { comment.copy(id = it, parentId = parent) })
                    offline -> Result.failure(IllegalStateException("offline"))
                    else -> Result.success(listOf(comment.copy(id = 12, parentId = parent)))
                }
            }
        }
        withViewModel(repo) { vm ->
            runCurrent()
            vm.setReplyingTo(root)
            vm.sendComment("Local", comment.author) // The server assigns ID 2, also present in page one.
            runCurrent()
            assertEquals((2L..11L).toList(), vm.comments.value.single().replies.map { it.id }.sorted())
            vm.loadMoreReplies(root.id)
            vm.loadMoreReplies(root.id)
            runCurrent()
            assertTrue(vm.comments.value.single().replyLoadFailed)
            assertEquals(listOf(1, 2), requests)
            offline = false
            vm.loadMoreReplies(root.id)
            runCurrent()
            vm.expandReplies(root)
            vm.expandReplies(root)
            vm.loadMoreReplies(root.id)
            runCurrent()
            assertEquals((2L..12L).toList(), vm.comments.value.single().replies.map { it.id }.sorted())
            assertEquals(listOf(1, 2, 2), requests)
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

    @Test
    fun failedRootCommentSurvivesSortChangeAndRetriesOnce() = runTest {
        val first = comment.copy(id = 10, content = "Top comment", likeCount = 5)
        val second = comment.copy(id = 11, content = "Newest comment", likeCount = 1)
        val serverRoots = mutableListOf(first, second)
        val repo = FakeFeed(post.copy(commentCount = 2))
        repo.sortedPages = { _, sort, parent ->
            if (parent != null) Result.success(emptyList())
            else Result.success(if (sort == "newest") serverRoots.sortedByDescending { it.id } else serverRoots.sortedByDescending { it.likeCount })
        }
        repo.submitResult = { content, parentId ->
            if (repo.failSubmission) Result.failure(IllegalStateException("offline"))
            else {
                val created = comment.copy(id = 50, postId = post.id, content = content, parentId = parentId)
                serverRoots += created
                Result.success(created)
            }
        }
        withViewModel(repo) { vm ->
            runCurrent()
            repo.failSubmission = true
            vm.sendComment("Failed root", comment.author)
            runCurrent()
            assertEquals(2, repo.postsMapFlow.value.getValue(post.id).commentCount)

            vm.setSortMode("newest")
            runCurrent()
            val failed = vm.comments.value.firstOrNull { it.content == "Failed root" }
            assertTrue("Expected failed root comment to survive sort change", failed != null)
            assertTrue(failed!!.sendFailed)
            assertFalse(failed.isSending)

            repo.failSubmission = false
            vm.retryComment(failed)
            vm.retryComment(failed) // Fast double tap must not duplicate submission.
            runCurrent()

            assertEquals(listOf("Failed root" to null, "Failed root" to null), repo.submissions)
            val confirmed = vm.comments.value.filter { it.content == "Failed root" }
            assertEquals(1, confirmed.size)
            assertEquals(50L, confirmed.single().id)
            assertFalse(confirmed.single().sendFailed)
            assertFalse(confirmed.single().isSending)
            assertEquals(3, repo.postsMapFlow.value.getValue(post.id).commentCount)

            vm.setSortMode("top")
            runCurrent()
            assertEquals(1, vm.comments.value.count { it.id == 50L })
        }
    }

    @Test
    fun failedReplySurvivesSortChangeAcrossPagesAndRetriesWithParentId() = runTest {
        val targetParent = comment.copy(id = 10, content = "Target parent", replyCount = 12, likeCount = 99)
        val otherRoots = (101L..120L).map { comment.copy(id = it, content = "Root $it") }
        val serverReplies = (201L..212L).map { comment.copy(id = it, parentId = targetParent.id, content = "Child $it") }.toMutableList()
        var parentReplyCount = 12
        val repo = FakeFeed(post.copy(commentCount = 33))
        repo.sortedPages = { page, sort, parent ->
            if (parent == targetParent.id) {
                val from = (page - 1) * 10
                Result.success(serverReplies.drop(from).take(10))
            } else if (parent != null) {
                Result.success(emptyList())
            } else if (sort == "top") {
                Result.success(if (page == 1) listOf(targetParent.copy(replyCount = parentReplyCount)) + otherRoots.take(19) else listOf(otherRoots.last()))
            } else {
                // In "newest" sort, targetParent moves to page 2.
                Result.success(if (page == 1) otherRoots else listOf(targetParent.copy(replyCount = parentReplyCount)))
            }
        }
        repo.submitResult = { content, parentId ->
            if (repo.failSubmission) Result.failure(IllegalStateException("offline"))
            else {
                val created = comment.copy(id = 300, postId = post.id, content = content, parentId = parentId)
                if (parentId == targetParent.id) {
                    serverReplies += created
                    parentReplyCount++
                }
                Result.success(created)
            }
        }
        withViewModel(repo) { vm ->
            runCurrent()
            repo.failSubmission = true
            vm.setReplyingTo(vm.comments.value.first { it.id == targetParent.id })
            vm.sendComment("Failed child", comment.author)
            runCurrent()

            vm.setSortMode("newest")
            runCurrent()
            assertTrue(vm.comments.value.none { it.id == targetParent.id })

            // Scroll to page 2 where targetParent lives under "newest".
            vm.loadComments(refresh = false)
            runCurrent()
            val reloadedParent = vm.comments.value.first { it.id == targetParent.id }
            assertEquals(12, reloadedParent.replyCount)
            assertEquals(12, CommentsAdapter.displayedReplyCount(reloadedParent))
            val failedReply = reloadedParent.replies.firstOrNull { it.content == "Failed child" }
            assertTrue("Expected failed reply to stay attached to parent on page 2", failedReply != null)
            assertEquals(targetParent.id, failedReply!!.parentId)
            assertTrue(failedReply.sendFailed)

            // Opening/loading older server replies keeps the failed reply and preserves read access on lock.
            if (!reloadedParent.isRepliesExpanded) vm.expandReplies(reloadedParent) else vm.loadMoreReplies(targetParent.id)
            runCurrent()
            val withOlderReplies = vm.comments.value.first { it.id == targetParent.id }
            assertTrue(withOlderReplies.replies.any { it.id == 201L })
            assertTrue(withOlderReplies.replies.any { it.id == failedReply.id && it.sendFailed })
            assertEquals(12, CommentsAdapter.displayedReplyCount(withOlderReplies))

            // Respects current comment permission on retry while keeping old replies readable.
            repo.postsMapFlow.value = mapOf(post.id to repo.postsMapFlow.value.getValue(post.id).copy(allowComments = false))
            runCurrent()
            repo.failSubmission = false
            vm.retryComment(failedReply)
            runCurrent()
            assertEquals(1, repo.submissions.size)
            assertTrue(vm.comments.value.first { it.id == targetParent.id }.replies.any { it.id == 201L })

            repo.postsMapFlow.value = mapOf(post.id to repo.postsMapFlow.value.getValue(post.id).copy(allowComments = true))
            runCurrent()
            vm.retryComment(failedReply)
            runCurrent()

            assertEquals(listOf("Failed child" to targetParent.id, "Failed child" to targetParent.id), repo.submissions)
            val confirmedParent = vm.comments.value.first { it.id == targetParent.id }
            assertEquals(13, confirmedParent.replyCount)
            assertEquals(13, CommentsAdapter.displayedReplyCount(confirmedParent))
            assertEquals(1, confirmedParent.replies.count { it.id == 300L && !it.sendFailed })
            assertEquals(34, repo.postsMapFlow.value.getValue(post.id).commentCount)
        }
    }

    @Test
    fun failedReplyDoesNotInflateZeroOrPartialParentReplyCount() = runTest {
        // Part A: Parent starts with 0 replies (post total = 1).
        val zeroParent = comment.copy(id = 1, replyCount = 0)
        val zeroRepo = FakeFeed(post.copy(commentCount = 1))
        var zeroServerCount = 0
        var zeroDeferred = CompletableDeferred<Result<Comment>>()
        zeroRepo.sortedPages = { _, _, parent ->
            if (parent == null) Result.success(listOf(zeroParent.copy(replyCount = zeroServerCount)))
            else Result.success(emptyList())
        }
        zeroRepo.submitResult = { _, _ -> zeroDeferred.await() }
        withViewModel(zeroRepo) { vm ->
            runCurrent()
            vm.setReplyingTo(zeroParent)
            vm.sendComment("First reply", comment.author)
            // While sending: count stays 0.
            var parent = vm.comments.value.single()
            assertEquals(0, parent.replyCount)
            assertEquals(0, CommentsAdapter.displayedReplyCount(parent))
            assertEquals(1, zeroRepo.postsMapFlow.value.getValue(post.id).commentCount)

            // First failure: count stays 0, failed row remains retryable.
            zeroDeferred.complete(Result.failure(IllegalStateException("503")))
            runCurrent()
            parent = vm.comments.value.single()
            val failed = parent.replies.single()
            assertTrue(failed.sendFailed)
            assertEquals(0, parent.replyCount)
            assertEquals(0, CommentsAdapter.displayedReplyCount(parent))
            assertEquals(1, zeroRepo.postsMapFlow.value.getValue(post.id).commentCount)

            // Retry fails again: count still 0.
            zeroDeferred = CompletableDeferred()
            vm.retryComment(failed)
            zeroDeferred.complete(Result.failure(IllegalStateException("503 again")))
            runCurrent()
            parent = vm.comments.value.single()
            assertTrue(parent.replies.single().sendFailed)
            assertEquals(0, parent.replyCount)
            assertEquals(0, CommentsAdapter.displayedReplyCount(parent))
            assertEquals(1, zeroRepo.postsMapFlow.value.getValue(post.id).commentCount)

            // Retry succeeds (with fast double tap): parent becomes 1, post total becomes 2.
            zeroDeferred = CompletableDeferred()
            vm.retryComment(parent.replies.single())
            vm.retryComment(parent.replies.single())
            zeroServerCount = 1
            zeroDeferred.complete(Result.success(comment.copy(id = 90, parentId = zeroParent.id, content = "First reply")))
            runCurrent()
            parent = vm.comments.value.single()
            assertEquals(1, parent.replyCount)
            assertEquals(1, CommentsAdapter.displayedReplyCount(parent))
            assertEquals(2, zeroRepo.postsMapFlow.value.getValue(post.id).commentCount)
            assertEquals(listOf(90L), parent.replies.map { it.id })
        }

        // Part B: Parent starts with 12 replies, 10 loaded (post total = 15).
        val busyParent = comment.copy(id = 2, replyCount = 12)
        val serverReplies = (101L..112L).map { comment.copy(id = it, parentId = busyParent.id, content = "Reply $it") }.toMutableList()
        var busyServerCount = 12
        val busyRepo = FakeFeed(post.copy(commentCount = 15))
        busyRepo.sortedPages = { page, _, parent ->
            if (parent == null) Result.success(listOf(busyParent.copy(replyCount = busyServerCount)))
            else Result.success(serverReplies.drop((page - 1) * 10).take(10))
        }
        busyRepo.submitResult = { content, parentId ->
            if (busyRepo.failSubmission) Result.failure(IllegalStateException("503"))
            else {
                val created = comment.copy(id = 113, postId = post.id, content = content, parentId = parentId)
                serverReplies += created
                busyServerCount++
                Result.success(created)
            }
        }
        withViewModel(busyRepo) { vm ->
            runCurrent()
            vm.expandReplies(busyParent)
            runCurrent()
            var parent = vm.comments.value.single()
            assertEquals(10, parent.replies.size)
            assertEquals(12, parent.replyCount)
            assertEquals(12, CommentsAdapter.displayedReplyCount(parent))

            busyRepo.failSubmission = true
            vm.setReplyingTo(parent)
            vm.sendComment("Reply 13", comment.author)
            runCurrent()
            parent = vm.comments.value.single()
            assertEquals(12, parent.replyCount)
            assertEquals(12, CommentsAdapter.displayedReplyCount(parent))
            assertEquals(15, busyRepo.postsMapFlow.value.getValue(post.id).commentCount)

            // Retry fails again -> still 12, row preserved.
            val failed = parent.replies.first { it.sendFailed }
            vm.retryComment(failed)
            runCurrent()
            parent = vm.comments.value.single()
            assertEquals(12, parent.replyCount)
            assertEquals(12, CommentsAdapter.displayedReplyCount(parent))

            // Loading replies 11..12 while failed reply is still present must still display 12 (not 13).
            vm.loadMoreReplies(busyParent.id)
            runCurrent()
            parent = vm.comments.value.single()
            assertTrue(parent.replies.any { it.id == 111L })
            assertTrue(parent.replies.any { it.id == 112L })
            assertEquals(13, parent.replies.size) // 12 confirmed + 1 failed
            assertEquals(12, parent.replyCount)
            assertEquals(12, CommentsAdapter.displayedReplyCount(parent))

            // Retry succeeds (fast double tap) -> 13 once; post total becomes 16; sort/reload keeps 13 (not 14).
            busyRepo.failSubmission = false
            val failedAfterPage2 = parent.replies.first { it.sendFailed }
            vm.retryComment(failedAfterPage2)
            vm.retryComment(failedAfterPage2)
            runCurrent()
            parent = vm.comments.value.single()
            assertEquals(13, parent.replyCount)
            assertEquals(13, CommentsAdapter.displayedReplyCount(parent))
            assertEquals(16, busyRepo.postsMapFlow.value.getValue(post.id).commentCount)
            assertEquals(1, parent.replies.count { it.id == 113L })

            vm.setSortMode("newest")
            runCurrent()
            parent = vm.comments.value.single()
            assertEquals(13, parent.replyCount)
            assertEquals(13, CommentsAdapter.displayedReplyCount(parent))
        }
    }

    @Test
    fun partialRepliesRemainReachableAfterRetrySucceeds() = runTest {
        val parent = comment.copy(id = 2, replyCount = 12)
        val serverReplies = (101L..112L).map { comment.copy(id = it, parentId = parent.id, content = "Reply $it") }.toMutableList()
        val repo = FakeFeed(post.copy(commentCount = 13))
        repo.sortedPages = { page, _, p ->
            if (p == null) Result.success(listOf(parent))
            else Result.success(serverReplies.drop((page - 1) * 10).take(10))
        }
        repo.submitResult = { content, parentId ->
            if (repo.failSubmission) Result.failure(IllegalStateException("503"))
            else {
                val created = comment.copy(id = 113, postId = post.id, content = content, parentId = parentId)
                serverReplies += created
                Result.success(created)
            }
        }
        withViewModel(repo) { vm ->
            runCurrent()
            vm.expandReplies(parent)
            runCurrent()
            repo.failSubmission = true
            vm.setReplyingTo(vm.comments.value.single())
            vm.sendComment("New reply", comment.author)
            runCurrent()
            assertEquals(12, CommentsAdapter.displayedReplyCount(vm.comments.value.single()))

            repo.failSubmission = false
            vm.retryComment(vm.comments.value.single().replies.first { it.sendFailed })
            runCurrent()
            assertEquals(13, vm.comments.value.single().replyCount)
            assertEquals(13, CommentsAdapter.displayedReplyCount(vm.comments.value.single()))
            assertEquals(14, repo.postsMapFlow.value.getValue(post.id).commentCount)

            // Replies 11 and 12 remain reachable on page 2 without duplicating reply 113.
            vm.loadMoreReplies(parent.id)
            runCurrent()
            val ids = vm.comments.value.single().replies.map { it.id }
            assertTrue(111L in ids && 112L in ids && 113L in ids)
            assertEquals(ids.distinct().size, ids.size)
            assertEquals(13, CommentsAdapter.displayedReplyCount(vm.comments.value.single()))
        }
    }

    @Test
    fun commentsAdapterDisplayedReplyCountIgnoresSendingAndFailedReplies() {
        val base = comment.copy(id = 1, replyCount = 0)
        val sending = comment.copy(id = -1, parentId = 1, isSending = true, sendFailed = false)
        val failed = comment.copy(id = -2, parentId = 1, isSending = false, sendFailed = true)
        val confirmedTwelve = (101L..112L).map { comment.copy(id = it, parentId = 1) }

        assertEquals(0, CommentsAdapter.displayedReplyCount(base.copy(replies = listOf(sending))))
        assertEquals(0, CommentsAdapter.displayedReplyCount(base.copy(replies = listOf(failed))))
        assertEquals(12, CommentsAdapter.displayedReplyCount(base.copy(replyCount = 12, replies = confirmedTwelve + sending)))
        assertEquals(12, CommentsAdapter.displayedReplyCount(base.copy(replyCount = 12, replies = confirmedTwelve + failed)))
        assertEquals(12, CommentsAdapter.displayedReplyCount(base.copy(replyCount = 12, replies = confirmedTwelve.take(10) + failed)))
        assertEquals(13, CommentsAdapter.displayedReplyCount(base.copy(replyCount = 12, replies = confirmedTwelve + comment.copy(id = 113, parentId = 1))))
    }

    @Test
    fun sortChangeDuringInFlightSubmissionAppliesResultOnceInBothOrders() = runTest {
        // Order 1 (Root): sort page returns first (already including confirmed id), then postComment completes.
        run {
            val repo = FakeFeed()
            val pageDeferred = CompletableDeferred<Result<List<Comment>>>()
            val postDeferred = CompletableDeferred<Result<Comment>>()
            val confirmed = comment.copy(id = 50, content = "In-flight root")
            repo.sortedPages = { _, sort, parent ->
                if (parent != null) Result.success(emptyList())
                else if (sort == "newest") pageDeferred.await()
                else Result.success(listOf(comment))
            }
            repo.submitResult = { _, _ -> postDeferred.await() }
            withViewModel(repo) { vm ->
                runCurrent()
                vm.sendComment("In-flight root", comment.author)
                vm.setSortMode("newest")
                runCurrent()

                pageDeferred.complete(Result.success(listOf(confirmed, comment)))
                runCurrent()
                assertTrue(vm.comments.value.any { it.content == "In-flight root" && it.isSending })

                postDeferred.complete(Result.success(confirmed))
                runCurrent()
                assertEquals(1, vm.comments.value.count { it.id == 50L })
                assertEquals(1, vm.comments.value.count { it.content == "In-flight root" })
                assertTrue(vm.comments.value.none { it.isSending || it.sendFailed })
            }
        }

        // Order 2 (Root): postComment completes first, then stale sort page returns without it.
        run {
            val repo = FakeFeed()
            val pageDeferred = CompletableDeferred<Result<List<Comment>>>()
            val postDeferred = CompletableDeferred<Result<Comment>>()
            val confirmed = comment.copy(id = 51, content = "In-flight root 2")
            repo.sortedPages = { _, sort, parent ->
                if (parent != null) Result.success(emptyList())
                else if (sort == "newest") pageDeferred.await()
                else Result.success(listOf(comment))
            }
            repo.submitResult = { _, _ -> postDeferred.await() }
            withViewModel(repo) { vm ->
                runCurrent()
                vm.sendComment("In-flight root 2", comment.author)
                vm.setSortMode("newest")
                runCurrent()

                postDeferred.complete(Result.success(confirmed))
                runCurrent()
                pageDeferred.complete(Result.success(listOf(comment))) // Stale snapshot missing id 51.
                runCurrent()
                assertEquals(1, vm.comments.value.count { it.id == 51L })
                assertTrue(vm.comments.value.none { it.isSending || it.sendFailed })
            }
        }

        // Order 1 (Reply): sort page returns first (even with server count already at 13), then postComment succeeds -> stays 13 (not 14).
        run {
            val parent = comment.copy(id = 7, replyCount = 12)
            val repo = FakeFeed()
            val pageDeferred = CompletableDeferred<Result<List<Comment>>>()
            val postDeferred = CompletableDeferred<Result<Comment>>()
            val confirmed = comment.copy(id = 60, parentId = parent.id, content = "In-flight reply")
            repo.sortedPages = { _, sort, p ->
                if (p != null) Result.success(emptyList())
                else if (sort == "newest") pageDeferred.await()
                else Result.success(listOf(parent))
            }
            repo.submitResult = { _, _ -> postDeferred.await() }
            withViewModel(repo) { vm ->
                runCurrent()
                vm.setReplyingTo(parent)
                vm.sendComment("In-flight reply", comment.author)
                vm.setSortMode("newest")
                runCurrent()

                pageDeferred.complete(Result.success(listOf(parent.copy(replyCount = 13))))
                runCurrent()
                var currentParent = vm.comments.value.single()
                assertEquals(12, currentParent.replyCount)
                assertEquals(12, CommentsAdapter.displayedReplyCount(currentParent))
                assertTrue(currentParent.replies.any { it.content == "In-flight reply" && it.isSending })

                postDeferred.complete(Result.success(confirmed))
                runCurrent()
                currentParent = vm.comments.value.single()
                assertEquals(13, currentParent.replyCount)
                assertEquals(13, CommentsAdapter.displayedReplyCount(currentParent))
                assertEquals(1, currentParent.replies.count { it.id == 60L })
                assertTrue(currentParent.replies.none { it.isSending || it.sendFailed })
            }
        }

        // Order 1 (Reply failure): sort page returns first, then postComment fails -> stays 12 and retryable.
        run {
            val parent = comment.copy(id = 8, replyCount = 12)
            val repo = FakeFeed()
            val pageDeferred = CompletableDeferred<Result<List<Comment>>>()
            val postDeferred = CompletableDeferred<Result<Comment>>()
            repo.sortedPages = { _, sort, p ->
                if (p != null) Result.success(emptyList())
                else if (sort == "newest") pageDeferred.await()
                else Result.success(listOf(parent))
            }
            repo.submitResult = { _, _ -> postDeferred.await() }
            withViewModel(repo) { vm ->
                runCurrent()
                vm.setReplyingTo(parent)
                vm.sendComment("In-flight fail reply", comment.author)
                vm.setSortMode("newest")
                runCurrent()

                pageDeferred.complete(Result.success(listOf(parent)))
                runCurrent()
                postDeferred.complete(Result.failure(IllegalStateException("503")))
                runCurrent()

                val currentParent = vm.comments.value.single()
                assertEquals(12, currentParent.replyCount)
                assertEquals(12, CommentsAdapter.displayedReplyCount(currentParent))
                val failed = currentParent.replies.single()
                assertEquals("In-flight fail reply", failed.content)
                assertEquals(parent.id, failed.parentId)
                assertTrue(failed.sendFailed)
                assertFalse(failed.isSending)
            }
        }

        // Order 2 (Reply): postComment succeeds first, then stale sort page returns replyCount = 12 -> still 13 with reply attached once.
        run {
            val parent = comment.copy(id = 9, replyCount = 12)
            val repo = FakeFeed()
            val pageDeferred = CompletableDeferred<Result<List<Comment>>>()
            val postDeferred = CompletableDeferred<Result<Comment>>()
            val confirmed = comment.copy(id = 61, parentId = parent.id, content = "Fast reply")
            repo.sortedPages = { _, sort, p ->
                if (p != null) Result.success(emptyList())
                else if (sort == "newest") pageDeferred.await()
                else Result.success(listOf(parent))
            }
            repo.submitResult = { _, _ -> postDeferred.await() }
            withViewModel(repo) { vm ->
                runCurrent()
                vm.setReplyingTo(parent)
                vm.sendComment("Fast reply", comment.author)
                vm.setSortMode("newest")
                runCurrent()

                postDeferred.complete(Result.success(confirmed))
                runCurrent()
                pageDeferred.complete(Result.success(listOf(parent))) // Stale snapshot with replyCount = 12.
                runCurrent()

                val currentParent = vm.comments.value.single()
                assertEquals(13, currentParent.replyCount)
                assertEquals(13, CommentsAdapter.displayedReplyCount(currentParent))
                assertEquals(1, currentParent.replies.count { it.id == 61L })
            }
        }
    }

    @Test
    fun retryReusesClientIdWhileTwoIntentionalSendsUseDistinctClientIds() = runTest {
        val repo = FakeFeed(post.copy(commentCount = 2))
        val parent = comment.copy(id = 10, replyCount = 0)
        val serverRoots = mutableListOf(parent)
        val savedByClientId = mutableMapOf<String, Comment>()
        var nextId = 500L
        var inject502AfterSave = true

        repo.sortedPages = { _, sort, p ->
            if (p != null) {
                Result.success(savedByClientId.values.filter { it.parentId == p })
            } else {
                val rootsWithCounts = serverRoots.map { root ->
                    val replyCount = savedByClientId.values.count { it.parentId == root.id }
                    root.copy(replyCount = replyCount)
                }
                val ordered = if (sort == "newest") {
                    rootsWithCounts.sortedByDescending { it.id }
                } else {
                    rootsWithCounts
                }
                Result.success(ordered)
            }
        }
        repo.submitWithClientId = { content, parentId, clientId ->
            assertTrue("Expected UUID clientId on send", !clientId.isNullOrBlank())
            val saved = savedByClientId.getOrPut(clientId!!) {
                val replyCountAfterInsert = if (parentId != null) {
                    savedByClientId.values.count { it.parentId == parentId } + 1
                } else {
                    null
                }
                val created = comment.copy(
                    id = nextId++,
                    postId = post.id,
                    parentId = parentId,
                    content = content,
                    clientId = clientId,
                    serverParentReplyCount = replyCountAfterInsert
                )
                if (parentId == null) serverRoots += created
                created
            }

            if (inject502AfterSave) {
                Result.failure(IllegalStateException("502 after save"))
            } else {
                Result.success(saved)
            }
        }

        withViewModel(repo) { vm ->
            runCurrent()

            // 1. Reply nhận 502 sau khi server đã lưu -> đổi sắp xếp -> Retry dùng lại đúng clientId & parentId.
            vm.setReplyingTo(parent)
            vm.sendComment("920001", comment.author)
            runCurrent()

            val firstClientId = repo.submittedClientIds.single()
            assertTrue(!firstClientId.isNullOrBlank())
            val failedReply = vm.comments.value.first { it.id == parent.id }.replies.single()
            assertTrue(failedReply.sendFailed)

            // Đổi sắp xếp sau 502 để GET thấy trước parent.replyCount == 1 từ server trước khi Retry chạy.
            vm.setSortMode("newest")
            runCurrent()
            val failedAfterSort = vm.comments.value.first { it.id == parent.id }.replies.single()
            assertTrue(failedAfterSort.sendFailed)

            inject502AfterSave = false
            vm.retryComment(failedAfterSort)
            runCurrent()

            assertEquals(listOf(firstClientId, firstClientId), repo.submittedClientIds)
            assertEquals(listOf("920001" to parent.id, "920001" to parent.id), repo.submissions)
            assertEquals(1, savedByClientId.size)

            val updatedParent = vm.comments.value.first { it.id == parent.id }
            assertEquals(1, updatedParent.replyCount)
            assertEquals(1, CommentsAdapter.displayedReplyCount(updatedParent))
            assertEquals(1, updatedParent.replies.size)
            assertNull(updatedParent.nextReplyPage)

            // 2. Hai lần gửi chủ ý cùng nội dung phải sinh hai clientId khác nhau và tạo hai bản ghi.
            vm.sendComment("920099", comment.author)
            runCurrent()
            vm.sendComment("920099", comment.author)
            runCurrent()

            val rootClientIds = repo.submittedClientIds.drop(2)
            assertEquals(2, rootClientIds.size)
            assertTrue(rootClientIds[0] != rootClientIds[1])
            assertEquals(2, vm.comments.value.count { it.content == "920099" })
        }
    }

    @Test
    fun heldPostResponseDeduplicatesByClientIdDuringSortAndHandlesReverseOrder() = runTest {
        // Phần A (Bình luận gốc): 201 bị giữ trong lúc GET đã trả về -> chỉ hiện 1 dòng NGAY KHI 201 chưa về.
        run {
            val repo = FakeFeed(post.copy(commentCount = 1))
            val pageDeferred = CompletableDeferred<Result<List<Comment>>>()
            val postDeferred = CompletableDeferred<Result<Comment>>()
            var capturedClientId: String? = null

            repo.sortedPages = { _, sort, parent ->
                when {
                    parent != null -> Result.success(emptyList())
                    sort == "newest" -> pageDeferred.await()
                    else -> Result.success(listOf(comment))
                }
            }
            repo.submitWithClientId = { _, _, clientId ->
                capturedClientId = clientId
                postDeferred.await()
            }

            withViewModel(repo) { vm ->
                runCurrent()
                vm.sendComment("930001", comment.author)
                vm.setSortMode("newest")
                runCurrent()
                assertTrue(!capturedClientId.isNullOrBlank())

                val serverCopy = comment.copy(
                    id = 701,
                    content = "930001",
                    clientId = capturedClientId
                )
                // Người dùng khác gửi cùng nội dung (không trùng clientId) phải được giữ riêng.
                val otherUserSameText = comment.copy(
                    id = 702,
                    author = Author(99, "other", "Other", null),
                    content = "930001",
                    clientId = null
                )
                pageDeferred.complete(Result.success(listOf(serverCopy, otherUserSameText, comment)))
                runCurrent()

                // Khi 201 vẫn bị giữ: bình luận của mình hiện 1 lần (không còn Sending), bản người khác giữ nguyên.
                assertEquals(2, vm.comments.value.count { it.content == "930001" })
                assertEquals(1, vm.comments.value.count { it.author.id == comment.author.id && it.content == "930001" })
                assertTrue(vm.comments.value.none { it.isSending })

                // Nhả 201 -> vẫn đúng 1 dòng của mình + 1 dòng của người khác.
                postDeferred.complete(Result.success(serverCopy))
                runCurrent()
                assertEquals(1, vm.comments.value.count { it.id == 701L })
                assertEquals(2, vm.comments.value.count { it.content == "930001" })
            }
        }

        // Phần B (Phản hồi con): 201 bị giữ trong lúc loadMoreReplies trả về -> chỉ hiện 1 dòng NGAY KHI 201 chưa về.
        run {
            val parent = comment.copy(id = 10, replyCount = 1)
            val repo = FakeFeed(post.copy(commentCount = 2))
            val repliesDeferred = CompletableDeferred<Result<List<Comment>>>()
            val postDeferred = CompletableDeferred<Result<Comment>>()
            var capturedClientId: String? = null

            repo.sortedPages = { _, _, p ->
                if (p == null) Result.success(listOf(parent)) else repliesDeferred.await()
            }
            repo.submitWithClientId = { _, _, clientId ->
                capturedClientId = clientId
                postDeferred.await()
            }

            withViewModel(repo) { vm ->
                runCurrent()
                vm.setReplyingTo(parent)
                vm.sendComment("930002", comment.author)
                runCurrent()
                assertTrue(!capturedClientId.isNullOrBlank())

                val savedReply = comment.copy(
                    id = 710,
                    parentId = parent.id,
                    content = "930002",
                    clientId = capturedClientId,
                    serverParentReplyCount = 2
                )
                val existingReply = comment.copy(id = 709, parentId = parent.id, content = "Older")
                repliesDeferred.complete(Result.success(listOf(existingReply, savedReply)))
                runCurrent()

                // Khi 201 vẫn bị giữ: phản hồi chỉ hiện 1 lần (không còn dòng Sending trùng).
                val pWhileHeld = vm.comments.value.single()
                assertEquals(1, pWhileHeld.replies.count { it.content == "930002" })
                assertTrue(pWhileHeld.replies.none { it.isSending })

                postDeferred.complete(Result.success(savedReply))
                runCurrent()
                val pAfter = vm.comments.value.single()
                assertEquals(1, pAfter.replies.count { it.id == 710L })
                assertEquals(2, pAfter.replyCount)
                assertEquals(2, CommentsAdapter.displayedReplyCount(pAfter))
            }
        }

        // Phần C (Thứ tự ngược): POST hoàn tất trước, sau đó GET cũ thiếu bản ghi mới về -> vẫn chỉ hiện 1 dòng.
        run {
            val repo = FakeFeed(post.copy(commentCount = 1))
            val pageDeferred = CompletableDeferred<Result<List<Comment>>>()
            val postDeferred = CompletableDeferred<Result<Comment>>()
            var capturedClientId: String? = null

            repo.sortedPages = { _, sort, parent ->
                when {
                    parent != null -> Result.success(emptyList())
                    sort == "newest" -> pageDeferred.await()
                    else -> Result.success(listOf(comment))
                }
            }
            repo.submitWithClientId = { _, _, clientId ->
                capturedClientId = clientId
                postDeferred.await()
            }

            withViewModel(repo) { vm ->
                runCurrent()
                vm.sendComment("930003", comment.author)
                vm.setSortMode("newest")
                runCurrent()

                val confirmed = comment.copy(
                    id = 720,
                    content = "930003",
                    clientId = capturedClientId
                )
                postDeferred.complete(Result.success(confirmed))
                runCurrent()

                // GET cũ về sau, chưa chứa bình luận id = 720.
                pageDeferred.complete(Result.success(listOf(comment)))
                runCurrent()

                assertEquals(1, vm.comments.value.count { it.id == 720L })
                assertEquals(1, vm.comments.value.count { it.content == "930003" })
                assertTrue(vm.comments.value.none { it.isSending || it.sendFailed })
            }
        }
    }

    @Test
    fun concurrentSecondSessionReplyUpdatesParentAndPostCountsWithoutReload() = runTest {
        // Biến thể A: Cha bắt đầu ở 0 phản hồi. 201 của máy bị giữ (parentReplyCount=1, postCommentCount=3).
        // Phiên 2 gửi thêm phản hồi #2 (server lên 2/4). Máy đổi sắp xếp rồi 201 về -> phải tự đồng bộ về 2 & 4.
        run {
            val parent = comment.copy(id = 20, replyCount = 0)
            val otherRoot = comment.copy(id = 21, replyCount = 0)
            var serverPostCount = 2
            val serverReplies = mutableListOf<Comment>()
            val postDeferred = CompletableDeferred<Result<Comment>>()
            val pageDeferred = CompletableDeferred<Result<List<Comment>>>()
            var capturedClientId: String? = null

            val repo = FakeFeed(post.copy(commentCount = 2))
            repo.postDetailProvider = { post.copy(commentCount = serverPostCount) }
            repo.sortedPages = { _, sort, p ->
                when {
                    p == parent.id -> Result.success(serverReplies.toList())
                    p != null -> Result.success(emptyList())
                    sort == "newest" -> pageDeferred.await()
                    else -> Result.success(listOf(parent.copy(replyCount = serverReplies.size), otherRoot))
                }
            }
            repo.submitWithClientId = { _, _, clientId ->
                capturedClientId = clientId
                postDeferred.await()
            }

            withViewModel(repo) { vm ->
                runCurrent()
                vm.setReplyingTo(parent)
                vm.sendComment("940001", comment.author)
                runCurrent()

                // Server lưu phản hồi của máy (#1) và của phiên 2 (#2) trong lúc 201 của #1 bị giữ.
                val phoneSaved = comment.copy(
                    id = 801,
                    parentId = parent.id,
                    content = "940001",
                    clientId = capturedClientId,
                    serverParentReplyCount = 1
                )
                val secondSessionSaved = comment.copy(
                    id = 802,
                    parentId = parent.id,
                    author = Author(2, "other", "Other", null),
                    content = "940002"
                )
                serverReplies += phoneSaved
                serverReplies += secondSessionSaved
                serverPostCount = 4

                vm.setSortMode("newest")
                runCurrent()
                pageDeferred.complete(Result.success(listOf(otherRoot, parent.copy(replyCount = 2))))
                runCurrent()

                // Nhả 201 cũ (chỉ mang parentReplyCount = 1, postCommentCount = 3).
                postDeferred.complete(Result.success(phoneSaved))
                runCurrent()

                val finalParent = vm.comments.value.first { it.id == parent.id }
                assertEquals(2, finalParent.replyCount)
                assertEquals(2, CommentsAdapter.displayedReplyCount(finalParent))
                assertEquals(setOf(801L, 802L), finalParent.replies.map { it.id }.toSet())
                assertEquals(2, finalParent.replies.size)
                assertEquals(4, repo.postsMapFlow.value.getValue(post.id).commentCount)
            }
        }

        // Biến thể B: Phiên 2 lưu TRƯỚC khi POST của máy lưu (GET thấy 1, sau đó 201 của máy trả parentReplyCount=2).
        run {
            val parent = comment.copy(id = 30, replyCount = 0)
            val serverReplies = mutableListOf<Comment>()
            val postDeferred = CompletableDeferred<Result<Comment>>()
            val pageDeferred = CompletableDeferred<Result<List<Comment>>>()
            var capturedClientId: String? = null

            val repo = FakeFeed(post.copy(commentCount = 1))
            repo.postDetailProvider = { post.copy(commentCount = 3) }
            repo.sortedPages = { _, sort, p ->
                when {
                    p == parent.id -> Result.success(serverReplies.toList())
                    p != null -> Result.success(emptyList())
                    sort == "newest" -> pageDeferred.await()
                    else -> Result.success(listOf(parent))
                }
            }
            repo.submitWithClientId = { _, _, clientId ->
                capturedClientId = clientId
                postDeferred.await()
            }

            withViewModel(repo) { vm ->
                runCurrent()
                vm.setReplyingTo(parent)
                vm.sendComment("940011", comment.author)
                runCurrent()

                val secondSessionSaved = comment.copy(
                    id = 811,
                    parentId = parent.id,
                    author = Author(2, "other", "Other", null),
                    content = "940010"
                )
                serverReplies += secondSessionSaved

                vm.setSortMode("newest")
                runCurrent()
                pageDeferred.complete(Result.success(listOf(parent.copy(replyCount = 1))))
                runCurrent()

                val phoneSaved = comment.copy(
                    id = 812,
                    parentId = parent.id,
                    content = "940011",
                    clientId = capturedClientId,
                    serverParentReplyCount = 2
                )
                serverReplies += phoneSaved
                postDeferred.complete(Result.success(phoneSaved))
                runCurrent()

                val finalParent = vm.comments.value.single()
                assertEquals(2, finalParent.replyCount)
                assertEquals(2, CommentsAdapter.displayedReplyCount(finalParent))
                assertEquals(setOf(811L, 812L), finalParent.replies.map { it.id }.toSet())
                assertEquals(3, repo.postsMapFlow.value.getValue(post.id).commentCount)
            }
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
        val submittedClientIds = mutableListOf<String?>()
        var failSubmission = false
        val rootPages = mutableListOf<Int>()
        val likes = mutableListOf<Boolean>()
        var pages: suspend (Int, Long?) -> Result<List<Comment>> = { _, _ -> Result.success(listOf(comment)) }
        var sortedPages: (suspend (Int, String, Long?) -> Result<List<Comment>>)? = null
        var submitResult: (suspend (String, Long?) -> Result<Comment>)? = null
        var submitWithClientId: (suspend (String, Long?, String?) -> Result<Comment>)? = null
        var postDetailProvider: (() -> Post)? = null
        var likeResult: suspend () -> Result<Boolean> = { Result.success(true) }

        override fun observePost(postId: Long) = postsMapFlow.map { it[postId] }
        override suspend fun getPostDetail(postId: Long) = runCatching {
            val loaded = postDetailProvider?.invoke() ?: postsMapFlow.value.getValue(postId)
            postsMapFlow.value = postsMapFlow.value + (postId to loaded)
            loaded
        }
        override suspend fun loadFollowingState(userId: Long) = Result.success(false)
        override suspend fun getComments(postId: Long, page: Int, pageSize: Int, sort: String, parentId: Long?): Result<List<Comment>> {
            if (parentId == null) rootPages += page
            return sortedPages?.invoke(page, sort, parentId) ?: pages(page, parentId)
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
        override suspend fun toggleFollowUser(userId: Long, currentFollowing: Boolean) = error("Unexpected request")
        override suspend fun postComment(postId: Long, content: String, parentId: Long?, clientId: String?): Result<Comment> {
            submissions += content to parentId
            submittedClientIds += clientId
            val result = submitWithClientId?.invoke(content, parentId, clientId)
                ?: submitResult?.invoke(content, parentId)
                ?: if (failSubmission) {
                    Result.failure(IllegalStateException("offline"))
                } else {
                    Result.success(comment.copy(id = 2, postId = postId, content = content, parentId = parentId, clientId = clientId))
                }
            if (result.isSuccess) {
                postsMapFlow.value[postId]?.let { existing ->
                    postsMapFlow.value = postsMapFlow.value + (postId to existing.copy(commentCount = existing.commentCount + 1))
                }
            }
            return result
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
