# 07 — Thiết kế Rolling Buffer

## 1. So sánh phương án
| Phương án | Ưu | Nhược | Kết luận |
|---|---|---|---|
| **A. Ring buffer mẫu đã mã hóa trong RAM → mux khi Mark** | Không khoảng hở; cắt chính xác theo keyframe; không I/O liên tục; không file hỏng; đơn giản | Mất buffer nếu process chết; tốn RAM (~15–30 MB) | **Chọn** |
| B. `MediaRecorder` xoay đoạn file | Sống sót khi process chết | Khoảng hở khi chuyển đoạn, ghép/cắt phức tạp, I/O liên tục, ranh giới Mark khó | Không |
| C. Ring file tự quản (ghi mẫu thô ra đĩa) | Chịu crash | Phải tự định dạng + quản lý chỉ mục, nhiều code hơn A | Không cho MVP |
Phương án A "đơn giản và đáng tin nhất": rủi ro duy nhất (mất buffer khi process chết) được giảm bằng cách persist marker ngay khi bấm (xem §6).

## 2. Mô hình dữ liệu
```text
EncodedSample { data: ByteArray, ptsUs: Long, flags: Int (KEY_FRAME?), generation: Int }
Generation    { id, width, height, csd (SPS/PPS từ INFO_OUTPUT_FORMAT_CHANGED), startPtsUs }
RingBuffer    { deque<EncodedSample> (theo ptsUs tăng dần), maxDurationUs, lock }
ClipSnapshot  { samples: List<EncodedSample> (tham chiếu bất biến), generation, tMarkUs }
```
- `ptsUs` lấy từ `BufferInfo.presentationTimeUs` của encoder (cùng đồng hồ monotonic). Marker lưu `tMarkUs` cùng thang đo + `SystemClock.elapsedRealtime()` + wall clock để hiển thị.

## 3. Quy tắc cắt tỉa (eviction)
- Giữ mẫu từ **keyframe đầu tiên tại hoặc sau `tNow − (buffer + margin)`** trở đi. `margin` mặc định 1 s. Như vậy thời lượng vùng đủ điều kiện luôn ≤ `buffer + margin` thay vì vượt thêm một GOP.
- Chỉ xóa theo đơn vị GOP (keyframe đến trước keyframe kế) để mẫu đầu luôn là keyframe ⇒ clip luôn giải mã được.
- Khi đổi generation (xoay màn hình, pause/resume): xóa mẫu generation cũ khỏi vùng "đủ điều kiện cắt clip"; giữ tham chiếu nếu snapshot đang dùng.
- Dùng `ReentrantLock` hoặc một coroutine đơn luồng (Channel) sở hữu buffer; **snapshot phải đồng bộ** với việc thêm mẫu.
- Khi không còn keyframe trong giới hạn giữ, bỏ GOP quá cũ và đợi keyframe mới. Không giữ một GOP vô hạn nếu encoder ngừng sinh sync frame.
- T-011: `EncodedSample` và `Generation` sao chép byte đầu vào; getter trả bản sao. `ClipSnapshot` đóng băng danh sách, chia sẻ cùng sample bất biến. `sizeBytes` phục vụ thống kê/mux mà không cần sao chép payload chỉ để đọc kích thước.

## 4. Mark Bug: pre-roll + post-roll
- **Pre-roll** = `bufferSec` (15/30, mặc định 30). Trần rolling buffer / pre-roll là **30 s** (không còn tùy chọn 60 s). **Post-roll** mặc định 5 s (0/3/5/10). Lý do post-roll: nhiều bug lộ rõ *sau* khi thao tác; 5 s ít tốn RAM.
- **Độ dài clip xuất ra** = pre-roll thực tế + post-roll. Trần 30 s **chỉ áp dụng cho buffer/pre-roll**, không gồm post-roll. Ví dụ: mặc định 30 + 5 = 35 s; tối đa 30 + 10 = 40 s.
- Không ghi clip/ảnh xuống đĩa cho đến khi Mark Bug; mẫu cũ trong buffer bị ghi đè trong RAM.
- Thuật toán:
  1. T0: ghi marker `PENDING` vào DB (id mới, `tMarkUs`).
  2. Chọn sample từ keyframe đầu tiên **≥ `tMark − preRoll` và ≤ `tMark`**. Pre-roll thực tế có thể ngắn hơn tới một GOP để bảo đảm trần 15/30 s theo ADR-16. Không chọn keyframe trước ranh giới vì sẽ làm vượt trần.
  3. Tiếp tục nhận mẫu có PTS ≤ `tMark + postRoll` (reader phụ gắn vào snapshot; không đụng vào buffer chính). Mẫu tại deadline được giữ và kết thúc clip; mẫu vượt deadline chỉ kết thúc clip, không được đưa vào snapshot.
  4. Hàng đợi export (`Semaphore(3)`): `MediaMuxer` → `clip.mp4.part` → `renameTo` atomic.
  5. Trích 2 ảnh, ghi `Evidence`, marker `READY`.
