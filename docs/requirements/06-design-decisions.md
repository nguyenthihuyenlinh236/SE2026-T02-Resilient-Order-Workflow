# 06 – Design Decisions

## 1. Quy ước bắt buộc

### 1.1. Quy tắc nền

- `UNKNOWN` không đồng nghĩa với `FAILED`. Timeout không phải bằng chứng thanh toán thất bại.
- `eventId` định danh một sự kiện; `commandId`/`idempotencyKey` định danh một tác dụng nghiệp vụ. Không dùng thay thế cho nhau.
- `EXPIRED` là trạng thái nghiệp vụ, không phải thao tác xóa bản ghi Order.
- Payment thành công đến muộn sau khi Order đã `EXPIRED` không làm Order được mở lại; hệ thống phải đối soát và hoàn tiền nếu tiền đã được thu.
- Một Order không được vừa `CONFIRMED` vừa `EXPIRED`.
- Không giải phóng reservation hoặc hoàn tiền khi kết quả payment còn `UNKNOWN`.
- Các thao tác có tác dụng phụ phải có khóa idempotency ổn định và lưu kết quả xử lý.
- Các thời điểm nghiệp vụ lưu theo UTC.

### 1.2. Thuật ngữ trạng thái dùng trong file này

Các tên dưới đây là **đề xuất**; tên cuối cùng được chốt trong `09-state-machine.md`.

| Loại trạng thái | Giá trị dùng trong file này |
|---|---|
| Order | `PENDING`, `EXPIRING`, `COMPENSATING`, `CONFIRMED`, `FAILED`, `EXPIRED` |
| Payment | `SUCCEEDED`, `FAILED`, `UNKNOWN`; "chưa thanh toán" nghĩa là chưa có bản ghi capture |
| Reservation | `RESERVED`, `RELEASED`, `REJECTED`; "chưa xác minh" nghĩa là kết quả reserve chưa rõ |
| Saga (bổ sung) | `COMPENSATION_FAILED`, `RECONCILIATION_REQUIRED` |
| Notification/Outbox | Có trạng thái "chờ gửi lại" (tên chốt ở `09`) |

`EXPIRING` là trạng thái trung gian, không phải trạng thái cuối. Nó đánh dấu nhánh hết hạn đã giành quyền xử lý Order.

---

## 2. Các quyết định thiết kế

### D-01 – Phân xử cạnh tranh giữa xác nhận thanh toán và hết hạn

#### Bối cảnh

W1 (FR-09, BR-13.5) dùng cụm "payment thành công hợp lệ trước khi nhánh hết hạn được chốt" nhưng chưa nói rõ cách phân xử khi callback payment và TTL Worker chạy gần như đồng thời. EC-11 yêu cầu ngăn hai worker cùng xử lý một đơn và không cho phép một đơn vừa `CONFIRMED` vừa `EXPIRED`.

#### Giải pháp: Cập nhật trạng thái Order có điều kiện và nguyên tử; nhánh nào chuyển Order khỏi `PENDING` thành công trước thì thắng, nhánh còn lại không được ghi đè.

**D-01.1. Xác nhận đơn.** Chỉ được chuyển `PENDING -> CONFIRMED` khi đồng thời thỏa mãn:

1. Reservation của chính Order đó ở trạng thái `RESERVED`.
2. Payment ở trạng thái `SUCCEEDED` và hợp lệ: `paymentId` thuộc đúng Order, số tiền khớp tổng đơn (nếu mô hình dữ liệu có giá), kết quả đã được Payment Service lưu bền vững; không phải `UNKNOWN`.
3. Order đang `PENDING` và thời điểm hiện tại theo đồng hồ DB nhỏ hơn `expireAt`.

**D-01.2. Claim hết hạn.** TTL Worker chỉ claim Order `PENDING` khi payment không ở `UNKNOWN`:

Chỉ worker cập nhật thành công (1 dòng) mới được xử lý nhánh hết hạn. Toàn bộ so sánh thời gian dùng **một nguồn thời gian duy nhất là đồng hồ DB (UTC)**, không dùng đồng hồ của từng service, để tránh lệch giờ.

**D-01.3. Xử lý bên thua và các tình huống cạnh tranh.**

