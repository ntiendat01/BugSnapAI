# BugSnap AI

Trợ lý kiểm thử Android chạy cùng thiết bị với ứng dụng đang test. Bộ tài liệu đặc tả nằm trong `docs/`.

Nhánh `dev` có nền tảng T-001–T-004 và spike T-010. Bản **0.1.2-t010** có bong bóng thu gọn 48 dp, phản hồi ngay trong nút và bổ sung giao diện quay, phản hồi Mark Bug, điều khiển nổi, thông báo kết quả và danh sách clip đọc lại được khi mở app. Xem [bong bóng thu gọn](docs/18_COMPACT_OVERLAY_UX.md), [thiết kế UX](docs/17_CAPTURE_FEEDBACK_UX.md) và [bằng chứng triển khai](docs/UI_UX_IMPLEMENTATION.md). Checklist V1–V9 trên OnePlus Ace 5 tiếp tục được theo dõi ở `docs/SPIKE_TESTING.md`.

T-014 bổ sung [EvidenceStore](docs/T014_EVIDENCE_STORE.md) cho kiến trúc phiên, gồm đường dẫn tương đối, ghi atomic, xóa phiên, dọn `.part` và kiểm dung lượng. Việc nối store vào pipeline quay thuộc T-015/T-016; video mới của spike được công bố vào `DCIM/BugSnap` theo [thay đổi lưu video](docs/DCIM_VIDEO_STORAGE.md).

T-011 hoàn thiện [rolling buffer](docs/T011_ROLLING_BUFFER.md) thuần JVM: sample/snapshot bất biến, giới hạn thời gian, generation và kiểm thử nhiều marker/đa luồng. T-012 bổ sung [CaptureEngine](docs/T012_CAPTURE_ENGINE.md). Bản `0.1.3-t012-fix` điều chỉnh tương thích encoder sau phản ánh video đen, xem [bản sửa và kiểm thử hình ảnh](docs/T012_BLACK_VIDEO_FIX.md). Nghiệm thu trên Ace 5 vẫn còn mở.

## Yêu cầu môi trường

- JDK 17
- Android SDK với `platforms;android-37.0` (hoặc 37), `platforms;android-36`, và Build-Tools
- Đặt `sdk.dir` trong `local.properties` (file này không commit) hoặc biến `ANDROID_HOME`

## Lệnh build

Từ thư mục gốc repo:

```bash
# Debug APK
./gradlew assembleDebug

# Unit test
./gradlew testDebugUnitTest

# Lint
./gradlew lint

# Cài lên thiết bị/emulator đang kết nối
./gradlew installDebug
```

APK debug: `app/build/outputs/apk/debug/app-debug.apk`.

Mở project bằng Android Studio (AGP 9.5 / Gradle 9.6). Thiết bị chính để chạy: OnePlus Ace 5 (Android 16).

## Phiên bản công cụ (T-001)

| Thành phần | Phiên bản |
|---|---|
| Android Gradle Plugin | 9.5.1 |
| Gradle | 9.6.0 |
| Kotlin | 2.4.21 (built-in Kotlin của AGP 9, override qua `kotlin-gradle-plugin`) |
| Compose BOM | 2026.09.00 |
| Hilt | 2.60.1 + KSP 2.3.14 |
| minSdk / targetSdk / compileSdk | 29 / 36 / 37 |

`compileSdk` là 37 (không phải 36 như bản đầu của `docs/05`) vì Compose 1.12+ trong BOM 2026.09.00 yêu cầu compileSdk 37. `targetSdk` vẫn 36, đúng thiết bị chính Android 16. Xem ADR-12 trong `docs/16_TECHNICAL_DECISIONS.md`.

## T-010 Spike (nhánh `dev`)

```bash
./gradlew installDebug
adb logcat -s BugSnapSpike:V
```

Màn hình mở lên hướng dẫn quay thử: Bắt đầu quay → đồng ý MediaProjection → Mark Bug → ghi thêm 5 giây → đang lưu → Xem clip. Khi đang dùng app khác, chạm bong bóng để mở Mark Bug hoặc bật Mark nhanh một chạm. Clip hiển thị số ảnh thực tế đã trích. Video mới nằm trong `DCIM/BugSnap`, xem được trong Thư viện Android. Clip cũ, ảnh bằng chứng và index vẫn ở bộ nhớ riêng của app (`files/spike/`). Hướng dẫn V1–V9: `docs/SPIKE_TESTING.md`.
