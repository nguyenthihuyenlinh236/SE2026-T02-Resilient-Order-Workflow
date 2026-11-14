# PHẦN 1. SPECIFICATION VÀ AI-ASSISTED PLAN (15 ĐIỂM)

> **Dự án:** APP-08 – Resilient Order Workflow  
> **Change request đang phân tích:** VT-006  
> **Repository/nhánh dùng để review:** `nguyenthihuyenlinh236/SE2026-T02-Resilient-Order-Workflow`, nhánh `dev`  
> **Commit được kiểm tra:** `a513241d0e71e2c0eb7adaa32198e0b3902cf766`  
> **Trạng thái:** Đã lập specification đề xuất, dùng coding agent tạo implementation plan và review plan bằng repository thật. Chưa triển khai code.

## 1.1. Bối cảnh và phạm vi change request

Trong quy trình xử lý đơn hàng theo Saga, một operation như giữ hàng hoặc capture thanh toán có thể đã được service đích thực hiện, nhưng Order Service không nhận được phản hồi do timeout hoặc lỗi mạng. Nếu hệ thống gửi lại operation bằng một idempotency key mới, hoặc không kiểm tra kết quả của lần gửi trước, tác động nghiệp vụ có thể bị lặp.

Repository tại nhánh `dev` đã có nền tảng persistence cho Order idempotency, Saga step, attempt, lease, state transition và Outbox. Tuy nhiên, việc có schema và repository method không đồng nghĩa các worker điều phối retry hoặc Outbox Publisher đã được triển khai.

**Giới hạn quan trọng:** Trong các file được theo dõi của repository tại commit được kiểm tra, chưa tìm thấy đặc tả hoặc issue chính thức mang mã `VT-006`. Vì vậy, phần dưới là specification đề xuất theo hướng retry an toàn, idempotent cho Saga và xử lý Outbox; không được xem là yêu cầu VT-006 đã được stakeholder phê duyệt. Cần lấy nguyên văn change request để xác nhận phạm vi trước khi code.

## 1.2. Problem statement

Khi một bước nghiệp vụ trong Saga gặp timeout hoặc lỗi tạm thời, Order Service cần có khả năng phục hồi và thử lại mà không tạo thêm tác động nghiệp vụ đã được service đích thực hiện thành công. Đặc biệt, khi request đã tới service đích nhưng response bị mất, hệ thống không thể suy luận rằng operation thất bại chỉ từ timeout.

Hiện repository đã lưu idempotency key, payload hash, trạng thái Saga step, attempt và Outbox; đồng thời đã có một số xử lý idempotency và cập nhật trạng thái có điều kiện. Tuy nhiên, source tree của Order Service chưa có worker/dispatcher thực thi retry Saga hoặc Outbox Publisher. Do đó, khoảng trống cần được xác minh và xử lý (nếu thuộc VT-006) là cơ chế claim/dispatch/reconciliation an toàn, bảo đảm retry dùng lại định danh và payload ban đầu, chỉ ghi nhận kết quả khi đã xác minh, và không làm sai lệch transaction giữa trạng thái Order với Outbox.

**Mục tiêu đề xuất:** hoàn thiện một lát cắt retry an toàn cho operation được VT-006 xác nhận, tận dụng persistence hiện có và không mở rộng sang publisher, consumer hoặc notification nếu change request không yêu cầu.

## 1.3. Acceptance criteria

Các tiêu chí dưới đây là tiêu chí nghiệm thu đề xuất để cụ thể hóa bài toán. Chúng cần được đối chiếu với VT-006 chính thức trước khi coi là baseline đã duyệt.

### AC-01 — Retry cùng Saga step phải giữ nguyên định danh và payload

