package com.example.momentbox_app.feature.auth

import com.example.momentbox_app.domain.model.User

data class LoginFormState(
    val email: String = "",
    val emailError: String? = null,
    val password: String = "",
    val passwordError: String? = null,
    val rememberMe: Boolean = true,
    val isFormValid: Boolean = false
)

/**
 * [LoginUiState] - Trạng thái giao diện tổng thể của màn hình đăng nhập (Presentation State Machine).
 */
sealed interface LoginUiState {
    /** Trạng thái rảnh rỗi (chờ người dùng thao tác) */
    data object Idle : LoginUiState
    /** Đang gửi API đăng nhập qua mạng, hiển thị ProgressBar và vô hiệu hóa nút bấm */
    data object Loading : LoginUiState
    /** Đăng nhập thành công, bọc thông tin [user] để chuyển tiếp sang MainActivity */
    data class Success(val user: User) : LoginUiState
    /** Đăng nhập thất bại, chứa thông điệp lỗi [message] và cờ [isNetworkError] để hiển thị nút Thử lại (Retry) */
    data class Error(val message: String, val isNetworkError: Boolean = false) : LoginUiState
}