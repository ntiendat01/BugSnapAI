# 16 — Quyết định kỹ thuật, rà soát, đánh giá sẵn sàng

## A. Nhật ký quyết định (ADR)
| ID | Quyết định | Lý do | Đánh đổi |
|---|---|---|---|
| ADR-01 | Một module `:app` ở MVP | Ít cấu hình, build nhanh, đủ rõ ràng nhờ package + quy tắc phụ thuộc | Tách module sau tốn công; xem điều kiện ở `05` |
| ADR-02 | Rolling buffer = **ring buffer mẫu H.264 trong RAM**, mux khi Mark | Không khoảng hở, đơn giản, cắt chính xác | Mất buffer khi process chết (đã giảm nhẹ bằng marker PENDING) |
| ADR-03 | **Không dùng ImageReader** ở MVP; ảnh trích từ clip | Hạn chế một VirtualDisplay/token (Android 14+) | Ảnh có độ phân giải = video và có thể chứa bong bóng; phải xác minh V1 |
| ADR-04 | Pre-roll cấu hình + **post-roll mặc định 5 s** | Bug thường lộ sau thao tác | Tốn thêm RAM/độ trễ |
| ADR-05 | Một `CaptureService` FGS `mediaProjection`, `START_NOT_STICKY` | Token một lần, không thể tự khởi động lại | Mất quyền ⇒ phải hỏi lại |
| ADR-06 | Chế độ dự phòng: notification action + QS tile | Overlay có thể bị từ chối/không ổn trên OEM | Thêm một đường vào Mark Bug |
| ADR-07 | AI = **vision + ≤6 keyframe**; có chế độ text-only | Cân bằng chất lượng/chi phí; không phải model nào cũng có vision | Có thể bỏ lỡ ngữ cảnh chuyển động |
| ADR-08 | Backend FastAPI mỏng, stateless, không lưu nội dung | Giữ khóa AI ngoài APK | Cần hạ tầng nhỏ; phương án BYO key trực tiếp ở app chỉ cho dev đơn lẻ |
| ADR-09 | **Không AccessibilityService, không MCP** ở MVP/V2 | Rủi ro riêng tư/Play; MCP không thay API capture | Không có metadata cấu trúc UI |
| ADR-10 | Room + FTS4; file store riêng của app; DataStore cho cài đặt | Chuẩn, đủ dùng | — |
| ADR-11 | Phân phối APK riêng ở MVP | Tránh rào cản chính sách Play | Cập nhật thủ công |
| ADR-12 | `minSdk 29`, `targetSdk 36`, `compileSdk 37` | FGS type mediaProjection; thiết bị chính Android 16. T-001: Compose 1.12+ (BOM 2026.09.00) bắt buộc compileSdk 37 | `targetSdk` vẫn 36; bỏ máy dưới API 29 |
| ADR-13 | Clip dừng tại ranh giới generation khi xoay màn hình | MP4 không đổi kích thước giữa chừng | Clip có thể ngắn hơn |
| ADR-14 | AI trả `Field{value,source,confidence,needs_confirmation}` | Tách quan sát/suy đoán, ép tester xác nhận | Schema phức tạp hơn |
| ADR-15 | Thêm **Phase 0 spike** | Giảm rủi ro nền tảng sớm | Chậm hiển thị tiến độ UI |
| ADR-16 | Trần rolling buffer / pre-roll = **30 s** (tùy chọn 15/30, mặc định 30; bỏ 60 s) | Chủ dự án giới hạn độ dài video giữ trước Mark Bug | Clip xuất ra = pre-roll + post-roll nên có thể dài hơn 30 s (tối đa 30+10 = 40 s) |
| ADR-17 | Khai báo trực tiếp `kotlinx-coroutines` 1.11.0 (core/android/test) | Đã có trong stack `05`; cần dependency trực tiếp cho qualifier dispatcher (`T-002`) | — |

## B. Kết quả spike (điền sau `T-010`)
Mã spike nằm trên nhánh `spike/capture` (commit T-010). **Chưa chạy trên OnePlus Ace 5 — không điền kết quả giả.** Quy trình từng bước, lệnh logcat và cách lấy clip: `docs/SPIKE_TESTING.md`.

Ghi chú build hiện tại: `versionName 0.1.0-t010`, pre-roll tối đa 30 s, post-roll 5 s, encode cạnh dài 1280 / 30 fps / 4 Mbps / I-frame 1 s.

| # | Câu hỏi (`06` §9) | Kết quả | Ngày/Build/OS |
|---|---|---|---|
| V1 | Token dùng lại có lỗi `SecurityException` ở Android 16? | _chưa kiểm chứng_ | |
| V2 | Chọn "một app" thì overlay có vào video không? AUT ra nền thì sao? | _chưa kiểm chứng_ | |
| V3 | Quay toàn màn hình: bong bóng/huy hiệu có hiện trong clip? | _chưa kiểm chứng_ | |
| V4 | Màn hình tĩnh có sinh keyframe định kỳ với tham số đã chọn? | _chưa kiểm chứng_ | |
| V5 | Khóa màn hình có gọi `onStop`? Mở khóa có tự phục hồi? | _chưa kiểm chứng_ | |
| V6 | OxygenOS có kill service sau bao lâu khi tắt màn hình/pin yếu? | _chưa kiểm chứng_ | |
| V7 | Android 15+ ẩn nội dung thông báo trong clip? | _chưa kiểm chứng_ | |
| V8 | Độ trễ Mark → clip xong; RAM thực tế | _chưa kiểm chứng_ | |
| V9 | Xoay màn hình: `setSurface` + encoder mới hoạt động không rò rỉ? | _chưa kiểm chứng_ | |