- **Given:** Một Saga step đã được lưu bền vững với `operation`, `idempotency_key` và `request_payload`.
- **When:** Hệ thống gửi lại cùng step sau timeout hoặc lỗi tạm thời.
- **Then:** Mọi lần gửi lại phải sử dụng đúng `idempotency_key` và payload ban đầu; không tạo step mới cho cùng cặp `(saga_id, operation)`. Service đích phải xử lý lặp theo contract idempotency của operation để không tạo thêm tác động nghiệp vụ có hiệu lực.

### AC-02 — Tái sử dụng Order idempotency key với payload khác phải bị từ chối

- **Given:** Một yêu cầu tạo Order đã được ghi nhận với `Idempotency-Key` và payload chuẩn hóa.
- **When:** Client gửi lại cùng key nhưng payload chuẩn hóa khác.
- **Then:** API trả HTTP `409` với mã `IDEMPOTENCY_KEY_CONFLICT`; không tạo thêm Order, Order Item hoặc Saga cho yêu cầu xung đột.

### AC-03 — Không kết thúc Saga step khi kết quả chưa được xác minh

- **Given:** Một Saga step đang ở trạng thái `UNKNOWN` hoặc `IN_PROGRESS`.
- **When:** Worker xử lý retry/đối soát nhưng chưa nhận được kết quả xác minh từ service đích.
- **Then:** Step không được chuyển sang `SUCCEEDED` hoặc `FAILED`; Order không được chuyển sang trạng thái cuối chỉ dựa trên timeout. Step phải tiếp tục ở trạng thái chưa xác định/đang xử lý hoặc đi qua cơ chế query/reconciliation đã được xác nhận.

### AC-04 — Cập nhật trạng thái cuối và ghi Outbox phải cùng transaction

- **Given:** Order đủ điều kiện chuyển sang trạng thái cuối và cần tạo domain event tương ứng.
- **When:** Hệ thống cập nhật trạng thái Order/Saga và ghi Outbox.
- **Then:** Hai thao tác phải cùng transaction DB: nếu insert Outbox thất bại thì cập nhật trạng thái trong transaction đó rollback; nếu commit thành công thì không tạo event domain trùng cho cùng Order và event type.

### AC-05 — Retry publish không tạo domain event mới

- **Given:** Domain event đã được ghi vào Outbox với event identity và payload xác định.
- **When:** Outbox Publisher phải gửi lại do chưa xác nhận được lần publish trước.
- **Then:** Publisher gửi lại cùng event identity/payload đã lưu, không insert domain event mới chỉ vì retry kỹ thuật. Việc bảo đảm consumer/Notification Service không xử lý trùng chỉ được nghiệm thu khi consumer và contract tương ứng tồn tại.

**Phân biệt trạng thái triển khai:** AC-02 và AC-04 đã có implementation/test tương ứng trong repository và cần được giữ như regression criteria. AC-01 chưa được chứng minh end-to-end vì chưa thấy dispatcher gọi service đích. AC-05 chưa thể nghiệm thu ở tầng publish/consumer vì chưa thấy Outbox Publisher hoặc Notification consumer trong source tree Order Service.

## 1.4. Non-functional constraints

1. **Idempotency bền vững:** Với cùng một hành động nghiệp vụ, mọi lần retry phải giữ nguyên idempotency key và payload đã lưu. Không chỉ dựa vào kiểm tra trong Java; cần dùng persistence/unique constraint phù hợp và contract idempotency của service đích.

2. **Không suy diễn timeout thành thất bại:** Timeout hoặc mất response không đủ chứng minh operation chưa được thực hiện. Hệ thống phải giữ trạng thái chưa xác định và dùng query/reconciliation hoặc cơ chế xác minh theo contract trước khi kết thúc step.

3. **Atomicity của Order và Outbox:** Cập nhật trạng thái Order/Saga và ghi domain event tương ứng phải commit hoặc rollback trong cùng transaction DB khi hai thao tác thuộc cùng một chuyển trạng thái nghiệp vụ.

