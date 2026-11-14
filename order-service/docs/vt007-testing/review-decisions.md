# Post-GREEN review decisions

## Snapshot fidelity: `items: null`

Decision: the conversion from `items: null` to `items: []` violated the accepted specification.
The specification allowed both null and empty Draft fields and prohibited canonicalization that
loses the submitted snapshot. It also caused a same-revision null request and empty-list request to
share one hash.

Evidence sequence:

1. Regression test commit `3b5a719` added
   `nullItemsRemainNullAndAreNotCanonicalizedToEmptyList` before the production fix.
2. RED run compiled and executed one test: 1 run, 1 assertion failure; response contained
   `items=[]` while the requirement expected `items=null`.
3. Fix commit `37a182c` preserves JSON null and reads it back as Java null.
4. The same focused test then passed: 1 run, 0 failures/errors.

No item sorting, merging or duplicate removal was introduced.

## Correctness concurrency versus load evidence

`concurrentSavesRetainHighestRevisionWithoutServerErrors` (20 requests) remains a correctness/race
test for the revision invariant. It is not used as evidence for 150-task capacity or p95. Performance
evidence comes only from the separately executable `OrderDraftLoadHarness` and its raw results.
