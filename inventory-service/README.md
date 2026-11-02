# Inventory Service – Reserve (issue #28)

Service sử dụng Java 17, Maven, Spring Boot, JDBC, PostgreSQL và Flyway. Phạm vi hiện tại: truy vấn tồn kho, Reserve nhiều sản phẩm trong một transaction và tra cứu Reservation. Release, Cancel, RabbitMQ Consumer và cơ chế idempotency hoàn chỉnh thuộc issue #29/các giai đoạn sau.

## 1. Khởi động

Chạy tại thư mục gốc repository. Mở Docker Desktop, bảo đảm `docker` và `mvn` có trên PATH.

```powershell
docker compose up -d --wait postgres rabbitmq
docker compose ps
mvn -pl inventory-service spring-boot:run "-Dspring-boot.run.profiles=demo"
```

Đợi `Started InventoryServiceApplication`, giữ terminal mở. Terminal thứ hai dùng để gửi request. Ctrl+C dừng server; `BUILD SUCCESS` sau khi dừng không có nghĩa server còn phục vụ request.

Nếu cổng PostgreSQL 5432 trên máy đã được dùng, đặt cổng Docker trước khi khởi động:

```powershell
$env:POSTGRES_PORT = "5433"
docker compose up -d --wait postgres
docker compose port postgres 5432
```

Trong terminal chạy Maven cũng đặt `POSTGRES_PORT=5433`. Kết nối mặc định là host `localhost`, user `workflow`, password `workflow_local`, database `inventory_db`; đặt `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_USER`, `POSTGRES_PASSWORD` trong terminal nếu cần thay đổi. Spring Boot chạy bằng Maven không tự đọc `.env`. Không commit mật khẩu; các thông tin mặc định chỉ dành cho môi trường local.

Nếu chỉ chạy PostgreSQL, thêm `"-Dspring-boot.run.arguments=--management.health.rabbit.enabled=false"` vào lệnh Maven để health không kiểm tra RabbitMQ. Reserve hiện không gửi message.

Flyway tự chạy migration khi khởi động. Profile `demo` nạp hai sản phẩm UUID kết thúc `0001` (10 sản phẩm) và `0002` (hết hàng). Seed không ghi đè tồn kho đã tồn tại. Khởi động lại không reset các Reservation cũ.

## 2. API

| Method | Path | Kết quả |
|---|---|---|
| POST | `/api/v1/inventory/reservations` | RESERVED hoặc REJECTED; HTTP 200 cho kết quả nghiệp vụ xác định |
| GET | `/api/v1/inventory/products/{productId}` | Tổng tồn, lượng giữ, cam kết khác và tồn khả dụng |
| GET | `/api/v1/inventory/stock?storeId={storeId}&sku={sku}` | Tồn kho SKU trong đúng cửa hàng |
| GET | `/api/v1/inventory/reservations/{reservationId}` | Reservation đã lưu |
| GET | `/api/v1/inventory/reservations?orderId={orderId}` | Tra cứu theo Order |
| GET | `/api/v1/inventory/reservations?reserveKey={key}` | Tra cứu theo khóa Reserve |

Tra cứu yêu cầu đúng một selector: `orderId`, `reserveKey` hoặc alias `idempotencyKey`. Request Reserve bắt buộc header `Idempotency-Key: reserve:{orderId}:v1`. Body có `orderId`, `reservationId` UUID, `storeId` không trắng tối đa 100 ký tự và `items` từ 1–20 dòng. Mỗi dòng có `sku` không trắng tối đa 128 ký tự và `quantity` nguyên dương. SKU trùng được gộp; từ chối nếu tổng lượng tràn integer hoặc quantity là số thập phân.

Order Service sinh và giữ nguyên reservationId qua các lần retry. Inventory giữ hàng theo `(storeId, sku)` và lưu ID bên gọi cung cấp theo payload ReserveInventory trong draft contract. `productId` chỉ là định danh tồn kho nội bộ. Hợp đồng chi tiết: [Inventory API Contract](../docs/architecture/14-inventory-api-contract.md). Chưa triển khai RabbitMQ envelope/consumer.

Lỗi đầu vào trả 400 INVALID_REQUEST; không tìm thấy resource trả 404; cùng Order/key khác nội dung trả 409 IDEMPOTENCY_KEY_CONFLICT. Thiếu tồn kho hoặc sản phẩm không tồn tại trả REJECTED với INSUFFICIENT_STOCK/PRODUCT_NOT_FOUND và lưu kết quả để tra cứu. GET không tìm thấy Reservation không phải bằng chứng một Reserve đang bay đã bị hủy.

## 3. Kiểm tra thủ công

Trong terminal thứ hai:

```powershell
$productId = "00000000-0000-4000-8000-000000000001"
Invoke-RestMethod "http://localhost:8082/api/v1/inventory/products/$productId"

$orderId = [guid]::NewGuid().ToString()
$reservationId = [guid]::NewGuid().ToString()
$headers = @{ "Idempotency-Key" = "reserve:${orderId}:v1" }
$body = @{
    orderId = $orderId
    reservationId = $reservationId
    storeId = "DEMO-STORE"
    items = @(@{ sku = "DEMO-ITEM"; quantity = 2 })
} | ConvertTo-Json -Depth 5

$reservation = Invoke-RestMethod -Method Post `
    -Uri "http://localhost:8082/api/v1/inventory/reservations" `
    -Headers $headers -ContentType "application/json" -Body $body
$reservation

Invoke-RestMethod "http://localhost:8082/api/v1/inventory/products/$productId"
Invoke-RestMethod "http://localhost:8082/api/v1/inventory/reservations/$($reservation.reservationId)"
```

