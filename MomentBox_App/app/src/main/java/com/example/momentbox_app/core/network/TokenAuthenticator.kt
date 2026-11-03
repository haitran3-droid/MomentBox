package com.example.momentbox_app.core.network

import com.example.momentbox_app.data.local.prefs.SettingsStorage
import com.example.momentbox_app.data.local.prefs.TokenStorage
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import okio.ByteString.Companion.encodeUtf8

class TokenAuthenticator(
    private val tokenStore: TokenStorage,
    private val settingsDataStore: SettingsStorage,
    private val rawClient: OkHttpClient,
    private val baseUrl: String,
    private val dummyJsonBaseUrl: String = "https://dummyjson.com/",
    private val gson: Gson = Gson()
) : Authenticator {

    companion object {
        /** Khóa đồng bộ Mutex đảm bảo chỉ duy nhất một coroutine thực hiện refresh tại một thời điểm */
        private val mutex = Mutex()

        /** Header đánh dấu request đã được thử lại, giới hạn tối đa 1 lần retry */
        private const val RETRY_HEADER = "X-Auth-Retry"
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        val originalRequest = response.request
        val path = originalRequest.url.encodedPath

        // Bước 1: Không bao giờ refresh khi chính endpoint auth bị 401 (tránh lặp vô hạn)
        if (path.endsWith("/auth/login") || path.endsWith("/auth/refresh")) {
            return null
        }

        // Bước 2: Giới hạn tối đa 1 lần retry. Nếu đã retry rồi mà vẫn 401 thì phiên chắc chắn không còn hợp lệ
        if (originalRequest.header(RETRY_HEADER) != null || responseCount(response) >= 2) {
            val generation = originalRequest.tag(SessionRequest::class.java)?.generation
            if (generation != null) runBlocking {
                // Xóa token nếu generation trùng khớp và phát thông báo phiên hết hạn
                if (tokenStore.clearIfGenerationMatches(generation)) SessionManager.notifySessionExpired()
            }
            return null
        }

        // Lấy access token vừa thất bại từ header Authorization của request gốc
        val failedToken = originalRequest.header("Authorization")?.removePrefix("Bearer ")?.trim()

        // runBlocking được sử dụng vì phương thức Authenticate của OkHttp là Java đồng bộ (synchronous callback)
        return runBlocking {
            mutex.withLock {
                // Bước 3: Đọc snapshot phiên hiện tại trong RAM
                val snapshot = tokenStore.snapshot()
                val currentToken = snapshot.accessToken
                val requestSession = originalRequest.tag(SessionRequest::class.java)

                // Nếu request thuộc về phiên cũ (khác generation trong RAM), từ chối không retry
                if (requestSession != null && requestSession.generation != snapshot.generation) return@withLock null
                if (requestSession == null && currentToken != failedToken) return@withLock null

                // Bước 4: Kiểm tra xem một request đồng thời khác đã kịp làm mới token trước đó hay chưa:
                // Nếu token trong RAM đã khác failedToken, nghĩa là request khác đã refresh thành công!
                // Ta chỉ việc dùng ngay token mới để retry, không cần gọi API refresh lần nữa.
                val debugTokenAlreadyRotated = requestSession?.forceRefresh == true &&
                        "Bearer $currentToken".encodeUtf8().sha256()
                            .hex() != requestSession.forcedTokenFingerprint
                if (requestSession != null && (!requestSession.forceRefresh || debugTokenAlreadyRotated) && !currentToken.isNullOrBlank() && currentToken != failedToken) {
                    return@withLock originalRequest.newBuilder()
                        .removeHeader("X-Simulate-Error")
                        .removeHeader("X-Debug-Force-401")
                        .header("Authorization", "Bearer $currentToken")
                        .header(RETRY_HEADER, "1")
                        .build()
                }

                // Bước 5: Kiểm tra Refresh Token còn tồn tại hay không
                val currentRefreshToken = snapshot.refreshToken
                if (currentRefreshToken.isNullOrBlank()) {
                    if (tokenStore.clearIfGenerationMatches(snapshot.generation)) SessionManager.notifySessionExpired()
                    return@withLock null
                }

                val startGeneration = snapshot.generation
                val dummyOrigin = dummyJsonBaseUrl.toHttpUrl()
                val isDummy =
                    originalRequest.url.host == dummyOrigin.host && originalRequest.url.port == dummyOrigin.port

                // MB Server trả về mã lỗi cụ thể (ví dụ TOKEN_EXPIRED) để phân biệt với các lỗi phân quyền khác.
                // DummyJSON không có envelope, chỉ trả 401 nên không kiểm tra JSON envelope.
                if (!isDummy && requestSession?.forceRefresh != true) {
                    val errorCode = try {
                        val json = gson.fromJson(response.peekBody(4096).string(), Map::class.java)
                        (json["error"] as? Map<*, *>)?.get("code")
                    } catch (_: Exception) {
                        null
                    }
                    if (errorCode != "TOKEN_EXPIRED") {
                        if (tokenStore.clearIfGenerationMatches(startGeneration)) SessionManager.notifySessionExpired()
                        return@withLock null
                    }
                }

                // Bước 6: Xây dựng request gọi API refresh token tùy theo nguồn dữ liệu (MB Server hay DummyJSON)
                val refreshUrl: String
                val refreshRequestBodyJson: String

                if (isDummy) {
                    val safeDummyBase =
                        if (dummyJsonBaseUrl.endsWith("/")) dummyJsonBaseUrl else "$dummyJsonBaseUrl/"
                    refreshUrl = "${safeDummyBase}auth/refresh"
                    refreshRequestBodyJson = gson.toJson(
                        mapOf(
                            "refreshToken" to currentRefreshToken,
                            "expiresInMins" to 30
                        )
                    )
                } else {
                    val safeMbBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                    refreshUrl = "${safeMbBase}v1/auth/refresh"
                    refreshRequestBodyJson =
                        gson.toJson(mapOf("refreshToken" to currentRefreshToken))
                }

                val refreshRequest = Request.Builder()
                    .url(refreshUrl)
                    .post(refreshRequestBodyJson.toRequestBody("application/json".toMediaType()))
                    .build()

                try {
                    // Bước 7: Thực thi gọi mạng bằng rawClient và đảm bảo đóng Response Body với .use { ... }
                    rawClient.newCall(refreshRequest).execute().use { refreshResponse ->
                        if (refreshResponse.isSuccessful) {
                            val bodyString = refreshResponse.body?.string()
                            if (!bodyString.isNullOrBlank()) {
                                var newAccessToken: String? = null
                                var newRefreshToken: String? = null

                                if (isDummy) {
                                    val flatMap = try {
                                        gson.fromJson(bodyString, Map::class.java)
                                    } catch (e: Exception) {
                                        null
                                    }
                                    newAccessToken = flatMap?.get("accessToken") as? String
                                    newRefreshToken = flatMap?.get("refreshToken") as? String
                                } else {
                                    val envelope = try {
                                        gson.fromJson(bodyString, ApiResponse::class.java)
                                    } catch (e: Exception) {
                                        null
                                    }
                                    if (envelope != null && envelope.success && envelope.data != null) {
                                        val dataMap = envelope.data as? Map<*, *>
                                        newAccessToken = dataMap?.get("accessToken") as? String
                                        newRefreshToken = dataMap?.get("refreshToken") as? String
                                    }
                                }

                                if (!newAccessToken.isNullOrBlank() && !newRefreshToken.isNullOrBlank()) {
                                    // Bước 8: Ghi token mới với cơ chế Compare-And-Set (CAS):
                                    // Chỉ commit nếu session generation không bị thay đổi trong suốt thời gian gọi mạng
                                    val updated = tokenStore.updateTokensIfGenerationMatches(
                                        startGeneration,
                                        newAccessToken,
                                        newRefreshToken
                                    )

                                    if (updated) {
                                        // Ghi nhận số lần refresh phục vụ kiểm thử và debug
                                        SessionManager.recordTokenRefresh()
                                        // Trả về Request mới kèm Access Token vừa cấp để OkHttp retry
                                        return@withLock originalRequest.newBuilder()
                                            .removeHeader("X-Simulate-Error")
                                            .removeHeader("X-Debug-Force-401")
                                            .header("Authorization", "Bearer $newAccessToken")
                                            .header(RETRY_HEADER, "1")
                                            .build()
                                    } else {
                                        // Phiên đã bị hủy (Logout) trong lúc refresh đang diễn ra -> Hủy kết quả cũ
                                        return@withLock null
                                    }
                                }
                            }
                        }

                        // Bước 9: Làm mới thất bại (Refresh Token không hợp lệ hoặc đã hết hạn) -> Xóa phiên
                        if (tokenStore.clearIfGenerationMatches(startGeneration)) {
                            SessionManager.notifySessionExpired()
                        }
                        null
                    }
                } catch (e: IOException) {
                    // BA M1 coi refresh không thành công là hết phiên, kể cả lỗi mạng.
                    // Giữ kiểm tra generation để lỗi cũ không xóa một lần đăng nhập mới.
                    if (tokenStore.clearIfGenerationMatches(startGeneration)) {
                        SessionManager.notifySessionExpired()
                    }
                    null
                }
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var result = 1
        var prior = response.priorResponse
        while (prior != null) {
            result++
            prior = prior.priorResponse
        }
        return result
    }
}