# BugSnap AI

Trợ lý kiểm thử Android chạy cùng thiết bị với ứng dụng đang test. Bộ tài liệu đặc tả nằm trong `docs/`.

Trạng thái hiện tại: **T-001 — khung project** (theme, Hilt, điều hướng rỗng, màn Home trống). Chưa có luồng quay màn hình.

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
