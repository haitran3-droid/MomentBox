package com.example.momentbox_app.core.network

import com.example.momentbox_app.data.local.prefs.TokenStorage
import okhttp3.Interceptor
import okhttp3.Response
import java.io.InterruptedIOException

class AuthInterceptor(private val tokenStore: TokenStorage) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath

        // 1. Kiểm tra nếu là endpoint công khai thì cho qua ngay, không gắn Authorization
        val isPublicEndpoint = path.endsWith("/auth/login") ||
                path.endsWith("/auth/refresh") ||
                path.endsWith("/auth/logout") || path.endsWith("/config")

        if (isPublicEndpoint) {
            return chain.proceed(request)
        }

        // 2. Đọc nhanh snapshot phiên từ RAM (không block I/O đĩa)
        val snapshot = tokenStore.snapshotSync()
        val token = snapshot.accessToken
        val session = request.tag(SessionRequest::class.java)

        // 3. Nếu request đã có thẻ session từ trước (ví dụ do retry)
        if (session != null) {
            // Nếu phiên đã thay đổi (đã đăng xuất hoặc đổi tài khoản), hủy request ngay lập tức
            if (session.generation != snapshot.generation) throw InterruptedIOException("Session changed")
            return chain.proceed(request.newBuilder()
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build())
        }

        // 4. Nếu request đã có header Authorization tự đặt
        val explicitAuthorization = request.header("Authorization")
        if (explicitAuthorization != null) {
            val generation = if (explicitAuthorization == "Bearer $token") snapshot.generation else -1L
            return chain.proceed(request.newBuilder()
                .tag(SessionRequest::class.java, SessionRequest(generation))
                .build())
        }

        // 5. Gắn Bearer Token và SessionRequest tag cho request thông thường
        return if (!token.isNullOrBlank()) {
            val authenticatedRequest = request.newBuilder()
                .header("Authorization", "Bearer $token")
                .tag(SessionRequest::class.java, SessionRequest(snapshot.generation))
                .build()
            chain.proceed(authenticatedRequest)
        } else {
            // Chưa có token (khách vãng lai), cho request tiếp tục mà không có header
            chain.proceed(request)
        }
    }
}