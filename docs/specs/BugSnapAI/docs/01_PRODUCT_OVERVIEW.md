# 01 — Tổng quan sản phẩm

## 1. Vấn đề
Manual tester phải: tái hiện bug, nhớ các bước, quay màn hình *sau khi* bug đã xảy ra (thường là quá muộn), rồi viết report thủ công. Chuyển qua lại giữa app ghi chú và AUT làm gián đoạn luồng test.

## 2. Giải pháp
BugSnap AI chạy nền cùng thiết bị, hiện **bong bóng nổi**. Khi thấy bug, tester bấm **Mark Bug**; app giữ lại **N giây trước đó** (rolling buffer) + ảnh chụp + metadata. Cuối phiên, AI soạn **bản nháp** report; tester duyệt rồi mới lưu/xuất.

## 3. Người dùng
Manual QA/QC, mobile tester, QA engineer. Thiết bị chính: OnePlus Ace 5 / OxygenOS 16 (Android 16).

## 4. Nguyên tắc sản phẩm
1. **Cùng thiết bị**, không root, không ADB lúc chạy.
2. **Tester là người chịu trách nhiệm**: AI chỉ đề xuất, không tự gửi.
3. **Local-first**: bằng chứng ở máy; chỉ upload thứ tester đã duyệt.
4. **Đơn giản và tin cậy hơn phức tạp**: ưu tiên kiến trúc ít phần chuyển động.
5. **Trung thực với bằng chứng**: tách "quan sát được" và "suy đoán".

## 5. Phạm vi theo phiên bản
| | MVP (Phase 1+2) | V2 (Phase 3) | V3 |
|---|---|---|---|
| Ghi hình | Rolling buffer, Mark Bug, ảnh chụp | Ghi chú giọng nói | Metadata UI (AccessibilityService, tùy chọn) |
| AI | Keyframe + notes → report JSON, duyệt tay | Phát hiện bug tương tự, so sánh ảnh | Agent/MCP nếu có nhu cầu thật |
| Xuất | Clipboard, Markdown, Share Sheet | PDF, chú thích ảnh | Jira REST |
| Khác | Thư viện bug cục bộ | Redaction vùng nhạy cảm | |

## 6. Chỉ số thành công (MVP)
- Mark Bug → clip + ảnh lưu xong < 5 s sau hết post-roll, thành công ≥ 99% trong 50 lần mark liên tiếp trên Ace 5.
- Phiên 60 phút không bị hệ thống giết service (sau khi cấu hình pin OxygenOS).
- Report AI cho 1 bug tốn ≤ ngân sách đặt ra (xem `08`), tester sửa ít hơn viết từ đầu (đo chủ quan qua phản hồi).

## 7. Ngoài phạm vi
Tự động hóa thao tác, ghi lại click, vượt `FLAG_SECURE`, hoạt động trên iOS, tự động tạo ticket không duyệt, cần máy tính.
