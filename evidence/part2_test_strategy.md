# BT3 – APP-08 – VT-006: Phần 2 – Risk-based Test Strategy

## Phạm vi và trạng thái repository

- Branch: `feature/vt006-async-progress-23001866`; HEAD/baseline: `a513241d0e71e2c0eb7adaa32198e0b3902cf766`.
- Specification dùng AC-01 đến AC-05 tại `evidence/part1_specification.md`.
- Order API hiện có `POST /api/v1/orders` → `202 Accepted` với `OrderResponse(orderId,status,createdAt,expireAt,correlationId)` và `GET /api/v1/orders/{orderId}`. Order được ghi cùng Saga/RESERVE intent trong request transaction.
- Có Order/Saga persistence, state machine và Testcontainers integration tests. Chưa có Order Saga worker/dispatcher, client adapter gọi Inventory/Payment, Outbox publisher hoặc Notification consumer trong Order source tree.
- Trong test hiện tại, hai worker tranh claim **Order nhánh hết hạn** đã được kiểm thử; không nhầm coverage đó với claim một **Saga step**.
- Các phần dưới là strategy cho yêu cầu async/progress. Test được đánh dấu *new* hoặc *blocked* chưa được viết/chạy ở Phần 2. Không ghi nhận PASS/FAIL.

## A. Test strategy theo risk

Thang đánh giá: **Impact** = thiệt hại nếu xảy ra; **Likelihood** = khả năng trong hiện trạng/đề xuất workflow. Likelihood mô tả exposure hoặc điều kiện có thể xảy ra, không khẳng định lỗi đã xuất hiện.

| Risk | Impact | Likelihood và căn cứ | AC liên quan | Test cần thiết |
|---|---|---|---|---|
| **R1 – Retry tạo Order/Saga/step trùng** | Cao: nhân đôi đơn và workflow có thể kéo theo giữ hàng/thanh toán lặp. | Thấp ở create API hiện tại: unique scope/key, response replay và concurrency được test. Trung bình cho luồng async mới nếu retry không gắn lại logical Order gốc. Không có evidence lỗi hiện tại. | AC-01, AC-03 | API Test replay cùng scope/key/canonical payload; Integration Test kiểm tra count Order/idempotency/Saga/step không tăng. Negative cùng key khác payload trả conflict. Đồng thời test cạnh tranh request lặp. |
| **R2 – Retry sau timeout làm Reserve/Capture nhiều lần** | Rất cao: giữ hàng/thu tiền trùng. | Có thể xảy ra khi downstream đã commit nhưng caller mất response; hiện chưa có Order client/worker nên chưa có flow thực tế để quan sát. Inventory operation và Payment capture có idempotency implementation riêng, nhưng phải xác nhận đúng contract/key cho từng operation. | AC-04, AC-05 | Integration Test/failure injection: downstream commit rồi drop response; retry cùng command identity/key/payload; kiểm tra số business effects tại downstream bằng DB/API và Order/Saga result. Timeout không được phân loại thành definitive failure. |
| **R3 – Hai worker xử lý cùng Saga step** | Cao: concurrent dispatch có thể gây side effect trùng hoặc ghi kết quả lẫn nhau. | Nếu triển khai nhiều worker, exposure trung bình/cao: `SagaRepository` finder đọc due steps nhưng chưa claim atomically; schema có lease fields. Hiện chưa có Saga worker. Test claim hai worker hiện có là cho Order expiration claim. | AC-04, AC-05 | Integration Test với PostgreSQL cho hai worker tranh cùng Saga step; chỉ owner claim được dispatch; stale owner sau takeover không persist được. Không xem test Order expiry là coverage thay thế. |
| **R4 – Trạng thái hiển thị không phản ánh trạng thái đã persist** | Cao: người dùng có thể nhận trạng thái sai hoặc trạng thái cuối trước xác minh. | Trung bình cho yêu cầu mới: GET hiện đọc Order status đã persist, nhưng worker chưa tồn tại và response không expose Saga-step status. `OrderResponse` của GET chỉ có `orderId`, `status`, `createdAt`, `expireAt`, `correlationId`; không có step status, percentage, current operation hay ETA. Chưa có evidence GET trả dữ liệu sai; hiện trạng là giới hạn khả năng quan sát progress. | AC-02, AC-05 | API Test kiểm tra GET trả Order status hiện persist. Integration Test cần chứng minh worker thực sự cập nhật trạng thái. Nếu yêu cầu progress theo Saga step, test blocked tới khi progress contract và worker được định nghĩa; không thêm field/enum trong strategy. |
| **R5 – Response field mapping bị nhầm** | Trung bình/cao: client có thể theo dõi nhầm Order hoặc hiểu sai trạng thái/thời gian. | Khả năng thấp ở hiện trạng đã kiểm thử một phần; risk tăng khi đổi DTO/mapping. Test hiện có kiểm tra status, correlation và TTL, GET round-trip, nhưng nên có assertion đối chiếu mọi field với dữ liệu persisted để làm mutation guard rõ ràng. | AC-01, AC-02 | API Test tạo rồi GET Order, kiểm tra từng field `orderId`, `status`, `createdAt`, `expireAt`, `correlationId` đối chiếu nguồn persisted/known input. Mutation đổi một field phải làm test fail bằng assertion. |
| **R6 – Tác vụ được nhận nhưng mất sau restart** | Cao: request đã được xác nhận nhưng không được xử lý/khôi phục. | Thấp cho bản ghi Order đã commit vì PostgreSQL persistence; trung bình cho tiếp tục xử lý vì không có worker/recovery loop trong Order Service hiện tại. Chưa có bằng chứng restart workflow. | AC-01, AC-02, AC-05; NFR bền vững | Integration Test restart/recreate application context trong khi giữ DB, sau đó API Test GET theo `orderId` và kiểm tra workflow tiếp tục/không nhân đôi. Nếu test harness không giữ DB xuyên restart hoặc worker chưa tồn tại thì blocked, không giả là đã kiểm chứng. |
| **R7 – Event/Notification phát lại gây consumer effect trùng** | Cao: gửi thông báo/tác dụng downstream lặp; mức nghiệp vụ phụ thuộc consumer. | Chưa đánh giá trực tiếp ở Order runtime: Outbox row và unique `(aggregate_id,event_type)` có; chưa thấy Outbox publisher/Notification consumer. EC-07/08 nêu failure scenario, không phải bằng chứng module đang chạy. | AC-05 | Khi publisher/consumer thuộc scope: Integration Test phát cùng persisted event nhiều lần và xác minh consumer dedup theo event identity/effect; nếu cần kiểm tra toàn luồng qua broker/service thì E2E. Trước đó chỉ test transaction Outbox đã có; consumer test blocked. |

