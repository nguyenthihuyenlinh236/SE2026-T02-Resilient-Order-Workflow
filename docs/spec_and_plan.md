# VT-006 — Problem Statement và Implementation Plan

> **Trạng thái:** Đề xuất trước khi code. Chỉ khảo sát và lập kế hoạch; chưa thay đổi mã nguồn.
>
> **Phạm vi repo khảo sát:** nhánh `dev` của `nguyenthihuyenlinh236/SE2026-T02-Resilient-Order-Workflow`.
>
> **Lưu ý quan trọng:** Trong repo hiện không tìm thấy đặc tả/issue có mã `VT-006`. Vì vậy, tài liệu này tạm diễn giải VT-006 theo hướng **bảo đảm retry an toàn, idempotent cho các bước Saga và phát sự kiện Outbox**. Cần xác nhận lại nội dung VT-006 chính thức trước khi triển khai.

## A. Problem statement

Khi một bước nghiệp vụ trong Saga gặp timeout hoặc lỗi tạm thời, Order Service cần có khả năng thử lại mà không lặp lại tác động nghiệp vụ đã được thực hiện thành công. Nếu lần gửi trước đã được service đích xử lý nhưng phản hồi bị mất, việc gửi lại với một định danh hành động không ổn định có thể tạo Reservation, capture/refund hoặc sự kiện trùng. Repo hiện đã lưu idempotency key, payload hash, trạng thái bước Saga, attempt và Outbox, nhưng chưa thấy thành phần worker/orchestrator/publisher chạy nền trong `order-service` để điều phối retry và phát sự kiện. VT-006 cần hoàn thiện lát cắt retry an toàn dựa trên dữ liệu tiến trình đã lưu, đồng thời không đánh dấu bước hoặc Saga hoàn tất khi kết quả chưa được xác minh.

## B. Acceptance Criteria

### AC-01 — Retry cùng một bước không tạo thêm hành động nghiệp vụ

**Given** một Saga step đã có `idempotency_key` và `request_payload` được lưu bền vững  
**When** cùng step được gửi lại N lần do timeout/lỗi tạm thời, với cùng operation, key và payload  
**Then** mọi lần gửi lại phải dùng đúng idempotency key và payload ban đầu; phía service đích chỉ ghi nhận một tác động nghiệp vụ có hiệu lực; Order Service không tạo step mới cho cùng `(saga_id, operation)`.

### AC-02 — Cùng key nhưng payload khác phải bị từ chối

**Given** một yêu cầu tạo Order đã được ghi nhận với một `Idempotency-Key` và payload chuẩn hóa  
**When** client gửi lại cùng key nhưng payload chuẩn hóa khác  
**Then** API trả HTTP 409 với mã `IDEMPOTENCY_KEY_CONFLICT`; không tạo Order, Order Item hoặc Saga thứ hai.

### AC-03 — Chỉ kết quả đã xác minh mới được kết thúc bước

**Given** một Saga step đang `UNKNOWN` hoặc `IN_PROGRESS`  
**When** worker xử lý retry/đối soát nhưng chưa nhận được kết quả xác minh  
**Then** step không được chuyển sang `SUCCEEDED` hoặc `FAILED`; Order không được chuyển sang trạng thái cuối chỉ dựa trên timeout.

### AC-04 — Cập nhật trạng thái cuối và ghi Outbox phải nguyên tử

**Given** một Order đủ điều kiện chuyển sang trạng thái cuối và cần phát domain event  
**When** transaction cập nhật Order và ghi Outbox được thực hiện  
**Then** nếu insert Outbox thất bại thì cập nhật Order/Saga trong transaction đó phải rollback; nếu commit thành công thì chỉ có một event tương ứng cho cùng Order và event type.

### AC-05 — Retry không làm phát sinh domain event mới cho mỗi lần thử

