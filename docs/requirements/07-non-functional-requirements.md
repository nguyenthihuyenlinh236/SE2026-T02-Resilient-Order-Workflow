# Non-Functional Requirements – APP-08 Resilient Order Workflow

## 1. Mục đích và phạm vi

Tài liệu xác định các yêu cầu phi chức năng (Non-Functional Requirements – NFR) cho APP-08 – Resilient Order Workflow. Trọng tâm là Reliability, tính nhất quán của trạng thái Order, khả năng phục hồi Saga, chống xử lý trùng, độ bền dữ liệu và khả năng kiểm chứng khi xảy ra lỗi tích hợp.

Trong tài liệu này, Reliability tập trung vào **tính đúng của xử lý nghiệp vụ và khả năng phục hồi sau lỗi**.

Phạm vi áp dụng gồm:

- Order Service và quản lý trạng thái Order/Saga.
- Inventory Service và reservation.
- Payment Service giả lập và quy trình đối soát.
- Notification Service.
- Outbox Publisher, consumer xử lý sự kiện và cơ chế chống trùng.
- TTL Worker / Expiration Scheduler.
- API tạo đơn, truy vấn trạng thái và các cơ chế kiểm thử lỗi.

## 2. Ràng buộc cố định

| ID | Ràng buộc | Giá trị | Ý nghĩa |
|---|---|---|---|
| NFR-C01 | Người dùng hoạt động đồng thời | 155 người dùng | Phải có kịch bản kiểm chứng tính đúng và hiệu năng dưới tải mục tiêu. |
| NFR-C02 | Thời gian lưu dữ liệu | 28 ngày | Phải xác định phạm vi dữ liệu và mốc bắt đầu tính thời hạn lưu giữ. |
| NFR-C03 | Thời gian phản hồi Change Request | 14 giờ | Phải có cách đánh giá ảnh hưởng và theo dõi thời gian phản hồi. |
| NFR-C04 | Thuộc tính chất lượng trọng tâm | Reliability | Khi một thành phần gặp lỗi, hệ thống tiếp tục an toàn, khôi phục hoặc bù trừ. |
| NFR-C05 | Ưu tiên rủi ro | Rủi ro tích hợp cao hơn rủi ro giao diện | Kiểm thử tích hợp được thực hiện và xử lý trước kiểm thử giao diện. |
| NFR-C06 | Người dùng trọng tâm | Người dùng lần đầu, chưa quen công nghệ | Trạng thái và thông báo dành cho khách hàng phải dễ hiểu, không làm lộ trạng thái kỹ thuật nội bộ. |

**Lưu ý về tải:** 155 người dùng hoạt động đồng thời không đồng nghĩa với 155 giao dịch mỗi giây. Thông lượng thực tế phụ thuộc thời gian suy nghĩ của người dùng, số yêu cầu trong mỗi phiên và thời gian phản hồi. Kịch bản tải phải nêu rõ các giả định này.

## 3. Danh sách yêu cầu phi chức năng

### NFR-01 – Reliability: Hoàn tất hoặc xử lý lỗi an toàn

**Mức ưu tiên:** Must

**Yêu cầu**

Khi một thành phần gặp lỗi trong quá trình xử lý đơn hàng, hệ thống phải tiếp tục quy trình nếu có thể, truy vấn trạng thái tác dụng phụ chưa rõ, hoặc thực hiện compensation khi có đủ căn cứ. Hệ thống không được báo thành công khi điều kiện nghiệp vụ chưa được xác minh và không được bỏ quên Saga đang chờ xử lý.

**Tiêu chí kiểm chứng**

1. Với từng tình huống EC-01 đến EC-11, mỗi Order phải đạt trạng thái kết thúc hợp lệ hoặc trạng thái trung gian có thể theo dõi và khôi phục.
2. Khi payment là `UNKNOWN`, hệ thống không được mặc định kết quả là thất bại để giải phóng reservation hoặc hoàn tiền.
3. Khi compensation chưa được xác nhận hoàn tất, Saga phải lưu trạng thái công việc chưa hoàn tất hoặc `COMPENSATION_FAILED`.
4. Mỗi Saga chưa kết thúc phải truy xuất được `Order ID`, `Saga ID`, bước xử lý cuối cùng và hành động tiếp theo.
5. Theo ngưỡng đề xuất, không Order nào được ở `PENDING` quá `expireAt + 10 giây`, ngoại trừ trường hợp payment vẫn `UNKNOWN`.

**Truy vết:** FR-09, FR-12, FR-13, FR-14, FR-15; BR-01, BR-07, BR-10, BR-13; EC-03, EC-05, EC-06, EC-09, EC-11; D-01, D-03, D-04, D-05, D-06.

### NFR-02 – Tính nhất quán trạng thái

**Mức ưu tiên:** Must

**Yêu cầu**

Order phải được chuyển trạng thái bằng cơ chế cập nhật có điều kiện hoặc cơ chế tương đương để xử lý cạnh tranh giữa các tiến trình xác nhận đơn, bắt đầu hết hạn và bắt đầu thất bại.

Chỉ **một trong ba nhánh** sau được phép chuyển Order khỏi `PENDING` thành công:

- Nhánh xác nhận: `PENDING -> CONFIRMED`.
- Nhánh hết hạn: `PENDING -> EXPIRING`.
- Nhánh thất bại: `PENDING -> COMPENSATING`.

Mọi so sánh thời gian dùng một nguồn duy nhất là đồng hồ cơ sở dữ liệu (DB clock) theo UTC, theo D-01 và A-15. Các timestamp được so sánh phải theo cùng quy ước thời gian; không dùng đồng hồ riêng của từng service làm căn cứ cạnh tranh chuyển trạng thái.

**Tiêu chí kiểm chứng**

1. Chạy ít nhất 1.000 lượt kiểm thử cạnh tranh; **mỗi lượt sử dụng một Order mới**. Trong mỗi lượt, ba nhánh xác nhận, hết hạn và thất bại cùng tranh chấp chuyển trạng thái từ `PENDING`; chỉ một conditional update thành công.
2. Trong dữ liệu kiểm thử, không có Order vừa `CONFIRMED` vừa `EXPIRED`.
3. Không có chuyển trạng thái từ `EXPIRING` hoặc `EXPIRED` trở lại `CONFIRMED`.
4. Nhánh xác nhận chỉ thành công khi reservation đã xác minh `RESERVED`, payment đã xác minh `SUCCEEDED` (không phải `UNKNOWN`), Order còn `PENDING` và `now() < expireAt` theo DB clock trong cùng câu lệnh cập nhật (D-01.1). Trường hợp payment hoàn tất trước `expireAt` nhưng chỉ được xác minh sau `expireAt` xử lý theo D-01.4.
5. Nhánh hết hạn chỉ claim khi `currentTime >= expireAt`, payment không ở `UNKNOWN`, và conditional update từ `PENDING` sang `EXPIRING` thành công.
6. Nhánh thất bại chỉ claim khi điều kiện thất bại nghiệp vụ đã được xác minh và conditional update từ `PENDING` sang `COMPENSATING` thành công.
7. Kiểm tra log và dữ liệu lưu trữ để xác nhận mọi quyết định so sánh thời gian sử dụng DB clock UTC.

**Truy vết:** FR-09, FR-12, FR-15; BR-01, BR-07, BR-12, BR-13; EC-02, EC-05, EC-06, EC-11; D-01, D-05, D-06; A-15.

### NFR-03 – Idempotency và chống xử lý trùng

**Mức ưu tiên:** Must

**Yêu cầu**

Các thao tác tạo đơn, giữ hàng, thanh toán, giải phóng reservation, hoàn tiền, phát sự kiện và xử lý sự kiện phải chống tác dụng phụ lặp do retry, message duplicate hoặc phục hồi sau crash. Khóa idempotency phải ổn định trong suốt các lần retry của cùng một tác dụng nghiệp vụ, theo D-02.

**Tiêu chí kiểm chứng**

1. Gửi cùng yêu cầu tạo đơn với cùng idempotency key và cùng payload ít nhất 10 lần chỉ tạo một Order.
2. Gửi lại cùng yêu cầu thanh toán ít nhất 10 lần không tạo nhiều giao dịch thu tiền.
3. Gửi lại cùng lệnh reserve, release hoặc refund ít nhất 10 lần không làm lặp tác dụng phụ tương ứng.
4. Consumer nhận cùng `eventId` ít nhất 10 lần chỉ xử lý tác dụng nghiệp vụ một lần.
5. Cùng khóa nhưng payload khác phải trả kết quả xung đột; không thực hiện tác dụng phụ mới.
6. Sau khi service khởi động lại, gửi lại yêu cầu đã xử lý phải trả kết quả đã lưu hoặc kết quả tương đương mà không tạo tác dụng phụ mới.
7. Các khóa được dùng lại khi retry phải tuân theo quy ước trong D-02; không tự tạo khóa mới chỉ vì lần gửi trước timeout.

**Truy vết:** FR-03, FR-05, FR-06, FR-07, FR-08, FR-10, FR-12, FR-15; BR-03, BR-05, BR-08, BR-11; EC-04, EC-05, EC-07, EC-08, EC-09, EC-11; D-02, D-03, D-05, D-06.

### NFR-04 – Khả năng phục hồi sau lỗi

**Mức ưu tiên:** Must

**Yêu cầu**

Hệ thống phải tiếp tục xử lý Saga, Outbox và TTL sau khi tiến trình bị dừng đột ngột. Trạng thái cần thiết để phục hồi phải được lưu bền vững; không được phụ thuộc vào bộ nhớ tiến trình để quyết định kết quả nghiệp vụ.

Kiểm thử phục hồi phải bao phủ các điểm crash được định nghĩa trong D-08.2:

- `BEFORE_COMMIT`.
- `AFTER_COMMIT_BEFORE_RESPONSE`.
- `AFTER_PUBLISH_BEFORE_MARK`.
- `AFTER_CLAIM_BEFORE_RELEASE`.

**Tiêu chí kiểm chứng**

