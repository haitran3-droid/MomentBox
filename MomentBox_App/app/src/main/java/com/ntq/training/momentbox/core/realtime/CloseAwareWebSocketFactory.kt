package com.ntq.training.momentbox.core.realtime

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

internal class CloseAwareWebSocketFactory(private val delegate: WebSocket.Factory) :
    WebSocket.Factory {
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
        delegate.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) =
                listener.onOpen(webSocket, response)

            override fun onMessage(webSocket: WebSocket, text: String) =
                listener.onMessage(webSocket, text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                listener.onMessage(webSocket, bytes)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosing(webSocket, code, reason)
                // 1005 là sentinel khi peer gửi close frame không kèm status; không được gửi nó lên wire.
                val replyCode =
                    if ((code in 1000..1014 && code !in 1004..1006) || code in 3000..4999) code else 1000
                webSocket.close(replyCode, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                listener.onClosed(webSocket, code, reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.onFailure(webSocket, t, response)
        })
}