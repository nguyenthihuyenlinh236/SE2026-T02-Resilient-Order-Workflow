# Load test — 2026-11-14

Actual successful script run: 15:17:27 Asia/Bangkok (08:17:27 UTC).
Evidence: `load.log`, `load-results.json` including all 600 request samples.

| Measurement | Result |
|---|---:|
| Concurrent worker tasks | 120 |
| Requests per worker | 5 sequential |
| Measured requests | 600 |
| Warmup requests (excluded) | 20 |
| Errors | 0 |
| Error rate | 0% |
| p50 | 428.783 ms |
| p95 | 689.191 ms |
| p99 | 896.299 ms |
| Measured wall time | 2.271 s |
| Throughput | 264.207 requests/s |
| p95 threshold | 3500 ms |
| Outcome | PASS for this scenario |

## Reproduce

From the repository root in PowerShell, with Docker Desktop running and Java 17:

```powershell
./order-service/scripts/vt005-load.ps1
# If Maven is not in PATH:
./order-service/scripts/vt005-load.ps1 -Maven 'C:/path/to/mvn.cmd'
```

The actual run used Maven 3.9.5 under C:/Users/tuyet/.m2/wrapper/dists and
`-LocalRepository C:/Users/tuyet/.m2/repository`. The script invokes:

```powershell
mvn -pl order-service "-Dtest=OrderSearchLoadHarness" "-Djacoco.skip=true" test
```

The harness is opt-in and does not match default Surefire test names. It starts
the actual Spring HTTP server on a random port and a fresh PostgreSQL container,
seeds 10,000 orders with SQL generate_series (100 customers, two statuses, timestamp
ties), then uses Java HttpClient against localhost. No external database is used
and no production/business data is modified. No extra index or migration was added.

Four equally distributed query shapes: unfiltered page 0; customer filter page 1;
status filter page 2; customer AND status. size=20 throughout. Response validation
requires HTTP 200, items array of 20 and expected filtered total (10000/100/5000/100).
This is not a replacement for detailed ordering assertions in the functional tests.

120 executor threads wait at a ready/start barrier, then each sends five sequential
requests with a 20-second request timeout. This is a closed-loop, short burst with
up to 120 requests in flight, not a sustained arrival rate. Latencies include HTTP,
body reading and JSON validation; warmup/container startup/seeding are excluded.
Percentiles use nearest rank over all measured samples, including errors; wall time
includes completion collection. The JSON records worker/request IDs, HTTP status,
validation result and latency for each measured request.

## Environment and practical limits

- Windows 11 amd64, Java 17.0.9, 12 available processors.
- Spring Boot 3.5.7, PostgreSQL 16.16 (postgres:16-alpine), Testcontainers 1.21.3.
- Docker Desktop engine 29.9.2; Docker reports approximately 3819 MB memory.
- Generator and application share the host; PostgreSQL runs in Docker. DEBUG logging
  is disabled in the harness and JaCoCo is skipped to avoid instrumentation overhead.
- The first attempt produced measurements but PowerShell treated a harmless JVM
  stderr warning as a terminating error. The script now preserves warnings and checks
  Maven's process exit code. The table above uses only the subsequent complete
  BUILD SUCCESS run (1 harness test, no failures/errors/skips).

This passes the p95 <= 3.5 s target for 10,000 fixed local rows and this short burst.
It does not establish production capacity, sustained throughput, performance with
deep offsets, cold caches, concurrent writes, larger datasets or remote networking.
Measure those workloads before choosing additional indexes or pagination changes.
