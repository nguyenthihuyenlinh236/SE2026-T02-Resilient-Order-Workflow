# BT3 APP-08 VT-006 — Part 3.2 test-first execution record

## Repository state before this step

- Repository: `D:\SE2026-T02-Resilient-Order-Workflow`
- Branch: `feature/vt006-async-progress-23001866`
- HEAD/baseline: `a513241d0e71e2c0eb7adaa32198e0b3902cf766`
- `git status --short` before work: `?? docs/phan1.md`, `?? docs/spec_and_plan.md`, `?? docs/test_strategy.md`, `?? evidence/`.
- `git diff --` and `git diff --cached`: empty.
- The four requested prior evidence files exist. This execution record did not exist before this step.

## Outcome: blocked before writing test code

The requested test was **not created**, and no test fixture was added. This is necessary to avoid a test whose failure would be caused by a non-running fixture or an invented worker path rather than a valid behavioral assertion.

The requested method `processesVerifiedReserveRejectionAsynchronouslyAndGetShowsCompensating` would need an actual Order worker to claim and execute the persisted RESERVE step, and an Order-side Inventory client for a downstream fixture to answer. The current Order production source contains `OrderController`, DTOs, `OrderService`, repositories, and `OrderStateMachine`; it has no worker/dispatcher or Inventory client. `OrderIntegrationTests` has a Spring Boot HTTP server, PostgreSQL Testcontainers and product catalog fixture, but no Inventory service container/server or worker synchronization fixture (`order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java:39-75`; Order source tree under `order-service/src/main/java/com/example/orderworkflow/order`).

The existing creation path persists Order `PENDING`, Saga `RUNNING`, and one RESERVE step `NOT_STARTED`, then returns. It does not dispatch the step (`order-service/src/main/java/com/example/orderworkflow/order/service/OrderService.java:92-106`; `order-service/src/main/java/com/example/orderworkflow/order/repository/SagaRepository.java:23-37`). `OrderStateMachine` can record a verified `REJECTED` reservation and move a PENDING Order into compensation, but those are direct state-machine calls, not async worker execution (`order-service/src/main/java/com/example/orderworkflow/order/service/OrderStateMachine.java:29-40,74-80`; `order-service/src/main/java/com/example/orderworkflow/order/repository/OrderStateRepository.java:68-74`).

If a test directly wrote `reservation_status='REJECTED'` or called `recordReservation`/`startCompensation`, it would manufacture the outcome instead of testing the worker. If it added a test-only worker that claims/dispatches and updates state, that fixture would implement the behavior under test. If it POSTed and waited for GET to change without a worker or downstream stimulus, it would fail due to the absent fixture/worker and timeout, which the instructions explicitly reject as a valid FAIL. The controller/DTO path itself exists, so there is no compile error to report; however, no valid executable test for the requested full async behavior can be constructed on this baseline under the stated restrictions.

## Planned test (not created)

- **Target test file:** `order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java`.
- **Proposed method:** `processesVerifiedReserveRejectionAsynchronouslyAndGetShowsCompensating`.
- **Arrange:** valid catalog fixture is available in the existing class. Missing prerequisites are an actual Order worker/client runtime and a deterministic Inventory downstream fixture reachable by that client. No test fixture was added because a fake downstream alone cannot cause the absent worker to call it.
- **Act:** POST a valid order; assert 202/PENDING; let the real worker execute RESERVE and receive verified REJECTED; after persistence, GET the same `orderId`.
- **Expected assertion once prerequisites exist:** GET `status=COMPENSATING`; DB Order `status=COMPENSATING`; reservation result `REJECTED` with verified flag true; assert Saga/step only against the runtime's defined terminal/intermediate contract. No new field/status is assumed here.
- **Baseline expected behavior:** without a worker, POST leaves the step `NOT_STARTED` and Order `PENDING`. Observing that state is already covered by `createsPendingOrderWithDatabaseTtl()` (`OrderIntegrationTests.java:77-94`). A new test asserting it would duplicate existing behavior and would not prove async processing.

## Minimal harness needed to make the test meaningful

