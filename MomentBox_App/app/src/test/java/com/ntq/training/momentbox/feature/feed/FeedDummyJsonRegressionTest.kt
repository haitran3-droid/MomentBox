package com.ntq.training.momentbox.feature.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.ntq.training.momentbox.core.media.VoicePlaybackState
import com.ntq.training.momentbox.core.realtime.SocketManager
import com.ntq.training.momentbox.data.local.db.NotificationDao
import com.ntq.training.momentbox.data.local.db.NotificationEntity
import com.ntq.training.momentbox.data.local.prefs.PersistedTokens
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.local.prefs.SettingsStorage
import com.ntq.training.momentbox.data.local.prefs.TokenPersistence
import com.ntq.training.momentbox.data.local.prefs.TokenStorage
import com.ntq.training.momentbox.data.local.prefs.TokenStore
import com.ntq.training.momentbox.data.remote.api.DummyJsonApi
import com.ntq.training.momentbox.data.remote.api.FeedApi
import com.ntq.training.momentbox.data.remote.dto.DummyJsonPost
import com.ntq.training.momentbox.data.remote.dto.DummyJsonReactions
import com.ntq.training.momentbox.data.repository.FeedRepository
import com.ntq.training.momentbox.data.repository.FeedRepositoryImpl
import com.ntq.training.momentbox.domain.mapper.PostMapper
import com.ntq.training.momentbox.domain.model.Author
import com.ntq.training.momentbox.domain.model.Comment
import com.ntq.training.momentbox.domain.model.FeedItem
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.domain.model.Story
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class FeedDummyJsonRegressionTest {

    @Test
    fun postMapperDummyJsonDoesNotMapViewsToCommentCount() {
        val mapper = PostMapper()
        val dto = DummyJsonPost(
            id = 42L,
            title = "Hello MomentBox",
            body = "Testing DummyJSON mapping",
            tags = listOf("travel", "photo"),
            reactions = DummyJsonReactions(likes = 192, dislikes = 5),
            views = 4501,
            userId = 187L
        )

        val mapped = mapper.mapFromDummyJson(dto)

        assertEquals(0, mapped.commentCount)
        assertEquals(192, mapped.likeCount)
        assertEquals("Tác giả #187", mapped.author.displayName)
        assertFalse(mapped.isLiked)
        assertFalse(mapped.isBookmarked)
    }

    @Test
    fun dummyJsonPullToRefreshPreservesLocalLikeDeltaAndBookmarkOverlay() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val serverLikes = AtomicInteger(192)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val json = """
                    {
                        "posts": [
                            {
                                "id": 10,
                                "title": "Sunset",
                                "body": "Golden hour",
                                "tags": ["sunset"],
                                "reactions": {"likes": ${serverLikes.get()}, "dislikes": 1},
                                "views": 999,
                                "userId": 187
                            }
                        ],
                        "total": 1,
                        "skip": 0,
                        "limit": 10
                    }
                """.trimIndent()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val settings = InMemorySettingsStorage(SettingsDataStore.SOURCE_DUMMY_JSON)
        val repo = FeedRepositoryImpl(
            feedApi = retrofit.create(FeedApi::class.java),
            dummyJsonApi = retrofit.create(DummyJsonApi::class.java),
            settingsDataStore = settings,
            tokenStore = TokenStore(InMemoryTokenPersistence()),
            dispatcher = dispatcher
        )

        // Initial load
        val initialPosts = repo.getFeed(page = 1, pageSize = 10)
            .getOrThrow()
            .filterIsInstance<FeedItem.PostItem>()
        assertEquals(1, initialPosts.size)
        assertEquals(192, initialPosts.single().post.likeCount)
        assertEquals(0, initialPosts.single().post.commentCount)
        assertFalse(initialPosts.single().post.isLiked)
        assertFalse(initialPosts.single().post.isBookmarked)

        // Like and bookmark locally in DummyJSON mode
        val liked = repo.toggleLike(10L).getOrThrow()
        assertTrue(liked.isLiked)
        assertEquals(193, liked.likeCount)

        val bookmarked = repo.toggleBookmark(10L).getOrThrow()
        assertTrue(bookmarked.isBookmarked)

        // Pull-to-refresh with same server count preserves local overlay
        val refreshedSame = repo.getFeed(page = 1, pageSize = 10)
            .getOrThrow()
            .filterIsInstance<FeedItem.PostItem>()
            .single()
            .post
        assertTrue(refreshedSame.isLiked)
        assertEquals(193, refreshedSame.likeCount)
        assertTrue(refreshedSame.isBookmarked)

        // Pull-to-refresh with updated server count preserves +1 like delta and bookmark
        serverLikes.set(200)
        val refreshedUpdated = repo.getFeed(page = 1, pageSize = 10)
            .getOrThrow()
            .filterIsInstance<FeedItem.PostItem>()
            .single()
            .post
        assertTrue(refreshedUpdated.isLiked)
        assertEquals(201, refreshedUpdated.likeCount)
        assertTrue(refreshedUpdated.isBookmarked)

        // Unliking resets delta back to 0 on next refresh
        repo.toggleLike(10L).getOrThrow()
        val refreshedAfterUnlike = repo.getFeed(page = 1, pageSize = 10)
            .getOrThrow()
            .filterIsInstance<FeedItem.PostItem>()
            .single()
            .post
        assertFalse(refreshedAfterUnlike.isLiked)
        assertEquals(200, refreshedAfterUnlike.likeCount)
        assertTrue(refreshedAfterUnlike.isBookmarked)
    }

    @Test
    fun paginationFailureBlocksAutoLoadNextPageUntilExplicitRetryAndOnClearedKeepsSocketAlive() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val store = ViewModelStore()
            try {
                val repo = PaginatingFakeFeedRepository()
                val socketManager = RecordingSocketManager(
                    tokenStore = TokenStore(InMemoryTokenPersistence()),
                    settingsStorage = InMemorySettingsStorage()
                )
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return FeedViewModel(
                            feedRepository = repo,
                            notificationDao = FakeNotificationDao(),
                            socketManager = socketManager,
                            messageText = { "error:$it" }
                        ) as T
                    }
                }
                val vm = ViewModelProvider(store, factory)[FeedViewModel::class.java]
                runCurrent()

                assertEquals(listOf(1), repo.requestedPages)
                assertNull(vm.footerError.value)

                // Trigger page 2 failure
                repo.failPageTwo = true
                vm.loadNextPage()
                runCurrent()

                assertEquals(listOf(1, 2), repo.requestedPages)
                assertNotNull(vm.footerError.value)

                // Subsequent scroll-triggered loadNextPage() calls must be ignored while footerError != null
                vm.loadNextPage()
                vm.loadNextPage()
                runCurrent()
                assertEquals(listOf(1, 2), repo.requestedPages)

                // Explicit Retry clears error and requests page 2 again
                repo.failPageTwo = false
                vm.retryFeed()
                runCurrent()

                assertEquals(listOf(1, 2, 2), repo.requestedPages)
                assertNull(vm.footerError.value)

                // Clearing FeedViewModel must NOT disconnect shared SocketManager (H16)
                store.clear()
                assertEquals(1, socketManager.connectCalls)
                assertEquals(0, socketManager.disconnectCalls)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun voicePlaybackStateSpeedForPostReflectsPrePlaySpeedSelection() {
        val state = VoicePlaybackState(
            postId = null,
            speed = 1.0f,
            speedsByPostId = mapOf(5L to 1.5f, 8L to 2.0f)
        )

        assertEquals(1.5f, state.speedForPost(5L), 0.001f)
        assertEquals(2.0f, state.speedForPost(8L), 0.001f)
        assertEquals(1.0f, state.speedForPost(99L), 0.001f)
    }

    private class PaginatingFakeFeedRepository : FeedRepository {
        private val author = Author(1L, "user", "User", null)
        private val post1 = Post(1L, "TEXT", author, "Page 1", createdAt = "")
        private val post2 = Post(2L, "TEXT", author, "Page 2", createdAt = "")

        val requestedPages = mutableListOf<Int>()
        var failPageTwo = false

        override val postsMapFlow = MutableStateFlow(mapOf(1L to post1, 2L to post2))

        override fun observePost(postId: Long): Flow<Post?> = postsMapFlow.map { it[postId] }
        override suspend fun getStories(): Result<List<Story>> = Result.success(emptyList())

        override suspend fun getFeed(page: Int, pageSize: Int): Result<List<FeedItem>> {
            requestedPages += page
            return when (page) {
                1 -> Result.success(listOf(FeedItem.PostItem(post1)))
                2 -> if (failPageTwo) {
                    Result.failure(IOException("Network unavailable"))
                } else {
                    Result.success(listOf(FeedItem.PostItem(post2)))
                }
                else -> Result.success(emptyList())
            }
        }

        override suspend fun getPostDetail(postId: Long): Result<Post> =
            Result.success(postsMapFlow.value.getValue(postId))

        override suspend fun toggleLike(postId: Long): Result<Post> =
            Result.success(postsMapFlow.value.getValue(postId))

        override suspend fun toggleBookmark(postId: Long): Result<Post> =
            Result.success(postsMapFlow.value.getValue(postId))

        override suspend fun deletePost(postId: Long): Result<Unit> = Result.success(Unit)
        override suspend fun toggleFollowUser(userId: Long, currentFollowing: Boolean) =
            Result.success(!currentFollowing)

        override suspend fun getComments(
            postId: Long,
            page: Int,
            pageSize: Int,
            sort: String,
            parentId: Long?
        ): Result<List<Comment>> = Result.success(emptyList())

        override suspend fun postComment(
            postId: Long,
            content: String,
            parentId: Long?,
            clientId: String?
        ): Result<Comment> = Result.failure(UnsupportedOperationException())

        override suspend fun toggleLikeComment(commentId: Long, currentLiked: Boolean) =
            Result.success(!currentLiked)

        override fun clearCache() = Unit
    }

    private class RecordingSocketManager(
        tokenStore: TokenStorage,
        settingsStorage: SettingsStorage
    ) : SocketManager(
        tokenStore = tokenStore,
        settingsStorage = settingsStorage,
        baseUrlProvider = { "http://localhost:3000/" }
    ) {
        var connectCalls = 0
        var disconnectCalls = 0

        override val feedNewCountFlow: StateFlow<Int> = MutableStateFlow(0)
        override val unreadNotificationCountFlow: StateFlow<Int> = MutableStateFlow(0)
        override val isConnectedFlow: StateFlow<Boolean> = MutableStateFlow(false)

        override fun connect() {
            connectCalls++
        }

        override fun disconnect() {
            disconnectCalls++
        }

        override fun clearFeedNewCount() = Unit
    }

    private class FakeNotificationDao : NotificationDao {
        override fun unreadCountForScopeFlow(ownerId: String, source: String): Flow<Int> = emptyFlow()
        override suspend fun getNotificationsForScope(ownerId: String, source: String) =
            emptyList<NotificationEntity>()

        override suspend fun deleteForScope(id: Long, ownerId: String, source: String): Int = 0
        override fun unreadCountFlow(): Flow<Int> = emptyFlow()
        override suspend fun getUnreadCount(): Int = 0
        override fun getAllNotificationsFlow(): Flow<List<NotificationEntity>> = emptyFlow()
        override suspend fun getAllNotifications(): List<NotificationEntity> = emptyList()
        override suspend fun insert(notification: NotificationEntity): Long = 0L
        override suspend fun markAsRead(id: Long): Int = 0
        override suspend fun markAllAsRead(): Int = 0
        override suspend fun deleteOlderThan(cutoffMillis: Long): Int = 0
        override suspend fun deleteAll(): Int = 0
    }

    private class InMemoryTokenPersistence : TokenPersistence {
        private var stored = PersistedTokens(null, null, true, 0L)
        override suspend fun read(): PersistedTokens = stored
        override suspend fun write(tokens: PersistedTokens) {
            stored = tokens
        }
    }

    private class InMemorySettingsStorage(
        initialSource: String = SettingsDataStore.SOURCE_MB_SERVER
    ) : SettingsStorage {
        private val onboarding = MutableStateFlow(false)
        private val source = MutableStateFlow(initialSource)
        private val debugDelay = MutableStateFlow(0)
        private val force401 = MutableStateFlow(false)
        private val configCache = MutableStateFlow<String?>(null)
        private val savedUser = MutableStateFlow<String?>(null)

        override val onboardingDoneFlow: Flow<Boolean> = onboarding
        override val dataSourceFlow: Flow<String> = source
        override val debugDelayMsFlow: Flow<Int> = debugDelay
        override val debugForce401Flow: Flow<Boolean> = force401
        override val configCacheFlow: Flow<String?> = configCache
        override val savedUserJsonFlow: Flow<String?> = savedUser

        override suspend fun setOnboardingDone(done: Boolean) {
            onboarding.value = done
        }

        override suspend fun isOnboardingDone(): Boolean = onboarding.value

        override suspend fun setDataSource(source: String) {
            this.source.value = source
        }

        override suspend fun getDataSource(): String = source.value

        override suspend fun setConfigCache(json: String) {
            configCache.value = json
        }

        override suspend fun getConfigCache(): String? = configCache.value

        override suspend fun setDebugDelay(delayMs: Int) {
            debugDelay.value = delayMs
        }

        override suspend fun setDebugForce401(force: Boolean) {
            force401.value = force
        }

        override suspend fun isDebugForce401(): Boolean = force401.value

        override suspend fun setSavedUserJson(json: String?) {
            savedUser.value = json
        }

        override suspend fun getSavedUserJson(): String? = savedUser.value
    }
}