| Tình huống | Quy tắc bắt buộc |
|---|---|
| `PENDING -> CONFIRMED` thành công trước | TTL Worker không được chuyển Order sang `EXPIRING` hoặc `EXPIRED`. |
| `PENDING -> EXPIRING` thành công trước | Callback payment không được chuyển Order thành `CONFIRMED`. |
| `PENDING -> COMPENSATING` (nhánh payment thất bại) thành công trước | TTL Worker không claim; nhánh thất bại tiếp tục. |
| Payment thành công sau khi Order đã vào nhánh hết hạn | Đối soát payment; nếu tiền đã thu thì refund theo D-05. Không mở lại Order. |
| Payment đang `UNKNOWN` khi đến hạn | Không claim, không giải phóng reservation, không hoàn tiền cho đến khi xác minh. |
| Payment chuyển sang `UNKNOWN` sau khi Order đã `EXPIRING` | Đối soát theo D-04; không release, không refund, không chốt `EXPIRED` cho đến khi xác minh. |
| Hai cập nhật xảy ra đồng thời | DB chỉ cho phép một cập nhật có điều kiện từ `PENDING` thành công; bên thua đọc lại trạng thái. |

Claim `EXPIRING` không phải bằng chứng reservation đã được giải phóng hoặc refund đã hoàn tất.

**D-01.4. Hệ quả cần chấp nhận (quy tắc nghiêm ngặt).** Quyết định này ưu tiên thứ tự chuyển trạng thái nguyên tử khỏi `PENDING`, không ưu tiên thời điểm callback đến hay timestamp của payment. Hệ quả:

- Nếu payment thực tế hoàn tất trước `expireAt` nhưng chỉ được **xác minh** sau `expireAt` (ví dụ do `UNKNOWN` kéo dài), Order **không** được `CONFIRMED`. Nhánh hết hạn claim Order và tiền được hoàn theo D-05.
- Phương án thay thế (cho phép xác nhận dựa trên `paymentCompletedAt < expireAt`, cần giả định A-04) cho kết quả thân thiện với khách hơn nhưng phụ thuộc timestamp bên ngoài. Xem câu hỏi mở **Q-01**.

**D-01.5. Lệnh đang xử lý khi Order rời `PENDING`.**

- Khi Order không còn `PENDING`, orchestrator **không được gửi lệnh tiến mới** (reserve/capture). Trước mỗi lệnh tiến phải kiểm tra Order còn `PENDING`.
- Lệnh đã gửi từ trước vẫn có thể hoàn tất (reserve đến muộn, capture đến muộn). Xử lý: reservation bằng release, tiền bằng refund (D-05).
- Inventory Service phải ghi nhận lệnh release theo `orderId`; reserve đến muộn sau release của cùng `orderId` phải bị từ chối hoặc vô hiệu, để không rò reservation (giả định A-13).
- Trước khi release, nếu kết quả reserve chưa rõ, phải tra cứu theo khóa reserve (EC-04) rồi mới gửi release.

#### Lý do

Ngăn hai nhánh cùng chốt kết quả cho một Order, có cơ chế phân xử cụ thể, kiểm thử được. Phù hợp BR-07, BR-11, BR-12, BR-13 và EC-11.

#### Tác động

- **Người 2:** cập nhật Order có điều kiện và kiểm tra số dòng; lưu `expireAt`, `EXPIRING`, claim token, lease; dùng đồng hồ DB; kiểm tra Order còn `PENDING` trước mỗi lệnh tiến; chỉ phát sự kiện sau khi trạng thái đã lưu bền vững.
- **Người 3:** kiểm thử xác nhận thắng trước hạn; claim thắng và callback đến sau; hai cập nhật đồng thời; payment `UNKNOWN` lúc hết hạn; payment đến muộn sau `EXPIRED`; payment hoàn tất trước hạn nhưng xác minh sau hạn; reserve đến muộn sau release.

#### Truy vết

FR-09, FR-13, FR-15 · BR-01, BR-07, BR-10, BR-12, BR-13 · EC-03, EC-04, EC-05, EC-06, EC-11 · UC-03, UC-04, UC-06.

---

### D-02 – Quy ước idempotency key cho lệnh và sự kiện

#### Bối cảnh

BR-03, BR-08, BR-11 yêu cầu chống tác dụng phụ lặp, phân biệt định danh sự kiện với định danh tác dụng nghiệp vụ, và từ chối cùng khóa nhưng khác payload. W1 chưa quy định cách sinh khóa, nơi lưu và hành vi khi trùng.

#### Quyết định đề xuất

