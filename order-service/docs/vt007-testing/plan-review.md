# VT-007 AI Plan Review

## Quyết định của người thực hiện

1. **Bác bỏ A-08:** yêu cầu bắt buộc là 150 tác vụ đồng thời và p95 <= 4.0 giây. 2 giây là stretch goal.
2. **Sửa A-05:** Draft được phép chưa hoàn chỉnh; không tái sử dụng constraint bắt buộc của `CreateOrderRequest`.
3. **Sửa strategy:** có unit, integration-API và service E2E; full frontend E2E chưa cần vì `frontend/README.md` mô tả frontend là placeholder.
4. **RED phải chạy:** test-only commit phải compile và fail vì endpoint/behavior chưa tồn tại, dự kiến HTTP 404.
5. **Concurrency:** 409 là business outcome; chỉ 5xx/timeout/invariant violation là lỗi. Snapshot Draft không được tự ý merge/sort items.

## Evidence repository

- `OrderDtos.CreateOrderRequest` có `@NotBlank customerId/storeId` và `@NotEmpty items`, không phù hợp partial Draft.
- `OrderController` chỉ có POST create và GET Order; chưa có Draft endpoint.
- `OrderRepository` dùng `JdbcTemplate`, phù hợp conditional PostgreSQL upsert.
- `OrderIntegrationTests` dùng HTTP + PostgreSQL Testcontainers thật.
- `frontend/README.md` xác định frontend hiện là placeholder.
- Flyway hiện kết thúc ở V005; Draft phải dùng V006 và không sửa migration cũ.
- `feature/vt005-order-search-lananh` sửa core Order classes; VT-007 dùng class `OrderDraft*` riêng để giảm conflict.

## Tách demo identity và authentication

`X-Client-Id` chỉ dùng làm scope kỹ thuật trong demo, giống convention idempotency hiện tại. Production phải lấy/kiểm tra identity từ authenticated principal hoặc gateway; vertical slice này không tuyên bố header là bằng chứng authentication.

