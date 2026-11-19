# 13 — Chiến lược kiểm thử

## 1. Kim tự tháp
| Tầng | Công cụ | Chạy ở đâu |
|---|---|---|
| Unit (JVM) | JUnit5/JUnit4, MockK, Turbine, Robolectric (hạn chế) | CI/máy dev |
| Instrumentation | AndroidX Test, Room test, `MigrationTestHelper` | Emulator/thiết bị |
| UI (Compose) | `compose-ui-test` | Emulator/thiết bị |
| Thủ công trên máy thật | Checklist | **OnePlus Ace 5** |
| Backend | pytest, httpx, provider giả | CI |

## 2. Cái gì tự động hóa được
| Hạng mục | Tự động? | Ghi chú |
|---|---|---|
| State machine (mọi chuyển hợp lệ/không hợp lệ) | ✅ unit | Bảng chuyển dạng dữ liệu, test tham số hóa |
| RingBuffer (cắt tỉa, keyframe, snapshot bất biến, đổi generation) | ✅ unit | Mẫu giả, không cần codec |
| MarkerPipeline (hàng đợi, marker liên tiếp, lỗi mux) | ✅ unit với fake muxer | |
| Repository/DAO/migration/FTS | ✅ instrumentation | |
| Chọn keyframe, nén ảnh, trộn payload | ✅ unit | |
| Schema/validate JSON báo cáo, map lỗi API | ✅ unit | |
| Phân loại offline/timeout/429 | ✅ unit với client giả | |
| Điều hướng, trạng thái UI, trạng thái quyền | ✅ Compose test | |
| Encoder thật + VirtualDisplay + MediaProjection | ⚠️ một phần | Cần thiết bị; hộp thoại đồng ý khó tự động hóa trên máy thật (có thể dùng UiAutomator trên emulator, **chưa chắc ổn định** — ghi nhận) |
| Overlay hiển thị/kéo thả | ⚠️ | UiAutomator, nhưng nên kiểm thủ công |
| Pin, nhiệt, OxygenOS kill nền | ❌ thủ công | Cần máy thật, thời gian dài |

## 3. Ma trận kịch bản (đề bài mục 10)
| Kịch bản | Cách kiểm | Tiêu chí đạt |
|---|---|---|
| Xử lý quyền (từ chối overlay/thông báo/projection) | UI test + thủ công | Chế độ dự phòng hoạt động; không crash |
| Chụp màn hình/clip | Thủ công (spike) + instrumentation | Clip phát được, dài đúng ±1 s |
| Rolling buffer | Unit + thủ công | Clip bắt đầu ở keyframe; độ dài ≈ cấu hình |
| Vòng đời (đưa app ra nền, màn hình tắt, khóa) | Thủ công | Phiên chuyển trạng thái đúng, không treo |
| Xoay màn hình ×20 | Thủ công + instrumentation | Không rò rỉ, clip chỉ gồm generation hiện tại |
| Chạy nền 60 phút | Thủ công | Service sống hoặc phục hồi đúng cách; ghi thời điểm bị kill |
| Thiếu dung lượng | Thủ công với đầy bộ nhớ giả | Từ chối bắt đầu <200 MB; PAUSED(LOW_STORAGE) |
| Pin/nhiệt | Thủ công, đo `dumpsys batterystats`/thermal | Báo cáo số đo thực; không tắt đột ngột |
| 10 marker trong 3 s | Unit + thủ công | 10 thư mục riêng, không ghi đè |
| Gián đoạn ghi (khóa màn hình, cuộc gọi, ghi màn hình khác) | Thủ công | Flush marker pending; PAUSED(PROJECTION_LOST) |
| AI timeout/lỗi/schema sai | Unit + backend giả | Retry đúng, soạn tay vẫn được, không mất dữ liệu |
| Offline | Instrumentation + thủ công (máy bay) | Yêu cầu vào hàng đợi, gửi khi có mạng |
| Crash app khi đang ghi | Thủ công (kill process) | Mở lại: phiên FAILED(PROCESS_DIED), marker CLIP_LOST, ghi chú giữ nguyên |
| Backend | pytest | Không gọi API thật |

## 4. Trọng tâm OnePlus Ace 5 / OxygenOS 16
Danh sách kiểm tra thủ công, chạy trước mỗi phát hành nội bộ:
1. Cấp từng quyền theo onboarding; xác nhận đúng màn hình cài đặt của OxygenOS.
2. Quay 60 phút với màn hình sáng; sau đó 60 phút với tắt màn hình (xem projection có dừng). Ghi thời điểm dừng (nếu có).
3. Bật chế độ tiết kiệm pin: service có bị kill không?
4. Chạy cùng game/video nặng: FPS, nhiệt, `thermal status`.
5. Overlay trên các app chuẩn (trình duyệt, một app ngân hàng test với `FLAG_SECURE` ⇒ vùng đen đúng).
6. Chọn "chia sẻ một app" vs "toàn màn hình" (V2 trong `06` §9).
7. Gesture/điều hướng hệ thống: bong bóng không chặn gesture cạnh.
8. Notification action và Quick Settings tile hoạt động khi overlay bị tắt.
Ghi kết quả có ngày, build, bản OxygenOS.

## 5. Chỉ tiêu độ phủ và chất lượng
- Logic quan trọng (state machine, buffer, pipeline, validate) ≥ 80% dòng; phần còn lại không áp ngưỡng cứng.
- Lint không lỗi mới; `detekt`/`ktlint` (nếu thêm, ghi ADR).
- Không flaky: test phụ thuộc thời gian dùng `Clock`/`TestDispatcher` giả.

## 6. Đóng gói bằng chứng kiểm thử
Mỗi task có phần "Test evidence" trong PR: lệnh đã chạy, kết quả thật, thiết bị/OS, ảnh/clip khi cần. Không ghi "đã test" nếu chưa chạy (`AGENTS.md` #15).