## B. Phân bổ test levels

### 1. Unit Test (UT)

Phù hợp để kiểm tra logic thuần, phân loại input/output và guard cục bộ nhanh, nhưng không chứng minh transaction PostgreSQL, uniqueness cạnh tranh, HTTP mapping thật hay effect ở downstream.

- **Phân loại trạng thái:** chỉ các enum/status thực sự được service xử lý; ví dụ `UNKNOWN` không được diễn giải là `FAILED`. Không tạo enum task mới trong test trước khi contract tồn tại.
- **Quyết định retry:** kiểm tra outcome đã phân loại (success/transient/permanent/unknown) và due eligibility theo policy đã được duyệt. Retry limit/backoff chưa định nghĩa ở Phần 1 nên không viết test số lần/threshold giả định.
- **Idempotency:** canonical payload/hash ổn định cho các thứ tự item tương đương; conflict khi cùng scope/key nhưng payload khác. `OrderServiceTests` đã có coverage hash/canonicalization/conflict logic.
- **Response mapping:** mapping record → `OrderResponse`; hữu ích cho lỗi đổi nhầm constructor argument, nhưng API Test vẫn cần để xác nhận JSON serialization và data source.
- **Điều kiện chuyển trạng thái:** transition guard khi evidence đã xác minh; `OrderStateMachine` đã có unit/integration coverage tùy hành vi.
- **UNKNOWN:** integration test hiện tại xác minh một guard cụ thể: capture `UNKNOWN` chặn claim nhánh hết hạn và Order giữ `PENDING`. Không suy rộng thành coverage cho mọi terminal transition hoặc worker reconciliation. Unit test mới chỉ cần nếu worker có classifier/decision logic mới.

Unit Test kiểm tra method/decision cục bộ bằng dependency thay thế/mock; không kiểm tra HTTP contract thật, commit/rollback database, lock cạnh tranh hay hiệu ứng qua service boundary. Vì vậy UT không thay thế Integration Test hoặc API Test cho R1–R7.

### 2. Integration Test

**Mục tiêu:** kiểm tra nhiều thành phần/module cùng hoạt động, đặc biệt persistence, transaction và concurrency. Integration Test có thể gọi repository/service trực tiếp mà không kiểm tra HTTP endpoint.

- Dùng PostgreSQL/Testcontainers để xác minh Order/Saga/idempotency/attempt/Outbox commit hoặc rollback thực tế.
- Khi worker tồn tại, kiểm tra claim, due step, attempts, lease/ownership, stale update và trạng thái sau retry bằng nhiều transaction/worker.
- Dùng downstream stub có state/effect store để mô phỏng commit rồi mất response; đếm business effect ở phía downstream thay vì chỉ verify mock invocation.
- Kiểm tra recovery sau khi khởi động lại application context/worker trong khi giữ database nếu test harness hỗ trợ. PostgreSQL Testcontainers hiện có nhưng chưa có restart/resume test.
- Integration Test tập trung vào tương tác và persistence; nếu cần xác minh status code/JSON của route, thêm API Test riêng hoặc để cùng test đồng thời kiểm tra hai mục tiêu và ghi rõ cả hai.

