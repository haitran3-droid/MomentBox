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
