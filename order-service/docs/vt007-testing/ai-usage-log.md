# AI usage log

| Prompt/activity | AI output used | Human review decision | Repository evidence |
|---|---|---|---|
| Prompt 02 specification and RED/GREEN plan | Separate Draft DTO/API, monotonic revision UPSERT, decision table | Accepted after rejecting 2-second mandatory target and CreateOrder constraints | `prompt-02.md`, `plan-review.md`, commits `aba74dc`, `d012471`, `dd29881` |
| Post-GREEN null-items review | Identified `null -> []` as fidelity loss | Accepted; required a RED regression before minimal fix | test `3b5a719`, fix `37a182c`, `review-decisions.md` |
| Prompt 03 mutation design | Remove same-revision payload-hash comparison in isolated worktree | Accepted because it directly targets AC-04 and never enters submission branch | `prompt-03.md`, `mutation-evidence.md` |
| Prompt 03 flaky assessment | Repeat real concurrency test and inspect DB/time isolation | Accepted with explicit statistical limitation; no absolute “non-flaky” claim | `flaky-assessment.md` |
| Prompt 03 load harness | 150 threads, barrier, shuffled revisions, status/latency capture and final GET | Accepted; correctness 20-request test explicitly rejected as performance evidence | `OrderDraftLoadHarness`, raw CSV/JSON, `load-test-report.md` |
| Coverage | Report missing structural tool and map risks to tests | Accepted; no fabricated line/branch percentage | `coverage.md`, `traceability.md` |

The separate ChatGPT patch review mentioned by the student was not recreated or paraphrased by this
run because its original artifact was not supplied here. This log records only AI assistance used in
the repository work above; prompts, generated proposals and acceptance/rejection decisions remain
traceable to files and commits.