1. Thực hiện kiểm thử tại cả bốn điểm crash nêu trên.
2. Với `BEFORE_COMMIT`, sau restart không được coi dữ liệu chưa commit là dữ liệu đã commit; yêu cầu có thể được gửi lại an toàn.
3. Với `AFTER_COMMIT_BEFORE_RESPONSE`, retry cùng khóa không tạo thêm Order hoặc tác dụng phụ.
4. Với `AFTER_PUBLISH_BEFORE_MARK`, sự kiện có thể được phát lại nhưng consumer không xử lý lặp tác dụng nghiệp vụ.
5. Với `AFTER_CLAIM_BEFORE_RELEASE`, tiến trình hết hạn được tiếp tục hoặc claim lại an toàn sau khi lease hết hạn; reservation không bị bỏ quên.
6. Theo ngưỡng đề xuất, không mất dữ liệu đã commit (RPO = 0).
7. Theo ngưỡng đề xuất:
   - Sau khi restart service, Publisher hoặc TTL Worker, Saga, Outbox và TTL Worker tiếp tục xử lý trong không quá 30 giây.
   - Khi worker đang giữ claim bị crash tại `AFTER_CLAIM_BEFORE_RELEASE`, TTL Worker khác tiếp quản và tiếp tục xử lý trong không quá 45 giây, gồm lease 30 giây, chu kỳ quét 5 giây và khoảng dự phòng.
8. Bằng chứng kiểm thử gồm trạng thái trước crash, điểm crash, dữ liệu sau restart, log phục hồi và kết quả tác dụng phụ.

**Truy vết:** FR-12, FR-13, FR-14, FR-15; BR-08, BR-10, BR-12, BR-13; EC-06, EC-08, EC-09, EC-11; D-02, D-03, D-06, D-08.2.

### NFR-05 – Đối soát trạng thái chưa xác định

**Mức ưu tiên:** Must

**Yêu cầu**

Khi một lệnh thanh toán hoặc reservation timeout, hệ thống phải phân biệt trạng thái `UNKNOWN` với `FAILED`. Việc đối soát phải theo lịch và giới hạn được quy định trong D-04; không được tạo khóa mới để thử lại tác dụng phụ chưa rõ kết quả.

**Tiêu chí kiểm chứng**

1. Khi payment trả `UNKNOWN`, hệ thống không tự giải phóng reservation hoặc hoàn tiền chỉ dựa trên timeout.
2. Payment được truy vấn lại bằng định danh giao dịch và khóa idempotency ổn định.
3. Lịch đối soát theo D-04 được cấu hình và kiểm thử: mỗi 5 giây trong 60 giây đầu, sau đó mỗi 30 giây đến mốc 10 phút, tổng tối đa 30 lần truy vấn trong 10 phút.
4. Nếu vẫn không xác định được kết quả sau giới hạn đối soát, hệ thống chuyển sang trạng thái cần đối soát thủ công, lưu nguyên nhân và phát cảnh báo.
5. Khi Inventory timeout, hệ thống truy vấn reservation bằng định danh ổn định trước khi quyết định gửi lại lệnh hoặc bù trừ.
6. Khi trạng thái payment được xác định, Saga tiếp tục theo kết quả thực tế; không mặc định thất bại chỉ vì hết thời gian chờ.
7. `expireAt` không tự được gia hạn do payment chưa rõ kết quả.

**Truy vết:** FR-05, FR-06, FR-07, FR-08, FR-12, FR-13, FR-14, FR-15; BR-07, BR-08, BR-10, BR-13; EC-03, EC-04, EC-05, EC-09, EC-11; D-03, D-04, D-05, D-06.

### NFR-06 – Độ bền dữ liệu và Outbox

**Mức ưu tiên:** Must

**Yêu cầu**

Trạng thái Order và bản ghi Outbox tương ứng phải được ghi trong cùng transaction khi chúng thuộc cùng một quyết định nghiệp vụ cần phát sự kiện. Cơ chế giao nhận sự kiện sử dụng **at-least-once**: sự kiện có thể được phát lại, và consumer phải chống xử lý trùng. Không tuyên bố giao nhận hoặc xử lý `exactly-once` xuyên suốt các service.

**Tiêu chí kiểm chứng**

1. Nếu transaction lưu Order thất bại, không được tồn tại Outbox event đã commit cho thay đổi Order đó.
2. Nếu transaction commit thành công, trạng thái Order và Outbox event tương ứng cùng tồn tại bền vững.
3. Publisher có thể phát lại một sự kiện sau timeout hoặc crash; consumer nhận trùng vẫn không lặp tác dụng phụ nghiệp vụ.
4. Mỗi sự kiện giữ nguyên `eventId` qua các lần retry phát lại.
5. Kiểm thử Outbox crash chứng minh không mất sự kiện đã commit, kể cả khi publisher dừng sau publish nhưng trước khi đánh dấu đã phát.
6. Theo ngưỡng đề xuất, độ trễ phát sự kiện có p95 không quá 2 giây trong điều kiện không có lỗi và tải bình thường.
7. Bằng chứng gồm trạng thái Order, bản ghi Outbox, log publish, kết quả consumer và phép đo độ trễ.

**Truy vết:** FR-10, FR-12, FR-13, FR-14, FR-15; BR-09, BR-10, BR-12, BR-13; EC-06, EC-07, EC-08, EC-10, EC-11; D-02, D-03, D-06, D-08.2.

### NFR-07 – Xử lý hết hạn an toàn

**Mức ưu tiên:** Must

**Yêu cầu**

