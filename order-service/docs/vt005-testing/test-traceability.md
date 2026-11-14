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
