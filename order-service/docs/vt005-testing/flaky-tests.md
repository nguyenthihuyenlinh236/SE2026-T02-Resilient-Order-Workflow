# Test stability — 2026-11-14

Three separate Maven/JVM runs executed:

```powershell
mvn -pl order-service "-Dtest=OrderIntegrationTests#concurrentRequestsWithSameKeyCreateOneOrder,OrderSearchIntegrationTests" "-Djacoco.skip=true" test
```

| Run | Tests | Failures | Errors | Skipped | Result |
|---|---:|---:|---:|---:|---|
| repeat-1.log | 28 | 0 | 0 | 0 | SUCCESS |
| repeat-2.log | 28 | 0 | 0 | 0 | SUCCESS |
| repeat-3.log | 28 | 0 | 0 | 0 | SUCCESS |

84 executions total. Each run creates fresh PostgreSQL Testcontainers. Each includes
10 competing POST requests sharing one idempotency key and verifies one Order and
one idempotency record. These 10 POSTs are unrelated to the 120-task search load test.

## Risks and limits

1. Concurrency/idempotency: thread scheduling changes the winning request and the
   amount of actual overlap. Assertions compare invariants, not a particular winner
   or order; the start latch encourages overlap. The existing test has unbounded
   Future.get(), so a deadlock could hang until external CI timeout. Three successful
   runs do not exhaust interleavings or prove absence of races.
2. Docker/PostgreSQL: engine permissions, image availability, startup resources,
   port allocation and container lifecycle affect integration tests. The first GREEN
   attempt in the restricted sandbox failed Docker discovery; rerunning with Docker
   access passed. This is an observed environment issue, not evidence of application
   nondeterminism. Fresh per-class containers and dynamic ports isolate these tests.
3. Search fixtures reset all business tables before each test, and T11 renames the
   orders table temporarily with finally restoration. These methods must not run
   concurrently against the same database. Current Maven runs are sequential; do
   not enable JUnit method parallelism without database isolation.
4. Fixed timestamps and deterministic IDs remove wall-clock and insertion-order
   assumptions. Pagination guarantees apply to unchanged datasets; independent
   requests during writes can shift offset pages by design.

No intermittent application test failure was observed in these three runs. No
claim that the suite is universally non-flaky is made. A CI timeout and repeated
stress on different environments remain useful follow-up work.
