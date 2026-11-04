package com.ntq.training.momentbox.data.remote.api

import com.ntq.training.momentbox.core.network.ApiResponse
import com.ntq.training.momentbox.data.remote.dto.ConfigDto
import retrofit2.Response
import retrofit2.http.GET

interface ConfigApi {
    @GET("v1/config")
    suspend fun getConfig(): Response<ApiResponse<ConfigDto>>
}