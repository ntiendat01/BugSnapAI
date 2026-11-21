# Triển khai UI/UX phản hồi quay và Mark Bug

Ngày 21/11/2026. Bản APK `0.1.1-t010`, `versionCode 2`, nhánh `dev`. Phạm vi là hoàn thiện trải nghiệm thử T-010 theo [thiết kế 17](17_CAPTURE_FEEDBACK_UX.md).

## Hành vi đã triển khai

- Màn hình chuẩn bị giải thích cách lưu clip và các quyền điều khiển. Nút Bắt đầu quay và Mark Bug ở vùng thao tác dưới, tách chẩn đoán kỹ thuật vào mục mở rộng.
- Chỉ chuyển sang Đang quay khi encoder nhận được keyframe đầu tiên. Mark được nhận có UUID riêng, số thứ tự và deadline ghi thêm 5 giây.
- Mỗi marker có trạng thái ghi thêm, chờ xuất, đang xuất, đã lưu hoặc lỗi. Các kết quả đến lệch thứ tự cập nhật theo đúng UUID. Một clip đang xuất vẫn cho phép nhận Mark tiếp theo.
- Bong bóng hiện khi dùng app khác. Chạm mở điều khiển, kéo để đổi vị trí. Tùy chọn Mark nhanh một chạm mặc định tắt và được lưu khi người dùng thay đổi.
- Thẻ phản hồi bám gần bong bóng, báo đã nhận, đếm ngược, đang lưu và kết quả. Panel cuộn được khi chiều cao khả dụng ngắn. Cài đặt trợ năng ảnh hưởng thời gian hiển thị.
- Rung xác nhận bám vào nguồn thao tác, tôn trọng cài đặt Android. Trở lại app hiển thị kết quả trong màn hình BugSnap.
- Thông báo phiên cập nhật trạng thái. Thông báo kết quả lặng có hành động mở đúng clip theo UUID. Nội dung chi tiết được ẩn trên màn hình khóa.
- MP4 được kiểm tra duration và rename trước khi báo thành công. Ảnh JPEG dùng file `.part` rồi rename. Thông báo hiển thị số ảnh thực, clip thiếu ảnh có cảnh báo.
- Metadata clip được ghi atomic vào index JSON trong bộ nhớ riêng. Mở lại app đọc được clip đã lưu. Video của bản cũ chưa có index được đọc lại bằng metadata MP4.
- Dừng quay chốt các marker đang nhận phần ghi thêm và chờ xuất xong. Service hoàn tất tác vụ xuất trước khi tự kết thúc.

## Cấu trúc mã

| Thành phần | Trách nhiệm |
|---|---|
| `SpikeCaptureScreen` | Luồng xin quyền và ViewModel |
| `SpikeCaptureContent` | Nội dung Compose và các trạng thái màn hình |
| `SpikeDiagnostics` | Các chỉ số và nút kiểm thử kỹ thuật |
| `SpikeSession`, `SpikeMarker` | Trạng thái per marker, deadline và danh sách kết quả |
| `SpikeOverlay` | Bong bóng, panel và phản hồi ngoài app |
| `SpikeNotifications`, `SpikeIntents` | Thông báo và mở đúng clip qua FileProvider |
| `SpikeExportFeedback`, `SpikeClipIndex` | Chốt kết quả xuất và metadata đọc lại được |

Index JSON phục vụ bản spike. T-016/T-022 sẽ nối pipeline sản phẩm vào Room theo đặc tả hiện có. Danh sách ở bản spike đọc lại các clip của những lần quay trước, nhận diện file bằng UUID.

## Kiểm tra

Chạy bằng Gradle 9.6.0 và JDK Temurin 17 trong bản sao build ở thư mục tạm. Lệnh kiểm tra gồm `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebugAndroidTest`. Android instrumentation chạy trên Google APIs Android 16 / API 36 x86_64, emulator 37.4.1, khung hình 720 × 1600 / density 320.

| Kiểm tra | Kết quả |
|---|---|
| Debug APK và test APK | Build thành công |
| Unit test | 78 test đạt, 0 thất bại |
| Android UI | 3 test đạt, gồm vòng quay/xuất video thực |
| Android lint | 0 lỗi, 24 cảnh báo về resource chưa dùng, plural, phiên bản dependency và KTX |
| Chữ ký APK | Xác minh APK Signature Scheme v2 thành công |
| Giới hạn kích thước mã | Tất cả file Kotlin dưới 500 dòng |

Bài UI dùng `CaptureUiTest` để chụp trạng thái cố định và `CaptureFlowTest` để đi qua hộp thoại đồng ý Android, encoder, Mark, xuất MP4 và báo hoàn thành. Ảnh thực được chụp sau khi compositor trình bày trạng thái mới. Metadata JSON của clip đã xuất được xác nhận có trên thiết bị kiểm thử.

## Ảnh giao diện

Ảnh `01`–`05` chụp từ component Compose và cửa sổ overlay Android đang chạy với dữ liệu kiểm thử cố định. Ví dụ clip #03 / 35 giây / 2 ảnh là dữ liệu dùng để duyệt trạng thái. Ảnh `06`–`07` lấy trong luồng cấp quyền MediaProjection và xuất video thực trên emulator.

- [Màn hình chuẩn bị](design/screenshots/01-ready.png)
- [Trong app sau khi nhận Mark](design/screenshots/02-mark-accepted.png)
- [Trong app sau khi lưu](design/screenshots/03-clip-saved.png)
- [Bong bóng sau khi nhận Mark](design/screenshots/04-overlay-mark-accepted.png)
- [Bong bóng sau khi lưu](design/screenshots/05-overlay-clip-saved.png)
- [Luồng quay thực sau Mark](design/screenshots/06-real-mark.png)
- [Luồng quay thực sau khi lưu](design/screenshots/07-real-saved.png)

## Phạm vi cần kiểm chứng tiếp

OnePlus Ace 5 / OxygenOS 16 cần kiểm thử V1–V9, thao tác kéo, vùng gesture, haptic, TalkBack, fontScale, chạy nền dài, từ chối từng quyền, mất projection, hết dung lượng và marker burst. Emulator dùng codec/phần cứng khác máy thật, số FPS và độ trễ trên emulator phục vụ kiểm tra luồng.

Bản này cung cấp điều khiển quay của spike. Pause/Resume, Quick Note, Quick Settings Tile và lịch sử phiên Room tiếp tục thuộc các task sản phẩm đã có trong danh sách triển khai.

## APK bàn giao

[BugSnapAI 0.1.1 UX debug](../BugSnapAI-0.1.1-ux-debug.apk), khoảng 32 MB, minSdk 29 / targetSdk 36. APK debug dùng cùng keystore như bản 0.1.0 đã bàn giao trong phiên này.

SHA-256 `331e0337ce3d453f06d4f1e0bfeecd97c7b7166bd939f3c68502105e0d83613b`.

Bài quay thực đã chạy lại sau khi chỉnh màu icon thanh hệ thống. Ảnh cuối cho thấy clip 6 giây và 2 ảnh được lưu thành công. Bản chụp thông báo/overlay trên OxygenOS cần kiểm chứng ở Ace 5.
