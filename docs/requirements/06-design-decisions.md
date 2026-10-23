# 06 – Design Decisions

### 1. Quy ước bắt buộc

1. `UNKNOWN` không đồng nghĩa với `FAILED`. Timeout không phải bằng chứng thanh toán thất bại.
2. `eventId` định danh một sự kiện; `commandId`/`idempotencyKey` định danh một tác dụng nghiệp vụ. Không dùng thay thế cho nhau.
3. `EXPIRED` là trạng thái nghiệp vụ, không phải thao tác xóa bản ghi Order.
4. Payment thành công đến muộn sau khi Order đã `EXPIRED` không làm Order được mở lại; hệ thống phải đối soát và hoàn tiền nếu tiền đã được thu.
5. Một Order không được vừa `CONFIRMED` vừa `EXPIRED`.
6. Không giải phóng reservation hoặc hoàn tiền khi kết quả payment còn `UNKNOWN`.
7. Các thao tác có tác dụng phụ phải có khóa idempotency ổn định và lưu kết quả xử lý.
8. Các thời điểm nghiệp vụ được lưu theo UTC.

## D-01 – Phân xử cạnh tranh giữa xác nhận thanh toán và hết hạn

### Bối cảnh

Trong W1, FR-09 và BR-13.5 dùng cụm “payment thành công hợp lệ trước khi nhánh hết hạn được chốt”. Cụm này chưa xác định rõ cách phân xử khi callback payment và TTL Worker chạy gần như đồng thời.

EC-11 cũng yêu cầu ngăn hai worker cùng xử lý một đơn và không cho phép một đơn vừa `CONFIRMED` vừa `EXPIRED`.

### Các phương án

- **Phương án A:** Chỉ căn cứ vào thời điểm nhận callback payment.
- **Phương án B:** Chỉ căn cứ vào `expireAt`, không xét thứ tự cập nhật trạng thái.
- **Phương án C:** Dùng cập nhật trạng thái Order có điều kiện và nguyên tử. Nhánh nào chuyển Order khỏi `PENDING` thành công trước thì thắng; nhánh còn lại không được ghi đè kết quả.

### Quyết định đề xuất

Chọn **Phương án C**, với các quy tắc cụ thể sau.

#### D-01.1. Xác nhận đơn

Order Service chỉ được chuyển:

`PENDING -> CONFIRMED`

khi đồng thời thỏa mãn:

1. Reservation của chính Order đó đã được xác nhận `RESERVED`.
2. Payment đã được xác minh là `SUCCEEDED`, không phải `UNKNOWN`.
3. Thời điểm thực hiện chuyển trạng thái nhỏ hơn `expireAt`.
4. Order vẫn đang ở trạng thái `PENDING` tại thời điểm cập nhật.

Việc cập nhật phải có điều kiện tương đương:

`UPDATE Order ... WHERE orderId = ? AND status = 'PENDING'`

Phải kiểm tra số bản ghi được cập nhật. Nếu không có bản ghi nào được cập nhật, Order Service phải đọc lại trạng thái hiện tại và xử lý theo nhánh đã thắng; không được ghi đè trạng thái.

#### D-01.2. Claim hết hạn

Khi `currentTime >= expireAt`, TTL Worker chỉ được claim một Order đang `PENDING` nếu trạng thái payment đã được xác minh và không còn `UNKNOWN`.

Worker thực hiện chuyển trạng thái có điều kiện:

`PENDING -> EXPIRING`

`EXPIRING` là trạng thái trung gian được đề xuất cho quá trình xử lý hết hạn, không phải trạng thái cuối. Mục đích là đánh dấu nhánh hết hạn đã giành quyền xử lý để callback payment không thể xác nhận lại Order.

Nếu payment vẫn `UNKNOWN`, Worker không được claim `EXPIRING`; Order phải tiếp tục chờ đối soát.

#### D-01.3. Xử lý bên thua