**Given** một domain event đã được ghi vào Outbox cho một chuyển trạng thái nghiệp vụ  
**When** Outbox Publisher phát lại do chưa xác nhận được lần publish trước  
**Then** Publisher phải phát lại cùng event identity/payload đã lưu; không insert domain event mới chỉ vì retry kỹ thuật. Việc chống gửi thông báo trùng ở Notification Service chỉ nghiệm thu khi consumer/service đó có implementation và contract riêng.

## C. Non-functional constraints

1. **Idempotency bền vững:** cùng một hành động nghiệp vụ phải giữ nguyên idempotency key và payload hash qua mọi lần retry; unique constraint ở database là lớp bảo vệ cuối cùng, không chỉ dựa vào kiểm tra trong Java.
2. **Không hoàn tất khi chưa xác minh:** timeout hoặc mất phản hồi không được tự diễn giải thành thất bại; step phải giữ trạng thái chưa xác định/đang xử lý và đi qua cơ chế query/reconciliation.
3. **Atomicity của Order và Outbox:** thay đổi trạng thái Order cùng domain event tương ứng phải commit/rollback trong cùng transaction DB.
4. **Hiệu năng — ngưỡng đề xuất cần xác nhận:** với 60 request tạo Order đồng thời, p95 của `POST /api/v1/orders` không vượt 2.0 giây trong môi trường CI/staging đã cố định cấu hình. Đây là ngưỡng đề xuất để đo, không phải cam kết hiện hữu trong repo.
5. **Không tự ý chuyển API đồng bộ thành async:** `POST /api/v1/orders` hiện tạo Order và trả response trong luồng request. Chỉ đưa retry/dispatch nền vào worker sau khi xác nhận contract vận hành; không thêm `@Async` hoặc queue consumer chỉ để đáp ứng một NFR chưa được đặc tả.

## D. Invariant / contract quan trọng nhất

> **Với cùng một hành động nghiệp vụ đã được định danh ổn định, mọi lần retry phải dùng cùng idempotency key và cùng payload; kết quả là chỉ có một tác động nghiệp vụ có hiệu lực. Việc ghi nhận bước Saga và domain event cũng phải duy nhất theo định danh nghiệp vụ tương ứng.**

Cụ thể trong phạm vi hiện có:

- Tạo Order: một cặp `(scope, idempotency_key)` chỉ đại diện cho một yêu cầu/payload chuẩn hóa; dùng lại key với payload khác phải conflict.
- Saga: một `(saga_id, operation)` chỉ có một step; `idempotency_key` của step phải ổn định qua retry.
- Outbox: cùng `aggregate_id` và `event_type` không được tạo nhiều event domain trùng nhau.
- Reservation/capture/release/refund: service thực hiện hành động phải tự bảo vệ idempotency bằng key của chính hành động đó.

Không nên viết invariant chung là “một key tạo đúng 1 bản ghi, 1 giao dịch, 1 thông báo” mà không phân biệt loại hành động. Một Order có thể có nhiều saga step hợp lệ (RESERVE, CAPTURE, RELEASE, REFUND); refund là giao dịch riêng, không xóa capture ban đầu. Outbox Publisher có thể phát một event nhiều lần theo at-least-once; điều cần bảo vệ là không tạo event nghiệp vụ mới cho mỗi lần retry và consumer phải deduplicate theo event identity. Repo hiện chưa có Notification Service/consumer implementation để khẳng định “đúng 1 thông báo” ở đầu cuối.

**Vì sao invariant này quan trọng:** timeout không cho biết service đích đã xử lý hay chưa. Nếu retry bằng key mới hoặc tạo lại step/event, hệ thống có thể giữ hàng, thu tiền, hoàn tiền hoặc thông báo nhiều lần. Ngược lại, nếu giữ key/payload ổn định và lưu kết quả/attempt bền vững, hệ thống có thể retry hoặc khôi phục sau restart mà không lặp tác động nghiệp vụ.

## E. Implementation plan

### Nguyên văn prompt yêu cầu phần E

