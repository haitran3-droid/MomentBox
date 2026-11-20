# Bàn giao MomentBox từ máy nhà sang máy công ty

Ngày 20/11/2026. Nhánh bàn giao là `origin/fix/reminder-language`, tách từ `main` tại `58b6796`. Bắt đầu từ nhánh bàn giao để có cả bản sửa và hai ca hồi quy.

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

## Tiếp tục trên máy công ty ngày 20/11/2026

Sau `git fetch origin`, đầu nhánh bàn giao `origin/fix/reminder-language` là `bd5a487`. Commit cha là `fe51dc1`. Commit mới chỉ cập nhật tên nhánh trong tài liệu này. Worktree `momentbox-s05/MomentBox` dùng nhánh local `fix/s05-comment-permissions`. Lúc bắt đầu, bản clone cũ ở `main` có thay đổi dở trong `FeedFragment.kt`, và nhánh M5 local có một commit chưa có trên remote.

### S22b trên Realme RMX3350, Android 13

Ca này đi từ giờ nhắc trong Cài đặt S22 qua hộp thoại đổi ngôn ngữ S22b đến màn lời nhắc S20. Lượt đầu dùng bản QA riêng và tài khoản thử còn lượt nhắc. Sau khi đặt 08:45 và đổi Anh → Việt, màn Cài đặt vẫn báo “8:45 hằng ngày” và “Báo thức chính xác”. Về Home lúc 08:40:34.034. Thời gian dưới đây theo đồng hồ điện thoại, UTC+7.

| Mốc | Máy nhà | Máy công ty · lịch hiện tại | Máy công ty · thử `setAlarmClock` |
|---|---|---|---|
| Giờ hẹn | 07:10:00 | 08:45:00.000 | 12:10:00.000 |
| Android giao báo thức | 07:12:27 khi mở lại app | 10:15:11.260, tính từ `nowRTC` và `last` của lượt đánh thức DAILY trong `dumpsys alarm` | Khoảng 12:35:23, suy từ lúc Android cho phép xử lý broadcast DAILY trong `batterystats`, sai số khoảng 1 giây |
| Android tiếp nhận thông báo | Chưa ghi | 10:15:11.634, sự kiện `notification_enqueue`, ID 5202 | Khoảng 12:35:26.060, suy từ thời điểm hủy và tuổi của thông báo 5202. Android ghi `NOTIFICATION_INTERRUPTION` trên kênh reminder lúc 12:35:26 |
| Thông báo hiển thị | Chưa ghi riêng | 10:15:36.529, `notification_visibility=1` trong ngăn thông báo. S20 mở lúc 10:15:41.799 | Người dùng thấy lúc 12:35. Android ghi `NOTIFICATION_SEEN` lúc 12:35:30, sau đó ghi thao tác của người dùng và mở S20 lúc 12:35:34 |

Độ trễ trước khi Android giao là **1 giờ 30 phút 11,260 giây**. Từ lúc giao đến khi đăng thông báo mất **374 ms**. Lúc về Home, điện thoại sạc USB, tiết kiệm pin tắt và device idle ở trạng thái ACTIVE. Dù app có quyền exact và gọi `setExactAndAllowWhileIdle`, `dumpsys alarm` ghi `exactAllowReason=permission` cùng cửa sổ 201.810 ms và xếp lịch vào nhóm với các báo thức khác. Log Oplus ghi tiến trình bị đóng băng khi ở nền và được mở băng lúc 10:15:11.259, khi Activity trở lại. Các mốc này định vị độ trễ ở khâu giao báo thức của thiết bị. Chưa tách được tác động riêng của việc gom lịch, đóng băng tiến trình và việc app đối soát lại lịch khi mở.

SystemUI từ chối heads-up và toàn màn hình ở lượt này dù kênh khai báo HIGH. Vì vậy có mốc riêng cho lần đầu thông báo hiển thị trong ngăn thông báo. Lần thử 07:10 trên máy nhà thiếu dữ liệu về quyền và thời điểm hiển thị để khẳng định cùng nguyên nhân.

