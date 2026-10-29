# MomentBox Mock Server

Backend giả lập (miễn phí, chạy trên máy cá nhân) cho dự án đào tạo Android Fresher **MomentBox**.

- **REST API** (Express) → luyện Retrofit, OkHttp Interceptor/Authenticator, Gson, Coroutines, phân trang, upload multipart.
- **Realtime** (Socket.IO 4) → luyện chat realtime, typing, presence, ack + timeout, cơ chế reconnect.
- **Media mẫu** → ảnh (picsum.photos), video (mp4/HLS public), âm thanh WAV tự sinh (MediaPlayer, ExoPlayer, AudioTrack static/stream).
- Dữ liệu nằm trong RAM, **khởi động lại là reset**. Không cần database, không cần tài khoản cloud.

---

## 1. Chạy server

Yêu cầu: **Node.js 18+**.

```bash
npm install
npm start
```

Server in ra các địa chỉ dùng được:

```
MomentBox mock server đang chạy:
  • Máy tính      : http://localhost:3000
  • Android Emulator: http://10.0.2.2:3000
  • Máy thật (LAN): http://192.168.1.23:3000
```

Biến môi trường:

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `PORT` | `3000` | Cổng HTTP |
| `ACCESS_TOKEN_TTL` | `900` | Thời gian sống của access token (giây). Đặt `60` để test refresh token nhanh |
| `REFRESH_TOKEN_TTL` | `604800` | Thời gian sống của refresh token (giây) |

Windows PowerShell: `$env:ACCESS_TOKEN_TTL=60; npm start` · macOS/Linux: `ACCESS_TOKEN_TTL=60 npm start`

Kiểm tra nhanh toàn bộ API: `node test/smoke.js http://localhost:3000`

### Tài khoản demo (mật khẩu chung `123456`)

| Email | Tên | Ghi chú |
|---|---|---|
| `minhanh@momentbox.dev` | Nguyễn Minh Anh | Tài khoản chính |
| `lananh@momentbox.dev` | Lan Anh | **Bot** – tự trả lời chat |
| `quan@momentbox.dev` | Minh Quân | **Bot** – tự trả lời chat |
| `travel@momentbox.dev` | Travel VN | Tài khoản verified |
| `trang@momentbox.dev`, `khang@momentbox.dev`, `linh@momentbox.dev` | … | Người dùng thường |

Mở 2 emulator, đăng nhập 2 tài khoản khác nhau để test chat 2 chiều thật.

---

## 2. Kết nối từ app Android

| Thiết bị | BASE_URL |
|---|---|
| Android Emulator | `http://10.0.2.2:3000/` |
| Máy thật cắm USB | Chạy `adb reverse tcp:3000 tcp:3000` rồi dùng `http://localhost:3000/` |
| Máy thật cùng Wi‑Fi | `http://<IP LAN của máy tính>:3000/` (mở firewall cổng 3000) |
| Chia sẻ cho cả nhóm / qua Internet | Xem mục 3 (Cloudflare Tunnel hoặc Render) |

**Lưu ý:** Từ Android 9, HTTP thường (cleartext) bị chặn mặc định. Khi dùng `http://`, cần cho phép trong bản **debug**:

`app/src/debug/res/xml/network_security_config.xml`
```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.0.2.2</domain>
        <domain includeSubdomains="false">localhost</domain>
        <domain includeSubdomains="false">192.168.1.23</domain>
    </domain-config>
</network-security-config>
```
`app/src/debug/AndroidManifest.xml`
```xml
<application android:networkSecurityConfig="@xml/network_security_config" />
```

Gợi ý: khai báo `BASE_URL` bằng `buildConfigField` theo build type / product flavor (`offline`, `dummyjson`, `mockserver`).

---

## 3. Đưa server lên Internet miễn phí

### Cách A — Cloudflare Quick Tunnel (nhanh nhất, không cần tài khoản)
1. Cài `cloudflared` (https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/).
2. Chạy server (`npm start`), mở terminal khác:
   ```bash
   cloudflared tunnel --url http://localhost:3000
   ```
3. Dùng URL dạng `https://xxxx.trycloudflare.com/` làm BASE_URL (có HTTPS, hỗ trợ WebSocket).
   URL đổi mỗi lần chạy lại → chỉ phù hợp cho buổi demo/học nhóm.

