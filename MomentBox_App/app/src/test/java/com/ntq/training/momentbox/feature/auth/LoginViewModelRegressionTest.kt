package com.ntq.training.momentbox.feature.auth

import androidx.lifecycle.SavedStateHandle
import com.ntq.training.momentbox.core.base.AppError
import com.ntq.training.momentbox.core.network.ApiException
import com.ntq.training.momentbox.data.local.prefs.PersistedTokens
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.local.prefs.SettingsStorage
import com.ntq.training.momentbox.data.local.prefs.TokenPersistence
import com.ntq.training.momentbox.data.local.prefs.TokenStore
import com.ntq.training.momentbox.data.remote.api.AuthApi
import com.ntq.training.momentbox.data.remote.api.DummyJsonApi
import com.ntq.training.momentbox.data.remote.api.UserApi
import com.ntq.training.momentbox.data.repository.AuthRepository
import com.ntq.training.momentbox.data.repository.AuthRepositoryImpl
import com.ntq.training.momentbox.domain.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelRegressionTest {

    private companion object {
        const val ERR_EMAIL_EMPTY = "Email không được để trống"
        const val ERR_EMAIL_INVALID = "Email không đúng định dạng"
        const val ERR_PASSWORD_SHORT = "Mật khẩu tối thiểu 6 ký tự"
    }

    @Test
    fun loginViaImeDoneValidatesBothEmailAndPasswordWithoutCallingRepository() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val authRepo = FakeAuthRepository()
            val settings = InMemorySettingsStorage()
            val vm = LoginViewModel(authRepo, settings, SavedStateHandle())

            vm.onEmailChanged("not-an-email")
            vm.onPasswordChanged("123", ERR_PASSWORD_SHORT)

            vm.login(
                emptyEmailErrorMsg = ERR_EMAIL_EMPTY,
                invalidEmailErrorMsg = ERR_EMAIL_INVALID,
                passwordLengthErrorMsg = ERR_PASSWORD_SHORT
            )
            runCurrent()

            assertEquals(0, authRepo.loginCalls)
            assertEquals(ERR_EMAIL_INVALID, vm.formState.value.emailError)
            assertEquals(ERR_PASSWORD_SHORT, vm.formState.value.passwordError)
            assertFalse(vm.formState.value.isFormValid)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun passwordShortenedAfterInteractingShowsErrorAndClearsWhenValid() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vm = LoginViewModel(FakeAuthRepository(), InMemorySettingsStorage(), SavedStateHandle())

            vm.onEmailChanged("minhanh@momentbox.dev")
            vm.onPasswordChanged("123456", ERR_PASSWORD_SHORT)
            assertNull(vm.formState.value.passwordError)
            assertTrue(vm.formState.value.isFormValid)

            // User deletes characters back below 6 after interacting
            vm.onPasswordChanged("123", ERR_PASSWORD_SHORT)
            assertEquals(ERR_PASSWORD_SHORT, vm.formState.value.passwordError)
            assertFalse(vm.formState.value.isFormValid)

            // Editing while still < 6 keeps the error visible
            vm.onPasswordChanged("1234", ERR_PASSWORD_SHORT)
            assertEquals(ERR_PASSWORD_SHORT, vm.formState.value.passwordError)
            assertFalse(vm.formState.value.isFormValid)

            // Reaching 6 characters clears the error and enables form
            vm.onPasswordChanged("123456", ERR_PASSWORD_SHORT)
            assertNull(vm.formState.value.passwordError)
            assertTrue(vm.formState.value.isFormValid)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun rotationPreservesValidationErrorsThroughSameInputSyncAndRecreation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val handle = SavedStateHandle()
            val settings = InMemorySettingsStorage()
            val vm = LoginViewModel(FakeAuthRepository(), settings, handle)

            vm.onEmailChanged("bad-email")
            vm.onPasswordChanged("123", ERR_PASSWORD_SHORT)
            vm.validateEmailOnFocusLost(ERR_EMAIL_EMPTY, ERR_EMAIL_INVALID)
            vm.validatePasswordOnFocusLost(ERR_PASSWORD_SHORT)

            assertEquals(ERR_EMAIL_INVALID, vm.formState.value.emailError)
            assertEquals(ERR_PASSWORD_SHORT, vm.formState.value.passwordError)

            // Re-delivering the same text or same dataSource (e.g. rotation / onResume) must not wipe errors
            vm.onEmailChanged("bad-email")
            vm.onPasswordChanged("123", ERR_PASSWORD_SHORT)
            vm.onDataSourceChanged(SettingsDataStore.SOURCE_MB_SERVER)

            assertEquals(ERR_EMAIL_INVALID, vm.formState.value.emailError)
            assertEquals(ERR_PASSWORD_SHORT, vm.formState.value.passwordError)

            // Recreating ViewModel with the same SavedStateHandle preserves both inline errors
            val recreated = LoginViewModel(FakeAuthRepository(), settings, handle)
            assertEquals("bad-email", recreated.formState.value.email)
            assertEquals(ERR_EMAIL_INVALID, recreated.formState.value.emailError)
            assertEquals(ERR_PASSWORD_SHORT, recreated.formState.value.passwordError)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun dummyJson400InvalidCredentialsMapsToLocalizedErrorNotBadRequest() = runBlocking {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            val settings = InMemorySettingsStorage(SettingsDataStore.SOURCE_DUMMY_JSON)
            val repo = createAuthRepository(
                dispatcher = dispatcher,
                settings = settings,
                tokenStore = TokenStore(InMemoryTokenPersistence())
            ) { statusCode, _ ->
                statusCode to """{"message":"Invalid credentials"}"""
            }
            val vm = LoginViewModel(repo, settings, SavedStateHandle())
            vm.onDataSourceChanged(SettingsDataStore.SOURCE_DUMMY_JSON)
            vm.onEmailChanged("emilys")
            vm.onPasswordChanged("wrongpass", ERR_PASSWORD_SHORT)

            vm.login(ERR_EMAIL_EMPTY, ERR_EMAIL_INVALID, ERR_PASSWORD_SHORT)

            val state = withTimeout(5_000L) {
                vm.uiState.first { it is LoginUiState.Error } as LoginUiState.Error
            }
            assertFalse(state.isNetworkError)
            assertNotEquals("Bad Request", state.message)
            assertEquals("Tài khoản hoặc mật khẩu không chính xác", state.message)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun dummyJson400EmptyBodyAndCustomJsonMessageNeverSurfaceRawBadRequest() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settings = InMemorySettingsStorage(SettingsDataStore.SOURCE_DUMMY_JSON)

        val emptyBodyRepo = createAuthRepository(
            dispatcher = dispatcher,
            settings = settings,
            tokenStore = TokenStore(InMemoryTokenPersistence())
        ) { _, _ -> 400 to "" }

        val emptyResult = emptyBodyRepo.login("emilys", "wrongpass", rememberMe = true)
        assertTrue(emptyResult.isFailure)
        val emptyError = emptyResult.exceptionOrNull() as ApiException
        assertTrue(emptyError.error is AppError.InvalidCredentials)
        assertNotEquals("Bad Request", emptyError.error.message)
        assertEquals("Tài khoản hoặc mật khẩu không chính xác", emptyError.error.message)

        val customBodyRepo = createAuthRepository(
            dispatcher = dispatcher,
            settings = settings,
            tokenStore = TokenStore(InMemoryTokenPersistence())
        ) { _, _ -> 400 to """{"message":"Account temporarily locked"}""" }

        val customResult = customBodyRepo.login("emilys", "wrongpass", rememberMe = true)
        assertTrue(customResult.isFailure)
        val customError = customResult.exceptionOrNull() as ApiException
        assertTrue(customError.error is AppError.InvalidCredentials)
        assertEquals("Account temporarily locked", customError.error.message)
    }

    @Test
    fun rememberMeFalseKeepsProfileInMemoryAndClearsPersistedSavedUserJson() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val diskTokens = InMemoryTokenPersistence()
        val settings = InMemorySettingsStorage(SettingsDataStore.SOURCE_DUMMY_JSON)
        // Seed stale persisted user JSON from a previous session
        settings.setSavedUserJson("""{"id":99,"username":"old","displayName":"Old User"}""")

        val tokenStore = TokenStore(diskTokens)
        val repo = createAuthRepository(
            dispatcher = dispatcher,
            settings = settings,
            tokenStore = tokenStore
        ) { _, _ ->
            200 to """
                {
                    "id": 1,
                    "username": "emilys",
                    "email": "emily@dummyjson.com",
                    "firstName": "Emily",
                    "lastName": "Johnson",
                    "image": "https://example.com/emily.png",
                    "accessToken": "acc-temp-1",
                    "refreshToken": "ref-temp-1"
                }
            """.trimIndent()
        }

        val loginResult = repo.login("emilys", "emilyspass", rememberMe = false)
        assertTrue(loginResult.isSuccess)
        assertTrue(repo.isLoggedIn())

        // In-memory profile works while process is alive
        val inMemoryUser = repo.getSavedUser()
        assertNotNull(inMemoryUser)
        assertEquals("emilys", inMemoryUser?.username)
        assertEquals("emilys", repo.userFlow.first()?.username)

        // Disk storage must have NO persisted user profile JSON or tokens
        assertNull(settings.getSavedUserJson())
        assertNull(diskTokens.stored.accessToken)
        assertNull(diskTokens.stored.refreshToken)
        assertFalse(diskTokens.stored.rememberMe)

        // Simulate app process kill & restart using the same disk stores
        val restartedTokenStore = TokenStore(diskTokens)
        val restartedRepo = createAuthRepository(
            dispatcher = dispatcher,
            settings = settings,
            tokenStore = restartedTokenStore
        ) { _, _ -> 500 to "" }

        assertFalse(restartedRepo.isLoggedIn())
        assertNull(restartedRepo.getSavedUser())
        assertNull(restartedRepo.userFlow.first())
    }

    private fun createAuthRepository(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        settings: SettingsStorage,
        tokenStore: TokenStore,
        responder: (Int, String) -> Pair<Int, String>
    ): AuthRepositoryImpl {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val (code, body) = responder(400, request.url.encodedPath)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 400) "Bad Request" else "OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return AuthRepositoryImpl(
            authApi = retrofit.create(AuthApi::class.java),
            userApi = retrofit.create(UserApi::class.java),
            dummyJsonApi = retrofit.create(DummyJsonApi::class.java),
            tokenStore = tokenStore,
            settingsDataStore = settings,
            dispatcher = dispatcher
        )
    }

    private class InMemoryTokenPersistence : TokenPersistence {
        var stored: PersistedTokens = PersistedTokens(null, null, true, 0L)
        override suspend fun read(): PersistedTokens = stored
        override suspend fun write(tokens: PersistedTokens) {
            stored = tokens
        }
    }

    private class FakeAuthRepository : AuthRepository {
        var loginCalls = 0
        override suspend fun login(account: String, secret: String, rememberMe: Boolean): Result<User> {
            loginCalls++
            return Result.success(User(1L, account, account, account, null))
        }

        override suspend fun isLoggedIn(): Boolean = false
        override suspend fun getSavedUser(): User? = null
        override suspend fun getMe(): Result<User> = Result.failure(IllegalStateException("unused"))
        override suspend fun logout(): Result<Unit> = Result.success(Unit)
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