4. **Không tự ý thay đổi contract đồng bộ:** `POST /api/v1/orders` hiện xử lý tạo Order trong luồng request. Không tự ý chuyển API sang asynchronous, thêm `@Async`, scheduler hoặc queue consumer nếu change request và thiết kế vận hành chưa xác nhận.

Không đưa ngưỡng hiệu năng như `p95 ≤ 2 giây với 60 request đồng thời` vào constraint bắt buộc vì repository/tài liệu hiện có không cung cấp căn cứ phê duyệt cho đúng ngưỡng đó. Nếu cần NFR hiệu năng, phải thống nhất workload, môi trường đo và ngưỡng với stakeholder trước.

## 1.5. Invariant / contract quan trọng nhất

> **Với cùng một hành động nghiệp vụ đã được định danh ổn định, mọi lần retry phải sử dụng cùng idempotency key và cùng payload; hệ thống không được tạo thêm tác động nghiệp vụ có hiệu lực chỉ vì retry. Chỉ kết quả đã xác minh mới được phép cập nhật trạng thái Saga/Order tương ứng.**

Invariant này được cụ thể hóa theo từng loại định danh:

- **Order request:** một cặp `(scope, idempotency_key)` đại diện cho một request/payload chuẩn hóa. Dùng lại key với payload khác phải trả conflict.
- **Saga step:** một cặp `(saga_id, operation)` chỉ có một step; key và payload của step phải được giữ ổn định qua retry.
- **Outbox event:** cùng `(aggregate_id, event_type)` không được tạo nhiều domain event trùng nhau cho cùng chuyển trạng thái.
- **Reservation/Capture/Release/Refund:** mỗi operation phải được bảo vệ bằng idempotency contract riêng của service thực hiện operation.

Không diễn giải invariant thành “một Order chỉ có một giao dịch” hoặc “hệ thống gửi đúng một thông báo”. Một Order có thể có nhiều Saga step hợp lệ; refund là operation riêng. Outbox Publisher có thể gửi lại một event theo semantics at-least-once. Điều cần bảo vệ là không tạo event nghiệp vụ mới cho mỗi lần retry và consumer, nếu có, phải deduplicate theo event identity. Repository hiện chưa có Notification consumer để cam kết exactly-once ở đầu cuối.

## 1.6. AI-assisted implementation plan

### 1.6.1. Prompt dùng/đề xuất để yêu cầu coding agent lập plan

> **Ghi chú về tính trung thực của evidence:** Hai file đầu vào cung cấp nội dung plan và kết quả review, nhưng không lưu nguyên văn prompt lịch sử đã gửi cho coding agent. Vì vậy, prompt dưới đây là bản prompt được chuẩn hóa để ghi vào báo cáo, không khẳng định là bản sao nguyên văn của một cuộc hội thoại Codex trước đó.

```text
Đóng vai Senior Software Engineer. Hãy khảo sát repository thực tế tại nhánh dev
và tạo implementation plan cho change request VT-006 trước khi viết code.

Trước tiên xác minh branch, commit và working tree. Đọc tài liệu yêu cầu,
kiến trúc, source code, migration và test liên quan.

Không suy đoán nội dung VT-006 nếu không tìm thấy issue/đặc tả chính thức.
Nếu thiếu yêu cầu, ghi rõ blocker và phân biệt nội dung đề xuất với yêu cầu
đã được phê duyệt.

Plan phải có:
1. Problem statement.
2. 3–5 acceptance criteria kiểm thử được.
3. Non-functional constraints.
4. Invariant/contract quan trọng nhất.
5. Danh sách file hiện có dự định sửa và file mới dự định thêm,
   kèm lý do và điều kiện.
6. Thứ tự triển khai và vertical slice nhỏ nhất chạy được.
7. Các assumptions đánh số A1, A2..., mỗi assumption nêu cách kiểm chứng.
8. Rủi ro và điều kiện dừng.

Không viết code trong bước lập plan. Không sửa source code, test,
migration hoặc tài liệu khác nếu chưa được cho phép.
```

