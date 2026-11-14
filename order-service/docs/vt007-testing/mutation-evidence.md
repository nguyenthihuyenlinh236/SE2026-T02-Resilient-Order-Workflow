# Deliberate mutation evidence

## State and mutation

- Correct source HEAD before mutation: `37a182cae38726396ba4ff1de07bc73a4423d876`.
- Isolated location: detached worktree `/tmp/vt007-mutation`; the submission branch was not edited.
- Deliberate diff:

```diff
- if (request.revision() == current.revision() && hash.equals(current.payloadHash())) {
+ if (request.revision() == current.revision()) {
```

This removes the payload-hash guard, incorrectly treating a conflicting payload at the same revision
as an idempotent replay.

## Requirement test and result

Command:

```bash
mvn -B -pl order-service \
  -Dtest=OrderDraftIntegrationTests#sameRevisionDifferentSnapshotIsConflict test
```

Actual result: **mutation killed**. The test compiled and ran; 1 test, 1 failure, 0 errors.
The invariant assertion expected HTTP `409 CONFLICT` but the mutant returned `200 OK`.
No additional test was needed because the existing requirement-based test detected the bug.

## Restoration evidence

The dirty detached worktree was removed with `git worktree remove --force /tmp/vt007-mutation`.
On the submission branch, `OrderDraftService` still contains
`request.revision() == current.revision() && hash.equals(current.payloadHash())`; `git status` was
clean immediately after restoration. The deliberate mutation was never committed or pushed.
