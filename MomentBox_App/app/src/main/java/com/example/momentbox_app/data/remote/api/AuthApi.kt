package com.example.momentbox_app.data.remote.api

import com.example.momentbox_app.core.network.ApiResponse
import com.example.momentbox_app.data.remote.dto.AuthResponseDto
import com.example.momentbox_app.data.remote.dto.LoginRequest
import com.example.momentbox_app.data.remote.dto.LogoutRequest
import com.example.momentbox_app.data.remote.dto.RefreshRequest
import com.example.momentbox_app.data.remote.dto.TokenRefreshResponseDto
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