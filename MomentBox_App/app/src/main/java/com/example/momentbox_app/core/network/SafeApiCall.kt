package com.example.momentbox_app.core.network

import com.example.momentbox_app.core.base.AppError
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

suspend fun <T> safeApiCall(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    gson: Gson = Gson(),
    apiCall: suspend () -> Response<ApiResponse<T>>
): Result<T> = withContext(dispatcher) {
    try {
        // Thực thi gọi hàm Retrofit suspend
        val response = apiCall()
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null && body.success && body.data != null) {
                // Trường hợp 1: Thành công và có dữ liệu hợp lệ
                Result.success(body.data)
            } else if (body != null && !body.success && body.error != null) {
                // Trường hợp 2: HTTP 200 nhưng server báo lỗi nghiệp vụ (success = false)
                Result.failure(ApiException(mapApiErrorToAppError(body.error, response.code())))
            } else {
                // Trường hợp 3: Response bị dị dạng (thiếu cả data lẫn error)
                Result.failure(ApiException(AppError.Unknown("Malformed response: missing data or success flag is false")))
            }
        } else {
            // Trường hợp 4: Mã HTTP thất bại (4xx, 5xx), đọc errorBody để lấy chi tiết lỗi JSON
            val errorBodyString = try {
                response.errorBody()?.string()
            } catch (e: Exception) {
                null
            }

            val parsedError = if (!errorBodyString.isNullOrBlank()) {
                try {
                    val envelope = gson.fromJson(errorBodyString, ApiResponse::class.java)
                    envelope?.error
                } catch (e: Exception) {
                    null
                }
            } else null

            if (parsedError != null) {
                Result.failure(ApiException(mapApiErrorToAppError(parsedError, response.code())))
            } else {
                val message = response.message().ifBlank { "HTTP ${response.code()} Error" }
                val appError = when (response.code()) {
                    401 -> AppError.Unauthorized(message)
                    in 400..499 -> AppError.Server(response.code(), message)
                    in 500..599 -> AppError.Server(
                        response.code(),
                        "Lỗi hệ thống (${response.code()})"
                    )

                    else -> AppError.Unknown("HTTP ${response.code()}: $message")
                }
                Result.failure(ApiException(appError))
            }
        }
    } catch (e: CancellationException) {
        // Bắt buộc rethrow CancellationException để Coroutine hủy bình thường
        throw e
    } catch (e: SocketTimeoutException) {
        Result.failure(ApiException(AppError.Timeout("Yêu cầu hết thời gian chờ (Timeout)"), e))
    } catch (e: IOException) {
        Result.failure(ApiException(AppError.Network("Không thể kết nối đến máy chủ", e), e))
    } catch (e: Exception) {
        Result.failure(ApiException(AppError.Unknown(e.message ?: "Lỗi không xác định", e), e))
    }
}

/**
 * Ngoại lệ bọc đối tượng [AppError] để truyền qua các tầng dưới dạng [Result.failure]
 */
class ApiException(
    val error: AppError,
    cause: Throwable? = null
) : Exception(error.message, cause)

/**
 * Ánh xạ đối tượng lỗi từ JSON [ApiError] sang [AppError] của tầng Domain
 */
fun mapApiErrorToAppError(error: ApiError, httpCode: Int = 400): AppError {
    return when (error.code) {
        "VALIDATION_ERROR" -> {
            val fields = (error.details?.get("fields") as? List<*>)?.mapNotNull { it?.toString() }
                ?: emptyList()
            AppError.Validation(error.message, fields)
        }

        "INVALID_CREDENTIALS" -> AppError.InvalidCredentials(error.message)
        "UNAUTHORIZED" -> AppError.Unauthorized(error.message)
        "TOKEN_EXPIRED" -> AppError.TokenExpired(error.message)
        "INTERNAL" -> AppError.Server(httpCode, error.message)
        else -> AppError.Server(httpCode, error.message)
    }
}