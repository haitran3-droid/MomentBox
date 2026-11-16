package com.ntq.training.momentbox.data.remote.api

import com.ntq.training.momentbox.core.network.ApiResponse
import com.ntq.training.momentbox.core.network.SessionRequest
import com.ntq.training.momentbox.data.remote.dto.PostDto
import com.ntq.training.momentbox.data.remote.dto.UserDto
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

interface ProfileApi {
    @GET("v1/users/suggestions")
    suspend fun suggestions(@Tag session: SessionRequest?): Response<ApiResponse<List<UserDto>>>

    @GET("v1/me")
    suspend fun me(@Tag session: SessionRequest?): Response<ApiResponse<UserDto>>

    @GET("v1/users/{id}")
    suspend fun user(
        @Path("id") userId: Long,
        @Tag session: SessionRequest?
    ): Response<ApiResponse<UserDto>>

    @GET("v1/users/{id}/posts")
    suspend fun posts(
        @Path("id") userId: Long,
        @Query("type") type: String?,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
        @Tag session: SessionRequest?
    ): Response<ApiResponse<List<PostDto>>>

    @GET("v1/me/bookmarks")
    suspend fun bookmarks(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
        @Tag session: SessionRequest?
    ): Response<ApiResponse<List<PostDto>>>

    @PATCH("v1/me")
    suspend fun edit(
        @Body fields: ProfileEditRequest,
        @Tag session: SessionRequest?
    ): Response<ApiResponse<UserDto>>

    @Multipart
    @POST("v1/me/avatar")
    suspend fun avatar(
        @Part avatar: MultipartBody.Part,
        @Tag session: SessionRequest?
    ): Response<ApiResponse<UserDto>>
}

/** Payload chỉ ba trường BA cho phép sửa; không gửi ID/quyền/updatedAt tưởng tượng. */
data class ProfileEditRequest(val displayName: String, val bio: String, val location: String)