- Mỗi tác dụng nghiệp vụ có một `commandId`/`idempotencyKey` ổn định; retry cùng tác dụng dùng lại đúng khóa.
- `eventId` sinh một lần khi ghi bản ghi Outbox; publish lại giữ nguyên `eventId`.
- Nơi thực hiện tác dụng nghiệp vụ lưu khóa, payload hash và kết quả xử lý.
- Cùng khóa, cùng payload: trả lại kết quả đã lưu, không lặp tác dụng phụ.
- Cùng khóa, khác payload: trả `409 IDEMPOTENCY_KEY_CONFLICT`, không thực hiện tác dụng phụ mới.
- Áp dụng unique constraint tại nơi lưu dữ liệu, không chỉ kiểm tra bằng code trước khi ghi.
- Hậu tố `v1` chỉ tăng khi có một **tác dụng nghiệp vụ mới có chủ đích**. Trong demo, mọi khóa luôn là `v1`; retry do timeout/lỗi tạm thời **không** được đổi phiên bản.
- Thiếu header `Idempotency-Key` khi tạo Order: trả `400 Bad Request`.

#### Bảng quy tắc khóa

| Lệnh/sự kiện | Bên sinh khóa | Công thức / quy tắc | Nơi lưu và ràng buộc duy nhất | Cùng khóa, cùng payload | Cùng khóa, khác payload |
|---|---|---|---|---|---|
| Tạo Order | Client | UUID ngẫu nhiên cho một yêu cầu đặt hàng logic; retry gửi lại đúng khóa | Order DB: `UNIQUE(customerId, idempotencyKey)`*; lưu payload hash và response ban đầu | Trả cùng `orderId` và response đã lưu | `409 IDEMPOTENCY_KEY_CONFLICT` |
| Giữ hàng | Order Service | `reserve:{orderId}:v1` | Inventory DB: unique theo `idempotencyKey`; unique reservation theo Order | Trả lại reservation đã lưu | Từ chối, không đổi tồn kho |
| Thu tiền | Order Service | `payment:capture:{orderId}:v1` | Payment DB: unique theo `idempotencyKey`; không có hai capture cho cùng tác dụng | Trả lại `paymentId` và kết quả đã lưu | Từ chối, không tạo capture mới |
| Giải phóng reservation | Order Service | `inventory:release:{orderId}:{reservationId}:v1` | Inventory DB: unique theo `idempotencyKey` và tác dụng release trên reservation | Trả lại kết quả release đã lưu | Từ chối, không cộng tồn kho lần hai |
| Hoàn tiền | Order Service | `payment:refund:{orderId}:{paymentId}:v1` | Payment DB: unique theo `idempotencyKey` và tác dụng refund trên payment | Trả lại `refundId` và kết quả đã lưu | Từ chối, không tạo refund mới |
| Tạo Outbox event | Service sở hữu trạng thái | UUID sinh một lần khi ghi event | Outbox DB: `UNIQUE(eventId)` | Giữ nguyên event khi publish lại | Cùng `eventId` nhưng payload hash khác: cách ly và cảnh báo, không xử lý payload thứ hai |
| Tiếp nhận event | Consumer | Dùng nguyên `eventId` từ producer | Inbox/processed-message store: `UNIQUE(consumerName, eventId)` | Bỏ qua xử lý nghiệp vụ đã hoàn tất | Ghi lỗi dữ liệu, không thực hiện tác dụng phụ từ payload xung đột |
| Gửi notification | Notification Service | `notification:{eventId}:{notificationType}:{channel}` | Notification DB: unique theo `eventId`, loại và kênh | Trả lại kết quả đã lưu, không tạo giao nhận mới | Từ chối xung đột và ghi lỗi |

\* Nếu hệ thống không có định danh khách hàng/đăng nhập, dùng `clientId` hoặc `UNIQUE(idempotencyKey)`; Người 2 chốt (A-14). Khi trùng khóa, response trả về vẫn là response ban đầu (`202`); client dùng `GET` để lấy trạng thái hiện tại.

#### Giới hạn quan trọng của notification

Unique constraint chỉ ngăn tạo giao nhận logic trùng trong DB. Nếu tiến trình crash sau khi bên nhận đã gửi thông báo nhưng trước khi lưu kết quả, hệ thống có thể không biết thông báo đã gửi hay chưa. Để bảo đảm không gửi trùng ra hệ thống bên ngoài cần bên nhận hỗ trợ idempotency hoặc tra cứu giao nhận. Nếu demo không có, phải ghi rõ: hệ thống chống trùng ở lớp xử lý nội bộ, **không tuyên bố exactly-once** cho tác dụng phụ bên ngoài.