Hệ thống tự động phát hiện Order quá hạn, claim nhánh hết hạn an toàn, giải phóng reservation khi cần và hoàn tiền khi tiền đã thu nhưng Order không thể xác nhận. TTL mặc định là 15 phút, chu kỳ quét là 5 giây và lease claim là 30 giây theo D-06.

**Tiêu chí kiểm chứng**

1. `expireAt = createdAt + TTL`; TTL mặc định bằng 15 phút và không tự gia hạn khi retry tạo đơn.
2. TTL Worker quét theo chu kỳ 5 giây và tìm lại các Order quá hạn chưa xử lý sau khi khởi động lại.
3. Trong luồng bình thường, không tính trường hợp worker đang giữ claim bị crash, Order quá hạn có payment không ở `UNKNOWN` được claim trong không quá 10 giây, tương đương hai chu kỳ quét 5 giây.
4. Claim sử dụng cập nhật có điều kiện và lease 30 giây theo D-06; tiến trình khác không được cùng claim thành công trên một Order.
5. Nếu payment là `UNKNOWN`, không claim nhánh hết hạn và không giải phóng reservation hoặc hoàn tiền dựa trên giả định.
6. Nếu có reservation, chỉ chuyển sang trạng thái kết thúc hết hạn sau khi Inventory xác nhận giải phóng thành công.
7. Nếu payment đã `SUCCEEDED` nhưng Order không thể xác nhận, phải hoàn tiền theo chính sách; không chốt `EXPIRED` trước khi kết quả hoàn tiền bắt buộc được xác minh.
8. Payment thành công đến muộn sau `EXPIRED` không làm Order quay lại `CONFIRMED`; nếu tiền đã thu thì thực hiện đối soát và hoàn tiền theo chính sách.
9. Sự kiện `OrderExpired` chỉ được ghi bền vững khi điều kiện kết thúc nghiệp vụ tương ứng được đáp ứng.

**Truy vết:** FR-09, FR-12, FR-13, FR-14, FR-15; BR-01, BR-07, BR-08, BR-10, BR-12, BR-13; EC-06, EC-09, EC-11; D-01, D-03, D-04, D-05, D-06, D-08.2.

### NFR-08 – Khả năng quan sát và kiểm toán

**Mức ưu tiên:** Must

**Yêu cầu**

Hệ thống phải lưu thông tin đủ để truy vết Order/Saga, xác định bước xử lý cuối cùng, theo dõi retry, nhận diện công việc cần can thiệp thủ công và cảnh báo khi vượt ngưỡng. Log không được chứa dữ liệu nhạy cảm thanh toán không cần thiết.

**Tiêu chí kiểm chứng**

1. Log của mỗi bước xử lý nghiệp vụ có `Order ID` và `Saga ID`; ghi thêm `Payment ID`, `Reservation ID` hoặc `eventId` khi có.
2. Với mỗi retry, lưu số lần thử, thời điểm, kết quả và bước đang thực hiện.
3. Saga cần can thiệp thủ công có trạng thái, lý do, thời điểm và định danh tương quan để truy vấn.
4. Khi vượt ngưỡng retry hoặc compensation thất bại theo D-03, hệ thống tạo cảnh báo hoặc bản ghi cảnh báo có thể truy vấn.
5. Kiểm tra log của các kịch bản thanh toán không chứa thông tin xác thực, dữ liệu thẻ đầy đủ hoặc bí mật thanh toán không cần thiết.
6. Có thể truy vấn lịch sử của một Order theo `Order ID` và lần theo các bước Saga liên quan.

**Truy vết:** FR-12, FR-13, FR-14, FR-15; BR-10, BR-12, BR-13; EC-03, EC-06, EC-08, EC-09, EC-11; D-03, D-04, D-05, D-06, D-08.

### NFR-09a – Tính đúng dưới tải 155 người dùng đồng thời

**Mức ưu tiên:** Must

**Yêu cầu**

Dưới tải mục tiêu 155 người dùng đồng thời, hệ thống phải duy trì các bất biến nghiệp vụ ngay cả khi có đặt hàng đồng thời trên sản phẩm tồn kho thấp và có chèn lỗi theo D-08.

**Tiêu chí kiểm chứng**

1. Trong toàn bộ kịch bản kiểm thử, số lần giữ hàng vượt tồn khả dụng bằng 0.
2. Số Order tạo ra nhiều giao dịch thu tiền do retry hoặc message duplicate bằng 0.
3. Số Order vừa `CONFIRMED` vừa `EXPIRED` bằng 0.
4. Reservation mồ côi là reservation `RESERVED` mà Order tương ứng đã `FAILED` hoặc `EXPIRED`, hoặc không có Order tương ứng. Sau khi kết thúc chèn lỗi và quá thời gian retry nền tối đa (đề xuất 5 phút), số reservation mồ côi bằng 0. Reservation của Order đang `EXPIRING`, `COMPENSATING` hoặc chờ đối soát `UNKNOWN` (D-04.2) không tính là mồ côi.
5. Thực hiện kịch bản có nhiều yêu cầu đặt mua đồng thời cùng một sản phẩm với tồn khả dụng thấp; kiểm tra tổng số lượng được giữ không vượt số lượng khả dụng.
6. Chèn các lỗi hỗ trợ trong D-08 và kiểm tra lại cả bốn bất biến trên.
7. Lưu số người dùng đồng thời, thời gian chạy, dữ liệu tồn kho ban đầu, số yêu cầu, lỗi được chèn và kết quả truy vấn kiểm chứng.

