# Coverage — 2026-11-14

JaCoCo 0.8.12 was absent and is now configured in order-service/pom.xml:
prepare-agent before tests; report during verify. No production classes excluded.

```powershell
mvn -pl order-service verify
```

Actual run: 45 tests, 0 failures/errors/skipped, BUILD SUCCESS. Evidence:
`coverage.log`, `coverage.csv`, `coverage.xml`. HTML: `order-service/target/site/jacoco/index.html`.
Coverage is from this full-module run, not the later load run (JaCoCo disabled there).

| Scope | Lines covered/total | Line % | Branches covered/total | Branch % |
|---|---:|---:|---:|---:|
| Entire Order module | 208/233 | 89.27 | 73/98 | 74.49 |
| OrderController | 7/7 | 100 | 0/0 | N/A |
| OrderService | 106/123 | 86.18 | 50/66 | 75.76 |
| OrderRepository | 54/55 | 98.18 | 9/10 | 90.00 |
| OrderErrorHandler | 24/27 | 88.89 | 10/18 | 55.56 |
| OrderDtos.OrderPage | 1/1 | 100 | 0/0 | N/A |

Search methods specifically:

| Method | Lines | Branches |
|---|---:|---:|
| OrderController.search | 1/1 | N/A |
| OrderService.search | 11/11 | 14/14 |
| OrderRepository.searchWhere | 8/8 | 4/4 |
| OrderRepository.countOrders | 3/3 | N/A |
| OrderRepository.searchOrders | 8/8 | N/A |
| OrderErrorHandler.invalidQuery | 2/2 | N/A |

100% of measured search branches does not prove all possible behaviors. Important
remaining gaps include transient/recoverable database failures mapped to 503,
transaction creation failures, incomplete/corrupt persisted idempotency snapshots,
and some catalog/amount validation paths in the existing create flow. A permanent
query error is exercised by T11. SQL AND semantics and ordering are asserted using
fixtures; they are not counted as Java branches by JaCoCo. Concurrent writes
between page requests, authorization and query performance need separate evidence.
