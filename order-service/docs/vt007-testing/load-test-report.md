# Load test report: 150 concurrent Draft saves

## Execution

- Harness commit: `3b5a291fee33b995903f660e639ad5287bcabd1d`
- Date: 2026-11-14 21:57 ICT (raw timestamp `2026-11-14T14:57:49.831380313Z`)
- Host: Linux 6.18.57 x86_64, 5 reported CPUs, 33 GiB RAM
- Java used by Maven test: Eclipse Temurin 17.0.17
- Maven: 3.9.11
- Docker server: 29.9.2
- Database: PostgreSQL 16 Alpine Testcontainers; Flyway V001–V006
- Protocol: 150 HTTP PUT tasks, fixed pool 150, one start barrier, one Draft, deterministic shuffled
  revisions 1–150. HTTP 2xx and 409 are valid business outcomes.

Command:

```bash
mvn -B -pl order-service -Dtest=OrderDraftLoadHarness \
  -Dvt007.load.outputDir=docs/vt007-testing/load-results test
```

## Measured result

| Metric | Actual |
|---|---:|
| Tasks / peak active | 150 / 150 |
| HTTP 2xx | 8 |
| HTTP 409 | 142 |
| HTTP 5xx / timeout | 0 / 0 |
| p50 | 1296.691 ms |
| p95 | **1544.143 ms** |
| max | 1617.084 ms |
| Throughput | 92.619 requests/s |
| Final revision | 150 |
| Final payload owner | `CLIENT-150` |

Result: **PASS** against the mandatory p95 <= 4,000 ms target. A final HTTP GET proved that the
largest revision and its matching payload remained stored. This is one measurement on the specified
cloud machine, not a capacity guarantee for production or other hardware.

Raw artifacts: `load-results/load-raw.csv` (150 samples plus header) and
`load-results/load-summary.json`.