**Truy vết:** FR-03, FR-04, FR-06, FR-07, FR-08, FR-09, FR-12, FR-13, FR-14, FR-15; BR-01, BR-02, BR-03, BR-05, BR-08, BR-13; EC-01, EC-02, EC-04, EC-05, EC-06, EC-07, EC-09, EC-11; D-01, D-02, D-03, D-05, D-06, D-08; D-04.2.

### NFR-09b – Hiệu năng dưới tải

**Mức ưu tiên:** Must

**Yêu cầu**

Với 155 người dùng/tác vụ đồng thời, thời gian phản hồi p95 mục tiêu của các API tạo đơn và tra cứu đơn không vượt quá 5,0 giây. Đây là tham số cố định của đề bài. Các ngưỡng bổ sung như tỷ lệ lỗi 5xx và độ trễ theo từng endpoint là ngưỡng đề xuất của nhóm, cần được xác nhận riêng.

**Tiêu chí kiểm chứng**

1. Đo thời gian phản hồi p95 của `POST /api/v1/orders` và `GET /api/v1/orders/{orderId}`.
2. Đo tỷ lệ phản hồi lỗi 5xx, thông lượng yêu cầu, độ trễ hoàn tất Saga và độ trễ phát sự kiện.
3. Chạy tối thiểu hai nhóm kịch bản: không chèn lỗi và chèn độ trễ tại một service phụ thuộc.
4. Báo cáo cấu hình môi trường, cấu hình tải, dữ liệu kiểm thử, thời gian chạy, số yêu cầu và kết quả đo.
5. Theo ngưỡng đề xuất:
   - `POST /api/v1/orders`: p95 không quá 5.000 ms (5,0 giây).
   - `GET /api/v1/orders/{orderId}`: p95 không quá 5.000 ms (5,0 giây).
   - Tỷ lệ phản hồi 5xx không quá 1%.
6. Điều kiện đo đề xuất: 155 người dùng/tác vụ đồng thời, think time 5–10 giây; thông lượng tham khảo khoảng 11–23 yêu cầu/giây. Thông lượng thực tế phải được đo và báo cáo, không suy ra chỉ từ số người dùng.

**Truy vết:** FR-01, FR-09, FR-10, FR-11, FR-14, FR-15; BR-09, BR-10, BR-12, BR-13; EC-03, EC-04, EC-06, EC-08, EC-10, EC-11; D-03, D-06, D-07, D-08.

### NFR-10 – Lưu giữ dữ liệu 28 ngày

**Mức ưu tiên:** Must

**Yêu cầu**

Dữ liệu phục vụ xử lý, truy vết, chống trùng và đối soát phải được lưu giữ theo ràng buộc 28 ngày. Mốc tính thời hạn lưu giữ đề xuất là thời điểm Order đạt trạng thái cuối.

Order/Saga chưa kết thúc hoặc đang compensation không bị xóa chỉ vì đã quá 28 ngày kể từ khi tạo đơn. Khóa idempotency, bản ghi inbox và outbox phải được giữ ít nhất 28 ngày và không ngắn hơn khoảng retry/phát lại tối đa được cấu hình, nhằm tránh xử lý lại duplicate sau khi bản ghi chống trùng đã bị xóa.

**Tiêu chí kiểm chứng**

1. Xác định rõ các loại dữ liệu áp dụng: Order, Saga, payment reference, reservation reference, Outbox, inbox, idempotency key và log kiểm toán.
2. Xác minh mốc bắt đầu tính 28 ngày là thời điểm Order đạt trạng thái cuối; nhóm cần xác nhận trước khi nghiệm thu.
3. Order/Saga chưa kết thúc hoặc đang compensation không bị xóa trong quá trình xử lý, kể cả khi thời gian từ `createdAt` đã vượt 28 ngày.
4. Khóa idempotency, inbox và outbox có thời hạn lưu giữ ít nhất 28 ngày và không ngắn hơn khoảng retry/phát lại tối đa được cấu hình.
5. Kiểm thử gửi lại sự kiện trong thời hạn chống trùng và xác nhận consumer không tạo tác dụng phụ lặp.
6. **Đề xuất phạm vi dữ liệu thanh toán:** hệ thống chỉ lưu tham chiếu thanh toán giả lập (`paymentId`, trạng thái, số tiền, khóa idempotency); không lưu số thẻ, mã xác thực hay dữ liệu thanh toán thật. Dev/BA xác nhận phạm vi lưu dữ liệu này.
7. Có bằng chứng về cấu hình lưu giữ, truy vấn dữ liệu và kết quả kiểm tra vòng đời dữ liệu.

**Truy vết:** FR-06, FR-08, FR-10, FR-12, FR-13, FR-14, FR-15; BR-08, BR-10, BR-11, BR-12, BR-13; EC-06, EC-07, EC-08, EC-09, EC-11; D-02, D-03, D-04, D-05, D-06.

