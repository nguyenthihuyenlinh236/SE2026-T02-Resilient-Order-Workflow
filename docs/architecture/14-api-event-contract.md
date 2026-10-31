# 14 – API Contract và Event Contract

> **Trạng thái:** Bản đặc tả thiết kế để review, chưa phải OpenAPI/schema triển khai cuối cùng.  
> **Phạm vi:** Order Service, Inventory Service, Payment Service và điểm nhận thông báo của Notification Service.  
> **Nguyên tắc:** Chốt hành vi nghiệp vụ và cấu trúc logic; Dev xác nhận tên endpoint, kiểu dữ liệu cụ thể, framework, broker và cấu hình triển khai trước khi code.

## 1. Mục tiêu và nguyên tắc thiết kế

Tài liệu thống nhất cách các service trao đổi trong Resilient Order Workflow, bao gồm API đồng bộ, command nghiệp vụ, domain event, dữ liệu trao đổi, lỗi, timeout, idempotency và tương thích schema.

Các nguyên tắc bắt buộc:

1. Order Service là nơi điều phối Saga và là chủ sở hữu trạng thái Order.
2. Inventory Service là nơi sở hữu trạng thái Reservation; Payment Service là nơi sở hữu kết quả capture/refund.
3. Service không đọc trực tiếp database của service khác. Service gọi API hoặc nhận message theo contract.
4. Các thao tác có tác dụng phụ (reserve, capture, release, refund) phải có idempotency key ổn định; retry cùng hành động dùng lại đúng key.
5. Timeout không chứng minh thao tác thất bại. Kết quả chưa xác minh phải được biểu diễn là `UNKNOWN`, sau đó query/đối soát.
6. Thay đổi trạng thái Order và ghi domain event tương ứng vào Outbox phải nằm trong cùng transaction.
7. Không phát domain event cho mỗi lần retry kỹ thuật. Event chỉ phản ánh thay đổi nghiệp vụ đã commit.
8. Tài liệu này không quy định broker/topic/queue hoặc triển khai Controller, producer, consumer.

### 1.1. Phân biệt API, command và event

| Khái niệm | Ý nghĩa trong dự án | Ví dụ |
|---|---|---|
| API | Giao diện request/response mà service cung cấp | `POST /internal/v1/reservations` |
| Command | Yêu cầu một service thực hiện một hành động nghiệp vụ | `ReserveInventory`, `CapturePayment` |
| Domain event | Sự thật nghiệp vụ đã xảy ra và được commit | `OrderConfirmed` |
| Thông báo kỹ thuật | Thông tin vận hành như retry vượt ngưỡng, worker lỗi | Không phải domain event |

Trong bản thiết kế hiện tại, các bước Saga giữa Order–Inventory–Payment được mô tả theo request/response đồng bộ. Vì vậy, các command dưới đây được thực hiện qua API nội bộ; **không tạo thêm command message qua broker** nếu nhóm chưa quyết định chuyển sang điều phối bất đồng bộ. Domain event được phát qua Outbox cho Notification Service.

## 2. Sơ đồ trách nhiệm giao tiếp

| Bên gọi/phát | Bên nhận | Giao tiếp | Mục đích |
|---|---|---|---|
| Client | Order Service | Public API | Tạo và tra cứu Order |
| Order Service / Saga Orchestrator | Inventory Service | Internal API / command | Reserve, query và release Reservation |
| Order Service / Saga Orchestrator | Payment Service | Internal API / command | Capture, query và refund Payment |
| Order Service / Outbox Publisher | Notification Service | Domain event | Thông báo Order đã kết thúc một nhánh nghiệp vụ |
| Notification Service | Consumer inbox | Lưu trữ nội bộ | Chống xử lý event trùng |

Order Service không phát `OrderCreated` trong contract domain event tối thiểu hiện tại: việc tạo Order ở `PENDING` chưa phải một kết quả cuối cần Notification Service xử lý theo các sequence diagram đã thống nhất. Ba domain event kết thúc được xác định tại State Machine là `OrderConfirmed`, `OrderFailed`, `OrderExpired`.

## 3. Quy ước chung cho API

### 3.1. Quy ước đường dẫn và định danh

- Prefix public: `/api/v1`.
- Prefix nội bộ đề xuất: `/internal/v1`.
- Các đường dẫn dưới đây là đường dẫn logic; Dev xác nhận base URL, service discovery và cách xác thực nội bộ.
- ID dùng kiểu chuỗi trong JSON: `orderId`, `reservationId`, `paymentId`, `refundId`.
- Thời gian dùng ISO-8601 UTC, ví dụ `2026-10-29T10:15:30Z`.
- Tiền (nếu mô hình đơn hàng có giá) dùng decimal dạng chuỗi và currency rõ ràng; không dùng số thực dấu phẩy động.

