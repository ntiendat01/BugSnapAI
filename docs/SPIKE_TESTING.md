# T-010 — Hướng dẫn kiểm thử trên thiết bị

Người dùng đã xác nhận ghi được video ở bản 0.1.0-t010. Checklist V1–V9 trên OnePlus Ace 5 **chưa được nghiệm thu đầy đủ**. Không điền kết quả giả. Sau khi chạy trên máy thật, chép số liệu vào bảng mục B của `docs/16_TECHNICAL_DECISIONS.md` (cả bản `docs/16` và `docs/specs/BugSnapAI/docs/16`).

## 1. Cài đặt

Trên máy phát triển, nhánh `dev`:

```bash
./gradlew installDebug
# hoặc adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Phiên bản giao diện phản hồi mới: `0.1.1-t010`. Thiết bị chính: OnePlus Ace 5 / OxygenOS 16 (Android 16).

Khi mở app, màn hình đầu là **BugSnap AI / Sẵn sàng quay thử**. Cấp quyền thông báo nếu hệ thống hỏi. Nên cấp quyền overlay (nút trên màn hình) trước khi làm V2/V3.

## 2. Logcat và chỉ số

```bash
adb logcat -s BugSnapSpike:V
```

Tag duy nhất: `BugSnapSpike`. Các dòng hữu ích:

| Dòng log | Ý nghĩa |
|---|---|
| `encoder started gen=… WxH` | Encoder H.264 đã chạy |
| `keyframe ptsUs=… intervalMs=…` | V4 — khoảng I-frame thực tế |
| `fps=…` | Số frame encode mỗi giây |
| `heartbeat minute=… stillRunning=true thermal=… batteryPct=…` | V6 — service còn sống |
| `ram … bufferBytes=… javaHeap=… nativeHeap=…` | V8 — RAM bộ đệm và heap |
| `markDone id=… latencyMs=… actualPreRollMs=… actualPostRollMs=… durationMs=… path=…` | V8 — độ trễ Mark → clip |
| `projection onStop elapsedMs=…` | V5 — hệ thống đã dừng projection |
| `reuseToken result=…` | V1 — thử tạo VirtualDisplay lần hai |
| `rotation/resize new encoder …` | V9 — đổi generation khi xoay |
| `posted V7 test notification body=SPIKE-NOTIF-VISIBLE` | V7 — đã gửi thông báo thử |

Clip và ảnh (chỉ sau Mark): `adb shell "run-as com.bugsnap.ai ls -l files/spike"` rồi `adb exec-out run-as com.bugsnap.ai cat files/spike/.../clip.mp4 > clip.mp4`. Hoặc bấm item clip trên UI (mở bằng FileProvider).

Màn hình spike cũng hiện: thời gian đã ghi, độ dài buffer, RAM, FPS, khoảng I-frame, generation, overlay, nhiệt, pin, latency Mark.

## 3. Quy trình V1–V9

Ghi **build / model / phiên bản OxygenOS** ở đầu mỗi lần chạy. Ô kết quả để trống cho đến khi chạy trên máy thật.

### V1 — Token MediaProjection dùng lại
1. Bấm **Bắt đầu quay**, đồng ý hộp thoại hệ thống (nên chọn toàn màn hình).
2. Đợi status **Đang ghi**.
3. Bấm **V1: thử VirtualDisplay lần hai**.
4. Đọc kết quả trên UI và log `reuseToken result=…`.
- Kỳ vọng (cần xác minh): `SecurityException` trên Android 14+/16; không được tạo VirtualDisplay thứ hai từ cùng token.
- Kết quả: _chưa chạy_
- Ngày / build / OS:

### V2 — Chia sẻ một ứng dụng, overlay có vào clip không?
1. Cấp quyền overlay. Đảm bảo huy hiệu **BS** góc trên hiện khi đang ghi.
2. Bấm **Bắt đầu quay**, ở hộp thoại hệ thống chọn **một ứng dụng** (một app khác, không phải BugSnap).
3. Dùng app đó vài giây, bấm Mark (nút trên spike, hoặc action trên notification). Đợi post-roll 5 s, mở clip.
4. Quan sát: huy hiệu BS / UI BugSnap có trong video không? Khi đưa AUT ra nền (Home hoặc app khác), hình còn quay không, đen, hay projection dừng?
- Kết quả: _chưa chạy_
- Ngày / build / OS:

### V3 — Quay toàn màn hình, bong bóng/huy hiệu có trong clip?
1. Cấp overlay. Bắt đầu quay, chọn **toàn màn hình**.
2. Để huy hiệu BS và dòng "BUGSNAP SPIKE" hiện rõ, Mark, xem clip + `screenshot_mark.jpg`.
- Kết quả: _chưa chạy_
- Ngày / build / OS:

### V4 — Màn hình tĩnh có keyframe định kỳ?
1. Bắt đầu quay toàn màn hình, để máy nằm yên, không chạm 20–30 s (màn hình vẫn sáng).
2. Theo dõi log `keyframe … intervalMs=…`. Kỳ vọng khoảng 1000 ms (± một GOP).
3. Mark, kiểm tra clip phát được và bắt đầu đúng (không hỏng đầu file).
- Kết quả: _chưa chạy_
- Ngày / build / OS:

### V5 — Khóa màn hình
1. Đang ghi, khóa màn hình 5–10 s, mở lại.
2. Log có `projection onStop` không? UI có chuyển **Mất MediaProjection — cần cấp lại** không?
3. App **không** được tự phục hồi. Bấm **Bắt đầu quay** lại (token mới) thì ghi tiếp được không?
- Kết quả: _chưa chạy_
- Ngày / build / OS:

### V6 — OxygenOS giết service?
1. Bắt đầu quay, để chạy **60 phút** khi màn hình sáng (ghi `heartbeat minute=`).
2. Lần khác: 60 phút khi tắt màn hình (có thể trùng V5 nếu khóa máy cắt projection — ghi rõ).
3. Thử thêm chế độ tiết kiệm pin. Ghi phút service bị kill (nếu có) và lý do (onStop / process chết / vẫn sống).
- Kết quả: _chưa chạy_
- Ngày / build / OS:

### V7 — Thông báo trên Android 15+
1. Đang quay toàn màn hình, bấm **V7: gửi thông báo thử**. Nội dung cần tìm: `SPIKE-NOTIF-VISIBLE`.
2. Mark ngay, xem clip/ảnh: chữ thông báo có bị hệ thống che không.
- Kết quả: _chưa chạy_
- Ngày / build / OS:

### V8 — Latency Mark → clip và RAM
1. Để buffer đầy khoảng 30 s, bấm Mark, đợi clip (post-roll 5 s + mux).
2. Ghi `latencyMs`, `bufferBytes`, `javaHeap`, `nativeHeap` từ log `markDone` / `ram`.
3. So với mục tiêu: clip sẵn sàng ≤ post-roll + 5 s; RAM buffer thiết kế ≈ 18 MB, trần 80 MB.
- Kết quả: _chưa chạy_
- Ngày / build / OS:

### V9 — Xoay màn hình 20 lần
1. Đang ghi, xoay dọc/ngang **20 lần**. Log `rotation/resize new encoder` / `configuration changed`.
2. Mark sau vài lần xoay. Clip chỉ được chứa **một generation** (có thể ngắn hơn 30 s). App không được treo. Quan sát tăng RAM bất thường (dấu hiệu rò codec/surface).
- Kết quả: _chưa chạy_
- Ngày / build / OS:

## 4. Việc spike này không chứng minh được

- Hành vi OxygenOS / Ace 5: chỉ máy thật mới đủ.
- Emulator (nếu có) không thay cho V1–V9 trên Ace 5.
- Spike **không** gồm bong bóng kéo thả sản phẩm (T-019), session Room, hay AI.
- Đo nhiệt/pin chỉ log số liệu, chưa tự hạ bitrate khi nóng (chỉ cảnh báo SEVERE trên UI).
