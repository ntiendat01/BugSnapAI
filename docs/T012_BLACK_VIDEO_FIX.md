# T-012 — Bản sửa tương thích video đen/đứng hình

Ngày 2026-11-21, người dùng báo video đen/đứng hình, liên quan đoạn đếm ngược còn 1 giây; trình phát vẫn còn thời lượng. Quay toàn màn hình, nhiều app khác nhau đều gặp. Chưa có máy OnePlus kết nối hoặc clip/log từ máy đó để kết luận nguyên nhân gốc.

Các test T-012 ban đầu chỉ kiểm tra MP4 có duration, vòng đời và bộ nhớ. Các kết quả đó không chứng minh nội dung khung hình hợp lệ; cần kiểm tra pixel và sự thay đổi hình ảnh.

## Bản 0.1.3-t012-fix (versionCode 4)

- Đổi đọc output MediaCodec từ callback async về polling đồng bộ, gần với đường đọc của spike trước T-012. Mỗi lượt tối đa 8 buffer, nhường worker 5 ms; open/poll/resize/stop/release vẫn trên cùng HandlerThread, không phục hồi race giữa dequeue và stop ở luồng khác.
- Yêu cầu H.264 Baseline, không cấu hình B-frame/latency tùy chọn, để phù hợp ring buffer giữ PTS tăng và clip độc lập từ keyframe. Giới hạn FPS đầu vào Surface theo frame rate cấu hình, tránh nguồn màn hình tần số cao đẩy nhiều frame hơn cấu hình quay.
- Nếu vendor từ chối yêu cầu sync frame phụ, ghi log một lần và ngừng yêu cầu phụ cho generation đó; tiếp tục đọc video theo I-frame interval đã cấu hình. Lỗi này không còn tự kết thúc cả phiên quay. Lỗi đọc codec thật, EOS bất ngờ hoặc PTS không tăng vẫn kết thúc phiên có báo lỗi, thay vì bỏ frame tham chiếu rồi tiếp tục ghi clip hỏng.
- Không đổi Surface/VirtualDisplay khi hoàn tất Mark; đường lưu vẫn là `DCIM/BugSnap`.

Đây là bản sửa tương thích dựa trên đường xử lý dùng chung và hồi quy từ T-012. Chưa khẳng định đã xác định hoặc khắc phục nguyên nhân trên OnePlus cho đến khi người dùng kiểm tra lại.

## Kiểm thử

Emulator Google APIs Android 16/API 36 x86_64. Build trong `/private/tmp/bugsnap-apk-build.ngt1jE/src`, JDK 17.0.21 / Gradle 9.6.0 / compileSdk 37.

- Build app/androidTest thành công, 122 unit test pass; gồm 2 test mới cho sync-request thành công/bị vendor từ chối/reset generation. Lint 0 error, 31 warning có sẵn.
- Bài `CaptureVideoPixelsTest` tạo nội dung màu có chuyển động, kiểm tra pixel ở trước Mark, giữa post-roll, mốc 4 giây và 100 ms trước frame cuối; so sánh chuyển động giữa hai mốc cuối để phát hiện đứng hình. Quay hai marker liên tiếp trong cùng phiên, yêu cầu post-roll thực tế ≥ 4,7 giây, kiểm tra engine vẫn RECORDING và tài nguyên về 0 khi dừng.
- Bản sửa đã qua lượt kiểm tra cuối: 1 pass trong 60,172 s. Cả hai clip có màu đúng ở cả 4 mốc và hình ở giây thứ 4 khác hình sát cuối nhờ vật thể đang chuyển động; post-roll ≥ 4,7 s, vẫn ghi được clip thứ hai và handle về 0 sau dừng. Log `black-video-final-pixels.txt` ghi `OK (1 test)`.
- `CaptureInvalidConsentTest` + `CaptureEngineFlowTest` với `captureSoakMs=0`: 2 pass. Xoay 20 lần, xuất MP4 và dừng; peak mỗi loại handle ≤ 1, sau dừng projection/display/codec/Surface/worker đều 0 và releaseErrors=0.

Lượt đối chiếu bản cũ timeout đợi Saved sau 30 s khi đang chạy Gradle đồng thời; log ghi lưu muộn khoảng 42,5 s. Một lượt test pixel đầu bản sửa báo pixel sáng thay vì màu kỳ vọng; lượt chạy lại có ảnh đối chiếu nguồn/frame đã pass, sau đó tăng thêm kiểm tra chuyển động cuối clip và chạy lại thành công. Không coi hai hiện tượng trong môi trường emulator này là bằng chứng tái hiện đúng lỗi đen trên OnePlus. Source/test ảnh dùng nội dung giả lập, không ghi dữ liệu của AUT thật.

Không chạy lại soak 10 phút trên bản sửa này; số liệu 10 phút trong tài liệu T-012 là của bản async trước đó. Kết quả mới giới hạn ở kiểm tra hình ảnh, ghi tiếp và vòng đời/20 lần xoay. Cần kiểm tra trên OnePlus Ace 5 trước khi nghiệm thu đầy đủ.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
adb -s emulator-5560 shell am instrument -w \
  -e class com.bugsnap.ai.capture.engine.CaptureVideoPixelsTest \
  com.bugsnap.ai.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5560 shell am instrument -w \
  -e class com.bugsnap.ai.capture.engine.CaptureInvalidConsentTest,com.bugsnap.ai.capture.engine.CaptureEngineFlowTest \
  -e captureSoakMs 0 \
  com.bugsnap.ai.test/androidx.test.runner.AndroidJUnitRunner
```

APK: `BugSnapAI-0.1.3-video-fix-debug.apk`, SHA-256 `c5e9d9095c52e6904a19f55dd2b16442168d704723199299e91eaa5b7e959ae5`. Log build/test ở thư mục build tạm: `black-video-final-build.txt`, `black-video-final-test-build.txt`, `black-video-pixel-proof.txt`, `black-video-final-pixels.txt`, `black-video-fixed-lifecycle.txt`.

Đối chiếu API chính thức [cấu hình video chia sẻ/Baseline](https://developer.android.com/media/optimize/sharing), [MediaFormat FPS đầu vào](https://developer.android.com/reference/android/media/MediaFormat#KEY_MAX_FPS_TO_ENCODER) và [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec).
