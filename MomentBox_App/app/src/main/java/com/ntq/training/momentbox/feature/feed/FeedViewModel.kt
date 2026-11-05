package com.ntq.training.momentbox.feature.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.realtime.SocketManager
import com.ntq.training.momentbox.data.local.db.AppDatabase
import com.ntq.training.momentbox.data.local.db.NotificationDao
import com.ntq.training.momentbox.data.repository.FeedRepository
import com.ntq.training.momentbox.domain.model.FeedItem
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.domain.model.User
import kotlinx.coroutines.Job
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FeedViewModel(
    private val feedRepository: FeedRepository,
    private val notificationDao: NotificationDao,
    private val socketManager: SocketManager,
    private var messageText: (Int) -> String
) : ViewModel() {

    constructor(
        feedRepository: FeedRepository,
        database: AppDatabase,
        socketManager: SocketManager,
        messageText: (Int) -> String
    ) : this(feedRepository, database.notificationDao(), socketManager, messageText)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _footerError = MutableStateFlow<String?>(null)
    val footerError: StateFlow<String?> = _footerError.asStateFlow()
    private var footerFallbackResource: Int? = null

    /** Fragment đưa Resources của locale hiện tại vào; ViewModel không giữ Activity hoặc View. */
    fun updateMessageText(resolver: (Int) -> String) {
        messageText = resolver
        footerFallbackResource?.let { _footerError.value = messageText(it) }
    }

    private fun showFooterError(error: Throwable?, fallback: Int) {
        val detail = error?.message?.takeIf { it.isNotBlank() }
        footerFallbackResource = if (detail == null) fallback else null
        _footerError.value = detail ?: messageText(fallback)
    }

    private fun failureText(resource: Int, error: Throwable?): String {
        val detail = error?.message?.takeIf { it.isNotBlank() }
        return messageText(resource) + if (detail == null) "" else ": $detail"
    }

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    private val _rawFeedItems = MutableStateFlow<List<FeedItem>>(emptyList())

    /** Chuỗi nhập do Fragment gửi; thuộc ViewModel để giữ qua lần tạo lại View. */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** Chờ người dùng ngừng gõ 300ms; xóa chuỗi phục hồi bảng tin ngay, không gọi API tìm kiếm.
     * Eagerly giữ chuỗi đã áp dụng qua lúc Fragment dừng quan sát hoặc tạo lại View.
     */
    @OptIn(FlowPreview::class)
    val appliedSearchQuery: StateFlow<String> = _searchQuery
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0L else 300L }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private var currentPage = 1
    private var hasMorePages = true

    /** Thế hệ tải dữ liệu bảng tin để bảo vệ chống race condition phân trang và refresh (F05) */
    private var feedGeneration: Long = 0L
    private var loadJob: Job? = null
    private var paginationJob: Job? = null
    private val followOperations = mutableMapOf<Long, Long>()
    private var nextFollowOperation = 0L
    private var followRevision = 0L

    private data class FollowState(val revision: Long, val following: Boolean)

    private val followStates = mutableMapOf<Long, FollowState>()

    /** Giữ caption và trang album qua lần tạo lại Fragment/View khi xoay máy. */
    val presentationState = FeedPresentationState()

    // Quan sát số thông báo chưa đọc từ Room Database
    val unreadNotificationsCount: StateFlow<Int> = socketManager.unreadNotificationCountFlow

    // Quan sát bài viết mới từ Socket.IO
    val newPostsCount: StateFlow<Int> = socketManager.feedNewCountFlow

    // Dòng chảy danh sách FeedItem kết hợp với cache bài viết và footer
    private val loadedFeedItems = combine(
        _rawFeedItems,
        feedRepository.postsMapFlow,
        _isLoadingMore,
        _footerError,
        feedRepository.followingMapFlow
    ) { rawItems, postsMap, loadingMore, footerErr, followingMap ->
        val result = mutableListOf<FeedItem>()

        for (item in rawItems) {
            when (item) {
                is FeedItem.PostItem -> {
                    // Nếu bài viết đã có bản cập nhật mới nhất trong postsMap (like, bookmark, commentCount), lấy bản mới nhất
                    val updatedPost = postsMap[item.post.id] ?: item.post
                    result.add(FeedItem.PostItem(updatedPost))
                }

                is FeedItem.Suggestions -> result.add(item.copy(users = item.users.map { user ->
                    // POST S04 đang chạy thì giữ optimistic state; còn lại nhận confirmed từ S05/GET.
                    if (user.id in followOperations) user
                    else followingMap[user.id]?.let { user.copy(isFollowing = it) } ?: user
                }))

                else -> result.add(item)
            }
        }

        // Chèn chân trang tải thêm nếu đang loading hoặc có lỗi
        if (loadingMore || footerErr != null) {
            result.add(
                FeedItem.LoadingFooter(
                    isLoading = loadingMore,
                    errorMessage = footerErr
                )
            )
        }

        result
    }

    /** Lọc sau khi ghép cache để like/bookmark và caption mới từ S05 vẫn phản ánh đúng.
     * Chỉ PostItem được tìm theo caption/hashtag; dữ liệu gốc, story và gợi ý vẫn được giữ để khôi phục.
     */
    val feedItems: StateFlow<List<FeedItem>> =
        combine(loadedFeedItems, appliedSearchQuery) { items, query ->
            if (query.isEmpty()) items else items.filter { item ->
                item is FeedItem.PostItem && (
                        item.post.caption.contains(query, ignoreCase = true) ||
                                item.post.tags.any { tag ->
                                    tag.removePrefix("#")
                                        .contains(query.removePrefix("#"), ignoreCase = true)
                                }
                        )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        socketManager.connect()
        loadInitialData()
    }

    /**
     * Tải dữ liệu ban đầu cho bảng tin trang 1.
     * Áp dụng chốt chặn thế hệ (Generation Guard) để hủy bỏ toàn bộ request trang 2 cũ đang bay (F05).
     */
    fun loadInitialData() {
        val gen = ++feedGeneration
        val readFollowRevision = followRevision
        loadJob?.cancel()
        paginationJob?.cancel()
        paginationJob = null
        // Refresh chỉ thay danh sách, không hủy POST follow đang chạy hoặc mở khóa cho tap mới.
        // Reset ngay ngoài coroutine để lần chạm tiếp theo không thấy trạng thái tải cũ.
        _isLoadingMore.value = false
        _isRefreshing.value = true
        loadJob = viewModelScope.launch {
            currentPage = 1
            hasMorePages = true
            _footerError.value = null
            footerFallbackResource = null

            val result = feedRepository.getFeed(page = 1, pageSize = 10)
            if (gen != feedGeneration) return@launch // Request cũ đã bị thay thế bởi refresh mới
            _isRefreshing.value = false

            if (result.isSuccess) {
                val items = result.getOrThrow()
                _rawFeedItems.value = reconcileFollowing(items, readFollowRevision)
                hasMorePages = items.isNotEmpty()
                currentPage = 1
                socketManager.clearFeedNewCount()
            } else {
                // Refresh lỗi: GIỮ NGUYÊN danh sách cũ, không xóa trắng màn hình của người dùng (F05)
                val errMsg = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                    ?: messageText(R.string.feed_error)
                if (_rawFeedItems.value.isEmpty()) {
                    showFooterError(result.exceptionOrNull(), R.string.feed_error)
                } else {
                    _snackbarMessage.emit(errMsg)
                }
            }
        }
    }

    /**
     * Kéo xuống để làm mới (Pull-to-refresh).
     */
    fun refresh() {
        loadInitialData()
    }

    /**
     * Tải trang bài viết tiếp theo khi người dùng cuộn xuống đáy danh sách.
     * Khử trùng lặp bài viết (Deduplication) và bảo vệ thế hệ phân trang (F05).
     */
    fun loadNextPage() {
        // Danh sách lọc ngắn có thể phát onScrolled ngay khi submitList; chặn cả thời gian debounce.
        if (_searchQuery.value.isNotBlank() || appliedSearchQuery.value.isNotEmpty()) return
        if (_isLoadingMore.value || !hasMorePages || _isRefreshing.value) return

        val gen = feedGeneration
        val readFollowRevision = followRevision
        _isLoadingMore.value = true
        _footerError.value = null
        footerFallbackResource = null
        paginationJob = viewModelScope.launch {
            val nextPage = currentPage + 1

            val result = feedRepository.getFeed(page = nextPage, pageSize = 10)
            if (gen != feedGeneration) return@launch // Phiên làm mới mới đã diễn ra, bỏ qua kết quả trang cũ
            _isLoadingMore.value = false

            if (result.isSuccess) {
                val newItems = reconcileFollowing(result.getOrThrow(), readFollowRevision)
                if (newItems.isEmpty()) {
                    hasMorePages = false
                } else {
                    currentPage = nextPage
                    // Khử trùng lặp bài viết đã có trên bảng tin (F05)
                    val existingPostIds = _rawFeedItems.value
                        .filterIsInstance<FeedItem.PostItem>()
                        .map { it.post.id }
                        .toSet()

                    val deduplicatedNewItems = newItems.filterNot { item ->
                        item is FeedItem.PostItem && existingPostIds.contains(item.post.id)
                    }

                    _rawFeedItems.value = _rawFeedItems.value + deduplicatedNewItems
                }
            } else {
                showFooterError(result.exceptionOrNull(), R.string.feed_load_more_error)
            }
        }
    }

    /**
     * Bấm thích bài viết (Optimistic Like).
     */
    fun toggleLike(post: Post) {
        viewModelScope.launch {
            val result = feedRepository.toggleLike(post.id)
            if (result.isFailure) {
                _snackbarMessage.emit(
                    result.exceptionOrNull()?.message ?: messageText(R.string.feed_like_failed)
                )
            }
        }
    }

    /**
     * Lưu hoặc bỏ lưu bài viết (Optimistic Bookmark).
     */
    fun toggleBookmark(post: Post) {
        viewModelScope.launch {
            val result = feedRepository.toggleBookmark(post.id)
            if (result.isFailure) {
                _snackbarMessage.emit(
                    result.exceptionOrNull()?.message ?: messageText(R.string.feed_bookmark_failed)
                )
            }
        }
    }

    /**
     * Theo dõi hoặc hủy theo dõi người dùng gợi ý (F10).
     * Kiểm tra Result phản hồi từ server; nếu lỗi thì rollback giao diện và thông báo người dùng.
     */
    fun toggleFollowSuggestion(user: User) {
        if (followOperations.containsKey(user.id)) return
        val current = _rawFeedItems.value.filterIsInstance<FeedItem.Suggestions>()
            .flatMap { it.users }.firstOrNull { it.id == user.id } ?: return
        val previousFollowing =
            feedRepository.followingMapFlow.value[user.id] ?: current.isFollowing
        val operation = ++nextFollowOperation
        followOperations[user.id] = operation
        updateSuggestedFollowing(user.id, !previousFollowing)
        viewModelScope.launch {
            try {
                val result = feedRepository.toggleFollowUser(user.id, previousFollowing)
                if (followOperations[user.id] != operation) return@launch
                // Repository loại phản hồi khác session/source; không rollback lên tài khoản mới.
                if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
                if (result.isFailure) {
                    // Chỉ hoàn tác trường theo dõi; không khôi phục bài đã xóa hoặc ghi đè trang mới.
                    updateSuggestedFollowing(user.id, previousFollowing)
                    _snackbarMessage.emit(
                        failureText(
                            R.string.feed_follow_failed,
                            result.exceptionOrNull()
                        )
                    )
                } else {
                    updateSuggestedFollowing(user.id, result.getOrThrow())
                }
            } finally {
                if (followOperations[user.id] == operation) followOperations.remove(user.id)
            }
        }
    }

    private fun updateSuggestedFollowing(userId: Long, following: Boolean) {
        followStates[userId] = FollowState(++followRevision, following)
        _rawFeedItems.value = _rawFeedItems.value.map { item ->
            if (item is FeedItem.Suggestions) item.copy(users = item.users.map { user ->
                if (user.id == userId) user.copy(isFollowing = following) else user
            }) else item
        }
    }

    /** Giữ thay đổi follow xảy ra trong lúc GET đang chờ; GET mới hơn vẫn được cập nhật từ server.
     * POST chưa xong thì giữ trạng thái tạm, tránh refresh nhấp nháy hoặc đảo hướng lần tap kế tiếp.
     */
    private fun reconcileFollowing(items: List<FeedItem>, readRevision: Long): List<FeedItem> =
        items.map { item ->
            if (item is FeedItem.Suggestions) item.copy(users = item.users.map { user ->
                val state = followStates[user.id]
                if (state != null && (state.revision > readRevision || user.id in followOperations)) {
                    user.copy(isFollowing = state.following)
                } else user
            }) else item
        }

    /**
     * Bỏ qua thẻ gợi ý khỏi danh sách.
     */
    fun dismissSuggestion(user: User) {
        val updated = _rawFeedItems.value.mapNotNull { item ->
            if (item is FeedItem.Suggestions) {
                val remainingUsers = item.users.filter { it.id != user.id }
                if (remainingUsers.isNotEmpty()) FeedItem.Suggestions(remainingUsers) else null
            } else {
                item
            }
        }
        _rawFeedItems.value = updated
    }

    /**
     * Xóa bài viết khỏi bảng tin (F10).
     * Chỉ xóa khỏi UI khi máy chủ xác nhận thành công; nếu lỗi hiển thị thông báo.
     */
    fun deletePost(postId: Long) {
        viewModelScope.launch {
            val result = feedRepository.deletePost(postId)
            if (result.isSuccess) {
                val updated = _rawFeedItems.value.filterNot {
                    it is FeedItem.PostItem && it.post.id == postId
                }
                _rawFeedItems.value = updated
                _snackbarMessage.emit(messageText(R.string.feed_post_deleted))
            } else {
                _snackbarMessage.emit(
                    failureText(
                        R.string.feed_delete_failed,
                        result.exceptionOrNull()
                    )
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        paginationJob?.cancel()
        socketManager.disconnect()
    }

    class Factory(
        private val feedRepository: FeedRepository,
        private val database: AppDatabase,
        private val socketManager: SocketManager,
        private val messageText: (Int) -> String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(FeedViewModel::class.java)) {
                return FeedViewModel(feedRepository, database, socketManager, messageText) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}