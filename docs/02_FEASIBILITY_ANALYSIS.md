# 02 — Phân tích tính khả thi

> Ghi chú độ tin cậy: mục nào đã đối chiếu tài liệu Android chính thức được đánh dấu **[đã đối chiếu]**; mục dựa trên hiểu biết nền tảng cần kiểm tra thêm đánh dấu **[cần xác minh]**. Mọi mục "cần xác minh" được gom thành checklist ở `06_SCREEN_CAPTURE_ENGINE.md` §9.

## 1. Kết luận
**Khả thi.** Quy trình cốt lõi (bong bóng nổi + MediaProjection + rolling buffer, cùng thiết bị, không root) chạy được trên Android hiện đại. Có 6 ràng buộc phải thiết kế quanh, không phải rào cản chặn:

| # | Giới hạn nền tảng | Hệ quả thiết kế |
|---|---|---|
| L1 | MediaProjection cần **người dùng đồng ý mỗi phiên**; token **dùng một lần** trên Android 14+, không tạo lại `VirtualDisplay` từ token cũ **[đã đối chiếu: phải đăng ký `MediaProjection.Callback`, projection có thể bị dừng khi người dùng dừng, khóa màn hình, hoặc phiên khác bắt đầu]** | Mỗi phiên = một lần cấp quyền. Mất projection ⇒ phiên chuyển PAUSED(reason) và yêu cầu cấp lại |
| L2 | Foreground service type `mediaProjection` + quyền `FOREGROUND_SERVICE_MEDIA_PROJECTION`; bắt buộc khai báo type từ target 34 **[đã đối chiếu]** | Một `CaptureService` foreground, bắt đầu ngay sau khi có kết quả đồng ý |
| L3 | Từ Android 15, app giữ `SYSTEM_ALERT_WINDOW` chỉ được khởi chạy FGS từ nền nếu đang có overlay nhìn thấy **[đã đối chiếu]** | Không khởi động service capture từ nền; chỉ khởi động khi Activity đang hiển thị |
| L4 | Tạo **một** `VirtualDisplay` cho mỗi token (Android 14+) **[cần xác minh trên máy]** | Không dùng ImageReader song song với encoder; ảnh chụp trích từ clip (xem `06`) |
| L5 | Overlay xuất hiện trong video khi quay toàn màn hình; không có API công khai đáng tin để loại trừ **[cần xác minh]** | Bong bóng nhỏ, mờ, bám cạnh; có đường Mark Bug dự phòng (thông báo, Quick Settings tile) |
| L6 | OxygenOS tối ưu pin/đóng app nền mạnh tay **[cần xác minh]** | Hướng dẫn cấu hình pin trong onboarding; ghi nhận service bị kill để phục hồi |

Ngoài ra: **không** bỏ qua `FLAG_SECURE` — màn hình có cờ này sẽ đen trong clip; đó là hành vi đúng, ghi vào tài liệu cho tester.

## 2. Hành vi theo phiên bản Android (liên quan)
| Phiên bản | Điểm đáng chú ý |
|---|---|
| 14 (API 34) | Khai báo type FGS + quyền riêng bắt buộc (target 34+); token MediaProjection một lần; phải đăng ký callback **[đã đối chiếu]** |
| 14 QPR2/15 | Người dùng có thể chọn chia sẻ **một ứng dụng** thay vì toàn màn hình; Android 15 có chip trạng thái và ẩn nội dung thông báo nhạy cảm khi chia sẻ **[cần xác minh]** |
| 15 (API 35) | Hạn chế khởi chạy FGS từ nền với SYSTEM_ALERT_WINDOW; hạn chế `BOOT_COMPLETED` **[đã đối chiếu]** |
| 16 (API 36) | Job nền khởi từ FGS chịu quota runtime **[đã đối chiếu]**; không ảnh hưởng thiết kế vì ta không dùng job cho capture |