### Cách B — Deploy lên Render (free web service)
1. Đưa thư mục `mock-server` lên một repo GitHub.
2. https://render.com → **New → Web Service** → chọn repo.
3. Runtime `Node`, Build command `npm install`, Start command `npm start`, Instance type **Free**.
4. URL dạng `https://momentbox-mock.onrender.com/` dùng được cho cả lớp.

Hạn chế của gói free (kiểm tra lại chính sách hiện hành của Render trước khi dùng): server **ngủ khi không có request** (request đầu tiên chờ khoảng 30–60 giây), ổ đĩa **không lưu lâu dài** (file upload mất khi restart). Với dự án học tập thì chấp nhận được.

---

## 4. Quy ước chung của REST API

Base path: `/v1`. Mọi response đều bọc trong một **envelope** — luyện `ApiResponse<T>` bằng Generic:

```json
{ "success": true, "data": { }, "meta": { "page": 1, "pageSize": 10, "total": 46, "hasMore": true } }
{ "success": false, "error": { "code": "TOKEN_EXPIRED", "message": "Phiên đăng nhập đã hết hạn" } }
```

| Header | Ý nghĩa |
|---|---|
| `Authorization: Bearer <accessToken>` | Bắt buộc với API cần đăng nhập |
| `Accept-Language: vi` / `en` | `error.message` trả về theo ngôn ngữ (gắn với tính năng đa ngôn ngữ) |
| `X-Simulate-Delay: 1500` | Server chờ N ms trước khi trả lời (test loading, timeout) |
| `X-Simulate-Error: 401` / `500` | Ép server trả lỗi (test xử lý lỗi) |

| `error.code` | HTTP | Khi nào |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Thiếu hoặc sai dữ liệu (`details.fields` liệt kê field lỗi) |
| `INVALID_CREDENTIALS` | 401 | Sai email/mật khẩu |
| `UNAUTHORIZED` | 401 | Không có token / token không tồn tại |
| `TOKEN_EXPIRED` | 401 | Access token hết hạn → gọi refresh |
| `REFRESH_TOKEN_INVALID` | 401 | Refresh token sai/hết hạn/đã dùng → đăng xuất |
| `FORBIDDEN` | 403 | Không có quyền |
| `NOT_FOUND` | 404 | Không tìm thấy |
| `INTERNAL` | 500 | Lỗi server |

---

## 5. Danh sách REST API

### Cấu hình & xác thực
| Method | Path | Body / Query | Mô tả |
|---|---|---|---|
| GET | `/v1/config` | – | Cấu hình app (version, socketUrl, danh sách âm thanh mẫu). Gọi ở Splash |
| POST | `/v1/auth/login` | `{ email, password }` | Trả `accessToken`, `refreshToken`, `expiresIn`, `user` |
| POST | `/v1/auth/refresh` | `{ refreshToken }` | Trả cặp token mới. **Refresh token chỉ dùng được 1 lần** (rotation) |
| POST | `/v1/auth/logout` | `{ refreshToken }` | Huỷ token |

### Người dùng
| Method | Path | Body / Query | Mô tả |
|---|---|---|---|
| GET | `/v1/me` | – | Hồ sơ của tôi |
| PATCH | `/v1/me` | `{ displayName?, bio?, location? }` | Sửa hồ sơ |
| POST | `/v1/me/avatar` | multipart `avatar` | Đổi ảnh đại diện |
| GET | `/v1/me/bookmarks` | `page, pageSize` | Bài đã lưu (tab "Đã lưu") |
| GET | `/v1/users/suggestions` | – | Gợi ý theo dõi |
| GET | `/v1/users/{id}` | – | Hồ sơ người dùng (kèm `postCount`, `followerCount`, `isFollowing`, `online`) |
| GET | `/v1/users/{id}/posts` | `page, pageSize, type=PHOTO\|VIDEO\|VOICE\|TEXT` | Bài viết của người dùng (grid trang cá nhân) |
| POST / DELETE | `/v1/users/{id}/follow` | – | Theo dõi / bỏ theo dõi |

