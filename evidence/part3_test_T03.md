# BT3 APP-08 VT-006 – Part 3.5: T03 execution record

## Test

- Test file: `order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java`
- Method: `postDispatchesPersistedReserveIntentToInventory`
- Scope: POST acceptance and persisted RESERVE intent followed by a request observed by a local Inventory HTTP fixture. No rejection, compensation, or terminal status is asserted.
- Test-only URL property: `inventory.base-url`, supplied through `@DynamicPropertySource`. This property is not present in production configuration on the baseline; it is a proposed seam for a future production client and is unused by the current Order application.

## Fixture and test flow

`InventoryHttpFixture` is a test-only `com.sun.net.httpserver.HttpServer` bound to `127.0.0.1` on an ephemeral port. Its constructor makes an HTTP GET to `/__ready`; the test also requires the readiness latch before POST. It captures request method, path, headers, and parsed JSON body, and uses a bounded three-second wait for a Reserve request. Its response is shaped as the public Reservation response (`reservationId`, `orderId`, `storeId`, `reserveKey`, `status`, `rejectionReason`, `items`, `createdAt`) with `REJECTED`; that response is not expected to be reached on baseline. `@AfterAll` stops the server and executor.

The test POSTs through `TestRestTemplate`, asserts HTTP 202 and Order `PENDING`, checks the persisted Order/Saga/RESERVE row and key, then waits for the fixture request. If one arrives, it compares the actual public route, key, and body fields against the persisted `request_payload`. It does not require a correlation header and does not modify database state to cause dispatch.

## Files changed

- Modified test source: `order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java`
- Created this evidence file: `evidence/part3_test_T03.md`

No production source, API/DTO, migration, production configuration, requirements, or prior evidence file was changed.

## Command and actual result

Command attempted:

```text
mvn -pl order-service -Dtest=OrderIntegrationTests#postDispatchesPersistedReserveIntentToInventory test
```

Actual shell output:

```text
mvn : The term 'mvn' is not recognized as the name of a cmdlet, function, script file, or operable program. Check the spelling of the name, or if a path was included, verify that the path is correct.
At line:2 char:1
+ mvn -pl order-service -Dtest=OrderIntegrationTests#postDispatchesPersistedReserveIntentToInventory test
+ ~~~
    + CategoryInfo          : ObjectNotFound: (mvn:String) [], CommandNotFoundException
    + FullyQualifiedErrorId : CommandNotFoundException
```

- Shell command exit code: `1`.
- Maven was not found in PATH. The repository has no `mvnw` or `.mvn/wrapper/maven-wrapper.properties`; Java 17 is present.
- The test runner did not start. Consequently there is no test output, assertion failure, compile result, fixture readiness result, POST result, or test PASS/FAIL result to report.
- Failure classification: **build-tool/environment blocker before test discovery**, not a behavioral failure. In particular, fixture readiness, POST, and the missing-dispatch assertion were not executed.
- Behavioral FAIL on baseline: **not established**. T03 remains unverified until run with Maven available. Do not treat the attempted command error as the intended missing-dispatch red test.

## Git state

Before editing, verified:

- Branch: `feature/vt006-async-progress-23001866`
- HEAD: `a513241d0e71e2c0eb7adaa32198e0b3902cf766`
- Existing short status: `?? docs/phan1.md`, `?? docs/spec_and_plan.md`, `?? docs/test_strategy.md`, `?? evidence/`
- Tracked and staged diffs were empty.
- This evidence path did not exist before this task. Other untracked files were preserved.

After editing, verified:

- Branch: `feature/vt006-async-progress-23001866`
- HEAD: `a513241d0e71e2c0eb7adaa32198e0b3902cf766` (unchanged)
- `git status --short`:
  ```text
   M order-service/src/test/java/com/example/orderworkflow/order/OrderIntegrationTests.java
  ?? docs/phan1.md
  ?? docs/spec_and_plan.md
  ?? docs/test_strategy.md
  ?? evidence/
  ```
- `git diff --check`: no whitespace errors.
- `git diff --cached`: empty.
- No commit, push, PR, merge, or branch change was performed.
