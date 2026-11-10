package com.ntq.training.momentbox

import android.app.Application
import com.google.gson.Gson
import com.ntq.training.momentbox.BuildConfig
import com.ntq.training.momentbox.core.coroutines.DefaultDispatcherProvider
import com.ntq.training.momentbox.core.coroutines.DispatcherProvider
import com.ntq.training.momentbox.core.network.NetworkModule
import com.ntq.training.momentbox.core.realtime.NotificationScope
import com.ntq.training.momentbox.core.realtime.SocketManager
import com.ntq.training.momentbox.data.local.db.AppDatabase
import com.ntq.training.momentbox.data.local.prefs.JournalDataStore
import com.ntq.training.momentbox.data.local.prefs.PostDraftStore
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.local.prefs.TokenStore
import com.ntq.training.momentbox.data.remote.api.AuthApi
import com.ntq.training.momentbox.data.remote.api.ConfigApi
import com.ntq.training.momentbox.data.remote.api.DummyJsonApi
import com.ntq.training.momentbox.data.remote.api.FeedApi
import com.ntq.training.momentbox.data.remote.api.UserApi
import com.ntq.training.momentbox.data.repository.AuthRepository
import com.ntq.training.momentbox.data.repository.AuthRepositoryImpl
import com.ntq.training.momentbox.data.repository.ConfigRepository
import com.ntq.training.momentbox.data.repository.ConfigRepositoryImpl
import com.ntq.training.momentbox.data.repository.FeedRepository
import com.ntq.training.momentbox.data.repository.FeedRepositoryImpl
import com.ntq.training.momentbox.data.repository.JournalRepository
import com.ntq.training.momentbox.data.repository.JournalRepositoryImpl
import com.ntq.training.momentbox.data.repository.UploadRepository
import com.ntq.training.momentbox.domain.model.User
import java.time.Clock

class MomentBoxApp : Application() {
    var journalClock: Clock = Clock.systemDefaultZone()
        internal set

    val postDraftStore by lazy { PostDraftStore(this) }
    val uploadRepository by lazy { UploadRepository(this) }
    lateinit var tokenStore: TokenStore
        private set

    lateinit var settingsDataStore: SettingsDataStore
        private set

    lateinit var journalDataStore: JournalDataStore
        private set

    lateinit var database: AppDatabase
        private set

    lateinit var dispatcherProvider: DispatcherProvider
        private set

    lateinit var socketManager: SocketManager
        private set

    lateinit var configRepository: ConfigRepository
        private set

    lateinit var authRepository: AuthRepository
        private set

    lateinit var feedRepository: FeedRepository
        private set

    lateinit var journalRepository: JournalRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // Lưu lại tham chiếu tĩnh để truy cập nhanh khi cần ApplicationContext
        instance = this

        // 1. Khởi tạo tầng lưu trữ cục bộ (Local Storage) và Threading
        tokenStore = TokenStore(this)
        settingsDataStore = SettingsDataStore(this)
        journalDataStore = JournalDataStore(this)
        database = AppDatabase.getInstance(this)
        dispatcherProvider = DefaultDispatcherProvider()
        socketManager =
            SocketManager(
                tokenStore = tokenStore,
                settingsStorage = settingsDataStore,
                notificationDao = database.notificationDao(),
                baseUrlProvider = { BuildConfig.BASE_URL },
                notificationScopeProvider = {
                    val json = settingsDataStore.getSavedUserJson()
                    val user =
                        json?.let {
                            try {
                                Gson().fromJson(it, User::class.java)
                            } catch (_: Exception) {
                                null
                            }
                        }
                    user?.let {
                        NotificationScope(it.id.toString(), settingsDataStore.getDataSource())
                    }
                },
            )

        // 2. Khởi tạo OkHttpClient thông qua NetworkModule:
        // - authenticatedClient: Có gắn AuthInterceptor, TokenAuthenticator phục vụ các API cần xác
        // thực
        // - configClient: Client gọn nhẹ không gắn xác thực, chuyên dùng tải cấu hình ban đầu
        val authenticatedClient =
            NetworkModule.provideOkHttpClient(this, tokenStore, settingsDataStore)
        val configClient = NetworkModule.provideConfigOkHttpClient(this, settingsDataStore)

        // 3. Khởi tạo các instance Retrofit tương ứng với từng nguồn dữ liệu (MB Server, DummyJSON)
        val mainRetrofit = NetworkModule.provideRetrofit(authenticatedClient)
        val dummyJsonRetrofit = NetworkModule.provideDummyJsonRetrofit(authenticatedClient)
        val configRetrofit = NetworkModule.provideRetrofit(configClient)

        // 4. Tạo các Retrofit API service interface
        val configApi = configRetrofit.create(ConfigApi::class.java)
        val authApi = mainRetrofit.create(AuthApi::class.java)
        val dummyJsonApi = dummyJsonRetrofit.create(DummyJsonApi::class.java)
        val userApi = mainRetrofit.create(UserApi::class.java)
        val feedApi = mainRetrofit.create(FeedApi::class.java)

        // 5. Khởi tạo Repositories, inject API và Local Storage tương ứng
        configRepository =
            ConfigRepositoryImpl(configApi, settingsDataStore, dispatcher = dispatcherProvider.io)
        feedRepository =
            FeedRepositoryImpl(
                feedApi = feedApi,
                dummyJsonApi = dummyJsonApi,
                settingsDataStore = settingsDataStore,
                tokenStore = tokenStore,
                dispatcher = dispatcherProvider.io,
            )
        authRepository =
            AuthRepositoryImpl(
                authApi = authApi,
                userApi = userApi,
                dummyJsonApi = dummyJsonApi,
                tokenStore = tokenStore,
                settingsDataStore = settingsDataStore,
                dispatcher = dispatcherProvider.io,
                invalidateFeedCache = { feedRepository.clearCache() },
            )
        journalRepository =
            JournalRepositoryImpl(
                context = this,
                journalDao = database.journalDao(),
                journalDataStore = journalDataStore,
                dispatcher = dispatcherProvider.io,
            )
    }

    companion object {
        /** Singleton instance toàn cục để truy cập nhanh Application Context */
        lateinit var instance: MomentBoxApp
            private set
    }
}