### 1.6.2. Assumptions trong plan ban đầu và cách kiểm chứng

| ID | Assumption của plan AI | Cách kiểm chứng được yêu cầu |
|---|---|---|
| A1 | VT-006 yêu cầu hoàn thiện retry an toàn cho Saga. | Lấy nguyên văn issue/change request VT-006 và đối chiếu từng AC. |
| A2 | Operation đầu tiên của vertical slice là RESERVE. | Kiểm tra operation được nêu trong VT-006; đối chiếu `OrderService.java`, Inventory API contract và test. |
| A3 | Các finder trong `SagaRepository` đủ để dispatcher claim step an toàn. | Đọc SQL/transaction của repository; kiểm tra claim ownership và chạy test hai worker cạnh tranh, lease hết hạn, stale worker. |
| A4 | Service đích hỗ trợ idempotency bền vững. | Đọc API contract và implementation đúng operation; thử gửi lặp cùng key/payload trong integration test. |
| A5 | Key và payload trong Saga step bất biến. | Đọc schema, repository update methods, payload hash validation và migration; kiểm tra có cơ chế ngăn thay đổi hay không. |
| A6 | Outbox publish theo at-least-once và consumer deduplicate. | Kiểm tra publisher/consumer và event contract thực tế; không suy ra từ schema Outbox. |
| A7 | Notification Service nằm ngoài vertical slice đầu tiên. | Kiểm tra Maven modules/source tree và xác nhận ownership với nhóm phụ trách. |
| A8 | `p95 ≤ 2.0s` ở 60 request đồng thời là mục tiêu chấp nhận được. | Xác nhận stakeholder; định nghĩa workload, môi trường và benchmark tái lập. |
| A9 | PostgreSQL/Testcontainers là môi trường integration test. | Kiểm tra POM, test annotations và chạy test trên môi trường có Docker/CI. |
| A10 | Không cần migration mới nếu constraint hiện tại đủ. | Đọc V001–V005, kiểm tra schema DB test và đối chiếu gap của thiết kế đã chốt. |

### 1.6.3. Các file coding agent dự định thay đổi/thêm mới

Danh sách dưới đây là kế hoạch có điều kiện, không có nghĩa các file đã được sửa.

**File hiện có dự kiến xem xét thay đổi:**

| File | Dự kiến | Điều kiện/lý do |
|---|---|---|
| `order-service/src/main/java/com/example/orderworkflow/order/service/OrderStateMachine.java` | Chỉ sửa nếu cần bổ sung transition hoặc tích hợp với dispatcher. | Giữ nguyên guard và transaction boundary đang có. |
| `order-service/src/main/java/com/example/orderworkflow/order/repository/SagaRepository.java` | Bổ sung atomic claim/fencing hoặc persistence còn thiếu nếu VT-006 yêu cầu. | Finder hiện có không tự chứng minh quyền sở hữu step. |
| `order-service/src/main/java/com/example/orderworkflow/order/repository/OrderStateRepository.java` | Chỉ sửa nếu workflow đã chốt cần thao tác persistence mới. | Không viết lại các thao tác state update/Outbox đã có. |
| `order-service/src/test/java/com/example/orderworkflow/order/OrderStateMachineIntegrationTests.java` | Bổ sung regression/integration tests cho hành vi mới chưa được cover. | Tận dụng test hiện có, tránh tạo test trùng. |
| `order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java` | Chỉ sửa nếu VT-006 thay đổi contract tạo Order/idempotency. | Replay, payload conflict, concurrency và rollback đã có test. |

**File mới dự kiến thêm nếu VT-006 xác nhận cần worker/publisher:**

