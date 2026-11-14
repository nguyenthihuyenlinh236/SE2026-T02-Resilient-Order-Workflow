# VT-007 Test Strategy

Expected result của mọi test lấy từ `specification.md` và decision table, không lấy từ repository/service/SQL implementation.

## Các tầng test

1. **Unit:** canonical JSON/hash giữ nguyên thứ tự và snapshot; phân loại outcome/error từ stored revision. Dùng mock chỉ cho logic thuần, không dùng để chứng minh concurrency.
2. **Integration/API:** `@SpringBootTest`, `TestRestTemplate`, PostgreSQL Testcontainers thật và Flyway thật. Kiểm tra HTTP contract, dữ liệu DB, replay, stale/conflict, rollback và concurrency.
3. **E2E trong phạm vi service:** gọi HTTP vào Order Service chạy random port và đọc lại bằng GET. Đây là E2E của vertical slice từ HTTP → service → JDBC → PostgreSQL.

Full browser/frontend E2E chưa cần: `frontend/README.md` xác nhận frontend hiện chỉ là placeholder. Vertical slice không triển khai debounce/UI và không có user flow frontend ổn định để tự động hóa.

## RED strategy

Test-only commit chỉ dùng Spring/Testcontainers/source đã tồn tại. Test gọi endpoint dự kiến bằng JSON string/Map và kiểu response tổng quát để compile dù implementation chưa có. RED hợp lệ là test chạy và nhận `404` thay vì contract mong đợi, không phải compile error.

## Concurrency semantics

- `409 STALE_DRAFT_REVISION` và `409 DRAFT_REVISION_CONFLICT` là kết quả nghiệp vụ hợp lệ.
- 5xx, timeout, duplicate DB record hoặc revision cuối không phải max là failure.
- Concurrency test dùng barrier/latch, không dựa vào sleep tùy ý.