## C. Rà soát nhất quán giữa các tài liệu
| # | Vấn đề đã kiểm | Kết quả |
|---|---|---|
| 1 | Trạng thái phiên: `01`, `03`, `05`, `09`, `11` | Khớp 7 trạng thái. T-003: tách `failureReason` khỏi `pauseReason`; `PROCESS_DIED` là `failureReason` (theo `05`), không nhét vào `pauseReason` như bản `09` trước đây |
| 2 | ImageReader nhắc trong đề bài vs thiết kế | Loại bỏ có chủ đích (ADR-03); `02`, `06`, `16` nhất quán |
| 3 | Post-roll: `03` (0/3/5/10), `07` (mặc định 5), `08` dùng `+postRoll` | Khớp |
| 4 | RAM: `03` NFR-4 (≤80 MB) vs `07` (≈18 MB ở 30 s + tối đa 3 snapshot đang mux) | Khớp, còn phải đo thực |
| 5 | Endpoint/field AI: `08` payload/schema vs `10` | Khớp (`schema_version 1.0`) |
| 6 | `report.status` ở `09` vs luồng ở `04`/`11` | Khớp: DRAFT, AI_QUEUED, AI_GENERATED, NEEDS_REVIEW, COMPLETED |
| 7 | Quyền: `05` §9 vs `04` onboarding vs `12` | Khớp; `RECORD_AUDIO` chỉ V2 |
| 8 | Nút Stop trên bong bóng: `11` (nhấn giữ/xác nhận) vs FR-B2 | Khớp |
| 9 | Task ID tham chiếu ở `README`/`14` (T-001, T-010) | Khớp với `15` |
**Mâu thuẫn còn mở / rủi ro tài liệu:**
- `06` §6 và V2/V3 (bong bóng có vào clip không) chưa biết ⇒ có thể đổi hướng dẫn UI sau spike.
- Giả định "một VirtualDisplay mỗi token" cần xác nhận trên Android 16 thật (V1). Nếu sai, có thể dùng ImageReader song song — chỉ chỉnh `06`/ADR-03.
- Số liệu về chính sách Google Play và OxygenOS là **nhận định**, chưa đối chiếu nguồn chính thức trong phiên làm việc này.
- Chưa đo chi phí AI; chưa chọn model/nhà cung cấp cụ thể.

## D. Đánh giá sẵn sàng triển khai
| Hạng mục | Đánh giá |
|---|---|
| **Khả thi kỹ thuật** | Cao cho lõi (overlay + MediaProjection + ring buffer, cùng thiết bị, không root). Điểm chưa chắc: hành vi OxygenOS và chi tiết overlay/clip |
| **Rủi ro chính** | (1) OxygenOS kill nền/ngừng projection khi khóa màn hình; (2) RAM/nhiệt khi quay dài; (3) bong bóng lọt vào clip; (4) chất lượng/chi phí AI; (5) riêng tư dữ liệu AUT |
| **MVP đề xuất** | Phase 0+1+2: phiên, bong bóng + dự phòng, ring buffer 15/30 s (mặc định 30, trần 30 s), Mark Bug (clip+ảnh+ghi chú), history, library, AI vision ≤6 ảnh duyệt tay, xuất Markdown/clipboard/Share |
| **Độ tin cậy kiến trúc** | Trung bình–cao cho thiết kế; **chưa kiểm chứng trên thiết bị** ⇒ chưa nên coi là chắc chắn |
| **Câu hỏi còn mở (cần chủ dự án)** | Nhà cung cấp/model AI và chính sách giữ dữ liệu của họ? Backend chạy ở đâu? Có cần phân phối Play không? Có AUT nào chứa dữ liệu nhạy cảm đặc biệt? Chấp nhận bong bóng xuất hiện trong clip? |
| **Task đầu tiên** | `T-001` scaffold, rồi **`T-010` spike** (ưu tiên cao nhất) |

## E. Xác nhận yêu cầu trung tâm
Đã kiểm tra: mọi luồng (`04`), kiến trúc (`05`), capture (`06`) và test (`13`) đều chạy **trên cùng một thiết bị Android, không root, không máy thứ hai, không ADB lúc chạy thật**. ADB chỉ được nhắc cho giai đoạn phát triển/spike. ✅

## F. Thay đổi so với đề bài (cần chủ dự án biết)
1. Bỏ ImageReader ở MVP. 2. Thêm Phase 0. 3. Thêm `pauseReason` và `CLIP_LOST/FAILED`. 4. Thêm chế độ dự phòng Mark Bug khi không có overlay. 5. Không dùng AccessibilityService/MCP trước V3. 6. Trần rolling buffer / pre-roll = 30 s (bỏ tùy chọn 60 s; mặc định 30 s); clip = pre-roll + post-roll nên có thể > 30 s.