| File mới | Trách nhiệm dự kiến |
|---|---|
| `order-service/src/main/java/com/example/orderworkflow/order/service/SagaRetryDispatcher.java` | Lấy step đến hạn, claim bằng cơ chế ownership an toàn, ghi attempt, dispatch operation và lưu kết quả. |
| `order-service/src/main/java/com/example/orderworkflow/order/service/OutboxPublisher.java` | Publish event đã persist, giữ nguyên event identity/payload và cập nhật trạng thái publish theo contract. |
| `order-service/src/test/java/com/example/orderworkflow/order/service/SagaRetryDispatcherTests.java` | Unit test retry, phân loại lỗi, key/payload ổn định và không retry step terminal. |
| `order-service/src/test/java/com/example/orderworkflow/order/OutboxPublisherIntegrationTests.java` | Integration test publish retry, phục hồi sau lỗi và không tạo domain event mới. |

Không đưa Notification Service, consumer inbox/dedup table, RabbitMQ listener hoặc client adapter mới vào slice đầu tiên nếu VT-006 chưa xác nhận rõ operation, API/event contract và module ownership. Không đề xuất sửa migration cũ; chỉ tạo migration mới nếu thiết kế đã duyệt chứng minh cần thay đổi schema.

### 1.6.4. Thứ tự triển khai đề xuất

1. Lấy nguyên văn VT-006; xác nhận operation, AC, service owner, API/event contract và phạm vi worker.
2. Map từng AC đã duyệt tới source, migration và test hiện có; chạy baseline trước khi sửa.
3. Nếu cần worker Saga, thiết kế atomic claim, lease, ownership token/fencing và quy tắc attempt trước khi dispatch.
4. Chọn đúng một operation theo VT-006; xác minh idempotency/query contract của service đích.
5. Làm vertical slice: claim step → gửi bằng key/payload đã lưu → xác minh hoặc query kết quả → conditional persist.
6. Bổ sung test cho worker cạnh tranh, lease hết hạn/stale worker, timeout sau khi service đích xử lý, replay cùng key/payload và trạng thái UNKNOWN.
7. Chỉ triển khai Outbox Publisher/consumer nếu VT-006 yêu cầu rõ; giữ nguyên transaction Order + Outbox.
8. Chạy regression tests và full build; chỉ đánh giá NFR hiệu năng khi có workload và ngưỡng được duyệt.

**Vertical slice nhỏ nhất được đề xuất:** một dispatcher xử lý một operation được xác nhận trong VT-006, với claim an toàn, attempt bền vững, key/payload ổn định, xử lý UNKNOWN và test hai worker cạnh tranh. RESERVE chỉ là ví dụ có thể xem xét, không phải lựa chọn đã được xác nhận.

## 1.7. Review implementation plan bằng repository thật

Plan ban đầu được đối chiếu với repository `dev` tại commit `a513241d0e71e2c0eb7adaa32198e0b3902cf766`. Review chỉ ra các assumption và quyết định cần sửa/bác bỏ sau đây.

