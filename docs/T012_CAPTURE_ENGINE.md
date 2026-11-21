# T-012 — CaptureEngine

Ngày triển khai: 2026-11-21. Trạng thái: đã triển khai, build/unit/lint và kiểm thử 10 phút/20 lần xoay trên emulator đạt; **chờ nghiệm thu OnePlus Ace 5**. Phạm vi T-012 theo `06` và task `15`; không triển khai SessionCoordinator/Room pipeline của T-015/T-016.

## Cấu trúc và vòng đời

`CaptureEngine` quản lý trạng thái, buffer, start/stop/pause/resume, resize và thermal policy. API trả `Result<..., DomainError>`, state có lỗi gốc và lỗi cleanup riêng. `CaptureBackend` là cổng native để kiểm thử engine với fake trên JVM. Hilt cấp `CaptureEngineFactory`; mỗi lần đồng ý quay tạo một engine riêng, gắn với scope của service.

`AndroidCaptureBackend` sở hữu một HandlerThread. Toàn bộ thao tác MediaCodec, callback output, VirtualDisplay và đóng tài nguyên đi qua cùng looper. Bản đầu dùng callback bất đồng bộ. Sau phản ánh video đen, bản `0.1.3-t012-fix` đổi sang polling có giới hạn trên cùng looper, luôn trả output buffer trong `finally`; không có dequeue chạy đồng thời với stop/release. Xem [bản sửa video đen](T012_BLACK_VIDEO_FIX.md).

`ProjectionHolder` chỉ nhận consent một lần, đăng ký callback trước khi tạo display. `VirtualDisplayController` chỉ tạo một display cho engine. Resize/thermal: detach Surface → dừng và release encoder/Surface cũ → encoder generation mới → resize/attach Surface mới trên display hiện có. Pause giữ projection/display, resume thay encoder mà không gọi createVirtualDisplay lần nữa. Sau stop/lỗi cần consent mới.

Khi đổi profile, engine khóa generation cũ khỏi vùng đủ điều kiện Mark, kết thúc các snapshot đang chờ bằng phần đã ghi, rồi chờ keyframe generation mới. Callback đến trễ không thể làm engine báo sẵn sàng cho cấu hình mới. API 34+ ưu tiên `onCapturedContentResize` để dùng đúng kích thước vùng được chia sẻ; cấu hình màn hình là fallback API 29–33 và cập nhật density.

Stop/lỗi đóng display, encoder/Surface, projection và các listener cấu hình/nhiệt, rồi kết thúc worker. Hủy scope hoặc onDestroy vẫn đưa cleanup vào looper native, dù coroutine đã bị hủy. Bộ đếm tài nguyên ghi số handle engine đang giữ, peak và lỗi release; bộ đếm này không thay thế đo native allocations bên trong driver OEM.

Service spike hiện giữ FGS, thông báo, overlay, Mark và phản hồi export. StartForeground vẫn chạy sau khi có consent, trước getMediaProjection. UI/đường lưu `DCIM/BugSnap` được giữ. OnDestroy không chờ executor export trên Main. Phần native đã chuyển khỏi package spike; không thêm dependency.

## Cấu hình và nhiệt

- H.264, cạnh dài tối đa 1280, làm tròn bội 16; không upscale màn hình nhỏ. Mặc định 30 fps/4 Mbps, keyframe 1 giây, yêu cầu sync định kỳ.
- Thử `repeat-previous-frame-after`; nếu codec từ chối, đóng đầy đủ lần mở lỗi trước khi thử lại không có tham số này.
- MODERATE trở lên: 24 fps/3 Mbps; đổi profile tạo generation mới. SEVERE còn hiển thị cảnh báo để người dùng dừng. Khi nhiệt trở lại thấp hơn MODERATE, trở về cấu hình mặc định.
- Pause/resume là API engine cho coordinator; T-012 chưa thêm nút Pause của UI sản phẩm.
- Chẩn đoán V1 trong build T-012 chỉ báo token dùng một lần, không cố tạo VirtualDisplay thứ hai. Không dùng thông báo đó làm bằng chứng V1 trên Ace 5; probe spike cũ và checklist T-010 vẫn chưa được nghiệm thu.

## Kiểm thử và giới hạn

Build được chạy trong bản sao source `/private/tmp/bugsnap-apk-build.ngt1jE/src`, đã đối chiếu với workspace; JDK 17.0.21 / Gradle 9.6.0 / compileSdk 37. Emulator Google APIs Android 16/API 36 x86\_64 (`emulator-5560`).

Bảng dưới đây là kết quả của bản T-012 ban đầu dùng async encoder (`0.1.2-t010`). Kết quả bản sửa mới được ghi riêng trong [T012\_BLACK\_VIDEO\_FIX.md](T012_BLACK_VIDEO_FIX.md).

