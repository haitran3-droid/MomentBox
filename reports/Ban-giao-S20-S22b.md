# Bàn giao MomentBox từ máy nhà sang máy công ty

**Kết quả mới nhất trên Realme:** [Phiên xác nhận 20–21/11, kết quả và phần còn mở](#ket-qua-realme-2111).

**Tiếp tục ở máy nhà:** xem mục [Bàn giao về máy nhà](#ban-giao-realme). Các hướng dẫn lấy nhánh ở đầu tài liệu thuộc phiên trước.

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

> Tôi đang mở bản clone MomentBox cũ trên máy công ty. Hãy kiểm tra trạng thái Git và remote, fetch origin rồi lấy đúng nhánh `origin/fix/reminder-language`. Nếu đang sửa dở hoặc có commit local riêng trên nhánh đích, hãy giữ nguyên công việc đó và mở worktree mới từ nhánh remote. Xác minh HEAD trùng với nhánh bàn giao và đọc `reports/Ban-giao-S20-S22b.md`.
>
> Nếu có điện thoại kết nối ADB, kiểm tra tiếp độ trễ nhắc nhật ký S22b khi app ở nền. Ghi rõ giờ đặt, giờ Android giao báo thức và giờ hiện thông báo. Nếu tái hiện được, xác định nguyên nhân trước khi sửa. Nếu chưa có thiết bị hoặc đã kiểm tra xong S22b, bắt đầu sửa S05 cho bài có `allowComments=false`, đối chiếu tài liệu BA và thêm ca hồi quy. Báo lại tệp đã sửa, kết quả test và phần còn mở. Tạm bỏ qua gọi điện M5 đến khi có thiết bị thứ hai.

## Kiểm tra lại sau khi pull `main` – 20/11/2026

Bản clone gốc ở `main` (`dca3eaa`, chứa `fe51dc1`). Bản đối chiếu S05 ở `fix/s05-comment-permissions` (`a1f175f`, PR #2). Các lượt dưới đây dùng APK debug có mã gói QA riêng và một mock server cục bộ. Giờ trong bảng lấy từ đồng hồ từng thiết bị, UTC+7.

### S22b – đổi ngôn ngữ, về Home và chờ lời nhắc

Đã bật nhắc nhật ký, chọn giờ trong hộp chọn giờ của Cài đặt, đổi sang tiếng Việt và kiểm tra lại công tắc cùng giờ hẹn sau khi màn hình được tạo lại. Cả hai thiết bị đều giữ đúng cài đặt. Sau khi kết thúc ca kiểm thử giao diện, đã mở Activity khởi động của app QA rồi đưa về Home để đo báo thức ngoài phiên instrumentation.

| Thiết bị và bản chạy | Giờ hẹn | Android giao broadcast DAILY | Android nhận đăng thông báo 5202 | System UI ghi nhận hiển thị |
| --- | --- | --- | --- | --- |
| Realme RMX3350, Android 13, `main`, app ghi `EXACT` | 14:01:00.000 | 14:05:05.845, khi mở lại app | 14:05:06.064 | 14:05:33.373, khi mở ngăn thông báo |
| Máy ảo Android 14, `main`, app ghi `INEXACT` | 14:04:00.000 | 14:06:30.922, app vẫn ở nền | 14:06:40.489 | 14:06:43.933, System UI tự hiển thị khi vẫn ở Home |

Giờ giao lấy từ `dispatchClockTime` trong lịch sử broadcast của Android. Giờ đăng lấy từ sự kiện `notification_enqueue`. Giờ hiển thị lấy từ `notification_visibility=1` của đúng gói QA và ID 5202.

**Realme tái hiện trễ 4 phút 5,845 giây.** Lúc 14:04:50, Home vẫn ở tiền cảnh và chưa có thông báo 5202. Bắt đầu mở lại app lúc 14:05:05.683. Hệ thống giao DAILY lúc 14:05:05.845 và nhận thông báo sau đó 219 ms. System UI ghi lý do bỏ qua hiển thị nổi là mức quan trọng của thông báo. Khi mở ngăn thông báo đã đọc được “Đến giờ viết nhật ký rồi ✍️”.

Phần trễ chính của lượt Realme nằm trước khi Android giao broadcast. Máy đang sạc, màn hình mở, device idle ở `ACTIVE`. Dù app ghi `EXACT`, `dumpsys alarm` ghi cửa sổ giao 2 phút 38,851 giây. Oplus Hans ghi trạng thái hạn chế cho UID của bản QA lúc 13:57:46.657, rồi bỏ hạn chế lúc 14:05:05.841, trước giờ giao 4 ms. Các mốc này khoanh vùng ảnh hưởng của quản lý nền trên Realme. Cần lượt đối chứng trên cùng máy, cùng quyền và cùng kênh thông báo khi cho phép chạy nền để tách tác động của Hans khỏi việc gom báo thức. Máy ảo chạy chế độ `INEXACT` và đã giao khi app vẫn ở nền.

### S05 – bài khóa và mở bình luận

Đối chiếu quyền “Cho phép bình luận” ở S15 mục 9 với thao tác nhập, trả lời, đọc phản hồi và gửi ở S05 mục 8–11 trong tài liệu BA. Đã mở màn hình chi tiết A và B trên máy ảo với hai bài riêng, nhập và bấm Gửi ở những nơi giao diện cho phép, rồi đọc lại bình luận qua API.

| Ca kiểm tra | `main` (`dca3eaa`) | PR #2 (`a1f175f`) |
| --- | --- | --- |
| Bài khóa bình luận, giao diện A và B | Cả hai vẫn cho nhập và bấm Gửi. Server nhận hai POST, đều trả 403. Bài vẫn có 0 bình luận. | Cả hai khóa ô nhập và nút Gửi. Server không nhận POST cho bài khóa. |
| Bài mở bình luận, giao diện A và B | Hai lần gửi trả 201. Đọc lại được cả hai nội dung. | Hai lần gửi trả 201. Đọc lại được cả hai nội dung. |
| Trả lời và thử lại trên bình luận cũ khi quyền thay đổi | Lượt này chỉ thao tác nhập và gửi trên màn hình chi tiết của `main`. | `CommentsAdapterTest` đạt 1/1. Đóng quyền chặn trả lời và thử lại trên hàng cũ, vẫn đọc và mở được phản hồi. Mở quyền thì thao tác hoạt động lại. |

Unit test đạt **17/17 trên main**, **21/21 trên PR #2**, không có ca bỏ qua. Trên Realme, sáu ca `M5PlatformContractTest` đạt **6/6**. Đây là các ca kiểm tra nền tảng, gồm cả khôi phục Cài đặt và truyền dữ liệu báo thức.

**Còn mở sau phiên này**

- S22b trên Realme vẫn giao trễ. Chưa sửa mã xử lý báo thức trong phiên kiểm tra này.
- S05 trên `main` vẫn có lỗi. Bản sửa đã qua các ca trên ở PR #2 và còn chờ tích hợp. Các ca tải phản hồi từ mục thứ 11 và đồng bộ `commentCount` vẫn cần kiểm tra riêng.
- Gọi điện M5 hai đầu vẫn chờ thiết bị thứ hai.

<a id="ban-giao-realme"></a>

## Bàn giao về máy nhà – kiểm thử toàn app trên Realme, 20/11/2026

**Bản đã chạy:** `main` tại `dca3eaa`. Thiết bị Realme RMX3350, Android 13, UTC+7. Dùng APK debug có mã gói QA riêng, tài khoản demo và mock server cục bộ. Các bước dưới đây được thực hiện bằng cách bấm, nhập, cuộn và quay lại qua giao diện. API chỉ dùng để chuẩn bị dữ liệu và kiểm tra kết quả lưu. Phiên dừng theo yêu cầu trước khi hoàn thành mọi ca BA. Các dòng có nhắc máy ảo ở phần trên là kết quả của phiên trước.

### Lỗi tái hiện trên Realme

| Ưu tiên | Bước tái hiện trên bản đã chạy | Kết quả và hướng xử lý |
| --- | --- | --- |
| Cao · S05, quyền bình luận | Mở bài riêng tư `QA Realme locked` đã tắt bình luận, nhập `QA505`, bấm Gửi, rồi bấm Retry. | Vẫn nhập được. Xuất hiện bình luận tạm với trạng thái `Failed (Retry)`. Server nhận hai POST và trả 403 cả hai, bài có 0 bình luận. Kiểm tra nhánh `fix/s05-comment-permissions` (`a1f175f`, PR #2) trên Realme trước khi tích hợp. Thử cả A/B, trả lời và thử lại khi quyền thay đổi. |
| Cao · S05, mất phản hồi từ mục 11 | Mở bài `QA Realme 12 replies`, mở luồng có 12 phản hồi rồi cuộn tới cuối. | UI ghi `Hide 10 replies` và dừng ở `QA child 10`. App chỉ gọi trang 1, cỡ 10. Gọi riêng trang 2 trên cùng server trả về `QA child 11` và `QA child 12`. `PostDetailViewModel.kt:264` dừng tải khi danh sách con đã có phần tử. Bổ sung trạng thái phân trang cho từng bình luận cha, kể cả khi người dùng vừa tự trả lời. |
| Vừa · S04/S05, số bình luận cũ | Ở bài mẫu số 1 có bộ đếm 1, bấm Trả lời một bình luận, gửi rồi quay về bảng tin. | Server trả 201, thấy phản hồi mới ngay trong chi tiết. Bộ đếm ở đầu bài và bảng tin vẫn là 1, trong khi API của cùng bài trả 2. `FeedRepositoryImpl.kt:773` và `PostDetailActivity.kt:396` là hai điểm cần đối chiếu khi cập nhật số đếm sau xác nhận của server. |
| Vừa · S10, nút Tiếp bị cắt | Mở Select media, chọn album `MomentBox-QA-20261120`, lọc Photos và chọn một ảnh. | Trên màn hình rộng 1080 px, vùng nhìn thấy của nút Next chỉ từ x=1026 tới x=1080. Ảnh màn hình cho thấy nhãn bị cắt; vẫn bấm được phần còn lộ. Trong `fragment_gallery.xml:27`, tên album dùng `wrap_content` rồi mới đến khoảng trống có trọng số và nút Next. Cho tên album dùng phần rộng còn lại, rút gọn một dòng; thử lại với album dài và cỡ chữ lớn. |
| Thấp · S14, nhãn trợ năng sai trước lần ghi đầu | Mở Recorder lần đầu và đọc nhãn nút ghi ở trạng thái 00:00. | Màn hình ghi `Tap to start`, nút có nhãn trợ năng `Resume`. Khi đã ghi và tạm dừng, nhãn Pause/Resume đúng. `RecorderActivity.kt:234` dùng chung nhãn Resume cho mọi trạng thái ngoài RECORDING. Tách nhãn bắt đầu và tiếp tục. |

### Lời nhắc S20/S22b

| Tình huống Realme | Giờ hẹn | Android giao DAILY | Android nhận thông báo | Thông báo thực sự thấy |
| --- | --- | --- | --- | --- |
| Lượt nền trước đó cùng ngày, mở lại app | 14:01:00.000 | 14:05:05.845 | 14:05:06.064 | 14:05:33.373 trong ngăn thông báo |
| Lượt toàn app, đã lưu nhật ký hôm nay, đang dùng app | 17:20:00.000 | 17:20:50.095 | App bỏ qua, log `ALREADY_WRITTEN` lúc 17:20:50.228 | Không có thông báo nhắc nhật ký cho lượt này |

Ở lượt 14:01, độ trễ chính xảy ra trước khi Android giao broadcast. Log Oplus Hans và cửa sổ giao báo thức đã ghi ở phần trên. Lượt 17:20 xác nhận nhánh bỏ qua khi đã viết nhật ký. Sau đó đã xoá bài QA và xác nhận danh sách trống. Khi dừng, hộp chọn giờ vẫn hiển thị 17:20, chưa lưu lịch 17:30. Đã gỡ bản QA trên Realme và dừng mock server của phiên.

### Phạm vi đã thao tác và phần còn thiếu

| Màn hình BA | Đã thao tác hoặc quan sát | Còn kiểm tra |
| --- | --- | --- |
| S01–S03 | Mở từ bản QA mới cài, đổi Anh → Việt trong onboarding, bấm Bỏ qua, vào feed bằng tài khoản demo. | Đăng nhập tay với mật khẩu sai/đúng, hết phiên và mở lại khi mất mạng. |
| S04–S06 | Thích, đổi trạng thái lưu bài, tìm bài theo caption, mở chi tiết, gửi phản hồi, mở video QA toàn màn hình và quay lại. | Tua, tốc độ, khóa điều khiển, xoay máy và khôi phục vị trí phát. Kiểm tra thêm layout A trên Realme. |
| S07–S09 | Chọn mood, nhập nội dung, lưu nháp, khôi phục và lưu bài. Tìm theo từ khóa, mở bộ lọc; thống kê hiện 1 bài, 6 từ. Đổi kỳ tháng/năm. Vuốt xóa, Hoàn tác thấy bài trở lại, xóa lần nữa thấy danh sách trống. | Lọc kết hợp, chuyển về kỳ trước, ngày biên, khôi phục sau khi tiến trình bị dừng. Thử lại nháp nhiều lần để xác định có chồng dialog hay không. |
| S10–S13 | Vào album QA, lọc ảnh, chọn một ảnh, xoay/lật ở màn cắt, thêm chữ, Undo/Redo, lưu ảnh sang màn đăng. | S11 chưa kiểm tra chụp/quay. Chụp mẫu không có nội dung cá nhân; kiểm tra timer, quyền bị từ chối, lưu/hủy và mở lại. Đối chiếu nội dung ảnh xuất sau chỉnh sửa. |
| S14–S15 | Ghi âm, tạm dừng, tiếp tục, lưu và gắn vào nhật ký. Đăng ảnh đã sửa với caption `QA515`, chọn Only me và tắt bình luận. API xác nhận bài PHOTO, private, 1 media, allowComments=false. | Thử lại sau timeout. Kiểm tra bài vừa đăng có xuất hiện đầu feed khi ở đầu danh sách, không bật bộ lọc và không kéo làm mới. |
| S16–S19 | Tìm Lan Anh, gửi `QA555`, nhận phản hồi bot; bản xem trước ở danh sách đã cập nhật. Mở bản ghi từ nhật ký, thấy PlayerService kết nối AIDL và thời lượng 7 giây; thao tác yêu thích, tốc độ, hàng đợi. Log có thông báo phát âm thanh. | Phát âm thanh qua loa/tai nghe, điều khiển từ thông báo, trả lời nhanh và đánh dấu đã đọc, hàng đợi sau mất kết nối. Gọi điện M5 hai đầu chờ thiết bị thứ hai. |
| S20–S23 | Xem bảng giờ ở trên. Mở tab đã lưu, sửa bio và thấy giá trị mới, API trả 200. Đổi ngôn ngữ và giao diện tối, xóa cache, xem quyền, ngắt rồi nối lại socket ở Developer tools và thấy Connected. | S20 khi chưa viết và app ở nền, chạm thông báo, báo lại, loại bỏ. Kiểm tra xuất/nhập JSON, đổi nguồn dữ liệu, mô phỏng 401 và đăng xuất/đăng nhập lại. |

### Review code trên `dca3eaa`

Unit test đạt **17/17**. `:app:lintDebug` hoàn tất với **0 lỗi, 160 cảnh báo**. Bảng sau ghi các vấn đề có thể xử lý riêng, kể cả khi ca giao diện thông thường vẫn chạy được.

| Mức | Vị trí và vấn đề | Thay đổi và cách kiểm chứng |
| --- | --- | --- |
| Cao trước phát hành | `MomentBox_App/app/build.gradle.kts:53` mặc định URL release là HTTP của máy ảo, còn network security config của main chặn cleartext. Bản QA dùng cấu hình debug. | Cấu hình endpoint HTTPS của môi trường phát hành, kiểm tra đầu vào lúc build; chạy đăng nhập và tải feed bằng bản release. |
| Cao khi thử lại đăng bài | `UploadRepository.kt:323` gửi POST rồi mới lưu biên nhận. `ComposeFragment.kt:234` cho gửi lại sau cảnh báo kết quả chưa rõ. Mock server tạo ID mới theo từng POST. Nếu server đã lưu nhưng phản hồi bị mất, có nguy cơ tạo bài thứ hai. | Dùng mã yêu cầu bền vững theo lần đăng và xử lý chống trùng ở server. Thử ngắt phản hồi sau khi server lưu, rồi Retry và đếm bài. Đây là rủi ro từ review, chưa tái hiện trong phiên. |
| Vừa · S15 | `ComposeFragment.kt:150` cập nhật draft mỗi lần gõ; collector tại dòng 342–343 gán lại danh sách media và gọi `notifyDataSetChanged()` dù chỉ đổi caption. | Chỉ cập nhật adapter khi danh sách media đổi. Đếm số lần bind thumbnail khi gõ caption dài và khi kéo đổi thứ tự. |
| Vừa · S09 | `JournalStatsViewModel.kt:120` gọi `getEntriesForStats()` không có khoảng ngày. `JournalRepositoryImpl.kt:307` đọc tất cả entry cùng quan hệ rồi mới tính kỳ đang xem. | Truy vấn dữ liệu cần cho kỳ hiện tại và kỳ so sánh; tính chuỗi ngày liên tục bằng truy vấn riêng để giữ đúng kết quả. Đối chiếu số liệu và thời gian với lịch sử nhiều năm. |
| Vừa · S16/S17 | `ChatRepository.kt:412` lặp theo cursor đến khi gặp tin đã biết hoặc hết lịch sử. Khi cache trống, lượt đồng bộ ban đầu có thể đọc nhiều trang ngoài nhu cầu mở màn hình. | Tách khởi tạo cache khỏi bù tin bị lỡ; giữ phân trang khi cuộn. Đếm request với nhiều phòng, lịch sử dài và ca kết nối lại. |

### Thứ tự tiếp tục tại nhà

1. Lấy bản bàn giao từ `origin/main`. Kiểm tra PR #2 và chạy lại S05 A/B trên **Realme** với bài khóa có bình luận cũ và bài mở. Tách kết quả của nhánh sửa khỏi kết quả của main. Ưu tiên tiếp theo là hai lỗi phản hồi và bộ đếm.
2. Khi QA chưa viết nhật ký trong ngày, đặt một giờ mới, báo giờ hẹn, đổi ngôn ngữ rồi về Home. Ghi riêng giờ hẹn, giờ Android giao, giờ đăng và giờ thấy thông báo. Nếu trễ, lưu lịch sử broadcast, trạng thái app và log OEM trước khi sửa.
3. Hoàn tất S11 và các cột còn kiểm tra ở trên. Sửa lỗi nút Next và nhãn nút ghi, rồi xử lý từng điểm review bằng ca hồi quy hoặc phép đo phù hợp. Ghi commit, thiết bị, bước tái hiện và bằng chứng vào cùng file này.

## Phiên máy nhà, tối 20/11/2026 (UTC+7)

Worktree riêng trên nhánh `fix/realme-home`, từ `origin/main` tại `b5c8f50`. Khi bắt đầu, PR #2 đang mở. Đã áp dụng commit `a1f175f` của PR lên nền bàn giao để kiểm thử cục bộ (`add57de`). Thư mục làm việc cũ vẫn giữ các báo cáo chưa commit.

Thiết bị Realme RMX3350 kết nối USB. Bản debug QA có mã gói `com.ntq.training.momentbox.reminderqa`. Hai bài mẫu đều có bình luận cũ và 12 phản hồi của cùng một bình luận cha. Mở bản A và B bằng deep link trỏ cùng bài, sau đó thao tác trong giao diện. Proxy QA tạo một lỗi 503 trước khi lưu cho lần gửi đầu có chữ RetryQA.

- **Trước sửa phân trang – bản có PR #2.** Cả A và B hiển thị “Comments are turned off”, ô nhập và nút gửi bị vô hiệu hóa ở bài khóa, hàng bình luận không có nút trả lời. Mở rộng và cuộn vẫn đọc được nội dung cũ. Cả hai dừng ở QA child 10 dù nhãn ban đầu báo 12. Bản B của bài mở cho nhập và gửi, lỗi 503 hiện đúng hàng “Failed (Retry)”.

- **Gửi và thử lại trên bản PR.** Bản B hiện bình luận mới sau khi bấm “Failed (Retry)”, server trả 201, nhưng bộ đếm vẫn là 14. Ở bản A, đã trả lời trực tiếp một bình luận, thấy đúng tiền tố tên tác giả. Một lần gửi bình luận gốc riêng nhận 503, bấm thử lại thì chuyển thành “Just now” và server trả 201.
- **Sau sửa phân trang và bộ đếm.** Trên cùng Realme, bài khóa bảo toàn trạng thái chỉ đọc trong cả A/B. Bấm “Show more replies” rồi cuộn thấy cả “QA child 11” và “QA child 12” ở mỗi bản. Bài mở trên bản B ban đầu có 14 bình luận. Trả lời khi chưa mở luồng, proxy trả 503, số đếm vẫn là 14. Bấm thử lại, phản hồi xuất hiện, số đếm thành 15. Nhấn Back và cuộn đến chính bài đó trên bảng tin cũng thấy 15. Đã phát hiện thêm header A trước đây chỉ hiển thị layout tĩnh và đã nối với cùng nguồn dữ liệu của B.
- **S11.** Đã vào Camera từ nút Tạo, chọn timer 3 giây, chụp vật mẫu bằng camera sau và thấy màn “Crop & rotate”. Bấm Close quay về camera.
- **S22b.** Lượt 18:25 đã đặt và xác nhận ô lịch bằng UI. ADB gián đoạn trước khi quan sát được đổi ngôn ngữ, về Home và giao thông báo. Khi kết nối lại, hệ thống đã đăng ký lượt 18:25 cho ngày mai. Không dùng lượt này để kết luận thời điểm giao. Ở lượt riêng trên gói QA mới `com.ntq.training.momentbox.homeqa`, đã thấy nhật ký trống, bật thông báo qua cài đặt Android, đặt 21:25 rồi đổi Anh → Việt. Giờ lưu vẫn là 21:25, công tắc vẫn bật. Sau khi về Home, thiết bị báo 21:19:57.688. AlarmManager ghi `RTC_WAKEUP`, giờ yêu cầu 21:25:00.000, cửa sổ dài 3 phút 52.383 giây.


### S22b trên Realme khi tài khoản chưa viết nhật ký

Lượt này dùng gói `com.ntq.training.momentbox.homeqa`. Nhật ký trống, lời nhắc đã bật. Đã báo giờ hẹn trước khi đổi tiếng Anh sang tiếng Việt và trở về Home. Các mốc dưới đây đều là giờ trên điện thoại, UTC+7 ngày 20/11/2026.

| Sự kiện | Giờ | Bằng chứng |
| --- | --- | --- |
| Hẹn | 21:25:00.000 | Giao diện vẫn hiện 21:25 sau đổi ngôn ngữ. AlarmManager lưu đúng giờ yêu cầu. |
| Android khởi động tiến trình để giao broadcast | 22:24:41.874 | Sự kiện `am_proc_start` nêu đích danh `ReminderAlarmReceiver`, lý do `broadcast`, PID 23229. BatteryStats cũng ghi cho phép tạm thời với action `reminder.DAILY`. Không còn dấu thời gian riêng lúc vào `onReceive`. |
| Android nhận thông báo | 22:24:45.325 | `notification_enqueue` đầu tiên của gói QA, PID 23229, ID 5202. |
| Thông báo xuất hiện trên giao diện hệ thống | 22:25:00.666 | `notification_visibility=1`, sau khi đăng 15.341 giây. Có sự kiện chạm vào đúng thông báo lúc 22:25:21.523. |

Hệ thống dừng tiến trình cũ lúc 22:24:41.661 rồi khởi động tiến trình nhận broadcast. Từ lúc hẹn đến khi khởi động receiver là 59 phút 41.874 giây. Từ đó tới khi đăng thông báo chỉ có 3.451 giây. Khi kiểm tra lúc 21:26, điện thoại đang sạc và Android báo trạng thái hoạt động. Cửa sổ giao mà hệ thống ghi lúc đặt lịch cũng đã qua từ lâu. Dữ liệu khoanh vùng độ trễ ở trước bước ứng dụng xử lý. Chưa có log chỉ rõ chính sách nào của Realme giữ lịch. Giữ cách đặt lịch hiện tại.


### S05 trên APK đã sửa

APK debug `com.ntq.training.momentbox.reminderqa` tại `add57de` cộng các sửa đổi trong worktree, SHA-256 `B958BB55C4196E881FB42026BA95D97699E3DFF1E7ECF89259017AB0BD61476E`.

| Tình huống trên Realme | Kết quả |
| --- | --- |
| Header A của bài mở | Hiện đúng caption “QA open”, hashtag `#coffee #weekend` và số do server trả về. Gửi bình luận gốc thành công làm số tăng 14 → 15. |
| A, bấm thử lại bình luận gốc | Proxy trả 503 lần đầu, hiện “Failed (Retry)”, số vẫn là 15. Bấm vào dòng lỗi, server trả 201 và số tăng lên 16 ngay trên màn hình. |
| A và B, bài bị khóa sau khi đã mở | Mỗi lượt đều bắt đầu với ô nhập và nút gửi hoạt động. Proxy mô phỏng chủ bài khóa trong lúc đang nhập. Mỗi giao diện nhận một POST bị từ chối 403 rồi tải lại quyền. Cả hai hiển thị “Comments are turned off”, vô hiệu hóa ô nhập, nút gửi và thao tác thử lại. Chạm vào dòng “Not sent” không phát sinh thêm POST. Bộ đếm vẫn là 16. |

Ca hồi quy bao gồm phân trang độc lập cho từng bình luận cha, thử lại đúng trang gặp lỗi, cập nhật bộ đếm khi server xác nhận, phản hồi khi chưa mở luồng, quyền bị thu hồi, và cách hiển thị từng hàng bình luận. Kết quả thao tác trên các bản trước đó được ghi riêng ở đầu mục phiên máy nhà.

### Thư viện ảnh, máy ảnh, đăng bài và kết quả review

- **S10.** Tạo riêng album `MomentBox-QA-Home-20261120-Long-Album-Next` có đúng một ảnh hình khối mẫu. Trong bộ lọc Photos, chọn ảnh đó. Trên màn rộng 1080 px, nút “Next (1)” nằm hoàn toàn trong khoảng x=806–1038, rộng 232 px. Bấm được nút để sang cắt ảnh và lưu qua trình chỉnh sửa. Ca hồi quy trên thiết bị cũng kiểm tra chiều rộng 320 dp và cỡ chữ 150%.
- **S11.** Sau khi cấp quyền mic, giữ nút quay trên camera sau, thả tay và thấy clip 0:01 được chọn trong thư viện. Bấm Next tới màn đăng, rồi chọn Discard ở hộp thoại bản nháp. Ảnh/video chụp vật mẫu chỉ phục vụ kiểm thử cục bộ.
- **S15, mất phản hồi sau khi server đã lưu.** Từ thư viện, chọn ảnh mẫu và quyền “Only me”, nhập `QA515home`. Proxy nhận HTTP 201 rồi đóng kết nối trước khi trả dữ liệu về app. Màn đăng giữ nội dung và hiện “Posting unconfirmed. Draft preserved.”. Bấm Retry trả HTTP 200. Kiểm tra danh sách của tài khoản trên server thấy đúng một bài, ID 49, một ảnh, quyền private.
- **S04 sau khi đăng.** Khi màn đăng đóng, hàng Tin và bài cũ “QA locked” vẫn nằm trước mắt. Vuốt xuống trong danh sách mới thấy bài `QA515home` nằm *phía trên* hàng Tin. Màn hình đã có dữ liệu mới nhưng vị trí hiển thị sau khi chèn bị sai.
- **S09 và S16.** Ca instrumentation trên Realme dùng Room trong bộ nhớ. Thống kê đối chiếu kỳ trước, ngày biên, DST, bài đã xóa và chuỗi liên tục 40 ngày. Với ba phòng chat trống cache, mỗi phòng chỉ yêu cầu một trang 30 tin. Cuộn lấy được 60 tin. Sau khi mô phỏng thêm 75 tin trên server, đồng bộ lại lấy đúng ba trang và giữ đủ các ID. Phép đo riêng bằng SQLite trên máy tính với 29.192 dòng cho cùng kết quả 240 bài kỳ này và 232 kỳ trước. Trung vị 9 lượt giảm từ 78,206 ms xuống 0,757 ms. Đây là số đo trên máy tính.
- **Cấu hình release.** Gradle yêu cầu URL HTTPS cho bản release, có kiểm tra trường hợp thiếu hoặc nhập HTTP. Đã build APK release unsigned với URL kiểm thử `https://qa.example.invalid/`. Đăng nhập/tải feed trên môi trường phát hành vẫn cần endpoint thật.


### Bản xác nhận sau sửa thứ tự bảng tin

- Test `FeedPostOrderTest` tái hiện lỗi thứ tự trước khi sửa. Bản mới đặt bài vừa đăng ngay sau hàng Tin, loại ID trùng với trang feed và giữ thứ tự các hàng còn lại. Đã cài trên Realme APK SHA-256 `385BC5BEF27BF09F5DF85B3504BA68A7FAFC93F3084DBF81A31E9E717A1E4CD9`. Trước khi đăng, đầu feed có hàng Tin và bài `QA515home`. Sau khi đăng `QA515-2026` với quyền Only me, hàng Tin vẫn ở đầu, bài mới nằm ngay bên dưới, trước `QA515home`. Không cuộn và không kéo làm mới sau khi bấm đăng.
- **S11.** Ở gói QA còn chưa có quyền camera, chọn Không cho phép. Ứng dụng hiện “Cần quyền máy ảnh để chụp.”. Chạm thông báo và cấp quyền trong khi sử dụng, camera hiện lại thanh zoom và nút chụp. Trên gói QA chính của phiên, chụp vật mẫu, xác nhận cắt, lưu qua trình chỉnh sửa, lưu nháp ở màn đăng. Mở lại qua Tạo → Text → Restore, thấy lại nội dung đã nhập và ảnh. Sau đó bỏ bản nháp.
- **S14.** Trước khi ghi, nhãn trợ năng của nút là “Start recording”, đồng hồ 00:00.0. Bấm để ghi, bấm tiếp để tạm dừng, nút chuyển thành “Resume”. Tiếp tục ghi, màn hình hiện “Recording” và đồng hồ chạy, sau đó tạm dừng và bỏ bản ghi. Đồng hồ trở lại 00:00.0 cùng nhãn bắt đầu.
- **Kết quả tự động.** Bản cuối build debug và androidTest thành công. Unit test 26/26. Trên Realme 17/17 ca instrumentation đạt, bao gồm hai ca nhật ký M3 với 24 bài mẫu trong gói QA. Lint debug ghi 0 lỗi, 178 cảnh báo. Backend đạt 12/12 ca Node và 34/34 kiểm tra smoke. Không có cuộc gọi M5 hai đầu trong những con số này.

### Đối chiếu tối 20/11 và sáng 21/11/2026 tại máy nhà

- **S22 xuất JSON.** Từ Cài đặt, bấm “Export journal (JSON)” rồi xác nhận. App báo “Journal JSON exported”. Đọc lại đúng tệp do gói QA tạo trong Downloads cho thấy `schemaVersion=1`, đủ 24 bài M3, bài mẫu đầu có hai thông tin đính kèm, `includesMediaFiles=false`. Tài liệu BA yêu cầu xuất nhật ký. Trong phần S22 không có yêu cầu nhập lại JSON.
- **S22 làm mới phiên.** Tại Developer tools chọn “Simulate HTTP 401”. Yêu cầu hồ sơ trả 401, refresh token trả 200 và app báo đã tải lại hồ sơ. Đăng xuất qua nút xác nhận cũng trả 200.
- **S20, lịch 23:16 tối 20/11.** Đã lưu giờ qua bộ chọn Android, kiểm tra công tắc vẫn bật rồi về Home. Bộ theo dõi trên máy tính chỉ còn dòng khởi động. Lịch sử Android ghi tiến trình của gói QA thoát với `REMOVE TASK` lúc 23:16:16.728, sau đó một tiến trình khác bị hệ thống dừng lúc 23:16:58.498. Sáng hôm sau gói ở trạng thái `stopped=true`. Không xác định được thời điểm giao, hiển thị hoặc thao tác báo lại của lượt 23:16.
- Sáng 21/11 đã fetch lại. `origin/main` vẫn là `b5c8f50`, PR #2 đang mở và nhánh nguồn vẫn tại `a1f175f`. Máy chỉ chạy các ca giao diện trên Realme RMX3350. Bản APK xác nhận ở mục trên được cài lại cho người dùng Android hiện tại.

**S01, đăng nhập bằng bàn phím.** Từ màn hình đăng nhập, nhập email của tài khoản demo và mật khẩu sai. Mock trả HTTP 401, giao diện hiện “Incorrect email or password” và giữ người dùng ở màn đăng nhập. Sửa mật khẩu rồi gửi, server trả 200 và giao diện hiện các tab Feed/Tạo. Hai lượt này đều bấm nút Log in.

**S06, video và xoay màn hình.** Trên Realme, mở bài QA có video cục bộ dài 09:56, bấm Play từ S05 và dừng ở 00:23. Nút tua tiến chuyển đến 00:33. Chọn tốc độ 1.5x, khóa điều khiển, thấy nút mở khóa và các nút tua ẩn đi. Mở khóa rồi xoay bằng nút trong trình phát. Cả lúc ngang và khi bấm Back về dọc đều giữ 00:33 và 1.5x. Nhấn Back lần nữa trở về bài viết.

**S08–S09, lọc kết hợp và so sánh kỳ.** Với 24 bài mẫu, chọn đồng thời mood Great, có ảnh và có âm thanh. Nút Apply báo 1 kết quả và danh sách hiện đúng `M3 QA 01`. Rời rồi mở lại nhật ký vẫn thấy dấu bộ lọc và cùng bài. Sau khi đặt lại bộ lọc, bấm nhãn khoảng ngày trên biểu đồ để mở điều hướng kỳ. Tháng 11 tính tới ngày 21 có 21 bài, tháng 10 có 3. Bấm trở lại tháng 11 lại thấy 21 bài và nút sang tương lai bị khóa.

### S22b ngày 21/11, đo đủ bốn mốc (giờ Việt Nam)

Gói `com.ntq.training.momentbox.homeqa` hiện “Chưa có dòng nhật ký nào”. Sau khi đặt lịch và báo giờ hẹn, đổi từ tiếng Việt sang tiếng Anh. Cài đặt vẫn hiện “Every day at 09:53” và công tắc bật. Khi đã về Home, đồng hồ thiết bị là 09:49:48.540. Android ghi `RTC_WAKEUP` và cửa sổ giao 2 phút 30.189 giây.

| Sự kiện | Giờ trên Realme |
| --- | --- |
| Giờ hẹn trong app và AlarmManager | 09:53:00.000 |
| Android giao `reminder.DAILY` | 09:59:04.392 |
| NotificationManager nhận thông báo ID 5202 | 09:59:04.792 |
| Lần hiển thị đầu tiên trong ngăn thông báo | 09:59:38.112 |

Lúc 09:59:04.356 bắt đầu mở lại app. OplusHansManager ghi chính UID của gói QA chuyển từ đóng băng sang chạy lúc 09:59:04.383, lý do `Activity`. Android đưa broadcast vào hàng đợi lúc 09:59:04.391 và giao lúc 09:59:04.392. Receiver ghi `timing=EXACT result=PRESENTED` lúc 09:59:04.815. Trong lượt này, Android giao trễ 6 phút 4.392 giây, ngay sau khi Realme mở đóng băng tiến trình. Phần ứng dụng từ lúc giao đến khi hệ thống nhận thông báo mất 400 ms. Không thay đổi mã đặt lịch hay cài đặt tiết kiệm pin toàn máy.

Mở ngăn thông báo, đọc được “Time to write your journal” qua giao diện lúc 09:59:40.828. Chạm thông báo lúc 09:59:41.117, thấy màn S20, bấm “Remind me in 10 minutes”. Android lưu lịch báo lại lúc 10:09:44.400 và còn lịch hằng ngày 09:53 ngày mai.

**S05 trên APK xác nhận.** Mở lại bài khóa trên từng giao diện A và B, kiểm tra ô nhập và nút gửi đều bị khóa. Trong cả hai giao diện, cuộn qua trang đầu, bấm xem thêm rồi đọc được “QA child 11”, “QA child 12”. Mỗi lượt, mock server ghi nhận yêu cầu trang 2 của đúng bình luận cha.

**S20, thông báo cũ còn sau khi báo lại.** Trên bản trước sửa, dù lịch báo lại 10:09:44.400 đã tồn tại và dịch vụ phát chuông đã dừng, Android vẫn giữ thông báo ID 5202 cho sự kiện DAILY cũ. SystemUI hiện lại nó lúc 10:02:26.709. Ca hồi quy dựng một lời nhắc đang đổ chuông và bấm nút báo lại trên S20 cũng thất bại trên thiết bị trước khi sửa. Đã đổi cách dừng foreground service để Android gỡ luôn thông báo khi sự kiện kết thúc. Trường hợp hết thời gian phát chuông mà người dùng chưa xử lý vẫn có thông báo để quay lại.

**Báo lại và bỏ qua.** Trước lúc báo lại, đưa gói QA ra trước màn hình. Giờ hẹn 10:09:44.400, Android giao broadcast SNOOZE lúc 10:09:44.402, nhận thông báo lúc 10:09:44.571. Ngăn thông báo ghi lần đầu hiển thị lúc 10:09:56.095. Mở từ thông báo và bấm “Skip today” trên S20. AlarmManager còn lịch DAILY của sáng hôm sau. Ở lượt này khoảng cách từ giờ hẹn tới lúc giao chỉ là 2 ms.

**Bản sửa cuối cho S20.** APK debug SHA-256 `D5440534435FE4313616A404776E8BD94032E0CF92A9EA0B203433148DBCE292`. Cùng ca hồi quy trên Realme đã thất bại trên APK `385BC5…` rồi đạt sau sửa. Sau khi người dùng bấm báo lại, ID 5202 được gỡ và lịch báo lại vẫn tồn tại. Chạy chung cả 18 ca instrumentation trên bản cuối đạt 18/18.

**S17 trên Realme.** Gửi hai dấu nhận diện từ bot Lan Anh trong môi trường mock cục bộ. Từ thông báo chứa đúng tin mẫu, bấm “Reply…”, nhập `QA-1721` và gửi. Lịch sử phía server có đúng một bản. Với tin mẫu tiếp theo, bấm “Mark as read”. API hội thoại trả số chưa đọc bằng 0.

**Kiểm tra bản cuối.** Gradle hoàn tất `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:testDebugUnitTest` và `:app:lintDebug`. Unit test đạt 26/26, instrumentation đạt 18/18. Lint debug có 0 lỗi và 178 cảnh báo. Bộ backend đã chạy 12/12 ca Node và 34/34 kiểm tra smoke.

<a id="ket-qua-realme-2111"></a>

### Kết quả bàn giao ngày 21/11

Đã đối chiếu S05 A/B, S06, S08–S11, S14–S15, S17 và S20/S22b trên Realme. Trong Developer tools cũng đã đổi MB Server → Offline → MB Server, đăng nhập lại bằng nút demo ở từng nguồn. Nhật ký vẫn hiện 21 bài tháng 11 ở cả hai lượt.

| Hạng mục | Trạng thái cuối phiên |
| --- | --- |
| Mã nguồn | Worktree `E:\MomentBox\build\home-realme`, nhánh `fix/realme-home`. Nền `b5c8f50`, commit cục bộ áp dụng PR là `add57de`. Các thay đổi sau commit đó đang ở working tree. PR #2 vẫn mở. |
| Bản chạy | Gói `com.ntq.training.momentbox.reminderqa` trên Realme, APK `D5440534…`. Giữ 24 bài mẫu M3 để tiếp tục thử. Đã gỡ gói QA cũ dành riêng cho đo lời nhắc khỏi người dùng Android hiện tại. |
| Mock server và USB | Mock QA trả 200 ở `/v1/config`, ADB reverse `tcp:3035 → tcp:3035`. Đã dừng proxy cố ý làm rớt phản hồi và tiến trình ghi log lời nhắc. |
| Xác minh | 26/26 unit, 18/18 instrumentation trên Realme, 12/12 Node, 34/34 smoke. Lint debug còn 178 cảnh báo, 0 lỗi. |
| Còn mở | Đăng nhập và tải feed bằng endpoint HTTPS phát hành thật. Thử khôi phục nháp sau khi hệ điều hành thu hồi tiến trình, đối chiếu ảnh xuất sau chỉnh sửa, phát qua loa/tai nghe và khôi phục hàng đợi phát sau ngắt kết nối. Gọi M5 hai đầu chờ thiết bị thứ hai. |

### Sửa hai lỗi S05 khi gửi bình luận/phản hồi thất bại – 21/11/2026 (bàn giao Codex)

Phạm vi: hai lỗi S05 trên worktree `E:\MomentBox\build\home-realme` (nhánh `fix/realme-home`, chưa commit).

- **Lỗi 1 – Đổi sắp xếp làm mất bình luận/phản hồi gửi lỗi và nút Thử lại:** `PostDetailViewModel` nay lưu các bình luận/phản hồi chưa xác nhận (`pendingComments`) cùng bản ghi `inFlightSubmissions` / `recentlyConfirmed` theo phiên bản đột biến (`commentMutationVersion`), và hợp nhất lại trong `mergeServerPage()` cho cả trang đầu (`refresh = true`) lẫn trang sau (`page > 1`). Đổi `top ↔ newest` giữ nguyên nội dung, trạng thái `Failed (Retry)` và `parentId`, kể cả khi bình luận cha trượt sang trang 2 hoặc khi đổi sắp xếp lúc request gửi đang chạy; khi thành công chỉ hiển thị đúng một bản và vẫn tuân thủ `allowComments`.
- **Lỗi 2 – Phản hồi gửi lỗi vẫn cộng vào tổng của bình luận cha:** `PostDetailViewModel.sendComment()` không còn cộng trước `parent.replyCount`; chỉ cộng đúng một lần khi server xác nhận (`submitComment()` thành công), đồng thời khóa nền `inFlightParentBaseCounts` để tránh cộng kép khi refresh trùng thời điểm gửi. `Comment.isConfirmed` và `CommentsAdapter.displayedReplyCount()` chỉ đếm phản hồi đã xác nhận (`id > 0 && !isSending && !sendFailed`), ẩn hẳn hàng mở/ẩn phản hồi khi cha có 0 phản hồi xác nhận và vẫn hiển thị dòng lỗi để thử lại.
- **Tệp đã sửa (phần mới trong worktree):** `Comment.kt`, `PostDetailViewModel.kt`, `CommentsAdapter.kt`, `PostDetailViewModelTest.kt`, `CommentsAdapterTest.kt`.
- **Đã xác nhận bằng unit test (máy tính):**
  - Trước sửa (RED): bộ S05 chạy 19 ca -> 13 ca có sẵn đạt, **6/6 ca hồi quy mới thất bại** (bắt cả lỗi ViewModel lẫn lỗi `CommentsAdapter.displayedReplyCount`).
  - Sau sửa (GREEN): bộ S05 đạt **19/19** (`PostDetailViewModelTest` 14/14, `CommentCountRepositoryTest` 2/2, `CommentLikeResponseTest` 3/3); toàn bộ `:app:testDebugUnitTest` đạt **32/32** (0 lỗi, 0 bỏ qua); `git diff --check` sạch.
- **Đã kiểm thử bổ sung trên Realme RMX3350 (`US79CERW99P7HU9P`, bản QA tạm `com.ntq.training.momentbox.s05qa`, APK SHA-256 `F3F0861E…`, đã gỡ sạch cùng reverse `tcp:3036` sau khi xong):**
  - Instrumentation trên máy: `CommentsAdapterTest` (3/3) + `PostHeaderAdapterTest` (2/2) = **5/5 đạt**.
  - Giao diện thật qua proxy chèn 503 cục bộ (`3036 → 3037`, giữ nguyên phiên đăng nhập):
    - **Bố cục B:** bình luận gốc `RootB_FAIL1` lỗi 503 → đổi `Top → Newest` vẫn còn `Failed (Retry)` → Thử lại (201) hiện 1 bản `Just now`, tổng bài `14 → 15`; cha 0 phản hồi (`QA Zero Parent`) gửi `ZeroB_FAIL2` lỗi 2 lần (kèm đổi `Newest → Top`) không hiện nhãn `Hide 1/0 reply`, lần 3 thành công hiện `Hide 1 reply`, tổng bài `15 → 16`; cha 12 phản hồi (`QA Twelve Parent`) mở 10/12 → gửi `TwelveB_FAIL2` lỗi → đổi `Top → Newest` vẫn `Hide 12 replies` + `Failed (Retry)` → thử lại lỗi lần 2 vẫn `12` → thử lại thành công thành `Hide 13 replies`, tổng bài `16 → 17`, bấm `Show more replies` vẫn tải tiếp `QA child 11`, `QA child 12`.
    - **Bố cục A:** lặp lại bình luận gốc `RootA_FAIL1` (`17 → 18`) và phản hồi `TwelveA_FAIL2` trên cha 13 phản hồi (lỗi 503 + đổi `Newest → Top` + thử lại lỗi vẫn `Hide 13 replies`, thành công lên `Hide 14 replies`, tổng bài `18 → 19`). Bài khóa (`id=50`) ở cả A/B hiện `Comments are turned off`.
- Codex đã build lại và đối chiếu các kết quả trên cùng Realme trong [mục review độc lập S05](#review-s05-realme-2111).
- **Cách tái hiện lặp lại lỗi gửi trên Realme mà giữ phiên đăng nhập:** đặt một HTTP proxy nhỏ (Node) đứng trước `mock-server` (ví dụ `127.0.0.1:3036 → 127.0.0.1:3035` với `adb reverse tcp:3036 tcp:3036` và build `-PmomentboxBaseUrl=http://127.0.0.1:3036/`). Proxy chuyển tiếp nguyên vẹn mọi header `Authorization` và các request `GET`/`login`/`refresh`, chỉ chặn `POST /v1/posts/:id/comments` khi `body.content` chứa từ khóa (ví dụ `FAIL1` trả `503` trước khi lưu ở lần 1; `FAIL2` trả `503` ở lần 1 và 2 theo khóa `url|parentId|content`), rồi chuyển tiếp bình thường (trả `201`) ở lần bấm `Thử lại` kế tiếp. Cách này không làm mất token hay đăng xuất và lặp lại ổn định cho cả bình luận gốc lẫn phản hồi.

<a id="review-s05-realme-2111"></a>

### Review độc lập bản sửa S05 trên Realme ngày 21/11/2026

**Bản chạy.** Worktree `fix/realme-home` tại `add57de` cùng diff chưa commit. Codex build APK debug riêng `com.ntq.training.momentbox.s05review`, SHA-256 `55E1D84186AC75A8A6853B79E697A41A5A87C54B2C6906AEAC659B62E5513955`. Đã đối chiếu cùng SHA-256 trên APK cài trong Realme RMX3350, Android 13, kết nối ADB qua USB. Cả A và B được mở bằng deep link chọn bố cục rồi thao tác bằng chạm, nhập và cuộn. Proxy loopback 3036 chuyển đến mock 3035. Dữ liệu mẫu gồm các bài riêng tư của tài khoản demo.

**Kiểm thử tự động.** Bộ có sẵn đạt 32/32 unit test, trong đó 19 ca thuộc S05. Trên Realme, `CommentsAdapterTest` và `PostHeaderAdapterTest` đạt 5/5. Hai unit test đối chứng của Codex chạy riêng từ thư mục build đều tái hiện lỗi. Khi một lượt gửi đã xuất hiện trong danh sách từ server nhưng phản hồi POST còn chậm, mong đợi 1 dòng, thực tế 2. Khi hai phiên cùng gửi vào một cha, mong đợi 2 phản hồi, thực tế 1. Đã chạy lại bộ 32 ca sau các phép đối chứng.

**Ca chuẩn đã xác nhận trên cả A và B.** Mỗi bố cục dùng một bài mở riêng. Gửi bình luận gốc lỗi 503 trước khi lưu, đổi sắp xếp rồi bấm “Thử lại” thành công. Tổng bài tăng từ 14 lên 15. Với cha ban đầu có 0 hoặc 12 phản hồi, hai lần gửi lỗi liên tiếp giữ nguyên số đếm và hàng “Thử lại” vẫn gắn đúng cha. Sau 201, số phản hồi lần lượt thành 1 và 13, tổng bài thành 16 rồi 17. Khi mới tải 10/12, cả hai bố cục vẫn đọc được phản hồi 11 và 12 qua nút xem thêm. Ở bài khóa có bình luận cũ, ô nhập và nút gửi bị khóa trong cả hai bố cục, vẫn mở được phản hồi thứ 12.

**Các tình huống còn lỗi trước khi merge**

| Mức | Cách tái hiện và kết quả | Vị trí và hướng sửa |
| --- | --- | --- |
| Cao | Cho mock lưu bình luận và trả 201, rồi để proxy trả 502 về điện thoại. Đổi sang “Mới nhất” và bấm “Thử lại”. Cùng một lần nhập sinh hai bình luận đã lưu, tổng bài tăng từ 2 lên 4. | `FeedRepositoryImpl.kt:790`, `FeedDtos.kt:89`, `mock-server/server.js:422`. Cấp một mã yêu cầu cho mỗi lần nhập, dùng lại mã khi thử lại và để server trả về bản ghi đã lưu. Bổ sung ca lỗi sau khi server đã lưu. |
| Cao | Bài có 23 bình luận gốc. Trả lời cha đang ở đầu “Phù hợp nhất”, nhận 503 rồi đổi sang “Mới nhất”. Cha chuyển sang trang 2. Bản A cuộn đến cuối vẫn dừng ở bình luận thứ 20, proxy chỉ thấy trang 1. Ở bản B, cuộn tải trang 2, hàng lỗi gắn đúng cha và thử lại thành công với `parentId=1141`. | `PostDetailActivity.kt:346`. Nối sự kiện cuộn gần đáy của `NestedScrollView` bản A vào phân trang bình luận gốc. Kiểm tra từ Activity với hơn 20 bình luận gốc. |
| Vừa | Giữ phản hồi 201 trên proxy 15,5 giây sau khi mock đã lưu. Trong lúc chờ, đổi sang “Mới nhất”. Cùng nội dung hiển thị một dòng “Sending…” và một dòng “Just now”. Sau khi 201 về máy, danh sách mới còn một dòng. | `PostDetailViewModel.kt:479`. Hợp nhất bản tạm và bản xác nhận bằng mã yêu cầu mà server cũng trả trong danh sách. Hai người dùng vẫn có thể chủ ý gửi cùng một nội dung. |
| Vừa | Cha ban đầu có 0 phản hồi. Giữ 201 của điện thoại, dùng một phiên QA khác gửi thêm một phản hồi vào cùng cha rồi đổi sắp xếp trên điện thoại. Sau khi 201 về, nhãn hiển thị 1 trong khi server có 2. Đổi sắp xếp thêm lần nữa nhãn mới lên 2. | `PostDetailViewModel.kt:453` và `:403` ưu tiên số cũ khi đang gửi rồi cộng 1. Đối chiếu với số mới từ server sau khi hoàn tất yêu cầu hoặc đọc lại cha, kể cả khi một phiên khác cùng gửi. |

Hai điểm ở mức cao đã có trong mã nền `add57de`. Cả A và B tái hiện được ba trường hợp liên quan đến phản hồi chậm, lỗi sau khi lưu và hai phiên cùng gửi. Đọc trực tiếp từ mock cho thấy một lần nhập tạo hai ID ở mỗi bố cục. Bài 53 (B) có `1193` và `1194` cùng nội dung `ReviewLostACK502`. Bài 55 (A) có `1207` và `1208` cùng nội dung `950502`. Với ca hai phiên, server có `1195` và `1196` dưới cha `1187` (B), `1209` và `1210` dưới cha `1205` (A). Cả hai lần, điện thoại hiện 1 rồi mới lên 2 khi tải lại.

Lượt A cuối dùng nội dung toàn chữ số do bộ gõ đã đổi chuỗi thử dạng chữ. Chỉ ghi nhận những lần log proxy xác nhận đã giữ hoặc chặn đúng phản hồi cần đo. Sau khi đối chiếu, đã dọn các bài riêng tư do phiên review tạo, gỡ hai gói `s05review` và `s05review.test`, dừng proxy 3036. Cổng USB 3035 vẫn nối với mock QA. Thay đổi của phiên review trong Git nằm ở báo cáo này.

**Kết luận.** Nên sửa thao tác gửi lại sau khi server đã lưu và phân trang gốc trên A trước khi merge. Bổ sung ca hồi quy cho hai tình huống này, cùng ca hai phiên trả lời đồng thời và ca đổi sắp xếp khi ứng dụng còn chờ 201.

---

<a id="sua-4-loi-s05-2111"></a>

### Sửa 4 lỗi S05 sau review độc lập và kiểm thử lại trên Realme ngày 21/11/2026

**Phạm vi & bản mã.** Thực hiện trên worktree `E:\MomentBox\build\home-realme` (nhánh `fix/realme-home`, HEAD nền `add57de`, giữ nguyên các thay đổi trước đó và để diff chưa commit để Codex review). Toàn bộ mã sửa/viết mới đã được định dạng gọn, rõ khối logic trước vòng kiểm thử cuối.

**Tóm tắt sửa lỗi**
1. **Lỗi 1 — Chống trùng khi bấm “Thử lại” sau 502 (đã lưu trên server):**
   - Android (`Comment.kt`, `FeedDtos.kt`, `CommentMapper.kt`, `FeedRepository.kt`, `FeedRepositoryImpl.kt`, `PostDetailViewModel.kt`, `OfflineFeedState.kt`): cấp một `clientId` (UUID v4) cho mỗi lần chủ ý gửi và tái sử dụng nguyên `clientId` + `parentId` khi gọi `retryComment()`; hai lần chủ ý gửi cùng nội dung mang hai `clientId` khác nhau.
   - `mock-server/server.js`: `POST /v1/posts/:id/comments` kiểm tra định dạng UUID v4 (trả `400` nếu sai, giữ tương thích request cũ thiếu `clientId`, giữ `403` cho bài khóa), khử trùng đồng bộ theo khóa `(userId, postId, clientId)` trong `commentIdByOperation` (trả `200` cùng bản ghi cũ khi gửi lại, trả `201` khi tạo mới), đồng thời trả kèm `postCommentCount` và `parentReplyCount`. `commentDto()` chỉ trả `clientId` cho đúng tác giả để không lộ cho người khác.
2. **Lỗi 2 — Phân trang bình luận gốc trên Bố cục A:**
   - `PostDetailActivity.kt` (`setupVersionA()`): gắn `ConcatAdapter(commentsAdapter, commentsFooterAdapter)` cho `rvCommentsA` (dọn adapter ở `onDestroy()`), nối `NestedScrollView.OnScrollChangeListener` của `nestedScrollViewA` để gọi `viewModel.loadComments(refresh = false)` khi cuộn xuống gần đáy (ngưỡng `240dp`), và cập nhật footer cho cả hai bố cục A/B.
3. **Lỗi 3 — Tránh hiện 2 dòng (“Sending…” + “Just now”) trong lúc 201 bị giữ:**
   - `PostDetailViewModel.kt` (`mergeServerPage()`, `loadMoreReplies()`, `submitComment()`): đối chiếu bình luận/phản hồi cục bộ với danh sách server theo `clientId` (cả bình luận gốc lẫn phản hồi con). Khi `GET` trả về bản ghi trùng `clientId` trong lúc `POST` còn treo, ẩn ngay bản `Sending…` để chỉ hiện 1 dòng duy nhất và ghi nhận vào `confirmedByClientId`; khi `201` hoàn tất hoặc gặp thứ tự ngược (`POST` xong trước `GET` cũ), danh sách vẫn chỉ còn 1 dòng; bình luận cùng nội dung từ người dùng khác vẫn giữ riêng.
4. **Lỗi 4 — Tự đồng bộ cha 0 phản hồi & tổng bài khi hai phiên cùng trả lời:**
   - `PostDetailViewModel.kt` (`handleConfirmedReply()`, `mergeServerPage()`, `refreshPostDetailCount()`): ghi nhận `observedServerParentCounts` trong khi `POST` đang chạy, ưu tiên `serverParentReplyCount` từ phản hồi `POST` để tránh cộng trùng sau `502 → đổi sắp xếp → Thử lại` nhưng vẫn lấy giá trị lớn nhất với số đếm server đã quan sát khi có phiên khác cùng trả lời; tự gọi `loadMoreReplies()` nếu thread đang mở thiếu phản hồi từ phiên khác và gọi `getPostDetail()` để đồng bộ tổng bài viết mà không cần tải lại thủ công.

**Kiểm thử tự động (chạy lại sau bước định dạng mã cuối)**
- **Backend (`mock-server`, `NODE_PATH=E:\MomentBox\mock-server\node_modules`):**
  - `node --test test/comment-retry.cjs test/post-retry.cjs test/m5-send.cjs test/m5-profile.cjs test/m5-following.cjs`: **13/13 đạt** (0 fail, 0 skip).
  - `node test/smoke.js http://127.0.0.1:3039` (trên server sạch tạm thời): **đạt toàn bộ**.
- **Android Unit Test (`MomentBox_App`):**
  - `.\gradlew.bat :app:testDebugUnitTest --rerun-tasks`: **36/36 đạt** (0 fail, 0 skip; S05 đạt **23/23**: `PostDetailViewModelTest` 17/17, `CommentCountRepositoryTest` 3/3, `CommentLikeResponseTest` 3/3).
- **Android Instrumentation trên Realme RMX3350 (`US79CERW99P7HU9P`, Android 13):**
  - Build gói tạm `com.ntq.training.momentbox.s05qa2` qua init script ngoài repo (`build.gradle.kts` không đổi), đối chiếu khớp SHA-256 trên máy tính và trên máy qua `sha256sum`: `FC58EF068B3419E4857C875F330F8E46CA59A5A496BCEC04F9C855B096B56B8F`.
  - `CommentsAdapterTest` (3/3) + `PostHeaderAdapterTest` (2/2) + `PostDetailActivityPaginationTest` (2/2 — kiểm tra trực tiếp `PostDetailActivity` cả bố cục A và B với 23 gốc, đổi sắp xếp, trang 2, Retry đúng `parentId`/`clientId`, không lặp vô hạn và phân trang phản hồi 11–12): **7/7 đạt** (`8.21s`).

**Kiểm thử thực tế trên Realme RMX3350 (cả Bố cục B và A, proxy `127.0.0.1:3036 → 127.0.0.1:3038`, mock QA cổng `3035` giữ nguyên)**

| Lỗi | Trước sửa (review 21/11) | Sau sửa trên APK `FC58EF06…` (cả B và A) |
| --- | --- | --- |
| Lỗi 1 (502 sau lưu → Thử lại) | 1 lần nhập tạo 2 ID trên server (`1193`/`1194` ở B, `1207`/`1208` ở A), tổng bài tăng 2 lần | **Đạt (B: bài `50`, A: bài `51`).** Bình luận gốc `805021` (`attempt=1 SAVED_THEN_502`, `attempt=2 status=200` cùng `clientId`): chỉ 1 ID trên server (`229` ở B, `236` ở A), 1 dòng trên UI, `Comments (2) → Comments (3)`. Bình luận gốc `805023` (502 → đổi `Mới nhất`): tự khớp 1 ID (`230` ở B, `237` ở A), `Comments (4)`. Phản hồi `805022` (502 → đổi sắp xếp → Thử lại): giữ đúng `parentId` (`149` ở B, `151` ở A) và 1 ID (`231` ở B, `238` ở A), `Hide 1 reply`, `Comments (5)`, tổng server `5`. Kiểm chứng riêng bài độc lập xác nhận cả B (`[245]`) và A (`[248]`) đều hiện `Comments (3)`, `Hide 1 reply`, `showMoreCount=0`, `serverPostCount=3`. |
| Lỗi 2 (Bố cục A dừng ở 20 gốc) | A chỉ gọi `GET page=1`, không tới trang 2 | **Đạt (B: bài `52` cha `153`, A: bài `53` cha `188`, 23 gốc / 12 phản hồi).** Phản hồi `805031` gặp `503` trước khi lưu → đổi `Mới nhất` → cuộn đến đáy phát sinh đúng `page=1` và `page=2` (`sort=newest`, không lặp trang 3) → hiện cha + `Failed (Retry)` (`Hide 12 replies`) → bấm Thử lại gửi đúng `parentId` & cùng `clientId` (`savedId=232` ở B, `239` ở A), nhãn lên `Hide 13 replies`, `postCount=36`; mở tiếp tải đủ phản hồi `11–12` (`2111`, `2112`). |
| Lỗi 3 (Giữ 201 + đổi sắp xếp) | Hiện 2 dòng (`Sending…` + `Just now`) trong lúc chờ 201 | **Đạt (B: bài `54` `savedId=233`, A: bài `55` `savedId=240`).** Khi proxy đang giữ 201 (`held=1`) và đổi `Mới nhất`, UI ở cả B và A chỉ hiển thị **1 dòng** `802011` (`Just now`, `sending=0`). Sau khi nhả 201 vẫn đúng **1 dòng** và `Comments (3)`. |
| Lỗi 4 (Hai phiên cùng trả lời cha 0 phản hồi) | Máy giữ nhãn `1` trong khi server có `2`, phải tải lại thủ công | **Đạt (B: bài `54` cha `223`, A: bài `55` cha `225`).** Giữ 201 phản hồi #1 (`804011`: ID `234` ở B, `241` ở A), phiên 2 (`travel@momentbox.dev`) gửi #2 (`804021`: ID `235` ở B, `242` ở A), đổi sắp xếp rồi nhả 201 → ngay lập tức (không reload/đổi sắp xếp thêm) UI tự cập nhật `Hide 2 replies`, đủ 2 phản hồi riêng biệt và `Comments (5)` khớp server (`postCount=5`, `parentReplies=2`). |
| Bài khóa bình luận (`id=56`) | Giữ nguyên quyền đọc, khóa nhập | **Đạt trên cả B và A:** ô nhập hiển thị `Comments are turned off` và bị khóa. |

**Dọn dẹp sau kiểm thử.** Đã gỡ `com.ntq.training.momentbox.s05qa2` và `com.ntq.training.momentbox.s05qa2.test`, bỏ `adb reverse tcp:3036`, dừng mock server tạm (`3038`) và proxy (`3036`). Kiểm tra lại xác nhận `adb reverse tcp:3035`, mock QA trên `127.0.0.1:3035` cùng các gói cài sẵn (`com.ntq.training.momentbox`, `.reminderqa`, `.homeqa`) đều còn nguyên vẹn. **Điểm mở:** không có lỗi S05 nào còn tồn đọng; sẵn sàng để Codex review diff và kiểm thử độc lập trên Realme.