| Tình huống | Quy tắc bắt buộc |
|---|---|
| Chuyển `PENDING -> CONFIRMED` thành công trước | TTL Worker không được chuyển Order sang `EXPIRING` hoặc `EXPIRED`. |
| Chuyển `PENDING -> EXPIRING` thành công trước | Callback payment không được chuyển Order thành `CONFIRMED`. |
| Payment thành công sau khi Order đã vào nhánh hết hạn | Đối soát payment; nếu tiền đã thu, thực hiện refund bằng khóa idempotency ổn định. Không mở lại Order. |
| Payment đang `UNKNOWN` tại thời điểm hết hạn | Không claim hết hạn, không giải phóng reservation và không hoàn tiền cho đến khi kết quả được xác minh. |
| Hai cập nhật xảy ra đồng thời | Cơ sở dữ liệu chỉ cho phép một cập nhật có điều kiện từ `PENDING` thành công. Bên thua phải đọc lại trạng thái. |

**Quy tắc về thời điểm:** Quyết định này ưu tiên thứ tự chuyển trạng thái nguyên tử khỏi `PENDING`, không ưu tiên thứ tự callback đến Order Service. Payment hoàn tất trước `expireAt` nhưng callback đến muộn không tự động có quyền thắng nếu nhánh hết hạn đã claim thành công trước. Trường hợp đó phải xử lý theo nhánh payment đến muộn.

Khi Order đã vào `EXPIRING`, hệ thống thực hiện compensation theo D-05 và chỉ chốt `EXPIRED` khi đáp ứng các điều kiện nghiệp vụ. Không được coi việc claim `EXPIRING` là bằng chứng reservation đã được giải phóng hoặc refund đã hoàn tất.

### Lý do

- Ngăn hai nhánh cùng xác nhận kết quả cuối cho một Order.
- Có cơ chế phân xử cụ thể khi payment callback và TTL Worker cạnh tranh.
- Phù hợp với BR-07, BR-11, BR-12, BR-13 và EC-11.

### Tác động

**Người 2 cần triển khai:**
- Cập nhật Order state có điều kiện, kiểm tra số dòng cập nhật thành công.
- Lưu `expireAt` và trạng thái trung gian `EXPIRING`.
- Không ghi đè trạng thái bằng cập nhật không có điều kiện.
- Chỉ phát sự kiện nghiệp vụ sau khi trạng thái tương ứng được lưu bền vững.

**Người 3 cần kiểm thử:**
- Payment xác minh thành công trước hạn và xác nhận Order thắng.
- TTL Worker claim thành công trước và callback đến sau.
- Hai cập nhật chạy đồng thời.
- Payment `UNKNOWN` tại thời điểm hết hạn.
- Payment thành công đến muộn sau `EXPIRED`.

### Truy vết

- FR-09, FR-13, FR-15.
- BR-01, BR-07, BR-10, BR-12, BR-13.
- EC-03, EC-05, EC-06, EC-11.
- UC-03, UC-04, UC-06.

## D-02 – Quy ước idempotency key cho lệnh và sự kiện

### Bối cảnh

BR-03, BR-08 và BR-11 yêu cầu chống tác dụng phụ lặp, phân biệt định danh sự kiện với định danh tác dụng nghiệp vụ, đồng thời từ chối trường hợp cùng khóa nhưng payload khác nhau.

W1 chưa quy định cụ thể cách sinh khóa, nơi lưu và hành vi khi trùng khóa.

### Quyết định đề xuất

1. Mỗi tác dụng nghiệp vụ có một `commandId` hoặc `idempotencyKey` ổn định.
2. Retry cùng tác dụng nghiệp vụ phải sử dụng lại khóa ban đầu.
3. `eventId` được sinh một lần khi tạo bản ghi Outbox; publish lại phải giữ nguyên `eventId`.
4. Nơi thực hiện tác dụng nghiệp vụ phải lưu khóa, payload hash và kết quả xử lý.
5. Cùng khóa và cùng payload: trả lại kết quả đã lưu, không lặp tác dụng phụ.
6. Cùng khóa nhưng payload khác: trả lỗi xung đột `409 IDEMPOTENCY_KEY_CONFLICT`, không thực hiện tác dụng phụ mới.
7. Áp dụng unique constraint tại nơi lưu dữ liệu tương ứng. Không chỉ dựa vào kiểm tra bằng code trước khi ghi.
8. Các công thức dưới đây là quy ước đề xuất; service sở hữu dữ liệu phải bảo đảm cùng một khóa luôn đại diện cho cùng một tác dụng nghiệp vụ.

