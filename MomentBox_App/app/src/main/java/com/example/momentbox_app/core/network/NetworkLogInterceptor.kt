package com.example.momentbox_app.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.Response
import okio.Buffer
import com.google.gson.JsonParser
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentLinkedDeque

data class NetworkLogEntry(
    val id: Long = System.currentTimeMillis(),
    val method: String,
    val url: String,
    val statusCode: Int,
    val durationMs: Long,
    val requestHeaders:Map<String, String>,
    val requestBody: String?,
    val responseHeaders: Map<String, String>,
    val responseBody: String?,
    val timestamp: Long = System.currentTimeMillis()
)

class NetworkLogInterceptor : Interceptor {

    companion object {
        /** Số lượng bản ghi tối đa lưu trữ trong bộ nhớ đệm */
        private const val MAX_LOGS = 100

        /** Kích thước tối đa của body được trích xuất (4 KB) để tránh làm chậm hệ thống */
        private const val MAX_BODY_BYTES = 4096

        private val buffer = ConcurrentLinkedDeque<NetworkLogEntry>()
        private val _logsFlow = MutableStateFlow<List<NetworkLogEntry>>(emptyList())

        /** StateFlow cung cấp danh sách log mạng cho các công cụ giám sát debug */
        val logsFlow: StateFlow<List<NetworkLogEntry>> = _logsFlow.asStateFlow()

        /** Xóa sạch lịch sử log mạng */
        fun clearLogs() {
            buffer.clear()
            _logsFlow.value = emptyList()
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        // Read and sanitize request body
        var reqBodyString: String? = null
        val requestBody = request.body
        if (requestBody != null && !requestBody.isDuplex() && !requestBody.isOneShot() && requestBody.contentLength() in 0..MAX_BODY_BYTES.toLong()) {
            val reqBuffer = Buffer()
            requestBody.writeTo(reqBuffer)
            val raw = reqBuffer.readString(Charset.forName("UTF-8"))
            reqBodyString = sanitize(raw)
        } else if (requestBody != null) {
            reqBodyString = "[streaming or oversized body omitted]"
        }

        // Sanitize request headers
        val reqHeaders = mutableMapOf<String, String>()
        for (name in request.headers.names()) {
            val value = if (isSensitive(name)) {
                "***"
            } else {
                request.headers[name] ?: ""
            }
            reqHeaders[name] = value
        }

        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            val failedEntry = NetworkLogEntry(
                method = request.method,
                url = safeUrl(request.url),
                statusCode = 0,
                durationMs = duration,
                requestHeaders = reqHeaders,
                requestBody = reqBodyString,
                responseHeaders = emptyMap(),
                responseBody = "Exception: ${e.javaClass.simpleName}"
            )
            addLog(failedEntry)
            throw e
        }

        val duration = System.currentTimeMillis() - startTime
        val resHeaders = mutableMapOf<String, String>()
        for (name in response.headers.names()) {
            resHeaders[name] = if (isSensitive(name)) "***" else response.headers[name] ?: ""
        }

        var resBodyString: String? = null
        val responseBody = response.body
        if (responseBody != null) {
            val preview = response.peekBody(MAX_BODY_BYTES.toLong() + 1).bytes()
            resBodyString = if (preview.size > MAX_BODY_BYTES) "[body omitted: exceeds 4KB]"
            else sanitize(preview.toString(Charsets.UTF_8))
        }

        val entry = NetworkLogEntry(
            method = request.method,
            url = safeUrl(request.url),
            statusCode = response.code,
            durationMs = duration,
            requestHeaders = reqHeaders,
            requestBody = reqBodyString,
            responseHeaders = resHeaders,
            responseBody = resBodyString
        )
        addLog(entry)

        return response
    }

    private fun addLog(entry: NetworkLogEntry) {
        buffer.addFirst(entry)
        while (buffer.size > MAX_LOGS) {
            buffer.removeLast()
        }
        _logsFlow.value = buffer.toList()
    }

    private fun isSensitive(name: String): Boolean =
        name.lowercase().replace("_", "").replace("-", "") in
                setOf(
                    "password",
                    "accesstoken",
                    "refreshtoken",
                    "authorization",
                    "proxyauthorization",
                    "cookie",
                    "setcookie",
                    "apikey"
                )

    private fun safeUrl(url: HttpUrl): String {
        val builder = url.newBuilder().username("").password("")
        url.queryParameterNames.filter(::isSensitive)
            .forEach { builder.setQueryParameter(it, "***") }
        return builder.build().toString()
    }

    private fun sanitize(str: String): String {
        fun redact(value: JsonElement) {
            if (value.isJsonObject) {
                val obj = value.asJsonObject
                obj.keySet().toList().forEach { key ->
                    if (isSensitive(key)) obj.add(key, JsonPrimitive("***")) else redact(obj[key])
                }
            } else if (value.isJsonArray) value.asJsonArray.forEach(::redact)
        }
        return try {
            JsonParser.parseString(str).also(::redact).toString()
        } catch (_: Exception) {
            "[non-JSON body omitted]"
        }
    }
}