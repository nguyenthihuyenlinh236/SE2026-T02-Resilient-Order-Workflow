# Test Strategy — VT-006: Safe Retry cho Saga / Outbox

> **Trạng thái:** Tài liệu thiết kế kiểm thử trước khi code. Chưa triển khai test hoặc sửa source code.  
> **Repository baseline:** `nguyenthihuyenlinh236/SE2026-T02-Resilient-Order-Workflow`, nhánh `dev`, commit `a513241d0e71e2c0eb7adaa32198e0b3902cf766`.  
> **Phạm vi giả định:** Retry an toàn cho Saga step; Outbox Publisher chỉ nằm trong phạm vi nếu change request VT-006 chính thức xác nhận.  
> **Blocker:** Chưa tìm thấy đặc tả/issue VT-006 chính thức trong tracked files. Các test đánh dấu `[VS]` là test cho vertical slice đề xuất, chưa phải bằng chứng rằng chức năng đã tồn tại.

## 1. Mục tiêu và nguyên tắc kiểm thử

Mục tiêu là chứng minh rằng retry không tạo tác động nghiệp vụ trùng, không làm sai lệch trạng thái Saga khi kết quả chưa được xác minh, và không phá vỡ các contract đã có của Order API, idempotency và Outbox.

Các nguyên tắc:

1. Ưu tiên test ở ranh giới có thể kiểm chứng: API + database cho Order; worker + persistence + downstream stub cho retry.
2. Không coi timeout là bằng chứng operation thất bại.
3. Mọi retry của cùng một Saga step phải giữ nguyên `idempotency_key` và payload đã lưu.
4. Test cạnh tranh phải kiểm tra quyền sở hữu step/claim, không chỉ kiểm tra các finder trả về danh sách.
5. Giữ các test hiện có cho Order idempotency và Outbox transaction làm regression tests; không viết lại chức năng đã được kiểm chứng.
6. Không đặt giới hạn retry, backoff, lease duration hoặc NFR định lượng mới khi VT-006 chưa xác nhận các giá trị đó.

### Phạm vi chức năng hiện biết

- Order API nhận `X-Client-Id`, `Idempotency-Key`, `X-Correlation-Id` tùy chọn; POST trả `202 Accepted`.
- `OrderResponse` có các field: `orderId`, `status`, `createdAt`, `expireAt`, `correlationId`.
- Order integration tests hiện dùng Spring Boot test, `TestRestTemplate`, PostgreSQL Testcontainers.
- Repository có Saga step, attempt, lease fields và finder; chưa thấy Saga retry dispatcher/worker trong Order source tree.
- Order state transition ghi Outbox trong cùng transaction; chưa thấy Outbox Publisher hoặc Notification consumer trong Order source tree.

Các thông tin trên là căn cứ để chọn test level và partition; không chứng minh VT-006 đã được duyệt hoặc worker đã được triển khai.

## 2. Chiến lược theo test level

### 2.1. Unit tests (UT)

**Mục tiêu:** Kiểm tra logic thuần và các quyết định cục bộ mà không cần khởi động Spring hoặc database.

Các nhóm test dự kiến:

- Phân loại kết quả downstream: success, retryable failure, permanent failure, unknown/timeout.
- Quyết định có được retry step dựa trên status, due time và retry policy đã được phê duyệt.
- Giữ nguyên idempotency key, operation và payload khi tạo command retry.
- Không chuyển `UNKNOWN` thành `FAILED` hoặc `SUCCEEDED` nếu chưa có kết quả xác minh.
- Không cho xử lý tiếp khi claim không thành công hoặc token sở hữu không còn hợp lệ.
- Mapping từ service/domain result sang `OrderResponse` hoặc response DTO tương ứng, nếu có thay đổi mapping.

**Không dùng Unit Test để kết luận:**
- Transaction rollback thật của PostgreSQL.
- Tính duy nhất dưới cạnh tranh nhiều transaction.
- HTTP serialization/deserialization thực tế.
- Downstream chỉ thực hiện một tác động khi response bị mất.

Các hành vi đó cần integration/API test.

### 2.2. Integration / API tests (API-IT)

**Mục tiêu:** Kiểm tra contract HTTP, validation, transaction và persistence thật của từng service.