#### Tác động

- **Người 2:** khóa ổn định, payload hash, unique constraint, lưu kết quả, inbox/outbox.
- **Người 3:** retry cùng khóa, cùng khóa khác payload, duplicate message, duplicate publish, retry capture/release/refund, thiếu header khóa.

#### Truy vết

FR-01, FR-03, FR-06, FR-07, FR-08, FR-10, FR-11 · BR-03, BR-05, BR-08, BR-09, BR-11, BR-12 · EC-04, EC-07, EC-08, EC-09, EC-10 · UC-01 đến UC-06.

---

### D-03 – Chính sách retry và backoff

#### Bối cảnh

FR-11, FR-12, EC-09 yêu cầu retry có giới hạn, backoff, theo dõi trạng thái và cảnh báo. W1 chưa có số lần gửi tối đa hay khoảng cách giữa các lần.

#### Quyết định đề xuất

Giá trị áp dụng cho cấu hình demo, **cấu hình được** (xem D-08.4). "Tổng lần gửi" tính cả lần đầu. Chỉ retry lỗi tạm thời.

| Thao tác | Tổng lần gửi tối đa | Backoff giữa các lần | Khi vượt ngưỡng |
|---|---|---|---|
| Reserve inventory | 5 | 1s, 2s, 4s, 8s | Tra cứu reservation theo khóa reserve. Nếu vẫn chưa rõ: Saga chờ xác minh, truy vấn nền mỗi 30 giây, **không gọi capture** khi chưa xác nhận `RESERVED`. Nếu Order hết hạn trong lúc chờ thì xử lý theo D-01.5 và D-06 |
| Capture payment | 5 lần gửi cùng khóa | 1s, 2s, 4s, 8s | Tra cứu payment. Nếu vẫn `UNKNOWN`: payment ở `UNKNOWN`, **bắt đầu lịch đối soát D-04**; không tạo khóa capture mới. `RECONCILIATION_REQUIRED` chỉ xảy ra khi D-04 cạn |
| Release reservation | 5 | 1s, 2s, 4s, 8s | Lưu bước release chưa hoàn tất, cảnh báo, retry nền mỗi 60 giây (xem D-03.2 về trạng thái Order) |
| Refund payment | 5 | 1s, 2s, 4s, 8s | Lưu refund chưa hoàn tất, Saga ở trạng thái compensation lỗi, cảnh báo, retry nền mỗi 60 giây |
| Publish Outbox | 8 | 1s, 2s, 4s, 8s, 16s, 32s, 60s | Giữ event chưa xác nhận, cảnh báo, tiếp tục thử mỗi 60 giây; không xóa event |
| Xử lý notification | 8 | 1s, 2s, 4s, 8s, 16s, 32s, 60s | Giữ ở trạng thái chờ gửi lại, tiếp tục retry nền mỗi 60 giây để tự phục hồi khi Notification Service sống lại (EC-10), cảnh báo; không đổi Order status. `DEAD_LETTER` không dùng trong demo |

**D-03.1. Điều kiện retry.**

- Retry tự động: timeout, lỗi kết nối, HTTP 5xx hoặc lỗi tạm thời tương đương, xung đột lock có thể thử lại mà không đổi ý nghĩa nghiệp vụ.
- Không retry tự động: request không hợp lệ, thiếu trường bắt buộc, cùng khóa khác payload, lỗi nghiệp vụ xác định (ví dụ không đủ tồn kho).
- Sau timeout của lệnh có thể đã tạo tác dụng phụ: truy vấn trạng thái hoặc gửi lại **cùng khóa**. Không suy luận lệnh thất bại chỉ vì không có phản hồi.

**D-03.2. Trạng thái Order khi compensation lỗi.**

- Nhánh thất bại (payment `FAILED`): Order giữ `COMPENSATING`; nếu release/refund gặp lỗi tạm thời vượt ngưỡng retry, Saga chuyển sang `COMPENSATION_FAILED` và tiếp tục retry nền.
- Nhánh hết hạn: Order **giữ `EXPIRING`** (để không mất thông tin đang xử lý hết hạn); trạng thái lỗi nằm ở Saga (`COMPENSATION_FAILED`). Order chỉ sang `EXPIRED` khi đủ điều kiện D-06.2.

