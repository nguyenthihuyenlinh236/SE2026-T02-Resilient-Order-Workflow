# BT3 – APP-08 – VT-006: Phần 3 – Test-first preparation

## Trạng thái repository tại thời điểm kiểm tra

- Repository: `D:\SE2026-T02-Resilient-Order-Workflow`
- Branch: `feature/vt006-async-progress-23001866`
- HEAD/baseline: `a513241d0e71e2c0eb7adaa32198e0b3902cf766`
- Working tree trước thay đổi: chỉ có các đường dẫn untracked đã tồn tại (`docs/phan1.md`, `docs/spec_and_plan.md`, `docs/test_strategy.md`, `evidence/`); `git diff` và `git diff --cached` rỗng.

## Vertical slice và quyết định test-first

Slice mục tiêu là POST Order → persist Order/Saga/RESERVE step → worker thực hiện bất đồng bộ RESERVE → GET Order quan sát trạng thái đã persist.

Không thể viết một test thực thi đúng contract cho phần bất đồng bộ/progress của slice này trên baseline hiện tại mà không tự tạo giả định về production class/API hoặc response contract. Do đó trong bước này **không tạo/sửa test code, không chạy test và không ghi nhận PASS/FAIL**. Đây không phải kết luận rằng yêu cầu sai; đây là blocker về khả năng quan sát và executable behavior hiện chưa có trong source.

Các tiêu chí/risk liên quan: AC-01 (tiếp nhận Order), AC-02 (đọc trạng thái Order đã persist), AC-03 (idempotency; đã có coverage), AC-04 (không lặp business effect khi retry), AC-05 (chỉ thể hiện kết quả đã xác minh/commit); R1 (duplicate Order/Saga/step), R2 (duplicate downstream effect sau lost response), R3 (hai worker cùng step), R4 (GET không phản ánh persist), R6 (khôi phục sau restart). AC/Risk được trích từ `evidence/part1_specification.md` và `evidence/part2_test_strategy.md`; các risk về worker/downstream còn là test mục tiêu, không phải lỗi đã quan sát.

## Evidence từ source và test hiện tại

| Quan sát | Evidence | Ý nghĩa cho slice |
|---|---|---|
| API hiện có POST trả 202 và GET theo `orderId`. | `order-service/src/main/java/com/example/orderworkflow/order/api/OrderController.java:28-41` | Có thể kiểm tra contract tiếp nhận và query Order hiện hữu. |
| `OrderService.create` claim idempotency; trong cùng luồng tạo Order, Saga và RESERVE step với key `reserve:{orderId}:v1`; step bắt đầu `NOT_STARTED`; response Order bắt đầu `PENDING`. | `order-service/src/main/java/com/example/orderworkflow/order/service/OrderService.java:50-106`; `order-service/src/main/java/com/example/orderworkflow/order/repository/SagaRepository.java:23-37` | Intent được persist cùng luồng create; đoạn source này không thực hiện Reserve bất đồng bộ hoặc cập nhật step sau downstream. |
| GET đọc Order từ `OrderRepository` rồi ánh xạ sang response. | `order-service/src/main/java/com/example/orderworkflow/order/service/OrderService.java:133-138` | GET phản ánh Order record; không đọc Saga step để trả tiến trình step. |
| Public response chỉ gồm `orderId`, `status`, `createdAt`, `expireAt`, `correlationId`. | `order-service/src/main/java/com/example/orderworkflow/order/api/OrderDtos.java:27-28` | Không có field step status/progress để một test có thể assert mà không có contract bổ sung. Không giả định field mới. |
| Order source tree hiện có controller, DTO, service, repositories; tìm kiếm source không tìm thấy `@Scheduled`, `@RabbitListener`, `@KafkaListener`, Inventory client hay executor worker. | Danh sách `order-service/src/main/java/com/example/orderworkflow/order` và truy vấn `rg` trên source Order ở lần review này | Chưa có worker/runtime dispatch cho RESERVE trong module Order theo code được kiểm tra. Schema/repository có khả năng lưu claim/lease không tự chứng minh worker thực thi. |
| Test integration chạy Spring Boot HTTP server với PostgreSQL Testcontainers và fixture DB; test tạo Order xác nhận một Order/Saga/step, RESERVE/NOT_STARTED và idempotency key. | `order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java:39-75,77-94` | Đây là harness phù hợp để mở rộng test tích hợp khi behavior/worker được implement; hiện test chỉ xác nhận intent được tạo. |
| Test hiện có GET Order đã tạo, ID không tồn tại và malformed ID; test idempotency/concurrency cũng hiện hữu. | `order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java:114-125` và các test phía sau trong cùng file; Part 2 ghi coverage tại `evidence/part2_test_strategy.md:109-117,126-130` | Không nên thêm test lặp cho create/persistence/GET/idempotency khi không có hành vi mới để quan sát. |
| Inventory có contract Reserve và idempotency/replay, nhưng đây là tài liệu contract; Order source không có client gọi API này. | `docs/architecture/14-inventory-api-contract.md:9-14,34-56`; kết quả tìm kiếm Order source ở trên | Contract đích không phải bằng chứng đã có luồng Order→Inventory chạy được. |

