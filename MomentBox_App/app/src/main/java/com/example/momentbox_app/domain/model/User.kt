package com.example.momentbox_app.domain.model

data class User(
    val id: Long,
    val username: String,
    val displayName: String,
    val email: String?,
    val avatarUrl: String?,
    val verified: Boolean = false,
    val bio: String? = null,
    val location: String? = null,
    val postCount: Int = 0,
    val followerCount: Int = 0,
    val followingCount: Int = 0
)