**Order API + PostgreSQL:**

- Dùng cách tổ chức hiện có: Spring Boot test, `TestRestTemplate`, PostgreSQL Testcontainers.
- Kiểm tra POST create, replay cùng key/payload, conflict cùng key nhưng payload khác, key scope theo `X-Client-Id`.
- Kiểm tra response JSON/DTO mapping và các field thời gian/correlation.
- Kiểm tra invalid request không tạo Order, idempotency record hoặc Saga.
- Kiểm tra lỗi giữa transaction rollback đúng các bản ghi liên quan.

**Saga retry vertical slice `[VS]`:**

- Khi dispatcher được triển khai, dùng PostgreSQL thật để kiểm tra claim/lease/token, attempt và conditional update.
- Dùng downstream stub/fake HTTP server có thể điều khiển kết quả và ghi nhận số lần tác động; chỉ chọn thư viện/công cụ sau khi kiểm tra dependency hiện có hoặc được nhóm thống nhất.
- Mô phỏng trường hợp downstream đã xử lý request nhưng response bị mất; lần retry phải dùng cùng key/payload và downstream chỉ ghi nhận một tác động nghiệp vụ.
- Mô phỏng hai worker cùng lấy một step; chỉ worker claim thành công mới được dispatch.
- Mô phỏng stale worker tiếp tục sau khi lease hết hạn; cập nhật của worker cũ phải bị từ chối nếu fencing/ownership là contract đã chọn.

**Outbox (nếu thuộc VT-006):**

- Kiểm tra terminal state + Outbox insert atomic bằng database.
- Publisher test cần broker hoặc broker stub theo event contract được phê duyệt; không giả định dependency AMQP hiện có đồng nghĩa publisher đã tồn tại.

### 2.3. End-to-End tests (E2E)

**Mục tiêu:** Chỉ kiểm tra một luồng nghiệp vụ xuyên service sau khi đã có orchestrator/dispatcher và API clients thực tế.

E2E phù hợp trong giai đoạn sau:

1. Client gửi POST tạo Order.
2. Order Service lưu Order và Saga intent.
3. Worker dispatch một operation đã được xác nhận (ví dụ RESERVE nếu VT-006 chọn operation này).
4. Downstream trả kết quả hoặc xảy ra timeout; worker query/retry theo contract.
5. Order/Saga chỉ cập nhật khi kết quả được xác minh.
6. Nếu luồng có terminal event, Order và Outbox được ghi theo đúng transaction; publish/consumer được kiểm tra riêng theo contract.

**Hiện tại chưa đưa E2E đa service vào vertical slice đầu tiên**, vì repository snapshot chưa có Saga dispatcher/HTTP clients ở Order Service và chưa có Outbox Publisher/Notification consumer. Viết E2E trước khi các thành phần và contract này tồn tại sẽ cần dựng giả một luồng chưa được triển khai, khó phân biệt lỗi ứng dụng với lỗi môi trường. Trước mắt dùng API-IT với downstream stub để kiểm tra retry boundary; bổ sung E2E khi workflow xuyên service được xác nhận và triển khai.

### 2.4. Những test chưa cần thiết và lý do

| Test chưa cần ở giai đoạn này | Lý do |
|---|---|
| Full E2E qua Notification Service và nhà cung cấp gửi thông báo | Chưa thấy Notification Service/consumer trong source tree Order; chưa có contract và ownership được xác nhận. |
| E2E qua RabbitMQ thật cho Outbox Publisher | Chưa thấy Publisher/listener chạy trong Order; chỉ có dependency AMQP không đủ chứng minh flow tồn tại. Chỉ thêm khi VT-006 yêu cầu và broker contract được chốt. |
| Load/stress test với ngưỡng `p95 ≤ 2s` và 60 concurrent requests | Ngưỡng này chưa có căn cứ phê duyệt. Chưa có workload/môi trường benchmark được thống nhất. |
| Chaos test trên cả cụm service hoặc Kubernetes | Quá rộng so với vertical slice retry một operation; chưa có deployment topology/operational SLO được xác nhận. |
| Test mọi tổ hợp giữa mọi status × mọi retry count × mọi payload | Tạo tổ hợp dư thừa. Dùng equivalence partitioning và boundary cases có rủi ro cao; bổ sung tổ hợp khi có rule chính thức. |
| Test Notification consumer dedup / exactly-once end-to-end | Consumer chưa được tìm thấy; không thể nghiệm thu hành vi của module chưa tồn tại. |
| Test retry đến một con số tối đa cụ thể | Retry limit/backoff chưa được xác nhận trong VT-006; không tự đặt max attempts. Khi có policy, bổ sung boundary `limit - 1`, `limit`, `limit + 1`. |