**D-03.3. Độ bền.** Bộ đếm retry, thời điểm thử kế tiếp và trạng thái vượt ngưỡng phải lưu bền vững để tiếp tục sau khi service restart.

#### Tác động

- **Người 2:** bộ đếm retry bền vững, lịch backoff cấu hình được, retry nền.
- **Người 3:** kiểm thử số lần gửi chính xác, khóa không đổi qua retry, trạng thái khi vượt ngưỡng, phục hồi sau restart.

#### Truy vết

FR-06, FR-07, FR-08, FR-11, FR-12, FR-13 · BR-03, BR-07, BR-08, BR-10, BR-11 · EC-03, EC-04, EC-05, EC-06, EC-08, EC-09, EC-10, EC-11.

---

### D-04 – Đối soát payment `UNKNOWN`

#### Bối cảnh

EC-03, EC-05 cho thấy timeout có thể xảy ra dù payment đã được xử lý; BR-07 cấm xem timeout là thất bại. W1 chưa chốt tần suất query, thời gian tối đa và cách xử lý khi vẫn chưa rõ.

#### Quyết định đề xuất

Khi capture hết 5 lần gửi (D-03) mà vẫn timeout:

1. Lưu payment ở `UNKNOWN`, giữ nguyên khóa idempotency của capture.
2. Bắt đầu lịch đối soát bền vững; lưu `unknownSince`, số lần query và thời điểm query kế tiếp.
3. Đối soát chỉ **truy vấn**, không gửi lại capture bằng khóa mới.

| Giai đoạn | Tần suất | Số lần tối đa |
|---|---|---|
| 60 giây đầu kể từ khi vào `UNKNOWN` | Mỗi 5 giây | 12 |
| Sau 60 giây đến hết 10 phút | Mỗi 30 giây | 18 |
| **Tổng** | Tối đa 10 phút | **30 lần query** |

Query timeout cũng tính là một lần. Lịch tính theo UTC, lưu bền vững và tiếp tục sau restart.

**D-04.1. Kết quả đối soát.**

- Xác minh được `SUCCEEDED`: lưu kết quả và xử lý theo D-01 (xem D-04.3).
- Xác minh được `FAILED`: tiếp tục nhánh thất bại/compensation.
- Hết 10 phút và 30 lần query vẫn `UNKNOWN`: Saga chuyển `RECONCILIATION_REQUIRED`, phát cảnh báo.

**D-04.2. Khi đã `RECONCILIATION_REQUIRED`.**

- Không tự đổi payment thành `FAILED`; không release reservation; không hoàn tiền; không chốt `EXPIRED` chỉ vì TTL đã qua.
- Tiếp tục **truy vấn nền mỗi 5 phút** (không tính vào 30 lần) và cho phép xử lý thủ công (xem Q-02).
- Ghi nhận đánh đổi: reservation tiếp tục bị giữ cho đến khi xác minh. Đây là hệ quả có chủ đích của BR-07 (không giải phóng mù quáng) và phải được cảnh báo.
- Không tự gia hạn `expireAt`.

**D-04.3. Quan hệ với TTL.** Nếu Order còn `PENDING` và đã quá `expireAt` trong lúc payment `UNKNOWN`, Order giữ `PENDING` (không claim). Sau khi xác minh:

- `SUCCEEDED` khi `now() >= expireAt`: Order Service không `CONFIRMED` (D-01.1); TTL Worker claim như thường; tiền được hoàn theo D-05 (D-01.4).
- `FAILED`: nhánh nào chuyển Order khỏi `PENDING` trước thì thắng (D-01.3).

#### Tác động

- **Người 2:** API/command truy vấn payment theo `paymentId` hoặc khóa capture; lưu bộ đếm và lịch query bền vững; truy vấn nền sau khi cạn.
- **Người 3:** `UNKNOWN` kéo dài; thành công ở lần query cuối; thất bại; restart giữa lịch query; TTL đã quá hạn khi payment còn `UNKNOWN`; xác minh không có release/refund trong lúc `UNKNOWN`.

#### Truy vết

FR-05, FR-06, FR-11, FR-13, FR-14, FR-15 · BR-07, BR-10, BR-13 · EC-03, EC-05, EC-06, EC-11 · UC-03, UC-04, UC-06.

