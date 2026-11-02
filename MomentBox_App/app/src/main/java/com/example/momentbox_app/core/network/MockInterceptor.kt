package com.example.momentbox_app.core.network
import android.content.Context
import com.example.momentbox_app.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
class MockInterceptor(
    private val context: Context,
    private val settingsDataStore: SettingsDataStore
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // Kiểm tra xem người dùng có đang chọn chế độ Offline hay không
        val dataSource = runBlocking { settingsDataStore.getDataSource() }
        if (dataSource != SettingsDataStore.SOURCE_OFFLINE) {
            // Không phải Offline -> Cho qua để gửi lên server thật
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
                      "username": "offline_user",
                      "displayName": "Người Dùng Offline",
                      "avatarUrl": null,
                      "email": "offline@momentbox.dev"
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
                    "username": "offline_user",
                    "displayName": "Người Dùng Offline",
                    "avatarUrl": "https://picsum.photos/seed/offline_avatar/100/100",
                    "email": "offline@momentbox.dev",
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
                      "user": { "id": 2, "username": "lananh", "displayName": "Lan Anh", "avatarUrl": "https://picsum.photos/seed/user_2/100/100", "verified": false },
                      "hasUnseen": true,
                      "items": [{ "id": 201, "url": "https://picsum.photos/seed/story_2/720/1280", "type": "image" }]
                    },
                    {
                      "user": { "id": 3, "username": "minhquan", "displayName": "Minh Quân", "avatarUrl": "https://picsum.photos/seed/user_3/100/100", "verified": false },
                      "hasUnseen": true,
                      "items": [{ "id": 301, "url": "https://picsum.photos/seed/story_3/720/1280", "type": "image" }]
                    },
                    {
                      "user": { "id": 4, "username": "travel", "displayName": "Travel VN", "avatarUrl": "https://picsum.photos/seed/user_4/100/100", "verified": true },
                      "hasUnseen": true,
                      "items": [{ "id": 401, "url": "https://picsum.photos/seed/story_4/720/1280", "type": "image" }]
                    },
                    {
                      "user": { "id": 5, "username": "hatrang", "displayName": "Hà Trang", "avatarUrl": "https://picsum.photos/seed/user_5/100/100", "verified": false },
                      "hasUnseen": false,
                      "items": [{ "id": 501, "url": "https://picsum.photos/seed/story_5/720/1280", "type": "image" }]
                    }
                  ]
                }
            """.trimIndent()
            path.endsWith("/feed") -> {
                val page = request.url.queryParameter("page")?.toIntOrNull() ?: 1
                if (page > 2) {
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
                            "author": { "id": 5, "username": "hatrang", "displayName": "Hà Trang", "avatarUrl": "https://picsum.photos/seed/user_5/100/100", "verified": false },
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
                        }
                      ],
                      "meta": { "page": 2, "pageSize": 10, "total": 5, "hasMore": false }
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
                            "author": { "id": 2, "username": "lananh", "displayName": "Lan Anh", "avatarUrl": "https://picsum.photos/seed/user_2/100/100", "verified": false },
                            "caption": "Sáng sớm ở đồi chè Cầu Đất, sương phủ kín cả thung lũng ☁️🌿 Một trong những buổi sáng đẹp nhất năm. Cảm ơn cả nhà đã dậy sớm cùng mình! #dalat #travel #family",
                            "tags": ["dalat", "travel", "family"],
                            "location": "Đà Lạt",
                            "privacy": "public",
                            "allowComments": true,
                            "media": [
                              { "type": "image", "url": "https://picsum.photos/seed/dalat_1/720/524" },
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
                            "author": { "id": 3, "username": "minhquan", "displayName": "Minh Quân", "avatarUrl": "https://picsum.photos/seed/user_3/100/100", "verified": false },
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
                            { "id": 11, "username": "hatrang", "displayName": "Hà Trang", "avatarUrl": "https://picsum.photos/seed/sugg_1/100/100", "verified": false, "isFollowing": false, "mutualCount": 12 },
                            { "id": 12, "username": "duckhang", "displayName": "Đức Khang", "avatarUrl": "https://picsum.photos/seed/sugg_2/100/100", "verified": false, "isFollowing": true, "mutualCount": 5 },
                            { "id": 13, "username": "phuonglinh", "displayName": "Phương Linh", "avatarUrl": "https://picsum.photos/seed/sugg_3/100/100", "verified": false, "isFollowing": false, "mutualCount": 4 }
                          ]
                        },
                        {
                          "kind": "post",
                          "post": {
                            "id": 103,
                            "type": "VIDEO",
                            "author": { "id": 4, "username": "travel", "displayName": "Travel VN", "avatarUrl": "https://picsum.photos/seed/user_4/100/100", "verified": true },
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
                            "author": { "id": 2, "username": "lananh", "displayName": "Lan Anh", "avatarUrl": "https://picsum.photos/seed/user_2/100/100", "verified": false },
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
            path.matches(Regex(".*/posts/\\d+/like.*")) -> """
                {
                  "success": true,
                  "data": { "postId": 101, "isLiked": ${request.method == "POST"}, "likeCount": 129 }
                }
            """.trimIndent()
            path.matches(Regex(".*/posts/\\d+/bookmark.*")) -> """
                {
                  "success": true,
                  "data": { "postId": 101, "isBookmarked": ${request.method == "POST"} }
                }
            """.trimIndent()
            path.matches(Regex(".*/comments/\\d+/like.*")) -> """
                {
                  "success": true,
                  "data": { "commentId": 1, "isLiked": ${request.method == "POST"}, "likeCount": 13 }
                }
            """.trimIndent()
            path.matches(Regex(".*/users/\\d+/follow.*")) -> """
                {
                  "success": true,
                  "data": { "id": 11, "username": "hatrang", "displayName": "Hà Trang", "isFollowing": ${request.method == "POST"} }
                }
            """.trimIndent()
            path.matches(Regex(".*/posts/\\d+/comments.*")) && request.method == "POST" -> """
                {
                  "success": true,
                  "data": {
                    "id": ${System.currentTimeMillis()},
                    "postId": 101,
                    "parentId": null,
                    "author": { "id": 1, "username": "offline_user", "displayName": "Người Dùng Offline", "avatarUrl": "https://picsum.photos/seed/offline_avatar/100/100", "verified": false },
                    "content": "Bình luận offline mẫu",
                    "likeCount": 0,
                    "isLiked": false,
                    "replyCount": 0,
                    "createdAt": "Vừa xong"
                  }
                }
            """.trimIndent()
            path.matches(Regex(".*/posts/\\d+/comments.*")) -> """
                {
                  "success": true,
                  "data": [
                    {
                      "id": 1,
                      "postId": 101,
                      "parentId": null,
                      "author": { "id": 4, "username": "travel", "displayName": "Travel VN", "avatarUrl": "https://picsum.photos/seed/user_4/100/100", "verified": true },
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
                      "author": { "id": 5, "username": "hatrang", "displayName": "Hà Trang", "avatarUrl": "https://picsum.photos/seed/user_5/100/100", "verified": false },
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
            path.matches(Regex(".*/posts/\\d+.*")) && request.method == "DELETE" -> """
                {
                  "success": true,
                  "data": null
                }
            """.trimIndent()
            path.matches(Regex(".*/posts/\\d+.*")) -> """
                {
                  "success": true,
                  "data": {
                    "id": 101,
                    "type": "PHOTO",
                    "author": { "id": 2, "username": "lananh", "displayName": "Lan Anh", "avatarUrl": "https://picsum.photos/seed/user_2/100/100", "verified": false },
                    "caption": "Sáng sớm ở đồi chè Cầu Đất, sương phủ kín cả thung lũng ☁️🌿 Một trong những buổi sáng đẹp nhất năm. Cảm ơn cả nhà đã dậy sớm cùng mình! #dalat #travel #family",
                    "tags": ["dalat", "travel", "family"],
                    "location": "Đà Lạt",
                    "privacy": "public",
                    "allowComments": true,
                    "media": [
                      { "type": "image", "url": "https://picsum.photos/seed/dalat_1/720/524" },
                      { "type": "image", "url": "https://picsum.photos/seed/dalat_2/720/524" },
                      { "type": "image", "url": "https://picsum.photos/seed/dalat_3/720/524" }
                    ],
                    "likeCount": 128,
                    "commentCount": 24,
                    "isLiked": true,
                    "isBookmarked": true,
                    "createdAt": "2 giờ trước"
                  }
                }
            """.trimIndent()
            else -> """
                {
                  "success": true,
                  "data": {}
                }
            """.trimIndent()
        }

        return Response.Builder()
            .code(200)
            .message("OK (Offline Mock)")
            .protocol(Protocol.HTTP_1_1)
            .request(request)
            .body(mockJson.toResponseBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()
    }
}