## 3. Equivalence partitioning

Mỗi partition chọn một đại diện có ý nghĩa khác biệt về hành vi. Với dữ liệu có ranh giới, bổ sung boundary-value test thay vì kiểm tra mọi giá trị.

### 3.1. Idempotency key và client scope

| Input | Lớp tương đương hợp lệ | Lớp tương đương không hợp lệ / xung đột | Đại diện được chọn và lý do |
|---|---|---|---|
| `Idempotency-Key` | Key hiện diện, không rỗng, được gửi lại đúng nguyên văn. | Header bị thiếu; key rỗng/blank nếu bị validation từ chối. | Một key hợp lệ đại diện cho đường tạo mới; cùng key lặp lại đại diện replay. Header thiếu đại diện cho lỗi request bắt buộc. Không tự đặt format/độ dài tối đa khi contract chưa nêu. |
| Cùng key và payload | Cùng client scope + cùng payload chuẩn hóa. | Cùng client scope + payload chuẩn hóa khác. | Dùng một cặp request có cùng key/payload để chứng minh replay; đổi quantity để chứng minh conflict. Đây là hai nhánh hành vi khác nhau. |
| Client scope | Cùng key ở hai `X-Client-Id` khác nhau là hai scope độc lập theo test hiện có. | Không có client ID hoặc client ID không hợp lệ nếu contract quy định validation. | Dùng cùng key và cùng body với hai client ID để chứng minh không xung đột chéo scope. Không mở rộng validation client ID khi chưa có contract. |

### 3.2. Trạng thái Saga step / task

| Input | Lớp tương đương hợp lệ cho xử lý | Lớp không đủ điều kiện / không hợp lệ | Đại diện được chọn và lý do |
|---|---|---|---|
| Step status | `NOT_STARTED` đến hạn và được claim; `IN_PROGRESS` có lease hợp lệ do worker sở hữu. | `SUCCEEDED`/`FAILED` terminal; `IN_PROGRESS` do worker khác giữ lease; `UNKNOWN` chưa được xác minh. | Chọn một step `NOT_STARTED` để kiểm tra dispatch; một terminal step để chứng minh không retry; một `UNKNOWN` để chứng minh không kết thúc mù. |
| Claim/lease | Claim thành công với token hiện hành. | Claim thất bại; lease hết hạn; token stale hoặc không khớp. | Dùng hai worker tranh cùng một step; một worker claim trước, worker còn lại không được gửi. Sau lease expiry, token cũ không được ghi kết quả nếu fencing là contract. |
| Operation | Operation nằm trong allowlist được VT-006 xác nhận. | Operation không được hỗ trợ/không được cấu hình. | Chỉ test operation được chốt. Không mặc định RESERVE là operation mục tiêu chỉ vì step này được tạo khi tạo Order. |

### 3.3. Số lần retry / thời điểm retry

| Input | Lớp tương đương | Lớp không hợp lệ / không được retry | Đại diện được chọn và lý do |
|---|---|---|---|
| Retry count | Lần gửi đầu (`0` attempt trước); đã retry nhưng vẫn dưới giới hạn được duyệt; step đến hạn retry. | Đã đạt/vượt giới hạn nếu policy có giới hạn; step chưa đến hạn; step terminal. | Chọn lần gửi đầu và một lần retry để kiểm tra giữ key/payload. Chỉ thêm test boundary quanh retry limit sau khi giá trị limit được chốt. |
| Error/outcome | Lỗi tạm thời hoặc timeout cần query/retry theo policy. | Lỗi permanent; kết quả `UNKNOWN` chưa thể kết luận; response thành công đã xác minh. | Timeout sau khi downstream xử lý là đại diện rủi ro trùng cao nhất; permanent failure đại diện nhánh không retry; success đại diện hoàn tất bình thường. |
| Lease time | Lease còn hiệu lực và thuộc worker hiện tại. | Lease hết hạn hoặc thuộc worker khác. | Kiểm tra một lease hợp lệ và một lease hết hạn; không cần quét mọi timestamp. |

