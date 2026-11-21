package com.ntq.training.momentbox.feature.postdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ntq.training.momentbox.data.repository.FeedRepository
import com.ntq.training.momentbox.domain.model.Author
import com.ntq.training.momentbox.domain.model.Comment
import com.ntq.training.momentbox.domain.model.Post
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class PostDetailViewModel(
    private val feedRepository: FeedRepository,
    private val postId: Long
) : ViewModel() {

    // Quan sát bài viết từ nguồn chân lý duy nhất (SSOT)
    val post: StateFlow<Post?> = feedRepository.observePost(postId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // A callback from a recycled row may run before the UI collects a permission change.
    val canComment: Boolean get() = feedRepository.postsMapFlow.value[postId]?.allowComments == true

    private val followOverride = MutableStateFlow<Pair<Long, Boolean>?>(null)
    private val _isFollowPending = MutableStateFlow(false)
    val isFollowPending: StateFlow<Boolean> = _isFollowPending.asStateFlow()
    /** Có quan hệ xác nhận mới cho phép UI toggle; pending chỉ biểu diễn request đang chạy. */
    val isFollowAvailable: StateFlow<Boolean> = combine(post, feedRepository.followingMapFlow) { currentPost, confirmed ->
        currentPost != null && currentPost.author.id in confirmed
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val _followErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val followErrors = _followErrors.asSharedFlow()
    val isFollowingAuthor: StateFlow<Boolean> = combine(post, feedRepository.followingMapFlow, followOverride) { currentPost, confirmed, optimistic ->
        val authorId = currentPost?.author?.id
        if (authorId == null) false else optimistic?.takeIf { it.first == authorId }?.second ?: confirmed[authorId] ?: false
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleFollowAuthor() {
        val currentPost = post.value ?: return
        if (_isFollowPending.value) return
        val authorId = currentPost.author.id
        val currentFollowing = feedRepository.followingMapFlow.value[authorId] ?: return
        // Khóa ngay trước khi launch để hai lần chạm nhanh không tạo hai request.
        _isFollowPending.value = true
        followOverride.value = authorId to !currentFollowing
        viewModelScope.launch {
            try {
                val result = try {
                    feedRepository.toggleFollowUser(authorId, currentFollowing)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
                if (result.isFailure && result.exceptionOrNull() !is CancellationException) _followErrors.emit(Unit)
            } finally {
                // Bỏ state tạm để trở về confirmed state mới nhất, không phục hồi snapshot cũ.
                followOverride.value = null
                _isFollowPending.value = false
            }
        }
    }

    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments: StateFlow<List<Comment>> = _comments.asStateFlow()

    private val _isLoadingComments = MutableStateFlow(false)
    val isLoadingComments: StateFlow<Boolean> = _isLoadingComments.asStateFlow()

    private val _hasMoreComments = MutableStateFlow(true)
    val hasMoreComments: StateFlow<Boolean> = _hasMoreComments.asStateFlow()

    private val _sortMode = MutableStateFlow("top") // "top" hoặc "newest"
    val sortMode: StateFlow<String> = _sortMode.asStateFlow()

    private val _replyingTo = MutableStateFlow<Comment?>(null)
    val replyingTo: StateFlow<Comment?> = _replyingTo.asStateFlow()

    private data class ConfirmedSubmission(
        val comment: Comment,
        val version: Long,
        val minParentReplyCount: Int?
    )

    private var currentCommentPage = 0
    private var commentMutationVersion = 0L
    private var loadCommentsSequence = 0L
    private var activeCommentLoadId: Long? = null
    private var replyGeneration = 0L
    private var nextTemporaryId = -1L

    private val retryingCommentIds = mutableSetOf<Long>()
    private val likingCommentIds = mutableSetOf<Long>()
    private val pendingComments = linkedMapOf<Long, Comment>()
    private val inFlightSubmissions = mutableMapOf<Long, Long?>()
    private val inFlightParentBaseCounts = mutableMapOf<Long, Int>()
    private val observedServerParentCounts = mutableMapOf<Long, Int>()
    private val confirmedByClientId = mutableMapOf<String, Comment>()
    private val refreshedInFlightIds = mutableSetOf<Long>()
    private val recentlyConfirmed = mutableListOf<ConfirmedSubmission>()

    private val _commentLoadFailed = MutableStateFlow(false)
    val commentLoadFailed = _commentLoadFailed.asStateFlow()

    init {
        viewModelScope.launch {
            post.collect { if (it?.allowComments != true) _replyingTo.value = null }
        }
        loadPostDetail()
        loadComments(refresh = true)
    }

    private fun loadPostDetail() {
        viewModelScope.launch {
            _isFollowPending.value = true
            try {
                val loaded = feedRepository.getPostDetail(postId).getOrNull() ?: return@launch
                // Không coi entry chưa có trong cache là "chưa follow": cold deep link cần hỏi server.
                val relationship = feedRepository.loadFollowingState(loaded.author.id)
                if (relationship.isFailure && relationship.exceptionOrNull() !is CancellationException) {
                    _followErrors.emit(Unit)
                }
            } finally {
                _isFollowPending.value = false
            }
        }
    }

    private var loadCommentsJob: Job? = null

    /**
     * Tải danh sách bình luận (có hỗ trợ phân trang và hủy tác vụ cũ khi đổi chế độ sắp xếp).
     *
     * **Xử lý cuộc đua điều kiện (Race Condition Guard - Sửa lỗi F11):**
     * - Khi người dùng chuyển đổi bộ lọc từ "Phù hợp nhất" sang "Mới nhất" trong lúc request trước đó
     *   đang gửi, [loadCommentsJob]?.cancel() sẽ hủy ngay request cũ để dữ liệu cũ không ghi đè vào kết quả mới.
     * - Tôn trọng [_hasMoreComments]: Khi đã hết bình luận trên máy chủ, không bắn thêm request rỗng.
     */
    fun loadComments(refresh: Boolean = false, retry: Boolean = false) {
        if (!refresh && (!_hasMoreComments.value || _isLoadingComments.value)) return
        if (!refresh && !retry && _commentLoadFailed.value) return
        val loadId = ++loadCommentsSequence
        activeCommentLoadId = loadId
        if (refresh) {
            loadCommentsJob?.cancel()
            currentCommentPage = 0
        }
        val pageToLoad = currentCommentPage + 1
        val sort = _sortMode.value
        val requestVersion = commentMutationVersion
        _isLoadingComments.value = true
        _commentLoadFailed.value = false
        loadCommentsJob = viewModelScope.launch {
            try {
                val result = feedRepository.getComments(
                    postId = postId,
                    page = pageToLoad,
                    pageSize = 20,
                    sort = sort
                )
                coroutineContext.ensureActive()
                if (result.isSuccess) {
                    val newComments = result.getOrThrow()
                    val mergedPage = mergeServerPage(newComments, pageToLoad, requestVersion)
                    if (pageToLoad == 1) {
                        replyGeneration++
                        _comments.value = mergedPage
                    } else {
                        _comments.value = (_comments.value + mergedPage).distinctBy { it.id }
                    }
                    currentCommentPage = pageToLoad
                    _hasMoreComments.value = newComments.size >= 20
                } else {
                    _commentLoadFailed.value = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _commentLoadFailed.value = true
            } finally {
                if (activeCommentLoadId == loadId) {
                    activeCommentLoadId = null
                    recentlyConfirmed.clear()
                }
                if (coroutineContext[Job]?.isActive == true) {
                    _isLoadingComments.value = false
                }
            }
        }
    }

    /**
     * Chuyển đổi chế độ sắp xếp bình luận và nạp lại trang đầu tiên.
     */
    fun setSortMode(mode: String) {
        if (_sortMode.value != mode) {
            _sortMode.value = mode
            _hasMoreComments.value = true
            loadComments(refresh = true)
        }
    }

    /**
     * Thử lại bình luận hoặc phản hồi con bị lỗi gửi (Sửa lỗi F12).
     */
    fun retryComment(comment: Comment) {
        if (!canComment) return
        // Adapter có thể còn giữ row cũ trong lúc DiffUtil đang chạy; luôn kiểm tra state hiện tại.
        val current = findComment(comment.id) ?: pendingComments[comment.id] ?: return
        if (!current.sendFailed || current.isSending || !retryingCommentIds.add(current.id)) return

        val clientId = current.clientId ?: UUID.randomUUID().toString()
        val retrying = current.copy(isSending = true, sendFailed = false, clientId = clientId)
        pendingComments[current.id] = retrying
        inFlightSubmissions[current.id] = current.parentId
        refreshedInFlightIds.remove(current.id)
        current.parentId?.let { parentId ->
            if (parentId !in inFlightParentBaseCounts) {
                findComment(parentId)?.let { inFlightParentBaseCounts[parentId] = it.replyCount }
            }
        }

        updateCommentInList(current.id) { retrying }

        viewModelScope.launch {
            try {
                submitComment(retrying)
            } finally {
                // Giải phóng khóa khi request kết thúc để người dùng có thể thử lại nếu còn lỗi.
                retryingCommentIds.remove(current.id)
            }
        }
    }

    fun setReplyingTo(comment: Comment?) {
        _replyingTo.value = comment?.takeIf { canComment }
    }

    fun toggleLikePost() {
        viewModelScope.launch {
            feedRepository.toggleLike(postId)
        }
    }

    fun toggleBookmarkPost() {
        viewModelScope.launch {
            feedRepository.toggleBookmark(postId)
        }
    }

    fun toggleLikeComment(comment: Comment) {
        val current = findComment(comment.id) ?: return
        if (current.id <= 0 || !likingCommentIds.add(current.id)) return
        setCommentLiked(current.id, !current.isLiked)
        viewModelScope.launch {
            try {
                val result = feedRepository.toggleLikeComment(current.id, current.isLiked)
                setCommentLiked(current.id, result.getOrDefault(current.isLiked))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                setCommentLiked(current.id, current.isLiked)
            } finally {
                likingCommentIds.remove(current.id)
            }
        }
    }

    private fun setCommentLiked(id: Long, liked: Boolean) {
        updateCommentInList(id) {
            if (it.isLiked == liked) it else {
                val delta = if (liked) 1 else -1
                it.copy(isLiked = liked, likeCount = (it.likeCount + delta).coerceAtLeast(0))
            }
        }
    }

    fun expandReplies(parentComment: Comment) {
        val current = findComment(parentComment.id) ?: return
        val expand = !current.isRepliesExpanded
        updateCommentInList(current.id) { it.copy(isRepliesExpanded = expand) }
        if (expand && current.nextReplyPage == 1) loadMoreReplies(current.id)
    }

    fun loadMoreReplies(parentId: Long) {
        val current = _comments.value.firstOrNull { it.id == parentId } ?: return
        val page = current.nextReplyPage ?: return
        if (!current.isRepliesExpanded || current.loadingReplies) return

        val generation = replyGeneration
        val pageSize = 10
        updateCommentInList(parentId) { it.copy(loadingReplies = true, replyLoadFailed = false) }

        viewModelScope.launch {
            val result = try {
                feedRepository.getComments(
                    postId = postId,
                    page = page,
                    pageSize = pageSize,
                    sort = "top",
                    parentId = parentId
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }

            if (replyGeneration != generation) return@launch

            var matchedFailedReply = false
            updateCommentInList(parentId) { parent ->
                result.fold(
                    onSuccess = { replies ->
                        val serverByClientId = replies
                            .mapNotNull { reply -> reply.clientId?.let { it to reply } }
                            .toMap()

                        // Đối chiếu các phản hồi tạm theo clientId để tránh hiện 2 dòng khi POST còn treo.
                        for (localReply in parent.replies.filterNot { it.isConfirmed }) {
                            val cid = localReply.clientId ?: continue
                            val matched = serverByClientId[cid] ?: continue
                            if (localReply.id in inFlightSubmissions) {
                                confirmedByClientId[cid] = matched
                            } else {
                                pendingComments.remove(localReply.id)
                                matchedFailedReply = true
                            }
                        }

                        val confirmed = (parent.replies.filter { it.isConfirmed } + replies)
                            .distinctBy { it.id }
                        val local = parent.replies.filterNot { reply ->
                            reply.isConfirmed || isDeduplicatedByClientId(reply, serverByClientId)
                        }

                        parent.copy(
                            replies = confirmed + local,
                            nextReplyPage = if (replies.size >= pageSize) page + 1 else null,
                            loadingReplies = false
                        )
                    },
                    onFailure = {
                        parent.copy(loadingReplies = false, replyLoadFailed = true)
                    }
                )
            }

            if (matchedFailedReply) refreshPostDetailCount()
        }
    }

    fun sendComment(content: String, currentAuthor: Author) {
        if (!canComment || content.isBlank()) return

        val targetParent = _replyingTo.value
        val tempId = nextTemporaryId--
        val clientId = UUID.randomUUID().toString()
        val createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

        val tempComment = Comment(
            id = tempId,
            postId = postId,
            parentId = targetParent?.id,
            author = currentAuthor,
            content = content.trim(),
            likeCount = 0,
            isLiked = false,
            replyCount = 0,
            createdAt = createdAt,
            isSending = true,
            sendFailed = false,
            clientId = clientId
        )

        pendingComments[tempId] = tempComment
        inFlightSubmissions[tempId] = targetParent?.id
        refreshedInFlightIds.remove(tempId)

        // Chèn ngay bình luận tạm thời vào danh sách (giữ nguyên replyCount cho tới khi server xác nhận).
        if (targetParent != null) {
            val currentParent = findComment(targetParent.id)
            if (targetParent.id !in inFlightParentBaseCounts) {
                val baseCount = currentParent?.replyCount ?: targetParent.replyCount
                inFlightParentBaseCounts[targetParent.id] = baseCount
            }

            updateCommentInList(targetParent.id) { parent ->
                val nextPage = if (parent.replyCount == 0 && !parent.loadingReplies) {
                    null
                } else {
                    parent.nextReplyPage
                }
                parent.copy(
                    replies = parent.replies + tempComment,
                    nextReplyPage = nextPage,
                    isRepliesExpanded = true
                )
            }

            if (findComment(targetParent.id)?.nextReplyPage == 1) {
                loadMoreReplies(targetParent.id)
            }
        } else {
            _comments.value = listOf(tempComment) + _comments.value
        }

        _replyingTo.value = null

        viewModelScope.launch {
            submitComment(tempComment)
        }
    }

    private suspend fun submitComment(pending: Comment) {
        val result = try {
            feedRepository.postComment(
                postId = postId,
                content = pending.content,
                parentId = pending.parentId,
                clientId = pending.clientId
            )
        } catch (cancelled: CancellationException) {
            inFlightSubmissions.remove(pending.id)
            refreshedInFlightIds.remove(pending.id)
            cleanupInFlightParent(pending.parentId)
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

        val refreshedDuringFlight = refreshedInFlightIds.remove(pending.id)
        inFlightSubmissions.remove(pending.id)
        val observedFromList = pending.clientId?.let { confirmedByClientId.remove(it) }
        val confirmed = result.getOrNull() ?: observedFromList
        val parentId = pending.parentId

        if (confirmed == null) {
            cleanupInFlightParent(parentId)
            val failed = (pendingComments[pending.id] ?: pending).copy(
                isSending = false,
                sendFailed = true
            )
            pendingComments[pending.id] = failed
            updateCommentInList(pending.id) { failed }
            return
        }

        pendingComments.remove(pending.id)
        commentMutationVersion++

        if (parentId != null) {
            handleConfirmedReply(
                pending = pending,
                parentId = parentId,
                confirmed = confirmed,
                refreshedDuringFlight = refreshedDuringFlight,
                observedFromList = observedFromList != null
            )
        } else {
            if (activeCommentLoadId != null) {
                recentlyConfirmed += ConfirmedSubmission(confirmed, commentMutationVersion, null)
            }
            replacePendingRoot(pending.id, confirmed)
            if (refreshedDuringFlight || observedFromList != null) {
                refreshPostDetailCount()
            }
        }
    }

    private fun handleConfirmedReply(
        pending: Comment,
        parentId: Long,
        confirmed: Comment,
        refreshedDuringFlight: Boolean,
        observedFromList: Boolean
    ) {
        val currentParent = _comments.value.firstOrNull { it.id == parentId }
        val baseCount = currentParent?.replyCount ?: inFlightParentBaseCounts[parentId] ?: 0
        val basePlusOne = baseCount + 1
        val observedServer = observedServerParentCounts[parentId] ?: 0
        val serverFromPost = confirmed.serverParentReplyCount
        val confirmedInReplies = currentParent?.replies?.count { it.isConfirmed } ?: 0

        // Ưu tiên parentReplyCount do server trả về để tránh cộng trùng sau 502 + đổi sắp xếp + thử lại,
        // đồng thời lấy giá trị lớn nhất với số server vừa quan sát khi có phiên khác cùng trả lời.
        val updatedReplyCount = if (serverFromPost != null) {
            maxOf(serverFromPost, observedServer, confirmedInReplies)
        } else {
            maxOf(basePlusOne, observedServer, confirmedInReplies)
        }

        if (hasInFlightForParent(parentId)) {
            inFlightParentBaseCounts[parentId] = updatedReplyCount
        } else {
            inFlightParentBaseCounts.remove(parentId)
            observedServerParentCounts.remove(parentId)
        }

        if (activeCommentLoadId != null) {
            recentlyConfirmed += ConfirmedSubmission(
                comment = confirmed,
                version = commentMutationVersion,
                minParentReplyCount = updatedReplyCount
            )
        }

        if (currentParent != null) {
            updateCommentInList(parentId) { parent ->
                val updatedReplies = replacePendingReply(parent.replies, pending.id, confirmed)
                val newConfirmedCount = updatedReplies.count { it.isConfirmed }
                val nextPage = if (updatedReplyCount > newConfirmedCount) {
                    parent.nextReplyPage ?: 1
                } else {
                    null
                }
                parent.copy(
                    replyCount = updatedReplyCount,
                    replies = updatedReplies,
                    nextReplyPage = nextPage
                )
            }

            // Nếu có phản hồi từ phiên khác làm tổng cha vượt quá dự kiến, tự tải danh sách con đang mở.
            if (updatedReplyCount > basePlusOne) {
                val afterUpdate = findComment(parentId)
                if (afterUpdate != null && afterUpdate.isRepliesExpanded && afterUpdate.nextReplyPage == 1) {
                    loadMoreReplies(parentId)
                }
            }
        }

        val serverCountMovedAhead = serverFromPost != null && observedServer > serverFromPost
        val shouldSyncPostCount =
            refreshedDuringFlight || observedFromList || serverCountMovedAhead || updatedReplyCount > basePlusOne
        if (shouldSyncPostCount) {
            refreshPostDetailCount()
        }
    }

    private fun refreshPostDetailCount() {
        viewModelScope.launch {
            try {
                feedRepository.getPostDetail(postId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Giữ nguyên số đếm cục bộ nếu yêu cầu đồng bộ lại thất bại tạm thời.
            }
        }
    }

    private fun mergeServerPage(
        serverComments: List<Comment>,
        pageToLoad: Int,
        requestVersion: Long
    ): List<Comment> {
        if (pageToLoad == 1) {
            refreshedInFlightIds.addAll(inFlightSubmissions.keys)
        }

        val serverRootsByClientId = serverComments
            .mapNotNull { item -> item.clientId?.let { it to item } }
            .toMap()

        // 1. Đối chiếu các bình luận gốc tạm với bản đã lưu từ server theo clientId.
        var reconciledFailedRoot = false
        val failedIdsToRemove = mutableListOf<Long>()
        for ((pendingId, pending) in pendingComments) {
            if (pending.parentId != null) continue
            val cid = pending.clientId ?: continue
            val matched = serverRootsByClientId[cid] ?: continue

            if (pendingId in inFlightSubmissions) {
                confirmedByClientId[cid] = matched
            } else {
                failedIdsToRemove += pendingId
                reconciledFailedRoot = true
            }
        }
        failedIdsToRemove.forEach { pendingComments.remove(it) }
        if (reconciledFailedRoot) refreshPostDetailCount()

        // 2. Gom các phản hồi tạm và phản hồi vừa xác nhận theo từng bình luận cha.
        val staleConfirmed = recentlyConfirmed.filter { it.version > requestVersion }
        val pendingByParent = pendingComments.values
            .filter { it.parentId != null && !isDeduplicatedByClientId(it, emptyMap()) }
            .groupBy { it.parentId!! }
        val confirmedByParent = staleConfirmed
            .filter { it.comment.parentId != null }
            .groupBy { it.comment.parentId!! }

        // 3. Hợp nhất từng bình luận cha trên trang với phản hồi cục bộ và số đếm server mới nhất.
        val mergedRoots = serverComments.map { serverComment ->
            if (serverComment.id in inFlightParentBaseCounts) {
                val previousObserved = observedServerParentCounts[serverComment.id] ?: 0
                observedServerParentCounts[serverComment.id] = maxOf(previousObserved, serverComment.replyCount)
            }

            val pendingReplies = pendingByParent[serverComment.id].orEmpty()
            val confirmedEntries = confirmedByParent[serverComment.id].orEmpty()
            if (pendingReplies.isEmpty() && confirmedEntries.isEmpty()) {
                serverComment
            } else {
                val baseReplyCount = inFlightParentBaseCounts[serverComment.id] ?: serverComment.replyCount
                val minConfirmedCount = confirmedEntries.mapNotNull { it.minParentReplyCount }.maxOrNull() ?: 0
                val effectiveReplyCount = maxOf(baseReplyCount, minConfirmedCount)
                val confirmedReplies = confirmedEntries.map { it.comment }.distinctBy { it.id }
                val hasServerReplies = maxOf(effectiveReplyCount, serverComment.replyCount) > confirmedReplies.size

                serverComment.copy(
                    replyCount = effectiveReplyCount,
                    replies = confirmedReplies + pendingReplies,
                    isRepliesExpanded = true,
                    nextReplyPage = if (hasServerReplies) serverComment.nextReplyPage else null
                )
            }
        }

        if (pageToLoad != 1) return mergedRoots

        // 4. Ở trang 1, giữ lại các bình luận gốc cục bộ chưa có trên trang server.
        val serverIds = mergedRoots.mapTo(mutableSetOf()) { it.id }
        val confirmedRoots = staleConfirmed
            .map { it.comment }
            .filter { it.parentId == null && it.id !in serverIds }
            .distinctBy { it.id }
            .reversed()
        val pendingRoots = pendingComments.values
            .filter { it.parentId == null && !isDeduplicatedByClientId(it, serverRootsByClientId) }
            .reversed()

        return (pendingRoots + confirmedRoots + mergedRoots).distinctBy { it.id }
    }

    private fun isDeduplicatedByClientId(
        localComment: Comment,
        serverByClientId: Map<String, Comment>
    ): Boolean {
        val cid = localComment.clientId ?: return false
        return cid in serverByClientId || cid in confirmedByClientId
    }

    private fun replacePendingRoot(pendingId: Long, confirmed: Comment) {
        val current = _comments.value
        val alreadyPresent = current.any { it.id == confirmed.id }
        _comments.value = when {
            alreadyPresent -> current.filterNot { it.id == pendingId }
            current.any { it.id == pendingId } -> current.map { if (it.id == pendingId) confirmed else it }
            else -> listOf(confirmed) + current
        }.distinctBy { it.id }
    }

    private fun replacePendingReply(replies: List<Comment>, pendingId: Long, confirmed: Comment): List<Comment> {
        val confirmedList = replies.filter { it.isConfirmed }
        val remainingLocal = replies.filterNot { it.isConfirmed || it.id == pendingId }
        return (confirmedList + confirmed).distinctBy { it.id } + remainingLocal
    }

    private fun hasInFlightForParent(parentId: Long): Boolean =
        inFlightSubmissions.values.any { it == parentId }

    private fun cleanupInFlightParent(parentId: Long?) {
        if (parentId != null && !hasInFlightForParent(parentId)) {
            inFlightParentBaseCounts.remove(parentId)
            observedServerParentCounts.remove(parentId)
        }
    }

    private fun findComment(id: Long): Comment? =
        _comments.value.firstOrNull { it.id == id }
            ?: _comments.value.asSequence().flatMap { it.replies.asSequence() }.firstOrNull { it.id == id }

    private fun updateCommentInList(commentId: Long, transform: (Comment) -> Comment) {
        _comments.value = _comments.value.map { root ->
            if (root.id == commentId) {
                transform(root)
            } else {
                // Tìm kiếm trong các phản hồi con
                val updatedReplies = root.replies.map { reply ->
                    if (reply.id == commentId) transform(reply) else reply
                }
                if (updatedReplies != root.replies) {
                    root.copy(replies = updatedReplies.distinctBy { it.id })
                } else {
                    root
                }
            }
        }.distinctBy { it.id }
    }

    class Factory(
        private val feedRepository: FeedRepository,
        private val postId: Long
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PostDetailViewModel::class.java)) {
                return PostDetailViewModel(feedRepository, postId) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
