package com.example.momentbox_app.domain.model

/**
 * [AppConfig] - Mô hình cấu hình hệ thống ở tầng nghiệp vụ (Domain Model).
 */
data class AppConfig(
    val version: String,
    val minSupportedVersionCode: Int,
    val serverTime: String?,
    val socketUrl: String?
)

/**
 * [AuthTokens] - Bộ cặp token xác thực kèm thông tin tài khoản ở tầng nghiệp vụ
 */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
    val user: User? = null
)