### 3.4. Payload tạo Order / Saga command

| Input | Lớp tương đương hợp lệ | Lớp tương đương không hợp lệ | Đại diện được chọn và lý do |
|---|---|---|---|
| `CreateOrderRequest` | `customerId`, `storeId` là string không blank; `items` không rỗng; mỗi item có SKU string và quantity số nguyên dương. | Thiếu field bắt buộc; string blank; `items` rỗng; quantity `0`, âm, thập phân hoặc sai kiểu; SKU/customer/store sai kiểu. | Một request hợp lệ với một item đại diện happy path; quantity `0` đại diện vi phạm positive; `items: []` đại diện vi phạm non-empty; numeric/string sai kiểu đại diện strict deserialization. |
| Catalog reference | SKU active thuộc đúng store. | SKU không tồn tại; SKU inactive; SKU thuộc store khác. | Chọn một SKU active để tạo thành công và một SKU missing/inactive để kiểm tra từ chối trước khi ghi dữ liệu. |
| Saga request snapshot | Payload đã lưu có operation, key, payload/hash nhất quán. | Payload/key bị thay đổi so với snapshot đã tạo. | Retry phải dùng snapshot ban đầu; kiểm tra key/payload/hash qua lần retry thay vì tạo command mới. |

### 3.5. Response mapping

| Input/response | Lớp hợp lệ | Lớp lỗi mapping | Đại diện được chọn và lý do |
|---|---|---|---|
| `OrderResponse` | `orderId`, `status`, `createdAt`, `expireAt`, `correlationId` được ánh xạ từ kết quả tạo/lấy Order đúng field. | Field bị null, đảo nhầm, lấy nhầm nguồn hoặc serialize sai tên/kiểu. | Dùng một response tạo Order và đối chiếu từng field; sau đó GET cùng `orderId` phải trả DTO tương ứng. Đây là test nhạy với mutation đổi nhầm getter/field mapping. |

## 4. Danh sách test cases

**Ký hiệu Level:** `UT` = Unit Test; `API-IT` = Integration/API Test; `E2E` = End-to-End.  
**Ký hiệu `[VS]`:** thuộc vertical slice retry nhỏ nhất đề xuất. Các test `[VS]` chưa thể chạy trước khi worker/dispatcher và contract operation được xác nhận.  
**Priority:** `P0` = chặn phát hành/đúng đắn cốt lõi; `P1` = quan trọng; `P2` = bổ sung.

