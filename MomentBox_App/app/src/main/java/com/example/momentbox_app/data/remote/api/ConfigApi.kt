package com.example.momentbox_app.data.remote.api

import com.example.momentbox_app.core.network.ApiResponse
import com.example.momentbox_app.data.remote.dto.ConfigDto
import retrofit2.Response
import retrofit2.http.GET

interface ConfigApi {
    @GET("v1/config")
    suspend fun getConfig(): Response<ApiResponse<ConfigDto>>
}