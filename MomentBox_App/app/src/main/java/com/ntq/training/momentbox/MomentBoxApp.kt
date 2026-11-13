package com.ntq.training.momentbox

import android.content.res.Configuration
import com.ntq.training.momentbox.core.notification.ChatActionApi
import com.ntq.training.momentbox.core.notification.ChatNotifications
import com.ntq.training.momentbox.core.notification.NotificationChannels
import com.ntq.training.momentbox.core.notification.NotificationReplySender
import com.ntq.training.momentbox.data.local.prefs.ReminderStore
import com.ntq.training.momentbox.data.remote.api.ProfileApi
import com.ntq.training.momentbox.data.repository.ProfileRepository
import com.ntq.training.momentbox.feature.reminder.AndroidReminderAlarms
import com.ntq.training.momentbox.feature.reminder.ReminderDelivery
import com.ntq.training.momentbox.feature.reminder.ReminderNotifications
import com.ntq.training.momentbox.feature.reminder.ReminderScheduler
import com.ntq.training.momentbox.feature.settings.ApplicationTheme
import com.ntq.training.momentbox.feature.settings.LogoutCoordinator
import android.app.ActivityManager
import android.app.Application
import android.os.Build
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.gson.Gson
import android.os.Process
import com.ntq.training.momentbox.core.coroutines.DefaultDispatcherProvider
import com.ntq.training.momentbox.core.coroutines.DispatcherProvider
import com.ntq.training.momentbox.core.network.NetworkModule
import com.ntq.training.momentbox.core.network.NetworkLogInterceptor
import com.ntq.training.momentbox.core.realtime.ChatRuntime
import com.ntq.training.momentbox.core.realtime.NotificationScope
import com.ntq.training.momentbox.core.realtime.SocketManager
import com.ntq.training.momentbox.data.local.db.AppDatabase
import com.ntq.training.momentbox.data.local.prefs.JournalDataStore
import com.ntq.training.momentbox.data.local.prefs.PostDraftStore
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.local.prefs.TokenStore
import com.ntq.training.momentbox.data.remote.api.AuthApi
import com.ntq.training.momentbox.data.remote.api.ChatApi
import com.ntq.training.momentbox.data.remote.api.ChatUploadApi
import com.ntq.training.momentbox.data.remote.api.ConfigApi
import com.ntq.training.momentbox.data.remote.api.DummyJsonApi
import com.ntq.training.momentbox.data.remote.api.FeedApi
import com.ntq.training.momentbox.data.remote.api.UserApi
import com.ntq.training.momentbox.data.repository.AuthRepository
import com.ntq.training.momentbox.data.repository.AuthRepositoryImpl
import com.ntq.training.momentbox.data.repository.ChatMediaRepository
import com.ntq.training.momentbox.data.repository.ConfigRepository
import com.ntq.training.momentbox.data.repository.ConfigRepositoryImpl
import com.ntq.training.momentbox.data.repository.FeedRepository
import com.ntq.training.momentbox.data.repository.FeedRepositoryImpl
import com.ntq.training.momentbox.data.repository.JournalRepository
import com.ntq.training.momentbox.data.repository.JournalRepositoryImpl
import com.ntq.training.momentbox.data.repository.UploadRepository
import com.ntq.training.momentbox.domain.model.User
import com.ntq.training.momentbox.feature.player.PlayerAudioBridge
import com.ntq.training.momentbox.feature.player.PlayerLibraryApi
import com.ntq.training.momentbox.feature.player.PlayerLibraryRepository
import java.time.Clock

class MomentBoxApp : Application() {

    /** Clock injectable để kiểm chứng ngày/kỳ; production luôn dùng đồng hồ hệ thống. */
    var journalClock: Clock = Clock.systemDefaultZone()
        internal set

    /** S21 dùng cùng authenticated Retrofit của main; :player không khởi tạo repository này. */
    lateinit var profileRepository: ProfileRepository private set

    /** S20 dùng cùng một store/controller trong main process. Lazy không mở prefs ở :player;
     * system receiver chỉ lập lại lịch, không kéo theo Activity hoặc khởi động audio nền. */
    val reminderStore by lazy { ReminderStore(this) }
    val reminderScheduler by lazy {
        ReminderScheduler(
            reminderStore,
            AndroidReminderAlarms(this)
        )
    }