### Bảng quy tắc khóa

| Lệnh/sự kiện | Thành phần sinh khóa | Công thức hoặc quy tắc | Nơi lưu và ràng buộc duy nhất đề xuất | Cùng khóa, cùng payload | Cùng khóa, payload khác |
|---|---|---|---|---|---|
| Tạo Order | Client tạo `Idempotency-Key` | UUID ngẫu nhiên cho một yêu cầu đặt hàng logic; retry gửi lại đúng khóa | Order DB: `UNIQUE(customerId, idempotencyKey)`; lưu payload hash và response ban đầu | Trả cùng `orderId` và response đã lưu | Trả `409 IDEMPOTENCY_KEY_CONFLICT` |
| Giữ hàng | Order Service tạo `commandId` | `reserve:{orderId}:v1` | Inventory DB: unique theo `idempotencyKey`; unique reservation theo Order và phiên bản reservation | Trả lại reservation/kết quả đã lưu | Từ chối xung đột, không thay đổi tồn kho |
| Thu tiền | Order Service tạo `commandId` | `payment:capture:{orderId}:v1` | Payment DB: unique theo `idempotencyKey`; không tạo hai capture cho cùng tác dụng nghiệp vụ | Trả lại `paymentId` và kết quả đã lưu | Từ chối xung đột, không tạo capture mới |
| Giải phóng reservation | Order Service tạo `commandId` | `inventory:release:{orderId}:{reservationId}:v1` | Inventory DB: unique theo `idempotencyKey` và tác dụng release trên reservation | Trả lại kết quả release đã lưu | Từ chối xung đột, không cộng tồn kho lần hai |
| Hoàn tiền | Order Service tạo `commandId` | `payment:refund:{orderId}:{paymentId}:v1` | Payment DB: unique theo `idempotencyKey` và tác dụng refund trên payment | Trả lại `refundId` và kết quả đã lưu | Từ chối xung đột, không tạo refund mới |
| Tạo Outbox event | Service sở hữu trạng thái sinh `eventId` | UUID sinh một lần khi ghi event | Outbox DB: `UNIQUE(eventId)` | Giữ nguyên event khi publish lại | Nếu cùng `eventId` nhưng payload hash khác, cách ly và ghi cảnh báo; không xử lý payload thứ hai |
| Tiếp nhận event | Consumer ghi nhận `eventId` | Dùng nguyên `eventId` từ producer | Inbox/processed-message store: `UNIQUE(consumerName, eventId)` | Bỏ qua xử lý nghiệp vụ đã hoàn tất | Ghi lỗi dữ liệu và không thực hiện tác dụng phụ từ payload xung đột |
| Gửi notification | Notification Service tạo khóa giao nhận | `notification:{eventId}:{notificationType}:{channel}` | Notification DB: unique theo `eventId`, loại thông báo và kênh | Trả lại kết quả đã lưu; không chủ động tạo giao nhận logic mới | Từ chối xung đột và ghi lỗi |

### Giới hạn quan trọng của notification

Unique constraint có thể ngăn tạo giao nhận logic trùng trong cơ sở dữ liệu. Tuy nhiên, nếu tiến trình crash sau khi bên nhận đã gửi thông báo nhưng trước khi lưu kết quả gửi, hệ thống có thể không biết thông báo đã được gửi hay chưa.

Để bảo đảm không gửi trùng ra hệ thống bên ngoài, cần bên nhận hỗ trợ idempotency hoặc cơ chế tra cứu giao nhận. Nếu demo không có bên nhận hỗ trợ tính năng đó, phải ghi rõ giới hạn: hệ thống bảo đảm chống trùng ở lớp xử lý nội bộ, nhưng không tuyên bố bảo đảm exactly-once cho tác dụng phụ bên ngoài.

### Tác động

