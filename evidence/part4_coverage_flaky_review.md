# BT3 APP-08 VT-006 – Coverage and flaky-test review

## Coverage configuration and run

Repository scan of root `pom.xml` and `order-service/pom.xml` found no configured JaCoCo, PIT, Cobertura or other coverage plugin. A one-off JaCoCo 0.8.12 agent/report invocation was available without changing either POM:

```powershell
& 'D:\IntelliJ IDEA Community Edition 2023.3.3\plugins\maven\lib\maven3\bin\mvn.cmd' '-Dmaven.repo.local=C:\Users\fah4i\.m2\repository' -pl order-service org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent '-Dtest=OrderDispatchIntegrationTests,SagaRepositoryIntegrationTests,OrderIntegrationTests,OrderStateMachineIntegrationTests' test org.jacoco:jacoco-maven-plugin:0.8.12:report
```

Environment: Docker Desktop 29.9.3, PostgreSQL 16.16 Testcontainers, Spring Boot 3.5.7, Java runtime 21.0.2 (Java 17 release target). Surefire: **36 tests, 0 failures, 0 errors, 0 skipped**. JaCoCo analyzed 34 module classes and wrote `order-service/target/site/jacoco/index.html`, `jacoco.csv`, and `jacoco.exec`.

Measured module-wide coverage from JaCoCo CSV:

- Lines: 471 covered / 545 total = **86.42%**.
- Branches: 196 covered / 270 total = **72.59%**.

Selected production classes (covered / total):

| Class | Lines | Branches |
|---|---:|---:|
| `InventoryClient` | 28 / 32 | 22 / 38 |
| `ReserveResultHandler` | 25 / 30 | 17 / 22 |
| `ReserveStepWorker` | 12 / 12 | 2 / 4 |
| `SagaRepository` | 58 / 82 | 7 / 12 |
| `OrderStateMachine` | 82 / 92 | 77 / 94 |

The module percentage includes all instrumented classes and existing tests; it is not a per-feature score. Branch coverage is lower than line coverage and must not be read as exhaustive workflow verification.

## Relevant behavior verified and gaps

- **RESERVED:** `OrderDispatchIntegrationTests#postDispatchesPersistedReserveIntentToInventory` verifies the public HTTP request, response, persisted reservation `RESERVED/verified`, step `SUCCEEDED`, and Order remains `PENDING`.
- **REJECTED:** `verifiedRejectionCompletesGuardedFailureBranch` verifies `FAILED/BUSINESS`, Order GET `FAILED`, completed Saga, and `OrderFailed` Outbox.
- **UNKNOWN:** `lostReserveResponseRemainsUnknownAndDoesNotFailOrder` closes the fixture response and verifies unverified Order reservation/step UNKNOWN and Order `PENDING`. The InventoryClient transport-error branch is exercised.
- **Claim/fencing:** `SagaRepositoryIntegrationTests#claimsDueStepForSingleOwnerAndFencesStaleToken` races two callers, expires the lease, verifies takeover with a new token, rejects the old token's update, and accepts the new owner's update.
- **Retry gap:** the UNKNOWN test does not assert a second HTTP send, same-key replay, eventual reconciliation, or bounded retry/backoff. UNKNOWN storage is covered; retry behavior is not claimed as verified. Malformed response, identity mismatch, non-2xx, lease expiry while an actual remote call remains in flight, and multiple-worker load are not fully tested.

## Flaky-test risk review

This is a **static review** of the test/source structure, supported by successful test runs; no nondeterministic failure was reproduced. The deliberate mutation failure in `part4_mutation_testing.md` was deterministic and is not counted as flakiness.

| Risk group and location | Why it could be flaky | Mitigation |
|---|---|---|
| Time – `OrderDispatchIntegrationTests.awaitStepStatus` and dispatch waits | Worker state is polled every 25 ms with a 5-second deadline; the test fixture readiness wait is 2 seconds. Slow CI/Docker startup or scheduler pauses can exceed these bounds. | Keep fixture readiness as a separate precondition (already present); use a condition-based waiter whose deadline is configured above the HTTP timeout + lease/poll window, and report observed DB state on timeout. Current tests passed, but the bound has not been stressed on slow CI. |
| Shared state / execution order – static `InventoryFixture`, `resetDatabase`, and UNKNOWN retries in `OrderDispatchIntegrationTests` | A timed-out request may be retried after the next test resets the shared request reference/latch and changes fixture outcome. Async work from one test could be observed by another if timing overlaps. | Give each test an isolated fixture/context, or wait until its step has no active claim and prevent further retries before resetting shared state. Current fixture is shared across methods; this isolation is not implemented. |
| Concurrency – `SagaRepositoryIntegrationTests` | The start latch releases both callers together, but scheduling may let one transaction finish before the other enters SQL. The one-owner assertion is valid, but this does not guarantee the test exercises overlapping row locks/`SKIP LOCKED`. | Add a test-only DB synchronization barrier around claim execution or run a repeated multi-worker claim stress case while asserting one durable owner. No duplicate-owner failure was observed; true lock overlap is not proven by the current barrier alone. |
| Network – single-thread local HTTP fixture in `OrderDispatchIntegrationTests` | Fixture requests are serialized; repeated UNKNOWN retries can queue. Local loopback and readiness probing reduce external-network variability, but a slow handler could interact with read timeout. | Keep fixture responses minimal, retain explicit readiness and bounded HTTP timeouts, and isolate retry tests or make the fixture handle requests on a bounded multi-thread executor. No network flake was reproduced. |

T03 passed after mutation restoration and in the subsequent 36-test JaCoCo run; those runs were not a dedicated randomized/repeated flake campaign.

## Git boundary

Before Part 4, status already showed `SagaRepository.java` modified from Part 3 plus untracked docs/evidence and Part 3 implementation/test files; staged diff was empty. After Part 4, tracked diff remains only that existing `SagaRepository.java` change, and staged diff remains empty. The three Part 4 evidence files and `OrderPerformanceHarnessTest.java` are new untracked files. Existing untracked files remain present; `git diff --check` is clean. No commit, push, branch change, PR, merge or GitHub update was performed.
