# Các việc chưa hoàn tất

Cập nhật: 2026-10-03. Đây là trạng thái khi dừng để xuất danh sách công việc. Không đưa API key vào tài liệu này.

## 1. Đồng bộ màu theme trong toàn bộ app — chưa triển khai

- [ ] Rà toàn bộ màu giao diện, thay các màu cố định bằng vai trò màu tương ứng trong `MaterialTheme.colorScheme`.
- [ ] Sửa các chỗ đã phát hiện: màu nền/chữ trong `MediaPreview.kt`, icon phát media trong `AttachmentPreview.kt`, màu mẫu và dấu chọn trong `SettingsScreen.kt`.
- [ ] Đưa màu xem trước của các preset về một nguồn chung trong `ui/theme/Theme.kt`; màn hình Settings không tự khai báo màu riêng.
- [ ] Rà các vai trò màu chưa được khai báo trong preset, tránh dùng màu mặc định của Material không phù hợp với preset đang chọn.
- [ ] Kiểm tra màu của dialog, bottom sheet, thanh hệ thống, nội dung media và các màn hình Settings ở chế độ sáng/tối.

## 2. Preset trắng/xám/đen — chưa triển khai

- [ ] Thêm preset monochrome cho cả chế độ sáng và tối, bao phủ các vai trò màu của theme.
- [ ] Thêm lựa chọn vào Appearance, bố trí các preset đủ diện tích chạm và có trạng thái chọn rõ ràng.
- [ ] Sửa cách áp dụng AMOLED: hiện tại nó chọn một bảng màu riêng và bỏ qua preset đã chọn; cần giữ màu của preset khi chuyển nền sang đen.
- [ ] Kiểm tra độ tương phản chữ, icon, viền, trạng thái chọn và tương tác với Dynamic Color/AMOLED.

## 3. MCP preset Content API — chưa triển khai

- [ ] Thêm preset tên `Content API`, endpoint `https://serverweb.serv00.net/mcp`, cho phép người dùng xoá.
- [ ] Không hiển thị URL mặc định trong giao diện; có thể lưu trường URL trống và phân giải endpoint khi kết nối.
- [ ] Khi người dùng thêm MCP tên `Content API`, `contentapi` hoặc `content-api`, không phân biệt hoa/thường, tự dùng endpoint mặc định nếu URL chưa được nhập.
- [ ] Giữ ưu tiên URL do người dùng nhập, nếu có.
- [ ] Cập nhật kiểm tra hợp lệ trong editor và mọi đường kết nối/khám phá/gọi tool để chấp nhận URL mặc định ẩn.
- [ ] Bổ sung cơ chế khởi tạo preset một lần cho cả cài mới và người dùng hiện có. Không tự thêm lại sau khi người dùng xoá.
- [ ] Kiểm thử nhận diện tên, URL tuỳ chỉnh, khởi tạo một lần và hành vi xoá.

## 4. Test provider bằng ADB — chưa hoàn tất kiểm thử trên app

Yêu cầu: provider và API key đã cung cấp chỉ để test, không được đưa vào mã production, preset hoặc cấu hình mặc định. API key được giữ trong tệp tạm riêng, không ghi vào repository.

Đã làm:

- Gọi API trực tiếp để lấy danh sách: nhận được 5 model và metadata context window.
- Gọi completion trực tiếp thành công để kiểm tra kết nối và khả năng tóm tắt.
- Viết `HelixLiveTest.kt` để nhập cấu hình, lấy model và kiểm thử các luồng trong app qua instrumentation/ADB; bài test chỉ chạy khi được bật rõ ràng.

Còn lại:

- [ ] Chạy thành công toàn bộ bài test trên thiết bị: nhập provider, lưu model và metadata, chat, sinh tiêu đề, summary thủ công, chat tiếp sau summary, gợi ý gần đầy và summary tự động.
- [ ] Xác nhận kết quả thực tế cho từng luồng; chưa được coi gọi API trực tiếp là đã kiểm thử luồng trong app.
- [ ] Điều tra lần chạy instrumentation bị `Process crashed` và các lần app/test package không còn trên thiết bị. Chưa xác định nguyên nhân; chưa có kết quả end-to-end đạt.
- [ ] Cài bản APK cuối cùng sau khi hoàn tất thay đổi và kiểm thử.
- [ ] Xoá tệp chứa credential tạm trên máy phát triển và thiết bị khi kết thúc kiểm thử; không in key ra log hoặc tài liệu.

## 5. Context window và summary tự động — đã có code, còn xác minh trên thiết bị

Đã triển khai:

- Context window trong cấu hình model; đọc metadata API, dùng mặc định 128.000 token khi thiếu dữ liệu.
- Hiển thị và chỉnh sửa context window trong UI model.
- Gợi ý summary từ 90%, tự summary từ 99%; tính context còn hiệu lực sau summary, xử lý tin nhắn đang chờ và chia nhỏ nội dung summary theo cửa sổ của model hỗ trợ.
- Năm unit test về metadata, tương thích dữ liệu cũ, ngưỡng, context sau summary và chia đoạn đã đạt. Build và lint đã đạt trong lần kiểm tra trước.

Còn lại:

- [ ] Xác minh luồng UI và API thực tế bằng bài test ở mục 4, gồm gợi ý 90% và tự summary 99%.
- [ ] Kiểm tra summary giữ đúng thông tin để chat tiếp, và không tạo vòng lặp summary khi context vẫn cao.
- [ ] Rà giới hạn khi một tin nhắn có reasoning/tool payload lớn: việc chia đoạn hiện tập trung vào text, cần xác minh payload tổng không vượt cửa sổ model summary.
- [ ] Ghi rõ cách tính token hiện là ước lượng, chưa phải tokenizer chính xác của từng model; đánh giá sai số với dữ liệu thực tế.

## 6. Kiểm tra cuối và cập nhật tiến độ

- [ ] Sau các chỉnh sửa còn lại, chạy build, unit test phù hợp, lint và kiểm tra diff; chạy kiểm thử thiết bị cho các luồng bị ảnh hưởng.
- [ ] Rà mã production để chắc chắn không có provider test hoặc API key bị hardcode.
- [ ] Cập nhật `WORK_PROGRESS.md` với kết quả thực tế. Một số checkbox cũ, như phần Provider editor cleanup, chưa phản ánh các lượt kiểm tra sau đó; cần đối chiếu trước khi đánh dấu.

Các phần bottom sheet/safe area, kéo provider vào/ra folder, General Settings/About, nút thu gọn/mở rộng Model Picker và xử lý hai mục Storage đã được hoàn thành trong các lượt trước; không đưa lại vào danh sách chưa triển khai.
