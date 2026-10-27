# 14 – API and Event Contract Draft

> **Trạng thái:** Bản nháp BA để Dev/Tester review; chưa phải OpenAPI/AsyncAPI đã chốt.  
> **Phạm vi:** APP-08 – Resilient Order Workflow (Java/Spring Boot, PostgreSQL; Inventory, Payment và Notification hiện được mô phỏng trong cùng ứng dụng).  
> **Nguồn đối chiếu:** `OrderController`, `AuthController`, `StaffOrderController`, `AdminAccountController`, `02-functional-requirements.md`, `03-business-rules.md`, `06-design-decisions.md`, `09-state-machine.md`, `10-saga-workflow-and-compensation.md`, `11-sequence-diagrams.md` và `12-acceptance-criteria.md`.

## 1. Mục tiêu và quy ước

Tài liệu thống nhất ở mức nghiệp vụ các API, command và event cần thiết để triển khai Saga/Outbox, retry, idempotency, hết hạn đơn và đối soát. Những endpoint được xác nhận có trong mã nguồn hiện tại được đánh dấu **Implemented**; command/event nội bộ được đề xuất được đánh dấu **Proposed**. Không suy ra rằng command/event đề xuất đã có API HTTP hoặc broker thật.

### 1.1. Quy ước chung

- Base path: `/api/v1`; JSON UTF-8; thời gian ISO-8601 UTC, ví dụ `2026-10-27T10:30:00Z`.
- ID dùng UUID trừ khi Dev chốt kiểu khác. `orderId`, `paymentId`, `reservationId`, `sagaId`, `eventId` không được dùng thay thế cho nhau.
- API yêu cầu đăng nhập dùng session cookie. Trước các mutation cần CSRF, gọi `GET /api/v1/auth/csrf`, giữ cookie và gửi token ở header được trả về (hiện tại là `X-XSRF-TOKEN`). Không ghi session cookie hoặc CSRF token vào log.
- API tạo đơn trả `202 Accepted` khi yêu cầu đã được tiếp nhận bền vững; không có nghĩa đơn đã thanh toán hoặc được xác nhận.
- Không coi timeout, mất kết nối hoặc HTTP 5xx là bằng chứng nghiệp vụ thất bại. Nếu kết quả tác dụng phụ chưa rõ, truy vấn bằng định danh ổn định trước khi retry.
- `UNKNOWN` là kết quả chưa xác định, không đồng nghĩa với `FAILED`.
- Các mã lỗi cụ thể cho command/event nội bộ dưới đây là đề xuất; API HTTP cần tuân theo response thực tế của ứng dụng và được Dev chuẩn hóa trong OpenAPI.

## 2. API HTTP

### 2.1. API xác thực

| Method | Endpoint | Quyền | Trạng thái | Mục đích |
|---|---|---|---|---|
| GET | `/api/v1/auth/csrf` | Công khai để lấy token CSRF | Implemented | Trả `token`, `headerName` |
| POST | `/api/v1/auth/login` | Công khai | Implemented | Đăng nhập, tạo session |
| GET | `/api/v1/auth/me` | Đã đăng nhập | Implemented | Lấy tài khoản/role hiện tại |
| POST | `/api/v1/auth/logout` | Đã đăng nhập | Implemented | Hủy session; cần CSRF |

**Login request**
```json
{ "username": "customer01", "password": "<secret>" }
```

**Login response (minh họa)**
```json
{
  "accountId": "8c7f8a6d-0000-4000-8000-000000000001",
  "username": "customer01",
  "role": "CUSTOMER",
  "sessionIdleTimeoutSeconds": 1800
}
```

Lỗi xác thực: `401 AUTHENTICATION_FAILED` khi thông tin đăng nhập không hợp lệ; `423 ACCOUNT_LOCKED` khi tài khoản đang bị khóa. Tên/title lỗi cần được kiểm tra với response thực tế của controller.

### 2.2. API đơn hàng cho Customer

| Method | Endpoint | Quyền | Trạng thái | Mục đích |
|---|---|---|---|---|
| POST | `/api/v1/orders` | CUSTOMER | Implemented | Tạo đơn bất đồng bộ, yêu cầu idempotency |
| GET | `/api/v1/orders/{orderId}` | CUSTOMER sở hữu đơn; ADMIN theo chính sách | Implemented | Tra cứu một đơn |
| GET | `/api/v1/orders?limit=20` | CUSTOMER | Implemented | Liệt kê đơn của chính khách hàng; limit 1–50 |