### 3. API Test

**Mục tiêu:** xác minh contract HTTP nhìn từ client: route, headers/body, status code, JSON/DTO serialization, field mapping và lỗi. API Test có thể chạy trên ứng dụng thật với database tích hợp; khi đó test cũng có khía cạnh Integration Test, nhưng câu hỏi chính của API Test vẫn là contract của client.

- **POST tiếp nhận:** xác nhận `202`, `OrderResponse` hiện có và các header/request contract.
- **Idempotency HTTP:** replay cùng key/payload, payload conflict và client scope; behavior create hiện tại đã có API tests.
- **GET/query:** kiểm tra 200 cho Order tồn tại, 404 cho ID không tồn tại, 400 cho ID malformed và field được serialize đúng.
- **Response mapping:** kiểm tra từng field JSON với giá trị persisted/known input để một mapping mutation không lọt qua chỉ vì body khác null.
- **Progress API:** chỉ kiểm thử contract thực tế đã được duyệt. API hiện không expose Saga-step status/progress; không viết API test cho field hoặc endpoint chưa tồn tại.
- Một test có thể cùng bao phủ API contract và integration nếu app chạy với PostgreSQL thật; khi đó ghi coverage riêng cho HTTP contract và persistence/transaction thay vì gộp chúng thành một tầng duy nhất.

### 4. End-to-End Test (E2E)

Chọn ít luồng đại diện sau khi có worker và client thật; không chạy mọi tổ hợp partition qua cả service.

1. **Happy path:** POST → nhận `orderId`/status ban đầu → GET trong khi xử lý → GET sau khi workflow đã xác minh hoàn tất.
2. **Lost response/retry:** submit/operation được xử lý nhưng response bị mất → retry đúng logical identity → GET theo cùng Order ID → chỉ một business effect.
3. **Recovery:** Order đã được accept và lưu → service/worker restart → tiến trình tiếp tục hoặc trạng thái chờ được hiển thị chính xác; không tạo Order/step/effect mới.

E2E notification/broker chỉ khi publisher, consumer, event schema và ownership thuộc scope. Hiện chưa có Notification consumer trong Order module nên chưa thể coi đây là E2E chạy được.

## C. Test không cần tạo trong scope hiện tại

| Test không nên tạo/lặp | Lý do và evidence |
|---|---|
| Viết lại toàn bộ test idempotency create Order | Đã có replay normalized payload, conflict payload, client scope, 10 concurrent request và count Order/Saga/step: `order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java:131-205`. Chạy lại làm regression; chỉ bổ sung test cho gap cụ thể. |
| Lặp lại toàn bộ validation ở Unit, API Test và E2E | Integration Test hiện đã kiểm tra thiếu key, payload malformed, quantity, SKU/catalog và không ghi dữ liệu: `OrderIntegrationTests.java:207-256`. Chọn partition representative ở API Test; không cần cùng input ở mọi tầng. |
| Test UI/browser cho progress indicator | `frontend/README.md` ghi frontend là placeholder/chưa có giao diện (README root cũng ghi vậy). Không có UI implementation để test; test contract API trước. |
| Notification provider/consumer E2E ở slice đầu | Order source tree không có publisher/consumer. Có Outbox persistence và requirements về notification; chưa đủ để test tác động consumer thực. Evidence: `OrderStateMachine.java:121-155`, `docs/requirements/04-failure-scenarios.md:11-14,44-51`. |
| Assert progress percentage, ETA hoặc `taskId` | Chưa có field/endpoint/contract. `OrderDtos.OrderResponse` chỉ chứa Order ID, status và timestamps/correlation (`OrderDtos.java:22-28`). |
| Test Saga-step concurrency bằng test Order-expiration hiện tại | `OrderStateMachineIntegrationTests.twoWorkersCannotClaimAndStaleWorkerCannotComplete` tranh `claimExpired(orderId)` (`:291-310`), không claim `saga_steps`. Không lặp test nhầm; cần test worker step mới nếu implementation xuất hiện. |
| Assert mọi tổ hợp status × retry count × error × payload | Tổ hợp dư thừa, retry policy còn chưa định nghĩa; dùng equivalence classes và các failure path có rủi ro cao, bổ sung boundary sau khi policy được duyệt. |
| Performance benchmark trong Phần 2 | Phần này chỉ lập strategy; không chạy load test. Benchmark cần môi trường/workload ổn định và nằm ngoài thao tác hiện tại. |