## 3. Phương án A/B/C/D (mục 4 của đề bài)
| | A: MediaProjection | B: + AccessibilityService | C: + AI Vision | D: MCP |
|---|---|---|---|---|
| Làm được | Video + ảnh màn hình | Cây view, text, tên màn hình/Activity, sự kiện click/scroll (nếu AUT không chặn) | Hiểu nội dung ảnh/keyframe được chọn | Cho agent AI gọi "tool" (đọc phiên, tạo report) |
| Không làm được | Hiểu nội dung; không thấy `FLAG_SECURE` | Không thấy Canvas/Flutter/game; WebView hạn chế; có thể bị AUT chặn | Không biết hành vi mong đợi; có thể nhầm UI | Không chụp màn hình; **không liên quan** tới API capture |
| Quyền | Đồng ý mỗi phiên + overlay + FGS | Bật thủ công trong Cài đặt Trợ năng, cảnh báo hệ thống | Không thêm (cần mạng) | Không thêm trên máy; cần máy chủ |
| Tin cậy giữa các AUT | Cao | Trung bình–thấp | Trung bình (phụ thuộc chất lượng ảnh) | Không áp dụng |
| Riêng tư | Cao rủi ro (thấy mọi thứ) | **Rất cao** (đọc text mọi app, kể cả mật khẩu field) | Cao rủi ro khi upload | Tùy dữ liệu truyền |
| Chính sách Google Play | Cần khai báo FGS + demo | **Rất rủi ro**: AccessibilityService chỉ cho mục đích hỗ trợ tiếp cận; app QA khó được duyệt **[cần đối chiếu chính sách hiện hành]** | Cần minh bạch dữ liệu | Không đặc thù |
| Pin/hiệu năng | Trung bình (encode liên tục) | Thêm tải sự kiện | Không đáng kể trên máy | Không đáng kể |
| Độ phức tạp | Trung bình | Cao | Thấp–trung bình | Cao, không cần cho MVP |

**MCP có cần không?** Không cho MVP/V2. MCP là giao thức để agent AI gọi tool; nó không thay thế MediaProjection. Backend đã gọi AI qua API với JSON schema là đủ. Chỉ cân nhắc MCP ở V3 nếu muốn cho Claude/IDE agent truy vấn kho bug của tester.

### Khuyến nghị
- **MVP:** A + C (chọn lọc keyframe, tester duyệt ảnh trước khi gửi).
- **V2:** giữ A + C; thêm giọng nói, redaction, so sánh ảnh.
- **V3:** B **tùy chọn, tắt mặc định**, chỉ phân phối APK riêng; D chỉ khi có nhu cầu rõ.

## 4. Phân phối: Google Play vs APK riêng
| | Google Play | APK riêng (khuyến nghị cho MVP) |
|---|---|---|
| MediaProjection + FGS | Phải khai báo mục đích, có thể yêu cầu video demo | Không rào cản |
| Overlay | Cho phép với lý do chính đáng | Không rào cản |
| AccessibilityService | Rủi ro bị từ chối/gỡ | Dùng được, vẫn cần cảnh báo người dùng |
| Cập nhật | Tự động | Cần kênh phân phối nội bộ |
Mọi điểm chính sách Play ở trên là **nhận định cần đối chiếu lại** với chính sách hiện hành trước khi nộp.

## 5. Các giả định sai trong đề bài cần chỉnh
1. "Dùng ImageReader để chụp màn hình khi Mark Bug" — với giới hạn một VirtualDisplay mỗi token, ImageReader song song với encoder không khả thi nếu không qua GL; **thay bằng trích khung hình từ clip**.
2. "Loại bong bóng khỏi video" — chưa có cách công khai đáng tin; mặc định chấp nhận bong bóng xuất hiện, trừ khi chọn chia sẻ một ứng dụng (cần kiểm chứng).
3. "Rolling buffer bằng các file đoạn" — phức tạp, dễ hỏng ranh giới; khuyến nghị **ring buffer dữ liệu đã mã hóa trong RAM** (xem `07`).
4. "Quay lại tự động sau khi mất quyền" — không được; phải hỏi lại người dùng.