**POST /api/v1/orders**

Headers:
- `Content-Type: application/json`
- `Idempotency-Key: <client-generated-key>` (bắt buộc, không rỗng, tối đa 200 ký tự)
- Session cookie và CSRF header hợp lệ.

Request:
```json
{
  "items": [
    { "sku": "DEMO-ITEM", "quantity": 2 }
  ],
  "storeId": "DEMO-STORE"
}
```

Quy tắc đầu vào: `items` không rỗng, tối đa 20 dòng; mỗi `sku` không rỗng; `quantity` là số nguyên dương; `storeId` tùy chọn, tối đa 100 ký tự. Quy tắc SKU trùng trong một đơn phải được xác nhận thống nhất với validation hiện tại.

Response: `202 Accepted`, header `Location: /api/v1/orders/{orderId}`, `Retry-After: 1`.
```json
{
  "orderId": "8c7f8a6d-0000-4000-8000-000000000002",
  "status": "PENDING",
  "fulfillmentStatus": null,
  "sagaStatus": "RUNNING",
  "paymentStatus": null,
  "reservationStatus": null,
  "storeId": "DEMO-STORE",
  "createdAt": "2026-10-27T10:30:00Z",
  "expireAt": "2026-10-27T10:45:00Z",
  "statusUrl": "/api/v1/orders/8c7f8a6d-0000-4000-8000-000000000002",
  "items": [{ "sku": "DEMO-ITEM", "quantity": 2 }]
}
```
Các trường nullable/trạng thái khởi tạo trong ví dụ cần được Dev xác nhận với `OrderResponse`; ví dụ không phải cam kết rằng mọi trường luôn có giá trị như trên.

**GET /api/v1/orders/{orderId}** trả `200 OK` với `OrderResponse` nếu người dùng có quyền xem. Order không tồn tại và Order thuộc tài khoản khác đều nên trả `404 Order not found` để tránh tiết lộ sự tồn tại của đơn.

**Idempotency của POST**
1. Cùng khóa + cùng payload: trả lại Order đã tạo/response tương ứng; không tạo Order, Saga, reservation hoặc capture mới.
2. Cùng khóa + payload khác: `409 IDEMPOTENCY_KEY_CONFLICT`; không thực hiện tác dụng phụ.
3. Thiếu khóa hoặc khóa không hợp lệ: `400 Bad Request`.
4. Timeout/mất phản hồi sau khi server có thể đã commit: client retry đúng khóa và payload cũ, không tự sinh khóa mới.
5. Phạm vi duy nhất đề xuất: `(customerId, idempotencyKey)`; Dev xác nhận ràng buộc DB và hành vi khi tài khoản khác dùng cùng key.

### 2.3. API vận hành đơn cho Staff

| Method | Endpoint | Quyền | Trạng thái | Mục đích |
|---|---|---|---|---|
| GET | `/api/v1/staff/orders` | STAFF | Implemented | Danh sách đơn của store được gán |
| POST | `/api/v1/staff/orders/{orderId}/claim` | STAFF | Implemented | Nhận xử lý một đơn |
| PATCH | `/api/v1/staff/orders/{orderId}/fulfillment` | STAFF | Implemented | Cập nhật trạng thái fulfillment |

Request PATCH:
```json
{ "status": "PREPARING" }
```
Giá trị được controller khai báo cho request là `PREPARING` hoặc `DELIVERED`; luồng nhận đơn có thể đặt `RECEIVED`. Mỗi mutation cần session và CSRF. Dev xác nhận mã lỗi cho đơn không thuộc store, đơn đã được nhân viên khác nhận và chuyển trạng thái không hợp lệ.

### 2.4. API quản trị tài khoản

| Method | Endpoint | Quyền | Trạng thái | Mục đích |
|---|---|---|---|---|
| POST | `/api/v1/admin/accounts` | ADMIN | Implemented | Tạo CUSTOMER, STAFF hoặc ADMIN |

