# AGENTS.md — Luật bắt buộc cho coding agent

Đọc `README.md` và `docs/16_TECHNICAL_DECISIONS.md` trước khi làm bất cứ việc gì. Mỗi lần chỉ làm **một task** trong `docs/15_IMPLEMENTATION_TASKS.md`.

## A. Luật bắt buộc (do chủ dự án đặt ra)
1. Không file source nào quá **500 dòng**. Tới ~400 dòng thì tách.
2. Ưu tiên class/file nhỏ, một trách nhiệm.
3. Tuân thủ Kotlin coding conventions và Android Kotlin style guide.
4. Tách UI, business logic, data access.
5. Không god class, không ViewModel phình to (> ~250 dòng thì xem lại).
6. Dùng dependency injection (Hilt) đúng chỗ; không service locator tự chế.
7. Không hardcode secret (API key, token, URL nội bộ). Dùng `local.properties`/BuildConfig cho dev, backend giữ khóa AI.
8. Không dùng API deprecated nếu không có lý do, và phải ghi lý do trong `docs/16`.
9. Xử lý lỗi tường minh (sealed `Result`/`DomainError`); không `catch` rỗng, không nuốt exception.
10. Logic quan trọng phải có test (xem `docs/13`).
11. Tránh dependency không cần thiết; thêm thư viện phải ghi lý do vào ADR.
12. Quyết định kiến trúc lớn phải ghi vào `docs/16_TECHNICAL_DECISIONS.md`.
13. Hành vi đổi thì cập nhật spec liên quan trong cùng PR/commit.
14. Không âm thầm đổi yêu cầu sản phẩm.
15. Không bịa tính năng đã làm hay kết quả test. Báo đúng cái gì đã chạy, cái gì chưa.
16. Build và chạy test mỗi khi môi trường cho phép; ghi lại lệnh và kết quả thật.
17. Không dùng workaround không an toàn để vượt quyền Android (kể cả FLAG_SECURE, root, hack hidden API).
18. Không làm tính năng ngoài scope đã duyệt; nếu cần thì ghi đề xuất vào `docs/16` và hỏi chủ dự án.

## B. Luật bổ sung cho phát triển Android bằng AI
19. **Không giả định hành vi MediaProjection/overlay/foreground service.** Mọi giả định ghi trong `docs/06` mục "Cần xác minh trên thiết bị" phải được kiểm chứng trên OnePlus Ace 5 (OxygenOS 16) trước khi đánh dấu task xong.
20. Không `GlobalScope`. Coroutine gắn vào scope có vòng đời (`viewModelScope`, scope của Service tự quản lý và hủy trong `onDestroy`).
21. Thread: encoder/muxer/IO chạy trên dispatcher riêng; không chặn Main. Không `runBlocking` trên Main.
22. Mọi `MediaCodec`, `MediaMuxer`, `VirtualDisplay`, `ImageReader`, `MediaProjection` phải được release trong `finally`/`onStop` và có test hoặc ghi chú kiểm tra rò rỉ.
23. Foreground service: khai báo `foregroundServiceType="mediaProjection"`, quyền `FOREGROUND_SERVICE_MEDIA_PROJECTION`, gọi `startForeground` **sau khi** nhận kết quả đồng ý từ người dùng. Đăng ký `MediaProjection.Callback` trước `createVirtualDisplay`. Không tái dùng token.
24. Không ghi log nội dung nhạy cảm (ghi chú tester, nội dung ảnh, token). Log có thể tắt ở release.
25. Mọi đường dẫn file bằng API của `Context`/`FileProvider`; không hardcode đường dẫn tuyệt đối; share qua `FileProvider`, không `file://`.
26. Ghi file video/ảnh theo mẫu "ghi `.part` rồi rename atomic"; dọn `.part` mồ côi khi khởi động.
27. Không gửi dữ liệu lên mạng khi người dùng chưa duyệt màn hình "Chọn bằng chứng gửi AI".
28. AI không được tự submit bug; không có code path nào tạo issue ngoài nút xác nhận của người dùng.
29. Chuỗi UI để trong `strings.xml` (tiếng Việt mặc định); không hardcode chuỗi trong Compose.
30. Version dependency dùng version catalog (`libs.versions.toml`). Không dùng `+`/`latest`.
31. Commit nhỏ, thông điệp rõ, một task một nhánh. Không commit file build, keystore, `local.properties`.
32. Khi không chắc về một API: đọc tài liệu chính thức `developer.android.com` thay vì đoán; nếu không truy cập được, ghi "chưa xác minh" vào task.

## C. Quy trình mỗi task
1. Đọc task + tài liệu liên quan. 2. Viết/cập nhật test trước hoặc cùng lúc. 3. Cài đặt. 4. Chạy lint + unit test (+ instrumentation nếu có thiết bị). 5. Cập nhật docs. 6. Báo cáo: đã làm gì, chạy lệnh gì, kết quả thật, điểm còn mở.

## D. Definition of Done chung (áp dụng mọi task)
- Build thành công; lint không lỗi mới. Unit test của task pass.
- Không file > 500 dòng. Không TODO mơ hồ (TODO phải có task ID).
- Không secret trong repo. Tài liệu liên quan đã cập nhật.
- Acceptance criteria của task được kiểm chứng và ghi lại bằng chứng.