### 3.2. Header chuẩn

| Header | Bắt buộc | Quy tắc |
|---|---:|---|
| `Content-Type: application/json` | Với request có body | Body JSON UTF-8 |
| `Idempotency-Key` | Có với POST tạo tác dụng nghiệp vụ | Key ổn định qua retry của cùng hành động |
| `X-Correlation-Id` | Có trong luồng nội bộ | Giữ nguyên xuyên suốt một Order/Saga để truy vết |
| `X-Command-Id` | Khuyến nghị với lệnh thay đổi trạng thái | Định danh command; giữ nguyên khi retry cùng command |
| `Authorization` | Theo cơ chế xác thực được triển khai | Không quy định token/identity trong tài liệu này |

Quy tắc định danh:

- `Idempotency-Key` định danh một tác dụng nghiệp vụ để chống lặp.
- `commandId`/`X-Command-Id` định danh yêu cầu command phục vụ truy vết và xác định nguyên nhân.
- `correlationId` liên kết các request, command, event thuộc cùng luồng xử lý.
- `eventId` định danh một event cụ thể; không dùng thay cho commandId hoặc Idempotency-Key.
- Nếu hệ thống dùng chung giá trị cho `commandId` và `Idempotency-Key` thì phải ghi rõ quy ước ở implementation; về ý nghĩa chúng vẫn là hai khái niệm khác nhau.

### 3.3. Error response chuẩn

