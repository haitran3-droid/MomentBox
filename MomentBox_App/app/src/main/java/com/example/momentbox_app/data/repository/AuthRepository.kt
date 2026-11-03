package com.example.momentbox_app.data.repository

import com.example.momentbox_app.core.base.AppError
import com.example.momentbox_app.core.network.ApiException
import com.example.momentbox_app.core.network.SessionManager
import com.example.momentbox_app.core.network.SessionRequest
import com.example.momentbox_app.core.network.safeApiCall
import com.example.momentbox_app.data.local.prefs.SettingsDataStore
import com.example.momentbox_app.data.local.prefs.TokenStore
import com.example.momentbox_app.data.remote.api.AuthApi
import com.example.momentbox_app.data.remote.api.DummyJsonApi
import com.example.momentbox_app.data.remote.api.UserApi
import com.example.momentbox_app.data.remote.dto.DummyJsonLoginRequest
import com.example.momentbox_app.data.remote.dto.LoginRequest
import com.example.momentbox_app.data.remote.dto.LogoutRequest
import com.example.momentbox_app.domain.mapper.UserMapper
import com.example.momentbox_app.domain.model.User
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.SocketTimeoutException

interface AuthRepository {
    suspend fun login(account: String, secret: String, rememberMe: Boolean): Result<User>
    suspend fun isLoggedIn(): Boolean
    suspend fun getSavedUser(): User?
    suspend fun getMe(): Result<User>
    suspend fun logout(): Result<Unit>
    suspend fun changeDataSource(source: String){
        throw UnsupportedOperationException("Source selection unavailable")
    }
}

class AuthRepositoryImpl(
    private val authApi: AuthApi,
    private val userApi: UserApi,
    private val dummyJsonApi: DummyJsonApi,
    private val tokenStore: TokenStore,
    private val settingsDataStore: SettingsDataStore,
    private val userMapper: UserMapper = UserMapper(),
    private val gson: Gson = Gson(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
): AuthRepository{
    private val sessionMutex = Mutex()
    override suspend fun changeDataSource(source: String) = sessionMutex.withLock {
        tokenStore.clear()
        settingsDataStore.setSavedUserJson(null)
        settingsDataStore.setDataSource(source)
    }

    override suspend fun login(account: String, secret: String, rememberMe: Boolean): Result<User> = withContext(dispatcher) {
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
                            if (!tokenStore.saveTokensIfGenerationMatches(expectedGeneration, body.accessToken, body.refreshToken, rememberMe)) {
                                throw CancellationException("Login belongs to an invalidated session")
                            }
                            SessionManager.resetSession()
                            val user = userMapper.mapFromDummyJson(body)
                            settingsDataStore.setSavedUserJson(gson.toJson(user))
                            Result.success(user)
                        }
                    } else {
                        val code = dummyResponse.code()
                        val msg = dummyResponse.message().ifBlank { "Tài khoản hoặc mật khẩu không chính xác ($code)" }
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
                        if (!tokenStore.saveTokensIfGenerationMatches(expectedGeneration, dto.accessToken, dto.refreshToken, rememberMe)) {
                            throw CancellationException("Login belongs to an invalidated session")
                        }
                        SessionManager.resetSession()

                        val user = if (dto.user != null) {
                            userMapper.map(dto.user)
                        } else {
                            User(id = 1, username = account, displayName = account, email = account, avatarUrl = null)
                        }

                        settingsDataStore.setSavedUserJson(gson.toJson(user))
                        Result.success(user)
                    }
                } else {
                    Result.failure(result.exceptionOrNull() ?: Exception("Đăng nhập thất bại"))
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { networkFailure(error) }
    }

    private fun networkFailure(error: Exception): Result<User> = Result.failure(when (error) {
        is SocketTimeoutException -> ApiException(AppError.Timeout("Request timed out"))
        is IOException -> ApiException(AppError.Network("Cannot connect to server", error))
        else -> error
    })

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
        val (session, dataSource) = sessionMutex.withLock {
            tokenStore.snapshot() to settingsDataStore.getDataSource()
        }
        val expectedGeneration = session.generation

        if (dataSource == SettingsDataStore.SOURCE_DUMMY_JSON) {
            val token = session.accessToken
            if (token.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Chưa đăng nhập"))
            }
            try {
                val response = dummyJsonApi.getMe("Bearer $token",
                    SessionRequest(expectedGeneration)
                )
                if (response.isSuccessful && response.body() != null) {
                    sessionMutex.withLock {
                        if (expectedGeneration != tokenStore.getSessionGeneration()) throw CancellationException("Session changed")
                        val user = userMapper.mapFromDummyJsonMe(response.body()!!)
                        settingsDataStore.setSavedUserJson(gson.toJson(user))
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
                    if (expectedGeneration != tokenStore.getSessionGeneration()) throw CancellationException("Session changed")
                    val user = userMapper.map(result.getOrThrow())
                    settingsDataStore.setSavedUserJson(gson.toJson(user))
                    Result.success(user)
                }
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("Không thể tải thông tin cá nhân"))
            }
        }
    }

    override suspend fun logout(): Result<Unit> = withContext(dispatcher) {
        val (oldSession, dataSource) = sessionMutex.withLock {
            val source = settingsDataStore.getDataSource()
            val old = tokenStore.snapshot()
            tokenStore.clear()
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