# Functional Requirements – APP-08

| ID | Tên | Mô tả / tiêu chí |
|---|---|---|
| FR-01 | Tạo đơn hàng | Tạo Order ID duy nhất và trạng thái `PENDING`. |
| FR-02 | Kiểm tra dữ liệu | Từ chối sản phẩm không hợp lệ, số lượng không dương hoặc dữ liệu bắt buộc thiếu. |
| FR-03 | Giữ hàng | Chỉ giữ hàng khi đủ tồn khả dụng; lưu reservation theo đơn. |
| FR-04 | Từ chối giữ hàng | Nếu không đủ hàng, từ chối reservation và xử lý nhánh thất bại. |
| FR-05 | Thanh toán giả lập | Payment Service xử lý yêu cầu và lưu trạng thái kết quả. |
| FR-06 | Chống thanh toán trùng | Cùng một yêu cầu thanh toán không tạo nhiều giao dịch thu tiền do retry. |
| FR-07 | Giải phóng hàng | Khi thanh toán thất bại sau khi giữ hàng, giải phóng reservation. |
| FR-08 | Hoàn tiền | Nếu tiền đã thu nhưng đơn không thể hoàn tất và chính sách yêu cầu hủy, thực hiện hoàn tiền. |
| FR-09 | Xác nhận đơn | Chỉ xác nhận khi giữ hàng thành công, thanh toán thành công hợp lệ và đơn chưa hết hạn hợp lệ. |
| FR-10 | Gửi thông báo | Gửi thông báo dựa trên sự kiện/trạng thái nghiệp vụ đã được xác nhận. |
| FR-11 | Retry | Retry lỗi tạm thời theo giới hạn số lần và backoff. |
| FR-12 | Compensation | Điều phối, thực hiện và theo dõi hành động bù trừ. |
| FR-13 | Khôi phục Saga | Tiếp tục Saga chưa hoàn tất sau khi service khởi động lại. |
| FR-14 | Theo dõi và đối soát | Lưu Order ID, Saga ID, Payment ID, Reservation ID và trạng thái từng bước. |
| FR-15 | Xử lý hết hạn tự động | Order Service gán `expireAt`; TTL Worker phát hiện đơn quá hạn, xác minh trạng thái thanh toán, giải phóng reservation nếu cần, chốt `EXPIRED` khi đủ điều kiện và phát sự kiện `OrderExpired`. |

## Đặc tả FR-15 – Xử lý hết hạn tự động

### Tiền điều kiện
- Đơn có `expireAt` hợp lệ.
- Đơn chưa kết thúc theo trạng thái nghiệp vụ.
- Worker hoặc cơ chế khôi phục có thể tìm các đơn quá hạn chưa xử lý.

### Luồng chính
1. Worker tìm đơn chưa kết thúc với `currentTime >= expireAt`.
2. Claim đơn bằng cập nhật có điều kiện/khóa phù hợp để tránh nhiều worker xử lý đồng thời.
3. Order Service xác minh trạng thái đơn và payment.
4. Nếu payment thành công hợp lệ trước khi nhánh hết hạn được chốt, tiếp tục luồng xác nhận nếu các điều kiện còn lại thỏa mãn.
5. Nếu payment thất bại hoặc chưa thành công và kết quả đã rõ, bắt đầu xử lý hết hạn.
6. Nếu có reservation, yêu cầu Inventory Service giải phóng reservation.
7. Khi điều kiện kết thúc được đáp ứng, ghi `EXPIRED` và `OrderExpired` vào Outbox.
8. Notification Service nhận sự kiện và gửi thông báo.

### Ngoại lệ
- Payment `UNKNOWN`: tiếp tục truy vấn/đối soát, không coi mặc định là thất bại.
- Inventory không phản hồi: retry/truy vấn reservation; không giả định đã giải phóng.
- Compensation thất bại: lưu trạng thái chưa hoàn tất, retry và cảnh báo.
- Worker ngừng hoạt động: khi phục hồi, quét lại các đơn quá hạn.
