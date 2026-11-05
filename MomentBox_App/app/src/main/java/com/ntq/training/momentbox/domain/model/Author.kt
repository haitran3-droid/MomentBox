package com.ntq.training.momentbox.domain.model

data class Author (
    val id: Long,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val verified: Boolean = false
)