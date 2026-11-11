package com.ntq.training.momentbox.data.repository

import com.google.gson.Gson
import com.ntq.training.momentbox.core.network.ApiResponse
import com.ntq.training.momentbox.core.network.SessionRequest
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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map as flowMap
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response

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
    private val gson: Gson = Gson(),
) : FeedRepository {
    private val _createdPosts = MutableStateFlow<List<Post>>(emptyList())
    override val createdPostsFlow: StateFlow<List<Post>> = _createdPosts.asStateFlow()

    override suspend fun createPost(
        fields: Map<String, RequestBody>,
        media: List<MultipartBody.Part>,
        voice: MultipartBody.Part?,
    ): Result<Post> = scoped { scope ->
        if (scope.source != SettingsDataStore.SOURCE_MB_SERVER)
            return@scoped Result.failure(
                UnsupportedOperationException("Posting requires MomentBox server")
            )
        val response = feedApi.createPost(fields, media, voice, SessionRequest(scope.generation))
        val body = response.body()
        if (!response.isSuccessful || body?.success != true || body.data == null)
            return@scoped Result.failure(IOException("HTTP ${response.code()}"))
        val post = postMapper.map(body.data)
        commit(scope) {
            _postsMap.value = _postsMap.value + (post.id to post)
            _createdPosts.value = (listOf(post) + _createdPosts.value).distinctBy { it.id }
            cacheVersion++
        }
        Result.success(post)
    }

    /** Cache tập trung Map<postId, Post> làm nguồn chân lý duy nhất (Single Source of Truth) */
    private val _postsMap = MutableStateFlow<Map<Long, Post>>(emptyMap())
    override val postsMapFlow: StateFlow<Map<Long, Post>> = _postsMap.asStateFlow()
    private val _followingMap = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    override val followingMapFlow: StateFlow<Map<Long, Boolean>> = _followingMap.asStateFlow()

    /** Danh sách story được lưu tạm để không phải gọi lại khi phân trang feed */
    private var cachedStories: List<Story> = emptyList()

    /** Thế hệ phiên làm việc gần nhất để phát hiện phiên bị thay đổi / đăng xuất */
    private var lastRecordedGeneration: Long = tokenStore?.getSessionGeneration() ?: 0L
    private val cacheLock = Any()
    private var cacheSource: String? = null
    private var cacheEpoch = 0L
    private var feedEpoch = 0L
    private var cacheVersion = 0L
    private val fieldVersions = mutableMapOf<Pair<Long, String>, Long>()
    private val activeMutations = mutableSetOf<Pair<Long, String>>()
    private val deletedVersions = mutableMapOf<Long, Long>()
    private val mutationLocks = ConcurrentHashMap<String, Mutex>()

    private data class Scope(
        val generation: Long,
        val source: String,
        val epoch: Long,
        val feedEpoch: Long?,
        val cacheVersion: Long,
    )

    private class InvalidScope : Exception()

    init {
        // Logout/login đổi generation, callback xóa dữ liệu quan sát được ngay khi token đổi.
        tokenStore?.addSnapshotListener { snapshot ->
            synchronized(cacheLock) {
                if (snapshot.generation != lastRecordedGeneration) {
                    clearLocked()
                    lastRecordedGeneration = snapshot.generation
                    cacheSource = null
                }
            }
        }
    }

    private fun clearLocked() {
        cacheEpoch++
        feedEpoch++
        _postsMap.value = emptyMap()
        _createdPosts.value = emptyList()
        _followingMap.value = emptyMap()
        cachedStories = emptyList()
        cacheVersion++
        fieldVersions.clear()
        activeMutations.clear()
        deletedVersions.clear()
    }

    private suspend fun beginScope(page: Int?): Scope {
        val generation = tokenStore?.snapshot()?.generation ?: 0L
        val source = settingsDataStore.getDataSource()
        return synchronized(cacheLock) {
            if (tokenStore != null && tokenStore.getSessionGeneration() != generation)
                throw InvalidScope()
            if (
                generation != lastRecordedGeneration ||
                    (cacheSource != null && cacheSource != source)
            ) {
                clearLocked()
                lastRecordedGeneration = generation
            }
            cacheSource = source
            if (page == 1) feedEpoch++
            Scope(generation, source, cacheEpoch, page?.let { feedEpoch }, cacheVersion)
        }
    }

    /** Kiểm tra và ghi cache dưới cùng mutex với đổi token, tránh race giữa kiểm tra và ghi. */
    private suspend fun <T> commit(scope: Scope, action: () -> T): T {
        if (settingsDataStore.getDataSource() != scope.source) throw InvalidScope()
        var output: Any? = null
        val write = {
            synchronized(cacheLock) {
                if (
                    scope.epoch != cacheEpoch ||
                        cacheSource != scope.source ||
                        (scope.feedEpoch != null && scope.feedEpoch != feedEpoch)
                )
                    throw InvalidScope()
                output = action()
            }
        }
        if (tokenStore != null) {
            if (!tokenStore.runIfGenerationMatches(scope.generation, write)) throw InvalidScope()
        } else write()
        @Suppress("UNCHECKED_CAST")
        return output as T
    }

    private suspend fun <T> scoped(
        page: Int? = null,
        block: suspend (Scope) -> Result<T>,
    ): Result<T> =
        withContext(dispatcher) {
            try {
                val scope = beginScope(page)
                val result = block(scope)
                commit(scope) { Unit }
                result
            } catch (_: InvalidScope) {
                Result.failure(CancellationException("Session invalidated"))
            }
        }

    private fun mutationLock(scope: Scope, postId: Long, field: String): Mutex =
        mutationLocks.getOrPut("${scope.generation}:${scope.source}:$postId:$field") { Mutex() }

    private fun mutationStarted(postId: Long, field: String) {
        val key = postId to field
        activeMutations.add(key)
        fieldVersions[key] = ++cacheVersion
    }

    private suspend fun mutationFinished(scope: Scope, postId: Long, field: String) {
        withContext(NonCancellable) {
            try {
                commit(scope) {
                    val key = postId to field
                    activeMutations.remove(key)
                    fieldVersions[key] = ++cacheVersion
                }
            } catch (_: InvalidScope) {
                /* Phiên cũ đã xóa toàn bộ trạng thái của nó. */
            }
        }
    }

    override fun observePost(postId: Long): Flow<Post?> {
        return _postsMap.flowMap { it[postId] }.distinctUntilChanged()
    }

    override suspend fun getStories(): Result<List<Story>> = scoped { scope ->
        val source = scope.source

        if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
            // DummyJSON không có endpoint story, sinh danh sách story giả lập mẫu
            val dummyStories =
                listOf(
                    storyMapper.createMyStory(currentStoryAuthor()),
                    Story(
                        user =
                            Author(
                                2,
                                "emilys",
                                "Emily",
                                "https://picsum.photos/seed/user_2/100/100",
                                false,
                            ),
                        hasUnseen = true,
                    ),
                    Story(
                        user =
                            Author(
                                3,
                                "michael",
                                "Michael",
                                "https://picsum.photos/seed/user_3/100/100",
                                true,
                            ),
                        hasUnseen = true,
                    ),
                    Story(
                        user =
                            Author(
                                4,
                                "sophia",
                                "Sophia",
                                "https://picsum.photos/seed/user_4/100/100",
                                false,
                            ),
                        hasUnseen = false,
                    ),
                )
            commit(scope) { cachedStories = dummyStories }
            return@scoped Result.success(dummyStories)
        }

        val result: Result<List<StoryDto>> =
            safeApiCall(dispatcher = dispatcher, gson = gson) {
                feedApi.getStories(SessionRequest(scope.generation))
            }
        if (result.isSuccess) {
            val dtoList = result.getOrThrow()
            val myStory = storyMapper.createMyStory(currentStoryAuthor())
            val mapped = listOf(myStory) + (dtoList.map { storyMapper.map(it) })
            commit(scope) { cachedStories = mapped }
            Result.success(mapped)
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Lỗi tải story"))
        }
    }

    override suspend fun getFeed(page: Int, pageSize: Int): Result<List<FeedItem>> =
        scoped(page) { scope ->
            val source = scope.source

            if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
                val skip = (page - 1) * pageSize
                try {
                    val response =
                        dummyJsonApi.getPosts(
                            limit = pageSize,
                            skip = skip,
                            session = SessionRequest(scope.generation),
                        )
                    if (response.isSuccessful && response.body() != null) {
                        val dummyPosts = response.body()?.posts ?: emptyList()
                        val mappedPosts = dummyPosts.map { postMapper.mapFromDummyJson(it) }

                        // Cập nhật vào cache tập trung
                        updatePostsCache(scope, mappedPosts)

                        val feedItems = mutableListOf<FeedItem>()

                        // Ở trang đầu tiên, gắn thêm StoryBar và Gợi ý theo dõi giả lập
                        if (page == 1) {
                            if (commit(scope) { cachedStories.isEmpty() }) {
                                getStories()
                            }
                            feedItems.add(FeedItem.StoryBar(commit(scope) { cachedStories }))

                            val dummySuggestions =
                                listOf(
                                    User(
                                        id = 2,
                                        username = "emilys",
                                        displayName = "Emily Johnson",
                                        email = null,
                                        avatarUrl = "https://picsum.photos/seed/user_2/100/100",
                                        verified = true,
                                        isFollowing = false,
                                        mutualCount = 5,
                                    ),
                                    User(
                                        id = 3,
                                        username = "michael",
                                        displayName = "Michael Williams",
                                        email = null,
                                        avatarUrl = "https://picsum.photos/seed/user_3/100/100",
                                        verified = false,
                                        isFollowing = false,
                                        mutualCount = 2,
                                    ),
                                )
                            feedItems.add(
                                FeedItem.Suggestions(updateFollowingCache(scope, dummySuggestions))
                            )
                        }

                        for (post in mappedPosts) {
                            feedItems.add(FeedItem.PostItem(post))
                        }

                        return@scoped Result.success(feedItems)
                    } else {
                        return@scoped Result.failure(
                            Exception("DummyJSON request failed: HTTP ${response.code()}")
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    return@scoped Result.failure(e)
                }
            }

            // MB Server hoặc Offline Mock
            val feedResult: Result<List<RawFeedItemDto>> =
                safeApiCall(dispatcher = dispatcher, gson = gson) {
                    feedApi.getFeed(page, pageSize, SessionRequest(scope.generation))
                }
            if (feedResult.isSuccess) {
                val rawList = feedResult.getOrThrow()
                val feedItems = mutableListOf<FeedItem>()

                // Ở trang 1, chèn StoryBar lên đầu danh sách
                if (page == 1) {
                    if (commit(scope) { cachedStories.isEmpty() }) {
                        getStories()
                    }
                    feedItems.add(FeedItem.StoryBar(commit(scope) { cachedStories }))
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
                                feedItems.add(
                                    FeedItem.Suggestions(updateFollowingCache(scope, usersList))
                                )
                            }
                        }

                        else -> {
                            // Unknown item: bỏ qua an toàn
                        }
                    }
                }

                // Đồng bộ toàn bộ bài viết mới tải vào cache tập trung
                updatePostsCache(scope, newPosts)

                Result.success(feedItems)
            } else {
                Result.failure(feedResult.exceptionOrNull() ?: Exception("Không thể tải bảng tin"))
            }
        }

    override suspend fun getPostDetail(postId: Long): Result<Post> = scoped { scope ->
        val source = scope.source

        if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
            val cached = commit(scope) { _postsMap.value[postId] }
            if (cached != null) return@scoped Result.success(cached)
            return@scoped Result.failure(Exception("Post not found in DummyJSON cache"))
        }

        val result: Result<PostDto> =
            safeApiCall(dispatcher = dispatcher, gson = gson) {
                feedApi.getPostDetail(postId, SessionRequest(scope.generation))
            }
        if (result.isSuccess) {
            val postDto = result.getOrThrow()
            val post = postMapper.map(postDto)
            updatePostsCache(scope, listOf(post))
            Result.success(commit(scope) { _postsMap.value[post.id] ?: throw InvalidScope() })
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Không thể tải chi tiết bài viết"))
        }
    }

    override suspend fun toggleLike(postId: Long): Result<Post> = scoped { scope ->
        mutationLock(scope, postId, "like").withLock {
            val currentGen = scope.generation
            val currentPost =
                commit(scope) { _postsMap.value[postId] }
                    ?: getPostDetail(postId).getOrNull()
                    ?: return@scoped Result.failure(Exception("Bài viết không tồn tại"))

            val targetLiked = !currentPost.isLiked
            val targetCount =
                if (targetLiked) currentPost.likeCount + 1
                else (currentPost.likeCount - 1).coerceAtLeast(0)

            // 1. Cập nhật lạc quan (Optimistic Update) chỉ trên trường Like, không đè trường
            // Bookmark
            commit(scope) {
                mutationStarted(postId, "like")
                _postsMap.update { currentMap ->
                    val p = currentMap[postId] ?: currentPost
                    val updated = p.copy(isLiked = targetLiked, likeCount = targetCount)
                    currentMap + (postId to updated)
                }
            }

            try {
                val source = scope.source
                if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
                    return@scoped Result.success(
                        commit(scope) { _postsMap.value[postId] } ?: currentPost
                    )
                }

                // 2. Gửi request mạng
                val callResult: Result<LikeResponseDto> =
                    if (targetLiked) {
                        safeApiCall(dispatcher = dispatcher, gson = gson) {
                            feedApi.likePost(postId, SessionRequest(scope.generation))
                        }
                    } else {
                        safeApiCall(dispatcher = dispatcher, gson = gson) {
                            feedApi.unlikePost(postId, SessionRequest(scope.generation))
                        }
                    }

                // Kiểm tra generation guard: nếu đã đổi phiên trong khi request đang bay, hủy bỏ
                if (tokenStore != null && tokenStore.getSessionGeneration() != currentGen) {
                    return@scoped Result.failure(CancellationException("Session invalidated"))
                }

                if (callResult.isSuccess) {
                    val dto = callResult.getOrThrow()
                    // Hợp nhất (Merge) kết quả máy chủ độc lập trên trường Like
                    commit(scope) {
                        _postsMap.update { currentMap ->
                            val p = currentMap[postId] ?: return@update currentMap
                            val merged = p.copy(isLiked = dto.isLiked, likeCount = dto.likeCount)
                            currentMap + (postId to merged)
                        }
                    }
                    Result.success(commit(scope) { _postsMap.value[postId] } ?: currentPost)
                } else {
                    // 3. Hoàn tác (Rollback) chỉ trên trường Like, giữ nguyên trạng thái Bookmark
                    // mới nhất
                    commit(scope) {
                        _postsMap.update { currentMap ->
                            val p = currentMap[postId] ?: return@update currentMap
                            val reverted =
                                p.copy(
                                    isLiked = currentPost.isLiked,
                                    likeCount = currentPost.likeCount,
                                )
                            currentMap + (postId to reverted)
                        }
                    }
                    Result.failure(
                        callResult.exceptionOrNull() ?: Exception("Không thể cập nhật lượt thích")
                    )
                }
            } finally {
                mutationFinished(scope, postId, "like")
            }
        }
    }

    override suspend fun toggleBookmark(postId: Long): Result<Post> = scoped { scope ->
        mutationLock(scope, postId, "bookmark").withLock {
            val currentGen = scope.generation
            val currentPost =
                commit(scope) { _postsMap.value[postId] }
                    ?: getPostDetail(postId).getOrNull()
                    ?: return@scoped Result.failure(Exception("Bài viết không tồn tại"))

            val targetBookmarked = !currentPost.isBookmarked

            // 1. Cập nhật lạc quan chỉ trên trường Bookmark
            commit(scope) {
                mutationStarted(postId, "bookmark")
                _postsMap.update { currentMap ->
                    val p = currentMap[postId] ?: currentPost
                    val updated = p.copy(isBookmarked = targetBookmarked)
                    currentMap + (postId to updated)
                }
            }

            try {
                val source = scope.source
                if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
                    return@scoped Result.success(
                        commit(scope) { _postsMap.value[postId] } ?: currentPost
                    )
                }

                // 2. Gửi request mạng
                val callResult: Result<BookmarkResponseDto> =
                    if (targetBookmarked) {
                        safeApiCall(dispatcher = dispatcher, gson = gson) {
                            feedApi.bookmarkPost(postId, SessionRequest(scope.generation))
                        }
                    } else {
                        safeApiCall(dispatcher = dispatcher, gson = gson) {
                            feedApi.unbookmarkPost(postId, SessionRequest(scope.generation))
                        }
                    }

                if (tokenStore != null && tokenStore.getSessionGeneration() != currentGen) {
                    return@scoped Result.failure(CancellationException("Session invalidated"))
                }

                if (callResult.isSuccess) {
                    val dto = callResult.getOrThrow()
                    commit(scope) {
                        _postsMap.update { currentMap ->
                            val p = currentMap[postId] ?: return@update currentMap
                            val merged = p.copy(isBookmarked = dto.isBookmarked)
                            currentMap + (postId to merged)
                        }
                    }
                    Result.success(commit(scope) { _postsMap.value[postId] } ?: currentPost)
                } else {
                    // 3. Hoàn tác chỉ trên trường Bookmark
                    commit(scope) {
                        _postsMap.update { currentMap ->
                            val p = currentMap[postId] ?: return@update currentMap
                            val reverted = p.copy(isBookmarked = currentPost.isBookmarked)
                            currentMap + (postId to reverted)
                        }
                    }
                    Result.failure(
                        callResult.exceptionOrNull() ?: Exception("Không thể lưu bài viết")
                    )
                }
            } finally {
                mutationFinished(scope, postId, "bookmark")
            }
        }
    }

    override suspend fun deletePost(postId: Long): Result<Unit> = scoped { scope ->
        val source = scope.source
        if (source != SettingsDataStore.SOURCE_DUMMY_JSON) {
            val callResult: Result<Unit> =
                safeApiCall(dispatcher = dispatcher, gson = gson) {
                    val response = feedApi.deletePost(postId, SessionRequest(scope.generation))
                    // DELETE của MB Server trả success/data:null, HTTP 204 cũng không có payload.
                    if (
                        response.isSuccessful &&
                            (response.code() == 204 || response.body()?.success == true)
                    )
                        Response.success(ApiResponse(success = true, data = Unit))
                    else response
                }
            if (callResult.isFailure) {
                return@scoped Result.failure(
                    callResult.exceptionOrNull() ?: Exception("Xóa bài thất bại")
                )
            }
        }

        // Xóa khỏi cache tập trung
        commit(scope) {
            deletedVersions[postId] = ++cacheVersion
            _createdPosts.value = _createdPosts.value.filterNot { it.id == postId }
            feedEpoch++
            _postsMap.update { it - postId }
        }

        Result.success(Unit)
    }

    /**
     * Nạp quan hệ tác giả từ server sau cold start, commit dùng cùng guard/revision với
     * Suggestions.
     */
    override suspend fun loadFollowingState(userId: Long): Result<Boolean> = scoped { scope ->
        if (
            scope.source == SettingsDataStore.SOURCE_DUMMY_JSON ||
                scope.source == SettingsDataStore.SOURCE_OFFLINE ||
                currentStoryAuthor().id == userId
        ) {
            return@scoped Result.success(
                commit(scope) {
                    val known = _followingMap.value[userId] ?: false
                    _followingMap.update { it + (userId to known) }
                    known
                }
            )
        }
        val response: Result<UserDto> =
            safeApiCall(dispatcher = dispatcher, gson = gson) {
                feedApi.getUser(userId, SessionRequest(scope.generation))
            }
        response.map { dto ->
            updateFollowingCache(scope, listOf(userMapper.map(dto))).single().isFollowing
        }
    }

    /**
     * S04 và S05 dùng chung trạng thái server đã xác nhận theo userId. Mutex theo user giữ hai màn
     * hình khỏi gửi cùng thao tác, chốt session/source vẫn được kiểm tra trước publish để phản hồi
     * tài khoản cũ không lọt vào phiên mới.
     */
    override suspend fun toggleFollowUser(
        userId: Long,
        currentFollowing: Boolean,
    ): Result<Boolean> = scoped { scope ->
        // Bảo vệ cả caller ngoài UI: server không cho phép theo dõi chính tài khoản đang đăng nhập.
        if (currentStoryAuthor().id == userId) {
            return@scoped Result.failure(IllegalArgumentException("Cannot follow your own account"))
        }
        mutationLock(scope, userId, "follow").withLock {
            val confirmed = commit(scope) { _followingMap.value[userId] }
            // Caller có thể đang hiển thị snapshot trước khi màn hình kia hoàn tất POST.
            // Trả state mới nhất thay vì biến tap cũ thành một lệnh đảo chiều ngoài ý muốn.
            if (confirmed != null && confirmed != currentFollowing) {
                return@withLock Result.success(confirmed)
            }
            val targetFollowing = !currentFollowing
            commit(scope) { mutationStarted(userId, "follow") }
            try {
                val result: Result<Boolean> =
                    if (scope.source == SettingsDataStore.SOURCE_DUMMY_JSON) {
                        // DummyJSON không có API follow, chỉ thay đổi trong phiên này.
                        Result.success(targetFollowing)
                    } else {
                        val response: Result<UserDto> =
                            if (targetFollowing) {
                                safeApiCall(dispatcher = dispatcher, gson = gson) {
                                    feedApi.followUser(userId, SessionRequest(scope.generation))
                                }
                            } else {
                                safeApiCall(dispatcher = dispatcher, gson = gson) {
                                    feedApi.unfollowUser(userId, SessionRequest(scope.generation))
                                }
                            }
                        response.map { it.isFollowing ?: targetFollowing }
                    }
                if (result.isSuccess) {
                    commit(scope) { _followingMap.update { it + (userId to result.getOrThrow()) } }
                }
                result
            } finally {
                mutationFinished(scope, userId, "follow")
            }
        }
    }

    override suspend fun getComments(
        postId: Long,
        page: Int,
        pageSize: Int,
        sort: String,
        parentId: Long?,
    ): Result<List<Comment>> = scoped { scope ->
        val source = scope.source

        if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
            try {
                val response =
                    dummyJsonApi.getPostComments(postId, SessionRequest(scope.generation))
                if (response.isSuccessful && response.body() != null) {
                    val dummyComments = response.body()?.comments ?: emptyList()
                    val mapped = dummyComments.map { commentMapper.mapFromDummyJson(it) }
                    return@scoped Result.success(mapped)
                } else {
                    return@scoped Result.failure(
                        Exception("DummyJSON comments failed: HTTP ${response.code()}")
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                return@scoped Result.failure(e)
            }
        }

        val result: Result<List<CommentDto>> =
            safeApiCall(dispatcher = dispatcher, gson = gson) {
                feedApi.getComments(
                    postId,
                    page,
                    pageSize,
                    sort,
                    parentId,
                    SessionRequest(scope.generation),
                )
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
        parentId: Long?,
    ): Result<Comment> = scoped { scope ->
        val source = scope.source

        if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
            val dummyComment =
                Comment(
                    id = System.currentTimeMillis(),
                    postId = postId,
                    parentId = parentId,
                    author = Author(1, "me", "Tôi", null, false),
                    content = content,
                    likeCount = 0,
                    isLiked = false,
                    replyCount = 0,
                    createdAt = "Vừa xong",
                )
            return@scoped Result.success(dummyComment)
        }

        val result: Result<CommentDto> =
            safeApiCall(dispatcher = dispatcher, gson = gson) {
                feedApi.postComment(
                    postId,
                    PostCommentRequest(content, parentId),
                    SessionRequest(scope.generation),
                )
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
        currentLiked: Boolean,
    ): Result<Boolean> = scoped { scope ->
        val targetLiked = !currentLiked
        val source = scope.source

        if (source == SettingsDataStore.SOURCE_DUMMY_JSON) {
            return@scoped Result.success(targetLiked)
        }

        val result =
            safeApiCall(
                dispatcher = dispatcher,
                gson = gson,
                // MB Server acknowledges the mutation with success: true and data: null.
                emptySuccess = { CommentLikeResponseDto(isLiked = targetLiked) },
            ) {
                val request = SessionRequest(scope.generation)
                if (targetLiked) feedApi.likeComment(commentId, request)
                else feedApi.unlikeComment(commentId, request)
            }
        if (result.isSuccess) {
            val dto = result.getOrThrow()
            Result.success(dto.isLiked)
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Không thể thích bình luận"))
        }
    }

    private suspend fun currentStoryAuthor(): Author {
        val saved =
            settingsDataStore.getSavedUserJson()?.let { json ->
                try {
                    gson.fromJson(json, User::class.java)
                } catch (_: Exception) {
                    null
                }
            }
        return if (saved == null) Author(0, "me", "", null, false)
        else Author(saved.id, saved.username, saved.displayName, saved.avatarUrl, saved.verified)
    }

    override fun clearCache() {
        synchronized(cacheLock) { clearLocked() }
    }

    /** Cập nhật danh sách bài viết vào cache Map tập trung theo ID bài viết. */
    /**
     * Seed follow từ GET cho cả hai màn hình, nhưng không để snapshot GET cũ ghi đè POST đang chạy
     * hoặc đã hoàn tất sau lúc GET bắt đầu. GET mới hơn được cập nhật từ server, riêng Dummy giữ
     * local state vì không có endpoint follow.
     */
    private suspend fun updateFollowingCache(scope: Scope, users: List<User>): List<User> =
        commit(scope) {
            val confirmed = _followingMap.value.toMutableMap()
            val merged =
                users.map { user ->
                    val key = user.id to "follow"
                    val preserve =
                        key in activeMutations ||
                            (fieldVersions[key] ?: -1L) > scope.cacheVersion ||
                            (scope.source == SettingsDataStore.SOURCE_DUMMY_JSON &&
                                user.id in confirmed)
                    if (preserve) {
                        user.copy(isFollowing = confirmed[user.id] ?: user.isFollowing)
                    } else {
                        confirmed[user.id] = user.isFollowing
                        user
                    }
                }
            _followingMap.value = confirmed
            merged
        }

    private suspend fun updatePostsCache(scope: Scope, posts: List<Post>) {
        commit(scope) {
            if (posts.any { (deletedVersions[it.id] ?: -1L) > scope.cacheVersion })
                throw InvalidScope()
            _postsMap.update { current ->
                val merged =
                    posts.associate { incoming ->
                        val existing = current[incoming.id]
                        val like = incoming.id to "like"
                        val bookmark = incoming.id to "bookmark"
                        val preserveLike =
                            like in activeMutations ||
                                (fieldVersions[like] ?: -1L) > scope.cacheVersion
                        val preserveBookmark =
                            bookmark in activeMutations ||
                                (fieldVersions[bookmark] ?: -1L) > scope.cacheVersion
                        var post = incoming
                        if (existing != null && preserveLike)
                            post =
                                post.copy(
                                    isLiked = existing.isLiked,
                                    likeCount = existing.likeCount,
                                )
                        if (existing != null && preserveBookmark)
                            post = post.copy(isBookmarked = existing.isBookmarked)
                        incoming.id to post
                    }
                current + merged
            }
        }
    }
}
