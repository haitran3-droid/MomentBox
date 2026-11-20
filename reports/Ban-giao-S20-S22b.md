# Bàn giao MomentBox từ máy nhà sang máy công ty

Ngày 20/11/2026. Nhánh bàn giao là `origin/codex/fix-reminder-language`, tách từ `main` tại `58b6796`. Bắt đầu từ nhánh bàn giao để có cả bản sửa và hai ca hồi quy.

## S20 và S22b đã sửa gì?

- **Đổi ngôn ngữ từng làm tắt nhắc viết nhật ký.** Các hàng Cài đặt dùng chung ID của công tắc. Khi Android tạo lại màn hình, trạng thái tắt của một hàng ẩn có thể ghi đè công tắc đang bật và hủy lịch nhắc. Công tắc nhắc giờ đây lấy trạng thái từ `ReminderScheduler`, kể cả sau khi màn hình được tạo lại.
- **Bản QA có hậu tố mã gói từng bỏ qua báo thức hợp lệ.** Bộ nhận báo thức nay đối chiếu với tên lớp thực tế của `ReminderAlarmReceiver`. App vẫn kiểm tra mã gói, action và URI của sự kiện.

## Kết quả trên máy nhà

| Kiểm tra | Kết quả ngày 20/11 |
|---|---|
| Android debug | Build thành công. 17/17 unit test đạt. |
| Bộ `M5PlatformContractTest` trên thiết bị USB | 6/6 đạt. Ca khôi phục màn Cài đặt đã bắt được lỗi trên bản cũ. |
| Thao tác trên bản chính | Bật nhắc lúc 07:00, đổi Việt → Anh → Việt, thoát rồi mở lại Cài đặt. Công tắc vẫn bật, giờ vẫn là 07:00. |
| Nhận thông báo trên bản QA cùng điện thoại | Đặt 07:10 rồi đưa app về nền. Android giao báo thức lúc 07:12:27 khi mở lại app. Chạm thông báo mở đúng S22b. **Vẫn cần kiểm tra vì sao thông báo đến trễ khi app ở nền.** |

Sau khi thử, đã gỡ bản QA tạm và trả bản chính về tiếng Việt, lời nhắc tắt, giờ lưu 21:00. Ca gọi điện M5 chờ có thiết bị thứ hai.

## Việc tiếp theo

| Thứ tự | Việc cần kiểm tra hoặc sửa |
|---|---|
| 1. Tiếp tục S22b khi có điện thoại | Dùng tài khoản thử còn lượt nhắc trong ngày, bật quyền thông báo, đặt giờ vài phút tới, đổi ngôn ngữ rồi về Home. Ghi giờ hẹn, giờ Android giao báo thức và giờ thông báo xuất hiện trong khi app vẫn ở nền. Đối chiếu với quyền báo thức chính xác và trạng thái pin trước khi kết luận nguyên nhân. |
| 2. S05 khi tiếp tục phát triển bản debug | Bắt đầu với `allowComments=false`. Trên máy thật vẫn gửi được yêu cầu rồi nhận HTTP 403 và hiện “Thử lại”. Khóa ô nhập và thao tác trả lời, kiểm tra quyền trong ViewModel. Sau đó xử lý tải phản hồi từ mục thứ 11 hoặc sau khi tự trả lời, rồi đồng bộ `commentCount` giữa chi tiết và bảng tin khi server xác nhận. |
| 3. Trước khi phát hành APK release | Bản release dùng URL HTTP của máy ảo nhưng cấu hình mạng chặn HTTP. Cần cấu hình endpoint HTTPS của môi trường phát hành và kiểm tra đầu vào khi build. |
| 4. Các mục review còn lại | Ưu tiên ngăn đăng trùng bài khi thử lại sau timeout. Các điểm cải thiện tiếp theo gồm tải lịch sử chat khi cache trống, truy vấn thống kê nhật ký theo kỳ, cập nhật danh sách media khi gõ caption, dọn tệp phiên sửa ảnh và giảm tính toán khi sắp xếp bình luận trên mock server. |

## Mở project cũ trên máy công ty

Tại thư mục gốc của bản clone hiện có, chạy `git status --short --branch`, `git remote -v` và `git fetch origin`. Remote dự án là `github.com/haitran3-droid/MomentBox`. Có thể lấy trực tiếp nhánh bàn giao dù `main` trên máy đang cũ.

- Nếu thư mục sạch và máy chưa có nhánh bàn giao, chạy `git switch --track origin/codex/fix-reminder-language`.
- Nếu đã có nhánh local này, chuyển sang nhánh đó và dùng `git merge --ff-only origin/codex/fix-reminder-language`. Kiểm tra `git rev-parse HEAD` và `git rev-parse origin/codex/fix-reminder-language` trả về cùng mã trước khi bắt đầu công việc mới.
- Nếu đang sửa dở, có commit local riêng trên nhánh bàn giao, hoặc Git báo hai nhánh đã tách lịch sử, mở một worktree mới từ nhánh remote. Ví dụ với tên nhánh và thư mục còn trống, chạy `git worktree add --no-track -b codex/tiep-tuc-review ../MomentBox-tiep-tuc origin/codex/fix-reminder-language` rồi mở thư mục mới trong Codex.

**Chạy debug qua USB.** Trong `mock-server`, chạy `npm ci` khi cần cài dependency, rồi `npm start`. Ở cửa sổ khác, dùng `adb devices` để kiểm tra thiết bị và `adb reverse tcp:3000 tcp:3000` để nối cổng. Hai lệnh sau chạy trong `MomentBox_App` để cài bản debug, chạy unit test và bộ kiểm thử M5 trên thiết bị.

```powershell
.\gradlew.bat :app:installDebug "-PmomentboxBaseUrl=http://127.0.0.1:3000/"
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest "-PmomentboxBaseUrl=http://127.0.0.1:3000/" "-Pandroid.testInstrumentationRunnerArguments.class=com.ntq.training.momentbox.M5PlatformContractTest"
```

## Prompt dán vào Codex ở máy công ty

> Tôi đang mở bản clone MomentBox cũ trên máy công ty. Hãy kiểm tra trạng thái Git và remote, fetch origin rồi lấy đúng nhánh `origin/codex/fix-reminder-language`. Nếu đang sửa dở hoặc có commit local riêng trên nhánh đích, hãy giữ nguyên công việc đó và mở worktree mới từ nhánh remote. Xác minh HEAD trùng với nhánh bàn giao và đọc `reports/Ban-giao-S20-S22b.md`.
>
> Nếu có điện thoại kết nối ADB, kiểm tra tiếp độ trễ nhắc nhật ký S22b khi app ở nền. Ghi rõ giờ đặt, giờ Android giao báo thức và giờ hiện thông báo. Nếu tái hiện được, xác định nguyên nhân trước khi sửa. Nếu chưa có thiết bị hoặc đã kiểm tra xong S22b, bắt đầu sửa S05 cho bài có `allowComments=false`, đối chiếu tài liệu BA và thêm ca hồi quy. Báo lại tệp đã sửa, kết quả test và phần còn mở. Tạm bỏ qua gọi điện M5 đến khi có thiết bị thứ hai.