- **Marker liên tiếp**: mỗi marker có snapshot riêng; các snapshot **dùng chung** `EncodedSample` (bất biến) nên chỉ tốn thêm danh sách tham chiếu. Thư mục riêng `markers/{markerId}/` ⇒ không ghi đè. Chặn spam: debounce 300 ms theo hành động nhưng không loại marker hợp lệ.
- Nếu Mark xảy ra khi buffer chưa đầy: clip ngắn hơn, ghi `actualPreRollMs`.
- Nếu phiên dừng trước khi hết post-roll: finalize với mẫu hiện có (`actualPostRollMs` < cấu hình).
- `mark` lấy PTS mặc định trong cùng khóa với snapshot. Với timestamp lịch sử hợp lệ, thu cả post-roll đã có trong ring. Nếu không có keyframe trong cửa sổ yêu cầu, timestamp ở tương lai/âm, cấu hình vượt giới hạn hoặc ID đang pending bị trùng, trả `MarkResult.Rejected`, không tạo snapshot rỗng.
- Pre-roll nhận từ 0 đến độ dài buffer cấu hình (tối đa 30 s), post-roll nội bộ từ 0 đến 10 s; UI tiếp tục chỉ cung cấp các lựa chọn sản phẩm. Deadline không được tràn `Long`.

## 5. Muxing
- `MediaMuxer(MPEG_4)`; `addTrack(MediaFormat)` dựng từ CSD của generation; ghi mẫu bắt đầu từ keyframe đầu; **dịch PTS** sao cho mẫu đầu = 0; `release()` trong `finally`.
- Nếu muxer lỗi: xóa `.part`, marker `CLIP_FAILED`, giữ ghi chú, thử lại tối đa 1 lần.
- Xác nhận file: mở bằng `MediaMetadataRetriever`, đọc duration ≠ 0 trước khi rename.

## 6. Ước lượng RAM
`RAM ≈ bitrate × (buffer + margin + postRoll) / 8`
| Cấu hình | Ước lượng |
|---|---|
| 4 Mbps, 15 s + 1 + 5 | ≈ 11 MB |
| 4 Mbps, 30 s + 1 + 5 (tối đa) | ≈ 18 MB |
| Trường hợp xấu: 3 snapshot đang chờ mux (mỗi clip tối đa ≈ 30+10 s ≈ 20 MB), đã bị cắt khỏi buffer | + ≤ 3 × 20 MB |
Trần thiết kế (NFR-4) ≤ 80 MB vẫn đủ cho cấu hình tối đa 30 s + 3 export song song; còn phải đo thực. Nếu vượt: giảm bitrate xuống 3 Mbps hoặc giới hạn số export song song còn 2. Dùng `ByteArray` (heap) hoặc `ByteBuffer.allocateDirect` pool; cần đo trong spike.

## 7. Phục hồi và độ bền
| Sự cố | Hành vi |
|---|---|
| Process bị kill giữa phiên | Buffer mất. Marker đã lưu DB (`PENDING`) → `CLIP_LOST` khi mở lại; ghi chú/timestamp vẫn còn. Phiên `FAILED(PROCESS_DIED)` |
| Mất projection | Flush các snapshot đang chờ bằng dữ liệu có sẵn, PAUSED |
| Hết dung lượng khi mux | `CLIP_FAILED`, cảnh báo, PAUSED(LOW_STORAGE) |
| `.part` mồ côi | Dọn khi khởi động |
Giảm cửa sổ mất dữ liệu: post-roll ngắn mặc định; V2 có thể "mux ngay pre-roll" trước rồi nối post-roll (không làm ở MVP).

## 8. Đồng bộ thời gian
Một đồng hồ duy nhất: `ptsUs` của encoder. Ghi chú, ảnh, marker đều lưu `tMarkUs` và offset so với `sessionStartPtsUs`. Hiển thị cho tester dạng `mm:ss` từ đầu phiên và giờ thực.

## 9. Pause / Resume
- Pause: ngừng nhận mẫu vào buffer (VirtualDisplay giữ nguyên hoặc `setSurface(null)`), **đóng generation**. Resume: generation mới + yêu cầu sync frame. Clip không bao giờ vắt qua khoảng pause.
- T-011: generation ID tăng trong một stream; đăng ký cùng cấu hình/ID là idempotent, ID cũ hoặc tái dùng ID với cấu hình khác bị từ chối (`false`). Đổi generation xóa ring đủ điều kiện và reset PTS. Pending cũ giữ mẫu của nó, kết thúc khi sample hợp lệ đầu tiên của generation mới đến hoặc `forceFinalizePending()` lúc dừng. Tác vụ gọi phải nhận các snapshot được trả về để xuất clip.
- Append bỏ callback thuộc generation cũ/chưa đăng ký, sample codec-config/rỗng, PTS trước mốc generation, trùng hoặc đi lùi. Trước keyframe đầu tiên không thêm delta vào ring. `clear()` loại bỏ cả pending; khi dừng cần `forceFinalizePending()` trước rồi mới clear.

## 10. Test bắt buộc cho buffer (thuần JVM, không cần máy)
- Cắt tỉa luôn bắt đầu ở keyframe; không vượt `buffer + margin`.
- Snapshot bất biến khi buffer tiếp tục chạy/cắt tỉa.
- 10 marker trong 3 s: id/đường dẫn khác nhau, không mẫu bị mất.
- Mark sát mép cắt tỉa không tạo clip bắt đầu ở non-keyframe.
- Đổi generation: clip không trộn hai generation.
- Mock `ClipMuxer` để test hàng đợi, lỗi, thử lại.
