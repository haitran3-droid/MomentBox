package com.example.momentbox_app.data.remote.api

import com.example.momentbox_app.core.network.ApiResponse
import com.example.momentbox_app.core.network.SessionRequest
import com.example.momentbox_app.data.remote.dto.UserDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Tag

interface UserApi {
    @GET("v1/me")
    suspend fun getMe(@Tag session: SessionRequest? = null): Response<ApiResponse<UserDto>>
}