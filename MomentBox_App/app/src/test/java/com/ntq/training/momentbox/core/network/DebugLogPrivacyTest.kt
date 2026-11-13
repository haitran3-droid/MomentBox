package com.ntq.training.momentbox.core.network

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogPrivacyTest {
    @After
    fun clearHistory() {
        NetworkLogInterceptor.clearLogs()
    }

    @Test
    fun redactsCredentialsInUrlHeadersAndNestedJson() {
        val url = DebugLogPrivacy.url("http://localhost/v1/me?refresh_token=private&limit=20".toHttpUrl())
        assertFalse(url.contains("private"))
        assertTrue(url.contains("limit=20"))
        val headers = DebugLogPrivacy.headers(
            Headers.headersOf("Authorization", "Bearer private", "Content-Type", "application/json")
        )
        assertEquals("***", headers["Authorization"])
        assertEquals("application/json", headers["Content-Type"])
        val body = DebugLogPrivacy.json("""{"profile":{"accessToken":"private","displayName":"Minh"}}""")
        assertFalse(body.contains("private"))
        assertTrue(body.contains("Minh"))
    }

    @Test
    fun limitsUtf8PreviewWithoutSplittingCodePoints() {
        val output = DebugLogPrivacy.bounded("日".repeat(50), 37)
        assertTrue(output.toByteArray(Charsets.UTF_8).size <= 37)
        assertFalse(output.contains('\uFFFD'))
    }

    @Test
    fun clearingHistoryDoesNotRestoreAnInFlightResponse() {
        val client = OkHttpClient.Builder()
            .addInterceptor(NetworkLogInterceptor())
            .addInterceptor { chain ->
                NetworkLogInterceptor.clearLogs()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"content":"previous account"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        client.newCall(Request.Builder().url("https://example.invalid/v1/me").build()).execute().use {
            assertEquals(200, it.code)
        }
        assertTrue(NetworkLogInterceptor.logsFlow.value.isEmpty())
    }
}