> E. Implementation plan: danh sách file dự định thay đổi/thêm mới, thứ tự các bước, vertical slice nhỏ nhất chạy được, assumptions (đánh số A1, A2...), mỗi assumption ghi rõ "cần kiểm chứng bằng cách nào".
>
> F. Ghi lại nguyên văn prompt này ở đầu phần E.

### E.1. Đối chiếu cấu trúc repo trước khi lập kế hoạch

Repo `dev` hiện có Order Service với code nghiệp vụ và persistence thực tế; không còn đúng với nhận định cũ rằng `order-service` chỉ là skeleton. Các file quan trọng đã được kiểm tra:

| File hiện có | Vai trò / evidence trong file |
|---|---|
| `order-service/src/main/java/com/example/orderworkflow/order/api/OrderController.java` | `@PostMapping` và nhận `Idempotency-Key` (dòng 28–35); GET Order (dòng 38 trở đi). |
| `order-service/src/main/java/com/example/orderworkflow/order/api/OrderDtos.java` | `CreateOrderRequest` và `OrderResponse` (dòng 22–32). |
| `order-service/src/main/java/com/example/orderworkflow/order/service/OrderService.java` | `create(...)` có `@Transactional` (dòng 49–50); claim/replay idempotency (58–68); tạo Saga và RESERVE step (100–103); hoàn tất response snapshot (105); GET read-only (133). |
| `order-service/src/main/java/com/example/orderworkflow/order/repository/OrderRepository.java` | Claim idempotency bằng insert (dòng 26–31); đọc record (33–39); hoàn tất record (75–84). |
| `order-service/src/main/java/com/example/orderworkflow/order/repository/SagaRepository.java` | Tạo Saga/step (23–39); tìm retryable step (65–70); tìm step cần query/lease hết hạn (73–80); ghi attempt (83–109); cập nhật kết quả step có điều kiện (122–126). |
| `order-service/src/main/java/com/example/orderworkflow/order/repository/SagaRecords.java` | Record Saga, SagaStep, Attempt (dòng 8–18), gồm key/hash/status/attempt/lease. |
| `order-service/src/main/java/com/example/orderworkflow/order/repository/OrderStateRepository.java` | Conditional state updates, claim/lease hết hạn (106–125), tìm Order hết hạn/claim hết lease (136–142), ghi Outbox (174–179). |
| `order-service/src/main/java/com/example/orderworkflow/order/service/OrderStateMachine.java` | State transition và transaction boundary; cần đối chiếu toàn file trước khi thay đổi. |
| `order-service/src/main/resources/db/migration/V001__create_order_catalog_and_idempotency.sql` | Schema idempotency ban đầu. |
| `order-service/src/main/resources/db/migration/V002__add_order_saga_state.sql` | Tạo `sagas`, `saga_steps`, `saga_attempts`, `outbox_events`; có unique `(saga_id, operation)`, unique `command_id`, unique `idempotency_key`, unique `(aggregate_id, event_type)`. |
| `order-service/src/main/resources/db/migration/V003__harden_saga_persistence.sql` – `V005__align_outbox_and_late_capture.sql` | Các migration bổ sung/harden schema Saga và Outbox. |
| `order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java` | Test replay cùng key, conflict payload, concurrent create và rollback (đặc biệt dòng 130–155, 177–205, 258–288). |
| `order-service/src/test/java/com/example/orderworkflow/order/OrderStateMachineIntegrationTests.java` | Test Outbox, unknown, retry, concurrency/lease, rollback Outbox (dòng 132–147, 162–190, 218–264, 292–323). |
| `docs/architecture/10-saga-workflow-and-compensation.md` | Contract kiến trúc về idempotency, retry, UNKNOWN, Outbox và Notification (dòng 45–47, 235–253, 255–320). |

