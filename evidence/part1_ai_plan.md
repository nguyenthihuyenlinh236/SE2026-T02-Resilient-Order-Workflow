# BT3 – APP-08 – VT-006: AI-assisted implementation plan

> **Trạng thái:** plan được lập trước implementation. Đây là đề xuất để review, chưa thực thi; không có production/test code nào được sửa trong Phần 1.

## Prompt đầy đủ đã dùng

```text
Bạn là Senior Software Engineer. Hãy lập implementation plan cho change request BT3 – APP-08 – VT-006: cho phép tác vụ dài chạy bất đồng bộ và hiển thị trạng thái tiến trình cho người dùng. Risk trọng tâm là retry tạo bản ghi, giao dịch hoặc thông báo trùng.

Chỉ dựa trên repository hiện tại tại branch feature/vt006-async-progress-23001866, baseline a513241d0e71e2c0eb7adaa32198e0b3902cf766. Trước khi đề xuất thay đổi, đọc README, requirements, architecture/API contracts, Order Service controller/DTO/service/repositories, migration và tests thực tế. Kiểm tra sự tồn tại của từng file; không coi tài liệu thiết kế là bằng chứng implementation. Không giả định API taskId, field progress, queue consumer, worker, client hoặc Notification Service tồn tại.

Yêu cầu đầu ra:
1. Tóm tắt hiện trạng có evidence và phân biệt implemented / partial / missing.
2. Đề xuất một vertical slice end-to-end nhỏ nhất: request được tiếp nhận, client dùng định danh contract hiện có để tra cứu trạng thái, xử lý nền cập nhật trạng thái; retry cùng key/payload không tạo tác dụng logic hoặc business effect trùng.
3. Liệt kê file hiện có cần sửa và file mới cần tạo; nêu lý do và điều kiện cho từng file. Không đề xuất file không cần thiết và không lặp test coverage đã có.
4. Đề xuất thứ tự test-first/implementation, happy path, negative path, retry/lost response, concurrency/claim và status response mapping theo test strategy có sẵn.
5. Ghi assumptions, dependencies, risks, contract/operational questions chưa xác định. Không tự đặt retry limit/backoff/lease/NFR khác ngoài yêu cầu đã cho.
6. Phân biệt đề xuất với quyết định đã được xác nhận; không thực hiện code changes.

NFR do change request/assignment cung cấp: workload 60 người dùng/tác vụ đồng thời và p95 không quá 2.0 giây. Nêu rõ metric/điều kiện đo cần xác nhận.
```

## Implementation plan do agent đề xuất

### Hiện trạng liên quan

- Order API đã có `POST /api/v1/orders`, trả `202 Accepted` và `OrderResponse` gồm `orderId`, `status`, `createdAt`, `expireAt`, `correlationId`; đã có `GET /api/v1/orders/{orderId}`.
- `OrderService.create` đang làm việc đồng bộ trong transaction để xác thực, chuẩn hóa request, claim idempotency, ghi Order/items/Saga/RESERVE intent, rồi trả response `PENDING`.
- Database đã lưu idempotency request, Saga step/attempt và các cột claim/lease. `SagaRepository` có finder, attempt insert và conditional status update; chưa có Saga dispatcher/worker hoặc atomic claim method cho step.
- `OrderStateMachine` có các transition được guard và ghi terminal Outbox event trong cùng transaction. Order Service chưa có Outbox Publisher.
- Integration tests đã bao phủ create response, replay cùng key/payload, conflict payload, client scope, cạnh tranh create cùng key, rollback và Outbox/state-machine invariants.
- Inventory Service có reserve/release contract và persistence idempotency. Payment Service có capture API/persistence. Order Service hiện chưa có API client/adapters gọi các service này.

### Vertical slice đề xuất

**Một luồng Order được tiếp nhận bất đồng bộ, chạy từ Order `PENDING` qua xử lý nền tới một kết quả cuối đã xác minh; client theo dõi bằng Order ID/API truy vấn hiện có.** Dùng Order ID, status và route đang có; chưa thêm `taskId`/`progressPercent`.

