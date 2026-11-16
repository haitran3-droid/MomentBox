package com.ntq.training.momentbox.data.remote.api

import com.ntq.training.momentbox.core.network.ApiResponse
import com.ntq.training.momentbox.core.network.SessionRequest
import com.ntq.training.momentbox.data.remote.dto.ChatMemberDto
import com.ntq.training.momentbox.data.remote.dto.ChatMessageDto
import com.ntq.training.momentbox.data.remote.dto.ConversationDto
import com.ntq.training.momentbox.data.remote.dto.CreateConversationBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

interface ChatApi {
    @GET("v1/conversations")
    suspend fun conversations(@Tag session: SessionRequest): ApiResponse<List<ConversationDto>>
    @GET("v1/me/following")
    suspend fun following(@Tag session: SessionRequest): ApiResponse<List<ChatMemberDto>>
    @POST("v1/conversations")
    suspend fun create(
        @Body body: CreateConversationBody,
        @Tag session: SessionRequest
    ): ApiResponse<ConversationDto>

    @GET("v1/conversations/{id}/messages")
    suspend fun messages(
        @Path("id") id: Long,
        @Query("before") before: Long? = null,
        @Query("limit") limit: Int = 30,
        @Tag session: SessionRequest
    ): ApiResponse<List<ChatMessageDto>>
}