**Phần chưa thấy trong source tree `order-service/src/main/java`:** class worker/orchestrator/dispatcher, `@Scheduled`, `@Async`, `@RabbitListener`, Outbox Publisher hoặc Notification consumer. Có repository methods và schema hỗ trợ retry/claim, nhưng không đồng nghĩa worker đã được triển khai. `docs/architecture/10-saga-workflow-and-compensation.md` mô tả các thành phần này ở mức kiến trúc (ví dụ dòng 80–85, 302–327); cần phân biệt tài liệu thiết kế với code chạy thực tế.

### E.2. Danh sách file dự định thay đổi / thêm mới

#### File hiện có dự kiến thay đổi

| File | Dự kiến thay đổi | Lý do / lưu ý |
|---|---|---|
| `order-service/src/main/java/com/example/orderworkflow/order/service/OrderStateMachine.java` | Chỉ sửa nếu kiểm chứng thấy thiếu transition/API cần cho dispatcher; ưu tiên giữ nguyên các guard hiện có. | Đây là nơi tập trung luật chuyển trạng thái; tránh đưa logic retry vào Controller. |
| `order-service/src/main/java/com/example/orderworkflow/order/repository/SagaRepository.java` | Chỉ bổ sung query/claim/update còn thiếu sau khi đối chiếu toàn bộ phương thức hiện tại. | Đã có `findRetryable`, `findDueQueries`, `findExpiredClaims`, `insertAttempt`, `updateStep`; không tạo method trùng chức năng. |
| `order-service/src/main/java/com/example/orderworkflow/order/repository/OrderStateRepository.java` | Chỉ bổ sung thao tác persistence thật sự còn thiếu cho workflow được chọn. | Đã có claim/lease, state update và `insertOutbox`; không viết lại các conditional update hiện có. |
| `order-service/src/test/java/com/example/orderworkflow/order/OrderStateMachineIntegrationTests.java` | Thêm integration tests cho hành vi retry/claim/Outbox mà VT-006 yêu cầu và hiện chưa được cover. | Test hiện đã bao phủ nhiều invariant; phải chạy test trước để tránh duplicate coverage. |
| `order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java` | Chỉ thêm test nếu VT-006 thay đổi contract POST create/idempotency. | Hiện đã có replay, payload conflict, concurrent create và rollback tests. |

#### File mới dự kiến thêm — chỉ khi VT-006 xác nhận cần worker chạy nền

| File mới | Trách nhiệm dự kiến |
|---|---|
| `order-service/src/main/java/com/example/orderworkflow/order/service/SagaRetryDispatcher.java` | Lấy các step đến hạn, claim bằng lease/token, ghi attempt, gọi handler theo operation và persist kết quả. |
| `order-service/src/main/java/com/example/orderworkflow/order/service/OutboxPublisher.java` | Đọc event Outbox chưa publish, phát lại cùng event identity/payload và cập nhật publish state theo contract đã chốt. |
| `order-service/src/test/java/com/example/orderworkflow/order/service/SagaRetryDispatcherTests.java` | Unit tests cho phân loại lỗi, giữ key/payload, retry schedule và không retry step terminal. |
| `order-service/src/test/java/com/example/orderworkflow/order/OutboxPublisherIntegrationTests.java` | Integration tests cho publish retry, không insert domain event mới và phục hồi sau lỗi. |

**Không đưa vào phạm vi file của slice đầu tiên:** `NotificationService`, consumer inbox/dedup table, Payment Service/Inventory Service client adapters mới hoặc RabbitMQ listener. Repo hiện không có Notification Service/consumer trong source tree của `order-service`; thêm chúng ngay sẽ mở rộng phạm vi vượt quá lát cắt nhỏ nhất. Chỉ bổ sung sau khi xác nhận contract và module ownership.

**Migration:** chưa đề xuất migration mới ngay. `V002` đã có unique constraints cho Saga step, idempotency key và Outbox event; cần kiểm chứng schema thực tế và yêu cầu VT-006 trước khi tạo `V006__...sql`. Không sửa migration đã chạy ở môi trường dùng chung.

### E.3. Thứ tự triển khai

