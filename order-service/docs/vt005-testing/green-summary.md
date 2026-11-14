# VT-005 GREEN — 2026-11-14

Branch: `feature/vt005-order-search-lananh`. RED commit: `40d2192`.

## Implementation

- Collection GET supports optional customerId/status filters combined with AND.
- page defaults to 0, size to 20 (1–100); invalid integers/ranges/status return 400.
- PostgreSQL performs LIMIT/OFFSET with `created_at DESC, order_id DESC`.
- Count and page use identical bound filters and one read-only REPEATABLE_READ transaction.
- Existing POST and GET-by-ID response DTOs remain unchanged. No migration needed for correctness.
- Database exceptions propagate to the existing infrastructure error handler.

## Actual verification

Commands executed using Maven 3.9.5 and local repository C:/Users/tuyet/.m2/repository:

```powershell
mvn -pl order-service "-Dtest=OrderSearchIntegrationTests,OrderApiContractTests" test
mvn -pl order-service test
```

The first sandbox attempt could not connect to Docker. Both recorded successful runs
were executed outside the sandbox with PostgreSQL Testcontainers.

| Run | Tests | Failures | Errors | Skipped | Result |
|---|---:|---:|---:|---:|---|
| Search + raw JSON contract | 29 | 0 | 0 | 0 | BUILD SUCCESS |
| Entire Order module | 45 | 0 | 0 | 0 | BUILD SUCCESS |

Evidence: `green.log`, `green-full.log`. Credential/configuration lines are replaced
with REDACTED markers; test results are preserved. RED evidence remains unchanged.

All T01–T13 cases (including T12b and parameterized invocations) now pass. T11
executes its successful-search preflight, table-rename fault injection, error
assertions and table restoration. T09/T10 protect the existing API using raw JSON
without importing production response DTOs. The original 16 tests also pass.

## Limits and next phase

Stable pagination is verified for fixed data. Concurrent inserts between separate
page requests can shift offset pages; the snapshot guarantee applies within one
response only. Customer filtering is not authorization. Index/performance choices
remain subject to measurements. Mutation, coverage and 120-task load measurements
have not been performed; passing tests does not establish those results.

Implementation stops at GREEN for user review. No commit or push in this phase.
