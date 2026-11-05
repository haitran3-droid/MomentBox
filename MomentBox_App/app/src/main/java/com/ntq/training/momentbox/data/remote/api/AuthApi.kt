package com.ntq.training.momentbox.data.remote.api

import com.ntq.training.momentbox.core.network.ApiResponse
import com.ntq.training.momentbox.data.remote.dto.AuthResponseDto
import com.ntq.training.momentbox.data.remote.dto.LoginRequest
import com.ntq.training.momentbox.data.remote.dto.LogoutRequest
import com.ntq.training.momentbox.data.remote.dto.RefreshRequest
import com.ntq.training.momentbox.data.remote.dto.TokenRefreshResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface AuthApi {
    @POST("v1/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<ApiResponse<AuthResponseDto>>

    @POST("v1/auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<ApiResponse<TokenRefreshResponseDto>>

    @POST("v1/auth/logout")
    suspend fun logout(
        @Body request: LogoutRequest,
        @Header("Authorization") authorization: String? = null
    ): Response<ApiResponse<Unit>>
}