1. **Chốt nội dung VT-006:** lấy nguyên văn change request và xác định operation nào cần retry (RESERVE, CAPTURE, RELEASE, REFUND, Outbox publish hay consumer notification). Không suy ra operation từ mã VT-006.
2. **Baseline:** chạy `mvn -pl order-service test` và `mvn -pl order-service verify` trên nhánh làm việc; lưu log riêng. Kiểm tra Docker/Testcontainers vì integration tests phụ thuộc PostgreSQL Testcontainers.
3. **Characterization trước:** đọc toàn bộ `SagaRepository`, `OrderStateMachine`, `OrderStateRepository` và test tương ứng; thêm test khóa hành vi hiện tại nếu VT-006 đòi sửa hành vi đang có.
4. **Vertical slice 1 — retry persistence/claim:** dùng các bảng và repository hiện có để chọn một Saga step đến hạn, claim bằng token/lease, ghi một attempt và đảm bảo worker khác không claim đồng thời. Không gọi service ngoài ở bước này.
5. **Vertical slice 2 — dispatch một operation:** chọn đúng một operation theo VT-006; dispatcher dùng lại `idempotency_key`, `request_payload` và `payload_hash` đã lưu, sau đó cập nhật kết quả theo điều kiện trạng thái. UNKNOWN phải đi query/reconciliation, không coi là FAILED.
6. **Vertical slice 3 — Outbox (nếu thuộc VT-006):** phát lại event đã persist; không tạo event mới cho mỗi lần retry. Giữ nguyên transaction boundary giữa terminal state và insert Outbox.
7. **Kiểm thử hồi quy:** chạy các test Order create/idempotency, Saga state machine, rollback Outbox và concurrency; sau đó chạy toàn bộ `mvn -B clean verify`.
8. **Đánh giá NFR:** đo p95 theo tải đã thống nhất; không ghi nhận đạt ngưỡng nếu chưa chạy benchmark có cấu hình và dữ liệu tái lập.

### E.4. Vertical slice nhỏ nhất chạy được

**Slice đề xuất đầu tiên:** một `SagaRetryDispatcher` xử lý một loại step được chọn (tạm đề xuất RESERVE, nhưng phải xác nhận VT-006), sử dụng dữ liệu đã có trong `saga_steps`.

Luồng tối thiểu:

1. Lấy một step `NOT_STARTED`/`IN_PROGRESS` đến hạn qua repository hiện có.
2. Claim step bằng `claim_token` và `lease_until`; nếu claim thất bại thì worker không gửi request.
3. Tạo attempt `SEND` bằng `insertAttempt`.
4. Gửi request với chính `idempotency_key` và payload đã lưu; không tạo key mới.
5. Persist kết quả bằng conditional update; timeout/unknown không được ghi `SUCCEEDED` hoặc `FAILED`.
6. Unit/integration test chứng minh hai worker cạnh tranh chỉ có một worker claim và retry không thay đổi key/payload.

Slice này **chưa bao gồm** Notification Service, consumer deduplication hoặc cam kết exactly-once ở hệ thống bên ngoài. Nếu VT-006 thực chất yêu cầu xử lý thông báo, phải thiết kế slice khác dựa trên event consumer contract cụ thể.

### E.5. Assumptions và cách kiểm chứng

