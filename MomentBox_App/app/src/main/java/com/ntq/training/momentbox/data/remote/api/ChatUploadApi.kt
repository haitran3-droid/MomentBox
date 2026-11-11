package com.ntq.training.momentbox.data.remote.api

import com.google.gson.annotations.SerializedName
import com.ntq.training.momentbox.core.network.ApiResponse
import com.ntq.training.momentbox.core.network.SessionRequest
import okhttp3.MultipartBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Tag

data class ChatUploadDto(
    @SerializedName("url") val url: String,
    @SerializedName("type") val type: String,
    @SerializedName("size") val size: Long
)

/** Chat upload dùng field file; khác post media/voice và avatar. Tag chặn đổi phiên tại wire. */
interface ChatUploadApi {
    @Multipart
    @POST("v1/uploads")
    suspend fun upload(
        @Part file: MultipartBody.Part,
        @Tag session: SessionRequest
    ): ApiResponse<ChatUploadDto>
}