    val reminderNotifications by lazy {
        ReminderNotifications(
            this
        )
    }
    val reminderDelivery by lazy {
        ReminderDelivery(
            reminderStore, reminderScheduler,
            hasWritten = { window ->
                database.journalDao().countNonDeletedInRange(
                    window.start.toEpochMilli(),
                    window.endExclusive.toEpochMilli()
                ) > 0
            },
            present = reminderNotifications::show
        )
    }

    val logoutCoordinator by lazy {
        LogoutCoordinator(
            this
        )
    }

    /** M4: job upload sống theo Application, draft bài viết tách riêng Nhật ký. */
    val postDraftStore by lazy { PostDraftStore(this) }
    val uploadRepository by lazy { UploadRepository(this) }
    // === Tầng Local Storage & Coroutines ===
    /** Quản lý phiên trong RAM và Preferences DataStore; implementation hiện không mã hóa token. */
    lateinit var tokenStore: TokenStore private set

    /** Lưu onboarding, nguồn API, config/user cache và tùy chọn debug. Locale do AppCompat quản lý riêng. */
    lateinit var settingsDataStore: SettingsDataStore private set

    /** Quản lý lưu trữ trạng thái bản nháp và bộ lọc nhật ký M3 */
    lateinit var journalDataStore: JournalDataStore private set

    /** Cơ sở dữ liệu Room cục bộ SQLite (momentbox.db) */
    lateinit var database: AppDatabase private set

    /** Trừu tượng hóa Dispatchers (IO, Main, Default) giúp dễ thay thế TestDispatcher khi viết Unit Test */
    lateinit var dispatcherProvider: DispatcherProvider private set

    lateinit var playerAudioBridge: PlayerAudioBridge private set
    lateinit var playerLibraryRepository: PlayerLibraryRepository private set

    lateinit var chatMediaRepository: ChatMediaRepository private set
    lateinit var chatRuntime: ChatRuntime private set
    lateinit var chatNotifications: ChatNotifications private set
    lateinit var notificationReplySender: NotificationReplySender private set

    // === Tầng Realtime & Tương tác mạng ===
    /** Quản lý kết nối Socket.IO hai chiều thời gian thực */
    lateinit var socketManager: SocketManager private set

    // === Tầng Repository (Single Source of Truth) ===
    /** Điều phối dữ liệu cấu hình app, phiên bản tối thiểu từ server */
    lateinit var configRepository: ConfigRepository private set

    /** Điều phối xác thực người dùng, đăng nhập, refresh token và quản lý phiên */
    lateinit var authRepository: AuthRepository private set

    /** Điều phối dữ liệu Bảng tin, bài viết, story, bình luận và tương tác */
    lateinit var feedRepository: FeedRepository private set

    /** Điều phối dữ liệu Nhật ký cá nhân, tệp đính kèm và thống kê M3 */
    lateinit var journalRepository: JournalRepository internal set

