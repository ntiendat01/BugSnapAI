# 12 — Bảo mật, quyền riêng tư, tuân thủ nền tảng

BugSnap AI nhìn thấy nội dung của app khác ⇒ quyền riêng tư là yêu cầu cốt lõi, không phải tính năng phụ.

## 1. Nguyên tắc
1. Đồng ý rõ ràng, từng phiên. 2. Luôn có chỉ báo đang ghi. 3. Start/Stop thủ công. 4. Thu thập tối thiểu. 5. **Local-first**. 6. Không upload nếu chưa duyệt. 7. Không vượt cơ chế bảo vệ của Android.

## 2. Mô hình mối đe dọa (rút gọn)
| Tài sản | Mối đe dọa | Biện pháp |
|---|---|---|
| Clip/ảnh chứa dữ liệu cá nhân của AUT (tài khoản test, OTP, thông tin thẻ test) | Rò rỉ qua share/backup/log | Ảnh/index lưu riêng, tắt backup của app; video mới lưu DCIM công khai theo yêu cầu chủ dự án (ADR-21), mở qua URI MediaStore; không log nội dung |
| Khóa API AI | Lộ trong APK | Khóa chỉ ở backend; app chỉ giữ bearer token theo thiết bị trong Keystore |
| Token backend | Đánh cắp | Mã hóa bằng Keystore, thu hồi được, TLS |
| Nội dung gửi AI | Gửi nhầm dữ liệu nhạy cảm | Màn duyệt bắt buộc, chọn từng ảnh, redaction (V2) |
| Prompt injection qua nội dung AUT | Text trong ảnh điều khiển AI | Prompt coi nội dung ảnh là dữ liệu; đầu ra bị ràng buộc schema; không có hành động tự động nào dựa trên đầu ra |
| Dịch vụ bị lạm dụng | Spam gọi backend | Rate limit, trần ngân sách |

## 3. Đồng ý và chỉ báo
- Mỗi phiên: hộp thoại MediaProjection của hệ thống (đồng ý thật sự) + màn giải thích trước đó.
- Chỉ báo: notification liên tục, chip hệ thống, bong bóng đổi màu. Không có ghi ngầm.
- Cảnh báo bên cạnh nút bắt đầu: "Chỉ ghi ứng dụng/dữ liệu bạn có quyền kiểm thử. Không dùng dữ liệu thật của người dùng cuối."

## 4. Lưu trữ và xóa
- Ảnh/index/dữ liệu phiên ở bộ nhớ riêng của app, `android:allowBackup="false"`. Video mới của spike công bố vào `DCIM/BugSnap` theo yêu cầu chủ dự án; video hiển thị trong Thư viện, có thể còn sau khi gỡ app và chịu cài đặt sao lưu Thư viện của thiết bị. Clip cũ chưa tự chuyển. Xem [hợp đồng lưu DCIM](../../../DCIM_VIDEO_STORAGE.md).
- Retention mặc định 14 ngày cho clip (xem `09`); xóa phiên xóa file thật; "Xóa toàn bộ dữ liệu" trong Settings.
- Xóa an toàn ở mức app (không đảm bảo xóa vật lý trên flash); ghi rõ giới hạn này.
- Ghi file tạm có tên không chứa thông tin nhạy cảm.

## 5. Redaction (che dữ liệu nhạy cảm)
- MVP: **duyệt thủ công** — tester bỏ chọn ảnh nhạy cảm; cảnh báo nếu AUT thuộc nhóm tài chính/đăng nhập do tester đánh dấu.
- V2: công cụ che vùng (hộp đen/blur) trên ảnh trước khi gửi/xuất; ảnh gốc giữ nguyên cục bộ.
- Không cố nhận diện PII tự động trong MVP (không đáng tin, tốn chi phí).

## 6. Upload AI
- Chỉ sau màn "Chọn bằng chứng gửi AI". Hiển thị chính xác thứ sẽ gửi.
- Backend không lưu ảnh/nội dung; không dùng cho huấn luyện của chúng ta. Chính sách giữ dữ liệu của nhà cung cấp AI do chủ dự án kiểm tra và ghi vào `16`.
- Lỗi mạng: không gửi lại tự động nếu người dùng chưa duyệt; hàng đợi chỉ chứa yêu cầu đã duyệt.

## 7. Bảo mật ứng dụng
- Không xuất component không cần thiết (`exported=false`); `CaptureService` chỉ nhận lệnh từ chính app (intent có kiểm tra/ `PendingIntent` nội bộ).
- Intent nút thông báo/tile dùng `PendingIntent.FLAG_IMMUTABLE`.
- Chặn screenshot ở các màn hiển thị token/cài đặt nhạy cảm bằng `FLAG_SECURE` của **chính app mình** (hợp lệ).
- Bản release: tắt log chi tiết; R8/obfuscation; không `debuggable`.
- Network: HTTPS bắt buộc, `usesCleartextTraffic=false` (trừ cấu hình debug cho LAN, ghi rõ).
- Phụ thuộc: ít, cố định phiên bản, kiểm tra cảnh báo bảo mật.

## 8. Chính sách Google Play (nhận định cần đối chiếu chính sách hiện hành trước khi nộp)
| Hạng mục | Rủi ro |
|---|---|
| MediaProjection / FGS `mediaProjection` | Phải khai báo mục đích trên Play Console, có thể yêu cầu video minh họa; phải hiển thị rõ cho người dùng |
| `SYSTEM_ALERT_WINDOW` | Được phép với lý do chính đáng, nhưng bị xem xét |
| AccessibilityService | **Rủi ro cao**: chỉ dành cho hỗ trợ tiếp cận; công cụ QA khó được chấp nhận. Không dùng ở bản Play |
| Dữ liệu người dùng | Cần chính sách quyền riêng tư, khai báo Data safety (ảnh/video/âm thanh nếu có) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Bị hạn chế trên Play; dùng hướng dẫn thủ công thay vì xin quyền trực tiếp cho bản Play |
**Khuyến nghị MVP: phân phối APK riêng** cho đội QA, có thể mở rộng lên Play sau khi bỏ AccessibilityService và hoàn thiện khai báo.

## 9. Việc không bao giờ làm
Vượt `FLAG_SECURE`, ghi lén, tự khởi động ghi không có đồng ý, dùng root/hidden API để lấy quyền, gửi dữ liệu khi chưa duyệt, tự submit bug.
