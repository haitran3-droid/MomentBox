package com.ntq.training.momentbox.core.network

data class SessionRequest(
    val generation: Long,
    val forceRefresh: Boolean = false,
    val forcedTokenFingerprint: String? = null
)