### NFR-11 – Khả năng bảo trì và phản hồi thay đổi

**Mức ưu tiên:** Must

**Yêu cầu**

Tài liệu và quy trình phân tích phải giúp nhóm đánh giá ảnh hưởng của Change Request trong giới hạn 14 giờ theo NFR-C03. ID của yêu cầu, quy tắc, tình huống lỗi, use case và Acceptance Criteria phải ổn định để duy trì khả năng truy vết.

**Tiêu chí kiểm chứng**

1. Mỗi FR, BR, EC, UC và AC có ID ổn định và có thể tham chiếu từ ma trận truy vết.
2. Các giả định chưa được xác nhận được ghi riêng, có người chịu trách nhiệm xác nhận và không được trình bày như quyết định đã chốt.
3. Với một Change Request mẫu, nhóm lập được danh sách các FR, BR, EC, UC, AC, service và sơ đồ chịu ảnh hưởng.
4. Ghi lại thời điểm nhận yêu cầu, thời điểm hoàn tất đánh giá ảnh hưởng và kết quả đánh giá.
5. Khi có thay đổi trạng thái, API hoặc event contract, ma trận truy vết chỉ ra được các tài liệu và kiểm thử cần xem xét.

**Truy vết:** FR-01 đến FR-15; BR-01 đến BR-13; EC-01 đến EC-11; D-01 đến D-08.

### NFR-12 – Dễ sử dụng cho người dùng lần đầu

**Mức ưu tiên:** Should (riêng tiêu chí 2: Must)

**Yêu cầu**

Trạng thái và thông báo hướng đến khách hàng phải dễ hiểu đối với người dùng lần đầu, không làm lộ tên trạng thái kỹ thuật nội bộ và không tuyên bố thanh toán thành công khi kết quả còn `UNKNOWN`.

API truy vấn trạng thái có thể trả thêm trường `displayMessage` bên cạnh `status`; đây là đề xuất thiết kế, cần được đối chiếu với API/event contract trước khi chốt.

**Bảng ánh xạ trạng thái nội bộ sang thông điệp khách hàng**

| Trạng thái nội bộ | Điều kiện hoặc ý nghĩa | Thông điệp khách hàng đề xuất |
|---|---|---|
| `PENDING` | Đơn đang được xử lý | Đơn hàng đang được xử lý. Vui lòng kiểm tra lại trạng thái sau. |
| `PENDING` + payment `UNKNOWN` | Chưa xác định được kết quả thanh toán | Chúng tôi đang xác minh kết quả thanh toán. Vui lòng không thực hiện lại thanh toán cho đơn này trong lúc xác minh. |
| `COMPENSATING` | Hệ thống đang hoàn tác các tác động nghiệp vụ | Đơn hàng chưa hoàn tất. Hệ thống đang xử lý và sẽ cập nhật trạng thái khi có kết quả. |
| `COMPENSATION_FAILED` | Bù trừ chưa hoàn tất, cần retry hoặc can thiệp | Đơn hàng đang được xử lý bổ sung. Vui lòng kiểm tra lại trạng thái hoặc liên hệ hỗ trợ nếu tình trạng kéo dài. |
| `EXPIRING` | Hệ thống đang xử lý hết hạn đơn | Đơn hàng đã quá thời hạn thanh toán và hệ thống đang hoàn tất xử lý. |
| `CONFIRMED` | Đơn được xác nhận hợp lệ | Đơn hàng đã được xác nhận thành công. |
| `FAILED` | Đơn thất bại và không còn tác vụ bù trừ bắt buộc chưa hoàn tất | Đơn hàng chưa thể hoàn tất. Bạn có thể đặt lại đơn mới. |
| `EXPIRED` | Đơn hết hạn và các điều kiện kết thúc bắt buộc đã được xác minh | Đơn hàng đã hết thời hạn thanh toán. Nếu khoản tiền đã bị trừ, hệ thống sẽ xử lý theo chính sách hoàn tiền áp dụng. |

Các thông điệp trên là nội dung đề xuất; cần thống nhất với chính sách nghiệp vụ và cách hiển thị thực tế.

**Tiêu chí kiểm chứng**

1. API hiển thị thông điệp cho các trạng thái trong bảng ánh xạ; tên trạng thái nội bộ không được dùng làm thông điệp duy nhất cho khách hàng.
2. Khi payment là `UNKNOWN`, thông điệp không tuyên bố thanh toán thành công hoặc thất bại. Đây là tiêu chí Must, truy vết theo BR-07.
3. Khi trạng thái Order thay đổi, thông điệp phản ánh trạng thái nghiệp vụ mới nhất được lưu bền vững.
4. Nếu có trường `displayMessage`, kiểm thử API xác nhận trường được trả về theo thiết kế được nhóm thống nhất.
5. Kiểm thử thông điệp với ít nhất 5 người dùng thử lần đầu hoặc thực hiện đánh giá theo kịch bản; ghi nhận các trường hợp hiểu sai trạng thái và kết quả đánh giá.

**Truy vết:** FR-01, FR-09, FR-10, FR-14, FR-15; BR-01, BR-07, BR-09, BR-12, BR-13; EC-03, EC-05, EC-09, EC-10, EC-11; D-04, D-05, D-06, D-07.