### Bảng tin & bài viết
| Method | Path | Body / Query | Mô tả |
|---|---|---|---|
| GET | `/v1/stories` | – | Danh sách story (thanh story đầu bảng tin) |
| GET | `/v1/feed` | `page, pageSize` | Danh sách **đa kiểu**: `{kind:"post", post}` hoặc `{kind:"suggestions", users}` (trang 1, vị trí 4) |
| GET | `/v1/posts/{id}` | – | Chi tiết bài viết |
| POST | `/v1/posts` | multipart: `caption`, `privacy`, `allowComments`, `location`, `media[]` (ảnh/video), `voice`, `voiceDurationMs` | Tạo bài viết. `type` được suy ra: VOICE > VIDEO > PHOTO > TEXT |
| DELETE | `/v1/posts/{id}` | – | Xoá bài của mình |
| POST / DELETE | `/v1/posts/{id}/like` | – | Thích / bỏ thích → `{ isLiked, likeCount }` |
| POST / DELETE | `/v1/posts/{id}/bookmark` | – | Lưu / bỏ lưu |
| GET | `/v1/posts/{id}/comments` | `page, pageSize, sort=newest\|top, parentId?` | Bình luận (truyền `parentId` để lấy phản hồi) |
| POST | `/v1/posts/{id}/comments` | `{ content, parentId? }` | Viết bình luận (tối đa 500 ký tự) |
| POST / DELETE | `/v1/comments/{id}/like` | – | Thích bình luận |

Ví dụ một `post`:
```json
{
  "id": 5, "type": "VOICE",
  "author": { "id": 3, "username": "minhquan", "displayName": "Minh Quân", "avatarUrl": "https://…", "verified": false },
  "caption": "Một chút tâm sự tối nay 🎧", "tags": ["podcast","night"], "location": null,
  "privacy": "public", "allowComments": true,
  "media": [ { "type": "audio", "url": "http://10.0.2.2:3000/media/audio/voice-3.wav", "durationMs": 15000 } ],
  "likeCount": 2, "commentCount": 5, "isLiked": false, "isBookmarked": false,
  "createdAt": "2026-10-21T10:42:00.000Z"
}
```

### Chat (phần REST)
| Method | Path | Body / Query | Mô tả |
|---|---|---|---|
| GET | `/v1/conversations` | – | Danh sách hội thoại (`lastMessage`, `unreadCount`, `members[].online`) |
| POST | `/v1/conversations` | `{ userId }` | Tạo/lấy hội thoại 1‑1 |
| GET | `/v1/conversations/{id}/messages` | `before=<messageId>, limit=30` | Tin nhắn **mới nhất trước**, phân trang theo cursor (`meta.nextBefore`) |
| POST | `/v1/uploads` | multipart `file` | Upload ảnh/ghi âm để gửi trong chat → `{ url, type }` |

### Sao lưu nhật ký (tuỳ chọn – luyện Gson)
| Method | Path | Body | Mô tả |
|---|---|---|---|
| PUT | `/v1/journal/backup` | `{ entries: [...] }` | Lưu bản sao lưu nhật ký (JSON tự do) |
| GET | `/v1/journal/backup` | – | Lấy bản sao lưu |

### Debug (dùng cho màn Developer tools)
| Method | Path | Mô tả |
|---|---|---|
| POST | `/v1/debug/expire-token` | Đánh dấu access token hiện tại hết hạn → request sau nhận `401 TOKEN_EXPIRED` (test Authenticator/refresh) |
| POST | `/v1/debug/socket/drop?mode=transport&outageSeconds=10` | Cắt kết nối socket của user. `transport` → client **tự reconnect**; `server` → client **không** tự reconnect. `outageSeconds` → server từ chối kết nối trong N giây (thấy "thử lại lần 1, 2, 3…") |
| POST | `/v1/debug/feed-new` | `{ count }` → bắn sự kiện `feed:new` (hiện nút "N bài viết mới") |
| POST | `/v1/debug/notify` | `{ title, body, type }` → bắn sự kiện `notification` |

---

## 6. Socket.IO

- URL: giống BASE_URL (không có `/v1`), path mặc định `/socket.io`.
- Xác thực: gửi access token trong `auth` khi kết nối (`{ "token": "<accessToken>" }`).

### Client → Server
| Event | Payload | Ack |
|---|---|---|
| `message:send` | `{ clientId, conversationId, type: "text"\|"image"\|"voice", content, mediaUrl?, durationMs? }` | `{ ok: true, message }` hoặc `{ ok: false, error: { code } }` |
| `message:seen` | `{ conversationId, messageId }` | – |
| `typing:start` / `typing:stop` | `{ conversationId }` | – |

Hook để test: nội dung chứa `#fail` → ack lỗi `SIMULATED_FAILURE`; chứa `#slow` → ack sau 8 giây (vượt timeout 5 giây của client).

