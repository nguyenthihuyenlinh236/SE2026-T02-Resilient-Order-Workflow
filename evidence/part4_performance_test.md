# BT3 APP-08 VT-006 – Performance test

## Existing harness review and safe test setup

Repository search found no pre-existing JMeter, Gatling, k6, wrk, or other performance harness. The Order API is `POST /api/v1/orders` (`OrderController.create`) and `GET /api/v1/orders/{orderId}` (`OrderController.get`).

Added executable test harness `order-service/src/test/java/com/example/orderworkflow/order/OrderPerformanceHarnessTest.java`. It runs the Spring Boot application on a random port with PostgreSQL Testcontainers, seeds one catalog item, releases **60 concurrent tasks** through a start gate, and has each task POST a unique Order then GET its persisted status. It measures POST and GET latency separately and combined, calculates nearest-rank p50/p95/p99 and successful-request throughput, and reports errors.

The harness sets `order.worker.enabled=false`. No Inventory fixture is needed because the worker does not make downstream calls. This avoids enabling the known unresolved expiry/late-Reserve race. POST acceptance is measured separately from Order completion; this run did not process Inventory Reserve or wait for a terminal Order state.

## Command and measured run

```powershell
& 'D:\IntelliJ IDEA Community Edition 2023.3.3\plugins\maven\lib\maven3\bin\mvn.cmd' '-Dmaven.repo.local=C:\Users\fah4i\.m2\repository' -pl order-service -Dtest=OrderPerformanceHarnessTest test
```

Environment: Docker Desktop 29.9.3, PostgreSQL `16-alpine` (server 16.16), Spring Boot 3.5.7, Java runtime 21.0.2 with Java 17 release compilation. The measurement starts after application startup and database seeding; it excludes migration/startup time.

Actual harness output:

```text
PERF_RESULT users=60 tasks=60 requests=120 successes=120 errors=0 elapsed_ms=1820.16 post_p50_ms=1654.00 post_p95_ms=1658.00 post_p99_ms=1664.00 get_p50_ms=97.00 get_p95_ms=165.00 get_p99_ms=166.00 combined_p50_ms=166.00 combined_p95_ms=1657.00 combined_p99_ms=1662.00 throughput_rps=65.93 worker_enabled=false order_completion_measured=false
PERF_ERRORS []
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

For this single local burst, all 60 POSTs returned 202 and all 60 GETs returned the matching Order; measured p95 was 1,658 ms for POST, 165 ms for GET, and 1,657 ms combined. The harness's ≤2,000 ms p95 assertions passed for this run.

## Limits

- This is one 60-task burst on the developer machine, not a repeated/steady-state benchmark or CI/production capacity claim. No warm-up/ramp profile, resource telemetry, or cross-run variance was collected.
- The POST metric is **acceptance latency** through Order+Saga intent persistence. GET reports the committed state (`PENDING`) while the worker is disabled. Inventory response handling and end-to-end Order completion time are explicitly **not measured**.
- The result does not resolve expiry coordination and must not be used to justify enabling the worker in a runtime where expiry can race Reserve.