### NFR-13 – Cấu hình được và kiểm thử được

**Mức ưu tiên:** Must

**Yêu cầu**

Các tham số vận hành và kiểm thử có ảnh hưởng đến tính đúng của Saga phải được cấu hình hoặc điều khiển bằng đồng hồ giả trong môi trường kiểm thử. Fault injection phải có cơ chế bật/tắt rõ ràng, mặc định tắt ngoài profile kiểm thử được phép sử dụng.

Các tham số cần cấu hình hoặc điều khiển gồm TTL, chu kỳ quét TTL, lease claim, backoff retry, lịch đối soát payment và các điểm chèn lỗi trong D-08.

**Tiêu chí kiểm chứng**

1. TTL mặc định bằng 15 phút; chu kỳ quét TTL bằng 5 giây; lease claim bằng 30 giây theo D-06.
2. Lịch đối soát payment tuân theo D-04: 12 lần truy vấn mỗi 5 giây trong 60 giây đầu, sau đó tối đa 18 lần mỗi 30 giây đến mốc 10 phút; tổng tối đa 30 lần truy vấn trong 10 phút.
3. Retry/backoff sử dụng giá trị đề xuất tại D-03 (chờ xác nhận); tham số kiểm thử có thể được điều khiển mà không phải sửa logic nghiệp vụ.
4. Fault injection mặc định tắt; chỉ bật trong profile kiểm thử được phép sử dụng.
5. Kiểm thử bao phủ các chế độ lỗi và điểm crash quy định trong D-08, bao gồm `BEFORE_COMMIT`, `AFTER_COMMIT_BEFORE_RESPONSE`, `AFTER_PUBLISH_BEFORE_MARK` và `AFTER_CLAIM_BEFORE_RELEASE`.
6. Có thể reset cấu hình fault injection và dữ liệu kiểm thử về trạng thái xác định giữa các lượt chạy.
7. Các kiểm thử xác nhận cấu hình sau reset không còn fault injection tồn dư và dữ liệu của lượt trước không ảnh hưởng kết quả lượt sau.
8. Cách triển khai đồng hồ giả, reset dữ liệu và reset cấu hình cần được Dev xác nhận và Tester kiểm chứng trong bộ kiểm thử.

**Truy vết:** FR-11, FR-12, FR-13, FR-14, FR-15; BR-07, BR-08, BR-10, BR-12, BR-13; EC-03, EC-04, EC-06, EC-07, EC-08, EC-09, EC-11; D-03, D-04, D-06, D-08.2, D-08.4; T-02, T-05, T-08.

## 4. Bảng ngưỡng đề xuất

Các giá trị trong bảng dưới đây là **ngưỡng đề xuất của nhóm**, không phải ràng buộc đã được đề bài quy định.

| NFR | Chỉ số | Ngưỡng đề xuất | Điều kiện hoặc ngoại lệ |
|---|---|---|---|
| NFR-01 | Thời gian Order ở `PENDING` sau `expireAt` | Không Order nào quá `expireAt + 10 giây` | Ngoại lệ khi payment vẫn `UNKNOWN`; phải có trạng thái theo dõi và đối soát. |
| NFR-02 | Số lượt kiểm thử cạnh tranh | ≥ 1.000 lượt, mỗi lượt một Order mới | Ba nhánh xác nhận, hết hạn, thất bại cùng tranh chấp. |
| NFR-03 | Số lần gửi lại cùng yêu cầu/lệnh/event | ≥ 10 lần cho mỗi loại | Cùng khóa, cùng payload. |
| NFR-04 | Mất dữ liệu đã commit | RPO = 0 | Áp dụng cho dữ liệu đã commit trong phạm vi hệ thống lưu trữ được kiểm thử. |
| NFR-04 | Thời gian tiếp tục xử lý sau restart | ≤ 30 giây | Restart service, Publisher hoặc TTL Worker. |
| NFR-04 | Thời gian worker khác tiếp quản sau crash | ≤ 45 giây | Điểm `AFTER_CLAIM_BEFORE_RELEASE`; lease 30 giây + chu kỳ quét 5 giây + dự phòng. |
| NFR-06 | Độ trễ phát sự kiện | p95 ≤ 2 giây | Không có lỗi, tải bình thường. |
| NFR-07 | Thời gian claim Order quá hạn | ≤ 10 giây | Luồng bình thường, payment không `UNKNOWN`; không tính worker đang giữ claim bị crash. |
| NFR-09a | Thời gian chờ để coi hệ thống đã ổn định khi đếm reservation mồ côi | 5 phút sau khi kết thúc chèn lỗi | Ngưỡng đề xuất cho thời gian retry nền tối đa. |
| NFR-09b | `POST /api/v1/orders` | p95 ≤ 5.000 ms (5,0 giây) | 155 người dùng/tác vụ đồng thời; think time 5–10 giây. |
| NFR-09b | `GET /api/v1/orders/{orderId}` | p95 ≤ 5.000 ms (5,0 giây) | 155 người dùng/tác vụ đồng thời; think time 5–10 giây. |
| NFR-09b | Tỷ lệ phản hồi 5xx | ≤ 1% | Đo trên tổng số phản hồi trong cùng kịch bản tải đã công bố. |
| NFR-09b | Tải tham khảo | Khoảng 11–23 yêu cầu/giây | Ước lượng từ 155 người dùng và think time 5–10 giây; thông lượng thực tế được đo và báo cáo. |
| NFR-12 | Số người dùng thử | ≥ 5 người dùng lần đầu | Hoặc đánh giá theo kịch bản. |

