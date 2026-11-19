# BugSnap AI

Trợ lý kiểm thử Android chạy **cùng thiết bị** với ứng dụng đang test (AUT): bong bóng nổi để đánh dấu bug, bộ đệm quay màn hình cuộn (rolling buffer) giữ lại tối đa 30 giây trước khi bug xảy ra (15 hoặc 30 s, mặc định 30), và AI soạn bản nháp bug report để tester duyệt. App không tự nhận diện bug và không ghi đĩa cho đến khi tester bấm Mark Bug.

> Trạng thái: **chỉ có tài liệu đặc tả, chưa có code ứng dụng.** Gói này dành cho coding agent triển khai sau.

## Ràng buộc cốt lõi (không được phá vỡ)
Tester chạy BugSnap AI và test app khác trên **cùng một thiết bị Android, không root, không cần máy thứ hai, không cần ADB lúc chạy thật.**

## Thứ tự đọc
| # | Tài liệu | Nội dung |
|---|---|---|
| 1 | `docs/02_FEASIBILITY_ANALYSIS.md` | Kết luận khả thi, giới hạn nền tảng, so sánh phương án A/B/C/D |
| 2 | `docs/16_TECHNICAL_DECISIONS.md` | ADR, rà soát nhất quán, đánh giá sẵn sàng triển khai |
| 3 | `docs/01_PRODUCT_OVERVIEW.md`, `03_PRODUCT_REQUIREMENTS.md`, `04_USER_WORKFLOWS.md` | Sản phẩm, yêu cầu, luồng |
| 4 | `docs/05`–`10` | Kiến trúc Android, capture, rolling buffer, AI, DB, backend |
| 5 | `docs/11`, `12`, `13` | UI/UX, bảo mật, kiểm thử |
| 6 | `docs/14`, `15` | Lộ trình và danh sách task |
| 7 | `AGENTS.md` | Luật bắt buộc cho coding agent |

## Task đầu tiên đề xuất
`T-001` (khởi tạo project) rồi **`T-010` spike: MediaProjection + MediaCodec ring buffer trên OnePlus Ace 5**. Spike này kiểm chứng rủi ro lớn nhất trước khi làm UI.

## Cấu trúc repo dự kiến
```text
BugSnapAI/
├── AGENTS.md
├── README.md
├── docs/
├── app/          # Android (một module :app giai đoạn MVP)
└── backend/      # FastAPI (Phase 2)
```
