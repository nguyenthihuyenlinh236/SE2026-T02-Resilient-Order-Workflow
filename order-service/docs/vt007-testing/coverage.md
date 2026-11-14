# Coverage statement

JaCoCo or another line/branch coverage agent is not configured in the repository, and no cached
JaCoCo plugin was available in the execution environment. No percentage is invented and dependency
configuration was not expanded only to manufacture a metric.

Risk coverage is used instead:

| Risk | Direct executable check |
|---|---|
| Stale overwrite | stale revision test plus final max-revision race assertion |
| Same-revision conflict mistaken for replay | conflict test; also killed the deliberate mutation |
| Replay changes persistence timestamp | replay test checks unchanged `savedAt` |
| Snapshot loses null/order/duplicates | null-vs-empty regression and ordered duplicate-item test |
| Partial write on database failure | trigger-injected rollback test |
| Client partition leakage | same Draft ID under two demo client keys |
| Load produces 5xx/timeout or loses maximum | 150-task harness, per-request statuses, final GET |

Limitation: risk coverage demonstrates selected behavior and does not replace structural line/branch
coverage. Adding JaCoCo can be reviewed in a later scope if a numeric course rubric requires it.