Request:
```json
{
  "username": "staff01",
  "password": "<at-least-12-characters>",
  "role": "STAFF",
  "storeId": "DEMO-STORE"
}
```
Username tối đa 100 ký tự và theo pattern hiện có; password 12–72 ký tự; role bắt buộc; tài khoản STAFF phải gắn store hợp lệ. Không đưa mật khẩu vào event, audit message không cần thiết hoặc log.

### 2.5. Bảng mã lỗi HTTP đề xuất

| HTTP | Code | Khi nào |
|---|---|---|
| 400 | `VALIDATION_ERROR` | JSON thiếu/sai trường, số lượng không dương, thiếu `Idempotency-Key`, CSRF không hợp lệ theo cấu hình |
| 401 | `UNAUTHENTICATED` / `AUTHENTICATION_FAILED` | Chưa đăng nhập hoặc thông tin đăng nhập sai |
| 403 | `FORBIDDEN` | Đã đăng nhập nhưng không đủ quyền |
| 404 | `ORDER_NOT_FOUND` | Không tìm thấy đơn hoặc không được phép biết đơn tồn tại |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | Khóa đã dùng với payload khác; hoặc xung đột trạng thái nghiệp vụ |
| 423 | `ACCOUNT_LOCKED` | Tài khoản bị khóa tạm thời |
| 429 | `RATE_LIMITED` | Chỉ áp dụng nếu Dev triển khai giới hạn tốc độ |
| 5xx | `INTERNAL_ERROR` | Lỗi hệ thống; không suy ra tác dụng phụ chưa commit nếu kết quả commit chưa rõ |

**Response lỗi chuẩn hóa đề xuất (Problem Details)**
```json
{
  "type": "about:blank",
  "title": "IDEMPOTENCY_KEY_CONFLICT",
  "status": 409,
  "detail": "Idempotency-Key was already used with a different request payload.",
  "instance": "/api/v1/orders"
}
```
Dev cần chốt schema lỗi thực tế, đặc biệt cho validation, CSRF, authorization và lỗi DB. Không được ghi thông tin nhạy cảm hoặc stack trace vào response production.

## 3. Command nghiệp vụ nội bộ (Proposed)

Các command dưới đây là hợp đồng logic giữa Saga Orchestrator và các capability Inventory/Payment. Hiện các capability này được mô phỏng trong cùng ứng dụng; tên transport, API nội bộ hoặc broker chưa được chốt.

### 3.1. Quy ước command

Mỗi command có `commandId` duy nhất và `idempotencyKey` ổn định. Retry cùng một tác dụng nghiệp vụ phải dùng lại khóa, cùng payload và cùng đối tượng nghiệp vụ. Khuyến nghị lưu fingerprint payload cùng kết quả đã xử lý. Cùng khóa nhưng fingerprint khác phải trả lỗi xung đột và không thực hiện side effect.

Envelope đề xuất:
```json
{
  "commandId": "uuid",
  "commandType": "ReserveInventory",
  "schemaVersion": 1,
  "occurredAt": "2026-10-27T10:30:00Z",
  "correlationId": "order-uuid",
  "causationId": "previous-command-or-event-uuid",
  "idempotencyKey": "reserve:order-uuid:v1",
  "payload": {}
}
```

### 3.2. Danh mục command

