# VT-007 Requirement-derived Test Cases

| ID | risk/AC | level | input/precondition | expected | priority |
|---|---|---|---|---|---|
| DT-01 | AC-01 first save | integration-API | Không có draft; PUT revision 1 với payload partial | 200 CREATED; GET trả revision 1 và nguyên snapshot | P0 |
| DT-02 | AC-02 newer wins | integration-API | Draft revision 1; PUT revision 2 | 200 UPDATED; GET trả payload revision 2 | P0 |
| DT-03 | AC-02 revision gap | integration-API | Draft revision 2; PUT revision 9 | 200 UPDATED; revision 9 được lưu | P1 |
| DT-04 | AC-03 stale overwrite | integration-API negative | Draft revision 9; PUT revision 8 | 409 STALE_DRAFT_REVISION; DB vẫn revision 9 | P0 |
| DT-05 | AC-04 replay | integration-API | Draft revision 4; gửi lại revision 4 cùng snapshot | 200 REPLAYED; savedAt/hash không đổi | P0 |
| DT-06 | AC-04 conflict | integration-API negative | Draft revision 4; revision 4 payload khác | 409 DRAFT_REVISION_CONFLICT; DB không đổi | P0 |
| DT-07 | Validation | integration-API negative | PUT revision 0 | 400 VALIDATION_ERROR; không tạo row | P1 |
| DT-08 | Partial draft | integration-API | customerId/storeId null, items empty | 200; snapshot partial được giữ nguyên | P0 |
| DT-09 | Snapshot fidelity | unit/integration | Items có thứ tự và SKU trùng | GET giữ đúng thứ tự/dòng, không merge/sort | P0 |
| DT-10 | Isolation | integration-API | Cùng draftId, hai X-Client-Id | Hai record độc lập | P0 |
| DT-11 | Concurrency | integration-API | N revision xáo trộn, cùng draft, start barrier | Revision cuối = max; không 5xx; 409 được chấp nhận | P0 |
| DT-12 | Failure path | integration-API failure-path | Inject DB failure khi write | Trả 5xx; không có partial/changed record | P1 |
| DT-13 | GET missing | integration-API negative | GET draft chưa tồn tại | 404 ORDER_DRAFT_NOT_FOUND | P1 |
| DT-14 | Durability | E2E service | PUT thành công rồi đọc qua request độc lập | GET trả snapshot đã commit | P1 |

