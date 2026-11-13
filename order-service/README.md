# Order Service – Create và Get Order

Order Service chạy mặc định tại `http://localhost:8081` và sở hữu `order_db`.

## Chạy profile demo

Khởi động PostgreSQL và RabbitMQ từ thư mục gốc repository:

```powershell
docker compose up -d --wait
$env:SPRING_PROFILES_ACTIVE = "demo"
mvn -pl order-service spring-boot:run
```

Flyway tự tạo `product_catalog`, `orders`, `order_items` và `idempotency_records`.
Profile `demo` nạp các SKU mẫu `SKU-001`, `SKU-002` và `SKU-INACTIVE` cho `STORE-001`.

## Tạo Order

`POST /api/v1/orders` yêu cầu hai header:

- `X-Client-Id`: định danh kỹ thuật của caller, tối đa 128 ký tự theo định dạng Identifier.
- `Idempotency-Key`: khóa cho một yêu cầu tạo Order logic, 1–512 ký tự ASCII hiển thị và không có whitespace.

`X-Client-Id` hiện chỉ dùng để tạo scope idempotency `(clientId, key)`. Nó không phải bằng
chứng xác thực và không được suy ra từ `customerId`. Khi bổ sung authentication, giá trị này
phải được lấy hoặc đối chiếu với principal tin cậy tại gateway/service.

```powershell
$headers = @{
    "X-Client-Id"       = "CLIENT-001"
    "Idempotency-Key"   = "create-order-demo-001"
    "X-Correlation-Id"  = "corr-demo-001"
}

$body = @{
    customerId = "CUSTOMER-001"
    storeId = "STORE-001"
    items = @(
        @{ sku = "SKU-001"; quantity = 2 }
        @{ sku = "SKU-002"; quantity = 1 }
    )
} | ConvertTo-Json -Depth 4

$order = Invoke-RestMethod -Method Post `
    -Uri "http://localhost:8081/api/v1/orders" `
    -Headers $headers -ContentType "application/json" -Body $body

$order
```

Response `202 Accepted`:

```json
{
  "orderId": "ORD-...",
  "status": "PENDING",
  "createdAt": "2026-11-14T03:00:00Z",
  "expireAt": "2026-11-14T03:15:00Z",
  "correlationId": "corr-demo-001"
}
```

Gửi lại cùng `X-Client-Id`, `Idempotency-Key` và payload đã chuẩn hóa sẽ trả response tạo
Order ban đầu. Cùng scope/key nhưng payload khác trả `409 IDEMPOTENCY_KEY_CONFLICT`.

## Tra cứu Order

```powershell
Invoke-RestMethod -Uri "http://localhost:8081/api/v1/orders/$($order.orderId)"
```

GET trả trạng thái hiện tại của Order. ID sai định dạng trả `400 INVALID_IDENTIFIER`; Order
không tồn tại trả `404 ORDER_NOT_FOUND`.
