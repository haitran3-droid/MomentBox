package com.example.momentbox_app.core.network

import com.example.momentbox_app.data.local.prefs.TokenStorage
import okhttp3.Interceptor
import okhttp3.Response
import java.io.InterruptedIOException

class SessionGuardInterceptor(private val tokens: TokenStorage) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val session = request.tag(SessionRequest::class.java)
        // Kiểm tra generation lần cuối ngay trước khi gửi byte ra socket mạng
        if (session != null && tokens.getSessionGeneration() != session.generation) {
            throw InterruptedIOException("Session changed before request was sent")
        }
        return chain.proceed(request)
    }
}