Không loại bỏ Integration Test cho transaction, persistence hay concurrency chỉ vì có Unit Test tương tự; đó là tầng cần thiết để xác minh các risk này.

## D. Equivalence Partitioning

Chọn một representative cho mỗi lớp có hành vi khác nhau. Giá trị ví dụ dùng data/test hiện có hoặc dạng mô tả, không tạo trạng thái/field mới.

| Input/condition | Equivalence classes hợp lệ | Equivalence classes không hợp lệ/xung đột | Representative test data | Expected behavior | Lý do chọn đại diện |
|---|---|---|---|---|---|
| Idempotency key | Header `Idempotency-Key` hiện diện, printable và không whitespace; key cùng scope/payload đã dùng. | Header thiếu; header rỗng/blank; cùng scope/key nhưng payload chuẩn hóa khác. | Dùng helper `headers("key-replay")`; thiếu key qua helper `headers(null)`; blank `""`; đổi quantity với cùng key. | Hợp lệ tạo/replay cùng Order; thiếu/blank trả 400 theo validate key; payload khác trả 409 `IDEMPOTENCY_KEY_CONFLICT`, không thêm logical Order. | Mỗi lớp đi qua validation, replay hoặc conflict branch khác nhau. Existing tests cover replay/conflict/missing; blank có thể bổ sung nếu chưa assert. |
| Client scope | Cùng `X-Client-Id` và cùng key. | Key trùng ở client scope khác không phải conflict (lớp độc lập); thiếu client header bị HTTP validation/error. | `CLIENT-001` và `CLIENT-002`, dùng cùng `shared-key` như existing test. | Cùng scope replay; scope khác được xem là request riêng theo contract hiện tại; không xung đột chéo. | Scope là thành phần key identity, không thể bỏ khỏi test idempotency. |
| Order/task identifier | `orderId` trả từ POST và tồn tại trong DB. | Identifier đúng format nhưng không tồn tại; malformed identifier. | Order ID vừa tạo; `ORD-missing`; `bad id` đã dùng trong test. | GET existing trả 200 với OrderResponse; unknown id trả 404 `ORDER_NOT_FOUND`; malformed trả 400 `INVALID_IDENTIFIER`. | Tách lookup miss khỏi input validation. Không phát minh task ID. |
| Processing state (Order) | `PENDING` đang chờ; `COMPENSATING`, `COMPENSATION_FAILED`, `EXPIRING` là trạng thái trung gian đã có trong schema; `CONFIRMED`, `FAILED`, `EXPIRED` là terminal Order status đã có. | Giá trị status không được schema cho phép; không tự định nghĩa `QUEUED`, `PROCESSING` hay `COMPLETED` cho Order. | Dùng Order fixture/state setup có trong `OrderStateMachineIntegrationTests`; GET status qua `orderId`. | Query phải phản ánh persisted Order status mới nhất; status terminal không được ghi đè trái guard. Step-level `NOT_STARTED/IN_PROGRESS/SUCCEEDED/FAILED/UNKNOWN` tồn tại trong persistence nhưng không phải field hiện tại của OrderResponse. | Phân biệt Order lifecycle với Saga step; ngăn nhầm status API. |
| Request lần đầu | Request hợp lệ với key chưa dùng. | N/A: không phải lớp lỗi. | Request helper `request(items(item("SKU-001",1)))` với key mới. | `202`, một Order/Saga/RESERVE step, status ban đầu `PENDING`. | Đại diện baseline submit/accept path. |
| Retry cùng request | Cùng client scope, key và canonical payload sau lần đầu; gồm trường hợp client không nhận được response create. | Cùng key nhưng canonical payload đổi (đã phân lớp ở trên). | Gửi hai lần cùng key và normalized item quantity; dùng request thứ hai đổi thứ tự/duplicate items nhưng cùng normalized quantity. | Replay response/order identity gốc; không tạo record logic mới. | Retry request khác với retry downstream operation; cần test riêng. |
| Retry đồng thời | Nhiều request create cùng key/payload; hai worker cùng một Saga step (chỉ khi worker có). | Cùng key/payload không phải input invalid; tranh quyền claim step là race condition. | Existing 10 concurrent POST; đề xuất hai worker cùng đọc một Saga step fixture. | Create hiện có chỉ một Order/Saga/step; future worker chỉ owner claim dispatch. | Dùng concurrency để kiểm tra unique constraint và atomic ownership thật. |
| Downstream outcome | Definitive success; definitive business rejection; transient error có phân loại theo contract. | Timeout/lost response sau commit; `UNKNOWN` nếu operation/API trả hoặc Order state machine nhận trạng thái này. | Inventory `RESERVED`/`REJECTED`; test stub “commit effect then close connection”; state `UNKNOWN` chỉ ở logic Order hiện có. | Success/rejection theo contract; transient theo retry policy được chốt; timeout không đồng nghĩa failure; UNKNOWN không kết thúc step/Order khi chưa xác minh. | Phân biệt response definitive với không có kết quả. Payment/Inventory client của Order chưa có nên downstream cases blocked. |
| Response mapping | Mọi field thực tế trong `OrderResponse`: `orderId`, `status`, `createdAt`, `expireAt`, `correlationId`. | Mutation hoán đổi/ghi nhầm một field so với dữ liệu persisted/request context. | Known `X-Correlation-Id=corr-test`; so timestamps/ID/status với persisted Order row và GET. | API JSON từng field đúng nguồn và GET cùng ID trả cùng current persisted Order. | Assertion theo từng field giúp bắt mutation thay mapping, không chỉ kiểm tra body khác null. |