| ID | Assumption | Cần kiểm chứng bằng cách nào |
|---|---|---|
| A1 | VT-006 yêu cầu hoàn thiện retry an toàn cho Saga, không phải thay đổi response DTO hay business rule khác. | Xin/đọc nguyên văn change request VT-006 từ issue, đề bài hoặc người giao việc; đối chiếu từng AC với tài liệu yêu cầu. |
| A2 | Operation được chọn cho vertical slice đầu tiên là RESERVE. | Kiểm tra VT-006 nêu operation nào; đọc `OrderService.java` nơi tạo step RESERVE (dòng 100–103), đối chiếu Inventory API contract và test integration hiện có. |
| A3 | `SagaRepository.findRetryable`, `findDueQueries`, `findExpiredClaims` đủ để dispatcher claim an toàn. | Đọc toàn bộ SQL/transaction isolation của các method và `OrderStateMachine`; viết integration test với hai worker đồng thời, worker chết và lease hết hạn. Không suy luận an toàn chỉ từ tên method. |
| A4 | Service đích hỗ trợ idempotency bền vững cho operation được retry. | Kiểm tra API contract và implementation của Inventory/Payment Service; gửi lặp cùng key/payload trong integration test và đếm tác động nghiệp vụ. Nếu chưa có contract, không bật retry tự động cho operation đó. |
| A5 | Request payload và idempotency key trong `saga_steps` là snapshot bất biến cho một operation. | Kiểm tra schema, repository update methods, payload hash validation và migration constraints; test rằng retry không thể ghi đè key/payload sau khi step được tạo. |
| A6 | Outbox event có thể được publish at-least-once; consumer cần deduplicate theo event identity. | Đọc publisher/consumer thực tế và contract event; trong repo hiện chưa thấy publisher hoặc Notification consumer ở `order-service/src/main/java`. Chỉ nghiệm thu phần consumer sau khi module đó tồn tại. |
| A7 | Notification Service nằm ngoài vertical slice đầu tiên. | Kiểm tra danh sách Maven modules và recursive source tree; xác nhận với người phụ trách notification. Tài liệu Saga có mô tả Notification Service nhưng source tree đã khảo sát không có implementation trong `order-service`. |
| A8 | p95 ≤ 2.0 giây ở 60 request đồng thời là mục tiêu được chấp nhận. | Xác nhận với stakeholder/đề bài; xây benchmark có dữ liệu seed, máy chạy, cấu hình DB và số lần lặp cố định. Không coi con số ví dụ là yêu cầu chính thức. |
| A9 | PostgreSQL/Testcontainers là môi trường kiểm thử chuẩn cho integration tests của Order Service. | Đọc `order-service/pom.xml`, annotation `@Testcontainers` và cấu hình test; chạy test trên máy có Docker và CI. |
| A10 | Không cần migration mới nếu các unique constraint hiện tại đã bảo vệ invariant của slice. | Đọc toàn bộ V001–V005, kiểm tra schema trên DB test bằng catalog queries và xác định constraint còn thiếu trước khi viết migration mới. |

### E.6. Rủi ro và điều kiện dừng

- Nếu VT-006 yêu cầu “một thông báo đúng một lần” ở đầu cuối, phải dừng trước khi cam kết AC đó: cần có Notification consumer, inbox/dedup storage và contract với nhà cung cấp gửi thông báo.
- Nếu service đích không hỗ trợ idempotency/query theo key, không retry thao tác có thể thu tiền/giữ hàng theo kiểu blind retry.
- Nếu không có nguyên văn VT-006, không được coi các AC/operation đề xuất ở trên là yêu cầu đã được phê duyệt.
- Không thêm `@Scheduled`, `@Async`, `@RabbitListener` hoặc RabbitMQ flow nếu chưa xác định cơ chế triển khai worker, lifecycle, concurrency, retry schedule và observability.
- Không sửa migration V001–V005 đã tồn tại; mọi thay đổi schema phải là migration mới và có test migration.

## F. Kết luận

`order-service` trên nhánh `dev` đã có nền tảng persistence cho idempotency, Saga step, attempt, lease, state transition và Outbox; test cũng đã bao phủ một phần đáng kể các invariant. Khoảng trống đáng chú ý trong source tree là thành phần thực thi worker/dispatcher và Outbox Publisher/Notification consumer, dù tài liệu kiến trúc đã mô tả chúng. Vì chưa tìm thấy đặc tả VT-006 chính thức, kế hoạch này là bản đề xuất có điều kiện: chốt change request và operation trước, sau đó làm vertical slice retry cho đúng một operation, tận dụng repository/schema/test hiện có và chỉ mở rộng sang Outbox/notification nếu VT-006 yêu cầu rõ ràng.
