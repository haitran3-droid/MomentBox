package com.example.momentbox_app.core.network

import android.content.Context
import com.example.momentbox_app.data.local.prefs.SettingsDataStore
import com.example.momentbox_app.data.local.prefs.TokenStore
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.ntq.training.momentbox.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {

    private val gson: Gson = GsonBuilder()
        .setLenient()
        .create()

    /** Cung cấp đối tượng Gson dùng chung để phân tích cú pháp JSON */
    fun provideGson(): Gson = gson

    /**
     * OkHttpClient "thô" (Raw Client) không gắn [TokenAuthenticator].
     * Chuyên dùng cho các request cấp thấp như: Refresh Token (`/v1/auth/refresh`), Health check.
     */
    fun provideRawOkHttpClient(context: Context): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(LanguageInterceptor())

        // Chỉ ghi log mạng trong môi trường phát triển (DEBUG), tắt trên RELEASE để bảo mật
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(NetworkLogInterceptor())
        }

        return builder.build()
    }

    /**
     * OkHttpClient chính có đầy đủ Interceptors và [TokenAuthenticator].
     * Sử dụng cho toàn bộ các API nghiệp vụ thông thường của ứng dụng.
     */
    fun provideOkHttpClient(
        context: Context,
        tokenStore: TokenStore,
        settingsDataStore: SettingsDataStore,
        baseUrl: String = BuildConfig.BASE_URL,
        dummyJsonBaseUrl: String = BuildConfig.DUMMY_JSON_URL
    ): OkHttpClient {
        // 1. Tạo raw client để truyền vào Authenticator, tránh vòng lặp đệ quy 401
        val rawClient = provideRawOkHttpClient(context)
        val authenticator = TokenAuthenticator(
            tokenStore = tokenStore,
            settingsDataStore = settingsDataStore,
            rawClient = rawClient,
            baseUrl = baseUrl,
            dummyJsonBaseUrl = dummyJsonBaseUrl,
            gson = gson
        )

        // 2. Thiết lập chuỗi Interceptor theo đúng thứ tự ưu tiên
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Đánh chặn trước nhất nếu đang ở chế độ Offline giả lập
            .addInterceptor(MockInterceptor(context, settingsDataStore))
            // Gắn header đa ngôn ngữ
            .addInterceptor(LanguageInterceptor())
            // Gắn Bearer Token vào header Authorization
            .addInterceptor(AuthInterceptor(tokenStore))
            // Kiểm soát phiên ở tầng mạng thực tế
            .addNetworkInterceptor(SessionGuardInterceptor(tokenStore))

        // 3. Các Interceptor chỉ phục vụ debug/kiểm thử
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(DebugHeaderInterceptor(settingsDataStore))
            builder.addInterceptor(NetworkLogInterceptor())
        }

        // 4. Gắn Authenticator tự động xử lý khi nhận mã lỗi 401
        return builder
            .authenticator(authenticator)
            .build()
    }

    /**
     * OkHttpClient chuyên dụng cho màn hình Splash để gọi API `/v1/config`.
     * Giới hạn timeout chặt chẽ ở mức 3 giây (theo đúng tài liệu BA) để đảm bảo trải nghiệm khởi động nhanh.
     */
    fun provideConfigOkHttpClient(
        context: Context,
        settingsDataStore: SettingsDataStore
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .addInterceptor(MockInterceptor(context, settingsDataStore))
            .addInterceptor(LanguageInterceptor())

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(NetworkLogInterceptor())
        }

        return builder.build()
    }

    /**
     * Khởi tạo Retrofit instance cho máy chủ chính MomentBox (MB Server).
     */
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        baseUrl: String = BuildConfig.BASE_URL
    ): Retrofit {
        val safeBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(safeBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    /**
     * Khởi tạo Retrofit instance cho máy chủ bên thứ ba DummyJSON (nguồn API phụ trợ).
     */
    fun provideDummyJsonRetrofit(
        okHttpClient: OkHttpClient,
        baseUrl: String = BuildConfig.DUMMY_JSON_URL
    ): Retrofit {
        val safeBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(safeBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }
}