## E. Test case table

“Đã có” nghĩa là có test tương đương cho contract hiện tại, không nhất thiết cover trọn async extension. Test mới/blocked là kế hoạch, không được viết hoặc chạy ở Phần 2.

| ID | Risk/AC | Test level | Preconditions/Input | Expected result | Priority |
|---|---|---|---|---|---|
| **VT6-TC-01** `[existing, regression]` | AC-01, R1 | API Test + Integration Test | POST hợp lệ, key mới, SKU active. | `202`; `OrderResponse` chứa `orderId`, status ban đầu `PENDING`; một Order/Saga/step. | P0 |
| **VT6-TC-02** `[existing, regression]` | AC-03, R1 | API Test + Integration Test | Hai POST cùng `X-Client-Id`, cùng idempotency key và payload chuẩn hóa. | Response lần hai khớp response/Order ban đầu; count Order/items/Saga/step không tăng. | P0 |
| **VT6-TC-03** `[existing, regression]` | AC-03, R1 (**negative**) | API Test + Integration Test | Request đầu đã dùng key; request kế tiếp cùng scope/key nhưng quantity/payload khác. | HTTP 409, `IDEMPOTENCY_KEY_CONFLICT`; không tạo Order thứ hai. | P0 |
| **VT6-TC-04** `[existing, regression]` | AC-01, R1 (**negative**) | API Test + Integration Test | POST thiếu `Idempotency-Key`. | HTTP 400; không ghi Order/idempotency/Saga. Existing test cover trường hợp header bị thiếu. Header blank không nằm trong test này; nếu cần thì là case bổ sung riêng. | P1 |
| **VT6-TC-05** `[existing, regression]` | AC-02, R4 | API Test + Integration Test | Method `getsExistingOrderAndReportsMissingOrInvalidIdentifiers`: tạo Order rồi GET ID tồn tại; GET ID hợp lệ-form nhưng không tồn tại; GET ID malformed. | Ba nhánh đã được assert riêng trong một test: existing → 200 và body bằng Order đã tạo; missing `ORD-missing` → 404 `ORDER_NOT_FOUND`; malformed `bad%20id` → 400 `INVALID_IDENTIFIER`. GET chỉ khẳng định Order status hiện tại, không khẳng định per-step progress. | P0 |
| **VT6-TC-06** `[existing/strengthen for mutation]` | AC-01, AC-02, R5 | API Test + Integration Test | POST với correlation ID xác định; đọc POST response và GET; so từng field với DB/source. | `orderId`, `status`, `createdAt`, `expireAt`, `correlationId` được map đúng. Nếu mutation đổi mapping một field, assertion field đó fail. Existing coverage một phần; test mapping đối chiếu toàn bộ cần bổ sung/strengthen nếu chưa có. | P0 |
| **VT6-TC-07** `[existing, regression]` | AC-03, R1 | API Test + Integration Test | N request đồng thời cùng client/key/payload. | Chỉ một logical Order, idempotency row, Saga và RESERVE step. | P0 |
| **VT6-TC-08** `[existing, regression]` | AC-05, R4/R7 | Integration Test | Persist Order state transition và query status; rollback Outbox injection. | GET/status và Outbox phản ánh transaction commit; nếu Outbox insert lỗi, terminal update rollback. Không chứng minh publisher/consumer. | P0 |
| **VT6-TC-09** `[new, blocked]` | AC-02, R4 | API Test + Integration Test | Chỉ sau khi progress contract được chốt và async worker tồn tại: POST lấy `orderId`; GET trong lúc worker đã persist một tiến trình/state được contract định nghĩa. | GET hiện chỉ trả `orderId`, `status`, `createdAt`, `expireAt`, `correlationId`; status là Order status, không phải Saga-step status. TC-09 blocked vì hiện không có worker và chưa có contract xác định người dùng cần thấy Order-level hay step-level progress. Không thêm endpoint/field/enum trong test strategy. | P0 |
| **VT6-TC-10** `[new, blocked]` | AC-04, R2 | Integration Test (PostgreSQL + downstream stub) | Downstream commit Reserve/Capture rồi làm mất response; worker retry cùng logical command. | Cùng operation identity/key/payload; downstream effect count bằng một; Order/Saga chỉ ghi kết quả theo xác minh. Blocked vì Order chưa có client/worker và operation scope chưa chốt. | P0 |
| **VT6-TC-11** `[new, blocked]` | AC-04, R3 | Integration Test (PostgreSQL concurrency) | Hai Saga workers tranh cùng due step. | Chỉ một claim owner dispatches; không có SEND attempt/effect từ worker không sở hữu. Existing claim test là cho Order expiry, không cover step. | P0 |
| **VT6-TC-12** `[new, blocked/conditional]` | AC-04, AC-05, R3 | Integration Test (PostgreSQL concurrency) | Worker A claim, lease expires, worker B takes over, A gửi kết quả stale. | Persist của A bị từ chối; B/owner hợp lệ duy nhất cập nhật step. Blocked đến khi Saga-step fencing contract/worker tồn tại. | P0 |
| **VT6-TC-13** `[existing integration regression; async extension new]` | AC-05, R2/R4 | Integration Test; Unit Test bổ sung chỉ nếu có classifier logic thuần mới | Gọi `recordCapture(..., "UNKNOWN", false, ...)`, sau đó gọi `claimExpired(orderId)`. | Test hiện có assert `claimExpired` trả `null` và Order status vẫn `PENDING`. Điều này chứng minh UNKNOWN capture chặn riêng nhánh hết hạn; không chứng minh mọi transition FAILED/terminal đều bị chặn, không kiểm tra worker retry/query/reconciliation, và không kiểm tra status hiển thị qua API. Các coverage đó cần test riêng nếu thuộc contract. | P0 |
| **VT6-TC-14** `[new, conditional on test harness]` | AC-02, AC-05, R6 | Integration Test (restart); API Test (query) | POST được accept/commit; restart/recreate Order app context giữ cùng DB; GET/resume. | Order ID và status đã persist còn đọc được; worker tiếp tục hoặc giữ trạng thái recoverable; không duplicate Order/step/effect. PostgreSQL Testcontainers có, nhưng app/worker restart harness hiện chưa có. | P0 |
| **VT6-TC-15** `[blocked, out of first slice]` | AC-05, R7 | Integration Test (consumer); E2E nếu xác minh toàn luồng | Cùng Outbox event được publisher gửi lại sau mất ACK; consumer chạy contract thật. | Consumer không tạo business effect/notification logic thứ hai cho cùng event identity. Blocked: publisher/consumer/module contract chưa có trong Order source. | P1 |
| **VT6-TC-16** `[existing, regression]` | AC-03/R1, AC-05 | Integration Test (PostgreSQL transaction) | Inject failure giữa Order/Saga/step transaction theo test hiện có. | Rollback không để lại Order/Saga/step/idempotency claim một phần. | P0 |

