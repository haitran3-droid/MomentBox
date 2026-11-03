package com.example.momentbox_app.feature.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.momentbox_app.core.realtime.SocketManager
import com.example.momentbox_app.data.local.db.AppDatabase
import com.example.momentbox_app.data.local.db.NotificationDao
import com.example.momentbox_app.data.repository.FeedRepository
import com.example.momentbox_app.domain.model.FeedItem
import com.example.momentbox_app.domain.model.Post
import com.example.momentbox_app.domain.model.User
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FeedViewModel(
    private val feedRepository: FeedRepository,
    private val notificationDao: NotificationDao,
    private val socketManager: SocketManager
) : ViewModel() {
    constructor(
        feedRepository: FeedRepository,
        database: AppDatabase,
        socketManager: SocketManager
    ) : this(feedRepository, database.notificationDao(), socketManager)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _footerError = MutableStateFlow<String?>(null)
    val footerError: StateFlow<String?> = _footerError.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    private val _rawFeedItems = MutableStateFlow<List<FeedItem>>(emptyList())

    private var currentPage = 1
    private var hasMorePages = true

    private var feedGeneration: Long = 0L
    private var loadJob: Job? = null

    val unreadNotificationsCount: StateFlow<Int> = notificationDao
        .unreadCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val newPostsCount: StateFlow<Int> = socketManager.feedNewCountFlow

    val feedItems: StateFlow<List<FeedItem>> = combine(
        _rawFeedItems,
        feedRepository.postsMapFlow,
        _isLoadingMore,
        _footerError
    ) { rawItems, postsMap, loadingMore, footerErr ->
        val result = mutableListOf<FeedItem>()
        for (item in rawItems) {
            when (item) {
                is FeedItem.PostItem -> {
                    val updatePost = postsMap[item.post.id] ?: item.post
                    result.add(FeedItem.PostItem(updatePost))
                }

                else -> result.add(item)
            }
        }
        if (loadingMore || footerError != null) {
            result.add(
                FeedItem.LoadingFooter(
                    isLoading = loadingMore,
                    errorMessage = footerErr
                )
            )
        }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        socketManager.connect()
        loadInitialData()
    }

    private fun loadInitialData() {
        val gen = ++feedGeneration
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isRefreshing.value = true
            currentPage = 1
            hasMorePages = true
            _footerError.value = null

            val result = feedRepository.getFeed(page = 1, pageSize = 10)
            _isRefreshing.value = false

            if (gen != feedGeneration) return@launch
            if (result.isSuccess) {
                val items = result.getOrThrow()
                _rawFeedItems.value = items
                hasMorePages = items.isNotEmpty()
                currentPage = 1
                socketManager.clearFeedNewCount()
            } else {
                val errMsg = result.exceptionOrNull()?.message ?: "Lỗi tải bảng tin"
                if (_rawFeedItems.value.isEmpty()) {
                    _footerError.value = errMsg
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
        if (_isLoadingMore.value || !hasMorePages || _isRefreshing.value) return
        val gen = feedGeneration
        viewModelScope.launch {
            _isLoadingMore.value = true
            _footerError.value = null
            val nextPage = currentPage + 1

            val result = feedRepository.getFeed(page = nextPage, pageSize = 10)
            _isLoadingMore.value = false

            if (gen != feedGeneration) return@launch

            if (result.isSuccess) {
                val newItems = result.getOrThrow()
                if (newItems.isEmpty()) {
                    hasMorePages = false
                } else {
                    currentPage = nextPage
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
                _footerError.value =
                    result.exceptionOrNull()?.message ?: "Không thể kết nối máy chủ"
            }
        }
    }

    /**
     * Bấm thích bài viết
     */
    fun toggleLike(post: Post) {
        viewModelScope.launch {
            val result = feedRepository.toggleLike(post.id)
            if (result.isFailure) {
                _snackbarMessage.emit(
                    result.exceptionOrNull()?.message ?: "Không thể cập nhật thích bài viết"
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
                    result.exceptionOrNull()?.message ?: "Không thể cập nhật lưu bài viết"
                )
            }
        }
    }

    /**
     * Theo dõi hoặc hủy theo dõi người dùng gợi ý (F10).
     * Kiểm tra Result phản hồi từ server; nếu lỗi thì rollback giao diện và thông báo người dùng.
     */
    fun toggleFollowSuggestion(user: User) {
        viewModelScope.launch {
            val previousItems = _rawFeedItems.value
            val update = _rawFeedItems.value.map { item ->
                if (item is FeedItem.Suggestions) {
                    val updateUsers = item.users.map { u ->
                        if (u.id == user.id) u.copy(isFollowing = !u.isFollowing) else u
                    }
                    FeedItem.Suggestions(updateUsers)
                } else {
                    item
                }
            }
            _rawFeedItems.value = update
            val result = feedRepository.toggleFollowUser(user.id, user.isFollowing)
            if (result.isFailure) {
                _rawFeedItems.value = previousItems
                _snackbarMessage.emit("Không thể cập nhật theo dõi: ${result.exceptionOrNull()?.message ?: "Lỗi kết nối"}")
            }
        }
    }
    /**
     * Bỏ qua thẻ gợi ý khỏi danh sách.
     */
    fun dismissSuggestion(user: User){
        val updated = _rawFeedItems.value.mapNotNull { item ->
            if(item is FeedItem.Suggestions){
                val remainingUsers = item.users.filter{it.id != user.id}
                if(remainingUsers.isNotEmpty()) FeedItem.Suggestions(remainingUsers) else null
            }else{
                item
            }
        }
        _rawFeedItems.value = updated
    }
    /**
     * Xóa bài viết khỏi bảng tin.
     * Chỉ xóa khỏi UI khi máy chủ xác nhận thành công; nếu lỗi hiển thị thông báo.
     */
    fun deletePost(postId: Long){
        viewModelScope.launch {
            val result = feedRepository.deletePost(postId)
            if(result.isSuccess){
                val updated = _rawFeedItems.value.filterNot {
                    it is FeedItem.PostItem && it.post.id == postId
                }
                _rawFeedItems.value = updated
                _snackbarMessage.emit("Đã xóa bài viết thành công")
            }else{
                _snackbarMessage.emit("Xóa bài viết thất bại: ${result.exceptionOrNull()?.message ?: "Không có quyền"}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        socketManager.disconnect()
    }

    class Factory(
        private val feedRepository: FeedRepository,
        private val database: AppDatabase,
        private val socketManager: SocketManager
    ): ViewModelProvider.Factory{
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(FeedViewModel::class.java)) {
                return FeedViewModel(feedRepository, database, socketManager) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}