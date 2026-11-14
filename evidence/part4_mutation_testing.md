# BT3 APP-08 VT-006 – Part 4 mutation testing

## Mutation target and test

- Actual response-field binding: Jackson binds the `status` component of `InventoryClient.PublicReservation` in `order-service/src/main/java/com/example/orderworkflow/order/service/InventoryClient.java:15-16`. `InventoryClient.reserve` branches on `response.status()` at lines 47-55 to distinguish RESERVED and REJECTED; other values become UNKNOWN.
- Test selected: `OrderDispatchIntegrationTests#postDispatchesPersistedReserveIntentToInventory` at `order-service/src/test/java/com/example/orderworkflow/order/OrderDispatchIntegrationTests.java:77`. It requires the saved RESERVE step to become `SUCCEEDED` and the persisted reservation to become `RESERVED`.

## Deliberate mutation and detection

Before mutation, the complete `InventoryClient.java` was copied byte-for-byte to the ignored build path `order-service/target/InventoryClient.java.before-mutation`. Both source and backup had SHA-256:

```text
82062824F3EDC96DF4B2C93AB45ECDD33FEA6FB8FDBEBD82AD27BCA882F52FEE
```

The only deliberate bug mapped the real `status` component from the nonexistent JSON property `status-mutation`:

```java
@JsonProperty("status-mutation") String status
```

The request/response fixture still returned the valid public response with `"status":"RESERVED"`. Command:

```powershell
& 'D:\IntelliJ IDEA Community Edition 2023.3.3\plugins\maven\lib\maven3\bin\mvn.cmd' '-Dmaven.repo.local=C:\Users\fah4i\.m2\repository' -pl order-service '-Dtest=OrderDispatchIntegrationTests#postDispatchesPersistedReserveIntentToInventory' test
```

Environment: Docker Desktop 29.9.3, PostgreSQL 16.16 Testcontainers, Spring Boot 3.5.7, Java runtime 21.0.2. The test compiled and ran through the application and HTTP fixture. Actual Surefire result:

```text
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
OrderDispatchIntegrationTests.postDispatchesPersistedReserveIntentToInventory:108
expected: "SUCCEEDED"
 but was: "UNKNOWN"
```

The test detected the incorrect mapping; no test assertion was changed.

## Restoration and verification

`InventoryClient.java` was restored by copying the saved original file over the source. SHA-256 matched the pre-mutation value above. The first immediate Maven rerun reported `Nothing to compile` because the restored file retained its old timestamp, so it was not accepted as a clean-source PASS. I then forced recompilation and executed:

```powershell
& 'D:\IntelliJ IDEA Community Edition 2023.3.3\plugins\maven\lib\maven3\bin\mvn.cmd' '-Dmaven.repo.local=C:\Users\fah4i\.m2\repository' -pl order-service clean '-Dtest=OrderDispatchIntegrationTests#postDispatchesPersistedReserveIntentToInventory' test
```

Actual result: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`. The clean build removed the temporary backup under `target` after restoration. A subsequent JaCoCo run also passed all three dispatch tests. The source contains no `status-mutation` mapping; no deliberate bug remains.