| Plan gốc của AI | Vấn đề phát hiện | Evidence trong repository | Quyết định |
|---|---|---|---|
| A1: “VT-006 yêu cầu hoàn thiện retry an toàn cho Saga”. | Không tìm thấy issue/đặc tả VT-006 trong các file được Git theo dõi. Đây là suy diễn phạm vi, không phải requirement đã xác nhận. | Inventory tracked files và kết quả khảo sát tài liệu; `docs/spec_and_plan.md` bản review, mục trạng thái/phạm vi. | **Bác bỏ** như requirement chính thức; giữ VT-006 là blocker trước khi code. |
| A2: “Operation đầu tiên là RESERVE”. | `OrderService` có tạo RESERVE step, nhưng không có evidence cho thấy VT-006 yêu cầu xử lý RESERVE trước. | `order-service/src/main/java/com/example/orderworkflow/order/service/OrderService.java:96-105`. | **Bác bỏ** lựa chọn mặc định; chỉ chọn operation sau khi đọc change request. |
| A3: `findRetryable`, `findDueQueries`, `findExpiredClaims` đủ để dispatcher claim an toàn. | Hai tên method không khớp code thực tế: repository có `findReconciliationDue()` và `findExpiredLeases()`. Các finder chỉ đọc danh sách; chưa có atomic Saga-step claim theo token/fencing. | `order-service/src/main/java/com/example/orderworkflow/order/repository/SagaRepository.java:65-104,120-126`; schema `V002__add_order_saga_state.sql:46-49`. | **Sửa**: không mô tả claim step là chức năng đã có; nếu cần worker cạnh tranh phải thiết kế atomic claim và fencing. |
| A4: service đích hỗ trợ idempotency bền vững cho mọi operation. | Có evidence cho một số operation, không thể khái quát cho toàn bộ Reserve/Release/Capture/Refund. Cũng chưa thấy Order Service client/orchestrator gọi các service đích. | `inventory-service/src/main/java/com/example/orderworkflow/inventory/repository/InventoryOperations.java:20-31`; `payment-service/src/main/java/com/example/orderworkflow/payment/service/PaymentCaptureService.java`; `OrderService.java:49-105`. | **Sửa** thành kiểm chứng theo từng API/operation; không bật blind retry khi chưa có contract/query phù hợp. |
| A5: payload và key trong `saga_steps` là snapshot bất biến. | Schema có key/payload/hash và unique constraint, nhưng chưa có bằng chứng DB ngăn mọi cập nhật payload/hash. Việc repository hiện không ghi đè không đồng nghĩa DB bảo đảm bất biến tuyệt đối. | `order-service/src/main/resources/db/migration/V002__add_order_saga_state.sql:33-39`; `SagaRepository.java:30-37,120-126`. | **Sửa**: ghi rõ bất biến theo code hiện tại nhưng DB enforcement chưa được chứng minh; xác minh nếu yêu cầu cần bảo đảm mạnh hơn. |
| A6: Outbox at-least-once và consumer deduplicate đang hoạt động. | Order Service đã có Outbox persistence và transaction write, nhưng không thấy Publisher hoặc Notification consumer trong source tree. At-least-once là semantics cần triển khai/kiểm chứng, không phải bằng chứng publisher đang chạy. | `V002__add_order_saga_state.sql:77-97`; `OrderStateMachine.java:121-155`; source tree `order-service/src/main/java`. | **Sửa**: phân biệt Outbox write đã có với publish/consume chưa có; chỉ bổ sung nếu thuộc VT-006. |
| A8: p95 ≤ 2.0 giây ở 60 request là mục tiêu. | Không có căn cứ phê duyệt cho ngưỡng và workload cụ thể này. | `docs/requirements/07-non-functional-requirements.md:19-29,347-357`. | **Bác bỏ** con số khỏi NFR bắt buộc; chỉ đưa vào sau khi stakeholder duyệt và có benchmark. |
| A9: PostgreSQL/Testcontainers dùng cho integration test Order. | Có dependency và test integration, nhưng điều đó không chứng minh Docker/CI runtime luôn sẵn sàng hoặc baseline đã chạy. | `order-service/pom.xml:34-60`; `order-service/src/test/java/com/example/orderworkflow/order/OrderStateMachineIntegrationTests.java`. | **Giữ có điều kiện**; xác minh môi trường thực thi trước khi dựa vào kết quả test. |
| A10: chưa cần migration nếu constraint hiện tại đủ. | Unique constraint không thay thế atomic claim/fencing. Chưa thể quyết định có migration hay không khi chưa chốt scope và thiết kế. | `V002__add_order_saga_state.sql:33-76`; `SagaRepository.java:65-126`. | **Sửa**: quyết định migration sau khi xác định gap cụ thể; không khẳng định trước là không cần. |

### 1.7.1. Những chức năng đã có và khoảng trống còn lại

