package com.ntq.training.momentbox.data.repository

import com.google.gson.Gson
import com.ntq.training.momentbox.core.base.AppError
import com.ntq.training.momentbox.core.network.ApiException
import com.ntq.training.momentbox.core.network.ApiResponse
import com.ntq.training.momentbox.core.network.safeApiCall
import com.ntq.training.momentbox.core.network.SessionManager
import com.ntq.training.momentbox.core.network.SessionRequest
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.local.prefs.SettingsStorage
import com.ntq.training.momentbox.data.local.prefs.TokenStorage
import com.ntq.training.momentbox.data.local.prefs.TokenStore
import com.ntq.training.momentbox.data.remote.api.AuthApi
import com.ntq.training.momentbox.data.remote.api.DummyJsonApi
import com.ntq.training.momentbox.data.remote.api.ProfileApi
import com.ntq.training.momentbox.data.remote.api.ProfileEditRequest
import com.ntq.training.momentbox.data.remote.api.UserApi
import com.ntq.training.momentbox.data.remote.dto.DummyJsonLoginRequest
import com.ntq.training.momentbox.data.remote.dto.LoginRequest
import com.ntq.training.momentbox.data.remote.dto.LogoutRequest
import com.ntq.training.momentbox.data.remote.dto.UserDto
import com.ntq.training.momentbox.domain.mapper.UserMapper
import com.ntq.training.momentbox.domain.model.User
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Response

interface AuthRepository {
    val userFlow: Flow<User?> get() = kotlinx.coroutines.flow.flow { emit(getSavedUser()) }
    suspend fun editProfile(displayName: String, bio: String, location: String): Result<User> =
        Result.failure(UnsupportedOperationException("PROFILE_EDIT_UNSUPPORTED"))

    suspend fun updateAvatar(file: File): Result<User> =
        Result.failure(UnsupportedOperationException("PROFILE_AVATAR_UNSUPPORTED"))

    suspend fun login(account: String, secret: String, rememberMe: Boolean): Result<User>
    suspend fun isLoggedIn(): Boolean
    suspend fun getSavedUser(): User?
    suspend fun getMe(): Result<User>
    suspend fun logout(): Result<Unit>
    suspend fun changeDataSource(source: String) {
        throw UnsupportedOperationException("Source selection unavailable")
    }
}