### Tình trạng coverage test cases

- **Đã có regression tương đương:** VT6-TC-01 đến 05, 07, 08, 13 (guard cơ bản), 16 trong `order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java`, `OrderStateMachineIntegrationTests.java` và `order-service/src/test/java/com/example/orderworkflow/order/service/OrderServiceTests.java`.
- **Cần bổ sung hoặc strengthen:** VT6-TC-06 để assertions đối chiếu rõ từng response field; TC-09 progress qua worker; TC-10 lost downstream response; TC-11/12 claim/fencing Saga step; TC-14 restart/resume nếu harness khả thi.
- **Blocked/ngoài slice:** VT6-TC-15 cho tới khi publisher, consumer, event identity/dedup contract được triển khai/xác nhận. TC-09 đến TC-12 cũng cần worker/operation contract trước khi viết test executable.
- Các phân loại này là đọc/review source/tests, không phải kết quả chạy test ở Phần 2.

## F. Failure-path test: downstream commit rồi response mất

### Kịch bản

1. **Preconditions:** Order/Saga/step đã được persist; một operation downstream cụ thể được chọn; có API client và downstream idempotency/query contract đã xác nhận; test có thể đọc effect counter/record của downstream.
2. Worker gửi operation lần đầu với logical operation identity, idempotency key và payload từ snapshot.
3. Downstream commit business effect (ví dụ tạo reservation hoặc capture) thành công.
4. Failure injection đóng/reset kết nối sau commit nhưng trước khi Order Service nhận response. Phía Order chỉ quan sát timeout/communication failure; không giả định downstream rollback.
5. Worker giữ state chưa xác định hoặc query/reconcile theo contract; sau khi retry được cho phép, nó gửi lại cùng operation identity/key/payload.
6. Downstream replay kết quả gốc hoặc trả trạng thái truy vấn; Order Service persist một kết quả đã xác minh. Không tạo command/Saga step mới cho cùng logical operation.

