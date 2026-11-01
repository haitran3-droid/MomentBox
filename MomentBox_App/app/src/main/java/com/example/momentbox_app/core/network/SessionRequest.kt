package com.example.momentbox_app.core.network

data class SessionRequest(
    val generation: Long,
    val forceRefresh: Boolean = false,
    val forcedTokenFingerprint: String? = null
)