| Thành phần | Hiện trạng có thể xác minh | Ý nghĩa đối với plan |
|---|---|---|
| Tạo Order/idempotency request | Đã triển khai; POST nhận `Idempotency-Key`, tạo Order/items/Saga/RESERVE intent; có replay và payload conflict. | Không viết lại; giữ AC-02 làm regression criterion. |
| Saga persistence | Đã triển khai một phần: schema Saga/step/attempt, payload/hash, status, due query và lease fields. | Không đồng nhất persistence với worker/orchestrator. |
| Saga step claim/retry/fencing | Chưa chứng minh đã triển khai; có finder và lease fields nhưng không có atomic claim/update theo token cho Saga step. | Đây là khoảng trống kỹ thuật tiềm năng nếu VT-006 yêu cầu worker. |
| Order state machine/Order expiry claim | Đã triển khai một phần, gồm guard chuyển trạng thái và claim/lease ở nhánh Order hết hạn. | Không suy rộng Order claim thành Saga-step claim. |
| Outbox write | Đã triển khai; terminal transition và insert Outbox cùng transaction. | Giữ nguyên transaction boundary; bổ sung regression test nếu cần. |
| Outbox Publisher/broker dispatch | Chưa thấy publisher/listener trong source Order Service. | Không tuyên bố publish đã hoạt động; chỉ thêm nếu change request yêu cầu. |
| Inventory Reserve/Release | Inventory API/service/persistence idempotency đã có. | Phải dùng đúng API contract cho operation được chọn. |
| Payment Capture/Refund | Capture API/service/idempotency đã có; không thấy Refund endpoint/service trong Payment source tree được theo dõi. | Không giả định Refund đã được triển khai. |
| API clients ở Order | Chưa thấy HTTP client/adapter trong Order Service gọi Inventory/Payment. | Nếu cần dispatch phải xác định client/contract và ownership. |
| Tests | Có test idempotency create, state transition, UNKNOWN, concurrency/lease của Order và rollback Outbox. | Không coi đây là test cho worker chưa tồn tại. |

## 1.8. Kết luận phần 1

Phần specification đã chuyển bài toán retry an toàn thành một problem statement, 5 acceptance criteria kiểm thử được, các non-functional constraints và invariant tập trung vào idempotency, xác minh kết quả và atomicity của Order/Outbox. Coding agent đã được yêu cầu tạo implementation plan trước khi code; plan nêu assumptions, cách kiểm chứng, file hiện có dự định sửa, file mới dự kiến thêm và thứ tự triển khai.

Review bằng repository thật đã phát hiện và sửa/bác bỏ nhiều điểm có ảnh hưởng trực tiếp tới tính đúng đắn: VT-006 chưa có đặc tả chính thức; RESERVE chưa được xác nhận là operation mục tiêu; tên finder trong plan sai; finder/lease fields không chứng minh Saga step claim an toàn; Outbox write không đồng nghĩa Publisher/consumer đã tồn tại; và ngưỡng p95 2 giây/60 request không có căn cứ. Vì vậy, kế hoạch cuối cùng giữ trạng thái **conditional plan** và yêu cầu xác nhận VT-006 trước khi code.

**Kết quả theo rubric:**
- Problem statement: có, mô tả rõ vấn đề timeout/retry và tác động nghiệp vụ trùng.
- Acceptance criteria: 5 tiêu chí Given/When/Then có thể kiểm thử.
- Non-functional constraints: có các ràng buộc idempotency, UNKNOWN/reconciliation, atomicity và giữ contract đồng bộ.
- Invariant/contract: xác định rõ key + payload ổn định và chỉ kết thúc khi có kết quả xác minh.
- AI-assisted plan: có prompt, assumptions/cách kiểm chứng, danh sách file và thứ tự triển khai.
- Repository-based review: có bảng đối chiếu và evidence cụ thể; nhiều hơn 2 điểm đã được sửa hoặc bác bỏ.