| Command | Payload tối thiểu | Kết quả xác định | Kết quả chưa rõ/lỗi |
|---|---|---|---|
| `ReserveInventory` | `orderId`, `reservationId`, `storeId`, `items[{sku,quantity}]` | `RESERVED` hoặc `REJECTED`, `reservationId` | Timeout/network: truy vấn reservation bằng cùng key; không tạo reservation mới |
| `GetReservation` | `orderId`, `reservationId` hoặc `idempotencyKey` | `RESERVED`, `RELEASED`, `REJECTED`, `NOT_FOUND` đã xác minh | `UNKNOWN` nếu chưa thể xác minh |
| `ReleaseReservation` | `orderId`, `reservationId`, `reason` | `RELEASED` hoặc xác nhận đã release trước đó | Retry cùng `release:{orderId}:v1`; không cộng tồn kho hai lần |
| `CapturePayment` | `orderId`, `paymentId`, `amount`, `currency` | `SUCCEEDED` hoặc `FAILED` đã xác minh | Timeout/không rõ: `UNKNOWN`; không gửi capture với key mới |
| `GetPayment` | `orderId`, `paymentId` hoặc `captureIdempotencyKey` | Trạng thái capture đã lưu | `UNKNOWN` nếu kết quả chưa thể xác minh |
| `RefundPayment` | `orderId`, `paymentId`, `refundId`, `amount`, `reason` | `REFUNDED` hoặc `FAILED` đã xác minh | `UNKNOWN`: tra cứu refund; không tạo refund mới với key khác |
| `ExpireOrder` | `orderId`, `expireAt`, `claimToken` | Claim thành công hoặc bị từ chối vì trạng thái hiện tại | Dùng conditional update theo DB clock; chỉ worker claim thành công mới được tiếp tục |
| `ReconcilePayment` | `orderId`, `paymentId`, `operationType` (`CAPTURE`/`REFUND`) | Kết quả đã xác minh | Tiếp tục đối soát; sau ngưỡng, chuyển hàng đợi xử lý thủ công theo chính sách |
| `NotifyOrderStatus` | `orderId`, `eventId`, `recipientRef`, `templateKey` | Ghi nhận đã xử lý/gửi theo khả năng provider | Retry với cùng `eventId`; không thay đổi trạng thái Order |

### 3.3. Quy tắc xử lý `UNKNOWN`