### Expected result và evidence quan sát

- Số record/effect nghiệp vụ tại downstream là **một**; cùng request không tạo reservation/capture thứ hai.
- `idempotency_key`, payload hash và payload của Saga step không đổi giữa lần gửi; không có step mới cho cùng operation.
- Attempt log thể hiện lần gửi đầu và retry/lookup, nhưng state chỉ chuyển theo definitive outcome/query result.
- GET Order trả trạng thái đã persist; không báo thành công/thất bại trong khi outcome còn UNKNOWN.
- Nếu có event liên quan, chỉ kiểm tra Outbox persistence/transaction trong slice này; consumer duplicate effect chỉ kiểm tra khi publisher/consumer thuộc contract và có test environment.

### Test level và giới hạn hiện tại

Integration Test với PostgreSQL thật và downstream HTTP stub có effect store là phù hợp nhất cho lost-response path: nó bao phủ DB transaction, retry boundary và số tác động thực tế. API Test bổ sung nếu cần xác minh public HTTP contract của Order. Unit Test chỉ xác minh classifier/key selection, không thể chứng minh downstream commit-once. E2E có thể bổ sung sau khi client/worker thật được triển khai.

Ở baseline hiện tại, Order Service không có client/worker thực thi operation. Do đó failure injection này là **test mới blocked**, không phải test có thể viết/run thành executable test Phần 2. Không đánh đồng `OrderServiceTests` mock idempotency hoặc Inventory/Payment service tests độc lập với test này.

## G. Performance test strategy

Không chạy benchmark trong Phần 2 và chưa có số liệu để tuyên bố đạt.

### Operation và thời điểm đo

- **Operation chính:** submit Order async. Bắt đầu khi API nhận request; kết thúc khi response `202`/`OrderResponse` xác nhận Order và idempotency result đã commit. NFR p95 ≤ 2.0 giây áp dụng cho operation này.
- **Operation phụ:** query `GET /api/v1/orders/{orderId}`. Đo riêng từ lúc gửi GET tới nhận response; assignment chưa chỉ định ngưỡng riêng cho query nên báo số liệu, không gán thêm pass/fail threshold.
- **End-to-end completion latency:** submit accepted tới terminal Order status; đo riêng để thấy thời gian xử lý nền, không nhầm với p95 acceptance API.

### Workload và cách tái lập

- Tải mục tiêu: **60 người dùng/tác vụ đồng thời**. Trước khi chạy phải cố định nghĩa con số là 60 active clients/tasks với request outstanding; ghi rõ nếu test tool mô hình hóa thành concurrent HTTP requests.
- Cố định và ghi lại tỉ lệ submit/poll, think/poll interval, số tác vụ mỗi client, request size/catalog data, warm-up, thời lượng đo, số lượt chạy và tài nguyên app/DB/downstream.
- Dùng cùng payload/profile và môi trường giữa các lần chạy; chỉ tính latency trong measurement window sau warm-up. Lưu tool/config/version và raw result để lặp lại.
- Không tự chọn workload split hay duration làm yêu cầu; nhóm phải chốt trước benchmark và ghi vào evidence.

### Metrics và tiêu chí báo cáo

- p50, p95, p99 latency cho submit và query riêng biệt; end-to-end completion latency phân phối riêng.
- Throughput (submit/sec, query/sec, completed tasks/sec) và error/timeout rate.
- Duplicate logical Orders/Sagas/steps, duplicate downstream business effects, duplicate event/consumer effects (nếu consumer nằm trong test), theo cùng workload window.
- NFR pass chỉ khi p95 submit ≤2.0s trên workload 60 đã chốt, cùng error/duplicate correctness checks. Không tuyên bố pass nếu môi trường không cố định hoặc chưa có kết quả thực nghiệm.

## H. Traceability sơ bộ

