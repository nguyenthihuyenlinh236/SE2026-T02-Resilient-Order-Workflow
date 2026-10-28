# Resilient Order Workflow — Microservices Skeleton

Bộ khung ban đầu cho ba Spring Boot microservices độc lập, dùng Java 17, Maven, PostgreSQL và RabbitMQ. Issue SETUP-01 chỉ thiết lập nền tảng chạy và kiểm tra sức khỏe; chưa triển khai nghiệp vụ đặt hàng, Saga hoặc Outbox.

## Thành phần

- `order-service` — cổng mặc định `8081`, database `order_db`.
- `inventory-service` — cổng mặc định `8082`, database `inventory_db`.
- `payment-service` — cổng mặc định `8083`, database `payment_db`.
- PostgreSQL — cổng `5432`; script `infra/postgres/init-databases.sql` tạo ba database.
- RabbitMQ — cổng AMQP `5672`, giao diện quản trị `15672`.
- `docs/` — tài liệu hiện có của nhóm, được giữ nguyên.

## Yêu cầu

- JDK 17
- Maven 3.9+
- Docker Engine/Desktop với Docker Compose v2

## Chạy và kiểm tra

Tại thư mục gốc repository, khởi động PostgreSQL và RabbitMQ:

```powershell
Copy-Item .env.example .env
docker compose up -d --wait
docker compose ps
```

Có thể bỏ qua `Copy-Item` nếu dùng giá trị mặc định. Compose tự đọc `.env`; các giá trị trong `.env.example` chỉ dành cho phát triển cục bộ, không dùng trong production.

Biên dịch và chạy smoke test ứng dụng/HTTP:

```powershell
mvn clean verify
```

Mở ba terminal riêng tại thư mục gốc và chạy từng service:

```powershell
mvn -pl order-service spring-boot:run
```

```powershell
mvn -pl inventory-service spring-boot:run
```

```powershell
mvn -pl payment-service spring-boot:run
```

Spring Boot khi chạy qua Maven đọc biến môi trường của tiến trình, không tự đọc file `.env`. Giá trị mặc định trong cấu hình service khớp với `.env.example`. Nếu đổi thông tin kết nối hoặc cổng, hãy đặt các biến tương ứng trong terminal chạy service.

Khi cả ba service và hạ tầng đang chạy, kiểm tra endpoint:

```powershell
Invoke-RestMethod http://localhost:8081/actuator/health
Invoke-RestMethod http://localhost:8082/actuator/health
Invoke-RestMethod http://localhost:8083/actuator/health
```

Cả ba endpoint cần trả về `status: UP`. Health indicator của PostgreSQL và RabbitMQ được bật khi chạy service, nên trạng thái `UP` xác nhận các kết nối đó đang khỏe. Smoke test tự động tắt hai indicator này để kiểm tra HTTP độc lập với hạ tầng; bước kiểm tra runtime phía trên xác minh kết nối thực tế.

Dừng hạ tầng local:

```powershell
docker compose down
```

Lưu ý: script tạo database của PostgreSQL chỉ chạy khi volume dữ liệu được khởi tạo lần đầu. Nếu dùng volume cũ chưa có ba database, hãy kiểm tra dữ liệu local trước khi quyết định tạo lại volume; lệnh `docker compose down -v` sẽ xóa dữ liệu trong volume.

## Cấu hình

Compose đọc các biến trong `.env`. Khi chạy service bằng Maven, đặt biến kết nối trong môi trường của terminal nếu cần ghi đè giá trị mặc định. Danh sách biến và cổng nằm trong `.env.example`.
