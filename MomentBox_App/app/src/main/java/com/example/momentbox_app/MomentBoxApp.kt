package com.example.momentbox_app

import android.app.Application
import com.example.momentbox_app.core.coroutines.DefaultDispatcherProvider
import com.example.momentbox_app.core.coroutines.DispatcherProvider
import com.example.momentbox_app.core.network.NetworkModule
import com.example.momentbox_app.data.local.prefs.SettingsDataStore
import com.example.momentbox_app.data.local.prefs.TokenStore
import com.example.momentbox_app.data.remote.api.AuthApi
import com.example.momentbox_app.data.remote.api.ConfigApi
import com.example.momentbox_app.data.remote.api.DummyJsonApi
import com.example.momentbox_app.data.remote.api.UserApi
import com.example.momentbox_app.data.repository.AuthRepository
import com.example.momentbox_app.data.repository.AuthRepositoryImpl
import com.example.momentbox_app.data.repository.ConfigRepository
import com.example.momentbox_app.data.repository.ConfigRepositoryImpl

class MomentBoxApp : Application() {
    lateinit var tokenStore: TokenStore
        private set
    lateinit var settingsDataStore: SettingsDataStore
        private set
    lateinit var dispatcherProvider: DispatcherProvider
        private set
    lateinit var configRepository: ConfigRepository private set
    lateinit var authRepository: AuthRepository private set
    override fun onCreate() {
        super.onCreate()
        // Lưu lại tham chiếu tĩnh để truy cập nhanh khi cần ApplicationContext
        instance = this

        // 1. Khởi tạo tầng lưu trữ cục bộ (Local Storage) và Threading
        tokenStore = TokenStore(this)
        settingsDataStore = SettingsDataStore(this)
        dispatcherProvider = DefaultDispatcherProvider()

        // 2. Khởi tạo OkHttpClient thông qua NetworkModule:
        // - authenticatedClient: Có gắn AuthInterceptor, TokenAuthenticator phục vụ các API cần xác thực
        // - configClient: Client gọn nhẹ không gắn xác thực, chuyên dùng tải cấu hình ban đầu
        val authenticatedClient = NetworkModule.provideOkHttpClient(this, tokenStore, settingsDataStore)
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

        // 5. Khởi tạo Repositories, inject API và Local Storage tương ứng
        configRepository =
            ConfigRepositoryImpl(configApi, settingsDataStore, dispatcher = dispatcherProvider.io)
        authRepository = AuthRepositoryImpl(
            authApi = authApi,
            userApi = userApi,
            dummyJsonApi = dummyJsonApi,
            tokenStore = tokenStore,
            settingsDataStore = settingsDataStore,
            dispatcher = dispatcherProvider.io
        )
    }

    companion object {
        /** Singleton instance toàn cục để truy cập nhanh Application Context */
        lateinit var instance: MomentBoxApp private set
    }
}