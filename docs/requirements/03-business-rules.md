# Business Rules – APP-08

| ID | Quy tắc | Kiểm soát |
|---|---|---|
| BR-01 | Không xác nhận đơn khi thiếu điều kiện | Chỉ xác nhận khi giữ hàng thành công, payment thành công hợp lệ và đơn chưa hết hạn hợp lệ. |
| BR-02 | Không giữ vượt tồn khả dụng | Kiểm tra và cập nhật tồn kho nguyên tử. |
| BR-03 | Không thu tiền hai lần | Dùng Idempotency Key ổn định và ràng buộc duy nhất. |
| BR-04 | Giải phóng hàng khi payment thất bại | Nếu đã giữ hàng và payment thất bại được xác nhận, bắt buộc kích hoạt compensation. |
| BR-05 | Không giải phóng hàng của đơn khác | Reservation phải gắn với Order ID/Reservation ID duy nhất. |
| BR-06 | Không sai thứ tự nghiệp vụ | Chỉ thanh toán sau khi giữ hàng thành công theo workflow đã chọn. |
| BR-07 | Timeout không đồng nghĩa thất bại | Với payment chưa rõ kết quả, truy vấn/đối soát trước khi quyết định compensation. |
| BR-08 | Compensation idempotent | Lệnh giải phóng/hoàn tiền gửi lặp không lặp tác dụng phụ. |
| BR-09 | Không gửi thông báo sai trạng thái | Chỉ phát thông báo từ trạng thái nghiệp vụ đã lưu bền vững. |
| BR-10 | Không bỏ quên Saga | Saga chờ, bù trừ hoặc bù trừ lỗi phải được theo dõi và khôi phục. |
| BR-11 | Không tái sử dụng khóa với payload khác | Cùng khóa nhưng payload khác phải bị từ chối/báo xung đột. |
| BR-12 | Phân biệt trạng thái nghiệp vụ và phát sự kiện | Trạng thái Order, Outbox và Notification là các trạng thái khác nhau. |
| BR-13 | Tự động hủy đơn quá hạn và giải phóng tồn kho | Khi `currentTime >= expireAt`, nếu đơn chưa được xác nhận thanh toán thành công và không còn kết quả thanh toán chưa xác định, hệ thống khởi tạo hết hạn. Nếu có reservation, bắt buộc giải phóng reservation trước khi coi compensation hoàn tất. |

## Chi tiết BR-13 – Order Expiration / TTL

### BR-13.1 – Thời hạn
- Order Service lưu `createdAt` và `expireAt` khi tạo đơn.
- Công thức: `expireAt = createdAt + TTL`.
- TTL mặc định đề xuất: 15 phút.
- Retry tạo đơn không tự động gia hạn TTL.
- Dùng thời gian chuẩn thống nhất; khuyến nghị lưu timestamp UTC.

### BR-13.2 – Điều kiện kích hoạt
Đơn được xem xét hết hạn khi:
- `currentTime >= expireAt`;
- đơn chưa `CONFIRMED` hoặc kết thúc hợp lệ;
- không có tiến trình khác đang xử lý hết hạn;
- trạng thái thanh toán đã được xác minh hoặc có thể xử lý an toàn.

### BR-13.3 – Giải phóng hàng
- Nếu có reservation, Order Service yêu cầu Inventory Service giải phóng đúng reservation.
- Inventory Service ghi nhận kết quả bền vững và bảo đảm lệnh idempotent.
- Nếu chưa có reservation, không được tạo tác dụng phụ tăng tồn kho.
- Nếu giải phóng thất bại, tiếp tục retry và không tuyên bố compensation đã hoàn tất.

### BR-13.4 – Chốt `EXPIRED`
Chỉ chốt `EXPIRED` khi:
1. Đơn đã quá hạn.
2. Không có thanh toán thành công hợp lệ cho phép xác nhận đơn.
3. Không còn kết quả thanh toán `UNKNOWN` cần đối soát.
4. Reservation cần giải phóng đã được giải phóng thành công.
5. Trạng thái và sự kiện Outbox được ghi bền vững.

Nếu compensation chưa hoàn tất, lưu trạng thái đang bù trừ hoặc `COMPENSATION_FAILED`.

### BR-13.5 – Thanh toán và hết hạn đồng thời
- Dùng conditional update/lock hoặc cơ chế tương đương để ngăn đơn vừa `CONFIRMED` vừa `EXPIRED`.
- Với mô hình demo: chỉ payment được xác nhận hợp lệ trước khi nhánh hết hạn được chốt mới đủ điều kiện xác nhận.
- Payment đến muộn sau khi đơn đã hết hạn phải được đối soát; nếu tiền đã thu, hoàn tiền theo chính sách.
- Không giải phóng hàng mù quáng khi payment vẫn `UNKNOWN`.

## Quy tắc tồn kho

`Available Stock = On-hand Stock - Reserved Stock - Other Commitments`

`Requested Quantity <= Available Stock`

Kiểm tra và cập nhật số lượng phải nguyên tử. Khi hết hạn, chỉ giải phóng lượng hàng thuộc reservation của đơn đó.

## Idempotency

- `eventId`: định danh một sự kiện.
- `commandId`/`idempotencyKey`: định danh một tác dụng nghiệp vụ.
- Payment, reservation, release và expire workflow phải chống xử lý lặp.
- Dùng khóa ổn định, unique constraint và lưu kết quả xử lý.
