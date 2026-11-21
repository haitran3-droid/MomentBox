# MomentBox — Dự án đào tạo Android Fresher

App "nhật ký khoảnh khắc đa phương tiện + cộng đồng + chat realtime", phủ toàn bộ sheet "Nội dung đào tạo".

| Thư mục / file | Nội dung |
|---|---|
| `MomentBox_Tai-lieu-BA.docx` | Tài liệu BA bản Word (bản gốc, chỉnh sửa được, nằm ở Claude Docs) |
| `mockups/` | 26 mockup bản sạch (720×1560) |
| `mockups/annotated/` | 26 mockup có đánh số, khớp bảng mô tả trong tài liệu BA |
| `mockups/pairs/` | Thiết kế + đánh số đặt cạnh nhau (ảnh dùng trong tài liệu BA) |
| `mockups/boards/` | 7 board theo nhóm + `00_Tong-quan.png` |
| `mock-server/` | Backend giả lập miễn phí (REST + Socket.IO), xem `mock-server/README.md` |
| `mockup-src/` | Nguồn HTML của mockup. Sửa `src/*.html` rồi chạy `node render.js` để xuất lại ảnh (cần Chrome) |

Chạy mock server:

```bash
cd mock-server
npm install
npm start
node test/smoke.js http://localhost:3000
```

### Cấu hình URL khi build Android

Bản debug mặc định dùng `http://10.0.2.2:3000/` cho Android Emulator. Khi dùng thiết bị thật với `adb reverse tcp:3000 tcp:3000`, chạy từ thư mục `MomentBox_App`:

```powershell
.\gradlew.bat :app:assembleDebug "-PmomentboxBaseUrl=http://127.0.0.1:3000/"
```

Bản release yêu cầu URL HTTPS thực tế của backend theo môi trường. Đặt biến môi trường `MOMENTBOX_RELEASE_BASE_URL` trong cấu hình build rồi chạy `.\gradlew.bat :app:assembleRelease`. Có thể ghi đè biến môi trường bằng Gradle property `momentboxReleaseBaseUrl`. URL phải có hostname, kết thúc bằng `/` và không chứa thông tin đăng nhập, query hoặc fragment. Build release sẽ dừng nếu URL thiếu hoặc sai định dạng.
