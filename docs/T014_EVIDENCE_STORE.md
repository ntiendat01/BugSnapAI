# T-014 — EvidenceStore

Hoàn thành ngày 2026-11-21. Phạm vi theo task T-014 và thiết kế `09` §4, §6.

## Hành vi

File bằng chứng của kiến trúc phiên được lưu dưới `Context.filesDir/sessions/<sessionId>/`. `EvidencePaths` tạo đường dẫn marker, frame và export. API trả `relPath` và `sizeBytes` để ghi Room, không lưu đường dẫn vật lý phụ thuộc thiết bị.

`EvidenceStore` được Hilt cấp singleton, chạy thao tác chặn trên `@IoDispatcher`. Ghi qua file tạm duy nhất `.part` cùng thư mục, kiểm file khác rỗng, đồng bộ dữ liệu rồi đổi tên atomic. Nếu callback lỗi hoặc bị hủy trước commit, file cũ vẫn đọc được và file tạm được dọn. Không có fallback sang copy nếu filesystem không hỗ trợ atomic. Commit là ranh giới rename; hủy ngay sau commit có thể để lại file hoàn chỉnh để tác vụ gọi xử lý khi thử lại.

Đọc/ghi cùng đường dẫn dùng khóa riêng. Hai marker khác nhau có thể xuất đồng thời. Xóa phiên và quét `.part` lấy khóa độc quyền, chờ thao tác đang chạy xong. API trả `Result<T, DomainError>`; cancellation tiếp tục được truyền lên coroutine. Callback đồng bộ phải đóng tài nguyên, không gọi ngược vào store hoặc giữ File để ghi sau khi trả về.

Xóa phiên bao gồm marker, frame, export và file đang lưu dở. Xóa lại phiên đã mất thành công. Đường dẫn không an toàn và symlink trong đường dẫn đọc/ghi bị từ chối. Khi xóa cây hoặc quét `.part`, symlink được gỡ tại chỗ, không duyệt sang đích.

`StatFs` cung cấp dung lượng trống. Ngưỡng cảnh báo là dưới 500 MiB, chặn bắt đầu phiên là dưới 200 MiB. Ghi bằng chứng đang chạy dùng ước lượng byte riêng, lỗi ENOSPC được ánh xạ `StorageLow`. Việc hiển thị cảnh báo và chặn phiên được coordinator sử dụng khi nối luồng phiên.

Khi màn hình chính khởi tạo, ViewModel gọi quét `.part` trong `sessions/`; lỗi hiển thị thông báo thử lại khi mở app. FileProvider đã cho phép URI `content://` của `sessions/`.

## Ranh giới tích hợp

- T-014 không chuyển clip cũ sang Room hoặc tự gán session ID. Thay đổi sau T-014 theo yêu cầu chủ dự án: [video mới của spike lưu vào DCIM/BugSnap](DCIM_VIDEO_STORAGE.md); `files/spike/` giữ ảnh/index, video cũ và MP4 tạm trong lúc xuất. Store T-014 tiếp tục quản lý dữ liệu riêng của kiến trúc phiên.
- T-015/T-016 dùng store khi điều phối phiên và xuất marker. Coordinator phải dừng/join tác vụ xuất trước khi xóa phiên, tránh tác vụ mới tạo lại thư mục sau khi xóa.
- Use case xóa phiên xóa file trước, chỉ xóa DB khi thành công. Khi lỗi phải giữ bản ghi, đặt `pendingDelete = true`, thử lại sau. Instrumentation kiểm chứng hợp đồng này với DAO hiện có; chưa bổ sung SessionRepository trong T-014.
- Retention 14 ngày và TTL export 24 giờ thuộc T-038. Không thêm dependency hoặc đổi schema Room.

Khi chạy hồi quy, test schema T-004 có sẵn lỗi `AbstractMethodError` ở `GeneratedSerializer.typeParametersSerializers()`. Dependency graph chọn core serialization 1.7.3 từ app, trong khi Room migration reader dùng JSON 1.8.1. Đã thêm version constraint 1.8.1 trong version catalog/build để căn chỉnh core dùng chung của app và test. Không thêm thư viện mới hoặc đổi test/schema để bỏ qua lỗi. Cách xử lý phù hợp hướng dẫn [dependency resolution của Android](https://developer.android.com/build/dependency-resolution-errors) và [compatibility của serialization](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/compatibility.md).

## Kiểm thử

Đã chạy trên bản source cuối trong bản sao build tại `/private/tmp/bugsnap-apk-build.ngt1jE/src`, dùng JDK 17.0.21, Gradle 9.6.0 và Android SDK 37. Emulator Google APIs Android 16/API 36 x86_64 (`emulator-5560`). Source app và version catalog đã được đối chiếu với workspace.

| Kiểm tra | Kết quả |
|---|---|
| `assembleDebug`, `assembleDebugAndroidTest` | PASS |
| `testDebugUnitTest` | 82 pass, 0 lỗi/bỏ qua, gồm 4 test mới |
| `lintDebug` | 0 error, 29 warning. 28 warning có sẵn; 1 `NewerVersionAvailable` cho constraint serialization 1.8.1 đang căn chỉnh theo Room |
| Instrumentation `data.files` | 19 pass |
| Instrumentation cuối `data.files` + `data.db` | 24 pass, gồm 19 test mới, 4 DAO và 1 test schema Room có sẵn |
| Mở app từ trạng thái force-stop | `Status: ok`; file mẫu `clip.mp4.part` trong `sessions/t014-startup-20261121/markers/m1/` bị dọn, `clip.mp4` vẫn còn. Dữ liệu mẫu đã được xóa sau kiểm tra |
| Kiểm tra tĩnh | `git diff --check` pass, XML hợp lệ, hai bản ADR giống nhau, source mới tối đa 198 dòng/file |

Lượt hồi quy đầu có 1 lỗi schema do runtime serialization nêu ở trên. Sau khi căn chỉnh đã chạy lại build, unit, lint và cả 24 instrumentation, kết quả cuối đều pass. Log build và test nằm ở `/private/tmp/bugsnap-apk-build.ngt1jE/t014-final-build.txt` và `t014-instrumentation-final.txt` trên máy phát triển. Không suy rộng kết quả này sang kiểm thử MediaProjection/overlay V1–V9 trên OnePlus Ace 5.

Các ca mới bao phủ đường dẫn/layout, ngưỡng dung lượng, ghi thay thế, callback lỗi/ENOSPC, file rỗng, cancellation, ghi đồng thời cùng/khác file, đọc file hoàn chỉnh, xóa/quét trong lúc ghi, symlink, dọn `.part`, cô lập phiên, round-trip đường dẫn qua Room và mở bằng FileProvider.

Lệnh tái hiện khi đã cấu hình JDK 17 và Android SDK theo README:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.bugsnap.ai.data.files
```

Lệnh instrumentation thực tế cho lượt cuối (sau khi cài APK app và APK androidTest):

```bash
adb -s emulator-5560 shell am instrument -w \
  -e package com.bugsnap.ai.data.files,com.bugsnap.ai.data.db \
  com.bugsnap.ai.test/androidx.test.runner.AndroidJUnitRunner
# OK (24 tests)
```

API đã đối chiếu tài liệu chính thức [Files.move](https://developer.android.com/reference/java/nio/file/Files#move(java.nio.file.Path,%20java.nio.file.Path,%20java.nio.file.CopyOption...)) và [runInterruptible](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-interruptible.html).
