# Acceptance Criteria – APP-08

| ID | Kịch bản | Kết quả mong đợi |
|---|---|---|
| AC-01 | Đủ hàng và thanh toán thành công đúng hạn | Order `CONFIRMED`; sự kiện thành công được ghi nhận. |
| AC-02 | Không đủ hàng | Order thất bại; không khởi tạo payment. |
| AC-03 | Giữ hàng thành công nhưng payment thất bại | Reservation được giải phóng; Order `FAILED`. |
| AC-04 | Gửi lại yêu cầu thanh toán cùng idempotency key | Không tạo giao dịch thu tiền thứ hai. |
| AC-05 | Payment timeout | Không kết luận thất bại trước khi xác minh kết quả. |
| AC-06 | Lệnh giữ hàng bị gửi trùng | Không tạo reservation trùng. |
| AC-07 | Service crash giữa Saga | Saga có thể tiếp tục hoặc compensation sau khi phục hồi. |
| AC-08 | Outbox phát trùng sự kiện | Consumer không lặp tác dụng nghiệp vụ ngoài chính sách. |
| AC-09 | Compensation thất bại tạm thời | Trạng thái chưa hoàn tất được lưu; có retry/cảnh báo. |
| AC-10 | Notification Service ngừng hoạt động | Order giữ nguyên trạng thái đúng; thông báo có thể gửi sau. |
| AC-11 | Đơn quá hạn thanh toán | Khi `currentTime >= expireAt`, nếu payment chưa thành công và không còn kết quả chưa xác định, hệ thống tự động xử lý hết hạn. Nếu có reservation, giải phóng thành công trước khi chốt `EXPIRED`; ghi `OrderExpired` vào Outbox. |

## Chi tiết AC-11 – TTL Expired

### Dữ liệu
- Order ID: `ORDER-1011`
- `createdAt`: 10:00:00
- TTL: 15 phút
- `expireAt`: 10:15:00
- Order: `PENDING`
- Reservation: 3 sản phẩm, `RESERVED`
- Payment: chưa thành công, kết quả đã rõ và không phải `UNKNOWN`

### Bước kiểm thử
1. Tạo đơn và xác minh `expireAt = createdAt + 15 phút`.
2. Giữ 3 sản phẩm thành công.
3. Không thực hiện thanh toán thành công.
4. Chạy TTL Worker tại hoặc sau `expireAt`.
5. Xác minh worker phát hiện đơn.
6. Xác minh Order bắt đầu luồng hết hạn.
7. Xác minh Inventory chuyển reservation sang `RELEASED`.
8. Xác minh Order ghi `EXPIRED`.
9. Xác minh `OrderExpired` được ghi vào Outbox.
10. Gửi lại lệnh expire/release và xác minh tồn kho không bị cộng trả nhiều lần.

### Kết quả đạt
- Đơn không còn `PENDING`.
- Reservation được giải phóng đúng một lần.
- Trạng thái và sự kiện được lưu bền vững.
- Sau worker crash, worker khởi động lại vẫn tìm và xử lý được đơn quá hạn.

### Kiểm thử bổ sung
- Nếu payment là `UNKNOWN`, không chốt hết hạn hoàn tất trước khi đối soát.
- Nếu Inventory Service lỗi, không đánh dấu compensation thành công.
- Nếu payment thành công đến muộn sau `EXPIRED`, không tự động mở lại đơn; xử lý đối soát/hoàn tiền theo chính sách.
- Nếu hai worker cùng quét đơn, chỉ một luồng được quyền khởi tạo xử lý tại một thời điểm.
