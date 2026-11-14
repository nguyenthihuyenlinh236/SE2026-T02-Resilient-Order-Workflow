# Mutation testing — 2026-11-14

Starting point: RED commit `40d2192` plus the uncommitted GREEN implementation.
Temporary branch: `mutation/vt005-swallowed-query-error` (no mutation commit).
Returned to: `feature/vt005-order-search-lananh`.

The mutation catches DataAccessException around search count/list queries and
returns an empty OrderPage with HTTP 200. Test source/expected values were not changed.

```powershell
mvn -pl order-service "-Dtest=OrderSearchIntegrationTests#t11DatabaseFailureMustNotBecomeSuccessfulEmptyPage" test
```

Actual result: 1 test, 1 failure, 0 errors, 0 skipped, exit 1 / BUILD FAILURE.
Failure: expected `500 INTERNAL_SERVER_ERROR`, actual `200 OK`. The successful-search
preflight passed, so this failure detects the injected wrong behavior, not a missing route.
Evidence: `mutation.log`, `mutation.diff` (diff against the saved GREEN file, not RED).

The service was restored byte-for-byte from the saved GREEN file. Both SHA256:
`ad8ef0fd36584daf2eb8b09c807a7bb98925f3e3eab9daec73d46aecf5d8fa8a`.
Subsequent coverage verify ran all 45 tests successfully, including T11.
No catch-and-empty mutation remains in production. This is one deliberate semantic
mutation; it is not a comprehensive PIT mutation score.
