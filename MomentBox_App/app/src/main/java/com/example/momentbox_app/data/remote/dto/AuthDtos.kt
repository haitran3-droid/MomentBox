package com.example.momentbox_app.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO chứa thông tin gửi lên để đăng nhập tài khoản trên MB Server
 */
data class LoginRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String
)

/**
 * DTO chứa Refresh Token gửi lên để xin cấp mới Access Token
 */
data class RefreshRequest(
    @SerializedName("refreshToken") val refreshToken: String
)

/**
 * DTO chứa Refresh Token gửi lên để hủy phiên khi đăng xuất
 */
data class LogoutRequest(
    @SerializedName("refreshToken") val refreshToken: String
)

/**
 * DTO phản hồi thành công sau khi đăng nhập từ MB Server
 */
data class AuthResponseDto(
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("refreshToken") val refreshToken: String,
    @SerializedName("tokenType") val tokenType: String = "Bearer",
    @SerializedName("expiresIn") val expiresIn: Int = 900,
    @SerializedName("user") val user: UserDto? = null
)

/**
 * DTO phản hồi sau khi làm mới token từ MB Server
 */
data class TokenRefreshResponseDto(
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("refreshToken") val refreshToken: String,
    @SerializedName("tokenType") val tokenType: String = "Bearer",
    @SerializedName("expiresIn") val expiresIn: Int = 900
)

// === DTO đặc thù dành riêng cho nguồn bên thứ ba DummyJSON ===

/** Request đăng nhập DummyJSON (dùng username thay vì email) */
data class DummyJsonLoginRequest(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String,
    @SerializedName("expiresInMins") val expiresInMins: Int = 1
)

/** Response đăng nhập DummyJSON (cấu trúc phẳng, không bọc trong envelope) */
data class DummyJsonLoginResponse(
    @SerializedName("id") val id: Long,
    @SerializedName("username") val username: String,
    @SerializedName("email") val email: String,
    @SerializedName("firstName") val firstName: String?,
    @SerializedName("lastName") val lastName: String?,
    @SerializedName("image") val image: String?,
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("refreshToken") val refreshToken: String
)

/** Request làm mới token trên DummyJSON */
data class DummyJsonRefreshRequest(
    @SerializedName("refreshToken") val refreshToken: String,
    @SerializedName("expiresInMins") val expiresInMins: Int = 30
)

/** Response làm mới token từ DummyJSON */
data class DummyJsonRefreshResponse(
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("refreshToken") val refreshToken: String
)

/** Response thông tin cá nhân từ DummyJSON `/auth/me` */
data class DummyJsonMeResponse(
    @SerializedName("id") val id: Long,
    @SerializedName("username") val username: String,
    @SerializedName("email") val email: String,
    @SerializedName("firstName") val firstName: String?,
    @SerializedName("lastName") val lastName: String?,
    @SerializedName("gender") val gender: String?,
    @SerializedName("image") val image: String?
)