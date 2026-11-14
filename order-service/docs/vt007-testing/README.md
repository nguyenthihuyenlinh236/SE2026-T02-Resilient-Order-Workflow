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

Mutation reproduction (chỉ thực hiện trong detached worktree, không commit mutation):

```bash
git worktree add --detach /tmp/vt007-mutation HEAD
# In OrderDraftService, temporarily remove: && hash.equals(current.payloadHash())
mvn -B -pl order-service \
  -Dtest=OrderDraftIntegrationTests#sameRevisionDifferentSnapshotIsConflict test
git worktree remove --force /tmp/vt007-mutation
```

Load harness 150 concurrent HTTP tasks:

```bash
mvn -B -pl order-service -Dtest=OrderDraftLoadHarness \
  -Dvt007.load.outputDir=docs/vt007-testing/load-results test
```

Both commands require a Docker-compatible runtime because integration tests use PostgreSQL Testcontainers.

## Acceptance-criteria mapping

| AC | Executable evidence |
|---|---|
| AC-01 partial draft save/read | `firstSaveAcceptsPartialDraftAndGetReturnsSnapshot` |
| AC-02 only a newer revision replaces the snapshot | `higherRevisionAndRevisionGapReplaceSnapshot`, `staleRevisionReturnsBusinessConflictWithoutOverwrite` |
| AC-03 stale revision protection | `staleRevisionReturnsBusinessConflictWithoutOverwrite` |
| AC-04 same-revision idempotency/conflict | `sameRevisionSameSnapshotReplaysWithoutChangingSavedAt`, `sameRevisionDifferentSnapshotIsConflict` |
| AC-05 atomic concurrency | `concurrentSavesRetainHighestRevisionWithoutServerErrors`, `databaseFailureDoesNotPartiallyReplaceDraft` |
| Snapshot fidelity | `snapshotPreservesItemOrderAndDuplicates`, `nullItemsRemainNullAndAreNotCanonicalizedToEmptyList` |

`X-Client-Id` is only the VT-007 demo partition key; it is not presented as an authentication mechanism.
The mutation and 150-task load run are now recorded in `mutation-evidence.md` and
`load-test-report.md`. The external ChatGPT patch review remains a separately supplied artifact.
