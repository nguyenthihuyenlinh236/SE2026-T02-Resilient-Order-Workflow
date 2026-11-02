# Inventory Service – Reserve, Release và Idempotency (#28, #29)

Service sử dụng Java 17, Maven, Spring Boot, JDBC, PostgreSQL và Flyway. #28 triển khai Reserve và tra cứu; #29 bổ sung Release, Cancel theo Order và snapshot kết quả idempotency. Chưa triển khai RabbitMQ Consumer, Saga Orchestrator hoặc Reconciliation Worker.

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

Đang đồng bộ từng phần với PR #39, **chưa tương thích toàn bộ contract**.
Chi tiết schema, migration và các điểm còn chờ chốt nằm trong
[Inventory API Contract, mục 9–11](../docs/architecture/14-inventory-api-contract.md#9-api-nội-bộ-đang-đồng-bộ-với-pr-39).

### API nội bộ

Mọi request `/internal/v1/**` bắt buộc header `X-Correlation-Id` không trắng,
tối đa 128 ký tự ASCII hiển thị được. `X-Command-Id` tùy chọn; nếu gửi phải hợp lệ.
Response thành công và các lỗi được Inventory xử lý trả correlation header.
Thiếu/sai correlation trả 400; response lỗi dùng UUID thay thế để truy vết.

| Method | Path | Request và response hiện tại |
|---|---|---|
| POST | `/internal/v1/reservations` | Request còn dùng caller reservationId/storeId/sku; response đã ánh xạ sang reservedAt/reasonCode theo #39 |
| GET | `/internal/v1/reservations/by-order/{orderId}` | Trạng thái hiện tại, idempotencyKey và reasonCode; REJECTED có reservationId=null |
| POST | `/internal/v1/reservations/{reservationId}/release` | Body commandId/orderId/correlationId; response reservationId/orderId/status/releasedAt |
| POST | `/internal/v1/orders/{orderId}/cancel` | Phần mở rộng #29; chưa có contract tương ứng được chốt trong #39 |
| GET | `/internal/v1/operations?idempotencyKey={key}` | Snapshot đã commit; phần mở rộng #29 |

Các API tra cứu tồn kho và Reservation khác cũng có alias `/internal/v1`, hiện
trả schema API cũ. Chỉ các endpoint nêu trong #39 được xem xét đồng bộ contract.

Lỗi validation/xung đột/không tìm thấy do Inventory xử lý dùng envelope:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Check required fields, identifiers and positive integer quantities",
    "details": [],
    "correlationId": "corr-demo-001",
    "retryable": false
  }
}
```

Chưa chuẩn hóa mọi lỗi 5xx, lỗi routing hoặc lỗi từ tầng ngoài service theo
envelope này. Xác thực/phân quyền API nội bộ chưa được triển khai.

### API tương thích #28–#29

| Method | Path | Kết quả |
|---|---|---|
| POST | `/api/v1/inventory/reservations` | RESERVED hoặc REJECTED; HTTP 200 cho kết quả nghiệp vụ xác định |
| POST | `/api/v1/inventory/reservations/{reservationId}/release` | Body orderId/reservationId/reason; giải phóng đúng Reservation một lần |
| POST | `/api/v1/inventory/orders/{orderId}/cancel` | Ghi dấu hủy và giải phóng nếu đã giữ |
| GET | `/api/v1/inventory/operations?idempotencyKey={key}` | Snapshot kết quả lệnh đã commit |
| GET | `/api/v1/inventory/products/{productId}` | Tồn kho theo UUID sản phẩm nội bộ |
| GET | `/api/v1/inventory/stock?storeId={storeId}&sku={sku}` | Tồn SKU trong đúng cửa hàng |
| GET | `/api/v1/inventory/reservations/{reservationId}` | Reservation hiện tại |
| GET | `/api/v1/inventory/reservations?orderId={orderId}` | Tra cứu theo Order |
| GET | `/api/v1/inventory/reservations?reserveKey={key}` | Tra cứu theo khóa Reserve; idempotencyKey là alias |

Order/Reservation ID là Identifier 1–128 ký tự: chữ cái/số ASCII ở đầu, sau đó
là chữ cái/số, `_` hoặc `-`; UUID dạng chuỗi vẫn hợp lệ. Request Reserve hiện yêu
cầu caller reservationId, storeId không trắng tối đa 100 ký tự, 1–100 item với
sku không trắng tối đa 128 ký tự và quantity là JSON integer dương trong phạm vi
Java Integer. Không nhận chuỗi số, float, boolean hoặc tràn số; SKU trùng vẫn
được gộp trong schema hiện tại. Không tự chọn kho mặc định hoặc gom tồn nhiều kho.

API cũ không bắt buộc tracing header và trả lỗi `{code,message}`. Thiếu tồn trả
REJECTED/INSUFFICIENT_STOCK, SKU không tồn tại trả REJECTED/PRODUCT_NOT_FOUND.
GET 404 không phải bằng chứng một Reserve đang bay đã bị hủy.

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

Nếu trước test còn 10, sau Reserve còn 8 và reservedQuantity=2. Gửi lại POST với nguyên body/header phải trả cùng ID và không giảm tồn lần nữa. Sau Release, replay Reserve vẫn trả snapshot RESERVED ban đầu, nhưng GET Reservation trả RELEASED và hàng không bị giữ lại.

### Release và Cancel

Sau khi Reserve thành công, chạy ở terminal thứ hai với các biến từ ví dụ trên:

```powershell
$releaseKey = "inventory:release:${orderId}:${reservationId}:v1"
$releaseBody = @{
    orderId = $orderId
    reservationId = $reservationId
    reason = "ORDER_EXPIRED"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
    -Uri "http://localhost:8082/api/v1/inventory/reservations/$reservationId/release" `
    -Headers @{ "Idempotency-Key" = $releaseKey } `
    -ContentType "application/json" -Body $releaseBody

Invoke-RestMethod "http://localhost:8082/api/v1/inventory/operations?idempotencyKey=$releaseKey"
Invoke-RestMethod "http://localhost:8082/api/v1/inventory/reservations/$reservationId"
Invoke-RestMethod "http://localhost:8082/api/v1/inventory/products/$productId"
```

Kỳ vọng RELEASED, tồn khả dụng tăng đúng 2 so với sau Reserve, onHandQuantity không đổi. Gửi lại Release nguyên key/body không trả hàng thêm. Cùng key đổi reason trả 409. Không tồn tại trả 404; sai Order hoặc Reservation REJECTED trả 409 và không ghi dấu hủy.

Cancel Order mới trước khi Reserve:

```powershell
$cancelOrderId = [guid]::NewGuid().ToString()
$cancelKey = "inventory:cancel:${cancelOrderId}:v1"
$cancelBody = @{ orderId = $cancelOrderId; reason = "ORDER_EXPIRED" } | ConvertTo-Json
Invoke-RestMethod -Method Post `
    -Uri "http://localhost:8082/api/v1/inventory/orders/$cancelOrderId/cancel" `
    -Headers @{ "Idempotency-Key" = $cancelKey } `
    -ContentType "application/json" -Body $cancelBody
```

Kỳ vọng CANCELLATION_RECORDED, reservationStatus=NOT_CREATED, reservationId=NULL. Reserve sau đó cho cùng Order bị REJECTED/ORDER_CANCELLED, tồn không đổi. Cancel đã có Reservation RESERVED giải phóng trong cùng transaction; Cancel lặp trả response ban đầu. Không gọi Cancel chỉ dựa vào timeout hoặc payment UNKNOWN.

| Kịch bản | Kỳ vọng |
|---|---|
| Cùng key đổi quantity | 409, tồn không đổi |
| Quantity 0/âm hoặc thiếu/sai Identifier | 400, không có tác dụng tồn kho |
| Order mới, yêu cầu lớn hơn tồn | REJECTED/INSUFFICIENT_STOCK |
| Order mới, SKU không tồn tại trong store | REJECTED/PRODUCT_NOT_FOUND |
| Cùng key đổi reservationId hoặc storeId | 409, không giữ thêm tồn |
| Dùng reservationId của Order khác | 409 RESERVATION_ID_CONFLICT, rollback |
| Hai Order cùng yêu cầu 5 khi còn 8 | Một RESERVED, một REJECTED, còn 3 |
| Đơn nhiều sản phẩm có một dòng thiếu tồn | Không giữ một phần đơn |

Các lần test dùng Order ID mới trừ trường hợp cố tình kiểm tra replay/conflict. Không chạy lại kịch bản cạnh tranh với giả định tồn ban đầu chưa được kiểm tra.

### Kiểm tra API nội bộ đang hỗ trợ

Ví dụ này dùng request Reserve hiện tại (caller ID/store/SKU), không phải request
Reserve nguyên bản của #39. Chạy với Order mới, còn đủ ít nhất 2 DEMO-ITEM;
giữ biến trong cùng terminal. Không dùng lại key đã Release bằng API cũ.

```powershell
$internalOrder = [guid]::NewGuid().ToString()
$internalReservation = [guid]::NewGuid().ToString()
$trace = "corr-demo-$internalOrder"
$internalReserveKey = "reserve:${internalOrder}:v1"
$internalReserveBody = @{
    orderId = $internalOrder
    reservationId = $internalReservation
    storeId = "DEMO-STORE"
    items = @(@{ sku = "DEMO-ITEM"; quantity = 2 })
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Method Post `
    -Uri "http://localhost:8082/internal/v1/reservations" `
    -Headers @{ "Idempotency-Key" = $internalReserveKey; "X-Correlation-Id" = $trace } `
    -ContentType "application/json" -Body $internalReserveBody

$internalReleaseKey = "inventory:release:${internalOrder}:${internalReservation}:v1"
$internalReleaseBody = @{
    commandId = "cmd-release-$internalOrder"
    orderId = $internalOrder
    correlationId = $trace
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
    -Uri "http://localhost:8082/internal/v1/reservations/$internalReservation/release" `
    -Headers @{ "Idempotency-Key" = $internalReleaseKey; "X-Correlation-Id" = $trace } `
    -ContentType "application/json" -Body $internalReleaseBody

Invoke-RestMethod `
    -Uri "http://localhost:8082/internal/v1/reservations/by-order/$internalOrder" `
    -Headers @{ "X-Correlation-Id" = $trace }
```

Reserve thành công trả RESERVED/reservedAt; Release trả RELEASED/releasedAt;
GET cuối trả RELEASED cùng reserve idempotencyKey. Gửi lại POST Reserve nguyên
body/key vẫn trả RESERVED ban đầu và không giữ hàng lại. Nếu Reserve REJECTED,
không tiếp tục Release: xem reasonCode và kiểm tra tồn trước kịch bản mới.

## 4. Test tự động

Giữ Docker Desktop chạy; không cần server Inventory hoặc database demo để chạy test:

```powershell
mvn -pl inventory-service test
```

Các lớp test:

- `InventoryServiceApplicationTests`: HTTP health smoke test độc lập DB/RabbitMQ, không chứng minh nghiệp vụ Reserve.
- `InventoryServiceTests`: unit test gộp/sắp SKU, dữ liệu sai, tràn lượng, command/key không hợp lệ và validation trước khi truy cập Repository.
- `InventoryReserveIntegrationTests`: HTTP thật + PostgreSQL riêng; Reserve theo caller ID, store isolation, rejection, replay/conflict, lookup theo ID/Order/key, validation, 12 request tranh tồn và 12 request trùng đồng thời. Kiểm tra nâng cấp từ V001 đến migration hiện tại giữ dữ liệu cũ.
- `InventoryRollbackTests`: PostgreSQL 16 riêng bằng Testcontainers, Flyway chạy migration thật. Constraint chỉ trong DB test gây lỗi ở dòng Reservation thứ hai sau khi đã cập nhật tồn và ghi header/dòng đầu. Test xác minh toàn bộ tồn kho, Reservation, items, guard rollback và retry cùng Order/key thành công.
- `InventoryLifecycleServiceTests`: validation Release/Cancel trước khi truy cập DB.
- `InventoryLifecycleIntegrationTests`: Release/Cancel, snapshot Reserve sau Release, sai Order, thiếu Reservation, conflict, request đồng thời, rollback khi lưu snapshot, đọc kết quả bằng repository mới nâng cấp V002–V005, response nội bộ, tracing, HTTP Cancel/Release/Reserve đến muộn.

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

V003 thêm inventory_operations, dấu hủy và released_at. Backfill response Reserve từ resource RESERVED/REJECTED có thể xác minh, giữ hash cũ. Không dựng response RESERVED giả cho dữ liệu đã RELEASED từ bên ngoài. Replay thiếu lịch sử gốc trả 409 RESERVE_HISTORY_UNAVAILABLE; Release legacy thiếu timestamp trả 409 RELEASE_HISTORY_UNAVAILABLE. Trường hợp này phải điều tra dữ liệu thay vì tạo tác dụng mới.

V004 mở rộng cột key lên 512 ký tự. V005 đổi Order/Reservation ID và các cột FK
sang VARCHAR(128); UUID dạng chuỗi vẫn giữ nguyên, hash và snapshot cũ không bị
ghi lại. Không sửa migration đã chạy hoặc xóa volume để nâng cấp.

Kết quả gần nhất được cung cấp ngày 2026-11-03: **134 test, 0 failure/error/skipped,
BUILD SUCCESS**. Chi tiết phạm vi và phần còn thiếu ở
[mục 10–11 của contract](../docs/architecture/14-inventory-api-contract.md#10-migration-và-bằng-chứng-kiểm-thử).

Chạy riêng test #29:

```powershell
mvn -pl inventory-service "-Dtest=InventoryLifecycleServiceTests,InventoryLifecycleIntegrationTests" test
```

Nếu Docker CLI chạy nhưng Testcontainers không nhận Docker trên Windows với context desktop-linux, đặt trong terminal test:

```powershell
$env:DOCKER_HOST = "npipe:////./pipe/dockerDesktopLinuxEngine"
```

`src/test/resources/docker-java.properties` chọn Docker API 1.44 để Testcontainers 1.21.3 làm việc với Docker mới; Docker Engine chạy test phải hỗ trợ API này. Nếu terminal không nhận Maven/Docker, thêm thư mục bin của bản cài trên máy vào PATH trước khi chạy.

## 5. Giao dịch và giới hạn

Reserve khóa guard theo Order, kiểm tra kết quả cũ, khóa Inventory theo thứ tự SKU cố định trong cửa hàng và kiểm tra tất cả dòng trước khi phân bổ. Cập nhật reservedQuantity có điều kiện và lưu Reservation/items trong một transaction. CHECK ở DB ngăn tồn khả dụng âm. Lỗi DB rollback toàn bộ, không được trả REJECTED giả cho lỗi hạ tầng.

Reserve/Release/Cancel khóa cùng guard theo Order; snapshot, trạng thái và tồn cùng commit hoặc rollback. Cancellation là dấu hiệu đơn điệu; Release không tạo snapshot Cancel giả. Snapshot được lưu trong inventory_operations thay vì nhúng vào guard, với UNIQUE(order_id, operation) và PK idempotency_key. Chưa có job xóa snapshot/guard: hiện tiếp tục giữ dữ liệu để không mất bảo vệ replay. Khi triển khai cleanup, thời lượng retention phải được cấu hình theo chính sách môi trường và không ngắn hơn cửa sổ retry/replay đã công bố; chưa xác định được cửa sổ an toàn thì tiếp tục giữ dữ liệu. Không xóa dữ liệu của công việc chưa hoàn tất. Kịch bản kiểm thử phải công bố thời lượng và căn cứ lựa chọn, không áp đặt một số ngày cố định cho toàn dự án.

Chưa có API sửa tồn kho, xác thực API nội bộ hoặc cơ chế đối soát tự động. Các test thủ công không thay thế bộ kiểm thử tải và phục hồi Saga ở các giai đoạn sau.
