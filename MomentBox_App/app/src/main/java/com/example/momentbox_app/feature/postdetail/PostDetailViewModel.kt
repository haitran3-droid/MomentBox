package com.example.momentbox_app.feature.postdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.momentbox_app.data.repository.FeedRepository
import com.example.momentbox_app.domain.model.Author
import com.example.momentbox_app.domain.model.Comment
import com.example.momentbox_app.domain.model.Post
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PostDetailViewModel(
    private val feedRepository: FeedRepository,
    private val postId: Long
) : ViewModel() {

    // Quan sát bài viết từ nguồn chân lý duy nhất (SSOT)
    val post: StateFlow<Post?> = feedRepository.observePost(postId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

    init {
        loadPostDetail()
        loadComments(refresh = true)
    }

    private fun loadPostDetail() {
        viewModelScope.launch {
            feedRepository.getPostDetail(postId)
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
        updateCommentInList(comment.id) {
            it.copy(isSending = true, sendFailed = false)
        }

        viewModelScope.launch {
            val result = feedRepository.postComment(
                postId = postId,
                content = comment.content,
                parentId = comment.parentId
            )

            if (result.isSuccess) {
                val confirmedComment = result.getOrThrow()
                if (comment.parentId != null) {
                    updateCommentInList(comment.parentId) { parent ->
                        parent.copy(
                            replies = parent.replies.map {
                                if (it.id == comment.id) confirmedComment else it
                            }
                        )
                    }
                } else {
                    _comments.value = _comments.value.map {
                        if (it.id == comment.id) confirmedComment else it
                    }
                }
            } else {
                if (comment.parentId != null) {
                    updateCommentInList(comment.parentId) { parent ->
                        parent.copy(
                            replies = parent.replies.map {
                                if (it.id == comment.id) it.copy(
                                    isSending = false,
                                    sendFailed = true
                                ) else it
                            }
                        )
                    }
                } else {
                    _comments.value = _comments.value.map {
                        if (it.id == comment.id) it.copy(
                            isSending = false,
                            sendFailed = true
                        ) else it
                    }
                }
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
            createdAt = "Vừa xong",
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
                                if (it.id == tempId) it.copy(
                                    isSending = false,
                                    sendFailed = true
                                ) else it
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