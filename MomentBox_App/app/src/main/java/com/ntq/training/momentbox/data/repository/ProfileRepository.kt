package com.ntq.training.momentbox.data.repository

import com.google.gson.Gson
import com.ntq.training.momentbox.core.network.ApiResponse
import com.ntq.training.momentbox.core.network.SessionRequest
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.local.prefs.SettingsStorage
import com.ntq.training.momentbox.data.local.prefs.TokenStorage
import com.ntq.training.momentbox.data.remote.api.ProfileApi
import com.ntq.training.momentbox.domain.mapper.PostMapper
import com.ntq.training.momentbox.domain.mapper.UserMapper
import com.ntq.training.momentbox.domain.model.User
import com.ntq.training.momentbox.feature.profile.ProfilePage
import com.ntq.training.momentbox.feature.profile.ProfileScope
import com.ntq.training.momentbox.feature.profile.ProfileTab
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response

interface ProfileData {
    suspend fun suggestions(scope: ProfileScope): List<User> =
        throw UnsupportedOperationException("PROFILE_PEOPLE_UNAVAILABLE")

    val sessions: Flow<ProfileScope?>
    suspend fun cachedUser(scope: ProfileScope, userId: Long): User?
    suspend fun user(scope: ProfileScope, userId: Long): User
    suspend fun page(
        scope: ProfileScope,
        userId: Long,
        tab: ProfileTab,
        page: Int,
        pageSize: Int = 30
    ): ProfilePage
}

class ProfileRepository(
    private val api: ProfileApi,
    private val tokens: TokenStorage,
    private val settings: SettingsStorage,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val gson: Gson = Gson(),
    private val auth: AuthRepository? = null
) : ProfileData {
    private val cached = linkedMapOf<Pair<ProfileScope, Long>, User>()

    /** Một màn đã mở không tự nhận account mới khi token đổi. Emit null để ViewModel dọn
     * các trang cũ; Main/auth guard chịu trách nhiệm mở màn mới sau khi login đã commit cache.
     * Refresh token cùng generation không làm pager mất scroll. awaitClose nhả listener theo VM. */
    override val sessions: Flow<ProfileScope?> = callbackFlow {
        val initial = try {
            snapshot()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (_: IOException) {
            null
        }
        trySend(initial)
        if (initial == null) {
            awaitClose { }
            return@callbackFlow
        }
        val subscription = tokens.addSnapshotListener { token ->
            if (token.generation != initial.generation || token.accessToken.isNullOrBlank()) trySend(
                null
            )
        }
        val sourceJob =
            launch { settings.dataSourceFlow.collect { if (it != initial.source) trySend(null) } }
        awaitClose { subscription.close()
        sourceJob.cancel() }
    }.distinctUntilChanged()

    override suspend fun cachedUser(scope: ProfileScope, userId: Long): User? =
        withContext(dispatcher) {
            verify(scope)
            // Own cache trong DataStore có local avatar version mới sau ACK; ưu tiên nó hơn
            // cache GET RAM. Hồ sơ người khác dùng map có scope và giới hạn32 bản ghi.
            if (userId != scope.ownerId) return@withContext synchronized(cached) { cached[scope to userId] }
            val json = settings.getSavedUserJson()
            val own = runCatching { gson.fromJson(json, User::class.java) }.getOrNull()
                ?.takeIf { it.id == userId }
            verify(scope)
            own
        }

    fun clear() = synchronized(cached) { cached.clear() }

    override suspend fun suggestions(scope: ProfileScope): List<User> = withContext(dispatcher) {
        verify(scope)
        supported(scope)
        val result = body(api.suggestions(SessionRequest(scope.generation))).map(UserMapper()::map)
            .filter { it.id != scope.ownerId }
        verify(scope)
        result
    }

    suspend fun snapshot(): ProfileScope = withContext(dispatcher) {
        val token = tokens.snapshot()
        if (token.accessToken.isNullOrBlank()) throw CancellationException("Profile session unavailable")
        val source = settings.getDataSource()
        val json = settings.getSavedUserJson()
        val user = runCatching { gson.fromJson(json, User::class.java) }.getOrNull()
            ?: throw IOException("PROFILE_OWNER_UNAVAILABLE")
        val scope = ProfileScope(user.id, source, token.generation)
        require(scope.ownerId > 0)
        verify(scope)
        scope
    }

    suspend fun verify(scope: ProfileScope) {
        val token = tokens.snapshot()
        if (token.generation != scope.generation || token.accessToken.isNullOrBlank() || settings.getDataSource() != scope.source)
            throw CancellationException("Profile session changed")
    }

    private fun supported(scope: ProfileScope) {
        if (scope.source !in listOf(
                SettingsDataStore.SOURCE_MB_SERVER,
                SettingsDataStore.SOURCE_OFFLINE
            )
        )
            throw UnsupportedOperationException("PROFILE_SOURCE_UNSUPPORTED")
    }

    override suspend fun user(scope: ProfileScope, userId: Long): User = withContext(dispatcher) {
        require(userId > 0)
        verify(scope)
        supported(scope)
        val ownAuth = auth
        val result = if (userId == scope.ownerId && ownAuth != null) ownAuth.getMe().getOrThrow()
        else UserMapper().map(
            body(
                if (userId == scope.ownerId) api.me(SessionRequest(scope.generation)) else api.user(
                    userId,
                    SessionRequest(scope.generation)
                )
            )
        )
        verify(scope)
        if (result.id != userId) throw IOException("PROFILE_ID_MISMATCH")
        if (!tokens.runIfGenerationMatches(scope.generation) {
                synchronized(cached) {
                    cached[scope to userId] = result
                    while (cached.size > 32) cached.remove(cached.keys.first())
                }
            }) throw CancellationException("Profile session changed")
        result
    }

    override suspend fun page(
        scope: ProfileScope,
        userId: Long,
        tab: ProfileTab,
        page: Int,
        pageSize: Int
    ): ProfilePage = withContext(dispatcher) {
        require(userId > 0 && page > 0 && pageSize in 1..100)
        require(tab != ProfileTab.SAVED || userId == scope.ownerId) { "Saved is private to the current owner" }
        verify(scope)
        supported(scope)
        val session = SessionRequest(scope.generation)
        val response = if (tab == ProfileTab.SAVED) api.bookmarks(page, pageSize, session)
        else api.posts(
            userId,
            if (tab == ProfileTab.VIDEOS) "VIDEO" else null,
            page,
            pageSize,
            session
        )
        val rows = body(response)
        val meta = response.body()?.meta ?: throw IOException("PROFILE_PAGE_META_MISSING")
        val more = meta.hasMore ?: throw IOException("PROFILE_HAS_MORE_MISSING")
        if (meta.page != null && meta.page != page) throw IOException("PROFILE_PAGE_MISMATCH")
        // hasMore là hợp đồng của server. Không đoán hết trang bằng rows.size vì server có
        // thể trả ít item do filter/quyền, trong khi vẫn còn trang sau.
        val result =
            ProfilePage(rows.map(PostMapper()::map).distinctBy { it.id }, page, meta.total, more)
        verify(scope)
        result
    }

    private fun <T> body(response: Response<ApiResponse<T>>): T {
        val envelope = response.body()
        if (!response.isSuccessful || envelope?.success != true || envelope.data == null)
            throw IOException("PROFILE_HTTP_${response.code()}")
        return envelope.data
    }
}
