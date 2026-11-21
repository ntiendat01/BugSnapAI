# 18 — Thu gọn bong bóng khi quay màn hình

Ngày 21/11/2026. Bản `0.1.2-t010`, versionCode 3. Điều chỉnh theo ảnh và phản hồi thực của người dùng về việc bong bóng và thẻ kết quả che mất nội dung cần ghi.

## Quy cách mới

| Thành phần | Thiết kế |
|---|---|
| Cửa sổ bong bóng | Cố định 48 × 48 dp, dùng custom View để kích thước độc lập với theme nút của OEM |
| Hình tròn nhìn thấy | Đường kính 36 dp, vòng tiến trình khoảng 38 dp, icon 20 dp |
| Sẵn sàng Mark | Biểu tượng tâm ngắm, bám cạnh màn hình |
| Đã nhận Mark | Rung xác nhận theo thiết lập Android, đổi ngay thành countdown 5 → 0 ngay trong nút |
| Chờ / đang xuất | Vòng tiến trình nằm trong cùng khung bong bóng |
| Lưu thành công | Dấu hoàn tất trong nút khoảng 2 giây, thời gian được điều chỉnh theo thiết lập trợ năng |
| Lỗi / mất quyền | Biểu tượng chấm than. Lý do và hành động phục hồi có trong thông báo và BugSnap |
| Kết thúc quay | Biểu tượng dừng khi những tác vụ còn lại hoàn tất |
| Menu do người dùng mở | Rộng tối đa 168 dp, lưới hai cột, mỗi vùng bấm ít nhất 48 dp. Chọn phía trên hoặc dưới bong bóng theo khoảng trống, nội dung cuộn khi thiếu chiều cao |

Phản hồi tự động luôn ở trong cửa sổ bong bóng. Chi tiết thời lượng, số ảnh, marker ID và hành động Xem clip được cung cấp trong thông báo và màn hình BugSnap. Việc hoàn thành clip cập nhật theo sự kiện xuất file thực của pipeline hiện có.

## Thao tác

- Mark nhanh mặc định bật cho cấu hình chưa được người dùng tùy chỉnh. Lựa chọn đã lưu của người dùng được giữ khi nâng cấp.
- Khi Mark nhanh bật, chạm bong bóng để Mark. Nhấn giữ để mở menu. Trong BugSnap có hướng dẫn ngay cạnh công tắc Mark nhanh.
- Khi Mark nhanh tắt, chạm mở menu gọn, rồi chọn Mark Bug.
- Kéo đổi vị trí. Khi thả, bong bóng bám cạnh gần nhất và lưu vị trí theo hướng máy. Tap, nhấn giữ và kéo được phân biệt bằng GestureDetector của Android.
- Menu có Xem clip gần nhất khi có video, Mở app, Dừng / Xác nhận và Đóng. Mark Bug xuất hiện trong menu khi dùng chế độ chạm mở menu.
- Xem clip mở đúng clip đã lưu. Dừng cần xác nhận trong menu. Menu nhàn rỗi tự thu, thời gian đọc được kéo dài theo cài đặt trợ năng.

## Trợ năng và khả năng ghi hình

Nút có vùng chạm 48 dp và class trợ năng Button. Nhãn hành động và trạng thái được cung cấp cho TalkBack, gồm marker ID và countdown đầy đủ. Hành động nhấn giữ được đặt nhãn Mở điều khiển. Người dùng có thể tắt Mark nhanh để dùng menu qua thao tác chạm.

Các mốc được thể hiện bằng số, vòng tiến trình, dấu hoàn tất và chấm than. Animation tiến trình tôn trọng cài đặt giảm chuyển động. Bản vẽ glyph countdown giữ trong vùng icon, còn nội dung đầy đủ nằm trong thông tin trợ năng.

Bong bóng vẫn chiếm một vùng nhỏ trong màn hình đang test. Khi quay toàn màn hình, cần tiếp tục xác minh V2/V3 trên Ace 5 để biết overlay xuất hiện trong clip thế nào. Vị trí bám cạnh và khả năng kéo giúp tester đặt nút xa vùng bug.

## Phạm vi mã và kiểm tra

`CompactMarkButton` vẽ phản hồi trong nút. `SpikeOverlay` quản lý cửa sổ 48 dp, gesture và menu chủ động mở. Capture engine, post-roll và thuật toán xuất clip tiếp tục cung cấp trạng thái thực cho giao diện.

Bài Android UI kiểm tra kích thước cửa sổ ở từng trạng thái, khả năng chạm Mark và mở menu bằng nhấn giữ. Ảnh kiểm thử được tạo từ View Android thật với dữ liệu marker cố định. Luồng MediaProjection và xuất video thực được kiểm tra bằng `CaptureFlowTest`.

## Kết quả kiểm tra và bàn giao

- Build APK và test APK thành công bằng Gradle 9.6.0 / JDK 17.
- 78 unit test đạt. Ba bài Android UI đạt, gồm nhận Mark qua chạm thật, đo vùng chạm, nhấn giữ mở menu và luồng MediaProjection / xuất video thực.
- Bài overlay chạy lại trên màn ngang 1600 × 720 với fontScale 2.0 đạt. Cửa sổ vẫn đo được 48 × 48 dp. Menu cuộn khi nội dung chữ lớn vượt chiều cao khả dụng.
- Bài overlay chạy lại trên APK cuối ở màn dọc 720 × 1600, density 320, fontScale 1.0 đạt. Kích thước cửa sổ đo được 96 × 96 pixel, tương đương 48 × 48 dp.
- Android lint có 0 lỗi, 28 cảnh báo về resource chưa dùng, plural, dependency và KTX. Đường GestureDetector gọi performClick đã được kiểm chứng qua input touch thật.
- Chữ ký APK v2 đã xác minh. APK debug dùng cùng keystore để nâng cấp từ bản trước.

[APK 0.1.2 compact](../BugSnapAI-0.1.2-compact-debug.apk), khoảng 32 MB. SHA-256 `bc24c2ca897a960a75dc34efa1ae518b80e766ae8d71f59f5e339e67165674d5`.

Ảnh từ cửa sổ Android thật, dùng dữ liệu marker kiểm thử cố định để nhìn rõ từng trạng thái.

- [Sẵn sàng](design/screenshots/compact/portrait/08-compact-ready.png)
- [Đếm ngược trong nút](design/screenshots/compact/portrait/09-compact-countdown.png)
- [Đang lưu](design/screenshots/compact/portrait/10-compact-saving.png)
- [Lưu xong](design/screenshots/compact/portrait/11-compact-saved.png)
- [Menu mở bằng nhấn giữ](design/screenshots/compact/portrait/12-compact-menu.png)
- [Màn ngang, chữ 200%](design/screenshots/compact/landscape-font2/12-compact-menu.png)

Kiểm tra trên OnePlus Ace 5 tiếp tục cần thiết cho gesture, haptic và hành vi overlay của OxygenOS.
