# Lưu video vào DCIM/BugSnap

Thay đổi ngày 2026-11-21 theo yêu cầu chủ dự án, sau T-014. Video mới sau Mark Bug được lưu vào `DCIM/BugSnap` của bộ nhớ dùng chung trên máy Android và xuất hiện trong Thư viện.

## Luồng lưu

1. Mux MP4 và trích ảnh bằng luồng hiện có trong thư mục riêng tạm `files/spike/<markerId>/`.
2. `VideoGalleryStore` tạo video `BugSnap_<markerId>.mp4` bằng MediaStore, `RELATIVE_PATH = DCIM/BugSnap/`, `IS_PENDING = 1`. Ghi trên dispatcher IO, đóng và đồng bộ dữ liệu trước khi công bố `IS_PENDING = 0`.
3. Lưu URI `content://media/.../video/media/<id>` vào `clip.json` bằng file `.part` rồi atomic move. Chỉ lúc này mới báo “Đã lưu” và cung cấp nút Xem clip.
4. Xóa MP4 tạm riêng sau khi index thành công. Ảnh bằng chứng và index tiếp tục ở bộ nhớ riêng.

Ghi lỗi/hủy trước công bố sẽ xóa bản MediaStore đang dở, giữ MP4 riêng để không mất bản ghi. Lỗi lưu index sau khi công bố vẫn giữ video DCIM và MP4 riêng. Lỗi dọn MP4 riêng báo riêng, không đảo kết quả video đã lưu thành thất bại.

Tên theo UUID marker giúp các marker không ghi đè nhau. Lưu lại cùng ID trả URI đã hoàn chỉnh thay vì tạo bản sao. Mutex của singleton bảo vệ ghi và dọn pending. Khi mở màn hình chính, chỉ quét video đang dở do app sở hữu trong `DCIM/BugSnap`, giữ video hoàn chỉnh và các thư mục khác.

API 30+ dùng `QUERY_ARG_MATCH_PENDING` để tìm bản ghi đang dở; API 29 dùng `MediaStore.setIncludePending` có giới hạn phiên bản vì chưa có API thay thế trên Android 10 (ADR-21). Không xin `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_VIDEO` hoặc `MANAGE_EXTERNAL_STORAGE`.

## Đọc lại và tương thích

`SpikeClipInfo.clipPath` nhận URI MediaStore cho clip mới và đường dẫn riêng cho clip cũ. `SpikeIntents` mở đúng loại URI, cấp quyền đọc cho ứng dụng phát video. Index đọc lại clip mới qua `videoUri`, loại bỏ mục nếu người dùng đã xóa video trong Thư viện. Clip cũ có/không có index vẫn đọc theo luồng cũ; không tự công bố lại toàn bộ clip cũ.

Video công khai có thể tồn tại sau khi gỡ app, chịu cơ chế quản lý/sao lưu của Thư viện Android. Thay đổi không upload lên AI hoặc tự gửi dữ liệu qua mạng. EvidenceStore T-014 tiếp tục quản lý store riêng; T-015/T-016 sẽ nối MediaStore khi đưa kiến trúc phiên vào luồng quay.

## Xác minh

Đã xác minh trên emulator Google APIs Android 16/API 36 x86_64 (`emulator-5560`), ngày 2026-11-21. Build bằng JDK 17.0.21 / Gradle 9.6.0 / compileSdk 37 trong bản sao source `/private/tmp/bugsnap-apk-build.ngt1jE/src`; đã đối chiếu source app với workspace.

| Kiểm tra | Kết quả |
|---|---|
| `assembleDebug`, `assembleDebugAndroidTest` | PASS |
| `testDebugUnitTest` | 82 pass, 0 lỗi/bỏ qua |
| `lintDebug` | 0 error, 31 warning (29 đã có từ T-014 và 2 gợi ý `UseKtx` cho URI) |
| `VideoGalleryStoreTest` | 11 pass |
| MediaStore + EvidenceStore + Room instrumentation | 35 pass |
| `CaptureFlowTest.capturesAndSavesRealVideo` | 1 pass: đồng ý MediaProjection, Mark, post-roll, lưu DCIM, đọc duration > 0 qua URI, dừng service |
| Kiểm tra file thực tế | `DCIM/BugSnap/BugSnap_9c5ab033-8695-43e3-bd38-b1b51b3f72fc.mp4`, 882093 byte. Thư mục riêng tương ứng chỉ còn index + 2 JPEG, không còn MP4 |
| Kiểm tra tĩnh | `git diff --check` pass; hai bản ADR giống nhau; source mới tối đa 266 dòng/file |

Bộ test mới kiểm tra thư mục/URI, rollback khi lỗi/hủy, không tạo video trùng, dọn pending đúng phạm vi và chờ tác vụ đang ghi, đọc lại index không cần MP4 riêng, xóa từ Thư viện, tương thích clip cũ, phản hồi thành công/thất bại của luồng xuất. Lượt đầu có lỗi teardown xóa lại URI đã mất; đã sửa dọn dữ liệu theo các hàng còn tồn tại và chạy lại cả 35 test thành công. Chưa kiểm tra trên OnePlus Ace 5 thật hoặc nhánh tương thích API 29.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
adb -s emulator-5560 shell am instrument -w \
  -e package com.bugsnap.ai.data.media,com.bugsnap.ai.data.files,com.bugsnap.ai.data.db \
  com.bugsnap.ai.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5560 shell am instrument -w \
  -e class com.bugsnap.ai.spike.CaptureFlowTest \
  com.bugsnap.ai.test/androidx.test.runner.AndroidJUnitRunner
```

Log kiểm thử ở thư mục build tạm: `dcim-final-build.txt`, `dcim-storage-tests-final.txt`, `dcim-capture-flow.txt`. APK giao để cài là `BugSnapAI-DCIM-debug.apk` ở gốc repo (ignored bởi git), SHA-256 `bf24f5d4759f4b2cc9f36b282ece02954aa0c72b3847c089f4a11cbac8f638fa`.

Đã đối chiếu hướng dẫn chính thức [shared media storage](https://developer.android.com/training/data-storage/shared/media) và [RELATIVE_PATH](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#RELATIVE_PATH).