## 5. Ma trận ưu tiên

| Yêu cầu | Mức ưu tiên | Cơ sở |
|---|---|---|
| NFR-01 – Reliability | Must | Thuộc tính chất lượng trọng tâm |
| NFR-02 – Nhất quán trạng thái | Must | Ngăn chuyển trạng thái mâu thuẫn |
| NFR-03 – Idempotency | Must | Ngăn tác dụng phụ lặp |
| NFR-04 – Phục hồi sau lỗi | Must | Duy trì Saga/Outbox/TTL sau crash |
| NFR-05 – Đối soát UNKNOWN | Must | Ngăn quyết định sai khi chưa rõ kết quả |
| NFR-06 – Độ bền dữ liệu và Outbox | Must | Không mất sự kiện đã commit |
| NFR-07 – Hết hạn an toàn | Must | Ngăn đơn treo và reservation bị giữ vô thời hạn |
| NFR-08 – Quan sát và kiểm toán | Must | Hỗ trợ điều tra lỗi tích hợp |
| NFR-09a – Tính đúng dưới tải | Must | Bảo toàn các bất biến nghiệp vụ dưới tải |
| NFR-09b – Hiệu năng dưới tải | Must | p95 ≤ 5,0 giây là tham số cố định; tỷ lệ lỗi 5xx và các chỉ số bổ sung là đề xuất |
| NFR-10 – Lưu giữ dữ liệu 28 ngày | Must | Ràng buộc cố định của đề bài |
| NFR-11 – Bảo trì và phản hồi thay đổi | Must | Hỗ trợ đánh giá Change Request |
| NFR-12 – Dễ sử dụng | Should; riêng tiêu chí 2 là Must | Tính dễ hiểu là Should; không tuyên bố thành công/thất bại khi payment `UNKNOWN` là Must theo BR-07 |
| NFR-13 – Cấu hình và kiểm thử | Must | Cho phép tái lập kiểm thử lỗi và phục hồi |

## 6. Kế hoạch kiểm chứng tối thiểu

| Nhóm kiểm chứng | Tình huống | Bằng chứng cần lưu |
|---|---|---|
| Reliability | Inventory, Payment hoặc Notification lỗi | Trạng thái Order/Saga trước và sau lỗi; hành động tiếp theo |
| Cạnh tranh trạng thái | Ba nhánh xác nhận, hết hạn, thất bại cùng tranh chấp Order | Kết quả conditional update; trạng thái cuối; xác nhận chỉ một nhánh thắng |
| Idempotency | Gửi trùng request, command, event và payload xung đột | Số tác dụng phụ thực tế; khóa sử dụng; kết quả trả về |
| Recovery | Crash tại bốn điểm trong D-08.2 | Trạng thái trước crash, dữ liệu sau restart, thời gian phục hồi |
| Payment UNKNOWN | Timeout, truy vấn trạng thái, hết giới hạn đối soát | Lịch truy vấn, kết quả, trạng thái Saga và cảnh báo |
| Outbox | Crash sau publish trước khi đánh dấu đã phát | Bản ghi Outbox, lịch phát lại, kết quả consumer |
| TTL | Hết hạn, hai worker tranh chấp, payment UNKNOWN | `expireAt`, claim, lease, chuyển trạng thái, reservation/payment result |
| Compensation | Release/refund timeout hoặc thất bại | Lịch sử retry, kết quả xác minh, trạng thái cuối và cảnh báo |
| Tính đúng dưới tải | 155 người dùng, tồn kho thấp, đặt đồng thời và chèn lỗi | Tồn kho, reservation, giao dịch payment, trạng thái Order và kết quả kiểm tra bất biến |
| Hiệu năng | Tải bình thường và một service bị chậm | Cấu hình môi trường, latency p95, tỷ lệ lỗi, throughput, độ trễ Saga/Outbox |
| Lưu giữ dữ liệu | Truy vấn dữ liệu và chống trùng trong thời hạn lưu giữ | Cấu hình retention, mốc thời gian, kết quả truy vấn và kiểm thử duplicate |
| Dễ sử dụng | Người dùng lần đầu đọc trạng thái và thông báo | Kịch bản đánh giá, lỗi hiểu sai và kết quả đánh giá |
| Cấu hình kiểm thử | Bật/tắt fault injection, reset cấu hình và dữ liệu | Profile sử dụng, cấu hình trước/sau reset, kết quả lặp lại |
| Change Request | Thay đổi một quy tắc nghiệp vụ mẫu | Thời gian tiếp nhận/đánh giá, ma trận ảnh hưởng và tài liệu/kiểm thử liên quan |

**Thứ tự kiểm thử theo NFR-C05:** Kiểm thử tích hợp giữa các service, idempotency, Outbox, TTL và compensation phải được chạy và xử lý trước kiểm thử giao diện. Việc kiểm thử giao diện không thay thế bằng chứng về tính đúng của các luồng tích hợp.