| Kiểm tra                                    | Kết quả                                                                                                                                                                           |
| ------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `assembleDebug`, `assembleDebugAndroidTest` | PASS                                                                                                                                                                              |
| `testDebugUnitTest`                         | 120 pass, 0 lỗi/bỏ qua; 20 test mới (6 config + 14 engine)                                                                                                                        |
| `lintDebug`                                 | 0 error, 31 warning có sẵn; không thêm warning                                                                                                                                    |
| `CaptureInvalidConsentTest`                 | 1 pass; trạng thái FAILED và handle/worker về 0 sau consent lỗi                                                                                                                   |
| Xoay nhanh (`captureSoakMs=0`)              | 20 lần xoay + Mark/lưu clip/đọc duration/dừng, 1 pass                                                                                                                             |
| Soak (`captureSoakMs=600000`)               | 20 lần xoay, giữ quay đủ 10 phút, Mark và đọc duration MP4 từ MediaStore, 1 pass. Mốc hoàn tất/dừng 606306 ms sau khi bắt đầu recording; toàn lượt test gồm consent mất 630,454 s |
| Tài nguyên trong lượt soak                  | Mỗi loại projection/display/codec/Surface/worker giữ đúng 1, peak không vượt 1. Sau stop: cả 5 loại về 0, `releaseErrors=0`                                                       |
| `CaptureFlowTest` hồi quy                   | 1 pass; thao tác Mark trên UI, lưu MP4 DCIM có duration, dừng FGS/thông báo                                                                                                       |
| Source                                      | Không file mới vượt 500 dòng; source/test engine dài nhất 251 dòng. `git diff --check` pass, hai bản ADR giống nhau                                                               |

10 mốc đo sau vòng xoay trên màn hình BugSnap: PSS 106747–115925 KiB; native heap 28519760–30043536 byte, cuối 28523056 byte (\~27,20 MiB); ring lớn nhất trong các mốc là 851510 byte. Sau phút 2, native heap nằm trong 28519760–28779184 byte, không thấy tăng dần trong khoảng quan sát. Đây là mẫu nội dung UI của app và bộ đếm handle do engine sở hữu, không phải đo worst-case chuyển động hoặc bằng chứng không rò driver OEM.

Bộ test mới gồm sizing/thermal, start/stop idempotence, lỗi khi mở/resize/cleanup, pause/resume, callback cũ, mất projection, hủy scope và hủy giữa start. Lượt harness ban đầu chưa đặt entry point chẩn đoán trong debug graph Hilt; đã chuyển sang `src/debug` rồi build và chạy lại. Không đưa entry point này vào release.

Tổng cộng 4 lượt instrumentation pass (consent lỗi, xoay nhanh, soak 10 phút và hồi quy capture), trong đó bài xoay/soak được chạy ở hai cấu hình thời gian. Log: `t012-final-build.txt`, `t012-invalid-consent.txt`, `t012-rotation-smoke.txt`, `t012-soak-10min.txt`, `t012-capture-regression.txt` trong thư mục build tạm. APK giao để thử là `BugSnapAI-T012-debug.apk` ở gốc repo (gitignored), SHA-256 `fe2dfd4bd8bdd064c7a0b04d7565914aaef561dd1b0abd12c6576f1a89201239`.

Môi trường hiện có emulator Android 16/API 36. Chưa có OnePlus Ace 5 kết nối. Theo `docs/AGENTS.md` §B.19, các giả định MediaProjection/OxygenOS cần xác minh trên Ace 5 trước khi đánh dấu nghiệm thu đầy đủ T-012; kết quả emulator không thay thế phần này.

Lệnh tái hiện sau khi cài APK app và APK androidTest:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
adb -s emulator-5560 shell am instrument -w \
  -e class com.bugsnap.ai.capture.engine.CaptureInvalidConsentTest \
  com.bugsnap.ai.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5560 shell am instrument -w \
  -e class com.bugsnap.ai.capture.engine.CaptureEngineFlowTest \
  -e captureSoakMs 600000 \
  com.bugsnap.ai.test/androidx.test.runner.AndroidJUnitRunner
```

Trên máy thật có thể cài APK, quay toàn màn hình 10 phút, xoay dọc/ngang 20 lần, Mark trước/sau xoay rồi xem clip trong DCIM. Cần ghi build/OS, lỗi, RAM/native allocations và việc dừng/thu hồi projection; xác minh thêm chia sẻ một app và checklist V1–V9. Log dev dùng `adb logcat -s BugSnapCapture:V BugSnapSpike:V`.

API đã đối chiếu [MediaProjection](https://developer.android.com/media/grow/media-projection), [quy tắc Android 14](https://developer.android.com/about/versions/14/behavior-changes-14#media-projection) và [MediaCodec callbacks](https://developer.android.com/reference/android/media/MediaCodec).