---

### D-05 – Hoàn tiền khi Order không thể xác nhận

#### Bối cảnh

FR-08 yêu cầu hoàn tiền khi tiền đã thu nhưng đơn không thể hoàn tất. EC-11 nêu payment thành công đến muộn sau `EXPIRED` không làm Order mở lại. W1 chưa xác định điều kiện kích hoạt, khóa refund và cách xử lý refund chưa rõ kết quả.

#### Quyết định đề xuất

**D-05.1. Điều kiện kích hoạt.** Chỉ gửi lệnh refund khi Payment Service đã xác minh payment `SUCCEEDED` **và** Order không thể được `CONFIRMED`, gồm:

- Order đã vào nhánh hết hạn và payment được xác minh thành công sau đó.
- Order đã `EXPIRED` nhưng phát hiện payment thực tế đã thu tiền.
- Payment đã thu tiền nhưng Order không thể xác nhận do nhánh nghiệp vụ khác (ví dụ thua cập nhật có điều kiện ở D-01).

Không gửi refund khi payment đang `UNKNOWN`; phải đối soát trước. Demo chỉ hoàn **toàn bộ** số tiền đã thu (hoàn một phần ngoài phạm vi).

**D-05.2. Khóa refund.** `payment:refund:{orderId}:{paymentId}:v1`. Retry dùng cùng khóa; không sinh khóa mới vì timeout hoặc lỗi tạm thời.

**D-05.3. Kết quả refund.**

- Chỉ coi refund hoàn tất khi Payment Service xác minh và lưu bền vững refund thành công.
- Refund timeout: truy vấn theo `paymentId` hoặc khóa refund theo lịch giống D-04 trước khi kết luận.
- Refund vẫn `UNKNOWN`: không tạo refund mới bằng khóa khác.
- Lỗi tạm thời: retry theo D-03; vượt ngưỡng thì ghi nhận compensation chưa hoàn tất, cảnh báo, retry nền mỗi 60 giây.

**D-05.4. Hai trường hợp phân biệt.**

| Trường hợp | Quy tắc |
|---|---|
| Payment đã được xác minh `SUCCEEDED` khi Order đang `EXPIRING` | Refund phải hoàn tất và được xác minh **trước khi** chốt `EXPIRED` (D-06.2). |
| Payment thành công chỉ được phát hiện sau khi Order đã `EXPIRED` | Order giữ `EXPIRED`, không mở lại. Saga theo dõi tác vụ refund riêng đến khi xác minh xong; trạng thái refund chưa xong không được che bằng cách đánh dấu compensation thành công. |

#### Tác động

- **Người 2:** refund idempotent; tra cứu trạng thái refund; theo dõi refund chưa hoàn tất.
- **Người 3:** late payment sau `EXPIRED`; refund timeout, duplicate, `UNKNOWN`; retry sau khi vượt ngưỡng.

#### Truy vết

FR-08, FR-12, FR-13, FR-14, FR-15 · BR-07, BR-08, BR-13 · EC-03, EC-05, EC-09, EC-11 · UC-04, UC-06.

---

### D-06 – TTL và cơ chế claim Order hết hạn

#### Bối cảnh

BR-13.1 đã đề xuất TTL 15 phút. BR-13.2 và EC-11 yêu cầu claim đơn quá hạn để ngăn nhiều worker cùng xử lý. W1 chưa chốt chu kỳ quét và cách phục hồi sau crash.

#### Quyết định đề xuất

| Thuộc tính | Giá trị / quy tắc |
|---|---|
| TTL mặc định | 15 phút (cấu hình được) |
| Công thức | `expireAt = createdAt + TTL` |
| Mốc hết hạn | `now() >= expireAt` (đồng hồ DB, UTC) |
| Chu kỳ quét | Mỗi 5 giây |
| Trạng thái trước / sau claim | `PENDING` / `EXPIRING` |
| Lease claim | 30 giây, gia hạn khi worker còn đang xử lý |
| Dữ liệu lưu | `createdAt`, `expireAt`, `claimedAt`, `claimToken`, `leaseUntil` (UTC) |
| Retry tạo đơn | Không tự động gia hạn TTL |

**D-06.1. Quy trình claim.**

