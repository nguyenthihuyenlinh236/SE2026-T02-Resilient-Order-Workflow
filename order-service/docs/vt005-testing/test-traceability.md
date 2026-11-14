# VT-005 — Traceability tại bước RED

Baseline: `ec965d6fad867f9fb9ef756f5650d69731017abb`.
Test source được lưu ở commit RED trên nhánh `feature/vt005-order-search-lananh`.
Tra commit bằng `git log --oneline -- order-service/src/test/java/com/example/orderworkflow/order/OrderSearchIntegrationTests.java`.

| Yêu cầu | Nội dung |
|---|---|
| AC1 | Lọc chính xác customerId/status và kết hợp AND |
| AC2 | Default/boundary phân trang, validation và metadata đúng |
| AC3 | Thứ tự created_at DESC, order_id DESC; dữ liệu cố định không trùng/thiếu giữa trang |
| AC4 | Giữ API POST/GET-by-ID và idempotency; search chỉ đọc |
| AC5 | Lỗi PostgreSQL khác kết quả rỗng; không nuốt exception |
| NFC2 | 120 tác vụ đồng thời, p95 ≤ 3,5 giây; kiểm chứng ở giai đoạn Load Test |

Các tên test dưới đây nằm trong
`order-service/src/test/java/com/example/orderworkflow/order/`.

| Case | AC/risk | Test file và method | Kết quả RED |
|---|---|---|---|
| T01 | AC1: lẫn khách hàng | OrderSearchIntegrationTests.t01FiltersByCustomer | FAIL: GET chưa triển khai |
| T02 | AC1: sai tập trạng thái | OrderSearchIntegrationTests.t02FiltersByStatus | 7 FAIL: GET chưa triển khai |
| T03 | AC1: OR thay AND | OrderSearchIntegrationTests.t03CombinesFilters | FAIL: GET chưa triển khai |
| T04 | AC2: sai default | OrderSearchIntegrationTests.t04UsesDefaultPagination | FAIL: GET chưa triển khai |
| T05 | AC2/AC3: trùng/thiếu trang | OrderSearchIntegrationTests.t05TraversesPagesWithoutMissingOrDuplicateRows | FAIL: GET chưa triển khai |
| T06 | AC3: timestamp bằng nhau | OrderSearchIntegrationTests.t06UsesDescendingIdAsStableTieBreaker | FAIL: GET chưa triển khai |
| T07 | AC2: negative pagination | OrderSearchIntegrationTests.t07RejectsInvalidPagination | 7 FAIL: expected 400, actual 500 |
| T08 | AC1/AC2: negative status | OrderSearchIntegrationTests.t08RejectsInvalidStatus | 4 FAIL: expected 400, actual 500 |
| T09 | AC4: phá POST/idempotency | OrderApiContractTests.t09CreateAndIdempotencyContractRemainsCompatible | PASS |
| T10 | AC4: phá GET-by-ID/error | OrderApiContractTests.t10GetByIdContractRemainsCompatible | PASS |
| T11 | AC5: nuốt lỗi thành trang rỗng | OrderSearchIntegrationTests.t11DatabaseFailureMustNotBecomeSuccessfulEmptyPage | FAIL ở prerequisite tìm kiếm; chưa thực hiện fault injection |
| T12 | AC2/AC5: không khớp | OrderSearchIntegrationTests.t12ReturnsEmptyPageWhenNothingMatches | FAIL: GET chưa triển khai |
| T12b | AC2: trang vượt cuối | OrderSearchIntegrationTests.t12bPageBeyondEndIsEmpty | FAIL: GET chưa triển khai |
| T13 | AC4: truy vấn có side effect | OrderSearchIntegrationTests.t13SearchDoesNotChangeData | FAIL: GET chưa triển khai |
| Load | NFC2 | Chưa triển khai/chạy ở bước RED | Chưa có kết quả |

Bằng chứng: `baseline.log`, `red.log`, `red-metadata.json`. Chưa có GREEN,
mutation, coverage hoặc tải; chưa có PR trong bước này. Không suy ra các kết quả đó
từ baseline 16 test hoặc hai contract test pass.

## GREEN update — 2026-11-14

All T01–T13 cases, including T12b and parameterized invocations, PASS. T11 now executes the database fault injection. Focused suite: 29/29; full Order suite: 45/45. See green-summary.md, green.log and green-full.log. Mutation, coverage and load remain unverified.

## Final evidence update — 2026-11-14

The RED table above is historical. Current verification:

| Requirement | Cases / measurement | Actual result | Evidence |
|---|---|---|---|
| AC1 filters and AND | T01, T02 (7 statuses), T03, T08 | PASS | coverage.log, green-full.log |
| AC2 pagination / invalid input / empty | T04, T05, T07, T08, T12, T12b | PASS | coverage.log |
| AC3 stable order and ties | T05, T06 | PASS on fixed data | coverage.log, repeat-1/2/3.log |
| AC4 old API and read-only | T09, T10, T13 | PASS; original 16 tests also pass | green-full.log, coverage.log |
| AC5 DB errors not empty 200 | T11 | PASS clean; mutant fails expected 500 vs actual 200 | mutation.log, mutation.diff, coverage.log |
| NFC2 search load | 120 tasks, 600 requests | 0 errors; p95 689.191 ms <= 3500 ms | load.log, load-results.json, load-test-results.md |
| Coverage | Full module, 45 tests | line 89.27%, branch 74.49% | coverage.csv/xml, coverage-summary.md |
| Repeatability | 3 runs x 28 tests | 84 pass, no observed intermittent failure | flaky-tests.md, repeat-1/2/3.log |

Provider-side JSON contract tests do not replace consumer-driven contracts from
external clients. Coverage and local burst performance limits are documented in
their own summaries. No mutation remains. Implementation/evidence commits are
listed in Git history; no PR created yet.
