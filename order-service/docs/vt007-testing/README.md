# VT-007 test execution and AC traceability

## Commands

Focused HTTP/PostgreSQL integration suite:

```bash
mvn -B -pl order-service -Dtest=OrderDraftIntegrationTests test
```

Full repository verification:

```bash
mvn -B clean verify
```

Both commands require a Docker-compatible runtime because integration tests use PostgreSQL Testcontainers.

## Acceptance-criteria mapping

| AC | Executable evidence |
|---|---|
| AC-01 partial draft save/read | `firstSaveAcceptsPartialDraftAndGetReturnsSnapshot` |
| AC-02 only a newer revision replaces the snapshot | `higherRevisionAndRevisionGapReplaceSnapshot`, `staleRevisionReturnsBusinessConflictWithoutOverwrite` |
| AC-03 same-revision idempotency/conflict | `sameRevisionSameSnapshotReplaysWithoutChangingSavedAt`, `sameRevisionDifferentSnapshotIsConflict` |
| AC-04 atomic concurrency and rollback | `concurrentSavesRetainHighestRevisionWithoutServerErrors`, `databaseFailureDoesNotPartiallyReplaceDraft` |
| AC-05 preserve the submitted snapshot | `snapshotPreservesItemOrderAndDuplicates`, `sameDraftIdIsIsolatedByDemoClientScope` |

`X-Client-Id` is only the VT-007 demo partition key; it is not presented as an authentication mechanism.
The 150-task/p95 load run, mutation run and AI patch review are intentionally deferred to later prompts.
