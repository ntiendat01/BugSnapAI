# 03 — Yêu cầu sản phẩm

Ký hiệu: **M** = MVP, **V2**, **V3**. FR = chức năng, NFR = phi chức năng.

## 1. Yêu cầu chức năng
### Phiên kiểm thử
| ID | Yêu cầu | Phase |
|---|---|---|
| FR-S1 | Tạo phiên: tên, AUT (chọn từ danh sách app có launcher), build/version ghi chú, độ dài buffer (15/30/60 s), post-roll (0/3/5/10 s) | M |
| FR-S2 | Bắt đầu: xin quyền overlay, thông báo, MediaProjection; chạy `CaptureService` | M |
| FR-S3 | Pause/Resume/Stop; hiển thị rõ trạng thái đang ghi | M |
| FR-S4 | Xem lại phiên: danh sách marker, bằng chứng | M |
| FR-S5 | Xóa phiên (xóa cả file) | M |
| FR-S6 | Phục hồi sau crash: đánh dấu marker mất clip, giữ ghi chú | M |

### Bong bóng nổi
| ID | Yêu cầu | Phase |
|---|---|---|
| FR-B1 | Kéo thả, bám cạnh, thu nhỏ/mở rộng | M |
| FR-B2 | Hành động: Mark Bug, Pause/Resume, Quick Note, Mở app, Stop | M |
| FR-B3 | Mark Bug một chạm, phản hồi rung + hiệu ứng tối thiểu | M |
| FR-B4 | Nếu thiếu quyền overlay: dùng thông báo có nút Mark Bug và Quick Settings tile | M |
| FR-B5 | Tự mờ khi không dùng | M |

### Ghi hình và marker
| ID | Yêu cầu | Phase |
|---|---|---|
| FR-C1 | Quay màn hình liên tục vào ring buffer RAM | M |
| FR-C2 | Mark Bug lưu clip (pre-roll + post-roll), ảnh chụp, timestamp, metadata thiết bị/AUT | M |
| FR-C3 | Nhiều marker không ghi đè nhau; marker liên tiếp nhanh được xếp hàng | M |
| FR-C4 | Ghi chú văn bản gắn marker (trước hoặc sau) | M |
| FR-C5 | Ghi chú giọng nói | V2 |
| FR-C6 | Chú thích ảnh | V2 |

### AI report
| ID | Yêu cầu | Phase |
|---|---|---|
| FR-A1 | Chọn marker và keyframe gửi AI (mặc định chọn sẵn, tester bỏ chọn được) | M |
| FR-A2 | Sinh report JSON đúng schema, validate | M |
| FR-A3 | Mỗi trường có nguồn: OBSERVED / TESTER_NOTE / INFERRED / UNKNOWN; trường không chắc đánh dấu "cần xác nhận" | M |
| FR-A4 | Tester sửa mọi trường; chỉ khi bấm "Hoàn tất" mới COMPLETED | M |
| FR-A5 | Không bao giờ tự submit | M |
| FR-A6 | Hoạt động offline: bản nháp thủ công, hàng đợi gửi AI khi có mạng | M |
| FR-A7 | Gợi ý test case bổ sung; phát hiện bug tương tự | M / V2 |

### Thư viện & xuất
| ID | Yêu cầu | Phase |
|---|---|---|
| FR-L1 | Tìm kiếm, lọc (trạng thái, phiên, mức độ, ngày), sửa, xóa, xem bằng chứng | M |
| FR-X1 | Copy report vào clipboard; xuất Markdown; Share Sheet (kèm bằng chứng) | M |
| FR-X2 | Xuất PDF | V2 |
| FR-X3 | Jira REST (project, issue type, tạo bug, upload attachment) | V3 |

## 2. Yêu cầu phi chức năng
| ID | Yêu cầu | Mục tiêu đo được |
|---|---|---|
| NFR-1 | Không root, không ADB, một thiết bị | Kiểm tra thủ công trên Ace 5 |
| NFR-2 | Độ trễ Mark Bug → phản hồi UI | < 150 ms |
| NFR-3 | Clip + ảnh sẵn sàng | ≤ post-roll + 5 s |
| NFR-4 | RAM cho buffer | ≤ 80 MB ở cấu hình 60 s mặc định (xem `07`) |
| NFR-5 | Pin | Mức tiêu hao khi quay 1 giờ được đo và công bố; mục tiêu ≤ thấp hơn quay màn hình hệ thống cùng độ phân giải (đo so sánh) |
| NFR-6 | Nhiệt | Không giảm FPS nhiều khi quay 60 phút (đo) |
| NFR-7 | Độ tin cậy | Không mất marker đã lưu DB khi app bị kill |
| NFR-8 | Dung lượng | Cảnh báo khi trống < 500 MB; từ chối bắt đầu khi < 200 MB |
| NFR-9 | Riêng tư | Xem `12` |
| NFR-10 | Ngôn ngữ UI | Tiếng Việt mặc định; thuật ngữ kỹ thuật giữ tiếng Anh |

## 3. Ma trận truy vết (rút gọn)
FR-C1→`06`,`07` · FR-B*→`05`,`11` · FR-A*→`08`,`10` · FR-L1→`09` · FR-X*→`05` · NFR-*→`13`.