- `UNKNOWN` biểu thị hệ thống chưa xác minh được kết quả, không phải trạng thái cuối thất bại.
- Khi capture là `UNKNOWN`: không capture lại với key mới, không giải phóng reservation, không refund dựa trên giả định. Query/đối soát bằng cùng định danh ổn định.
- Khi release/refund là `UNKNOWN`: query kết quả bằng cùng idempotency key; không đánh dấu compensation hoàn tất cho đến khi xác minh.
- Khi payment vẫn `UNKNOWN) tại thời điểm TTL: không claim nhánh hết hạn nếu theo D-01/BR-13; tiếp tục reconciliation và giữ reservation.
- Nếu capture thành công sau khi Order đã vào nhánh hết hạn hoặc `EXPIRED`: không mở lại Order; đối soát và refund theo chính sách.
- Lưu `attemptCount`, `nextAttemptAt`, `lastErrorCode`, trạng thái reconciliation và thời gian cập nhật bền vững. Retry/backoff/lease là cấu hình, không hard-code vào contract.
- Không phát event xác nhận nghiệp vụ cuối cùng trước khi trạng thái liên quan được commit.

## 4. Event nghiệp vụ (Proposed)

### 4.1. Envelope chuẩn

```json
{
  "eventId": "uuid",
  "eventType": "OrderCreated",
  "schemaVersion": 1,
  "occurredAt": "2026-10-27T10:30:00Z",
  "producer": "order-service",
  "aggregateType": "Order",
  "aggregateId": "order-uuid",
  "correlationId": "order-uuid",
  "causationId": "command-or-event-uuid",
  "payload": {}
}
```

- `eventId` xác định một event cụ thể; giữ nguyên qua retry publish. Không dùng `eventId` thay `commandId`/`idempotencyKey`.
- `aggregateId` thường là `orderId`; event payment có thể dùng `paymentId` làm aggregate riêng nhưng vẫn phải mang `orderId` để correlation.
- Event phát sinh từ thay đổi trạng thái Order phải được ghi trong Outbox cùng transaction với thay đổi trạng thái đó.
- Publisher có thể phát lại event; consumer phải deduplicate bằng `eventId` (Inbox/unique constraint) và thực hiện side effect idempotent.
- Message broker, topic/exchange, partition key, delivery guarantee, retention và dead-letter policy chưa được chốt.

### 4.2. Danh mục event

| Event | Producer | Payload tối thiểu | Ý nghĩa |
|---|---|---|---|
| `OrderCreated` | Order Service | `orderId`, `customerId`, `items`, `createdAt`, `expireAt` | Order đã được lưu bền vững ở `PENDING` |
| `ReservationSucceeded` | Inventory capability | `orderId`, `reservationId`, `items`, `status=RESERVED` | Đã xác minh giữ hàng |
| `ReservationRejected` | Inventory capability | `orderId`, `reasonCode`, `status=REJECTED` | Không thể giữ hàng |
| `PaymentSucceeded` | Payment capability | `orderId`, `paymentId`, `amount`, `currency`, `completedAt` nếu có | Capture đã được xác minh thành công |
| `PaymentFailed` | Payment capability | `orderId`, `paymentId`, `reasonCode` | Capture thất bại đã được xác minh |
| `PaymentOutcomeUnknown` | Payment capability | `orderId`, `paymentId`, `operationType`, `reasonCode` | Chưa xác định kết quả; không phải thất bại |
| `OrderConfirmed` | Order Service | `orderId`, `status=CONFIRMED`, `confirmedAt` | Order đã commit xác nhận |
| `OrderFailed` | Order Service | `orderId`, `status=FAILED`, `reasonCode` | Nhánh thất bại đã hoàn thành compensation bắt buộc |
| `ReservationReleased` | Inventory capability | `orderId`, `reservationId`, `status=RELEASED` | Đã xác minh giải phóng reservation |
| `PaymentRefunded` | Payment capability | `orderId`, `paymentId`, `refundId`, `amount`, `status=REFUNDED` | Refund đã được xác minh |
| `OrderExpired` | Order Service | `orderId`, `status=EXPIRED`, `expireAt`, `expiredAt` | Nhánh hết hạn và compensation bắt buộc đã hoàn tất |
| `CompensationFailed` | Saga Orchestrator | `orderId`, `sagaId`, `actionType`, `attemptCount`, `lastErrorCode` | Bù trừ chưa hoàn tất; cần retry/điều tra |
| `ReconciliationRequired` | Saga/Reconciliation Worker | `orderId`, `paymentId`, `operationType`, `reasonCode` | Đưa giao dịch chưa rõ vào luồng đối soát |

Tên event trong bảng là tên đề xuất, không khẳng định đã được khai báo trong code. Dev cần chọn tên canonical duy nhất và cập nhật mọi tài liệu/state machine theo tên đó. Không phát đồng thời nhiều tên alias cho cùng một chuyển trạng thái.

### 4.3. Ví dụ payload event

**OrderCreated**
```json
{
  "eventId": "00000000-0000-4000-8000-000000000010",
  "eventType": "OrderCreated",
  "schemaVersion": 1,
  "occurredAt": "2026-10-27T10:30:00Z",
  "producer": "order-service",
  "aggregateType": "Order",
  "aggregateId": "00000000-0000-4000-8000-000000000002",
  "correlationId": "00000000-0000-4000-8000-000000000002",
  "causationId": "create-order-command-uuid",
  "payload": {
    "orderId": "00000000-0000-4000-8000-000000000002",
    "customerId": "00000000-0000-4000-8000-000000000001",
    "items": [{ "sku": "DEMO-ITEM", "quantity": 2 }],
    "createdAt": "2026-10-27T10:30:00Z",
    "expireAt": "2026-10-27T10:45:00Z"
  }
}
```

**PaymentOutcomeUnknown**
```json
{
  "eventId": "00000000-0000-4000-8000-000000000011",
  "eventType": "PaymentOutcomeUnknown",
  "schemaVersion": 1,
  "occurredAt": "2026-10-27T10:31:00Z",
  "producer": "payment-service",
  "aggregateType": "Order",
  "aggregateId": "00000000-0000-4000-8000-000000000002",
  "correlationId": "00000000-0000-4000-8000-000000000002",
  "causationId": "capture-command-uuid",
  "payload": {
    "orderId": "00000000-0000-4000-8000-000000000002",
    "paymentId": "00000000-0000-4000-8000-000000000003",
    "operationType": "CAPTURE",
    "status": "UNKNOWN",
    "reasonCode": "PROVIDER_TIMEOUT"
  }
}
```

## 5. Quy tắc trạng thái và thứ tự

1. **Order:** các trạng thái cuối được quy định tại `09-state-machine.md`; tối thiểu phải ngăn `CONFIRMED` và `EXPIRED` cùng đúng cho một Order.
2. **Claim hết hạn:** chỉ chuyển `PENDING -> EXPIRING` bằng cập nhật có điều kiện; dùng đồng hồ DB UTC. Worker không thắng phải đọc lại trạng thái.
3. **Capture/expiry race:** theo D-01, callback thành công đến muộn không được ghi đè nhánh đã claim. Nếu đã thu tiền nhưng Order không thể xác nhận, đối soát/refund theo D-05.
4. **Reservation:** chỉ release reservation thuộc đúng `orderId`; release không được cộng tồn kho nhiều lần. Reserve đến muộn sau release phải bị từ chối hoặc vô hiệu theo A-13.
5. **Outbox:** thay đổi Order và event tương ứng commit nguyên tử trong transaction cục bộ. Không phát event trước commit.
6. **Notification:** lỗi gửi notification không được làm đảo trạng thái Order. Retry event giữ nguyên `eventId`.
7. **Thứ tự event:** consumer không được chỉ dựa vào thứ tự đến của message để quyết định trạng thái cuối; kiểm tra aggregate/version hoặc trạng thái bền vững nếu broker không bảo đảm thứ tự.
8. **Versioning:** thêm trường tương thích theo quy tắc schema; thay đổi breaking cần tăng version và có kế hoạch chuyển đổi. Không tái sử dụng tên trường với ngữ nghĩa khác.

## 6. Ma trận lỗi và hành vi bắt buộc

| Tình huống | API/command kết quả | Hành vi bắt buộc |
|---|---|---|
| Request thiếu dữ liệu/quantity không dương | HTTP 400 | Không tạo Order hoặc side effect |
| Thiếu `Idempotency-Key` | HTTP 400 | Không tạo Order |
| Cùng key, payload khác | HTTP 409 | Không thực hiện tác dụng phụ |
| POST timeout sau khi server có thể commit | Không rõ với client | Retry cùng key/payload hoặc GET trạng thái; không tạo key mới |
| Inventory không phản hồi | `UNKNOWN` cho reserve | Query bằng cùng key trước khi retry; không release nếu reserve chưa rõ |
| Payment capture timeout | `UNKNOWN` | Query/reconcile; không capture key mới, không release/refund khi chưa xác minh |
| Payment xác minh `FAILED` | `FAILED` | Claim nhánh thất bại; release reservation nếu đã reserve |
| Release/refund timeout | `UNKNOWN` | Query bằng cùng key; chưa đánh dấu compensation hoàn tất |
| TTL tới hạn nhưng payment `UNKNOWN` | Chưa claim expiry | Giữ reservation, tiếp tục đối soát |
| Hai worker cùng claim Order | Một cập nhật thắng | Chỉ worker thắng được xử lý nhánh |
| Duplicate event | Cùng `eventId` | Consumer deduplicate; không lặp side effect |
| Outbox publish thành công nhưng crash trước khi mark sent | Event có thể phát lại | Consumer idempotent; publisher tiếp tục |
| Notification ngừng hoạt động | Event chưa giao | Retry/DLQ theo chính sách; không đổi kết quả Order |
| Compensation vượt retry limit | Saga chưa hoàn tất | Lưu trạng thái lỗi, cảnh báo và retry/đối soát; không chốt terminal sớm |

## 7. Bảo mật và dữ liệu

- Yêu cầu xác thực và kiểm tra role/ownership tại server; không tin role hoặc `customerId` do client tự gửi.
- Session cookie dùng thuộc tính bảo vệ đã cấu hình; bật `SESSION_COOKIE_SECURE=true` khi chạy HTTPS. Mutation dùng CSRF token.
- Không phát mật khẩu, session ID, CSRF token, credential hoặc thông tin thanh toán nhạy cảm trong event.
- Chỉ đưa dữ liệu cá nhân cần thiết vào event; ưu tiên `accountId`/reference thay vì sao chép hồ sơ người dùng.
- API không được để lộ sự tồn tại của đơn thuộc tài khoản khác. Audit log ghi sự kiện từ chối theo chính sách hiện hành.
- Các event và idempotency records phải tuân thủ chính sách retention; không xóa dữ liệu đang cần cho Saga, refund hoặc reconciliation.

## 8. Việc Dev cần chốt trước khi đóng hợp đồng

| ID | Câu hỏi cần xác nhận | Đầu ra cần có |
|---|---|---|
| DEV-01 | Có dùng HTTP nội bộ, gọi trực tiếp trong ứng dụng hay message broker cho Inventory/Payment/Notification? | Component/deployment diagram và transport contract |
| DEV-02 | Chốt schema chuẩn của `OrderResponse`, `ProblemDetail` và các lỗi validation/CSRF/authorization | OpenAPI spec và ví dụ response thực tế |
| DEV-03 | Phạm vi duy nhất của `Idempotency-Key`, thời gian lưu key và fingerprint payload | DB constraint, retention và test duplicate/conflict |
| DEV-04 | Chốt `commandId`, `eventId`, `correlationId`, `causationId`, aggregate version | Schema envelope và quy tắc tracing |
| DEV-05 | Chốt canonical state/event names, đặc biệt `UNKNOWN`, `COMPENSATION_FAILED`, `RECONCILIATION_REQUIRED`, `OrderExpired` | Cập nhật state machine, enum và tài liệu đồng bộ |
| DEV-06 | Cách tra cứu capture/refund/reservation sau timeout; tính chất idempotent của provider/mock | Interface và contract tests |
| DEV-07 | Chốt amount/currency, rounding, refund một phần/toàn phần và đối chiếu payment muộn | Payment contract và business rules |
| DEV-08 | Chọn broker, topic/exchange, partition key, delivery semantics, retry, DLQ và schema registry nếu có | AsyncAPI/broker configuration |
| DEV-09 | Chốt event ordering/versioning và Inbox deduplication | Consumer contract và test duplicate/out-of-order |
| DEV-10 | Chốt claim token/lease, retry/backoff, lịch reconciliation, TTL và DB clock | Cấu hình worker và test recovery/race |
| DEV-11 | Xác nhận luồng fulfillment `RECEIVED -> PREPARING -> DELIVERED` và quyền Staff/store | API contract và authorization tests |
| DEV-12 | Chốt SLA cho notification, lỗi provider và xử lý thủ công | NFR/operational runbook |

## 9. Truy vết và kiểm thử nghiệm thu

| Hợp đồng | Requirement/rule liên quan | Kiểm thử tối thiểu |
|---|---|---|
| Tạo Order và GET trạng thái | FR-01, FR-02, FR-14; BR-03, BR-11 | 202; thiếu key; duplicate cùng payload; cùng key khác payload; timeout sau commit |
| Reserve/release | FR-03, FR-04, FR-07; BR-02, BR-05, BR-08 | đủ/thiếu hàng; timeout sau side effect; duplicate reserve/release; reserve đến muộn |
| Capture/query/refund | FR-05, FR-06, FR-08; BR-03, BR-07, BR-08 | SUCCEEDED, FAILED, UNKNOWN; query sau timeout; refund duplicate; capture muộn |
| TTL/expiry | FR-09, FR-15; BR-01, BR-13; D-01 | expiry vs confirm race; payment UNKNOWN; release/refund chưa hoàn tất; không terminal sớm |
| Outbox/event/notification | FR-10, FR-13; BR-09, BR-12 | crash sau publish trước mark; duplicate event; notification down/recovery |
| Auth/Staff/Admin | Yêu cầu bảo mật và phân quyền hiện hành | role sai; truy cập đơn người khác/store khác; CSRF thiếu/sai; session hết hạn |
| Recovery/reconciliation | FR-11 đến FR-14; BR-10 | restart worker; lease hết hạn; retry/backoff; event out-of-order |

### Điều kiện hoàn thành bản nháp

- [x] Liệt kê API HTTP hiện có và phân biệt với command/event nội bộ đề xuất.
- [x] Mô tả payload đầu vào/đầu ra chính và lỗi quan trọng.
- [x] Định nghĩa nguyên tắc idempotency, duplicate/conflict và `UNKNOWN`.
- [x] Bao gồm event envelope, danh mục event và ví dụ payload.
- [x] Có danh sách câu hỏi rõ ràng để Dev chốt schema, message format và broker integration.
- [ ] Dev xác nhận các quyết định còn mở; chuyển bản nháp thành OpenAPI/AsyncAPI chính thức nếu cần.

**Lưu ý:** Các dấu hoàn thành trên phản ánh phạm vi tài liệu BA của bản nháp, không phải xác nhận rằng mọi endpoint, event, broker hoặc hành vi đề xuất đã được triển khai hay kiểm thử trong mã nguồn.
