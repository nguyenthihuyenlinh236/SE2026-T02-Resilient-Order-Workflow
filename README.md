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
- Git để tải và cập nhật repository

Các lệnh dưới đây dùng PowerShell, có thể chạy trong terminal của VS Code. Mở Docker Desktop và đợi Docker Engine sẵn sàng trước khi chạy hạ tầng.

Kiểm tra công cụ:

```powershell
java -version
javac -version
mvn -version
docker compose version
docker info
```

`javac` cần hoạt động; chỉ cài Java runtime là chưa đủ. Kiểm tra Java mà Maven sử dụng trong kết quả `mvn -version`.

## Lấy mã nguồn và mở đúng nhánh

Nếu chưa có repository trên máy:

```powershell
git clone --branch feature/setup-01-skeleton https://github.com/nguyenthihuyenlinh236/SE2026-T02-Resilient-Order-Workflow.git
cd SE2026-T02-Resilient-Order-Workflow
```

Nếu đã có repository, mở terminal tại thư mục đó:

```powershell
git status
git fetch origin
git switch feature/setup-01-skeleton
git pull --ff-only origin feature/setup-01-skeleton
```

Nếu có thay đổi chưa commit hoặc Git báo không thể chuyển/cập nhật nhánh, hãy lưu và xử lý các thay đổi trước; không dùng reset/clean để xóa chúng. Trong VS Code, chọn **File → Open Folder** để mở thư mục gốc có `pom.xml` và `docker-compose.yml`.

## Chạy và kiểm tra

Tại thư mục gốc repository, khởi động PostgreSQL và RabbitMQ:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose up -d --wait
docker compose ps
```

Có thể bỏ qua bước tạo `.env` nếu dùng giá trị mặc định. Lệnh trên giữ nguyên `.env` đã có. Compose tự đọc `.env`; các giá trị trong `.env.example` chỉ dành cho phát triển cục bộ, không dùng trong production. `docker compose ps` cần hiển thị cả hai container ở trạng thái `healthy`.

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

Giữ ba terminal mở và đợi mỗi service báo `Started ...Application`. Mở terminal thứ tư để chạy các lệnh kiểm tra bên dưới. Frontend hiện chỉ là placeholder; chưa có giao diện, API đặt hàng hoặc bước chạy `npm`.

Khi cả ba service và hạ tầng đang chạy, kiểm tra endpoint:

```powershell
Invoke-RestMethod http://localhost:8081/actuator/health
Invoke-RestMethod http://localhost:8082/actuator/health
Invoke-RestMethod http://localhost:8083/actuator/health
```

Cả ba endpoint cần trả về `status: UP`. Health indicator của PostgreSQL và RabbitMQ được bật khi chạy service, nên trạng thái `UP` xác nhận các kết nối đó đang khỏe. Smoke test tự động tắt hai indicator này để kiểm tra HTTP độc lập với hạ tầng; bước kiểm tra runtime phía trên xác minh kết nối thực tế.

## Dừng ứng dụng

Nhấn `Ctrl+C` trong từng terminal chạy service để dừng ba ứng dụng Spring Boot. Sau đó, tại thư mục gốc repository, dừng hạ tầng và giữ nguyên container/volume để dùng tiếp:

```powershell
docker compose stop
```

Lần chạy sau dùng lại `docker compose up -d --wait` rồi chạy ba service như trên. Nếu muốn gỡ container và network của Compose, vẫn giữ dữ liệu trong named volume:

```powershell
docker compose down
```

Lưu ý: script tạo database của PostgreSQL chỉ chạy khi volume dữ liệu được khởi tạo lần đầu. Nếu dùng volume cũ chưa có ba database, hãy kiểm tra dữ liệu local trước khi quyết định tạo lại volume; lệnh `docker compose down -v` sẽ xóa dữ liệu trong volume.

## Cấu hình

Compose đọc các biến trong `.env`. Khi chạy service bằng Maven, đặt biến kết nối trong môi trường của terminal nếu cần ghi đè giá trị mặc định. Danh sách biến và cổng nằm trong `.env.example`.

## Xử lý lỗi thường gặp

### Không tìm thấy `mvn`, `java` hoặc `javac`

Cài JDK 17 và Maven 3.9+, cấu hình `JAVA_HOME` trỏ tới thư mục JDK và thêm thư mục `bin` của JDK/Maven vào `PATH`. Đóng rồi mở lại terminal/VS Code và chạy lại các lệnh kiểm tra công cụ.

### Docker chưa chạy hoặc container không healthy

Mở Docker Desktop, kiểm tra `docker info`, rồi xem trạng thái và log:

```powershell
docker compose ps -a
docker compose logs --tail 100 postgres rabbitmq
```

### Cổng đang được sử dụng

Kiểm tra các cổng trên Windows:

```powershell
Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in 5432,5672,15672,8081,8082,8083 } | Select-Object LocalAddress,LocalPort,OwningProcess
```

Xác định ứng dụng đang chiếm cổng trước khi dừng nó. Nếu đổi cổng PostgreSQL/RabbitMQ trong `.env`, đặt cùng biến `POSTGRES_PORT`/`RABBITMQ_PORT` trong **cả ba terminal service** trước khi chạy Maven. Ví dụ đổi PostgreSQL sang `5433`: đặt `POSTGRES_PORT=5433` trong `.env`, chạy lại `docker compose up -d --wait`, rồi chạy `$env:POSTGRES_PORT = "5433"` trong mỗi terminal service. Với cổng HTTP, dùng `ORDER_SERVICE_PORT`, `INVENTORY_SERVICE_PORT`, `PAYMENT_SERVICE_PORT` và sửa URL kiểm tra tương ứng.

### PostgreSQL báo sai mật khẩu hoặc thiếu database

Kiểm tra service đang kết nối đúng PostgreSQL của Compose, thay vì một PostgreSQL khác trên máy. Đối chiếu các biến `POSTGRES_*` trong terminal service với cấu hình `.env`. Đổi `POSTGRES_USER`/`POSTGRES_PASSWORD` trong `.env` không cập nhật tài khoản đã tồn tại trong volume; cần dùng thông tin của database hiện có hoặc cập nhật tài khoản bằng quyền quản trị phù hợp. Không xóa volume để thử sửa lỗi khi chưa sao lưu dữ liệu.

Nếu log báo thiếu `order_db`, `inventory_db` hoặc `payment_db`, script khởi tạo có thể chưa chạy vì volume đã tồn tại. Kiểm tra danh sách database trước (thay `workflow` nếu dùng tài khoản khác):

```powershell
docker compose exec postgres psql -U workflow -d postgres -c "\l"
```

Chỉ tạo database còn thiếu sau khi kiểm tra. Nếu dùng Linux và log báo `Permission denied` khi đọc `01-databases.sql`, chạy `chmod a+r infra/postgres/init-databases.sql` trên máy host. Nếu lần khởi tạo đã dừng giữa chừng, sửa quyền không tự chạy lại script trên volume cũ; cần kiểm tra và tạo các database còn thiếu.

### Health endpoint không trả `UP`

Kiểm tra container và log bằng các lệnh trên, sau đó xem terminal của service bị lỗi. `Connection refused` thường cho thấy service/hạ tầng chưa chạy hoặc sai cổng. Nếu Maven kết thúc bằng `BUILD FAILURE`, tìm `APPLICATION FAILED TO START` và `Caused by:` phía trên để xác định nguyên nhân; dòng `exit code: 1` chỉ là thông báo kết thúc.
