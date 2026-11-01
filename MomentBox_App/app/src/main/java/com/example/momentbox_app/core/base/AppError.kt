package com.example.momentbox_app.core.base

sealed interface AppError {
    val message: String
    /** Lỗi dữ liệu nhập không hợp lệ từ phía người dùng (Form validation) */
    data class Validation(
        override val message: String,
        val fields: List<String> = emptyList()
    ) : AppError

    /** Lỗi sai tên đăng nhập hoặc mật khẩu (HTTP 401 khi login) */
    data class InvalidCredentials(
        override val message: String
    ) : AppError

    /** Lỗi chưa được xác thực hoặc không có quyền truy cập endpoint được bảo vệ */
    data class Unauthorized(
        override val message: String
    ) : AppError

    /** Lỗi Refresh Token hết hạn hoặc không thể cấp mới phiên đăng nhập */
    data class TokenExpired(
        override val message: String
    ) : AppError

    /** Lỗi kết nối mạng: Mất mạng, DNS không phân giải được, không kết nối được server */
    data class Network(
        override val message: String,
        val cause: Throwable? = null
    ) : AppError

    /** Lỗi quá thời gian chờ kết nối hoặc đọc dữ liệu từ server (Timeout) */
    data class Timeout(
        override val message: String
    ) : AppError

    /** Lỗi từ phía máy chủ (Mã HTTP 5xx: 500, 502, 503...) */
    data class Server(
        val code: Int,
        override val message: String
    ) : AppError

    /** Lỗi không xác định hoặc ngoại lệ bất thường chưa được phân loại */
    data class Unknown(
        override val message: String,
        val cause: Throwable? = null
    ) : AppError
}