| AC/Risk | Test case IDs | Test level | Test file dự kiến | Expected evidence |
|---|---|---|---|---|
| AC-01, R1 – accept và không nhân đôi Order/Saga/step | VT6-TC-01, 02, 03, 04, 07, 16 | API Test + Integration Test | `order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java` (existing); mở rộng chỉ nếu có gap | HTTP response, cùng `orderId`, DB counts cho orders/idempotency_records/sagas/saga_steps, conflict code, transaction rollback. |
| AC-02, R4 – query status hiện persist | VT6-TC-05, 09 | API Test; Integration Test cho progress worker | Existing GET test trong `OrderIntegrationTests.java`; `SagaWorkerIntegrationTests.java` (new, blocked) | TC-05 có ba nhánh GET riêng; TC-09 cần progress contract/worker và chỉ có thể kiểm tra Order status hiện tại nếu contract xác nhận như vậy. |
| AC-01/02, R5 – mapping response fields | VT6-TC-06 | API Test + Integration Test | `OrderIntegrationTests.java` (existing file; cần strengthen) | Assertion từng field response với input/database và GET; mutation một mapping làm assertion fail. |
| AC-03, R1 – idempotent replay/canonical payload | VT6-TC-02, 03, 07 | API Test + Integration Test | `OrderIntegrationTests.java` (existing regression) | Response replay, conflict, count records và logical ID. |
| AC-04, R2 – lost response không tạo downstream effect trùng | VT6-TC-10 | Integration Test (PostgreSQL + downstream stub) | `SagaWorkerIntegrationTests.java` (new; blocked tới worker/client contract) | HTTP call log + downstream effect DB/count, identical key/payload, persisted verified step/Order state. |
| AC-04, R3 – concurrent/stale worker | VT6-TC-11, 12 | Integration Test (PostgreSQL concurrency) | `SagaWorkerIntegrationTests.java` (new; blocked tới Saga-step claim implementation) | Chỉ owner dispatch; stale token update bị từ chối; không trùng attempt/effect. Existing expiry test ở `OrderStateMachineIntegrationTests.java` không thay thế. |
| AC-05, R4 – UNKNOWN chặn nhánh hết hạn | VT6-TC-13 | Integration Test; Unit Test nếu xuất hiện classifier thuần mới | `order-service/src/test/java/com/example/orderworkflow/order/OrderStateMachineIntegrationTests.java:161-167` (existing); worker/API tests new nếu được yêu cầu | `unknownIsNotFailureAndBlocksExpiration` chứng minh `claimExpired` bị chặn và Order giữ `PENDING`. Không dùng test này làm bằng chứng cho mọi final transition, reconciliation worker hoặc API-visible progress. |
| AC-02/05, R6 – restart recovery | VT6-TC-14 | Integration Test (restart); API Test (query) | `SagaWorkerIntegrationTests.java` (new) hoặc `OrderRecoveryIntegrationTests.java` (TBD) | Order/status vẫn đọc được sau restart và worker resume không duplicate; tên test file cần theo test harness thực tế. |
| AC-05, R7 – publisher/consumer dedup | VT6-TC-08, 15 | Integration Test; E2E nếu cần xác minh broker xuyên service | `OrderStateMachineIntegrationTests.java` (existing Outbox transaction); consumer test path `TBD` vì module/contract chưa tồn tại | Outbox event atomic với Order; nếu consumer test được duyệt thì cùng event replay không tạo effect thứ hai. |
| NFR 60 concurrent / p95 ≤2.0s | Benchmark plan, chưa phải TC executable Phần 2 | Performance/load test | `TBD` — repository chưa có performance harness/tool config được xác minh | Raw latency distributions, throughput/error rate, duplicate counters, environment/workload manifest. |

## Evidence repository đã đối chiếu

- API/DTO: `order-service/src/main/java/com/example/orderworkflow/order/api/OrderController.java:19-41`; `OrderDtos.java:22-33`.
- Create/replay transaction: `order-service/src/main/java/com/example/orderworkflow/order/service/OrderService.java:49-105`; `OrderRepository.java:26-40,75-91`.
- Saga finder/attempt/update: `order-service/src/main/java/com/example/orderworkflow/order/repository/SagaRepository.java:65-127`.
- Step status/lease schema: `order-service/src/main/resources/db/migration/V002__add_order_saga_state.sql:69-98`.
- Current Order status query source: `order-service/src/main/java/com/example/orderworkflow/order/repository/OrderRepository.java:88-91`.
- Existing create/idempotency/concurrency tests: `order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java:77-94,113-205,207-256,257-325`.
- Existing unknown/state/expiry-worker/Outbox tests: `order-service/src/test/java/com/example/orderworkflow/order/OrderStateMachineIntegrationTests.java:131-195,291-325`.
- Order expiry claim implementation and Saga worker distinction: `order-service/src/main/java/com/example/orderworkflow/order/repository/OrderStateRepository.java:106-142`; `SagaRepository.java:65-104`.
- Requirements: `docs/requirements/02-functional-requirements.md:5-19`; `03-business-rules.md:7-16,82-`; `04-failure-scenarios.md:8-14,38-51`; `05-use-cases.md:3-22,59-67`.
- Test strategy (local untracked file): `docs/test_strategy.md:14-29,58-77,156-190`.
