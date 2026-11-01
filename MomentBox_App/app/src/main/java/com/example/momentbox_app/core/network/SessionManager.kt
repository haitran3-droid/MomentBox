package com.example.momentbox_app.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class SessionState {
    AUTHENTICATED, EXPIRED
}

object SessionManager {
    /** Bộ đếm nguyên tử số lần refresh token thành công (thread-safe) */
    private val refreshes = AtomicLong()
    fun refreshCount(): Long = refreshes.get()
    fun recordTokenRefresh() {
        refreshes.incrementAndGet()
    }

    private val _sessionState = MutableStateFlow(SessionState.AUTHENTICATED)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _expirationRevision = MutableStateFlow(0L)
    val expirationRevision: StateFlow<Long> = _expirationRevision.asStateFlow()

    /**
     * Thông báo phiên làm việc đã hết hạn.
     * Cập nhật trạng thái thành EXPIRED và tăng revision để đánh thức các UI Collector.
     */
    fun notifySessionExpired() {
        _sessionState.value = SessionState.EXPIRED
        _expirationRevision.value += 1
    }

    /**
     * Khôi phục trạng thái phiên về AUTHENTICATED khi người dùng đăng nhập thành công.
     */
    fun resetSession() {
        _sessionState.value = SessionState.AUTHENTICATED
    }
}