Lỗi ở tầng giao thức/validation dùng cấu trúc thống nhất:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request không hợp lệ",
    "details": [
      {
        "field": "items[0].quantity",
        "reason": "must be greater than zero"
      }
    ],
    "correlationId": "corr-ord-001",
    "retryable": false
  }
}
```

| HTTP status | Ý nghĩa | Hành vi phía gọi |
|---|---|---|
| `400` | JSON sai, thiếu trường hoặc validation không hợp lệ | Không retry nếu chưa sửa request |
| `401/403` | Chưa xác thực/không có quyền | Không retry mù; xử lý cấu hình/quyền |
| `404` | Không tìm thấy tài nguyên | Kiểm tra ID và phạm vi truy vấn |
| `409` | Xung đột nghiệp vụ hoặc idempotency key khác payload | Không tạo key mới để né xung đột |
| `422` | Request đúng cấu trúc nhưng không thể xử lý theo quy tắc nghiệp vụ (nếu nhóm chọn dùng) | Đọc error code; không retry lỗi xác định |
| `429` | Bị giới hạn tốc độ | Retry theo hướng dẫn/backoff nếu được phép |
| `5xx` | Lỗi tạm thời phía service | Có thể retry theo D-03, giữ nguyên key |
| Timeout / mất kết nối | Không nhận được phản hồi xác định | Kết quả tác dụng phụ có thể là `UNKNOWN`; query hoặc retry cùng key |

Không dùng HTTP status đơn lẻ để suy ra trạng thái nghiệp vụ. Ví dụ timeout khi capture không được chuyển thành `FAILED`.

## 4. API Contract – Order Service

### 4.1. Tạo Order

| Thuộc tính | Đặc tả |
|---|---|
| Command | `CreateOrder` |
| API | `POST /api/v1/orders` |
| Bên gọi | Client |
| Bên xử lý | Order Service |
| Header | `Idempotency-Key` bắt buộc; `X-Correlation-Id` khuyến nghị |
| Thành công | `202 Accepted` – Order được tạo ở `PENDING`; Saga bắt đầu |
| Lỗi chính | `400` thiếu key/request không hợp lệ; `409 IDEMPOTENCY_KEY_CONFLICT`; `5xx` lỗi tạm thời |

Request mẫu:

```json
{
  "customerId": "CUS-001",
  "items": [
    {
      "productId": "SKU-001",
      "quantity": 2
    }
  ]
}
```

Response mẫu:

```json
{
  "orderId": "ORD-001",
  "status": "PENDING",
  "createdAt": "2026-10-29T10:00:00Z",
  "expireAt": "2026-10-29T10:15:00Z",
  "correlationId": "corr-ord-001"
}
```

Quy tắc:
- `Idempotency-Key` do Client tạo cho một yêu cầu đặt hàng logic; gửi lại yêu cầu phải dùng cùng key.
- Cùng key và cùng payload: trả lại Order/response đã lưu, không tạo Order thứ hai.
- Cùng key nhưng payload khác: `409 IDEMPOTENCY_KEY_CONFLICT`.
- Thiếu `Idempotency-Key`: `400 Bad Request`.
- `expireAt` được tạo theo TTL đã thống nhất; retry không tự gia hạn TTL.
- Response `202` không có nghĩa Order đã được xác nhận; Client dùng API tra cứu để xem tiến trình.

### 4.2. Tra cứu Order

| Thuộc tính | Đặc tả |
|---|---|
| Command/query | `GetOrder` |
| API | `GET /api/v1/orders/{orderId}` |
| Bên gọi | Client hoặc thành phần được cấp quyền |
| Thành công | `200 OK` |
| Lỗi chính | `404 ORDER_NOT_FOUND` |

Response mẫu:

```json
{
  "orderId": "ORD-001",
  "status": "PENDING",
  "createdAt": "2026-10-29T10:00:00Z",
  "expireAt": "2026-10-29T10:15:00Z",
  "reservationStatus": "RESERVED",
  "captureStatus": "UNKNOWN",
  "correlationId": "corr-ord-001"
}
```

Các trường trạng thái con là bản kết quả đã xác minh/lưu tại Order Service; không phải truy vấn trực tiếp database Inventory hoặc Payment. Chỉ trả các trường mà mô hình dữ liệu thực tế có.

Không đặc tả API Cancel Order trong phiên bản này vì các tài liệu nghiệp vụ/State Machine hiện tại chưa xác định một luồng khách hàng chủ động hủy riêng. Hủy tự động do payment thất bại hoặc TTL được điều phối nội bộ bởi Order Service.

## 5. API Contract – Inventory Service

Các API này là internal API, chỉ Order Service/Saga Orchestrator được gọi theo luồng hiện tại.

### 5.1. Reserve Inventory

| Thuộc tính | Đặc tả |
|---|---|
| Command | `ReserveInventory` |
| API | `POST /internal/v1/reservations` |
| Bên gọi → nhận | Order Service → Inventory Service |
| Idempotency key | `reserve:{orderId}:v1` |
| Thành công nghiệp vụ | `200 OK`, `status = RESERVED` |
| Từ chối nghiệp vụ | `409 INVENTORY_NOT_AVAILABLE` hoặc response kết quả `REJECTED`; Dev chọn một cách biểu diễn và dùng nhất quán |
| Lỗi chưa rõ | Timeout/5xx: Order Service ghi nhận `UNKNOWN`, query trước khi quyết định bước tiếp |

Request mẫu:

```json
{
  "commandId": "cmd-reserve-001",
  "orderId": "ORD-001",
  "items": [
    {
      "productId": "SKU-001",
      "quantity": 2
    }
  ],
  "correlationId": "corr-ord-001"
}
```

Response mẫu:

```json
{
  "reservationId": "RSV-001",
  "orderId": "ORD-001",
  "status": "RESERVED",
  "reservedAt": "2026-10-29T10:00:02Z"
}
```

Quy tắc:
- Kiểm tra và cập nhật tồn kho phải nguyên tử.
- Một Order không được tạo nhiều reservation có hiệu lực do retry.
- Cùng key/cùng payload trả lại kết quả đã lưu; cùng key/khác payload bị từ chối.
- Nếu reserve timeout, không gửi release khi chưa xác minh kết quả reserve; query bằng key ổn định trước.
- Inventory phải xử lý quy tắc reserve đến muộn sau release của cùng Order theo D-01.5/A-13; chi tiết triển khai cần Dev xác nhận.

### 5.2. Tra cứu Reservation

| Thuộc tính | Đặc tả |
|---|---|
| Query | `GetReservation` |
| API đề xuất | `GET /internal/v1/reservations/by-order/{orderId}` |
| Bên gọi → nhận | Order Service → Inventory Service |
| Thành công | `200 OK`; trả trạng thái Reservation đã lưu |
| Lỗi chính | `404 RESERVATION_NOT_FOUND`; timeout/5xx cần retry query |

Response mẫu:

```json
{
  "reservationId": "RSV-001",
  "orderId": "ORD-001",
  "status": "RESERVED",
  "idempotencyKey": "reserve:ORD-001:v1"
}
```

Nếu chưa có reservation, service trả kết quả không tồn tại rõ ràng; không được suy luận từ timeout thành `NOT_CREATED`.

### 5.3. Release Inventory

| Thuộc tính | Đặc tả |
|---|---|
| Command | `ReleaseInventory` |
| API | `POST /internal/v1/reservations/{reservationId}/release` |
| Bên gọi → nhận | Order Service → Inventory Service |
| Idempotency key | `inventory:release:{orderId}:{reservationId}:v1` |
| Thành công | `200 OK`, `status = RELEASED` |
| Lỗi chính | `404 RESERVATION_NOT_FOUND`; `409 IDEMPOTENCY_KEY_CONFLICT`; timeout/5xx |

Request mẫu:

```json
{
  "commandId": "cmd-release-001",
  "orderId": "ORD-001",
  "correlationId": "corr-ord-001"
}
```

Response mẫu:

```json
{
  "reservationId": "RSV-001",
  "orderId": "ORD-001",
  "status": "RELEASED",
  "releasedAt": "2026-10-29T10:03:00Z"
}
```

Quy tắc:
- Release đúng Reservation của Order; không làm tăng tồn kho hai lần.
- Gửi lại cùng key/cùng payload trả lại kết quả release đã lưu.
- Timeout hoặc lỗi chưa rõ không được coi là đã release; query Reservation hoặc retry cùng key theo D-03.
- Không release khi payment còn `UNKNOWN` hoặc khi reserve chưa được xác minh, theo D-01/D-04.

## 6. API Contract – Payment Service

Các API này là internal API. Payment Service sở hữu trạng thái capture/refund; Order Service chỉ lưu bản kết quả đã xác minh.

### 6.1. Capture Payment

| Thuộc tính | Đặc tả |
|---|---|
| Command | `CapturePayment` |
| API | `POST /internal/v1/payments/captures` |
| Bên gọi → nhận | Order Service → Payment Service |
| Idempotency key | `payment:capture:{orderId}:v1` |
| Kết quả | `SUCCEEDED`, `FAILED` hoặc `UNKNOWN` |
| Lỗi chính | `400` request không hợp lệ; `409 IDEMPOTENCY_KEY_CONFLICT`; timeout/5xx cần query/đối soát |

Request mẫu:

```json
{
  "commandId": "cmd-capture-001",
  "orderId": "ORD-001",
  "amount": "250000.00",
  "currency": "VND",
  "correlationId": "corr-ord-001"
}
```

Response khi thành công:

```json
{
  "paymentId": "PAY-001",
  "orderId": "ORD-001",
  "status": "SUCCEEDED",
  "amount": "250000.00",
  "currency": "VND",
  "capturedAt": "2026-10-29T10:00:05Z"
}
```

Response khi chưa xác định:

```json
{
  "paymentId": "PAY-001",
  "orderId": "ORD-001",
  "status": "UNKNOWN",
  "idempotencyKey": "payment:capture:ORD-001:v1",
  "message": "Chưa xác minh được kết quả capture"
}
```

Quy tắc:
- `UNKNOWN` là kết quả chưa xác định, không phải `FAILED`.
- Timeout/mất kết nối có thể xảy ra sau khi Payment đã thực hiện capture; Order Service phải lưu trạng thái chưa rõ và query theo paymentId hoặc capture key.
- Không gửi capture mới bằng key khác để xử lý timeout.
- Chỉ Order ở `PENDING` mới được bắt đầu lệnh tiến capture; sau khi Order rời `PENDING`, không gửi lệnh tiến mới.
- Order chỉ được xác nhận khi capture `SUCCEEDED` đã xác minh, Reservation `RESERVED` đã xác minh, và conditional update `PENDING → CONFIRMED` hợp lệ theo D-01.

### 6.2. Tra cứu Payment

| Thuộc tính | Đặc tả |
|---|---|
| Query | `GetPayment` |
| API đề xuất | `GET /internal/v1/payments/{paymentId}` |
| API tra cứu theo key (nếu chưa có paymentId) | `GET /internal/v1/payments/by-idempotency-key/{key}` |
| Bên gọi → nhận | Order Service/Reconciliation Worker → Payment Service |
| Thành công | `200 OK`; trả `SUCCEEDED`, `FAILED` hoặc `UNKNOWN` |
| Lỗi chính | `404 PAYMENT_NOT_FOUND`; timeout/5xx chỉ retry query |

Response mẫu:

```json
{
  "paymentId": "PAY-001",
  "orderId": "ORD-001",
  "status": "UNKNOWN",
  "amount": "250000.00",
  "currency": "VND",
  "idempotencyKey": "payment:capture:ORD-001:v1"
}
```

Quy tắc:
- Query không tạo tác dụng phụ.
- Nếu kết quả vẫn `UNKNOWN`, tiếp tục lịch đối soát D-04; hết lịch chính thì Saga chuyển `RECONCILIATION_REQUIRED` và polling nền theo quyết định hiện hành.
- Không release Reservation, refund hoặc chốt Order `EXPIRED` chỉ dựa trên kết quả `UNKNOWN`.

### 6.3. Refund Payment

| Thuộc tính | Đặc tả |
|---|---|
| Command | `RefundPayment` |
| API | `POST /internal/v1/payments/{paymentId}/refunds` |
| Bên gọi → nhận | Order Service → Payment Service |
| Idempotency key | `payment:refund:{orderId}:{paymentId}:v1` |
| Kết quả | `SUCCEEDED`, `FAILED` hoặc `UNKNOWN` |
| Lỗi chính | `404 PAYMENT_NOT_FOUND`; `409 IDEMPOTENCY_KEY_CONFLICT`; timeout/5xx cần query/đối soát |

Request mẫu:

```json
{
  "commandId": "cmd-refund-001",
  "orderId": "ORD-001",
  "reason": "ORDER_EXPIRED",
  "correlationId": "corr-ord-001"
}
```

Response mẫu:

```json
{
  "refundId": "REF-001",
  "paymentId": "PAY-001",
  "orderId": "ORD-001",
  "status": "SUCCEEDED",
  "refundedAt": "2026-10-29T10:16:00Z"
}
```

Quy tắc:
- Chỉ refund khi capture đã được xác minh `SUCCEEDED` và chính sách nghiệp vụ yêu cầu hoàn tiền.
- Capture và refund là hai trạng thái/giao dịch riêng.
- Timeout refund là `UNKNOWN`; query theo refundId/key trước khi retry.
- Cùng key/cùng payload không tạo refund thứ hai; cùng key/khác payload bị từ chối.
- Refund `FAILED` đã xác minh không được tự động né bằng cách tạo key mới; xử lý theo quyết định vận hành/Q-08.

## 7. Danh sách command và API tương ứng

Các command dưới đây là hành động nghiệp vụ được gọi qua API nội bộ trong thiết kế hiện tại, không phải danh sách message broker độc lập.

| Command | Service nhận | API tương ứng | Idempotency key |
|---|---|---|---|
| `ReserveInventory` | Inventory | `POST /internal/v1/reservations` | `reserve:{orderId}:v1` |
| `GetReservation` | Inventory | `GET /internal/v1/reservations/by-order/{orderId}` | Không áp dụng (query) |
| `ReleaseInventory` | Inventory | `POST /internal/v1/reservations/{reservationId}/release` | `inventory:release:{orderId}:{reservationId}:v1` |
| `CapturePayment` | Payment | `POST /internal/v1/payments/captures` | `payment:capture:{orderId}:v1` |
| `GetPayment` | Payment | `GET /internal/v1/payments/{paymentId}` hoặc tra cứu theo key | Không áp dụng (query) |
| `RefundPayment` | Payment | `POST /internal/v1/payments/{paymentId}/refunds` | `payment:refund:{orderId}:{paymentId}:v1` |

Order API không dùng idempotency key nội bộ thay cho các key của Inventory/Payment. Mỗi tác dụng phụ có key riêng do Order Service tạo và lưu bền vững.

## 8. Event Contract

### 8.1. Domain event được chốt trong phạm vi hiện tại

| Event | Producer | Consumer | Thời điểm phát |
|---|---|---|---|
| `OrderConfirmed` | Order Service | Notification Service | Sau khi `PENDING → CONFIRMED` thành công và transaction Order + Outbox commit |
| `OrderFailed` | Order Service | Notification Service | Sau khi nhánh thất bại hoàn tất các compensation bắt buộc và Order chuyển `FAILED` |
| `OrderExpired` | Order Service | Notification Service | Sau khi nhánh hết hạn hoàn tất release/refund bắt buộc và Order chuyển `EXPIRED` |

Không đưa `InventoryReserved`, `PaymentSucceeded`, `PaymentFailed`, `PaymentUnknown` thành domain event trong phiên bản contract này: theo luồng sequence hiện tại, Inventory/Payment trả kết quả trực tiếp qua API cho Order Service. Nếu sau này nhóm chuyển sang giao tiếp bất đồng bộ, cần cập nhật contract, Saga và sequence diagram đồng thời; không phát sinh thêm event song song với response đồng bộ nếu chưa có nhu cầu rõ ràng.

### 8.2. Message envelope

Envelope JSON thống nhất:

```json
{
  "eventId": "evt-uuid-001",
  "commandId": "cmd-capture-001",
  "correlationId": "corr-ord-001",
  "schemaVersion": 1,
  "eventType": "OrderConfirmed",
  "occurredAt": "2026-10-29T10:00:06Z",
  "producer": "order-service",
  "payload": {
    "orderId": "ORD-001",
    "status": "CONFIRMED"
  }
}
```

| Trường | Kiểu logic | Bắt buộc | Ý nghĩa |
|---|---|---:|---|
| `eventId` | string/UUID | Có | Định danh duy nhất của event; tạo một lần khi ghi Outbox |
| `commandId` | string/null | Có mặt trong envelope; có thể null nếu không truy được command gây ra event | Truy vết command/causation liên quan; không dùng dedupe thay eventId |
| `correlationId` | string | Có | Liên kết toàn bộ luồng Order/Saga |
| `schemaVersion` | integer | Có | Phiên bản schema payload |
| `eventType` | string | Có | Tên domain event |
| `occurredAt` | ISO-8601 UTC | Có | Thời điểm nghiệp vụ được commit/phát sinh |
| `producer` | string | Có | Service phát event |
| `payload` | object | Có | Dữ liệu nghiệp vụ của event |

Quy tắc:
- `eventId` sinh một lần khi ghi Outbox; publish lại phải giữ nguyên eventId và payload.
- `commandId` chỉ phục vụ liên kết nguyên nhân; không thay thế `eventId`.
- `correlationId` được truyền xuyên suốt từ request tạo Order qua các API nội bộ đến Outbox.
- Timestamp dùng UTC.
- Không đưa dữ liệu nhạy cảm hoặc thông tin thanh toán không cần thiết vào event.
- Payload chỉ chứa dữ liệu cần cho consumer; Notification Service không được tự suy diễn trạng thái từ một event khác.

### 8.3. Payload mẫu

**OrderConfirmed**

```json
{
  "eventId": "evt-confirmed-001",
  "commandId": "cmd-capture-001",
  "correlationId": "corr-ord-001",
  "schemaVersion": 1,
  "eventType": "OrderConfirmed",
  "occurredAt": "2026-10-29T10:00:06Z",
  "producer": "order-service",
  "payload": {
    "orderId": "ORD-001",
    "status": "CONFIRMED",
    "confirmedAt": "2026-10-29T10:00:06Z"
  }
}
```

**OrderFailed**

```json
{
  "eventId": "evt-failed-001",
  "commandId": "cmd-release-001",
  "correlationId": "corr-ord-002",
  "schemaVersion": 1,
  "eventType": "OrderFailed",
  "occurredAt": "2026-10-29T10:03:00Z",
  "producer": "order-service",
  "payload": {
    "orderId": "ORD-002",
    "status": "FAILED",
    "reasonCode": "PAYMENT_FAILED",
    "failedAt": "2026-10-29T10:03:00Z"
  }
}
```

**OrderExpired**

```json
{
  "eventId": "evt-expired-001",
  "commandId": "cmd-expire-001",
  "correlationId": "corr-ord-003",
  "schemaVersion": 1,
  "eventType": "OrderExpired",
  "occurredAt": "2026-10-29T10:15:10Z",
  "producer": "order-service",
  "payload": {
    "orderId": "ORD-003",
    "status": "EXPIRED",
    "expiredAt": "2026-10-29T10:15:10Z"
  }
}
```

Các giá trị ID và timestamp trong ví dụ chỉ minh họa cấu trúc, không phải dữ liệu cố định. `reasonCode` cần được thống nhất thành enum trong schema triển khai.

## 9. Quy tắc Outbox, Inbox và xử lý trùng

### 9.1. Producer / Outbox

- Service sở hữu trạng thái Order ghi thay đổi Order và domain event tương ứng trong cùng transaction DB.
- Outbox tối thiểu lưu `eventId`, `eventType`, `schemaVersion`, `correlationId`, `payload`, `occurredAt`, trạng thái publish và thời điểm tạo.
- Publisher chỉ phát event đã commit.
- Nếu publish thành công nhưng Publisher crash trước khi đánh dấu, event có thể được phát lại; eventId không đổi.
- Không xóa event chưa được xác nhận publish; chính sách lưu trữ do Dev xác nhận.

### 9.2. Consumer / Inbox

- Consumer Notification Service lưu dấu đã xử lý với unique key `(consumerName, eventId)`.
- Event đã xử lý: acknowledge/skip, không gửi lại tác dụng phụ nghiệp vụ.
- Event mới: ghi nhận xử lý và tạo tác vụ notification theo transaction nội bộ phù hợp.
- Ghi nhận inbox và tác vụ notification phải được thiết kế tránh trường hợp đánh dấu đã xử lý nhưng mất tác vụ gửi.
- Gửi ra hệ thống bên ngoài không được tuyên bố exactly-once nếu nhà cung cấp không hỗ trợ idempotency hoặc tra cứu giao nhận. Mục tiêu hiện tại là chống xử lý logic trùng trong hệ thống và retry at-least-once.

## 10. Timeout, UNKNOWN và ma trận lỗi

| Thao tác | Tình huống | Trạng thái cần lưu ở Order/Saga | Hành động bắt buộc |
|---|---|---|---|
| Reserve | Timeout/mất phản hồi | Reservation `UNKNOWN`; Saga `RECONCILING` | Query bằng reserve key; chưa xác minh thì không capture và không release mù |
| Reserve | Không đủ tồn kho đã xác minh | Reservation `REJECTED`; claim nhánh thất bại nếu Order còn `PENDING` | Không capture; hoàn tất nhánh thất bại theo State Machine |
| Capture | Timeout/mất phản hồi | Capture `UNKNOWN`; Saga `RECONCILING` | Query theo paymentId/capture key; không coi là FAILED |
| Capture | `FAILED` đã xác minh | Capture `FAILED` | Claim nhánh thất bại bằng conditional update; release reservation đã xác minh |
| Release | Timeout/mất phản hồi | Release `UNKNOWN`; Saga `RECONCILING` | Query/retry cùng release key; không tuyên bố đã release |
| Refund | Timeout/mất phản hồi | Refund `UNKNOWN`; Saga `RECONCILING` | Query theo refundId/refund key; không tạo refund key mới |
| Refund | `FAILED` đã xác minh | Refund `FAILED`; Saga cần xử lý ngoại lệ/đối soát | Không đánh dấu Saga hoàn tất; xử lý theo Q-08 |
| Bất kỳ POST nào | Cùng key, khác payload | Không thay đổi trạng thái nghiệp vụ | Trả `409 IDEMPOTENCY_KEY_CONFLICT` |
| Event | Nhận lại cùng eventId | Inbox đã có bản ghi | Bỏ qua tác dụng phụ lặp |
| Event | Schema không tương thích | Không xử lý payload không hiểu | Cách ly/ghi lỗi và cảnh báo; không đoán trường bị thiếu |

### 10.1. Idempotency key chuẩn

| Hành động | Key |
|---|---|
| Create Order | Client sinh UUID; lưu cùng payload hash và Order ID |
| Reserve | `reserve:{orderId}:v1` |
| Capture | `payment:capture:{orderId}:v1` |
| Release | `inventory:release:{orderId}:{reservationId}:v1` |
| Refund | `payment:refund:{orderId}:{paymentId}:v1` |
| Outbox event | UUID `eventId` sinh một lần |
| Consumer xử lý event | `UNIQUE(consumerName, eventId)` |
| Notification logic | `notification:{eventId}:{notificationType}:{channel}` |

Cùng key/cùng payload phải trả lại kết quả đã lưu; cùng key/khác payload trả `409 IDEMPOTENCY_KEY_CONFLICT`. Unique constraint phải được áp dụng tại nơi thực hiện tác dụng phụ, không chỉ kiểm tra ở code gọi.

### 10.2. Retry

Áp dụng chính sách D-03 trong `06-design-decisions.md`: retry lỗi tạm thời theo giới hạn và backoff cấu hình; retry cùng hành động phải dùng cùng key. Trước khi retry thao tác có thể đã thành công nhưng mất phản hồi, query trạng thái hoặc gửi lại cùng key theo contract. Không retry tự động lỗi validation, out-of-stock đã xác minh hoặc idempotency conflict.

## 11. Tương thích và tiến hóa schema

- Mỗi event có `schemaVersion` dạng số nguyên.
- Thay đổi tương thích ngược (ví dụ thêm field optional) có thể giữ version nếu nhóm thống nhất consumer bỏ qua field không biết.
- Xóa/đổi tên field, đổi kiểu dữ liệu, thay đổi ý nghĩa hoặc làm field optional thành bắt buộc là thay đổi phá vỡ tương thích; cần version mới và kế hoạch chuyển đổi consumer.
- Consumer phải bỏ qua field không biết nếu schema cho phép; không được giả định field optional luôn có mặt.
- Không tái sử dụng cùng version cho hai cấu trúc payload khác nhau.
- Event đã ghi Outbox phải giữ nguyên payload/version khi publish lại.
- Cơ chế registry, JSON Schema/Avro/Protobuf và chính sách giữ nhiều version là quyết định triển khai còn mở.

## 12. Bảo mật và quan sát

- API nội bộ chỉ được gọi bởi service/identity được cấp quyền.
- Không ghi thông tin xác thực, token, dữ liệu thẻ hoặc bí mật vào log/event.
- Log tối thiểu các định danh: `orderId`, `correlationId`, `commandId`, `eventId` (khi có), operation key ở dạng được phép ghi log.
- Không log toàn bộ payload nếu chứa dữ liệu cá nhân/nhạy cảm.
- Theo dõi số lần retry, timeout, trạng thái `UNKNOWN`, tuổi của Saga chưa hoàn tất, Outbox chưa publish và notification đang retry.
- `correlationId` phải được giữ qua các hop để có thể truy vết một luồng từ request tạo Order đến event kết thúc.

## 13. Quyết định còn mở cần Dev/nhóm xác nhận

| ID | Nội dung | Đề xuất trong bản contract | Người cần xác nhận |
|---|---|---|---|
| OPEN-01 | REST sync hay broker command cho Reserve/Capture/Release/Refund | Giữ REST request/response như sequence hiện tại; không tạo command message trùng | Dev phụ trách tích hợp |
| OPEN-02 | Endpoint nội bộ, base URL và authentication | Dùng các path logic ở mục 5–6; Dev chốt service discovery và auth | Dev |
| OPEN-03 | Cách biểu diễn Inventory từ chối reserve | Chọn nhất quán giữa `409 INVENTORY_NOT_AVAILABLE` và response nghiệp vụ `REJECTED` | Inventory owner |
| OPEN-04 | Kiểu ID, tiền và currency | String ID; decimal string + currency nếu có giá | Order/Payment owner |
| OPEN-05 | Query theo idempotency key khi chưa có resource ID | Cần endpoint tra cứu theo key cho reserve/payment/refund | Inventory/Payment owner |
| OPEN-06 | Schema validation và format chính thức | JSON mẫu trong tài liệu; Dev chốt OpenAPI/JSON Schema hoặc format tương ứng | Dev |
| OPEN-07 | Broker, topic/queue, delivery/ack và schema registry | Chưa chốt trong tài liệu BA; chỉ triển khai sau khi thống nhất kiến trúc tích hợp | Dev |
| OPEN-08 | Enum `reasonCode` của OrderFailed | Chốt danh mục mã lỗi dùng chung với failure catalog | BA + Dev |
| OPEN-09 | Cách xử lý refund `FAILED` đã xác minh | Không tự sinh key refund mới; theo Q-08 trong Design Decisions | Nhóm |

## 14. Checklist review và nghiệm thu

- [ ] Các API Order create/query được đặc tả request, response, validation và lỗi.
- [ ] Các thao tác reserve/query/release Inventory có contract và idempotency key.
- [ ] Các thao tác capture/query/refund Payment có contract và phân biệt `SUCCEEDED`, `FAILED`, `UNKNOWN`.
- [ ] Command được phân biệt với API transport và domain event.
- [ ] Chỉ domain event đã được State Machine xác định được đưa vào phạm vi hiện tại.
- [ ] Mỗi event có producer, consumer, envelope, payload và thời điểm phát rõ ràng.
- [ ] `eventId`, `commandId`, `correlationId`, `schemaVersion` được phân biệt và dùng nhất quán.
- [ ] Retry cùng hành động giữ nguyên Idempotency-Key; cùng key khác payload bị từ chối.
- [ ] Timeout không bị xem là FAILED; `UNKNOWN` phải query/đối soát.
- [ ] Outbox event được ghi cùng transaction với thay đổi Order tương ứng.
- [ ] Consumer chống trùng bằng `(consumerName, eventId)`.
- [ ] Không tuyên bố exactly-once cho tác dụng phụ bên ngoài nếu không có hỗ trợ từ bên nhận.
- [ ] Không giả định API cũ trong source code đã được triển khai trong skeleton mới.
- [ ] Dev/owner ba service review endpoint, schema, message format và các điểm mở trước khi merge vào `dev`.

## 15. Tài liệu tham chiếu

- `docs/requirements/02-functional-requirements.md`
- `docs/requirements/03-business-rules.md`
- `docs/requirements/06-design-decisions.md`
- `docs/architecture/09-state-machine.md`
- `docs/architecture/10-saga-workflow-and-compensation.md`
- `docs/architecture/11-sequence-diagrams.md`
- Issue #6 – Draft API and Event Contracts
- Issue #20 – Hoàn thiện API Contract và Event Contract giữa các service