**Người 2:** triển khai khóa ổn định, payload hash, unique constraint, lưu kết quả và cơ chế inbox/outbox phù hợp.

**Người 3:** kiểm thử retry cùng khóa, payload khác khóa, duplicate message, duplicate publish và retry capture/release/refund.

### Truy vết

- FR-01, FR-03, FR-06, FR-07, FR-08, FR-10, FR-11.
- BR-03, BR-05, BR-08, BR-09, BR-11, BR-12.
- EC-04, EC-07, EC-08, EC-09, EC-10.
- UC-01, UC-02, UC-03, UC-04, UC-05, UC-06.

## D-03 – Chính sách retry và backoff

### Bối cảnh

FR-11, FR-12 và EC-09 yêu cầu retry có giới hạn, backoff, theo dõi trạng thái và cảnh báo. W1 chưa quy định số lần gửi tối đa hoặc khoảng thời gian giữa các lần retry.

### Quyết định đề xuất

Các giá trị sau áp dụng cho cấu hình demo. “Tổng lần gửi” bao gồm lần đầu tiên. Chỉ retry lỗi tạm thời; không retry lỗi dữ liệu hoặc xung đột idempotency.

| Thao tác | Tổng lần gửi đồng bộ tối đa | Backoff giữa các lần | Khi vượt ngưỡng |
|---|---:|---|---|
| Reserve inventory | 5 | 1s, 2s, 4s, 8s | Tra cứu trạng thái reservation. Nếu kết quả chưa xác định, giữ Saga ở trạng thái chờ đối soát; không gọi capture khi chưa xác nhận đã giữ hàng |
| Capture payment | 5 lần gửi cùng khóa | 1s, 2s, 4s, 8s | Tra cứu payment. Nếu vẫn `UNKNOWN`, chuyển Saga sang `RECONCILIATION_REQUIRED`; không tạo khóa capture mới |
| Release reservation | 5 | 1s, 2s, 4s, 8s | Đặt Order ở `COMPENSATION_FAILED`, lưu bước release chưa hoàn tất, cảnh báo và tiếp tục retry nền mỗi 60 giây |
| Refund payment | 5 | 1s, 2s, 4s, 8s | Lưu refund chưa hoàn tất, đặt Saga ở trạng thái compensation lỗi, cảnh báo và tiếp tục retry nền mỗi 60 giây |
| Publish Outbox | 8 | 1s, 2s, 4s, 8s, 16s, 32s, 60s | Giữ event chưa được xác nhận, cảnh báo và tiếp tục thử lại mỗi 60 giây; không xóa event |
| Xử lý notification | 8 | 1s, 2s, 4s, 8s, 16s, 32s, 60s | Đánh dấu `DEAD_LETTER` hoặc trạng thái tương đương, giữ dữ liệu để retry thủ công; không đổi Order status |

Các giá trị trạng thái `RECONCILIATION_REQUIRED` và `DEAD_LETTER` là đề xuất bổ sung cho trạng thái Saga/Notification, không phải trạng thái Order.

### D-03.1. Điều kiện retry

Retry tự động áp dụng cho:
- Timeout hoặc lỗi kết nối.
- HTTP 5xx hoặc lỗi tạm thời tương đương.
- Xung đột khóa/lock có thể thử lại mà không thay đổi ý nghĩa nghiệp vụ.

Không retry tự động:
- Request không hợp lệ.
- Thiếu trường bắt buộc.
- Cùng khóa nhưng payload khác.
- Lỗi nghiệp vụ xác định như không đủ tồn kho.

Sau timeout của một lệnh có thể đã tạo tác dụng phụ, phải truy vấn trạng thái hoặc gửi lại cùng khóa idempotency. Không được suy luận rằng lệnh thất bại chỉ vì không nhận được phản hồi.

### Lý do

Chính sách cụ thể giúp tránh retry vô hạn trong luồng xử lý ban đầu, đồng thời bảo đảm các bước compensation và giao nhận event chưa hoàn tất vẫn có đường phục hồi.

### Tác động

**Người 2:** cần bộ đếm retry bền vững, lịch backoff cấu hình được, trạng thái vượt ngưỡng và cơ chế retry nền.