Nếu trước test còn 10, sau Reserve còn 8 và reservedQuantity=2. Gửi lại POST với nguyên body/header phải trả cùng ID và không giảm tồn lần nữa. Khóa bản ghi Order và UNIQUE bảo vệ Reservation trùng; replay sau Release và các tác vụ idempotency đầy đủ được hoàn thiện ở #29.

| Kịch bản | Kỳ vọng |
|---|---|
| Cùng key đổi quantity | 409, tồn không đổi |
| Quantity 0/âm hoặc thiếu UUID | 400, không có tác dụng tồn kho |
| Order mới, yêu cầu lớn hơn tồn | REJECTED/INSUFFICIENT_STOCK |
| Order mới, SKU không tồn tại trong store | REJECTED/PRODUCT_NOT_FOUND |
| Cùng key đổi reservationId hoặc storeId | 409, không giữ thêm tồn |
| Dùng reservationId của Order khác | 409 RESERVATION_ID_CONFLICT, rollback |
| Hai Order cùng yêu cầu 5 khi còn 8 | Một RESERVED, một REJECTED, còn 3 |
| Đơn nhiều sản phẩm có một dòng thiếu tồn | Không giữ một phần đơn |

Các lần test dùng Order ID mới trừ trường hợp cố tình kiểm tra replay/conflict. Không chạy lại kịch bản cạnh tranh với giả định tồn ban đầu chưa được kiểm tra.

## 4. Test tự động

Giữ Docker Desktop chạy; không cần server Inventory hoặc database demo để chạy test:

```powershell
mvn -pl inventory-service test
```

Các lớp test:

- `InventoryServiceApplicationTests`: HTTP health smoke test độc lập DB/RabbitMQ, không chứng minh nghiệp vụ Reserve.
- `InventoryServiceTests`: unit test gộp/sắp SKU, dữ liệu sai, tràn lượng, command/key không hợp lệ và validation trước khi truy cập Repository.
- `InventoryReserveIntegrationTests`: HTTP thật + PostgreSQL riêng; Reserve theo caller ID, store isolation, rejection, replay/conflict, lookup theo ID/Order/key, validation, 12 request tranh tồn và 12 request trùng đồng thời. Kiểm tra migration V001 → V002 giữ dữ liệu cũ.
- `InventoryRollbackTests`: PostgreSQL 16 riêng bằng Testcontainers, Flyway chạy migration thật. Constraint chỉ trong DB test gây lỗi ở dòng Reservation thứ hai sau khi đã cập nhật tồn và ghi header/dòng đầu. Test xác minh toàn bộ tồn kho, Reservation, items, guard rollback và retry cùng Order/key thành công.

Chạy riêng rollback:

```powershell
mvn -pl inventory-service "-Dtest=InventoryRollbackTests" test
```

Test không bọc transaction ngoài: kiểm tra transaction thật của service sau khi lỗi. Testcontainers chọn cổng ngẫu nhiên và dọn container khi kết thúc, không reset database demo. Lần đầu cần tải image/dependency. Kỳ vọng toàn bộ suite: 0 failure/error, không skipped; số test bao gồm các lượt parameterized.

Chạy riêng unit test không cần Docker:

```powershell
mvn -pl inventory-service "-Dtest=InventoryServiceTests" test
```

V002 nâng cấp database demo đã có, không sửa V001 hoặc xóa volume. Giữ nguyên tồn và Reservation cũ; payload mới bắt buộc SKU/store/caller ID. Request mẫu productId trước đây không còn hợp lệ. Dữ liệu V001 vẫn query được; không replay hash legacy bằng payload contract mới. Các Order test mới dùng ID mới và lượng tồn hiện tại, không mặc định tồn quay về 10.

Nếu Docker CLI chạy nhưng Testcontainers không nhận Docker trên Windows với context desktop-linux, đặt trong terminal test:

```powershell
$env:DOCKER_HOST = "npipe:////./pipe/dockerDesktopLinuxEngine"
```

`src/test/resources/docker-java.properties` chọn Docker API 1.44 để Testcontainers 1.21.3 làm việc với Docker mới; Docker Engine chạy test phải hỗ trợ API này. Nếu terminal không nhận Maven/Docker, thêm thư mục bin của bản cài trên máy vào PATH trước khi chạy.

## 5. Giao dịch và giới hạn

Reserve khóa guard theo Order, kiểm tra kết quả cũ, khóa Inventory theo thứ tự SKU cố định trong cửa hàng và kiểm tra tất cả dòng trước khi phân bổ. Cập nhật reservedQuantity có điều kiện và lưu Reservation/items trong một transaction. CHECK ở DB ngăn tồn khả dụng âm. Lỗi DB rollback toàn bộ, không được trả REJECTED giả cho lỗi hạ tầng.

Chưa có API sửa tồn kho, xác thực API nội bộ, Release/Cancel hoặc cơ chế đối soát tự động. Các test thủ công không thay thế bộ kiểm thử tải và phục hồi Saga ở các giai đoạn sau.