Keep the existing PostgreSQL/TestRestTemplate integration harness. Add or configure a deterministic downstream Inventory test endpoint/container that returns the actual contract's verified business rejection, and enable the real Order worker/client implementation to consume the persisted RESERVE step and call that endpoint. Provide a synchronization mechanism based on persisted state (for example, awaiting the expected DB status, with a bounded timeout used only to fail a test that otherwise stalls). Then GET through HTTP and assert that it returns the persisted `COMPENSATING` status and independently query Order/reservation/Saga-step persistence. The exact worker hook, transport, downstream fixture implementation, step status and wait bound must follow the eventual implementation/contract; none can be truthfully named or configured from current source. Creating the worker/client is production implementation and is outside this test-only step.

## Test execution

- **Command:** none; there is no new test method to select or execute.
- **Compile check:** not run; no test source was written. The requested test cannot be made executable without the missing production worker/client (or a test fixture that would improperly simulate the worker).
- **Test output / exit code:** none; no test was run. No PASS or FAIL is claimed.
- **Behavioral FAIL classification:** **No valid behavioral FAIL was obtained.** This is blocked before test creation, not an assertion failure and not a test infrastructure failure.

## Files changed and code boundary

- Created: `evidence/part3_test_first_execution.md` only.
- Test files changed: none.
- Production code, API/DTO, migrations, configuration, requirements and architecture docs changed: none.
- No tests, benchmarks or load tests were run. No commit, push, PR or merge was performed.

## Git state after this step

- Branch: `feature/vt006-async-progress-23001866` (unchanged).
- HEAD: `a513241d0e71e2c0eb7adaa32198e0b3902cf766` (unchanged).
- `git status --short`: `?? docs/phan1.md`, `?? docs/spec_and_plan.md`, `?? docs/test_strategy.md`, `?? evidence/`.
- `git diff --` and `git diff --cached`: empty. The pre-existing untracked files were left untouched; the newly created evidence file is under the already-untracked `evidence/` directory.

## Part 3 – lần chạy thực tế (2026-11-14)

Lần thử trước được ghi ở đầu tài liệu từng dừng trước khi chạy test vì `mvn` không có trong PATH. Phần ghi nhận đó chỉ áp dụng cho lần thử cũ; lần chạy bên dưới là kết quả thực tế mới nhất cho T03.

### Test, lệnh và môi trường

- Test: `OrderIntegrationTests#postDispatchesPersistedReserveIntentToInventory` trong `order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java`.
- Lệnh: `mvn -pl order-service -Dtest=OrderIntegrationTests#postDispatchesPersistedReserveIntentToInventory test`.
- Docker Desktop và PostgreSQL Testcontainers hoạt động bình thường; `postgres:16-alpine` khởi động thành công (Spring log ghi PostgreSQL 16.16).
- Spring Boot khởi động thành công với Java 17.0.21. Flyway validate và áp dụng đủ 5 migration, tới `v005`.
- Surefire report `order-service/target/surefire-reports/TEST-com.example.orderworkflow.order.OrderIntegrationTests.xml` xác nhận đúng test method đã chạy: `tests=1`, `failures=1`, `errors=0`, `skipped=0`. File `.txt` cùng thư mục ghi `Tests run: 1, Failures: 1, Errors: 0, Skipped: 0`.
- Test source đã biên dịch và thực thi đến assertion tại dòng 145; đây không phải compile failure hay lỗi trước test discovery.

### Kết quả và phân loại

POST tạo Order trả về 202 và các assertion trước đó về trạng thái/persistence của Order, Saga và RESERVE intent đi qua. HTTP fixture đã sẵn sàng trước POST, nhưng không ghi nhận request Reserve trong khoảng chờ; assertion thất bại tại `OrderIntegrationTests.java:145` với thông báo `Inventory fixture received a Reserve request after it was ready; captured=null`.

Đây là **behavioral failure**: test, ứng dụng, database và fixture đã chạy; hành vi mong đợi là gửi Reserve tới Inventory fixture nhưng request không xuất hiện. Đây **không phải infrastructure error** (không phải Docker/Testcontainers, PostgreSQL, Spring Boot, Flyway, compile hoặc fixture-startup failure). Kết quả chứng minh thiếu request dispatch được quan sát bởi test này, không xác định nguyên nhân nội bộ sâu hơn.

Trong bước ghi nhận này không sửa production code, assertion cũng không bị sửa/vô hiệu hóa và implementation không chạy. Không commit, push, tạo branch hoặc thay đổi GitHub.