**Người 3:** cần kiểm thử số lần gửi chính xác, khóa không đổi qua retry, trạng thái khi vượt ngưỡng và khả năng phục hồi sau restart.

### Truy vết

- FR-06, FR-07, FR-08, FR-11, FR-12, FR-13.
- BR-03, BR-07, BR-08, BR-10, BR-11.
- EC-03, EC-04, EC-05, EC-06, EC-08, EC-09, EC-10, EC-11.

## D-04 – Đối soát payment `UNKNOWN`

### Bối cảnh

EC-03 và EC-05 xác định rằng timeout có thể xảy ra dù payment đã được xử lý. BR-07 cấm xem timeout là thất bại. W1 chưa chốt tần suất query, thời gian tối đa và cách xử lý khi kết quả vẫn chưa rõ.

### Quyết định đề xuất

Khi capture timeout:
1. Lưu payment ở trạng thái `UNKNOWN`.
2. Giữ nguyên khóa idempotency của capture.
3. Bắt đầu lịch đối soát bền vững; lưu `unknownSince`, số lần query và thời điểm query kế tiếp.

Lịch đối soát:

| Giai đoạn | Tần suất | Số lần tối đa |
|---|---|---:|
| 60 giây đầu kể từ khi vào `UNKNOWN` | Mỗi 5 giây | 12 |
| Từ sau 60 giây đến hết 10 phút | Mỗi 30 giây | Tối đa 18 lần bổ sung |
| Tổng | Tối đa 10 phút | 30 lần query |

Một query timeout cũng tính là một lần query. Thời gian tính theo UTC và trạng thái lịch đối soát phải được lưu bền vững để tiếp tục sau khi service restart.

### D-04.1. Kết quả đối soát

- Nếu xác minh được `SUCCEEDED`: lưu kết quả và tiếp tục xử lý theo D-01. Nếu Order đã vào nhánh hết hạn, xử lý theo D-05.
- Nếu xác minh được `FAILED`: tiếp tục nhánh thất bại/compensation theo nghiệp vụ.
- Nếu sau 10 phút và 30 lần query vẫn là `UNKNOWN`: chuyển Saga sang `RECONCILIATION_REQUIRED`, phát cảnh báo và yêu cầu can thiệp thủ công.

Khi vượt ngưỡng:
- Không tự đổi payment thành `FAILED`.
- Không giải phóng reservation.
- Không hoàn tiền.
- Không chốt Order thành `EXPIRED` chỉ vì TTL đã qua.
- Nếu Order vẫn `PENDING`, giữ nguyên Order state trong khi chờ kết quả xác minh; lưu rằng TTL đã quá hạn để xử lý lại sau đối soát.
- Sau khi payment được xác minh, đánh giá lại điều kiện TTL bằng D-01. Không tự gia hạn `expireAt`.

### Lý do

Giữ tách biệt giữa “không nhận được kết quả” và “đã xác minh thất bại”, tránh giải phóng hàng hoặc hoàn tiền sai khi payment có thể đã thu tiền.

### Tác động

**Người 2:** cần API/command truy vấn payment theo `paymentId` hoặc khóa capture; lưu bộ đếm và lịch query bền vững.

**Người 3:** cần kiểm thử `UNKNOWN` kéo dài, kết quả thành công ở lần query cuối, kết quả thất bại, restart giữa lịch query và TTL đã quá hạn trong khi payment còn `UNKNOWN`.

### Truy vết

- FR-05, FR-06, FR-11, FR-13, FR-14, FR-15.
- BR-07, BR-10, BR-13.
- EC-03, EC-05, EC-06, EC-11.
- UC-03, UC-04, UC-06.

## D-05 – Hoàn tiền khi Order không thể xác nhận

### Bối cảnh

FR-08 yêu cầu hoàn tiền khi tiền đã thu nhưng đơn không thể hoàn tất và chính sách yêu cầu hủy. EC-11 cũng nêu payment thành công đến muộn sau `EXPIRED` không được làm Order mở lại.

