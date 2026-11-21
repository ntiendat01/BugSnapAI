# T-011 — EncodedSample và RingBuffer

Hoàn thành triển khai và kiểm thử JVM ngày 2026-11-21 theo task T-011 và `07` §10. Hoàn thiện mã buffer được dùng trong spike hiện có. T-010 vẫn còn checklist V1–V9 chưa nghiệm thu trên OnePlus Ace 5; kiểm thử JVM của T-011 không thay thế checklist đó.

## Thay đổi

- Byte của sample và codec configuration được sao chép khi nhận vào, getter trả bản sao. Snapshot giữ danh sách không thể sửa từ bên ngoài, chia sẻ cùng sample bất biến giữa nhiều marker. `sizeBytes` tránh sao chép payload khi tính RAM hoặc lấy kích thước frame để mux.
- Ring luôn bắt đầu ở keyframe và không vượt `buffer + margin` theo PTS. Khi GOP kéo dài quá giới hạn mà chưa có keyframe mới, bỏ vùng không còn hợp lệ và đợi keyframe.
- Chọn keyframe tại/sau ranh giới pre-roll để thời gian thực tế không vượt cấu hình 15/30 s. Có thể ngắn hơn một GOP. Mẫu quá deadline post-roll không được thêm vào clip; post-roll tối đa 10 s.
- `append`, `mark`, `stats`, đổi generation, flush và clear đều dùng cùng khóa. PTS mặc định của Mark được lấy trong khóa. Timestamp lịch sử thu được post-roll đã có sẵn.
- Bỏ frame trùng, đi lùi, rỗng, codec-config và callback từ generation cũ/chưa đăng ký. Generation không được tái dùng ID cho cấu hình khác. Pending của generation cũ hoàn tất trên sample đầu của generation mới hoặc lúc flush.
- Input Mark không hợp lệ trả `Rejected`. Chỉ `clear()` sau khi đã nhận kết quả `forceFinalizePending()` nếu cần xuất các marker đang chờ.

Mã buffer dùng Kotlin/JVM và thư viện chuẩn Java, không import Android. Không thêm dependency, không đổi vị trí video `DCIM/BugSnap`, không thay hệ thống quyền hoặc UI.

## Xác minh

Build chạy trong bản sao source `/private/tmp/bugsnap-apk-build.ngt1jE/src`, JDK 17.0.21 / Gradle 9.6.0 / compileSdk 37. Source app đã được đối chiếu với workspace. Emulator Google APIs Android 16/API 36 x86_64, serial `emulator-5560`.

| Kiểm tra | Kết quả |
|---|---|
| `assembleDebug`, `assembleDebugAndroidTest` | PASS |
| `testDebugUnitTest` | 100 pass, 0 lỗi/bỏ qua; thêm 18 test |
| Buffer và export queue | 7 test buffer cũ + 15 contract + 2 stress + 4 queue = 28 pass |
| `lintDebug` | 0 error, 31 warning có sẵn, không thêm warning |
| `CaptureFlowTest` | 1 pass: đồng ý MediaProjection → Mark → post-roll → lưu DCIM, mở MediaStore URI bằng retriever có duration > 0 → dừng service |
| Kiểm tra source | Không import Android/AndroidX trong `capture/buffer`; source/test buffer dài nhất 212 dòng; `git diff --check` pass, hai bản ADR giống nhau |

Log build và test nằm trong thư mục build tạm: `t011-build.txt` và `t011-capture-flow.txt`. APK debug giao để thử là `BugSnapAI-T011-debug.apk` ở gốc repo (gitignored). Kết quả emulator không chứng minh các hành vi MediaProjection/OxygenOS V1–V9 trên máy thật.

Các test bao phủ bảo vệ byte/list, chia sẻ sample giữa marker, retention, ranh giới pre/post-roll, deadline và timestamp lịch sử, generation cũ, đầu vào lỗi/overflow, flush/clear. Kiểm thử ngẫu nhiên dùng 20 seed cố định × 1.200 frame, đổi generation và Mark liên tiếp; kiểm tra mỗi marker chỉ được hoàn tất một lần, thời gian không vượt cấu hình và snapshot không thay đổi. Test hai luồng chạy append/Mark/stats qua 100 đợt đồng thời; fake exporter xác minh 10 marker có thư mục riêng và tối đa 3 tác vụ xuất cùng lúc.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
adb -s emulator-5560 shell am instrument -w \
  -e class com.bugsnap.ai.spike.CaptureFlowTest \
  com.bugsnap.ai.test/androidx.test.runner.AndroidJUnitRunner
```

Sau T-011 là T-012 CaptureEngine. Các yêu cầu 10 phút không rò rỉ, xoay 20 lần và hành vi OxygenOS cần kiểm tra theo task đó; không đánh dấu đã đạt chỉ từ unit test buffer.
