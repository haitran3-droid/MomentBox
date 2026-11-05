package com.ntq.training.momentbox.feature.postdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ntq.training.momentbox.data.repository.FeedRepository
import com.ntq.training.momentbox.domain.model.Author
import com.ntq.training.momentbox.domain.model.Comment
import com.ntq.training.momentbox.domain.model.Post
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class PostDetailViewModel(
    private val feedRepository: FeedRepository,
    private val postId: Long
) : ViewModel() {

    // Quan sát bài viết từ nguồn chân lý duy nhất (SSOT)
    val post: StateFlow<Post?> = feedRepository.observePost(postId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

    private var currentCommentPage = 1
    private val retryingCommentIds = mutableSetOf<Long>()

    init {
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

    private var loadCommentsJob: kotlinx.coroutines.Job? = null

    /**
     * Tải danh sách bình luận (có hỗ trợ phân trang và hủy tác vụ cũ khi đổi chế độ sắp xếp).
     *
     * **Xử lý cuộc đua điều kiện (Race Condition Guard - Sửa lỗi F11):**
     * - Khi người dùng chuyển đổi bộ lọc từ "Phù hợp nhất" sang "Mới nhất" trong lúc request trước đó
     *   đang gửi, [loadCommentsJob]?.cancel() sẽ hủy ngay request cũ để dữ liệu cũ không ghi đè vào kết quả mới.
     * - Tôn trọng [_hasMoreComments]: Khi đã hết bình luận trên máy chủ, không bắn thêm request rỗng.
     */
    fun loadComments(refresh: Boolean = false) {
        if (!refresh && !_hasMoreComments.value) return
        if (_isLoadingComments.value && !refresh) return

        if (refresh) {
            loadCommentsJob?.cancel()
        }

        loadCommentsJob = viewModelScope.launch {
            _isLoadingComments.value = true
            val pageToLoad = if (refresh) 1 else currentCommentPage + 1

            try {
                val result = feedRepository.getComments(
                    postId = postId,
                    page = pageToLoad,
                    pageSize = 20,
                    sort = _sortMode.value
                )
                _isLoadingComments.value = false

                if (result.isSuccess) {
                    val newComments = result.getOrThrow()
                    if (refresh) {
                        _comments.value = newComments
                        currentCommentPage = 1
                    } else {
                        _comments.value = _comments.value + newComments
                        currentCommentPage = pageToLoad
                    }
                    _hasMoreComments.value = newComments.size >= 20
                }
            } catch (e: Exception) {
                _isLoadingComments.value = false
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
        // Adapter có thể còn giữ row cũ trong lúc DiffUtil đang chạy; luôn kiểm tra state hiện tại.
        val current = _comments.value.firstOrNull { it.id == comment.id }
            ?: _comments.value.asSequence().flatMap { it.replies.asSequence() }.firstOrNull { it.id == comment.id }
            ?: return
        if (!current.sendFailed || current.isSending || !retryingCommentIds.add(current.id)) return

        updateCommentInList(current.id) {
            it.copy(isSending = true, sendFailed = false)
        }

        viewModelScope.launch {
            try {
                val result: Result<Comment> = try {
                    feedRepository.postComment(postId, current.content, current.parentId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
                updateCommentInList(current.id) {
                    result.getOrNull() ?: it.copy(isSending = false, sendFailed = true)
                }
            } finally {
                // Giải phóng khóa khi request kết thúc để người dùng có thể thử lại nếu còn lỗi.
                retryingCommentIds.remove(current.id)
            }
        }
    }

    fun setReplyingTo(comment: Comment?) {
        _replyingTo.value = comment
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
        viewModelScope.launch {
            val nextLiked = !comment.isLiked
            val delta = if (nextLiked) 1 else -1

            // Cập nhật lạc quan
            updateCommentInList(comment.id) {
                it.copy(
                    isLiked = nextLiked,
                    likeCount = (it.likeCount + delta).coerceAtLeast(0)
                )
            }

            val result = feedRepository.toggleLikeComment(comment.id, comment.isLiked)
            if (result.isFailure) {
                // Rollback nếu thất bại
                updateCommentInList(comment.id) {
                    it.copy(
                        isLiked = comment.isLiked,
                        likeCount = comment.likeCount
                    )
                }
            }
        }
    }

    fun expandReplies(parentComment: Comment) {
        viewModelScope.launch {
            // Đánh dấu mở rộng câu trả lời
            updateCommentInList(parentComment.id) {
                it.copy(isRepliesExpanded = !it.isRepliesExpanded)
            }

            // Nếu chưa có danh sách phản hồi, tải phản hồi con từ server
            if (parentComment.replies.isEmpty()) {
                val result = feedRepository.getComments(
                    postId = postId,
                    page = 1,
                    pageSize = 10,
                    sort = "top",
                    parentId = parentComment.id
                )
                if (result.isSuccess) {
                    val replies = result.getOrThrow()
                    updateCommentInList(parentComment.id) {
                        it.copy(replies = replies, isRepliesExpanded = true)
                    }
                }
            }
        }
    }

    fun sendComment(content: String, currentAuthor: Author) {
        if (content.isBlank()) return

        val targetParent = _replyingTo.value
        val tempId = -System.currentTimeMillis() // ID âm tạm thời cho optimistic update

        val tempComment = Comment(
            id = tempId,
            postId = postId,
            parentId = targetParent?.id,
            author = currentAuthor,
            content = content.trim(),
            likeCount = 0,
            isLiked = false,
            replyCount = 0,
            createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()),
            isSending = true,
            sendFailed = false
        )

        // Chèn ngay bình luận tạm thời vào danh sách
        if (targetParent != null) {
            updateCommentInList(targetParent.id) { parent ->
                parent.copy(
                    replyCount = parent.replyCount + 1,
                    replies = parent.replies + tempComment,
                    isRepliesExpanded = true
                )
            }
        } else {
            _comments.value = listOf(tempComment) + _comments.value
        }

        _replyingTo.value = null // Xóa trạng thái đang reply

        viewModelScope.launch {
            val result = feedRepository.postComment(
                postId = postId,
                content = content.trim(),
                parentId = targetParent?.id
            )

            if (result.isSuccess) {
                val confirmedComment = result.getOrThrow()
                // Cập nhật lại ID thật của bình luận
                if (targetParent != null) {
                    updateCommentInList(targetParent.id) { parent ->
                        parent.copy(
                            replies = parent.replies.map {
                                if (it.id == tempId) confirmedComment else it
                            }
                        )
                    }
                } else {
                    _comments.value = _comments.value.map {
                        if (it.id == tempId) confirmedComment else it
                    }
                }
            } else {
                // Đánh dấu gửi thất bại
                if (targetParent != null) {
                    updateCommentInList(targetParent.id) { parent ->
                        parent.copy(
                            replies = parent.replies.map {
                                if (it.id == tempId) it.copy(isSending = false, sendFailed = true) else it
                            }
                        )
                    }
                } else {
                    _comments.value = _comments.value.map {
                        if (it.id == tempId) it.copy(isSending = false, sendFailed = true) else it
                    }
                }
            }
        }
    }

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
                    root.copy(replies = updatedReplies)
                } else {
                    root
                }
            }
        }
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