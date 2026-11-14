# APP-08 VT-006 – Part 3 implementation record

## Scope delivered

Implemented the opt-in vertical slice from committed Order intent through asynchronous Inventory Reserve and durable outcome handling.

- `SagaRepository.claimReserve` claims one eligible RESERVE row in a short transaction using `FOR UPDATE SKIP LOCKED`, an atomic update, lease, UUID claim token and durable SEND attempt. Eligibility is restricted to a forward-running Saga and a PENDING Order. Expired IN_PROGRESS leases can be taken over.
- Inventory HTTP runs after the claim transaction commits. `InventoryClient` posts to `POST /api/v1/inventory/reservations`, sends `Idempotency-Key: reserve:{orderId}:v1`, posts the persisted `request_payload` JSON snapshot unchanged, and parses the documented public Reservation response fields. It does not use the `/internal/v1` projection or its DTO.
- `ReserveResultHandler` applies outcomes in a separate short transaction and only while the claim token still owns the step. RESERVED persists a verified reservation and SUCCEEDED step; the Order remains PENDING and Saga remains RUNNING. REJECTED persists verified Reservation and FAILED/BUSINESS step, then uses the existing guarded `PENDING → COMPENSATING → FAILED` state machine flow; Saga completion and `OrderFailed` Outbox are committed in the same transaction. No Release call is made for verified rejection.
- Transport errors, timeouts, non-2xx responses, malformed responses and identity mismatches become UNKNOWN, never REJECTED. UNKNOWN is unverified and retries by replaying the same saved body/key after the configured delay. A stale token cannot update the step.
- Worker startup is opt-in with `order.worker.enabled=true` and `inventory.base-url` set. The default is disabled because the expiry/late-Reserve cancellation coordinator described in Technical Plan V2 is not implemented; deployment must not enable it where Order expiry can race an in-flight Reserve.
- `current_operation` is left as existing source writes it. No CAPTURE step, Payment call, or new cursor semantics were added. No migration was needed.

## Changed files

Production:

- `order-service/src/main/java/com/example/orderworkflow/order/repository/SagaRepository.java`
- `order-service/src/main/java/com/example/orderworkflow/order/service/InventoryClient.java` (new; client-side DTO records mirror the selected public Reserve contract because the services are separate Maven modules)
- `order-service/src/main/java/com/example/orderworkflow/order/service/ReserveResultHandler.java` (new)
- `order-service/src/main/java/com/example/orderworkflow/order/service/ReserveStepWorker.java` (new)
- `order-service/src/main/java/com/example/orderworkflow/order/service/ReserveWorkerConfiguration.java` (new; opt-in scheduler and HTTP timeout configuration)

Tests:

- `order-service/src/test/java/com/example/orderworkflow/order/OrderDispatchIntegrationTests.java` (new; T03 plus RESERVED, REJECTED and lost-response/UNKNOWN behavior through PostgreSQL Testcontainers and a ready-checked HTTP fixture)
- `order-service/src/test/java/com/example/orderworkflow/order/SagaRepositoryIntegrationTests.java` (new; concurrent atomic claim, lease takeover and stale-token fencing)
- The earlier T03 method/fixture was moved out of `OrderIntegrationTests` so the worker-enabled test context is isolated. Existing characterization tests remain unchanged.

## Verification

Maven was available through IntelliJ's bundled Maven; the bare `mvn` executable was not on this shell's PATH. The final focused command was:

```powershell
& 'D:\IntelliJ IDEA Community Edition 2023.3.3\plugins\maven\lib\maven3\bin\mvn.cmd' '-Dmaven.repo.local=C:\Users\fah4i\.m2\repository' -pl order-service '-Dtest=OrderDispatchIntegrationTests,SagaRepositoryIntegrationTests,OrderIntegrationTests,OrderStateMachineIntegrationTests' test
```

Environment observed in the run: Docker Desktop 29.9.3, PostgreSQL `16-alpine` (server 16.16), Spring Boot 3.5.7, Java runtime 21.0.2 (compiled with the project's Java 17 release setting). Flyway validated and applied all 5 migrations.

Surefire final result: **36 tests, 0 failures, 0 errors, 0 skipped**. Per class: dispatch 3/3, Saga claim/fencing 1/1, existing Order integration 13/13, Order state machine 19/19. T03 now observes the actual public Reserve call and persisted RESERVED result. The added rejection test observes GET `FAILED`, and the dropped-response test observes Order PENDING with reservation/step UNKNOWN and unverified. The claim test proves one concurrent owner, successful lease takeover with a new token, and rejection of the stale owner's update.

## Remaining limits

- The worker is not enabled by default. Full production enablement still needs a designed and tested expiry/late-response coordination path; without it, an expiry transition racing an Inventory Reserve could leave work unresolved. No Cancel/Release flow was guessed or added.
- UNKNOWN recovery currently retries the same idempotent POST after a configured delay. It does not query Inventory's operation endpoint, and no retry ceiling/backoff policy was approved or implemented.
- CAPTURE/Payment, expiry processing, Outbox publishing, load/performance claims and broader restart/reconciliation tests remain outside this slice.

## Git boundary

Before implementation, status showed the already-modified `OrderIntegrationTests.java`, existing untracked `docs/` files and existing untracked `evidence/`; staged diff was empty. At completion, `OrderIntegrationTests.java` was restored to its original tracked contents after relocating the earlier T03 fixture to the dedicated test class. Only `SagaRepository.java` is modified among tracked files; new implementation/test/evidence files are untracked. Existing untracked docs/evidence files were preserved. `git diff --cached` remains empty. No commit, push, branch change, PR, merge or GitHub change was made.
