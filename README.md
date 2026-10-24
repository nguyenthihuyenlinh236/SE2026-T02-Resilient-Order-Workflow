# Resilient Order Workflow

Ứng dụng mô phỏng quy trình đặt hàng dùng **Java 17, Spring Boot và PostgreSQL**. Mã nguồn nền triển khai API tạo/tra cứu đơn, Saga xử lý nền, inventory/payment giả lập, idempotency, Outbox và xử lý đơn hết hạn.

## Chạy ứng dụng

Yêu cầu: JDK 17+, Maven 3.9+ và Docker Compose.

```powershell
docker compose up -d
mvn spring-boot:run
```

Chạy kiểm thử:

```powershell
mvn test
```

Mặc định ứng dụng kết nối `jdbc:postgresql://localhost:5432/resilient_orders` với thông tin local trong `application.yml`. Có thể ghi đè bằng `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` và cổng HTTP bằng `PORT`. Flyway tạo schema và dữ liệu demo `DEMO-ITEM` (10.000 đơn vị) khi khởi động lần đầu.

## API

Tạo đơn (API trả `202 Accepted` sau khi đã lưu bền vững; tiến trình Saga chạy nền):

```powershell
curl.exe -X POST http://localhost:8080/api/v1/orders `
  -H "Content-Type: application/json" `
  -H "Idempotency-Key: demo-order-001" `
  -d '{"items":[{"sku":"DEMO-ITEM","quantity":2}]}'
```

Tra cứu trạng thái:

```powershell
curl.exe http://localhost:8080/api/v1/orders/<orderId>
```

Gửi lại POST cùng `Idempotency-Key` và payload trả lại cùng Order; dùng lại khóa với payload khác trả `409 IDEMPOTENCY_KEY_CONFLICT`. API từ chối khóa idempotency thiếu, SKU trùng, danh sách hàng rỗng hoặc số lượng không dương.

## Mô phỏng payment

`PAYMENT_SIMULATED_OUTCOME` nhận `SUCCEEDED` (mặc định), `FAILED` hoặc `UNKNOWN`. Đặt `UNKNOWN` để giữ Saga ở trạng thái đối soát; đổi sang `SUCCEEDED`/`FAILED` và khởi động lại ứng dụng để worker tiếp tục bằng cùng payment record. TTL mặc định 15 phút, có thể chỉnh bằng `ORDER_TTL` (ví dụ `30s`). Các worker có chu kỳ riêng trong `application.yml`.

Các event nghiệp vụ được lưu cùng transaction thay đổi trạng thái Order; publisher nền ghi nhận lượt giao notification mô phỏng và đánh dấu Outbox đã phát. Dịch vụ Inventory, Payment, Notification hiện mô phỏng trong cùng ứng dụng/Database, chưa phải các microservice độc lập.

## Phạm vi của base code

Đã có luồng nền cho giữ hàng, payment mock, xác nhận/thất bại, giải phóng reservation, refund mock và hết hạn; các nhánh cạnh tranh dùng conditional update theo DB clock. Các retry/backoff phân tán, lease worker bền vững, fault-injection API, đối soát payment theo lịch D-04 và tích hợp broker/provider thật vẫn cần được triển khai tiếp trước khi coi đây là hệ thống production.