1. Client POST Order với `X-Client-Id` và `Idempotency-Key`; request transaction lưu Order + Saga intent và trả `202` cùng `orderId/status` như contract hiện tại.
2. Worker lấy Saga step đủ điều kiện bằng cơ chế claim nguyên tử/ownership đã thiết kế; ghi attempt và dispatch operation đã chốt bằng key/payload đã persist.
3. Worker xử lý phản hồi xác định hoặc UNKNOWN theo API đích; không coi timeout là thất bại. Persist kết quả bằng conditional update và cơ chế ownership để worker stale không ghi đè.
4. Với một happy path được chọn, xử lý đủ các bước cần thiết đến trạng thái Order cuối đã xác minh. GET bằng `orderId` trả trạng thái hiện tại đã persist; không đưa phần trăm tiến độ/ETA vào response nếu chưa có contract.
5. Retry request cùng client scope/key/payload trả cùng logical Order; retry downstream dùng định danh/payload operation ban đầu. Dùng test existing làm regression, không viết lại.

**Giới hạn slice:** một happy path của workflow Order và retry boundary được chọn; không triển khai Notification Service/provider hoặc cam kết exactly-once delivery. Outbox row là persistence sẵn có; publisher/consumer delivery retry là slice riêng nếu yêu cầu xác nhận. Trước implementation cần chốt mức hiển thị tiến trình: trạng thái Order hiện có hay trạng thái theo từng Saga step. API hiện tại chỉ trả Order status, không trả step status/progress.

### File dự kiến thay đổi/thêm

| File | Hành động dự kiến | Lý do / điều kiện |
|---|---|---|
| `order-service/src/main/java/com/example/orderworkflow/order/repository/SagaRepository.java` | Sửa | Thêm atomic claim/lease ownership cho Saga step và conditional completion có token/fencing nếu worker đa instance thuộc thiết kế được duyệt. Finder hiện có không claim step. |
| `order-service/src/main/java/com/example/orderworkflow/order/service/SagaWorker.java` | Tạo mới | Poll/consume step, claim, dispatch, classify result, retry/reconcile và persist theo contract. Chọn poller hay broker sau khi xác nhận vận hành. |
| `order-service/src/main/java/com/example/orderworkflow/order/service/InventoryClient.java` | Tạo mới nếu slice chọn Reserve/Release | Gọi đúng Inventory API contract, truyền command/correlation/idempotency identity ổn định. |
| `order-service/src/main/java/com/example/orderworkflow/order/service/PaymentClient.java` | Tạo mới nếu happy path cần Capture | Gọi Payment capture theo API hiện có; không tự thêm Refund API. |
| `order-service/src/main/java/com/example/orderworkflow/order/service/OrderStateMachine.java` | Có thể sửa | Chỉ nếu kết quả worker cần transition chưa có; giữ guards và transaction Outbox hiện có. |
| `order-service/src/main/java/com/example/orderworkflow/order/api/OrderDtos.java` | Có thể sửa sau contract review | Chỉ nếu yêu cầu tiến trình theo step/progress vượt quá field `status` hiện có; không tự thêm field trước khi chốt semantics. |
| `order-service/src/main/resources/db/migration/V006__...sql` | Có thể tạo | Chỉ nếu thiết kế claim/fencing cần cột/constraint/index chưa có. Schema đã có `claim_token`, `claimed_at`, `lease_until`; chưa khẳng định phải migration. |
| `order-service/src/test/java/com/example/orderworkflow/order/SagaWorkerIntegrationTests.java` | Tạo mới | PostgreSQL + downstream stub: claim ownership/concurrency, lost response, same key/payload, UNKNOWN, status progression. |
| `order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java` | Không sửa theo mặc định | Existing tests đã cover idempotency/create. Chỉ sửa nếu contract API đổi; tránh test trùng. |

Không đề xuất sửa `OrderController` để thêm `taskId`/endpoint mới: `POST` đã trả 202 và `GET /{orderId}` đã tồn tại. Không thêm Outbox Publisher/Notification consumer vào slice đầu trừ khi scope yêu cầu.

### Thứ tự thực hiện sau khi plan được duyệt

