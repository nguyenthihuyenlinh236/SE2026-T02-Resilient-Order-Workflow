# VT-007 Prompt 03 task record

Continue from branch `feature/vt007-autosave-testfirst` at confirmed HEAD
`a504aaaffdd49653c3a87e1c0907c53e346aa19a`. Do not redo implementation, create a submission branch,
or merge.

Required work in this turn:

1. Review whether converting `items: null` to `items: []` violates snapshot fidelity; add a
   regression test before a minimal fix if it does. Do not treat the 20-request correctness test as
   evidence for the 150-task performance target.
2. In a temporary worktree/branch, deliberately remove either the PostgreSQL revision guard or the
   same-revision replay/conflict distinction. Run requirement-based tests, record whether the mutant
   is killed, then fully restore correct code. Never submit the deliberate bug.
3. Assess concurrency-scheduling and shared-database/time flaky risks with repeated real runs, no
   fixed sleep ordering, actual pass/fail counts, mitigations and limits.
4. Run an executable HTTP harness with 150 tasks and a start barrier against one Draft with shuffled
   revisions. Record per-request latency, throughput, p50/p95/max, 2xx/409/5xx/timeouts, and prove the
   final revision/payload is the maximum. Mandatory target: p95 <= 4.0 seconds.
5. Record structural coverage if available or an honest risk-coverage substitute; complete AC/risk
   traceability, AI usage log, README commands, raw load artifacts, focused/full test evidence, and
   commit/push valid results.

Do not run an additional fabricated AI patch review, merge, or write a PDF. Report new HEAD,
mutation result, flaky repeats, load metrics, coverage evidence, rerun commands and limitations.

This file preserves the task content in compact form. The complete original prompt remains in the
conversation record; no requirement above was intentionally omitted from execution.
