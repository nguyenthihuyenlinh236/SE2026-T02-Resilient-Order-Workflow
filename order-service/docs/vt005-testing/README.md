# BT3 VT-005 — Order search testing evidence

Sinh viên: Nguyễn Thị Lan Anh — 23001826. Repository APP-08 / SE2026-T02.

## Baseline và phạm vi hiện tại

- Nhánh: `feature/vt005-order-search-lananh`.
- Baseline: `ec965d6fad867f9fb9ef756f5650d69731017abb`.
- Baseline đã chạy ở TASK 1: 16 tests, 0 failures/errors/skipped, BUILD SUCCESS.
- `baseline.log` tổng hợp các Surefire report của lần chạy TASK 1; không phải
  stdout Maven được dựng lại, cũng không phải một lần chạy baseline mới.
- Bước hiện tại: RED. Chưa triển khai production search, chưa chạy GREEN,
  mutation, coverage hoặc load test. Commit RED được lưu riêng để giữ bằng chứng
  trước implementation; xem lịch sử Git của các file test.

## Contract được kiểm thử

API mới: `GET /api/v1/orders`, các bộ lọc `customerId`/`status` tùy chọn và kết
hợp AND; page mặc định 0, size mặc định 20, size 1–100. Thứ tự cố định là
`created_at DESC, order_id DESC`; phân trang theo dữ liệu PostgreSQL.

Response đề xuất có đúng `items`, `page`, `size`, `totalElements`, `totalPages`.
Các metadata là JSON integer; mỗi item giữ năm trường của response Order cũ:
orderId, status, createdAt, expireAt, correlationId. Timestamp là chuỗi parse
được thành Instant. Dữ liệu rỗng trả 200; lỗi query không được biến thành 200.
Tham số phân trang/status sai trả 400 VALIDATION_ERROR theo envelope hiện có.
Các assertion không import DTO production và không lấy schema từ DTO ứng dụng.

Đây là contract test phía provider và characterization cho client cũ được mô
phỏng bằng JSON assertions; chưa có contract từ consumer ngoài repository.

## Chạy lại bước RED

Cần JDK 17, Maven và Docker Desktop. Testcontainers dùng PostgreSQL
`postgres:16-alpine`, Flyway V001 và cổng ngẫu nhiên. Hai lớp test có container
riêng; không dùng database demo và không cần RabbitMQ.

Chạy tại thư mục gốc:

```powershell
mvn -pl order-service "-Dtest=OrderSearchIntegrationTests,OrderApiContractTests" test
```

Kết quả thực tế ngày 2026-11-14:

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| OrderSearchIntegrationTests | 27 | 27 | 0 | 0 |
| OrderApiContractTests | 2 | 0 | 0 | 0 |
| Tổng | 29 | 27 | 0 | 0 |

Exit code 1 / BUILD FAILURE là kết quả RED mong đợi. Biên dịch thành công,
PostgreSQL/container và migration khởi động thành công. Baseline chưa có
collection GET; `HttpRequestMethodNotSupportedException` đang bị handler tổng
quát ánh xạ thành 500. Test tìm kiếm thất bại ở expected 200 hoặc 400, actual 500.
Hai test contract POST và GET theo ID vẫn pass. Parameterized invocations được
tính riêng, nên 13 test-case ID tương ứng 29 test executions, kể cả T12b bổ sung.

`red.log` lưu output Maven thực tế và metadata. Các dòng cấu hình chứa trường
credential đã được thay bằng dấu REDACTED; không chỉnh kết quả test hoặc failure.
`red-metadata.json` ghi hash test source để nhận diện chính xác phiên bản RED.

## Dữ liệu và failure-path

- 9 Order cố định, hai khách hàng, đủ bảy trạng thái.
- Có nhiều Order cùng created_at, với thứ tự insert khác thứ tự yêu cầu.
- T04 bổ sung dữ liệu vượt 20 bản ghi để kiểm tra default size thực sự.
- T09/T10 tạo Order bằng API cũ, đọc JsonNode để bảo vệ shape và idempotency.
- T13 chụp nội dung cả orders, order_items, idempotency_records, product_catalog.

T11 bắt buộc chứng minh tìm kiếm hoạt động trước khi tạm rename bảng orders
trong DB test và kỳ vọng lỗi 500. Bảng được khôi phục trong finally. Ở RED, T11
fail ngay tại bước tìm kiếm thành công, chưa tới phần gây lỗi DB. Vì vậy không
được dùng response 500 của endpoint chưa tồn tại để tuyên bố failure-path đã pass.
Sau GREEN, T11 mới kiểm chứng lỗi PostgreSQL và dùng để thử mutation nuốt exception.

## Giới hạn và bước tiếp theo

Thứ tự ổn định được kiểm tra khi dữ liệu cố định; chưa cam kết snapshot xuyên
nhiều request lúc dữ liệu thay đổi. Đây chưa phải test hiệu năng hoặc kiểm chứng
phân quyền. Mục tiêu tải VT-005 là 120 tác vụ đồng thời, p95 không quá 3,5 giây;
chưa có số đo tải trong giai đoạn RED.

Chỉ thực hiện GREEN sau khi người thực hiện xác nhận kết quả RED. Giữ nguyên
test expected values; bổ sung implementation nhỏ ở Controller/Service/Repository/
DTO và xử lý lỗi query parameter. Không đổi nghiệp vụ Inventory/Payment/Saga.