| ID | Risk/Requirement | Level | Precondition/Input (partition nào) | Expected result | Priority |
|---|---|---|---|---|---|
| TS-01 | Order API tạo Order hợp lệ; regression cho contract hiện có | API-IT | Payload hợp lệ: customer/store hợp lệ, một SKU active, quantity nguyên dương; key hợp lệ | HTTP `202`; tạo đúng một Order, một Saga và một RESERVE intent; status ban đầu `PENDING` | P0 |
| TS-02 | Thiếu idempotency key làm request không hợp lệ | API-IT | Negative: thiếu header `Idempotency-Key` (partition key không hợp lệ) | HTTP `400`; không tạo Order hoặc idempotency record | P0 |
| TS-03 | Payload validation chặn dữ liệu không hợp lệ | API-IT | Negative: `items` rỗng hoặc quantity `0`/sai kiểu (partition payload invalid) | HTTP `400`; trả lỗi validation; không ghi Order/idempotency record | P0 |
| TS-04 | Idempotency replay không tạo Order thứ hai | API-IT | Cùng `X-Client-Id`, cùng key, cùng payload chuẩn hóa gửi hai lần | Lần hai replay response ban đầu; chỉ một Order/Saga/step; không gia hạn TTL hoặc tạo tác động mới | P0 |
| TS-05 | Cùng key nhưng payload khác phải conflict | API-IT | Negative: cùng client scope và key, thay quantity trong payload chuẩn hóa | HTTP `409`, code `IDEMPOTENCY_KEY_CONFLICT`; không tạo Order thứ hai | P0 |
| TS-06 | Idempotency key được scope theo client | API-IT | Cùng key và payload nhưng hai `X-Client-Id` khác nhau | Hai request độc lập được chấp nhận theo contract hiện có; tạo hai Order khác nhau | P1 |
| TS-07 | Mapping response field phải chính xác (mutation guard) | API-IT | Tạo Order với `X-Correlation-Id` xác định; đọc response `OrderResponse` | JSON/DTO map đúng `orderId`, `status`, `createdAt`, `expireAt`, `correlationId`; `status=PENDING`; `expireAt-createdAt` theo TTL cấu hình; GET cùng ID trả đúng Order. Test phải assert từng field, không chỉ assert body khác null | P0 |
| TS-08 | Lỗi giữa transaction không để lại dữ liệu một phần | API-IT | Inject lỗi khi insert Saga step sau khi bắt đầu tạo Order | Transaction rollback; không còn Order, Saga, step hoặc idempotency claim | P0 |
| TS-09 | Chỉ một worker được dispatch cùng step | API-IT `[VS]` | Một step `NOT_STARTED` đến hạn; hai worker cùng claim đồng thời | Chỉ một worker claim thành công và được phép gửi request; worker còn lại không tạo attempt SEND/không gọi downstream | P0 |
| TS-10 | Retry không tạo tác động nghiệp vụ trùng khi response bị mất | API-IT `[VS]` | Failure path: downstream nhận request và ghi nhận tác động; response bị drop/timeout; dispatcher retry cùng step với cùng key/payload | Retry dùng đúng key/payload đã lưu; downstream chỉ có một tác động nghiệp vụ hiệu lực; step chỉ thành công sau khi kết quả được xác minh | P0 |
| TS-11 | UNKNOWN không được đánh dấu FAILED/SUCCEEDED khi chưa xác minh | UT + API-IT `[VS]` | Step ở `UNKNOWN`; query/reconciliation chưa trả kết quả xác định | Step vẫn ở trạng thái chưa xác định/đang xử lý; không kết thúc Order/Saga dựa trên timeout | P0 |
| TS-12 | Stale worker không được ghi đè kết quả sau khi mất lease | API-IT `[VS]` | Worker A claim step; lease hết hạn; worker B takeover bằng token mới; A gửi update với token cũ | Update của A bị từ chối/không thay đổi trạng thái; chỉ owner/token hợp lệ được persist kết quả | P0 |
| TS-13 | Step terminal không bị retry lại | UT `[VS]` | Step ở `SUCCEEDED` hoặc `FAILED` terminal; retry scan được gọi | Dispatcher không dispatch step terminal; không tạo attempt SEND mới | P1 |
| TS-14 | Retry policy không retry lỗi permanent | UT `[VS]` | Downstream outcome phân loại là permanent failure | Không lên lịch retry; step chuyển theo policy đã được duyệt; không tự đổi thành UNKNOWN nếu response đã xác định permanent | P1 |
| TS-15 | Outbox write atomic với terminal state | API-IT | Ép insert Outbox thất bại trong transaction terminal transition | Order/Saga transition rollback; không tồn tại terminal state thiếu event tương ứng | P0 |
| TS-16 | Outbox publish retry giữ event identity (chỉ nếu thuộc VT-006) | API-IT `[VS-OUTBOX]` | Event đã persist; lần publish đầu timeout/mất ack; publisher gửi lại | Publisher dùng cùng event ID/payload; không insert domain event mới; consumer dedup chỉ test khi consumer contract tồn tại | P1 |

### 4.1. Coverage theo yêu cầu bắt buộc

- **Negative tests:** TS-02, TS-03 và TS-05.
- **Failure-path gắn với risk “retry gây trùng”:** TS-10 mô phỏng downstream đã xử lý nhưng response bị mất, sau đó retry cùng key/payload.
- **Response field mapping / mutation guard:** TS-07 assert riêng từng field của `OrderResponse`.
- **Concurrency/claim:** TS-09 và TS-12.
- **Outbox atomicity:** TS-15; publisher retry TS-16 chỉ khi scope VT-006 có Outbox Publisher.

