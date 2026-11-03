package com.example.momentbox_app.core.network

import com.google.gson.annotations.SerializedName

data class ApiResponse<T>(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: T? = null,
    @SerializedName("meta") val meta: PageMeta? = null,
    @SerializedName("error") val error: ApiError? = null
)

data class PageMeta(
    @SerializedName("page") val page: Int? = null,
    @SerializedName("pageSize") val pageSize: Int? = null,
    @SerializedName("total") val total: Int? = null,
    @SerializedName("hasMore") val hasMore: Boolean? = null,
    @SerializedName("nextBefore") val nextBefore: Long? = null
)

data class ApiError(
    @SerializedName("code") val code: String,
    @SerializedName("message") val message: String,
    @SerializedName("details") val details: Map<String, Any?>? = null
)