W1 chưa xác định điều kiện kích hoạt refund, khóa refund và cách xử lý refund chưa rõ kết quả.

### Quyết định đề xuất

#### D-05.1. Điều kiện kích hoạt

Chỉ gửi lệnh refund khi Payment Service đã xác minh payment `SUCCEEDED` và Order không thể được xác nhận `CONFIRMED`.

Các trường hợp gồm:
- Order đã vào nhánh hết hạn và payment được xác minh thành công sau đó.
- Order đã `EXPIRED` nhưng phát hiện payment thực tế đã thu tiền.
- Payment đã thu tiền nhưng Order không thể xác nhận do nhánh nghiệp vụ thất bại khác.

Không gửi refund khi payment đang `UNKNOWN`. Phải đối soát trước.

#### D-05.2. Khóa refund

Dùng khóa:

`payment:refund:{orderId}:{paymentId}:v1`

Retry refund phải sử dụng cùng khóa. Không sinh khóa mới chỉ vì timeout hoặc lỗi tạm thời.

#### D-05.3. Kết quả refund

- Chỉ coi refund hoàn tất khi Payment Service xác minh và lưu bền vững kết quả refund thành công.
- Nếu refund timeout, truy vấn refund theo `paymentId` hoặc khóa refund trước khi kết luận.
- Nếu refund vẫn `UNKNOWN`, không tạo refund mới bằng khóa khác.
- Nếu refund lỗi tạm thời, retry theo D-03.
- Nếu vượt ngưỡng retry đồng bộ, ghi nhận compensation chưa hoàn tất, cảnh báo và tiếp tục retry nền mỗi 60 giây.

#### D-05.4. Payment đến muộn sau `EXPIRED`

Order vẫn giữ trạng thái `EXPIRED`; không chuyển lại `PENDING` hoặc `CONFIRMED`.

Saga theo dõi tác vụ refund riêng cho đến khi kết quả được xác minh. Trạng thái refund chưa hoàn tất không được che giấu bằng cách đánh dấu compensation thành công.

### Lý do

Tách trạng thái nghiệp vụ của Order khỏi nghĩa vụ tài chính còn phải xử lý; tránh mở lại đơn đã hết hạn hoặc hoàn tiền trùng.

### Tác động

**Người 2:** cần thao tác refund idempotent và khả năng tra cứu trạng thái refund.

**Người 3:** cần kiểm thử late payment sau `EXPIRED`, refund timeout, refund duplicate, refund `UNKNOWN` và retry sau khi vượt ngưỡng.

### Truy vết

- FR-08, FR-12, FR-13, FR-14, FR-15.
- BR-07, BR-08, BR-13.
- EC-03, EC-05, EC-09, EC-11.
- UC-04, UC-06.

## D-06 – TTL và cơ chế claim Order hết hạn

### Bối cảnh

BR-13.1 đã đề xuất TTL mặc định 15 phút. BR-13.2 và EC-11 yêu cầu claim đơn quá hạn để ngăn nhiều worker cùng xử lý. W1 chưa chốt chu kỳ quét và cách worker phục hồi sau crash.

### Quyết định đề xuất

| Thuộc tính | Giá trị/quy tắc |
|---|---|
| TTL mặc định | 15 phút |
| Công thức | `expireAt = createdAt + TTL` |
| Mốc hết hạn | `currentTime >= expireAt` |
| Chu kỳ quét | Mỗi 5 giây |
| Trạng thái trước claim | `PENDING` |
| Trạng thái sau claim | `EXPIRING` |
| Lease claim | 30 giây |
| Thời gian lưu | `createdAt`, `expireAt`, `claimedAt` dùng UTC |
| Retry tạo đơn | Không tự động gia hạn TTL |

### D-06.1. Quy trình claim

