package com.ntq.training.momentbox.data.repository

import com.ntq.training.momentbox.core.network.safeApiCall
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.local.prefs.SettingsStorage
import com.ntq.training.momentbox.data.local.prefs.TokenStorage
import com.ntq.training.momentbox.data.remote.api.DummyJsonApi
import com.ntq.training.momentbox.data.remote.api.FeedApi
import com.ntq.training.momentbox.data.remote.dto.BookmarkResponseDto
import com.ntq.training.momentbox.data.remote.dto.CommentDto
import com.ntq.training.momentbox.data.remote.dto.CommentLikeResponseDto
import com.ntq.training.momentbox.data.remote.dto.LikeResponseDto
import com.ntq.training.momentbox.data.remote.dto.PostCommentRequest
import com.ntq.training.momentbox.data.remote.dto.PostDto
import com.ntq.training.momentbox.data.remote.dto.RawFeedItemDto
import com.ntq.training.momentbox.data.remote.dto.StoryDto
import com.ntq.training.momentbox.data.remote.dto.UserDto
import com.ntq.training.momentbox.domain.mapper.CommentMapper
import com.ntq.training.momentbox.domain.mapper.PostMapper
import com.ntq.training.momentbox.domain.mapper.StoryMapper
import com.ntq.training.momentbox.domain.mapper.UserMapper
import com.ntq.training.momentbox.domain.model.Author
import com.ntq.training.momentbox.domain.model.Comment
import com.ntq.training.momentbox.domain.model.FeedItem
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.domain.model.Story
import com.ntq.training.momentbox.domain.model.User
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map as flowMap
import kotlinx.coroutines.CancellationException

