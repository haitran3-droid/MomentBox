package com.ntq.training.momentbox.data.remote.api

import com.ntq.training.momentbox.core.network.ApiResponse
import com.ntq.training.momentbox.core.network.SessionRequest
import com.ntq.training.momentbox.data.remote.dto.UserDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Tag

interface UserApi {
    @GET("v1/me")
    suspend fun getMe(@Tag session: SessionRequest? = null): Response<ApiResponse<UserDto>>
}