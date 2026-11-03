package com.example.momentbox_app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class UserDto (
    @SerializedName("id") val id: Long,
    @SerializedName("username") val username: String,
    @SerializedName("displayName") val displayName: String?,
    @SerializedName("email") val email: String? = null,
    @SerializedName("avatar") val avatar: String? = null,
    @SerializedName("avatarUrl") val avatarUrl: String? = null,
    @SerializedName("verified") val verified: Boolean? = false,
    @SerializedName("bio") val bio: String? = null,
    @SerializedName("location") val location: String? = null,
    @SerializedName("postCount") val postCount: Int? = 0,
    @SerializedName("followerCount") val followerCount: Int? = 0,
    @SerializedName("followingCount") val followingCount: Int? = 0
)