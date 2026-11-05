package com.ntq.training.momentbox.core.network

import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.local.prefs.SettingsStorage
import com.ntq.training.momentbox.data.local.prefs.TokenStorage
import com.ntq.training.momentbox.BuildConfig
import com.ntq.training.momentbox.R
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class MockInterceptor(
    private val settingsDataStore: SettingsStorage,
    private val tokenStore: TokenStorage? = null
) : Interceptor {

    private val offline = OfflineFeedState({ listOf(feedJson(1), feedJson(2)) }, { commentsJson() })
    private var offlineGeneration = tokenStore?.getSessionGeneration()

    init {
        tokenStore?.addSnapshotListener { snapshot ->
            if (snapshot.generation != offlineGeneration) {
                offlineGeneration = snapshot.generation
                offline.clear()
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        // Kiểm tra xem người dùng có đang chọn chế độ Offline hay không
        val dataSource = runBlocking { settingsDataStore.getDataSource() }
        if (dataSource != SettingsDataStore.SOURCE_OFFLINE) {
            // Không phải Offline -> Cho qua để gửi lên server thật
            offline.clear()
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val path = request.url.encodedPath
        if (BuildConfig.DEBUG) {
            val delay = runBlocking { settingsDataStore.getDebugDelay() }.coerceIn(0, 10_000)
            if (delay > 0) Thread.sleep(delay.toLong())
        }

        val mockJson = when {
            path.endsWith("/config") -> """
                {
                  "success": true,
                  "data": {
                    "version": "1.0.0",
                    "minSupportedVersionCode": 1,
                    "serverTime": "2026-10-26T10:00:00.000Z",
                    "features": { "offline": true }
                  }
                }
            """.trimIndent()
            path.endsWith("/auth/login") -> """
                {
                  "success": true,
                  "data": {
                    "accessToken": "at_offline_demo_token_12345",
                    "refreshToken": "rt_offline_demo_token_67890",
                    "expiresIn": 900,
                    "user": {
                      "id": 1,
                      "username": "minhanh",
                      "displayName": "Minh Anh",
                      "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_ma}",
                      "email": "minhanh@momentbox.dev"
                    }
                  }
                }
            """.trimIndent()
            path.endsWith("/auth/refresh") -> """
                {
                  "success": true,
                  "data": {
                    "accessToken": "at_offline_refreshed_token",
                    "refreshToken": "rt_offline_refreshed_token",
                    "expiresIn": 900
                  }
                }
            """.trimIndent()
            path.endsWith("/me") -> """
                {
                  "success": true,
                  "data": {
                    "id": 1,
                    "username": "minhanh",
                    "displayName": "Minh Anh",
                    "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_ma}",
                    "email": "minhanh@momentbox.dev",
                    "bio": "Đang dùng chế độ offline",
                    "postCount": 12,
                    "followerCount": 128,
                    "followingCount": 45
                  }
                }
            """.trimIndent()
            path.endsWith("/stories") -> """
                {
                  "success": true,
                  "data": [
                    {
                      "user": { "id": 2, "username": "lananh", "displayName": "Lan Anh", "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_la}", "verified": false },
                      "hasUnseen": true,
                      "items": [{ "id": 201, "url": "https://picsum.photos/seed/story_2/720/1280", "type": "image" }]
                    },
                    {
                      "user": { "id": 3, "username": "minhquan", "displayName": "Minh Quân", "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_mq}", "verified": false },
                      "hasUnseen": true,
                      "items": [{ "id": 301, "url": "https://picsum.photos/seed/story_3/720/1280", "type": "image" }]
                    },
                    {
                      "user": { "id": 4, "username": "travel", "displayName": "Travel VN", "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_tv}", "verified": true },
                      "hasUnseen": true,
                      "items": [{ "id": 401, "url": "https://picsum.photos/seed/story_4/720/1280", "type": "image" }]
                    },
                    {
                      "user": { "id": 5, "username": "hatrang", "displayName": "Hà Trang", "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_ht}", "verified": false },
                      "hasUnseen": false,
                      "items": [{ "id": 501, "url": "https://picsum.photos/seed/story_5/720/1280", "type": "image" }]
                    }
                  ]
                }
            """.trimIndent()
            path.endsWith("/feed") -> feedJson(request.url.queryParameter("page")?.toIntOrNull() ?: 1)
            path.matches(Regex(".*/posts/\\d+/comments.*")) && request.method == "POST" -> """
                {
                  "success": true,
                  "data": {
                    "id": ${System.currentTimeMillis()},
                    "postId": 101,
                    "parentId": null,
                    "author": { "id": 1, "username": "minhanh", "displayName": "Minh Anh", "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_ma}", "verified": false },
                    "content": "Bình luận offline mẫu",
                    "likeCount": 0,
                    "isLiked": false,
                    "replyCount": 0,
                    "createdAt": "Vừa xong"
                  }
                }
            """.trimIndent()
            path.matches(Regex(".*/posts/\\d+/comments.*")) -> commentsJson()
            else -> """
                {
                  "success": true,
                  "data": {}
                }
            """.trimIndent()
        }

        // Mock trả trực tiếp trước AuthInterceptor, vì vậy phải giữ cùng chốt generation khi ghi fixture.
        // Thứ tự khóa TokenStorage -> OfflineFeedState cũng giống callback xóa khi logout.
        val taggedGeneration = request.tag(SessionRequest::class.java)?.generation
        var resolved: Pair<Int, String>? = null
        if (tokenStore != null && taggedGeneration != null) {
            val accepted = runBlocking {
                tokenStore.runIfGenerationMatches(taggedGeneration) {
                    resolved = offline.resolve(request, mockJson)
                }
            }
            if (!accepted) resolved = 409 to """{"success":false,"error":{"code":"SESSION_CHANGED"}}"""
        } else resolved = offline.resolve(request, mockJson)
        val (code, body) = requireNotNull(resolved)
        return Response.Builder()
            .code(code)
            .message("OK (Offline Mock)")
            .protocol(Protocol.HTTP_1_1)
            .request(request)
            .body(body.toResponseBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()
    }

    private fun feedJson(page: Int): String {
        // Ảnh đầu bài Đà Lạt dùng đúng artwork mockup đã đóng gói trong app,
        // giúp đối chiếu S04/S05 ổn định và không phụ thuộc ảnh Picsum thay đổi.
        return if (page > 2) {
            """
                    {
                      "success": true,
                      "data": [],
                      "meta": { "page": $page, "pageSize": 10, "total": 6, "hasMore": false }
                    }
                    """.trimIndent()
        } else if (page == 2) {
            """
                    {
                      "success": true,
                      "data": [
                        {
                          "kind": "post",
                          "post": {
                            "id": 105,
                            "type": "TEXT",
                            "author": { "id": 5, "username": "hatrang", "displayName": "Hà Trang", "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_ht}", "verified": false },
                            "caption": "Chào buổi tối cả nhà! Chúc mọi người một tuần làm việc hiệu quả và tràn đầy năng lượng nha #momentbox #evening",
                            "tags": ["momentbox", "evening"],
                            "location": "Hà Nội",
                            "privacy": "public",
                            "allowComments": true,
                            "media": [],
                            "likeCount": 18,
                            "commentCount": 3,
                            "isLiked": false,
                            "isBookmarked": false,
                            "createdAt": "6 giờ trước"
                          }
                        },
                        {
                          "kind": "post",
                          "post": {
                            "id": 106,
                            "type": "VIDEO",
                            "author": { "id": 4, "username": "travel", "displayName": "Travel VN", "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_tv}", "verified": true },
                            "caption": "Trải nghiệm phát video luồng HLS trực tiếp với định dạng .m3u8 trên ExoPlayer 🎬 #hls #stream #test",
                            "tags": ["hls", "stream", "test"],
                            "location": "Đà Nẵng",
                            "privacy": "public",
                            "allowComments": true,
                            "media": [
                              { "type": "video", "url": "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", "durationMs": 634000 }
                            ],
                            "likeCount": 55,
                            "commentCount": 7,
                            "isLiked": false,
                            "isBookmarked": false,
                            "createdAt": "3 giờ trước"
                          }
                        }
                      ],
                      "meta": { "page": 2, "pageSize": 10, "total": 6, "hasMore": false }
                    }
                    """.trimIndent()
        } else {
            """
                    {
                      "success": true,
                      "data": [
                        {
                          "kind": "post",
                          "post": {
                            "id": 101,
                            "type": "PHOTO",
                            "author": { "id": 2, "username": "lananh", "displayName": "Lan Anh", "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_la}", "verified": false },
                            "caption": "Sáng sớm ở đồi chè Cầu Đất, sương phủ kín cả thung lũng ☁️🌿 Một trong những buổi sáng đẹp nhất năm. Cảm ơn cả nhà đã dậy sớm cùng mình! #dalat #travel #family",
                            "tags": ["dalat", "travel", "family"],
                            "location": "Đà Lạt",
                            "privacy": "public",
                            "allowComments": true,
                            "media": [
                              { "type": "image", "url": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.art_onboarding_dalat}" },
                              { "type": "image", "url": "https://picsum.photos/seed/dalat_2/720/524" },
                              { "type": "image", "url": "https://picsum.photos/seed/dalat_3/720/524" }
                            ],
                            "likeCount": 128,
                            "commentCount": 24,
                            "isLiked": true,
                            "isBookmarked": true,
                            "createdAt": "2 giờ trước"
                          }
                        },
                        {
                          "kind": "post",
                          "post": {
                            "id": 102,
                            "type": "VOICE",
                            "author": { "id": 3, "username": "minhquan", "displayName": "Minh Quân", "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_mq}", "verified": false },
                            "caption": "Một chút tâm sự tối nay 🎧",
                            "tags": ["podcast", "night"],
                            "location": null,
                            "privacy": "friends",
                            "allowComments": true,
                            "media": [
                              { "type": "audio", "url": "http://localhost:3000/media/audio/voice-1.wav", "durationMs": 42000 }
                            ],
                            "likeCount": 36,
                            "commentCount": 8,
                            "isLiked": false,
                            "isBookmarked": false,
                            "createdAt": "5 giờ trước"
                          }
                        },
                        {
                          "kind": "suggestions",
                          "users": [
                            { "id": 11, "username": "hatrang", "displayName": "Hà Trang", "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_ht}", "verified": false, "isFollowing": false, "mutualCount": 12 },
                            { "id": 12, "username": "duckhang", "displayName": "Đức Khang", "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_dk}", "verified": false, "isFollowing": true, "mutualCount": 5 },
                            { "id": 13, "username": "phuonglinh", "displayName": "Phương Linh", "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_pl}", "verified": false, "isFollowing": false, "mutualCount": 4 }
                          ]
                        },
                        {
                          "kind": "post",
                          "post": {
                            "id": 103,
                            "type": "VIDEO",
                            "author": { "id": 4, "username": "travel", "displayName": "Travel VN", "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_tv}", "verified": true },
                            "caption": "Biển Mỹ Khê lúc hoàng hôn, bình yên đến lạ 🌅🌊 #danang #mykhe #sunset",
                            "tags": ["danang", "mykhe", "sunset"],
                            "location": "Đà Nẵng",
                            "privacy": "public",
                            "allowComments": true,
                            "media": [
                              { "type": "video", "url": "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4", "durationMs": 24000 }
                            ],
                            "likeCount": 89,
                            "commentCount": 14,
                            "isLiked": false,
                            "isBookmarked": false,
                            "createdAt": "Hôm qua"
                          }
                        },
                        {
                          "kind": "post",
                          "post": {
                            "id": 104,
                            "type": "TEXT",
                            "author": { "id": 2, "username": "lananh", "displayName": "Lan Anh", "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_la}", "verified": false },
                            "caption": "Đôi khi chỉ cần một cuốn sách hay và một tách cà phê ấm là đủ cho một ngày mưa rả rích ☕️📖",
                            "tags": ["coffee", "rain"],
                            "location": "Hà Nội",
                            "privacy": "public",
                            "allowComments": true,
                            "media": [],
                            "likeCount": 42,
                            "commentCount": 5,
                            "isLiked": false,
                            "isBookmarked": false,
                            "createdAt": "Hôm kia"
                          }
                        }
                      ],
                      "meta": { "page": 1, "pageSize": 10, "total": 5, "hasMore": true }
                    }
                    """.trimIndent()
        }

    }

    private fun commentsJson(): String = """
                {
                  "success": true,
                  "data": [
                    {
                      "id": 1,
                      "postId": 101,
                      "parentId": null,
                      "author": { "id": 4, "username": "travel", "displayName": "Travel VN", "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_tv}", "verified": true },
                      "content": "Đẹp quá chị ơi! Cho em xin địa chỉ đồi chè với 😍",
                      "likeCount": 12,
                      "isLiked": true,
                      "replyCount": 2,
                      "createdAt": "15 phút"
                    },
                    {
                      "id": 2,
                      "postId": 101,
                      "parentId": null,
                      "author": { "id": 5, "username": "hatrang", "displayName": "Hà Trang", "avatarUrl": "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.avatar_demo_ht}", "verified": false },
                      "content": "Hẹn tháng sau mình đi cùng nha!",
                      "likeCount": 3,
                      "isLiked": false,
                      "replyCount": 0,
                      "createdAt": "1 giờ"
                    }
                  ],
                  "meta": { "page": 1, "pageSize": 20, "total": 24, "hasMore": false }
                }
            """.trimIndent()
}