**Lượt đối chứng 12:10.** Bản QA thứ hai dùng `AlarmManager.setAlarmClock` cho lời nhắc hằng ngày có quyền exact, một phương án trong BA S20. Sau khi đổi Anh → Việt và về Home lúc 12:04:06.832, Android ghi đúng giờ 12:10, `window=0`, `flags=0x9`. Máy khóa lúc 12:04:26 rồi mở khóa lại lúc 12:04:32. Đến giờ hẹn, màn hình đang mở và MomentBox vẫn ở nền. Trước khi thông báo xuất hiện, hệ thống ghi nhận tiến trình cũ bị dừng lúc 12:35:23.187. S20 chỉ trở lại tiền cảnh sau khi người dùng thao tác lúc 12:35:34.

**Kết luận S22b còn mở.** Lượt 12:10 vẫn giao trễ khoảng 25 phút 23 giây, dù lịch đã có trong mục báo thức kế tiếp với cửa sổ bằng 0. Việc mở lại app không phải điều kiện cần để tái hiện. Cả hai lượt đều cho thấy phần lớn thời gian trễ xảy ra trước khi dịch vụ đăng thông báo. Dữ liệu còn thiếu để quy cho một chính sách cụ thể của Realme. Ca kiểm tra báo thức kế tiếp thất bại trên bản cũ và đạt trên bản đối chứng (7/7 ca nền tảng), nhưng không dự đoán được giờ giao thực tế. Đã rút thay đổi thử nghiệm `setAlarmClock` và ca kiểm thử chỉ dành cho phương án đó. Bản giao giữ phần sửa S05.

Đã gỡ hai bản QA riêng cùng APK kiểm thử của lượt đối chứng. ADB xác nhận bản MomentBox chính còn trên điện thoại, không còn lịch nhắc QA hay cổng reverse của phiên thử.

### S05 trên máy công ty

Trong `MomentBox_Tai-lieu-BA.docx`, S15 mục 9 cho tác giả chọn “Cho phép bình luận”. S05 mục 8–11 mô tả trả lời, xem phản hồi, nhập và gửi. Trước bản sửa, cả hai giao diện A/B đều cho nhập và trả lời. ViewModel thêm bình luận tạm rồi gọi repository dù bài đã tắt bình luận. Đây là nguyên nhân của HTTP 403 và nút “Thử lại” từng thấy trên máy nhà.

Bản sửa khóa ô nhập, nút gửi, trả lời và thử gửi lại khi chưa tải được bài hoặc khi `allowComments=false`. ViewModel kiểm tra quyền mới nhất trước khi thay đổi danh sách hoặc gọi repository. Người dùng vẫn đọc bình luận và mở phản hồi cũ. Nếu quyền thay đổi trong lúc trả lời, ứng dụng hủy đích trả lời và bỏ tiền tố `@tên` do ứng dụng chèn, giữ phần nháp người dùng đã gõ.

| Kiểm tra S05 | Kết quả trên máy công ty |
|---|---|
| Unit test trước sửa | 4 ca hồi quy mới thất bại. Cả 17 ca có sẵn đạt. |
| Unit test sau sửa | 21/21 đạt, gồm 7 ca của `PostDetailViewModelTest`. Bao phủ bài đóng, chưa có bài, đổi quyền giữa lúc trả lời và thử gửi lại khi mở quyền. |
| Build Android | `:app:assembleDebug` và `:app:assembleDebugAndroidTest` thành công với mock server qua loopback. |
| Bản bàn giao cuối | Sau khi rút thử nghiệm S22b, build debug, build APK kiểm thử và toàn bộ 21 unit test đều đạt với cấu hình mặc định. Android Lint ghi 0 lỗi và 160 cảnh báo. |
| Ô nhập và nút gửi trên Pixel 6a giả lập | Cùng một bài thử mà API trả `allowComments=false`. Trước sửa, ô nhập và nút gửi đều bật ở A/B. Sau sửa, cả hai bố cục hiện “Comments are turned off”, ô nhập và nút gửi bị khóa. Chạm rồi gõ qua ADB không thêm ký tự. |
| Hồi quy giao diện danh sách bình luận | `CommentsAdapterTest` đạt 1/1 trên máy ảo. Ca này thử callback trả lời/thử lại ở hàng cũ ngay khi đóng quyền, đọc và mở phản hồi, rồi kích hoạt lại các thao tác khi quyền được mở. |
| Bài mở bình luận trên cùng máy ảo | Bố cục A gửi bình luận gốc thành công. Bố cục B chèn `@tên` khi trả lời và server ghi nhận đúng `parentId`. Bài đóng vẫn có 0 bình luận sau các lần thử. Đã dọn hai bài thử khỏi mock server. |

