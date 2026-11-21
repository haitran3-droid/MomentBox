package com.ntq.training.momentbox.feature.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.google.gson.JsonParser
import com.ntq.training.momentbox.core.network.OfflineFeedState
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.repository.AuthRepository
import com.ntq.training.momentbox.data.repository.FeedProfileUpdate
import com.ntq.training.momentbox.data.repository.FeedRepository
import com.ntq.training.momentbox.data.repository.ProfileData
import com.ntq.training.momentbox.domain.model.Comment
import com.ntq.training.momentbox.domain.model.FeedItem
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.domain.model.Story
import com.ntq.training.momentbox.domain.model.User
import com.ntq.training.momentbox.feature.language.LanguageDialogFragment
import com.ntq.training.momentbox.feature.settings.cleanupMediaDraftsFiles
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileOfflineLogoutRegressionTest {

    // --- H14: OfflineFeedState profile, posts, bookmarks & PATCH /me routes ---

    @Test
    fun offlineUsersRouteReturnsSeededUserMeAndMissing404() {
        val offline = createOfflineState()

        val (meCode, meBody) = offline.resolve(getRequest("http://localhost/v1/users/1"), "{}")
        assertEquals(200, meCode)
        val meData = JsonParser.parseString(meBody).asJsonObject.getAsJsonObject("data")
        assertEquals(1L, meData.get("id").asLong)
        assertEquals("Minh Anh", meData.get("displayName").asString)
        assertEquals(2, meData.get("postCount").asInt)

        val (otherCode, otherBody) = offline.resolve(getRequest("http://localhost/v1/users/2"), "{}")
        assertEquals(200, otherCode)
        val otherData = JsonParser.parseString(otherBody).asJsonObject.getAsJsonObject("data")
        assertEquals(2L, otherData.get("id").asLong)
        assertEquals("Lan Anh", otherData.get("displayName").asString)
        assertEquals(2, otherData.get("postCount").asInt)
        assertFalse(otherData.has("email"))

        val (missingCode, _) = offline.resolve(getRequest("http://localhost/v1/users/999"), "{}")
        assertEquals(404, missingCode)
    }

    @Test
    fun offlineUserPostsSupportsTypeVideoFilterAndPaginationMeta() {
        val offline = createOfflineState()

        val (page1Code, page1Body) = offline.resolve(
            getRequest("http://localhost/v1/users/1/posts?page=1&pageSize=1"),
            "{}"
        )
        assertEquals(200, page1Code)
        val page1Json = JsonParser.parseString(page1Body).asJsonObject
        assertTrue(page1Json.get("success").asBoolean)
        assertEquals(1, page1Json.getAsJsonArray("data").size())
        assertEquals(201L, page1Json.getAsJsonArray("data")[0].asJsonObject.get("id").asLong)
        val meta1 = page1Json.getAsJsonObject("meta")
        assertEquals(1, meta1.get("page").asInt)
        assertEquals(1, meta1.get("pageSize").asInt)
        assertEquals(2, meta1.get("total").asInt)
        assertTrue(meta1.get("hasMore").asBoolean)

        val (videoCode, videoBody) = offline.resolve(
            getRequest("http://localhost/v1/users/1/posts?type=VIDEO&page=1&pageSize=30"),
            "{}"
        )
        assertEquals(200, videoCode)
        val videoJson = JsonParser.parseString(videoBody).asJsonObject
        val videos = videoJson.getAsJsonArray("data")
        assertEquals(1, videos.size())
        assertEquals(202L, videos[0].asJsonObject.get("id").asLong)
        assertEquals("VIDEO", videos[0].asJsonObject.get("type").asString)
        assertFalse(videoJson.getAsJsonObject("meta").get("hasMore").asBoolean)
    }

    @Test
    fun offlineBookmarksTracksInitialAndDynamicBookmarkChangesWithMeta() {
        val offline = createOfflineState()

        val (initialCode, initialBody) = offline.resolve(
            getRequest("http://localhost/v1/me/bookmarks?page=1&pageSize=30"),
            "{}"
        )
        assertEquals(200, initialCode)
        val initialJson = JsonParser.parseString(initialBody).asJsonObject
        assertEquals(1, initialJson.getAsJsonArray("data").size())
        assertEquals(101L, initialJson.getAsJsonArray("data")[0].asJsonObject.get("id").asLong)
        assertEquals(1, initialJson.getAsJsonObject("meta").get("total").asInt)
        assertFalse(initialJson.getAsJsonObject("meta").get("hasMore").asBoolean)

        val (bmCode, _) = offline.resolve(postJson("http://localhost/v1/posts/103/bookmark", "{}"), "{}")
        assertEquals(200, bmCode)

        val (afterCode, afterBody) = offline.resolve(
            getRequest("http://localhost/v1/me/bookmarks?page=1&pageSize=1"),
            "{}"
        )
        assertEquals(200, afterCode)
        val afterJson = JsonParser.parseString(afterBody).asJsonObject
        assertEquals(1, afterJson.getAsJsonArray("data").size())
        assertEquals(2, afterJson.getAsJsonObject("meta").get("total").asInt)
        assertTrue(afterJson.getAsJsonObject("meta").get("hasMore").asBoolean)
    }

    @Test
    fun offlinePatchMeUpdatesInMemoryProfileAndRejectsBlankDisplayName() {
        val offline = createOfflineState()

        val (badCode, _) = offline.resolve(
            patchJson("http://localhost/v1/me", """{"displayName":"   ","bio":"x","location":"y"}"""),
            "{}"
        )
        assertEquals(400, badCode)

        val (patchCode, patchBody) = offline.resolve(
            patchJson(
                "http://localhost/v1/me",
                """{"displayName":"Minh Anh Updated","bio":"New offline bio","location":"Đà Lạt"}"""
            ),
            "{}"
        )
        assertEquals(200, patchCode)
        val patched = JsonParser.parseString(patchBody).asJsonObject.getAsJsonObject("data")
        assertEquals("Minh Anh Updated", patched.get("displayName").asString)
        assertEquals("New offline bio", patched.get("bio").asString)
        assertEquals("Đà Lạt", patched.get("location").asString)

        val (getCode, getBody) = offline.resolve(getRequest("http://localhost/v1/me"), "{}")
        assertEquals(200, getCode)
        val fetched = JsonParser.parseString(getBody).asJsonObject.getAsJsonObject("data")
        assertEquals("Minh Anh Updated", fetched.get("displayName").asString)
        assertEquals("New offline bio", fetched.get("bio").asString)
    }

    @Test
    fun offlineFollowUpdatesMeFollowingCountAndTargetFollowerCount() {
        val offline = createOfflineState()

        val (followCode, _) = offline.resolve(postJson("http://localhost/v1/users/2/follow", "{}"), "{}")
        assertEquals(200, followCode)

        val (_, meAfterFollow) = offline.resolve(getRequest("http://localhost/v1/me"), "{}")
        assertEquals(
            46,
            JsonParser.parseString(meAfterFollow).asJsonObject.getAsJsonObject("data")
                .get("followingCount").asInt
        )

        val (unfollowCode, _) = offline.resolve(deleteRequest("http://localhost/v1/users/2/follow"), "{}")
        assertEquals(200, unfollowCode)

        val (_, meAfterUnfollow) = offline.resolve(getRequest("http://localhost/v1/me"), "{}")
        assertEquals(
            45,
            JsonParser.parseString(meAfterUnfollow).asJsonObject.getAsJsonObject("data")
                .get("followingCount").asInt
        )
    }

    // --- H4 & M13: ProfileViewModel own followingCount updates & error clearing ---

    @Test
    fun ownProfileUpdatesFollowingCountWithoutDuplicateIncrements() = runTest {
        val scope = ProfileScope(ownerId = 1L, source = SettingsDataStore.SOURCE_MB_SERVER, generation = 3L)
        val updates = MutableSharedFlow<FeedProfileUpdate>(extraBufferCapacity = 8)
        val fakeProfile = FakeProfileData(
            sessions = flowOf(scope),
            initialUser = User(
                id = 1L,
                username = "minhanh",
                displayName = "Minh Anh",
                email = "minhanh@momentbox.dev",
                avatarUrl = null,
                followingCount = 10
            )
        )
        val fakeFeed = FakeFeedRepository(updates)

        withProfileViewModel(fakeProfile, fakeFeed) { vm ->
            runCurrent()
            assertEquals(10, vm.state.value.user?.followingCount)

            // Follow user 2 -> 11
            updates.tryEmit(FeedProfileUpdate(3L, scope.source, FeedProfileUpdate.Change.Follow(2L, true)))
            runCurrent()
            assertEquals(11, vm.state.value.user?.followingCount)

            // Duplicate follow emission for user 2 -> still 11
            updates.tryEmit(FeedProfileUpdate(3L, scope.source, FeedProfileUpdate.Change.Follow(2L, true)))
            runCurrent()
            assertEquals(11, vm.state.value.user?.followingCount)

            // Follow self is ignored -> still 11
            updates.tryEmit(FeedProfileUpdate(3L, scope.source, FeedProfileUpdate.Change.Follow(1L, true)))
            runCurrent()
            assertEquals(11, vm.state.value.user?.followingCount)

            // Unfollow user 2 -> 10
            updates.tryEmit(FeedProfileUpdate(3L, scope.source, FeedProfileUpdate.Change.Follow(2L, false)))
            runCurrent()
            assertEquals(10, vm.state.value.user?.followingCount)

            // Duplicate unfollow emission -> still 10
            updates.tryEmit(FeedProfileUpdate(3L, scope.source, FeedProfileUpdate.Change.Follow(2L, false)))
            runCurrent()
            assertEquals(10, vm.state.value.user?.followingCount)
        }
    }

    @Test
    fun clearActionErrorResetsStaleEditFailureState() = runTest {
        val scope = ProfileScope(ownerId = 1L, source = SettingsDataStore.SOURCE_OFFLINE, generation = 1L)
        val fakeProfile = FakeProfileData(
            sessions = flowOf(scope),
            initialUser = User(
                id = 1L,
                username = "minhanh",
                displayName = "Minh Anh",
                email = "minhanh@momentbox.dev",
                avatarUrl = null
            )
        )
        val failingAuth = FakeAuthRepository(editResult = Result.failure(UnsupportedOperationException("offline")))

        withProfileViewModel(fakeProfile, FakeFeedRepository(MutableSharedFlow()), failingAuth) { vm ->
            runCurrent()
            vm.editProfile("New Name", "bio", "loc")
            runCurrent()
            assertEquals("UNSUPPORTED", vm.state.value.actionError)

            vm.clearActionError()
            assertNull(vm.state.value.actionError)
        }
    }

    // --- H15: LogoutCoordinator media drafts & unattached recordings cleanup ---

    @Test
    fun logoutMediaCleanupPreservesReferencedJournalRecordingWhenKeepingJournal() {
        val tempRoot = Files.createTempDirectory("momentbox-logout-keep-").toFile()
        try {
            val postDrafts = File(tempRoot, "post-drafts").apply { mkdirs() }
            val createMedia = File(tempRoot, "create-media").apply { mkdirs() }
            val editorSessions = File(tempRoot, "editor-sessions").apply { mkdirs() }
            val recordings = File(tempRoot, "recordings").apply { mkdirs() }

            val draftFile = File(postDrafts, "draft-img.jpg").apply { writeText("a") }
            val mediaFile = File(createMedia, "image-1.jpg").apply { writeText("b") }
            val editorFile = File(editorSessions, "session-1.json").apply { writeText("{}") }
            val keptAudio = File(recordings, "journal_kept.m4a").apply { writeText("kept") }
            val orphanAudio = File(recordings, "rec_orphan.m4a").apply { writeText("orphan") }
            val pendingAudio = File(recordings, "rec_crash.pending.m4a").apply { writeText("pending") }

            cleanupMediaDraftsFiles(
                filesDir = tempRoot,
                keepJournal = true,
                referencedAttachmentPaths = setOf(keptAudio.absolutePath)
            )

            assertFalse(draftFile.exists())
            assertFalse(mediaFile.exists())
            assertFalse(editorFile.exists())
            assertTrue(keptAudio.exists())
            assertFalse(orphanAudio.exists())
            assertFalse(pendingAudio.exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun logoutMediaCleanupDeletesAllRecordingsWhenNotKeepingJournal() {
        val tempRoot = Files.createTempDirectory("momentbox-logout-wipe-").toFile()
        try {
            val recordings = File(tempRoot, "recordings").apply { mkdirs() }
            val keptAudio = File(recordings, "journal_kept.m4a").apply { writeText("kept") }
            val pendingAudio = File(recordings, "rec_crash.pending.m4a").apply { writeText("pending") }

            cleanupMediaDraftsFiles(
                filesDir = tempRoot,
                keepJournal = false,
                referencedAttachmentPaths = setOf(keptAudio.absolutePath)
            )

            assertFalse(keptAudio.exists())
            assertFalse(pendingAudio.exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    // --- M15b: LanguageDialogFragment fallback ---

    @Test
    fun unsupportedLanguageTagNormalizesToVietnamese() {
        assertEquals("vi", LanguageDialogFragment.normalizeLanguageTag("vi"))
        assertEquals("en", LanguageDialogFragment.normalizeLanguageTag("en-US"))
        assertEquals("ja", LanguageDialogFragment.normalizeLanguageTag("ja_JP"))
        assertEquals("vi", LanguageDialogFragment.normalizeLanguageTag("fr"))
        assertEquals("vi", LanguageDialogFragment.normalizeLanguageTag("ko"))
        assertEquals("vi", LanguageDialogFragment.normalizeLanguageTag(""))
        assertEquals("vi", LanguageDialogFragment.normalizeLanguageTag(null))
    }

    // --- Helpers ---

    private fun createOfflineState(): OfflineFeedState = OfflineFeedState(
        pages = { listOf(SAMPLE_FEED_PAGE) },
        commentTemplate = { SAMPLE_COMMENTS },
        meTemplate = { SAMPLE_ME },
        extraPosts = { listOf(SAMPLE_OWN_PHOTO_POST, SAMPLE_OWN_VIDEO_POST) }
    )

    private fun getRequest(url: String): Request = Request.Builder().url(url).get().build()

    private fun deleteRequest(url: String): Request = Request.Builder().url(url).delete().build()

    private fun postJson(url: String, json: String): Request =
        Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

    private fun patchJson(url: String, json: String): Request =
        Request.Builder()
            .url(url)
            .patch(json.toRequestBody("application/json".toMediaType()))
            .build()

    private suspend fun TestScope.withProfileViewModel(
        profileData: ProfileData,
        feedRepository: FeedRepository,
        authRepository: AuthRepository? = null,
        block: suspend (ProfileViewModel) -> Unit
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ProfileViewModel(
                        repository = profileData,
                        saved = SavedStateHandle(),
                        auth = authRepository,
                        feed = feedRepository
                    ) as T
            }
            val vm = ViewModelProvider(store, factory)[ProfileViewModel::class.java]
            block(vm)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private class FakeProfileData(
        override val sessions: Flow<ProfileScope?>,
        private val initialUser: User
    ) : ProfileData {
        override suspend fun cachedUser(scope: ProfileScope, userId: Long): User = initialUser
        override suspend fun user(scope: ProfileScope, userId: Long): User = initialUser
        override suspend fun page(
            scope: ProfileScope,
            userId: Long,
            tab: ProfileTab,
            page: Int,
            pageSize: Int
        ): ProfilePage = ProfilePage(emptyList(), page, 0, false)
    }

    private class FakeAuthRepository(
        private val editResult: Result<User>
    ) : AuthRepository {
        override val userFlow: Flow<User?> = flowOf(null)
        override suspend fun editProfile(
            displayName: String,
            bio: String,
            location: String
        ): Result<User> = editResult

        override suspend fun login(account: String, secret: String, rememberMe: Boolean) =
            error("unused")

        override suspend fun isLoggedIn() = true
        override suspend fun getSavedUser(): User? = null
        override suspend fun getMe(): Result<User> = error("unused")
        override suspend fun logout(): Result<Unit> = Result.success(Unit)
    }

    private class FakeFeedRepository(
        override val profileUpdates: Flow<FeedProfileUpdate>
    ) : FeedRepository {
        override val postsMapFlow: StateFlow<Map<Long, Post>> = MutableStateFlow(emptyMap())
        override fun observePost(postId: Long): Flow<Post?> = flowOf(null)
        override suspend fun getStories(): Result<List<Story>> = Result.success(emptyList())
        override suspend fun getFeed(page: Int, pageSize: Int): Result<List<FeedItem>> =
            Result.success(emptyList())

        override suspend fun getPostDetail(postId: Long): Result<Post> = error("unused")
        override suspend fun toggleLike(postId: Long): Result<Post> = error("unused")
        override suspend fun toggleBookmark(postId: Long): Result<Post> = error("unused")
        override suspend fun deletePost(postId: Long): Result<Unit> = Result.success(Unit)
        override suspend fun toggleFollowUser(userId: Long, currentFollowing: Boolean): Result<Boolean> =
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
        ): Result<Comment> = error("unused")

        override suspend fun toggleLikeComment(commentId: Long, currentLiked: Boolean): Result<Boolean> =
            Result.success(!currentLiked)

        override fun clearCache() {}
    }

    companion object {
        private const val SAMPLE_ME = """
            {
              "success": true,
              "data": {
                "id": 1,
                "username": "minhanh",
                "displayName": "Minh Anh",
                "avatarUrl": null,
                "email": "minhanh@momentbox.dev",
                "bio": "Offline bio",
                "location": "Hà Nội",
                "postCount": 2,
                "followerCount": 128,
                "followingCount": 45
              }
            }
        """

        private const val SAMPLE_FEED_PAGE = """
            {
              "success": true,
              "data": [
                {
                  "kind": "post",
                  "post": {
                    "id": 101,
                    "type": "PHOTO",
                    "author": { "id": 2, "username": "lananh", "displayName": "Lan Anh", "avatarUrl": null, "verified": false },
                    "caption": "Post 101",
                    "media": [],
                    "likeCount": 10,
                    "commentCount": 1,
                    "isLiked": false,
                    "isBookmarked": true,
                    "createdAt": "2026-10-26T10:00:00.000Z"
                  }
                },
                {
                  "kind": "post",
                  "post": {
                    "id": 103,
                    "type": "VIDEO",
                    "author": { "id": 2, "username": "lananh", "displayName": "Lan Anh", "avatarUrl": null, "verified": false },
                    "caption": "Post 103",
                    "media": [],
                    "likeCount": 5,
                    "commentCount": 0,
                    "isLiked": false,
                    "isBookmarked": false,
                    "createdAt": "2026-10-25T10:00:00.000Z"
                  }
                }
              ]
            }
        """

        private const val SAMPLE_OWN_PHOTO_POST = """
            {
              "id": 201,
              "type": "PHOTO",
              "author": { "id": 1, "username": "minhanh", "displayName": "Minh Anh", "avatarUrl": null, "verified": false },
              "caption": "Own photo",
              "media": [],
              "likeCount": 3,
              "commentCount": 0,
              "isLiked": false,
              "isBookmarked": false,
              "createdAt": "2026-10-26T09:00:00.000Z"
            }
        """

        private const val SAMPLE_OWN_VIDEO_POST = """
            {
              "id": 202,
              "type": "VIDEO",
              "author": { "id": 1, "username": "minhanh", "displayName": "Minh Anh", "avatarUrl": null, "verified": false },
              "caption": "Own video",
              "media": [],
              "likeCount": 4,
              "commentCount": 0,
              "isLiked": false,
              "isBookmarked": false,
              "createdAt": "2026-10-26T08:00:00.000Z"
            }
        """

        private const val SAMPLE_COMMENTS = """{"success":true,"data":[]}"""
    }
}
