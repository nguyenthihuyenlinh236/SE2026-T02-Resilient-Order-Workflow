# Business Rules – APP-08

| ID | Quy tắc | Kiểm soát |
|---|---|---|
| BR-01 | Không xác nhận đơn khi thiếu điều kiện | Chỉ xác nhận khi reservation đã được xác nhận `RESERVED`, payment được xác minh `SUCCEEDED`, Order vẫn ở `PENDING` và conditional update từ `PENDING` sang `CONFIRMED` thành công trước khi nhánh hết hạn claim đơn. Nếu đơn đã chuyển sang `EXPIRING`, không được xác nhận lại; nếu tiền đã thu, phải đối soát và hoàn tiền theo chính sách. |
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
| BR-13 | Tự động hủy đơn quá hạn và giải phóng tồn kho | Khi `currentTime >= expireAt`, hệ thống xem xét hết hạn nếu Order chưa kết thúc. Chỉ claim nhánh hết hạn khi payment không ở trạng thái `UNKNOWN`. Nếu có reservation, phải giải phóng reservation; nếu đã thu tiền nhưng không thể xác nhận đơn, phải hoàn tiền theo chính sách. Chỉ chốt `EXPIRED` khi các điều kiện kết thúc và bù trừ bắt buộc đã được xác minh. |

## Chi tiết BR-13 – Order Expiration / TTL

### BR-13.1 – Thời hạn

- Order Service lưu `createdAt` và `expireAt` khi tạo đơn.
- Công thức: `expireAt = createdAt + TTL`.
- TTL mặc định đề xuất: 15 phút.
- Retry tạo đơn không tự động gia hạn TTL.
- Dùng thời gian chuẩn thống nhất; khuyến nghị lưu timestamp UTC.

### BR-13.2 – Điều kiện kích hoạt

Đơn được xem xét hết hạn khi:

- `currentTime >= expireAt`.
- Order vẫn ở trạng thái `PENDING`.
- Payment đã được xác minh hoặc có thể xử lý an toàn; nếu payment là `UNKNOWN`, chưa được claim nhánh hết hạn.
- Không có tiến trình khác đã claim nhánh hết hạn thành công.

Order Service sử dụng conditional update để chuyển `PENDING` sang `EXPIRING`. Chỉ tiến trình cập nhật thành công mới được tiếp tục xử lý hết hạn. Nếu Order đã `CONFIRMED`, tiến trình hết hạn phải dừng; nếu Order đã `EXPIRING`, các tiến trình khác không được xác nhận đơn.

### BR-13.3 – Giải phóng hàng

- Nếu có reservation, Order Service yêu cầu Inventory Service giải phóng đúng reservation.
- Inventory Service ghi nhận kết quả bền vững và bảo đảm lệnh idempotent.
- Nếu chưa có reservation, không được tạo tác dụng phụ tăng tồn kho.
- Nếu giải phóng thất bại hoặc kết quả chưa rõ, tiếp tục retry/truy vấn bằng cùng idempotency key; không giả định reservation đã được giải phóng.
- Không giải phóng hàng khi payment vẫn `UNKNOWN`; phải đối soát trước khi quyết định compensation.

### BR-13.4 – Chốt `EXPIRED`

Chỉ chốt `EXPIRED` khi đáp ứng tất cả điều kiện áp dụng:

1. Đơn đã quá hạn và nhánh hết hạn đã được claim thành công.
2. Không có thanh toán thành công hợp lệ cho phép xác nhận đơn theo quy tắc BR-13.5.
3. Không còn kết quả payment `UNKNOWN` cần đối soát.
4. Reservation cần giải phóng đã được giải phóng thành công.
5. Nếu payment đã thu tiền nhưng đơn không thể xác nhận, việc hoàn tiền bắt buộc đã được xác minh thành công.
6. Trạng thái Order và sự kiện `OrderExpired` được ghi bền vững thông qua Outbox trong cùng giao dịch lưu trữ phù hợp.

Nếu compensation chưa hoàn tất, lưu tiến trình bù trừ chưa hoàn tất hoặc trạng thái `COMPENSATION_FAILED`; tiếp tục retry, theo dõi và cảnh báo. Không được chốt `EXPIRED` chỉ vì đã gửi yêu cầu giải phóng hàng hoặc hoàn tiền.

### BR-13.5 – Thanh toán và hết hạn đồng thời

- Dùng conditional update hoặc cơ chế tương đương để ngăn Order vừa `CONFIRMED` vừa `EXPIRED`.
- Chỉ một nhánh được phép claim chuyển trạng thái từ `PENDING`: xác nhận đơn (`CONFIRMED`) hoặc bắt đầu hết hạn (`EXPIRING`).
- Nhánh xác nhận chỉ được cập nhật `PENDING -> CONFIRMED` khi reservation đã được xác nhận `RESERVED`, payment được xác minh `SUCCEEDED`, payment hoàn tất hợp lệ trước `expireAt`, và cập nhật có điều kiện thành công.
- Nhánh hết hạn chỉ được cập nhật `PENDING -> EXPIRING` khi `currentTime >= expireAt`, payment không còn `UNKNOWN`, và cập nhật có điều kiện thành công.
- Nếu nhánh xác nhận thắng, tiến trình hết hạn không được chuyển đơn sang `EXPIRING` hoặc `EXPIRED`.
- Nếu nhánh hết hạn thắng, không được chuyển đơn từ `EXPIRING` hoặc `EXPIRED` trở lại `CONFIRMED`.
- Payment đến muộn sau khi đơn đã hết hạn không làm đơn được xác nhận trở lại. Nếu tiền đã thu, hệ thống phải đối soát và hoàn tiền theo chính sách.
- Nếu payment là `UNKNOWN`, không claim nhánh hết hạn, không giải phóng reservation và không hoàn tiền dựa trên giả định. Tiếp tục truy vấn/đối soát đến khi có kết quả xác định hoặc chuyển sang xử lý thủ công theo quyết định thiết kế.

## Quy tắc tồn kho

`Available Stock = On-hand Stock - Reserved Stock - Other Commitments`

`Requested Quantity <= Available Stock`

Kiểm tra và cập nhật số lượng phải nguyên tử. Khi hết hạn, chỉ giải phóng lượng hàng thuộc reservation của đơn đó.

`Other Commitments` cần được định nghĩa rõ trong thiết kế triển khai để tránh trừ trùng những lượng hàng đã được tính trong `Reserved Stock`.

## Idempotency

- `eventId`: định danh duy nhất của một sự kiện; giữ nguyên qua các lần retry phát sự kiện.
- `commandId`/`idempotencyKey`: định danh một tác dụng nghiệp vụ; giữ nguyên qua các lần retry cùng một tác dụng.
- Payment, reservation, release, refund và expire workflow phải chống xử lý lặp.
- Dùng khóa ổn định, unique constraint và lưu kết quả xử lý.
- Cùng khóa và cùng payload phải trả lại kết quả đã lưu; cùng khóa nhưng payload khác phải bị từ chối hoặc báo xung đột.
- Consumer cần chống xử lý trùng sự kiện; không được tạo tác dụng phụ nghiệp vụ lần thứ hai khi nhận lại cùng `eventId`.