class FeedRepositoryImpl(
    private val feedApi: FeedApi,
    private val dummyJsonApi: DummyJsonApi,
    private val settingsDataStore: SettingsStorage,
    private val tokenStore: TokenStorage? = null,
    private val postMapper: PostMapper = PostMapper(),
    private val storyMapper: StoryMapper = StoryMapper(postMapper),
    private val commentMapper: CommentMapper = CommentMapper(postMapper),
    private val userMapper: UserMapper = UserMapper(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val gson: Gson = Gson()
) : FeedRepository {

    private val _postsMap = MutableStateFlow<Map<Long, Post>>(emptyMap())
    override val postsMapFlow: StateFlow<Map<Long, Post>> = _postsMap.asStateFlow()

    private var cachedStories: List<Story> = emptyList()
    private var lastRecordedGeneration: Long = tokenStore?.getSessionGeneration() ?: 0L

    private fun validateSessionGeneration(): Long {
        val currentGen = tokenStore?.getSessionGeneration() ?: 0L
        if (currentGen != lastRecordedGeneration) {
            clearCache()
            lastRecordedGeneration = currentGen
        }
        return currentGen
    }

    override fun observePost(postId: Long): Flow<Post?> {
        return _postsMap.flowMap { it[postId] }.distinctUntilChanged()
    }

    override suspend fun getStories(): Result<List<Story>> = withContext(dispatcher) {
        val source = settingsDataStore.getDataSource()
        if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
            val dummyStories = listOf(
                storyMapper.createMyStory(Author(1, "my_user", "Tin của bạn", null, false)),
                Story(
                    user = Author(
                        2,
                        "emilys",
                        "Emily",
                        "https://picsum.photos/seed/user_2/100/100",
                        false
                    ), hasUnseen = true
                ),
                Story(
                    user = Author(
                        3,
                        "michael",
                        "Michael",
                        "https://picsum.photos/seed/user_3/100/100",
                        true
                    ), hasUnseen = true
                ),
                Story(
                    user = Author(
                        4,
                        "sophia",
                        "Sophia",
                        "https://picsum.photos/seed/user_4/100/100",
                        false
                    ), hasUnseen = false
                )
            )
            cachedStories = dummyStories
            return@withContext Result.success(dummyStories)
        }
        val result: Result<List<StoryDto>> = safeApiCall(dispatcher = dispatcher, gson = gson) {
            feedApi.getStories()
        }
        if (result.isSuccess) {
            val dtoList = result.getOrThrow()
            val myStory = storyMapper.createMyStory(
                Author(1, "me", "Tin của bạn", null, false)
            )
            val mapped = listOf(myStory) + (dtoList.map { storyMapper.map(it) })
            cachedStories = mapped
            Result.success(mapped)
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Lỗi tải story"))
        }
    }

    override suspend fun getFeed(page: Int, pageSize: Int): Result<List<FeedItem>> =
        withContext(dispatcher) {
            val source = settingsDataStore.getDataSource()

            if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
                val skip = (page - 1) * pageSize
                try {
                    val response = dummyJsonApi.getPosts(limit = pageSize, skip = skip)
                    if (response.isSuccessful && response.body() != null) {
                        val dummyPosts = response.body()?.posts ?: emptyList()
                        val mappedPosts = dummyPosts.map { postMapper.mapFromDummyJson(it) }

                        // Cập nhật vào cache tập trung
                        updatePostsCache(mappedPosts)

                        val feedItems = mutableListOf<FeedItem>()

                        // Ở trang đầu tiên, gắn thêm StoryBar và Gợi ý theo dõi giả lập
                        if (page == 1) {
                            if (cachedStories.isEmpty()) {
                                getStories()
                            }
                            feedItems.add(FeedItem.StoryBar(cachedStories))

                            val dummySuggestions = listOf(
                                User(
                                    id = 2,
                                    username = "emilys",
                                    displayName = "Emily Johnson",
                                    email = null,
                                    avatarUrl = "https://picsum.photos/seed/user_2/100/100",
                                    verified = true,
                                    isFollowing = false,
                                    mutualCount = 5
                                ),
                                User(
                                    id = 3,
                                    username = "michael",
                                    displayName = "Michael Williams",
                                    email = null,
                                    avatarUrl = "https://picsum.photos/seed/user_3/100/100",
                                    verified = false,
                                    isFollowing = false,
                                    mutualCount = 2
                                )
                            )
                            feedItems.add(FeedItem.Suggestions(dummySuggestions))
                        }

                        for (post in mappedPosts) {
                            feedItems.add(FeedItem.PostItem(post))
                        }

                        return@withContext Result.success(feedItems)
                    } else {
                        return@withContext Result.failure(Exception("DummyJSON request failed: HTTP ${response.code()}"))
                    }
                } catch (e: Exception) {
                    return@withContext Result.failure(e)
                }
            }

            // MB Server hoặc Offline Mock
            val feedResult: Result<List<RawFeedItemDto>> =
                safeApiCall(dispatcher = dispatcher, gson = gson) {
                    feedApi.getFeed(
                        page,
                        pageSize
                    )
                }
            if (feedResult.isSuccess) {
                val rawList = feedResult.getOrThrow()
                val feedItems = mutableListOf<FeedItem>()

                // Ở trang 1, chèn StoryBar lên đầu danh sách
                if (page == 1) {
                    if (cachedStories.isEmpty()) {
                        getStories()
                    }
                    feedItems.add(FeedItem.StoryBar(cachedStories))
                }

                val newPosts = mutableListOf<Post>()

                for (raw in rawList) {
                    when (raw.kind) {
                        "post" -> {
                            val postDto = raw.post
                            if (postDto != null) {
                                val post = postMapper.map(postDto)
                                newPosts.add(post)
                                feedItems.add(FeedItem.PostItem(post))
                            }
                        }

                        "suggestions" -> {
                            val usersList = raw.users?.map { userMapper.map(it) } ?: emptyList()
                            if (usersList.isNotEmpty()) {
                                feedItems.add(FeedItem.Suggestions(usersList))
                            }
                        }

                        else -> {
                            // Unknown item: bỏ qua an toàn
                        }
                    }
                }

                // Đồng bộ toàn bộ bài viết mới tải vào cache tập trung
                updatePostsCache(newPosts)

                Result.success(feedItems)
            } else {
                Result.failure(feedResult.exceptionOrNull() ?: Exception("Không thể tải bảng tin"))
            }
        }

    override suspend fun getPostDetail(postId: Long): Result<Post> = withContext(dispatcher) {
        val source = settingsDataStore.getDataSource()

        if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
            val cached = _postsMap.value[postId]
            if (cached != null) return@withContext Result.success(cached)
            return@withContext Result.failure(Exception("Post not found in DummyJSON cache"))
        }

        val result: Result<PostDto> =
            safeApiCall(dispatcher = dispatcher, gson = gson) { feedApi.getPostDetail(postId) }
        if (result.isSuccess) {
            val postDto = result.getOrThrow()
            val post = postMapper.map(postDto)
            updatePostsCache(listOf(post))
            Result.success(post)
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Không thể tải chi tiết bài viết"))
        }
    }

    override suspend fun toggleLike(postId: Long): Result<Post> = withContext(dispatcher) {
        val currentGen = validateSessionGeneration()
        val currentPost = _postsMap.value[postId] ?: getPostDetail(postId).getOrNull()
        ?: return@withContext Result.failure(Exception("Bài viết không tồn tại"))

        val targetLiked = !currentPost.isLiked
        val targetCount =
            if (targetLiked) currentPost.likeCount + 1 else (currentPost.likeCount - 1).coerceAtLeast(
                0
            )

        // 1. Cập nhật lạc quan (Optimistic Update) chỉ trên trường Like, không đè trường Bookmark
        _postsMap.update { currentMap ->
            val p = currentMap[postId] ?: currentPost
            val updated = p.copy(isLiked = targetLiked, likeCount = targetCount)
            currentMap + (postId to updated)
        }

        val source = settingsDataStore.getDataSource()
        if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
            return@withContext Result.success(_postsMap.value[postId] ?: currentPost)
        }

        // 2. Gửi request mạng
        val callResult: Result<LikeResponseDto> = if (targetLiked) {
            safeApiCall(dispatcher = dispatcher, gson = gson) { feedApi.likePost(postId) }
        } else {
            safeApiCall(dispatcher = dispatcher, gson = gson) { feedApi.unlikePost(postId) }
        }

        // Kiểm tra generation guard: nếu đã đổi phiên trong khi request đang bay, hủy bỏ
        if (tokenStore != null && tokenStore.getSessionGeneration() != currentGen) {
            return@withContext Result.failure(CancellationException("Session invalidated"))
        }

        if (callResult.isSuccess) {
            val dto = callResult.getOrThrow()
            // Hợp nhất (Merge) kết quả máy chủ độc lập trên trường Like
            _postsMap.update { currentMap ->
                val p = currentMap[postId] ?: return@update currentMap
                val merged = p.copy(isLiked = dto.isLiked, likeCount = dto.likeCount)
                currentMap + (postId to merged)
            }
            Result.success(_postsMap.value[postId] ?: currentPost)
        } else {
            // 3. Hoàn tác (Rollback) chỉ trên trường Like, giữ nguyên trạng thái Bookmark mới nhất
            _postsMap.update { currentMap ->
                val p = currentMap[postId] ?: return@update currentMap
                val reverted =
                    p.copy(isLiked = currentPost.isLiked, likeCount = currentPost.likeCount)
                currentMap + (postId to reverted)
            }
            Result.failure(
                callResult.exceptionOrNull() ?: Exception("Không thể cập nhật lượt thích")
            )
        }
    }

    override suspend fun toggleBookmark(postId: Long): Result<Post> = withContext(dispatcher) {
        val currentGen = validateSessionGeneration()
        val currentPost = _postsMap.value[postId] ?: getPostDetail(postId).getOrNull()
        ?: return@withContext Result.failure(Exception("Bài viết không tồn tại"))

        val targetBookmarked = !currentPost.isBookmarked

        // 1. Cập nhật lạc quan chỉ trên trường Bookmark
        _postsMap.update { currentMap ->
            val p = currentMap[postId] ?: currentPost
            val updated = p.copy(isBookmarked = targetBookmarked)
            currentMap + (postId to updated)
        }

        val source = settingsDataStore.getDataSource()
        if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
            return@withContext Result.success(_postsMap.value[postId] ?: currentPost)
        }

        // 2. Gửi request mạng
        val callResult: Result<BookmarkResponseDto> = if (targetBookmarked) {
            safeApiCall(dispatcher = dispatcher, gson = gson) { feedApi.bookmarkPost(postId) }
        } else {
            safeApiCall(dispatcher = dispatcher, gson = gson) { feedApi.unbookmarkPost(postId) }
        }

        if (tokenStore != null && tokenStore.getSessionGeneration() != currentGen) {
            return@withContext Result.failure(CancellationException("Session invalidated"))
        }

        if (callResult.isSuccess) {
            val dto = callResult.getOrThrow()
            _postsMap.update { currentMap ->
                val p = currentMap[postId] ?: return@update currentMap
                val merged = p.copy(isBookmarked = dto.isBookmarked)
                currentMap + (postId to merged)
            }
            Result.success(_postsMap.value[postId] ?: currentPost)
        } else {
            // 3. Hoàn tác chỉ trên trường Bookmark
            _postsMap.update { currentMap ->
                val p = currentMap[postId] ?: return@update currentMap
                val reverted = p.copy(isBookmarked = currentPost.isBookmarked)
                currentMap + (postId to reverted)
            }
            Result.failure(callResult.exceptionOrNull() ?: Exception("Không thể lưu bài viết"))
        }
    }

    override suspend fun deletePost(postId: Long): Result<Unit> = withContext(dispatcher) {
        val source = settingsDataStore.getDataSource()
        if (source != SettingsDataStore.SOURCE_DUMMY_JSON) {
            val callResult: Result<Unit> =
                safeApiCall(dispatcher = dispatcher, gson = gson) { feedApi.deletePost(postId) }
            if (callResult.isFailure) {
                return@withContext Result.failure(
                    callResult.exceptionOrNull() ?: Exception("Xóa bài thất bại")
                )
            }
        }

        // Xóa khỏi cache tập trung
        val currentMap = _postsMap.value.toMutableMap()
        currentMap.remove(postId)
        _postsMap.value = currentMap

        Result.success(Unit)
    }

    override suspend fun toggleFollowUser(
        userId: Long,
        currentFollowing: Boolean
    ): Result<Boolean> = withContext(dispatcher) {
        val targetFollowing = !currentFollowing
        val source = settingsDataStore.getDataSource()

        if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
            return@withContext Result.success(targetFollowing)
        }

        val callResult: Result<UserDto> = if (targetFollowing) {
            safeApiCall(dispatcher = dispatcher, gson = gson) { feedApi.followUser(userId) }
        } else {
            safeApiCall(dispatcher = dispatcher, gson = gson) { feedApi.unfollowUser(userId) }
        }

        if (callResult.isSuccess) {
            val userDto = callResult.getOrThrow()
            Result.success(userDto.isFollowing ?: targetFollowing)
        } else {
            Result.failure(callResult.exceptionOrNull() ?: Exception("Không thể cập nhật theo dõi"))
        }
    }

    override suspend fun getComments(
        postId: Long,
        page: Int,
        pageSize: Int,
        sort: String,
        parentId: Long?
    ): Result<List<Comment>> = withContext(dispatcher) {
        val source = settingsDataStore.getDataSource()

        if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
            try {
                val response = dummyJsonApi.getPostComments(postId)
                if (response.isSuccessful && response.body() != null) {
                    val dummyComments = response.body()?.comments ?: emptyList()
                    val mapped = dummyComments.map { commentMapper.mapFromDummyJson(it) }
                    return@withContext Result.success(mapped)
                } else {
                    return@withContext Result.failure(Exception("DummyJSON comments failed: HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }

        val result: Result<List<CommentDto>> = safeApiCall(dispatcher = dispatcher, gson = gson) {
            feedApi.getComments(postId, page, pageSize, sort, parentId)
        }
        if (result.isSuccess) {
            val list = result.getOrThrow()
            Result.success(list.map { commentMapper.map(it) })
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Không thể tải bình luận"))
        }
    }

    override suspend fun postComment(
        postId: Long,
        content: String,
        parentId: Long?
    ): Result<Comment> = withContext(dispatcher) {
        val source = settingsDataStore.getDataSource()

        if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
            val dummyComment = Comment(
                id = System.currentTimeMillis(),
                postId = postId,
                parentId = parentId,
                author = Author(1, "me", "Tôi", null, false),
                content = content,
                likeCount = 0,
                isLiked = false,
                replyCount = 0,
                createdAt = "Vừa xong"
            )
            return@withContext Result.success(dummyComment)
        }

        val result: Result<CommentDto> = safeApiCall(dispatcher = dispatcher, gson = gson) {
            feedApi.postComment(postId, PostCommentRequest(content, parentId))
        }
        if (result.isSuccess) {
            val dto = result.getOrThrow()
            Result.success(commentMapper.map(dto))
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Không thể gửi bình luận"))
        }
    }

    override suspend fun toggleLikeComment(
        commentId: Long,
        currentLiked: Boolean
    ): Result<Boolean> = withContext(dispatcher) {
        val targetLiked = !currentLiked
        val source = settingsDataStore.getDataSource()

        if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
            return@withContext Result.success(targetLiked)
        }

        val result: Result<CommentLikeResponseDto> = if (targetLiked) {
            safeApiCall(dispatcher = dispatcher, gson = gson) { feedApi.likeComment(commentId) }
        } else {
            safeApiCall(dispatcher = dispatcher, gson = gson) { feedApi.unlikeComment(commentId) }
        }
        if (result.isSuccess) {
            val dto = result.getOrThrow()
            Result.success(dto.isLiked)
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Không thể thích bình luận"))
        }
    }

    override fun clearCache() {
        _postsMap.value = emptyMap()
        cachedStories = emptyList()
    }

    private fun updatePostsCache(posts: List<Post>) {
        if (posts.isEmpty()) return
        val current = _postsMap.value.toMutableMap()
        for (p in posts) {
            current[p.id] = p
        }
        _postsMap.value = current
    }
}