1. Chốt định nghĩa trạng thái tiến trình hiển thị (Order-level hay step-level), operation trong slice, API contracts downstream, worker trigger model và cách truy vấn UNKNOWN.
2. Viết integration test đầu tiên cho luồng async bằng API contract hiện có, PostgreSQL test setup hiện có và downstream stub đã chọn; assert phản hồi nhận việc, status query, idempotent replay và không có duplicate logical record.
3. Viết test worker cho atomic claim/worker contention, retry sau lost response giữ key/payload, UNKNOWN reconciliation và không nhận update stale token.
4. Implement persistence claim/fencing (migration mới chỉ nếu schema gap chứng minh cần), worker lifecycle và client adapter(s) operation đã chốt.
5. Persist transition/state, dùng Outbox transaction sẵn có cho terminal result; test status query phản ánh dữ liệu commit.
6. Chạy test slice và regression hiện có; đo NFR 60 concurrent / p95 ≤2s trên môi trường/workload được xác định. Performance test thuộc bước implementation/verification khác, không phải Phần 1 này.

### Assumptions

| ID | Assumption / trạng thái | Cách xác minh |
|---|---|---|
| A1 | `orderId` hiện có là định danh client dùng theo dõi tác vụ; không cần `taskId`. Có căn cứ từ API, nhưng yêu cầu assignment vẫn cần xác nhận cách hiển thị. | Review API contract và UI/client contract; xác nhận stakeholder. |
| A2 | GET Order là cách hiển thị status đủ cho VT-006. Chưa xác minh vì DTO chỉ có Order status, không có step progress. | Chốt status granularity và UI acceptance criteria trước khi sửa DTO/API. |
| A3 | Có thể chạy Saga worker nền trong Order Service. Cần xác nhận runtime/lifecycle/deployment. | Review deployment/operational requirement và concurrency model. |
| A4 | Inventory Reserve là operation phù hợp để lát cắt đầu tiên. Đề xuất, không phải quyết định. | Xác nhận workflow test case và downstream operation cùng owner; kiểm tra API contract. |
| A5 | Retry dùng cùng key/payload như command đã persist; downstream replay chống duplicate cho operation chọn. | Kiểm tra API client implementation và integration test replay/lost response. |
| A6 | Cần claim ownership/fencing cho nhiều worker. Schema có lease fields nhưng chưa có claim operation. | Chốt worker concurrency; thiết kế và kiểm thử atomic claim/stale owner trước dispatch. |
| A7 | Tải 60 concurrent và p95 ≤2.0s áp dụng cho endpoint tiếp nhận async. Assignment nêu hai con số nhưng workload/environment còn thiếu. | Chốt workload, tài nguyên, test duration, sampling và metric start/end. |
| A8 | Notification dedup nằm ngoài vertical slice đầu. | Xác nhận event consumer/notification module ownership; nếu trong scope, lập slice riêng với event contract. |

### Risks and dependencies

- Không có Saga worker hoặc HTTP client ở Order Service; idempotency persistence một mình không làm workflow chạy nền.
- Saga step finder không atomically claim. Hai worker có thể cùng đọc step nếu chỉ gọi finder hiện hữu; token/lease fields trong schema không tự bảo vệ update.
- Order GET chỉ phản ánh Order status; trạng thái per-step/progress percentage chưa có API field/contract.
- Retry an toàn cần downstream replay/query semantics cho đúng operation. Inventory API contract hiện có; Payment API chủ yếu có capture, refund không được giả định.
- Outbox write có transaction boundary nhưng không có publisher/consumer trong Order Service; notification at-least-once/dedup phải được đặc tả riêng.
- Cần Testcontainers/PostgreSQL và chọn HTTP stub phù hợp dependency sẵn có; không thêm dependency trước khi xác minh.
- Performance target có số liệu nhưng chưa có test environment/workload định nghĩa; không thể kết luận đạt chỉ bằng unit/integration tests.

### Chưa xác định

Status hiển thị ở mức Order hay Saga step; có cần progress percentage/message/ETA; operation path cụ thể; worker polling hay broker; retryable error classification/backoff; lease/fencing parameters; reconciliation API/query; notification consumer/event delivery requirement; benchmark environment và workload chi tiết.
