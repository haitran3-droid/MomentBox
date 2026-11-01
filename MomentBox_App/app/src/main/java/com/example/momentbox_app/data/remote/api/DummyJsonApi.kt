package com.example.momentbox_app.data.remote.api

import com.example.momentbox_app.core.network.SessionRequest
import com.example.momentbox_app.data.remote.dto.DummyJsonLoginRequest
import com.example.momentbox_app.data.remote.dto.DummyJsonLoginResponse
import com.example.momentbox_app.data.remote.dto.DummyJsonMeResponse
import com.example.momentbox_app.data.remote.dto.DummyJsonRefreshRequest
import com.example.momentbox_app.data.remote.dto.DummyJsonRefreshResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Tag

interface DummyJsonApi {
    /** Đăng nhập vào DummyJSON bằng username và password (ví dụ: emilys / emilyspass) */
    @POST("auth/login")
    suspend fun login(
        @Body request: DummyJsonLoginRequest
    ): Response<DummyJsonLoginResponse>

    /** Lấy thông tin tài khoản hiện tại từ DummyJSON với header Authorization */
    @GET("auth/me")
    suspend fun getMe(
        @Header("Authorization") authorization: String,
        @Tag session: SessionRequest? = null
    ): Response<DummyJsonMeResponse>

    /** Làm mới token trên DummyJSON */
    @POST("auth/refresh")
    suspend fun refresh(
        @Body request: DummyJsonRefreshRequest
    ): Response<DummyJsonRefreshResponse>
}