1. Worker tìm Order `PENDING` đã đến `expireAt`.
2. Nếu payment `UNKNOWN`: không claim. Nếu **reservation chưa xác minh**: vẫn được claim, nhưng trước khi release phải tra cứu theo khóa reserve (D-01.5, EC-04).
3. Worker thực hiện cập nhật có điều kiện `PENDING -> EXPIRING` như D-01.2.
4. Chỉ worker cập nhật thành công mới xử lý tiếp. Các bước compensation chạy như bước Saga bền vững (không phụ thuộc bộ nhớ của worker).
5. Trong lúc xử lý, worker gia hạn `leaseUntil` (ví dụ mỗi 10 giây).
6. Worker cũng quét Order `EXPIRING` có `leaseUntil < now()`. Worker tiếp quản chỉ khi claim lại thành công bằng thao tác nguyên tử, sau đó đọc lại trạng thái Order, Saga, Payment, Reservation và không lặp tác dụng phụ đã hoàn tất.
7. Idempotency vẫn là lớp bảo vệ bắt buộc nếu hai worker tình cờ thực hiện cùng một bước.

**D-06.2. Điều kiện chốt `EXPIRED`.** Chỉ chốt khi đồng thời:

1. Order đã quá hạn và không được `CONFIRMED`.
2. Payment không còn `UNKNOWN`.
3. Reservation cần giải phóng đã được xác nhận `RELEASED`.
4. Nếu payment đã xác minh `SUCCEEDED` thì refund theo D-05 đã được xác minh hoàn tất.
5. Trạng thái Order và event `OrderExpired` được lưu bền vững (cùng transaction với Outbox).

Nếu chưa đủ, Order giữ `EXPIRING`, tiếp tục theo dõi và retry; không tuyên bố hoàn tất.

#### Tác động

- **Người 2:** worker quét lại, claim nguyên tử, lease có gia hạn, phục hồi các bước chưa hoàn tất.
- **Người 3:** hai worker cùng claim; worker crash sau claim; lease hết hạn và tiếp quản; TTL khi payment `UNKNOWN`; TTL khi reserve chưa xác minh.

#### Truy vết

FR-13, FR-14, FR-15 · BR-10, BR-12, BR-13 · EC-04, EC-06, EC-11 · UC-06.

---

### D-07 – API tạo đơn đồng bộ hay bất đồng bộ

#### Bối cảnh

UC-01 yêu cầu tạo Order, khởi tạo Saga và trả Order ID/trạng thái ban đầu. W1 chưa chốt API có chờ toàn bộ Saga hay không.

#### Quyết định đề xuất: `POST` chỉ xác nhận đã tiếp nhận bền vững; client truy vấn trạng thái riêng.

**D-07.1. Tạo Order.** `POST /api/v1/orders`

- Header bắt buộc: `Idempotency-Key: <client-generated-key>`. Thiếu header: `400`.
- Request tối thiểu gồm sản phẩm và số lượng; schema chi tiết ở `14-api-event-contract-draft.md`.
- Chỉ trả `202 Accepted` **sau khi** Order, Saga và Outbox record cần thiết đã được lưu bền vững.

```json
{
  "orderId": "<order-id>",
  "status": "PENDING",
  "createdAt": "<UTC timestamp>",
  "expireAt": "<UTC timestamp>",
  "statusUrl": "/api/v1/orders/<order-id>"
}
```

Header đề xuất: `Location: /api/v1/orders/{orderId}`, `Retry-After: 1`.

`202` chỉ nghĩa là hệ thống đã tiếp nhận yêu cầu, **không** nghĩa là đã thanh toán hay xác nhận.

**D-07.2. Theo dõi trạng thái.** `GET /api/v1/orders/{orderId}` chỉ đọc, không tạo tác dụng phụ nghiệp vụ.

| Mã | Ý nghĩa |
|---|---|
| `200 OK` | Order tồn tại |
| `202 Accepted` | Tạo đơn đã được tiếp nhận (cả khi gửi lại cùng khóa, cùng payload) |
| `400 Bad Request` | Request không hợp lệ hoặc thiếu `Idempotency-Key` |
| `404 Not Found` | Không tìm thấy Order |
| `409 Conflict` | Cùng `Idempotency-Key` nhưng payload khác |
| `5xx` | Lỗi hệ thống, yêu cầu chưa được xác nhận đã lưu bền vững |

Nếu `POST` gặp lỗi mạng và client không biết server đã tiếp nhận hay chưa, client phải retry với **cùng** `Idempotency-Key`.

#### Tác động

