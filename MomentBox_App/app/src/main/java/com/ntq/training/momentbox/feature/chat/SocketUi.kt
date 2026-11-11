package com.ntq.training.momentbox.feature.chat

import android.content.Context
import com.ntq.training.momentbox.core.realtime.SocketManager
import com.ntq.training.momentbox.core.realtime.SocketPhase
import com.ntq.training.momentbox.core.realtime.SocketState
import com.ntq.training.momentbox.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalCoroutinesApi::class)
fun socketLabels(context: Context, manager: SocketManager): Flow<Pair<SocketState, String>> =
    manager.state.flatMapLatest { state ->
        flow {
            do {
                val remaining =
                    ((state.retryAtMs?.minus(System.currentTimeMillis()) ?: 0L) + 999).div(1000)
                        .coerceAtLeast(0).toInt()
                val text = when (state.phase) {
                    SocketPhase.CONNECTED -> context.getString(R.string.m5_chat_connected)
                    SocketPhase.CONNECTING -> if (state.attempt > 0) context.getString(
                        R.string.m5_socket_attempt,
                        state.attempt
                    ) else context.getString(R.string.m5_socket_connecting)

                    SocketPhase.WAITING -> if (state.retryAtMs == null) context.getString(
                        R.string.m5_socket_waiting_unknown,
                        state.attempt
                    ) else context.getString(R.string.m5_socket_waiting, state.attempt, remaining)

                    SocketPhase.FAILED -> context.getString(R.string.m5_socket_failed)
                    SocketPhase.AUTH_EXPIRED -> context.getString(R.string.m5_socket_expired)
                    SocketPhase.DISCONNECTED -> context.getString(R.string.m5_chat_offline)
                }
                emit(state to text)
                if (state.phase != SocketPhase.WAITING || remaining == 0) break
                delay(250)
            } while (true)
        }
    }