package com.example.momentbox_app.core.network

import androidx.appcompat.app.AppCompatDelegate
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale

class LanguageInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // 1. Lấy danh sách ngôn ngữ đang áp dụng của riêng ứng dụng
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val languageTag = if (!appLocales.isEmpty) {
            appLocales.get(0)?.language ?: "vi"
        } else {
            Locale.getDefault().language.ifBlank { "vi" }
        }

        // 2. Gắn header Accept-Language vào request
        val newRequest = request.newBuilder()
            .header("Accept-Language", languageTag)
            .build()

        return chain.proceed(newRequest)
    }
}