## 5. Vertical slice và thứ tự thực hiện test

### 5.1. Vertical slice được chọn

**Vertical slice đề xuất:** retry an toàn cho đúng một Saga operation được VT-006 xác nhận, từ claim step đến dispatch, xác minh kết quả và persist trạng thái.

Không mặc định operation là RESERVE. `OrderService` hiện tạo RESERVE intent, nhưng chưa có căn cứ cho thấy VT-006 chọn operation này. Nếu người giao việc xác nhận RESERVE, dùng Inventory Reserve API contract; nếu chọn operation khác, thay downstream contract và test data tương ứng.

### 5.2. Test thuộc vertical slice

<badge>VS — triển khai cùng slice</badge>

| Thứ tự | Test | Vì sao thuộc slice |
|---|---|---|
| 1 | TS-09 | Xác nhận claim độc quyền trước khi worker gửi request. |
| 2 | TS-10 | Chứng minh retry sau response mất không tạo tác động nghiệp vụ trùng — risk quan trọng nhất. |
| 3 | TS-11 | Bảo vệ trạng thái UNKNOWN khi chưa xác minh được kết quả. |
| 4 | TS-12 | Ngăn stale worker ghi kết quả sau khi mất quyền sở hữu. |
| 5 | TS-13 | Ngăn dispatch lại step đã terminal. |
| 6 | TS-14 | Phân biệt lỗi permanent với lỗi có thể retry theo policy đã duyệt. |

**Regression/API tests cần chạy cùng slice:** TS-01, TS-04, TS-05, TS-07 và TS-08. Các test này bảo vệ contract tạo Order, idempotency, response mapping và transaction hiện có.

**Ngoài slice đầu tiên:** TS-16 và E2E qua Notification consumer. Chỉ đưa vào nếu VT-006 yêu cầu Outbox Publisher/consumer và có event contract được duyệt.

### 5.3. Thứ tự chạy đề xuất

1. Chạy regression hiện có của Order API/idempotency/Outbox trước khi thay đổi.
2. Viết UT cho phân loại outcome, retry eligibility và giữ key/payload.
3. Viết API-IT với PostgreSQL cho atomic claim, concurrency và fencing.
4. Viết failure-path API-IT với downstream stub: xử lý thành công nhưng làm mất response, sau đó retry.
5. Chạy lại regression tests và toàn bộ module tests.
6. Chỉ bổ sung E2E/broker tests khi các module và contract tương ứng tồn tại.

## 6. Điều kiện cần xác nhận trước khi implementation

1. Nguyên văn change request VT-006 và AC chính thức.
2. Operation nào thuộc scope (RESERVE, CAPTURE, RELEASE, REFUND hay Outbox publish).
3. Retry policy: lỗi nào retry được, giới hạn attempt, backoff, thời điểm due.
4. Claim/lease/fencing semantics và hành vi takeover khi worker chết.
5. API/query contract để xác minh kết quả khi timeout hoặc response bị mất.
6. Downstream idempotency contract cho đúng operation.
7. Outbox Publisher/broker/consumer có thuộc scope hay không.
8. NFR/benchmark đã được stakeholder phê duyệt (nếu có).

Không tự đặt giá trị retry limit, backoff, lease duration hoặc performance threshold để làm test pass. Khi các giá trị được xác nhận, bổ sung boundary tests quanh giá trị đó.

## 7. Tóm tắt

Chiến lược ưu tiên Unit Test cho logic retry và phân loại lỗi; Integration/API Test cho HTTP contract, PostgreSQL transaction, claim/fencing và failure-path; E2E chỉ bổ sung khi workflow xuyên service thực sự tồn tại. Các test quan trọng nhất của vertical slice là claim độc quyền, retry sau khi downstream đã xử lý nhưng response bị mất, giữ trạng thái UNKNOWN và chặn stale worker. Test response mapping `OrderResponse` được đưa vào regression để phát hiện mutation làm ánh xạ sai `orderId`, `status`, `createdAt`, `expireAt` hoặc `correlationId`.

**Trạng thái hiện tại: chỉ lập test strategy; chưa viết code, chưa tạo test file, chưa sửa repository source, chưa commit hoặc push.**
