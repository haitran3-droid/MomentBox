package com.ntq.training.momentbox.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ConfigDto(
    @SerializedName("version") val version: String? = null,
    @SerializedName("minSupportedVersionCode") val minSupportedVersionCode: Int = 1,
    @SerializedName("serverTime") val serverTime: String? = null,
    @SerializedName("socketUrl") val socketUrl: String? = null
)