## Việc tiếp theo

| Thứ tự | Việc cần kiểm tra hoặc sửa |
|---|---|
| 1. S22b · xác định nguyên nhân trễ ở nền | Đối chiếu cùng bản QA trên thiết bị Android khác với RMX3350. Lấy dấu thời gian lúc Android phát broadcast, ngay đầu `ReminderAlarmReceiver.onReceive` và lúc SystemUI hiển thị. Kiểm tra riêng trường hợp màn hình giữ khóa qua giờ hẹn để đối chiếu tiêu chí S20 của BA. |
| 2. S05 khi tiếp tục phát triển bản debug | Kiểm tra tải phản hồi từ mục thứ 11 và sau khi tự trả lời, rồi đồng bộ `commentCount` giữa chi tiết và bảng tin khi server xác nhận. |
| 3. Trước khi phát hành APK release | Bản release dùng URL HTTP của máy ảo nhưng cấu hình mạng chặn HTTP. Cần cấu hình endpoint HTTPS của môi trường phát hành và kiểm tra đầu vào khi build. |
| 4. Các mục review còn lại | Ưu tiên ngăn đăng trùng bài khi thử lại sau timeout. Các điểm cải thiện tiếp theo gồm tải lịch sử chat khi cache trống, truy vấn thống kê nhật ký theo kỳ, cập nhật danh sách media khi gõ caption, dọn tệp phiên sửa ảnh và giảm tính toán khi sắp xếp bình luận trên mock server. |

## Mở project cũ trên máy công ty

Tại thư mục gốc của bản clone hiện có, chạy `git status --short --branch`, `git remote -v` và `git fetch origin`. Remote dự án là `github.com/haitran3-droid/MomentBox`. Có thể lấy trực tiếp nhánh bàn giao dù `main` trên máy đang cũ.

- Nếu thư mục sạch và máy chưa có nhánh bàn giao, chạy `git switch --track origin/fix/reminder-language`.
- Nếu đã có nhánh local này, chuyển sang nhánh đó và dùng `git merge --ff-only origin/fix/reminder-language`. Kiểm tra `git rev-parse HEAD` và `git rev-parse origin/fix/reminder-language` trả về cùng mã trước khi bắt đầu công việc mới.
- Nếu đang sửa dở, có commit local riêng trên nhánh bàn giao, hoặc Git báo hai nhánh đã tách lịch sử, mở một worktree mới từ nhánh remote. Ví dụ với tên nhánh và thư mục còn trống, chạy `git worktree add --no-track -b fix/tiep-tuc-review ../MomentBox-tiep-tuc origin/fix/reminder-language` rồi mở thư mục mới trong Codex.

**Chạy debug qua USB.** Trong `mock-server`, chạy `npm ci` khi cần cài dependency, rồi `npm start`. Ở cửa sổ khác, dùng `adb devices` để kiểm tra thiết bị và `adb reverse tcp:3000 tcp:3000` để nối cổng. Hai lệnh sau chạy trong `MomentBox_App` để cài bản debug, chạy unit test và bộ kiểm thử M5 trên thiết bị.

```powershell
.\gradlew.bat :app:installDebug "-PmomentboxBaseUrl=http://127.0.0.1:3000/"
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest "-PmomentboxBaseUrl=http://127.0.0.1:3000/" "-Pandroid.testInstrumentationRunnerArguments.class=com.ntq.training.momentbox.M5PlatformContractTest"
```

## Tiếp tục ở phiên sau

> Mở worktree `momentbox-s05/MomentBox` của nhánh `fix/s05-comment-permissions` trên máy công ty. Kiểm tra `git status`, đọc kết quả máy công ty và bảng việc tiếp theo trong `reports/Ban-giao-S20-S22b.md`. Tiếp tục từ các ca còn mở. Ca gọi điện M5 chờ thiết bị thứ hai.
