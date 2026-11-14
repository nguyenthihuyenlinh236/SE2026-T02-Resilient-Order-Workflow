# AC and risk traceability

| AC/Risk | Test case | Test file | Defining commit | Actual result |
|---|---|---|---|---|
| AC-01 partial create/read | `firstSaveAcceptsPartialDraftAndGetReturnsSnapshot` | `OrderDraftIntegrationTests` | `d012471` | PASS focused GREEN |
| AC-02 newer revision/gap | `higherRevisionAndRevisionGapReplaceSnapshot` | `OrderDraftIntegrationTests` | `d012471` | PASS focused GREEN |
| AC-03 stale protection | `staleRevisionReturnsBusinessConflictWithoutOverwrite` | `OrderDraftIntegrationTests` | `d012471` | PASS focused GREEN |
| AC-04 replay | `sameRevisionSameSnapshotReplaysWithoutChangingSavedAt` | `OrderDraftIntegrationTests` | `d012471` | PASS focused GREEN |
| AC-04 conflict | `sameRevisionDifferentSnapshotIsConflict` | `OrderDraftIntegrationTests` | `d012471` | PASS; killed mutation |
| Snapshot risk: order/duplicates | `snapshotPreservesItemOrderAndDuplicates` | `OrderDraftIntegrationTests` | `d012471` | PASS focused GREEN |
| Snapshot risk: null/empty | `nullItemsRemainNullAndAreNotCanonicalizedToEmptyList` | `OrderDraftIntegrationTests` | `3b5a719` | RED before fix; PASS after `37a182c` |
| Isolation | `sameDraftIdIsIsolatedByDemoClientScope` | `OrderDraftIntegrationTests` | `d012471` | PASS focused GREEN |
| Validation/not found | `invalidRevisionAndMissingDraftReturnContractErrors` | `OrderDraftIntegrationTests` | `d012471` | PASS focused GREEN |
| AC-05 atomic race | `concurrentSavesRetainHighestRevisionWithoutServerErrors` | `OrderDraftIntegrationTests` | `d012471` | PASS 5/5 repeats |
| Transaction rollback | `databaseFailureDoesNotPartiallyReplaceDraft` | `OrderDraftIntegrationTests` | `d012471` | PASS focused GREEN |
| NFR 150/p95 | `runOneHundredFiftyConcurrentDraftSaves` | `OrderDraftLoadHarness` | `3b5a291` | PASS: p95 1544.143 ms, 0 5xx/timeouts |
