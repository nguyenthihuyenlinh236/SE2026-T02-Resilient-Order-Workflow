# General Use Cases – APP-08

## UC-01 – Place Order

**Tác nhân chính:** End User  
**Thành phần chính:** Order Service

### Tiền điều kiện
- Yêu cầu có dữ liệu hợp lệ.
- Sản phẩm và số lượng được cung cấp.

### Luồng chính
1. Người dùng gửi yêu cầu đặt hàng.
2. Order Service xác thực dữ liệu.
3. Tạo Order ID và lưu `PENDING`.
4. Ghi `createdAt` và `expireAt`.
5. Khởi tạo Saga và yêu cầu giữ hàng.
6. Trả Order ID/trạng thái ban đầu cho người dùng.

### Ngoại lệ
- Dữ liệu không hợp lệ: từ chối yêu cầu.
- Yêu cầu trùng: trả kết quả của yêu cầu đã được nhận diện bằng idempotency key.

## UC-02 – Reserve Inventory

**Tác nhân chính:** Order Service  
**Service phối hợp:** Inventory Service

1. Order Service gửi lệnh giữ hàng với order/reservation ID ổn định.
2. Inventory kiểm tra tồn khả dụng và cập nhật nguyên tử.
3. Nếu đủ hàng, ghi reservation `RESERVED`.
4. Nếu thiếu hàng, từ chối reservation.
5. Nếu timeout, Order Service truy vấn hoặc retry idempotent.

## UC-03 – Process Payment

**Tác nhân chính:** Order Service  
**Service phối hợp:** Payment Service

1. Sau khi giữ hàng thành công, Order Service yêu cầu thanh toán.
2. Payment Service kiểm tra Idempotency Key.
3. Payment xử lý và lưu kết quả.
4. Nếu thành công hợp lệ, Saga tiếp tục xác nhận đơn.
5. Nếu thất bại, kích hoạt compensation.
6. Nếu kết quả `UNKNOWN`, truy vấn/đối soát trước khi quyết định.

## UC-04 – Compensate Failed Order

**Tác nhân chính:** Order Service

1. Phát hiện bước bắt buộc thất bại.
2. Ghi Saga ở trạng thái `COMPENSATING`.
3. Giải phóng reservation nếu đã giữ hàng.
4. Hoàn tiền nếu tiền đã thu và nghiệp vụ yêu cầu.
5. Retry các bước bù trừ chưa hoàn tất.
6. Chỉ chốt trạng thái kết thúc khi điều kiện bù trừ đã đáp ứng.
7. Phát sự kiện thông báo.

## UC-05 – Notify Order Result

**Tác nhân chính:** Notification Service

1. Nhận sự kiện nghiệp vụ từ Outbox/message broker.
2. Kiểm tra event ID/notification ID để chống trùng.
3. Gửi thông báo thành công, thất bại hoặc hết hạn.
4. Lưu kết quả.
5. Retry lỗi tạm thời; không thay đổi trạng thái nghiệp vụ của Order.

## UC-06 – Expire Order / TTL Cleanup

**Tác nhân chính:** TTL Worker / Expiration Scheduler  
**Thành phần phối hợp:** Order, Inventory, Payment, Notification

### Mục tiêu
Tự động xử lý đơn vượt thời hạn thanh toán, tránh đơn `PENDING` treo và giải phóng hàng đã giữ khi đủ điều kiện.

### Tiền điều kiện
- Order tồn tại và có `expireAt`.
- Đơn chưa kết thúc theo trạng thái nghiệp vụ.
- Worker hoạt động hoặc có cơ chế khôi phục đơn quá hạn.

### Kích hoạt
`currentTime >= expireAt`, hoặc service khởi động lại và tìm thấy đơn đã quá hạn chưa xử lý.

### Luồng chính
1. Worker tìm đơn chưa kết thúc đã quá hạn.
2. Worker claim đơn bằng cập nhật có điều kiện/lock.
3. Order Service xác minh trạng thái Order và Payment.
4. Nếu payment thành công hợp lệ trước khi nhánh hết hạn được chốt, tiếp tục luồng xác nhận nếu các điều kiện khác thỏa mãn.
5. Nếu payment chưa thành công và kết quả đã rõ, bắt đầu xử lý hết hạn.
6. Nếu có reservation, yêu cầu Inventory giải phóng.
7. Inventory giải phóng đúng reservation và lưu kết quả.
8. Khi các điều kiện kết thúc được đáp ứng, Order ghi `EXPIRED` và `OrderExpired` vào Outbox.
9. Publisher phát sự kiện.
10. Notification Service gửi thông báo.

### Luồng thay thế / ngoại lệ
- **Payment `UNKNOWN`:** trì hoãn kết luận cuối cùng và tiếp tục đối soát.
- **Inventory timeout:** truy vấn reservation/retry idempotent.
- **Compensation thất bại:** lưu trạng thái chưa hoàn tất, retry và cảnh báo.
- **Hai worker đồng thời:** chỉ một worker claim; worker còn lại đọc trạng thái hiện tại.
- **Worker crash:** khởi động lại và quét lại các đơn quá hạn.
- **Payment đến muộn sau EXPIRED:** không tự mở lại đơn; đối soát và hoàn tiền nếu cần.
- **Notification down:** sự kiện vẫn được lưu bền vững và gửi lại.

### Hậu điều kiện thành công
- Đơn được ghi nhận `EXPIRED` khi đủ điều kiện.
- Reservation cần giải phóng đã được giải phóng.
- `OrderExpired` được ghi vào Outbox.
- Notification có thể được gửi lại sau sự cố.

## Quy tắc chung cho các Use Case
- Mọi command quan trọng phải có định danh ổn định.
- Retry không được lặp tác dụng nghiệp vụ.
- Không coi timeout là bằng chứng thất bại.
- Không cho phép cùng đơn vừa `CONFIRMED` vừa `EXPIRED`.
- Lưu trạng thái trung gian để khôi phục sau sự cố.