- **Người 2:** API trạng thái Order; lưu kết quả của yêu cầu idempotent.
- **Người 3:** kiểm thử `202`, duplicate POST, cùng khóa khác payload, thiếu header, `GET` không có side effect, retry sau lỗi mạng.

#### Truy vết

FR-01, FR-02, FR-10, FR-14 · BR-09, BR-11, BR-12 · EC-06, EC-07 · UC-01, UC-05, UC-06.

---

### D-08 – Fault injection phục vụ demo và kiểm thử

#### Bối cảnh

EC-03 đến EC-11 yêu cầu kiểm thử timeout, duplicate message, crash, Outbox publisher lỗi, compensation lỗi và Notification ngừng hoạt động. W1 chưa có quy ước thống nhất để tái tạo các tình huống này.

#### Quyết định đề xuất

Fault injection chỉ hoạt động trong profile demo/test. Mặc định `FAULT_INJECTION_ENABLED=false`; không bật ở môi trường production.

**D-08.1. Các chế độ lỗi.**

| Chế độ | Ý nghĩa |
|---|---|
| `NONE` | Không chèn lỗi |
| `FAIL` | Trả lỗi tạm thời tại operation được chọn |
| `TIMEOUT` | Trì hoãn phản hồi quá thời gian chờ của client |
| `DUPLICATE` | Phát lại cùng command/event với cùng định danh |
| `CRASH` | Dừng xử lý tại điểm crash được cấu hình |

**D-08.2. Cấu hình fault.**

| Trường | Ý nghĩa |
|---|---|
| `service` | `order`, `inventory`, `payment` hoặc `notification` (`order` gồm Outbox publisher và TTL Worker) |
| `mode` | Một trong các chế độ D-08.1 |
| `targetOperation` | Tên command hoặc consumer cần chèn lỗi |
| `occurrences` | Số lần chèn lỗi còn lại, số nguyên dương; giảm sau mỗi lần áp dụng |
| `effectTiming` | `BEFORE_EFFECT` hoặc `AFTER_EFFECT` (side effect đã commit hay chưa) |
| `delayMs` | Độ trễ cho `TIMEOUT`; mặc định 3000 ms |
| `duplicateCount` | Tổng số bản sao được phát; mặc định 2 |
| `crashPoint` | `BEFORE_COMMIT`, `AFTER_COMMIT_BEFORE_RESPONSE`, `AFTER_PUBLISH_BEFORE_MARK`, `AFTER_CLAIM_BEFORE_RELEASE` |

**D-08.3. Quy tắc từng chế độ.**

- `FAIL`: mặc định `effectTiming=BEFORE_EFFECT` (lỗi xảy ra trước side effect).
- `TIMEOUT`: mặc định `effectTiming=AFTER_EFFECT` (side effect **đã commit** nhưng phản hồi bị trễ), vì đây là tình huống nguy hiểm của EC-03/EC-05. Test phải kiểm tra query hoặc retry idempotent.
- `DUPLICATE`: giữ nguyên `eventId`/`commandId`; không sinh định danh mới để mô phỏng cùng một thông điệp.
- `CRASH`: tiến trình dừng đúng điểm cấu hình; test harness khởi động lại service để kiểm tra recovery.
- Sau mỗi test, cấu hình fault phải được reset để không ảnh hưởng test kế tiếp.

**D-08.4. Cấu hình rút ngắn thời gian cho test.** TTL, chu kỳ quét, lease, backoff và lịch đối soát phải cấu hình được (ví dụ TTL 30 giây, backoff 100 ms) hoặc dùng đồng hồ giả, để kiểm thử không phải chờ 15 phút thật. Giá trị mặc định ở D-03, D-04, D-06 là giá trị demo, không hard-code.

Endpoint điều khiển fault (nếu có) là API nội bộ của profile test/demo, không phải API nghiệp vụ công khai; Người 2 xác nhận cách triển khai.

#### Tác động

- **Người 2:** cơ chế fault injectable; dừng/restart service tại điểm xác định; cấu hình thời gian rút ngắn.
- **Người 3:** thiết lập/reset fault tự động; xác minh side effect trước/sau crash; chạy lại cùng kịch bản nhiều lần.

#### Truy vết

FR-11, FR-12, FR-13, FR-14 · BR-08, BR-10, BR-11, BR-12 · EC-03 đến EC-11 · UC-02 đến UC-06.
