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
| FR-09 | Xác nhận đơn | Chỉ xác nhận khi reservation của đơn đã được xác nhận `RESERVED`, payment được xác minh `SUCCEEDED`, Order vẫn ở `PENDING` và phép cập nhật có điều kiện từ `PENDING` sang `CONFIRMED` thành công trước khi nhánh hết hạn claim được Order. Nếu nhánh hết hạn đã chuyển `PENDING` sang `EXPIRING` thành công trước, không được xác nhận lại đơn; nếu payment đã thu tiền thì đối soát và hoàn tiền theo chính sách. |
| FR-10 | Gửi thông báo | Gửi thông báo dựa trên sự kiện/trạng thái nghiệp vụ đã được xác nhận. |
| FR-11 | Retry | Retry lỗi tạm thời theo giới hạn số lần và backoff. |
| FR-12 | Compensation | Điều phối, thực hiện và theo dõi hành động bù trừ. |
| FR-13 | Khôi phục Saga | Tiếp tục Saga chưa hoàn tất sau khi service khởi động lại. |
| FR-14 | Theo dõi và đối soát | Lưu Order ID, Saga ID, Payment ID, Reservation ID và trạng thái từng bước. |
| FR-15 | Xử lý hết hạn tự động | Order Service gán `expireAt`; TTL Worker phát hiện đơn quá hạn, xác minh trạng thái thanh toán, claim nhánh hết hạn bằng cập nhật có điều kiện từ `PENDING` sang `EXPIRING`, giải phóng reservation và hoàn tiền nếu cần, chốt `EXPIRED` khi đủ điều kiện và phát sự kiện `OrderExpired`. Không claim hết hạn khi payment còn `UNKNOWN`. |

## Đặc tả FR-15 – Xử lý hết hạn tự động

### Tiền điều kiện
- Đơn có `expireAt` hợp lệ.
- Đơn chưa kết thúc theo trạng thái nghiệp vụ.
- Worker hoặc cơ chế khôi phục có thể tìm các đơn quá hạn chưa xử lý.
- Giá trị TTL và quy tắc claim tuân theo `06-design-decisions.md`.

### Luồng chính
1. Worker tìm đơn đang `PENDING` với `currentTime >= expireAt`.
2. Worker xác minh trạng thái payment trước khi claim. Nếu payment là `UNKNOWN`, không được chuyển đơn sang nhánh hết hạn; tiếp tục đối soát.
3. Nếu payment đã được xác minh và không có điều kiện chặn hết hạn, Worker thực hiện cập nhật có điều kiện `PENDING -> EXPIRING`.
4. Chỉ worker cập nhật thành công mới được xử lý nhánh hết hạn. Worker khác phải đọc lại trạng thái hiện tại và không được ghi đè.
5. Nếu Order đã được chuyển sang `CONFIRMED` trước đó, Worker bỏ qua đơn và không được chuyển đơn sang `EXPIRING` hoặc `EXPIRED`.
6. Nếu Order đã chuyển sang `EXPIRING`, không được xác nhận lại đơn, kể cả khi callback payment đến sau đó.
7. Nếu có reservation cần hủy, yêu cầu Inventory Service giải phóng reservation bằng lệnh idempotent.
8. Nếu payment được xác minh `SUCCEEDED` nhưng Order không thể được xác nhận, yêu cầu hoàn tiền bằng lệnh idempotent theo chính sách.
9. Chỉ chốt `EXPIRED` khi payment không còn `UNKNOWN`, các reservation cần giải phóng đã được xác nhận giải phóng và các khoản refund bắt buộc đã được xác minh hoàn tất.
10. Ghi thay đổi trạng thái và sự kiện `OrderExpired` vào Outbox theo cơ chế lưu bền vững; Notification Service nhận sự kiện và gửi thông báo.

### Ngoại lệ
- **Payment `UNKNOWN`:** tiếp tục truy vấn/đối soát; không mặc định là thất bại, không giải phóng reservation và không hoàn tiền.
- **Payment thành công đến muộn sau `EXPIRED`:** không mở lại Order; đối soát và hoàn tiền nếu tiền đã được thu.
- **Inventory không phản hồi:** retry/truy vấn reservation bằng cùng khóa idempotency; không giả định đã giải phóng.
- **Refund không phản hồi hoặc kết quả chưa rõ:** truy vấn/đối soát refund; không tạo refund mới bằng khóa khác và không tuyên bố đã hoàn tiền thành công.
- **Compensation thất bại:** lưu trạng thái chưa hoàn tất, retry theo chính sách và phát cảnh báo khi vượt ngưỡng.
- **Worker ngừng hoạt động:** khi phục hồi, quét lại các đơn quá hạn và Saga chưa hoàn tất; tiếp tục từ trạng thái bền vững, không lặp tác dụng phụ đã hoàn tất.
- **Hai worker cùng claim:** chỉ một cập nhật `PENDING -> EXPIRING` được thành công; worker còn lại phải đọc trạng thái hiện tại.
- **Payment và TTL cạnh tranh:** áp dụng D-01 trong `06-design-decisions.md`. Không được để cùng một Order vừa `CONFIRMED` vừa `EXPIRED`.
