package com.example.momentbox_app.core.network
import android.content.Context
import com.example.momentbox_app.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
class MockInterceptor(
    private val context: Context,
    private val settingsDataStore: SettingsDataStore
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // Kiểm tra xem người dùng có đang chọn chế độ Offline hay không
        val dataSource = runBlocking { settingsDataStore.getDataSource() }
        if (dataSource != SettingsDataStore.SOURCE_OFFLINE) {
            // Không phải Offline -> Cho qua để gửi lên server thật
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val path = request.url.encodedPath
        if (BuildConfig.DEBUG) {
            val delay = runBlocking { settingsDataStore.getDebugDelay() }.coerceIn(0, 10_000)
            if (delay > 0) Thread.sleep(delay.toLong())
        }

        val mockJson = when {
            path.endsWith("/config") -> """
                {
                  "success": true,
                  "data": {
                    "version": "1.0.0",
                    "minSupportedVersionCode": 1,
                    "serverTime": "2026-10-26T10:00:00.000Z",
                    "features": { "offline": true }
                  }
                }
            """.trimIndent()
            path.endsWith("/auth/login") -> """
                {
                  "success": true,
                  "data": {
                    "accessToken": "at_offline_demo_token_12345",
                    "refreshToken": "rt_offline_demo_token_67890",
                    "expiresIn": 900,
                    "user": {
                      "id": 1,
                      "username": "offline_user",
                      "displayName": "Người Dùng Offline",
                      "avatarUrl": null,
                      "email": "offline@momentbox.dev"
                    }
                  }
                }
            """.trimIndent()
            path.endsWith("/auth/refresh") -> """
                {
                  "success": true,
                  "data": {
                    "accessToken": "at_offline_refreshed_token",
                    "refreshToken": "rt_offline_refreshed_token",
                    "expiresIn": 900
                  }
                }
            """.trimIndent()
            path.endsWith("/me") -> """
                {
                  "success": true,
                  "data": {
                    "id": 1,
                    "username": "offline_user",
                    "displayName": "Người Dùng Offline",
                    "avatarUrl": null,
                    "email": "offline@momentbox.dev",
                    "bio": "Đang dùng chế độ offline",
                    "postCount": 0,
                    "followerCount": 0,
                    "followingCount": 0
                  }
                }
            """.trimIndent()
            else -> """
                {
                  "success": true,
                  "data": {}
                }
            """.trimIndent()
        }

        return Response.Builder()
            .code(200)
            .message("OK (Offline Mock)")
            .protocol(Protocol.HTTP_1_1)
            .request(request)
            .body(mockJson.toResponseBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()
    }
}