1. Worker tìm Order đang `PENDING` và đã đến `expireAt`.
2. Worker kiểm tra payment; nếu `UNKNOWN`, không claim.
3. Worker thực hiện cập nhật có điều kiện `PENDING -> EXPIRING`.
4. Chỉ worker cập nhật thành công mới được tiếp tục xử lý nhánh hết hạn.
5. Worker lưu `claimToken`, `claimedAt` và `leaseUntil`.
6. Nếu worker crash, worker khác chỉ được tiếp quản khi lease đã hết hạn và cập nhật claim thành công bằng thao tác nguyên tử.
7. Worker tiếp quản phải đọc lại trạng thái Order, Saga, Payment và Reservation trước khi tiếp tục; không lặp tác dụng phụ đã hoàn tất.

Lease 30 giây là giá trị đề xuất cho demo. Nếu một bước xử lý vẫn đang thực hiện khi lease hết hạn, cơ chế claim phải tránh để hai worker đồng thời thực hiện cùng tác dụng phụ; idempotency vẫn là lớp bảo vệ bắt buộc.

### D-06.2. Điều kiện chốt `EXPIRED`

Claim `EXPIRING` không đồng nghĩa đã hết hạn hoàn tất.

Chỉ chốt `EXPIRED` khi:
- Order đã quá hạn.
- Order không được xác nhận `CONFIRMED`.
- Payment không còn `UNKNOWN`.
- Reservation cần giải phóng đã được xác nhận `RELEASED`.
- Refund bắt buộc theo D-05 đã được xác minh hoàn tất.
- Trạng thái Order và event `OrderExpired` được lưu bền vững.

Nếu compensation hoặc refund chưa hoàn tất, tiếp tục theo dõi và retry; không tuyên bố hoàn tất.

### Lý do

Chu kỳ quét và lease có giá trị cụ thể cho phép kiểm thử TTL, tránh nhiều worker cùng claim và phục hồi sau crash.

### Tác động

**Người 2:** cần worker có khả năng quét lại, claim nguyên tử, lease và phục hồi các bước chưa hoàn tất.

**Người 3:** cần kiểm thử hai worker cùng claim, worker crash sau claim, lease hết hạn và TTL khi payment `UNKNOWN`.

### Truy vết

- FR-13, FR-14, FR-15.
- BR-10, BR-12, BR-13.
- EC-06, EC-11.
- UC-06.

## D-07 – API tạo đơn đồng bộ hay bất đồng bộ

### Bối cảnh

UC-01 yêu cầu tạo Order, khởi tạo Saga và trả Order ID/trạng thái ban đầu. W1 chưa chốt API có chờ toàn bộ Saga hoàn tất hay không.

### Các phương án

- **Phương án A:** `POST` chờ cho đến khi đơn `CONFIRMED` hoặc thất bại.
- **Phương án B:** `POST` chỉ xác nhận đã tiếp nhận bền vững; client truy vấn trạng thái riêng.

### Quyết định đề xuất

Chọn **Phương án B – API tạo đơn bất đồng bộ**.

#### D-07.1. Tạo Order

Endpoint đề xuất:

`POST /api/v1/orders`

Header bắt buộc:

`Idempotency-Key: <client-generated-key>`

Request tối thiểu gồm sản phẩm và số lượng. Schema chi tiết sẽ được chốt trong `14-api-event-contract-draft.md`.

Chỉ trả `202 Accepted` sau khi Order, Saga và Outbox record cần thiết đã được lưu bền vững. Response tối thiểu:

```json
{
  "orderId": "<order-id>",
  "status": "PENDING",
  "createdAt": "<UTC timestamp>",
  "expireAt": "<UTC timestamp>",
  "statusUrl": "/api/v1/orders/<order-id>"
}
```

Header đề xuất:
- `Location: /api/v1/orders/{orderId}`
- `Retry-After: 1`

`202 Accepted` chỉ có nghĩa hệ thống đã tiếp nhận yêu cầu; không có nghĩa đơn đã thanh toán hoặc được xác nhận.

#### D-07.2. Theo dõi trạng thái

Endpoint đề xuất:

`GET /api/v1/orders/{orderId}`

GET chỉ đọc trạng thái và không tạo tác dụng phụ nghiệp vụ.

