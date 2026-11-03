package com.example.momentbox_app.core.network

import com.example.momentbox_app.data.local.prefs.SettingsStorage
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import okio.ByteString.Companion.encodeUtf8

class DebugHeaderInterceptor(private val settingsDataStore: SettingsStorage) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()

        runBlocking {
            val session = request.tag(SessionRequest::class.java)
            val force401 = session != null && settingsDataStore.consumeDebugForce401()
            if (force401) {
                // Thay thế bằng token giả định hết hạn để ép server trả về HTTP 401 thật
                builder.header("Authorization", "Bearer momentbox-debug-expired-token")
                builder.tag(
                    SessionRequest::class.java, session!!.copy(
                        forceRefresh = true,
                        forcedTokenFingerprint = request.header("Authorization")?.encodeUtf8()
                            ?.sha256()?.hex()
                    )
                )
            }

            // Gắn độ trễ mạng giả lập nếu được cấu hình trong cài đặt debug
            val delayMs = settingsDataStore.getDebugDelay()
            if (delayMs > 0) {
                builder.header("X-Simulate-Delay", delayMs.toString())
            }
        }

        return chain.proceed(builder.build())
    }
}