### Server → Client
| Event | Payload |
|---|---|
| `message:new` | `{ message }` |
| `message:status` | `{ conversationId, messageId, clientId?, status: "delivered"\|"seen", userId? }` |
| `typing` | `{ conversationId, userId, isTyping }` |
| `presence:update` | `{ userId, online, lastSeenAt }` |
| `feed:new` | `{ count, postId? }` |
| `notification` | `{ type, title, body, data }` |

### Hai loại lỗi kết nối — điểm hay bị nhầm
| Tình huống | Client nhận | Tự reconnect? | App phải làm |
|---|---|---|---|
| Mất mạng, server restart, `drop?mode=transport`, `outageSeconds` | `disconnect("transport close")`, `reconnect_attempt n` | **Có** (theo `reconnectionDelay`…`reconnectionDelayMax`) | Hiện banner "Đang kết nối lại (lần n)" |
| Token sai/hết hạn (bị chặn ở middleware) | `connect_error` với `message = "UNAUTHORIZED"`/`"TOKEN_EXPIRED"` | **Không** | Refresh token → cập nhật `auth` → gọi `socket.connect()` |
| Server chủ động ngắt (`drop?mode=server`) | `disconnect("io server disconnect")` | **Không** | Gọi lại `socket.connect()` nếu hợp lệ |

### Mẫu cấu hình Android (socket.io-client-java 2.x)
```kotlin
val options = IO.Options.builder()
    .setAuth(mapOf("token" to tokenStore.accessToken))
    .setReconnection(true)
    .setReconnectionAttempts(5)
    .setReconnectionDelay(1_000)
    .setReconnectionDelayMax(5_000)
    .setRandomizationFactor(0.5)
    .setTimeout(20_000)
    .build()
val socket = IO.socket(BuildConfig.SOCKET_URL, options)

socket.emit("message:send", payload, object : AckWithTimeout(5_000) {
    override fun onSuccess(vararg args: Any?) { /* cập nhật trạng thái SENT */ }
    override fun onTimeout() { /* đánh dấu FAILED, cho phép gửi lại */ }
})
```

---

## 7. Media mẫu

| Loại | Nguồn | Dùng cho |
|---|---|---|
| Ảnh | `https://picsum.photos/seed/<seed>/<w>/<h>` | Glide: custom size, cache, transform |
| Avatar | `https://ui-avatars.com/api/?name=...` | Glide `circleCrop()` |
| Video MP4 | `https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4` | ExoPlayer |
| Video HLS | `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8` | ExoPlayer (HLS) |
| Giọng nói | `/media/audio/voice-1.wav` … `voice-3.wav` | MediaPlayer (bài viết/tin nhắn thoại) |
| Âm thanh nền | `/media/audio/ambient-rain.wav`, `ambient-ocean.wav` (PCM 16‑bit, mono, 22050 Hz, lặp liền mạch) | **AudioTrack MODE_STREAM** |
| Hiệu ứng | `/media/audio/sfx-shutter.wav`, `sfx-pop.wav`, `sfx-tick.wav` | **AudioTrack MODE_STATIC** (copy vào `res/raw`) |

File WAV được tự sinh khi khởi động server (thư mục `public/audio`), không vướng bản quyền.

---

## 8. Phương án khác: dùng API public, không cần chạy server

Nếu chưa muốn chạy server, phần REST cơ bản có thể dùng **DummyJSON** (`https://dummyjson.com`, miễn phí, không cần đăng ký):

| Tính năng MomentBox | DummyJSON |
|---|---|
| Đăng nhập | `POST /auth/login` `{ "username": "emilys", "password": "emilyspass", "expiresInMins": 1 }` |
| Lấy user hiện tại | `GET /auth/me` (Bearer) |
| Refresh token | `POST /auth/refresh` `{ "refreshToken": "…", "expiresInMins": 1 }` |
| Bảng tin | `GET /posts?limit=10&skip=0&sortBy=id&order=desc` (ghép ảnh từ picsum theo `id`) |
| Tìm kiếm | `GET /posts/search?q=...` |
| Bình luận | `GET /posts/{id}/comments` |
| Người dùng | `GET /users/{id}` |

Hạn chế: DummyJSON **không có** Socket.IO, upload file, bài viết dạng video/voice và envelope `success/data`. Các phần đó dùng mock server này hoặc chế độ **Offline** (MockInterceptor đọc JSON trong `assets/`).