class AuthRepositoryImpl(
    private val authApi: AuthApi,
    private val userApi: UserApi,
    private val dummyJsonApi: DummyJsonApi,
    private val tokenStore: TokenStorage,
    private val settingsDataStore: SettingsStorage,
    private val userMapper: UserMapper = UserMapper(),
    private val gson: Gson = Gson(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val invalidateFeedCache: () -> Unit = {},
    private val profileApi: ProfileApi? = null,
    private val onSessionEstablished: () -> Unit = {}
) : AuthRepository {
    /** Khóa đồng bộ phiên làm việc, ngăn ngừa xung đột giữa Login, Logout và Đổi nguồn API */
    private val sessionMutex = Mutex()
    private val profileMutationMutex = Mutex()

    // Session mutex bảo vệ revision cùng cache. GET bắt đầu trước/giữa upload không
    // được ghi đè avatar/PATCH mới sau ACK, dù các HTTP response về khác thứ tự.
    private var profileRevision = 0L
    override val userFlow: Flow<User?> = settingsDataStore.savedUserJsonFlow.map { json ->
        json?.let { runCatching { gson.fromJson(it, User::class.java) }.getOrNull() }
    }.flowOn(dispatcher)

    /**
     * Chuyển đổi nguồn API dữ liệu:
     * - Bắt buộc giữ trạng thái bận và thực hiện theo đúng thứ tự:
     *   1. Xóa toàn bộ token của nguồn cũ.
     *   2. Xóa cache thông tin người dùng cũ.
     *   3. Ghi nhận nguồn dữ liệu mới vào DataStore.
     */
    override suspend fun changeDataSource(source: String) = sessionMutex.withLock {
        tokenStore.clear()
        invalidateFeedCache()
        settingsDataStore.setSavedUserJson(null)
        settingsDataStore.setDataSource(source)
        invalidateFeedCache()
    }

    override suspend fun login(account: String, secret: String, rememberMe: Boolean): Result<User> =
        withContext(dispatcher) {
            try {
                // Ghi nhận thế hệ phiên và nguồn dữ liệu hiện tại trước khi gọi mạng
                val (expectedGeneration, dataSource) = sessionMutex.withLock {
                    tokenStore.snapshot().generation to settingsDataStore.getDataSource()
                }

                if (dataSource == SettingsDataStore.SOURCE_DUMMY_JSON) {
                    // Handle DummyJSON login adapter
                    try {
                        val dummyResponse = dummyJsonApi.login(
                            DummyJsonLoginRequest(
                                username = account,
                                password = secret,
                                expiresInMins = 30
                            )
                        )
                        if (dummyResponse.isSuccessful && dummyResponse.body() != null) {
                            val body = dummyResponse.body()!!
                            if (body.accessToken.isNullOrBlank() || body.refreshToken.isNullOrBlank()) {
                                return@withContext Result.failure(ApiException(AppError.Unknown("Malformed login response: missing tokens")))
                            }
                            sessionMutex.withLock {
                                if (!tokenStore.saveTokensIfGenerationMatches(
                                        expectedGeneration,
                                        body.accessToken,
                                        body.refreshToken,
                                        rememberMe
                                    )
                                ) {
                                    throw CancellationException("Login belongs to an invalidated session")
                                }
                                SessionManager.resetSession()
                                val user = userMapper.mapFromDummyJson(body)
                                if (!settingsDataStore.setSavedUserForGeneration(
                                        expectedGeneration + 1,
                                        gson.toJson(user)
                                    )
                                )
                                    throw CancellationException("Login session changed before profile commit")
                                onSessionEstablished()
                                Result.success(user)
                            }
                        } else {
                            val code = dummyResponse.code()
                            val msg = dummyResponse.message()
                                .ifBlank { "Tài khoản hoặc mật khẩu không chính xác ($code)" }
                            Result.failure(Exception(msg))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        networkFailure(e)
                    }
                } else {
                    // Standard MB Server / Offline mode
                    val result = safeApiCall(dispatcher = dispatcher, gson = gson) {
                        authApi.login(LoginRequest(email = account, password = secret))
                    }

                    if (result.isSuccess) {
                        val dto = result.getOrThrow()
                        if (dto.accessToken.isNullOrBlank() || dto.refreshToken.isNullOrBlank()) {
                            return@withContext Result.failure(ApiException(AppError.Unknown("Malformed login response: missing tokens")))
                        }
                        sessionMutex.withLock {
                            if (!tokenStore.saveTokensIfGenerationMatches(
                                    expectedGeneration,
                                    dto.accessToken,
                                    dto.refreshToken,
                                    rememberMe
                                )
                            ) {
                                throw CancellationException("Login belongs to an invalidated session")
                            }
                            SessionManager.resetSession()

                            val user = if (dto.user != null) {
                                userMapper.map(dto.user)
                            } else {
                                User(
                                    id = 1,
                                    username = account,
                                    displayName = account,
                                    email = account,
                                    avatarUrl = null
                                )
                            }

                            if (!settingsDataStore.setSavedUserForGeneration(
                                    expectedGeneration + 1,
                                    gson.toJson(user)
                                )
                            )
                                throw CancellationException("Login session changed before profile commit")
                            onSessionEstablished()
                            Result.success(user)
                        }
                    } else {
                        Result.failure(result.exceptionOrNull() ?: Exception("Đăng nhập thất bại"))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                networkFailure(error)
            }
        }

    private fun networkFailure(error: Exception): Result<User> = Result.failure(
        when (error) {
            is SocketTimeoutException -> ApiException(AppError.Timeout("Request timed out"))
            is IOException -> ApiException(AppError.Network("Cannot connect to server", error))
            else -> error
        }
    )

    override suspend fun isLoggedIn(): Boolean {
        val token = tokenStore.getAccessToken()
        return !token.isNullOrBlank()
    }

    override suspend fun getSavedUser(): User? {
        val json = settingsDataStore.getSavedUserJson() ?: return null
        return try {
            gson.fromJson(json, User::class.java)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getMe(): Result<User> = withContext(dispatcher) {
        val (session, dataSource, readRevision) = sessionMutex.withLock {
            Triple(tokenStore.snapshot(), settingsDataStore.getDataSource(), profileRevision)
        }
        val expectedGeneration = session.generation

        if (dataSource == SettingsDataStore.SOURCE_DUMMY_JSON) {
            val token = session.accessToken
            if (token.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Chưa đăng nhập"))
            }
            try {
                val response =
                    dummyJsonApi.getMe("Bearer $token", SessionRequest(expectedGeneration))
                if (response.isSuccessful && response.body() != null) {
                    sessionMutex.withLock {
                        if (expectedGeneration != tokenStore.getSessionGeneration()) throw CancellationException(
                            "Session changed"
                        )
                        val user = resolveProfileRead(
                            readRevision,
                            userMapper.mapFromDummyJsonMe(response.body()!!)
                        )
                        if (!settingsDataStore.setSavedUserForGeneration(
                                expectedGeneration,
                                gson.toJson(user)
                            )
                        )
                            throw CancellationException("Profile session changed before cache commit")
                        Result.success(user)
                    }
                } else {
                    Result.failure(Exception("Không thể tải thông tin cá nhân: ${response.code()}"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            val result = safeApiCall(dispatcher = dispatcher, gson = gson) {
                userApi.getMe(SessionRequest(expectedGeneration))
            }
            if (result.isSuccess) {
                sessionMutex.withLock {
                    if (expectedGeneration != tokenStore.getSessionGeneration()) throw CancellationException(
                        "Session changed"
                    )
                    val user = resolveProfileRead(readRevision, userMapper.map(result.getOrThrow()))
                    if (!settingsDataStore.setSavedUserForGeneration(
                            expectedGeneration,
                            gson.toJson(user)
                        )
                    )
                        throw CancellationException("Profile session changed before cache commit")
                    Result.success(user)
                }
            } else {
                Result.failure(
                    result.exceptionOrNull() ?: Exception("Không thể tải thông tin cá nhân")
                )
            }
        }
    }

    /** Giữ local signature khi GET làm mới dữ liệu; read cũ trả cache đã ACK thay vì
     * đưa URL/bio cũ trở lại UI. Chỉ gọi trong sessionMutex, không khóa lúc chờ HTTP. */
    private suspend fun resolveProfileRead(readRevision: Long, incoming: User): User {
        val cached = getSavedUser()?.takeIf { it.id == incoming.id }
        return if (readRevision != profileRevision && cached != null) cached
        else incoming.copy(avatarVersion = cached?.avatarVersion ?: 0L)
    }

    override suspend fun editProfile(
        displayName: String,
        bio: String,
        location: String
    ): Result<User> {
        if (displayName.isBlank()) return Result.failure(IllegalArgumentException("PROFILE_NAME_REQUIRED"))
        return mutateProfile(false) { api, session ->
            api.edit(
                ProfileEditRequest(
                    displayName.trim(),
                    bio,
                    location.trim()
                ), session
            )
        }
    }

    override suspend fun updateAvatar(file: File): Result<User> = withContext(dispatcher) {
        if (!file.isFile || file.length() == 0L) return@withContext Result.failure(IOException("AVATAR_FILE_UNAVAILABLE"))
        mutateProfile(true) { api, session ->
            api.avatar(
                MultipartBody.Part.createFormData(
                    "avatar",
                    file.name,
                    file.asRequestBody("image/jpeg".toMediaType())
                ), session
            )
        }
    }

    private suspend fun mutateProfile(
        avatarChanged: Boolean,
        request: suspend (ProfileApi, SessionRequest) -> Response<ApiResponse<UserDto>>
    ): Result<User> = withContext(dispatcher) {
        profileMutationMutex.withLock {
            try {
                val api = profileApi
                    ?: throw UnsupportedOperationException("PROFILE_MUTATION_UNAVAILABLE")
                val (generation, ownerId) = sessionMutex.withLock {
                    val token = tokenStore.snapshot()
                    if (settingsDataStore.getDataSource() != SettingsDataStore.SOURCE_MB_SERVER)
                        throw UnsupportedOperationException("PROFILE_SOURCE_UNSUPPORTED")
                    if (token.accessToken.isNullOrBlank()) throw CancellationException("Profile session unavailable")
                    val own = getSavedUser() ?: throw IOException("PROFILE_OWNER_UNAVAILABLE")
                    ++profileRevision
                    token.generation to own.id
                }
                val response = safeApiCall(dispatcher = dispatcher, gson = gson) {
                    request(
                        api,
                        SessionRequest(generation)
                    )
                }
                if (response.isFailure) return@withLock Result.failure<User>(
                    response.exceptionOrNull() ?: IOException("PROFILE_UPDATE_FAILED")
                )
                sessionMutex.withLock {
                    if (tokenStore.getSessionGeneration() != generation) throw CancellationException(
                        "Profile session changed"
                    )
                    val incoming = userMapper.map(response.getOrThrow())
                    if (incoming.id != ownerId) throw IOException("PROFILE_ID_MISMATCH")
                    val previous = getSavedUser()?.takeIf { it.id == ownerId }
                        ?: throw CancellationException("Profile owner changed")
                    val updated =
                        incoming.copy(avatarVersion = previous.avatarVersion + if (avatarChanged) 1L else 0L)
                    if (!settingsDataStore.setSavedUserForGeneration(
                            generation,
                            gson.toJson(updated)
                        )
                    )
                        throw CancellationException("Profile session changed before commit")
                    ++profileRevision
                    Result.success(updated)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                networkFailure(failure)
            }
        }
    }

    override suspend fun logout(): Result<Unit> = withContext(dispatcher) {
        val (oldSession, dataSource) = sessionMutex.withLock {
            val source = settingsDataStore.getDataSource()
            val old = tokenStore.snapshot()
            tokenStore.clear()
            invalidateFeedCache()
            settingsDataStore.setSavedUserJson(null)
            old to source
        }
        val refreshToken = oldSession.refreshToken

        if (dataSource == SettingsDataStore.SOURCE_MB_SERVER && !refreshToken.isNullOrBlank()) {
            try {
                withTimeoutOrNull(1500) {
                    authApi.logout(LogoutRequest(refreshToken), "Bearer ${oldSession.accessToken}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
            }
        }
        Result.success(Unit)
    }
}
