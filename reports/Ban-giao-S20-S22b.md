# Bàn giao MomentBox từ máy nhà sang máy công ty

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

## Việc tiếp theo

| Thứ tự | Việc cần kiểm tra hoặc sửa |
|---|---|
| 1. Tiếp tục S22b khi có điện thoại | Dùng tài khoản thử còn lượt nhắc trong ngày, bật quyền thông báo, đặt giờ vài phút tới, đổi ngôn ngữ rồi về Home. Ghi giờ hẹn, giờ Android giao báo thức và giờ thông báo xuất hiện trong khi app vẫn ở nền. Đối chiếu với quyền báo thức chính xác và trạng thái pin trước khi kết luận nguyên nhân. |
| 2. S05 khi tiếp tục phát triển bản debug | Bắt đầu với `allowComments=false`. Trên máy thật vẫn gửi được yêu cầu rồi nhận HTTP 403 và hiện “Thử lại”. Khóa ô nhập và thao tác trả lời, kiểm tra quyền trong ViewModel. Sau đó xử lý tải phản hồi từ mục thứ 11 hoặc sau khi tự trả lời, rồi đồng bộ `commentCount` giữa chi tiết và bảng tin khi server xác nhận. |
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

## Prompt dán vào Codex ở máy công ty

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