## Test file/method và khả năng chạy

- **Test file hiện có phù hợp làm điểm mở rộng sau này:** `order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java`.
- **Test method hiện có liên quan:** `createsPendingOrderWithDatabaseTtl()` (POST và kiểm tra Order/Saga/RESERVE intent đã persist); `getsExistingOrderAndReportsMissingOrInvalidIdentifiers()` (GET Order và lỗi identifier). Hai method này chưa kiểm tra worker, downstream Reserve, hay tiến trình async.
- **Test method mới:** chưa tạo. Không gọi class/method giả định và không sửa test hiện có.
- **Lệnh chạy riêng test hiện có (tham khảo, không chạy trong bước này):** `./mvnw -pl order-service -Dtest=OrderIntegrationTests test` (PowerShell: `.\mvnw -pl order-service -Dtest=OrderIntegrationTests test`). Lệnh này chỉ chạy characterization hiện có; nó không thể chứng minh hành vi async RESERVE chưa tồn tại.
- **Expected FAIL trên baseline:** chưa thể xác định một assertion hợp lệ cho “worker đã xử lý RESERVE và GET thể hiện tiến trình”, vì response hiện không có progress field/step status, và không có worker/client cập nhật step. Nếu viết test gọi API/field/class chưa có, kết quả sẽ là compile/discovery failure hoặc test sẽ áp đặt contract mới, không phải behavioral FAIL hợp lệ. Nếu chỉ assert Order `PENDING`, Saga và step `NOT_STARTED`, test sẽ trùng hành vi đã được kiểm tra và có thể PASS trên baseline.
- **Kết quả thực tế:** không chạy test; không có output hoặc exit code. Vì chưa có test mới nên chưa thể tuyên bố FAIL đúng yêu cầu. Không thay đổi production code để làm test pass.

## Harness đề xuất sau khi blocker được giải quyết

Khi worker/dispatch contract và trạng thái mà GET cần hiển thị được chốt, mở rộng Order integration harness dùng PostgreSQL Testcontainers. Submit Order qua HTTP; cho worker thực xử lý step RESERVE bằng adapter/downstream test thực tế hoặc stub có persistence/effect counter; chờ điều kiện kết thúc theo cơ chế đồng bộ được thiết kế (không đặt timeout/poll interval giả định tại đây); truy vấn DB xác nhận Order/Saga/step đã commit và kiểm tra số business effect; sau đó GET cùng `orderId` và assert đúng các field/state đã được contract công bố. Test phải quan sát persisted outcome và response, không chỉ verify mock invocation.

Harness này hiện là đề xuất, chưa tạo vì chưa có worker/client và chưa có public progress contract. Không tự chọn transport, trạng thái, field, retry/backoff, lease duration hoặc polling strategy. Test lost-response/idempotent downstream và concurrent claim cần contract/implementation tương ứng trước khi có thể viết test executable. Existing Testcontainers PostgreSQL chỉ xác nhận DB test fixture; restart-resume harness chưa được chứng minh bởi test hiện tại.

## Giới hạn và điều kiện tiếp tục

Phần 3 chưa thể đạt yêu cầu “test behavioral FAIL trước implementation” cho đoạn bất đồng bộ bằng code repository hiện tại. Cần xác nhận contract progress (Order-level hay Saga-step-level và biểu diễn qua API hiện có hay thay đổi contract) và có worker/dispatch path cùng Inventory adapter để tạo một test không giả định. Việc quyết định/thay đổi production architecture nằm ngoài thay đổi được phép của bước này; dừng trước khi viết test. Các test POST/persistence/GET/idempotency hiện hữu vẫn là characterization/regression coverage cho hành vi hiện tại, không phải bằng chứng async slice đã hoạt động.
