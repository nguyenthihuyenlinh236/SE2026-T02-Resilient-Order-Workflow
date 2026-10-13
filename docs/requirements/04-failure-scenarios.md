# Initial Failure Scenarios – APP-08

| ID | Tình huống | Rủi ro | Xử lý dự kiến |
|---|---|---|---|
| EC-01 | Hết hàng | Không thể tiếp tục đơn | Từ chối reservation, không thanh toán, thông báo thất bại. |
| EC-02 | Payment thất bại sau khi giữ hàng | Hàng bị giữ không cần thiết | Compensation giải phóng reservation. |
| EC-03 | Gateway lỗi/timeout | Không rõ tiền đã thu chưa | Tra cứu giao dịch; không tạo payment mới bằng khóa khác. |
| EC-04 | Inventory timeout | Không rõ reservation đã được tạo chưa | Truy vấn hoặc retry idempotent bằng reservation ID. |
| EC-05 | Payment timeout | Có thể thu trùng hoặc giải phóng quá sớm | Đánh dấu chưa rõ kết quả, xác minh trước khi chốt. |
| EC-06 | Service crash giữa Saga | Quy trình dừng ở trạng thái trung gian | Khôi phục từ dữ liệu bền vững và tiếp tục/bù trừ. |
| EC-07 | Duplicate message | Lặp giữ hàng, thu tiền hoặc thông báo | Idempotent consumer, unique key và lưu trạng thái xử lý. |
| EC-08 | Outbox publisher crash | Sự kiện chưa phát hoặc phát rồi nhưng chưa đánh dấu | Phát lại; consumer chống trùng. |
| EC-09 | Compensation thất bại | Hàng/tiền chưa được hoàn tác | Retry, theo dõi trạng thái, cảnh báo và đối soát. |
| EC-10 | Notification Service down | Khách hàng chưa nhận thông báo | Giữ sự kiện bền vững và retry sau khi phục hồi. |
| EC-11 | TTL Expired / đơn treo quá hạn | Đơn PENDING chiếm reservation vô thời hạn | Worker phát hiện, xác minh payment, giải phóng reservation và chốt EXPIRED khi đủ điều kiện. |

## EC-01 – Out of Stock
1. Order Service tạo đơn `PENDING`.
2. Inventory từ chối giữ hàng do không đủ tồn khả dụng.
3. Order chuyển `FAILED`.
4. Không khởi tạo thanh toán.
5. Phát sự kiện thông báo thất bại.

## EC-02 – Payment Failed
1. Inventory giữ hàng.
2. Payment xác nhận thất bại.
3. Order/Saga chuyển sang `COMPENSATING`.
4. Inventory giải phóng đúng reservation.
5. Khi bù trừ thành công, đơn chuyển `FAILED` và phát sự kiện.

## EC-03/EC-05 – Payment timeout hoặc kết quả chưa rõ
1. Ghi giao dịch với khóa idempotency ổn định.
2. Nếu timeout, trạng thái có thể là `UNKNOWN`.
3. Truy vấn trạng thái giao dịch.
4. Chỉ tiếp tục hoặc compensation sau khi có kết quả đủ tin cậy.
5. Nếu kết quả vẫn chưa rõ, giữ trạng thái chờ đối soát; không mặc định thất bại.

## EC-04 – Inventory timeout
Reservation có thể đã được tạo dù Order Service không nhận phản hồi. Tra cứu bằng reservation ID/order ID trước khi gửi lệnh mới hoặc bắt đầu hết hạn. Mọi retry phải idempotent.

## EC-06 – Service crash
Trạng thái Order/Saga và Outbox phải được lưu bền vững. Khi khởi động lại, tìm các Saga chưa hoàn tất và các đơn đã quá hạn chưa xử lý; truy vấn trạng thái của Inventory/Payment trước khi tiếp tục.

## EC-07/EC-08 – Message duplicate / Outbox crash
Publisher có thể phát lại sự kiện. Consumer phải chống xử lý trùng. Phân biệt `eventId` với khóa định danh tác dụng nghiệp vụ; chỉ chống trùng theo event ID có thể chưa đủ.

## EC-09 – Compensation thất bại
Lưu trạng thái chưa hoàn tất, retry theo backoff, cảnh báo khi vượt ngưỡng và hỗ trợ xử lý thủ công. Không đánh dấu bù trừ thành công nếu chưa có xác nhận.

## EC-10 – Notification Service down
Không đảo ngược trạng thái Order chỉ vì gửi thông báo thất bại. Giữ sự kiện bền vững và retry độc lập.

## EC-11 – TTL Expired

### Ví dụ
- `createdAt = 10:00:00`
- `TTL = 15 phút`
- `expireAt = 10:15:00`
- Order vẫn `PENDING`, Inventory đã giữ 3 sản phẩm, payment chưa thành công và không ở trạng thái `UNKNOWN`.

### Luồng xử lý
1. TTL Worker tìm đơn với `currentTime >= expireAt`.
2. Claim đơn bằng cập nhật có điều kiện/lock để tránh nhiều worker xử lý cùng lúc.
3. Order Service kiểm tra trạng thái Order và Payment.
4. Nếu payment thành công hợp lệ trước khi nhánh hết hạn được chốt, xử lý theo luồng xác nhận.
5. Nếu chưa thanh toán và kết quả đã rõ, kích hoạt compensation.
6. Inventory giải phóng đúng reservation.
7. Order ghi `EXPIRED` và sự kiện `OrderExpired` vào Outbox.
8. Notification Service gửi thông báo.

### Nhánh lỗi
- Worker ngừng hoạt động: quét lại các đơn quá hạn khi phục hồi.
- Hai worker cùng xử lý: chỉ một worker được claim; thao tác còn lại đọc trạng thái hiện tại.
- Payment `UNKNOWN`: đối soát trước khi chốt.
- Inventory không phản hồi: retry/truy vấn reservation.
- Compensation thất bại: giữ trạng thái chưa hoàn tất và cảnh báo.
- Payment thành công đến muộn sau `EXPIRED`: không tự mở lại đơn; đối soát và hoàn tiền nếu cần.
- Sự kiện hết hạn bị phát trùng: consumer idempotent.