    override fun onCreate() {
        super.onCreate()
        // Lưu lại tham chiếu tĩnh để truy cập nhanh khi cần ApplicationContext
        instance = this
        // Application cũng được Android tạo trong :player. Hai process không chung RAM;
        // player chỉ khởi tạo service/IPC của nó, tuyệt đối không mở Room, token hay SocketManager.
        val process = if (Build.VERSION.SDK_INT >= 28) getProcessName() else {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        if (process == packageName + ":player") return
        NotificationChannels.ensure(this)


        // 1. Khởi tạo tầng lưu trữ cục bộ (Local Storage) và Threading
        tokenStore = TokenStore(this)
        if (BuildConfig.DEBUG) {
            var logGeneration = tokenStore.getSessionGeneration()
            tokenStore.addSnapshotListener { snapshot ->
                if (logGeneration != snapshot.generation) {
                    logGeneration = snapshot.generation
                    NetworkLogInterceptor.clearLogs()
                }
            }
        }
        settingsDataStore = SettingsDataStore(this)
        ApplicationTheme.observe(settingsDataStore)
        journalDataStore = JournalDataStore(this)
        database = AppDatabase.getInstance(this)
        dispatcherProvider = DefaultDispatcherProvider()
        socketManager = SocketManager(
            tokenStore = tokenStore,
            settingsStorage = settingsDataStore,
            notificationDao = database.notificationDao(),
            baseUrlProvider = { BuildConfig.BASE_URL },
            notificationScopeProvider = {
                val json = settingsDataStore.getSavedUserJson()
                val user = json?.let {
                    try {
                        Gson()
                            .fromJson(it, User::class.java)
                    } catch (_: Exception) {
                        null
                    }
                }
                user?.let {
                    NotificationScope(
                        it.id.toString(),
                        settingsDataStore.getDataSource()
                    )
                }
            }
        )

        // 2. Khởi tạo OkHttpClient thông qua NetworkModule:
        // - authenticatedClient: Có gắn AuthInterceptor, TokenAuthenticator phục vụ các API cần xác thực
        // - configClient: Client gọn nhẹ không gắn xác thực, chuyên dùng tải cấu hình ban đầu
        val authenticatedClient =
            NetworkModule.provideOkHttpClient(this, tokenStore, settingsDataStore)
        val configClient = NetworkModule.provideConfigOkHttpClient(this, settingsDataStore)

        // 3. Khởi tạo các instance Retrofit tương ứng với từng nguồn dữ liệu (MB Server, DummyJSON)
        val mainRetrofit = NetworkModule.provideRetrofit(authenticatedClient)
        chatRuntime = ChatRuntime(
            tokenStore, settingsDataStore, socketManager, database,
            mainRetrofit.create(ChatApi::class.java)
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(chatRuntime)
        notificationReplySender =
            NotificationReplySender(
                chatRuntime, socketManager, database,
                mainRetrofit.create(ChatActionApi::class.java)
            )
        chatMediaRepository = ChatMediaRepository(
            this, chatRuntime,
            mainRetrofit.create(ChatUploadApi::class.java)
        )
        chatRuntime.retryMedia = chatMediaRepository::retry
        chatNotifications = ChatNotifications(this)

        val dummyJsonRetrofit = NetworkModule.provideDummyJsonRetrofit(authenticatedClient)
        val configRetrofit = NetworkModule.provideRetrofit(configClient)

        // 4. Tạo các Retrofit API service interface
        val configApi = configRetrofit.create(ConfigApi::class.java)
        val authApi = mainRetrofit.create(AuthApi::class.java)
        val dummyJsonApi = dummyJsonRetrofit.create(DummyJsonApi::class.java)
        val userApi = mainRetrofit.create(UserApi::class.java)
        val feedApi =
            mainRetrofit.create(FeedApi::class.java)

        // 5. Khởi tạo Repositories, inject API và Local Storage tương ứng
        configRepository =
            ConfigRepositoryImpl(configApi, settingsDataStore, dispatcher = dispatcherProvider.io)
        feedRepository = FeedRepositoryImpl(
            feedApi = feedApi,
            dummyJsonApi = dummyJsonApi,
            settingsDataStore = settingsDataStore,
            tokenStore = tokenStore,
            dispatcher = dispatcherProvider.io
        )
        authRepository = AuthRepositoryImpl(
            authApi = authApi,
            userApi = userApi,
            dummyJsonApi = dummyJsonApi,
            tokenStore = tokenStore,
            settingsDataStore = settingsDataStore,
            dispatcher = dispatcherProvider.io,
            invalidateFeedCache = { feedRepository.clearCache()
            if (::profileRepository.isInitialized) profileRepository.clear() },
            profileApi = mainRetrofit.create(ProfileApi::class.java),
            onSessionEstablished = { if (::journalRepository.isInitialized) journalRepository.resumeAfterLogin() }
        )
        profileRepository = ProfileRepository(
            mainRetrofit.create(ProfileApi::class.java),
            tokenStore,
            settingsDataStore,
            dispatcher = dispatcherProvider.io,
            auth = authRepository
        )
        reminderNotifications
        playerAudioBridge =
            PlayerAudioBridge(this).also { it.connect() }
        playerLibraryRepository = PlayerLibraryRepository(
            this,
            mainRetrofit.create(PlayerLibraryApi::class.java)
        )
        journalRepository = JournalRepositoryImpl(
            context = this,
            journalDao = database.journalDao(),
            journalDataStore = journalDataStore,
            dispatcher = dispatcherProvider.io
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Android đổi locale có thể không tạo lại Application; chỉ cập nhật nhãn channel đã có.
        // Không truy cập Room/socket ở đây nên callback vẫn an toàn trong :player.
        if (this::tokenStore.isInitialized)
            NotificationChannels.ensure(this)
    }

    companion object {
        /** Singleton instance toàn cục để truy cập nhanh Application Context */
        lateinit var instance: MomentBoxApp private set
    }
}