Các mã phản hồi đề xuất:
- `200 OK`: Order tồn tại.
- `404 Not Found`: không tìm thấy Order.
- `400 Bad Request`: request tạo đơn không hợp lệ.
- `409 Conflict`: cùng idempotency key nhưng payload khác.
- `5xx`: lỗi hệ thống khiến yêu cầu chưa thể được xác nhận đã lưu bền vững.

Nếu POST gặp lỗi mạng và client không biết server đã tiếp nhận hay chưa, client phải retry với cùng `Idempotency-Key`.

### Lý do

Client không cần giữ kết nối trong toàn bộ quá trình Saga; phù hợp với xử lý phân tán có retry, timeout và trạng thái trung gian.

### Tác động

**Người 2:** cần API trạng thái Order và lưu kết quả của yêu cầu idempotent.

**Người 3:** cần kiểm thử `202`, duplicate POST, cùng khóa khác payload, GET không tạo side effect và retry sau lỗi mạng.

### Truy vết

- FR-01, FR-02, FR-10, FR-14.
- BR-09, BR-11, BR-12.
- EC-06, EC-07.
- UC-01, UC-05, UC-06.

## D-08 – Fault injection phục vụ demo và kiểm thử

### Bối cảnh

EC-03 đến EC-11 yêu cầu kiểm thử timeout, duplicate message, crash, Outbox publisher lỗi, compensation lỗi và Notification Service ngừng hoạt động. W1 chưa định nghĩa một quy ước thống nhất để tái tạo các tình huống này.

### Quyết định đề xuất

Fault injection chỉ hoạt động trong profile `demo` hoặc `test`. Mặc định:

`FAULT_INJECTION_ENABLED=false`

Không bật cơ chế này trong môi trường production.

### D-08.1. Các chế độ lỗi

| Chế độ | Ý nghĩa |
|---|---|
| `NONE` | Không chèn lỗi |
| `FAIL` | Trả lỗi tạm thời tại operation được chọn |
| `TIMEOUT` | Trì hoãn phản hồi quá thời gian chờ của client; side effect có thể đã commit |
| `DUPLICATE` | Phát lại cùng command/event với cùng định danh |
| `CRASH` | Dừng xử lý tại điểm crash được cấu hình |

### D-08.2. Cấu hình fault

Cấu hình test đề xuất có các trường:

| Trường | Ý nghĩa |
|---|---|
| `service` | `inventory`, `payment` hoặc `notification` |
| `mode` | Một trong các chế độ D-08.1 |
| `targetOperation` | Tên command hoặc consumer cần chèn lỗi |
| `occurrences` | Số lần chèn lỗi còn lại; số nguyên dương |
| `delayMs` | Độ trễ cho `TIMEOUT`; mặc định 3000 ms |
| `duplicateCount` | Tổng số bản sao được phát; mặc định 2 |
| `crashPoint` | `BEFORE_COMMIT`, `AFTER_COMMIT_BEFORE_RESPONSE` hoặc `AFTER_PUBLISH_BEFORE_MARK` |

Cấu hình phải tác động đúng operation được chỉ định và giảm `occurrences` sau mỗi lần áp dụng.

### D-08.3. Quy tắc từng chế độ

- `FAIL`: phải xác định rõ lỗi xảy ra trước hay sau side effect. Mặc định lỗi test xảy ra trước side effect.
- `TIMEOUT`: không được giả định side effect thất bại; test phải kiểm tra query/retry idempotent.
- `DUPLICATE`: giữ nguyên `eventId` hoặc `commandId` tương ứng; không sinh định danh mới để mô phỏng cùng một thông điệp.
- `CRASH`: tiến trình phải dừng tại điểm cấu hình; test harness khởi động lại service để kiểm tra recovery.
- Sau mỗi test, cấu hình fault phải được reset để không ảnh hưởng test kế tiếp.

Endpoint điều khiển fault, nếu được triển khai, là API nội bộ dành riêng cho profile test/demo; không phải API nghiệp vụ công khai. Người 2 xác nhận cách triển khai cụ thể.

### Lý do

Cho phép Người 3 tái tạo lỗi có tính lặp lại và kiểm tra idempotency/recovery mà không phụ thuộc vào thao tác thủ công hoặc lỗi ngẫu nhiên
