package com.ntq.training.momentbox.data.remote.api

import com.ntq.training.momentbox.core.network.SessionRequest
import com.ntq.training.momentbox.data.remote.dto.DummyJsonCommentsResponse
import com.ntq.training.momentbox.data.remote.dto.DummyJsonLoginRequest
import com.ntq.training.momentbox.data.remote.dto.DummyJsonLoginResponse
import com.ntq.training.momentbox.data.remote.dto.DummyJsonMeResponse
import com.ntq.training.momentbox.data.remote.dto.DummyJsonPostsResponse
import com.ntq.training.momentbox.data.remote.dto.DummyJsonRefreshRequest
import com.ntq.training.momentbox.data.remote.dto.DummyJsonRefreshResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
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

    @GET("posts")
    suspend fun getPosts(
        @Query("limit") limit: Int,
        @Query("skip") skip: Int,
        @Query("sortBy") sortBy: String = "id",
        @Query("order") order: String = "desc"
    ): Response<DummyJsonPostsResponse>

    @GET("posts/{id}/comments")
    suspend fun getPostComments(
        @Path("id") id: Long
    ): Response<DummyJsonCommentsResponse>
}