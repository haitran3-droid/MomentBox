package com.ntq.training.momentbox.core.network

import com.ntq.training.momentbox.data.local.prefs.TokenStorage
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okio.Buffer

private object NetworkLogIds {
    val next = AtomicLong()
}

data class NetworkLogEntry(
    val id: Long = NetworkLogIds.next.incrementAndGet(),
    val method: String,
    val url: String,
    val statusCode: Int,
    val durationMs: Long,
    val requestHeaders: Map<String, String>,
    val requestBody: String?,
    val responseHeaders: Map<String, String>,
    val responseBody: String?,
    val timestamp: Long = System.currentTimeMillis()
)

/** Snapshot log chỉ chứa preview đã che/bounded. Một khóa bao trùm append/trim/clear/publish,
 * tránh deque bị clear giữa size và removeLast khi nhiều request hoàn tất cùng lúc.
 * Không writeTo multipart/media để xem trước: việc đó có thể gọi progress callback lần hai
 * hoặc tiêu thụ stream trước khi mạng gửi thật. Response dùng peekBody, không lấy .string(). */
class NetworkLogInterceptor(private val tokens: TokenStorage? = null) : Interceptor {
    companion object {
        private const val MAX_LOGS = 100
        private val gate = Any()
        private val buffer = ArrayDeque<NetworkLogEntry>()
        private var revision = 0L
        private val mutable = MutableStateFlow<List<NetworkLogEntry>>(emptyList())
        val logsFlow = mutable.asStateFlow()

        fun clearLogs() = synchronized(gate) {
            revision++
            buffer.clear()
            mutable.value = emptyList()
        }
    }

    private fun add(expectedRevision: Long, session: SessionRequest?, entry: NetworkLogEntry) {
        synchronized(gate) {
            if (revision != expectedRevision) return
            if (session != null && tokens != null && tokens.getSessionGeneration() != session.generation) return
            buffer.addFirst(entry)
            while (buffer.size > MAX_LOGS) buffer.removeLast()
            mutable.value = buffer.toList()
        }
    }

    private fun json(type: MediaType?): Boolean =
        type?.let { it.type == "application" && (it.subtype == "json" || it.subtype.endsWith("+json")) } == true

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val logRevision = synchronized(gate) { revision }
        val session = request.tag(SessionRequest::class.java)
        val requestHeaders = DebugLogPrivacy.headers(request.headers)
        val preview = request.body?.let { body ->
            if (!json(body.contentType()) || body.isDuplex() || body.isOneShot()) "[request body omitted: not replayable JSON]"
            else try {
                if (body.contentLength() !in 0..DebugLogPrivacy.LIMIT.toLong()) "[body omitted: exceeds 4KiB]"
                else Buffer().let { out ->
                    body.writeTo(out)
                    DebugLogPrivacy.json(
                    out.readString(
                        body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    )
                )
                }
            } catch (_: Exception) {
                "[request preview unavailable]"
            }
        }
        val start = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (error: Exception) {
            add(
                logRevision,
                session,
                NetworkLogEntry(
                    method = request.method,
                    url = DebugLogPrivacy.url(request.url),
                    statusCode = 0,
                    durationMs = (System.nanoTime() - start) / 1_000_000,
                    requestHeaders = requestHeaders,
                    requestBody = preview,
                    responseHeaders = emptyMap(),
                    responseBody = "Exception: ${error.javaClass.simpleName}"
                )
            )
            throw error
        }
        val duration = (System.nanoTime() - start) / 1_000_000
        val body = response.body
        val responsePreview = when {
            body == null -> null
            !json(body.contentType()) -> "[response body omitted: non-JSON]"
            !response.header("Content-Encoding")
                .isNullOrBlank() && response.header("Content-Encoding") != "identity" -> "[response preview omitted: encoded body]"

            else -> try {
                val bytes = response.peekBody(DebugLogPrivacy.LIMIT + 1L).bytes()
                if (bytes.size > DebugLogPrivacy.LIMIT) "[body omitted: exceeds 4KiB]"
                else DebugLogPrivacy.json(
                    String(
                        bytes,
                        body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    )
                )
            } catch (_: Exception) {
                "[response preview unavailable]"
            }
        }
        add(
            logRevision,
            session,
            NetworkLogEntry(
                method = request.method,
                url = DebugLogPrivacy.url(request.url),
                statusCode = response.code,
                durationMs = duration,
                requestHeaders = requestHeaders,
                requestBody = preview,
                responseHeaders = DebugLogPrivacy.headers(response.headers),
                responseBody = responsePreview
            )
        )
        return response
    }
}
