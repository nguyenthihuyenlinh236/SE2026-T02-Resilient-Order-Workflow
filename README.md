# Resilient Order Workflow

Ứng dụng mô phỏng quy trình đặt hàng dùng **Java 17, Spring Boot và PostgreSQL**. Mã nguồn nền triển khai API tạo/tra cứu đơn, Saga xử lý nền, inventory/payment giả lập, idempotency, Outbox và xử lý đơn hết hạn.

## Chạy ứng dụng

Yêu cầu: JDK 17+, Maven 3.9+ và Docker Compose.

```powershell
$env:APP_BOOTSTRAP_ADMIN_USERNAME = "admin"
$env:APP_BOOTSTRAP_ADMIN_PASSWORD = "<choose-a-local-password-of-at-least-12-characters>"
docker compose up -d
mvn spring-boot:run
```

Chạy kiểm thử:

```powershell
mvn test
```

Mặc định ứng dụng kết nối `jdbc:postgresql://localhost:5432/resilient_orders` với thông tin local trong `application.yml`. Có thể ghi đè bằng `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` và cổng HTTP bằng `PORT`. Flyway tạo schema và dữ liệu demo `DEMO-ITEM` (10.000 đơn vị) khi khởi động lần đầu.

## API

API dùng session cookie có CSRF token. Trước lệnh `POST`, `PATCH` hoặc `logout`, gọi `GET /api/v1/auth/csrf`, giữ cookie và gửi token trả về trong header được chỉ định (`X-XSRF-TOKEN`).

Đăng nhập:

```powershell
$csrf = Invoke-RestMethod http://localhost:8080/api/v1/auth/csrf -SessionVariable session
Invoke-RestMethod -Method Post http://localhost:8080/api/v1/auth/login `
  -WebSession $session `
  -Headers @{ "X-XSRF-TOKEN" = $csrf.token } `
  -ContentType "application/json" `
  -Body '{"username":"admin","password":"<configured-admin-password>"}'
```

Tạo đơn (API trả `202 Accepted` sau khi đã lưu bền vững; tiến trình Saga chạy nền; cần tài khoản `CUSTOMER`):

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/v1/orders `
  -WebSession $session `
  -Headers @{ "X-XSRF-TOKEN" = $csrf.token; "Idempotency-Key" = "demo-order-001" } `
  -ContentType "application/json" `
  -Body '{"items":[{"sku":"DEMO-ITEM","quantity":2}]}'
```

Tra cứu trạng thái:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/orders/<orderId> -WebSession $session
```

Gửi lại POST cùng `Idempotency-Key` và payload trả lại cùng Order; dùng lại khóa với payload khác trả `409 IDEMPOTENCY_KEY_CONFLICT`. API từ chối khóa idempotency thiếu, SKU trùng, danh sách hàng rỗng hoặc số lượng không dương.

## Tài khoản và phân quyền

Khởi tạo admin lần đầu bằng biến môi trường `APP_BOOTSTRAP_ADMIN_USERNAME` và `APP_BOOTSTRAP_ADMIN_PASSWORD` (tối thiểu 12 ký tự); không có mật khẩu mặc định. Admin tạo tài khoản `CUSTOMER`, `STAFF` hoặc admin khác qua `POST /api/v1/admin/accounts`. Tài khoản nhân viên phải gắn với store đã tồn tại; base code tạo sẵn `DEMO-STORE`.

- `CUSTOMER`: chỉ tạo đơn và xem đơn của chính mình.
- `STAFF`: chỉ xem/nhận/cập nhật fulfillment của đơn trong cửa hàng được gán; mỗi đơn chỉ một nhân viên nhận.
- `ADMIN`: quản lý tài khoản, xem đơn và đọc cảnh báo bảo mật.

Đơn không tồn tại và đơn thuộc tài khoản khác đều trả `404 Order not found`. Mỗi truy cập bị từ chối được ghi vào audit log độc lập với transaction nghiệp vụ. Sau 10 lần từ chối trong cùng phiên, phiên bị thu hồi và tạo cảnh báo trong hàng đợi admin. Phiên hết hạn sau 30 phút không hoạt động; tài khoản bị khóa 15 phút ngay sau lần đăng nhập sai thứ năm. Cookie phiên là HttpOnly/SameSite=Strict; bật `SESSION_COOKIE_SECURE=true` khi dùng HTTPS.

Đơn được ẩn khỏi danh sách sau 26 ngày; worker mỗi giờ xóa dữ liệu của đơn đã kết thúc và các bản ghi liên quan. Đơn quá hạn nhưng còn Saga/bù trừ chưa hoàn tất được giữ lại an toàn và ghi lỗi vận hành để đối soát, không xóa mù làm thất thoát reservation hoặc payment.

Nhân viên xem đơn tại `GET /api/v1/staff/orders`, nhận đơn bằng `POST /api/v1/staff/orders/{id}/claim`, rồi chuyển `RECEIVED → PREPARING → DELIVERED` bằng `PATCH /api/v1/staff/orders/{id}/fulfillment`. Mỗi mutation cần session và CSRF token.

## Hàng đợi đơn khi mất mạng (client)

`/offline-order-queue.js` cung cấp `window.OfflineOrderQueue` và lưu nháp trong IndexedDB của trình duyệt. Client lấy `accountId` từ `/api/v1/auth/me`, rồi dùng module như sau:

```javascript
const queue = OfflineOrderQueue.createOfflineOrderQueue();
await queue.enqueue(account.accountId, {
  items: [{ sku: "DEMO-ITEM", quantity: 2 }]
});

window.addEventListener("online", async () => {
  const result = await queue.sync(account.accountId);
  // Hiển thị result cho người dùng; các đơn chưa gửi vẫn còn trong hàng đợi.
});
```

Mỗi nháp giữ nguyên `Idempotency-Key` qua các lần đồng bộ. Trước khi gửi, module kiểm tra phiên hiện tại qua `/api/v1/auth/me`, xác minh đúng tài khoản Customer, rồi lấy CSRF token. Lỗi mạng và phản hồi lỗi không xóa nháp; client có thể xem `queue.list(accountId)` và xóa nháp cụ thể bằng `queue.remove(idempotencyKey)`. Đây là module client, chưa có giao diện đặt hàng tích hợp; dữ liệu nháp chỉ lưu trên trình duyệt hiện tại và có thể mất nếu người dùng xóa dữ liệu trình duyệt.

## Kiểm thử tải cục bộ

Script `scripts/order-load-test.mjs` dùng Node.js tích hợp sẵn, không cần cài thêm package. Chỉ chạy với ứng dụng/database thử nghiệm: script tạo đơn lặp lại và tiêu thụ tồn kho. Cần có một tài khoản `CUSTOMER` đã được tạo trước:

```powershell
$env:LOAD_TEST_USERNAME = "load-test-customer"
$env:LOAD_TEST_PASSWORD = "<customer-password>"
node scripts/order-load-test.mjs
```

Mặc định chạy 148 phiên đăng nhập độc lập trong tối thiểu 120 giây; mỗi phiên tạo đơn, chờ 5–10 giây, tra cứu đơn rồi tiếp tục. Báo cáo JSON in p95 cho POST/GET, throughput, lỗi 5xx và phản hồi không mong đợi. Kết quả chỉ được coi đạt ngưỡng cá nhân hóa khi p95 mỗi endpoint ≤2 giây, 5xx ≤1%, đủ 148 phiên và chạy đủ thời lượng. Có thể dùng `LOAD_TEST_VUS` hoặc `LOAD_TEST_DURATION_SECONDS` để thử nhanh, nhưng các lần chạy đó không thể xác nhận đạt tải mục tiêu. `LOAD_TEST_BASE_URL` mặc định là `http://localhost:8080`; SKU mặc định là `DEMO-ITEM`.

## Mô phỏng payment

`PAYMENT_SIMULATED_OUTCOME` nhận `SUCCEEDED` (mặc định), `FAILED` hoặc `UNKNOWN`. Đặt `UNKNOWN` để giữ Saga ở trạng thái đối soát; đổi sang `SUCCEEDED`/`FAILED` và khởi động lại ứng dụng để worker tiếp tục bằng cùng payment record. TTL mặc định 15 phút, có thể chỉnh bằng `ORDER_TTL` (ví dụ `30s`). Các worker có chu kỳ riêng trong `application.yml`.

Các event nghiệp vụ được lưu cùng transaction thay đổi trạng thái Order; publisher nền ghi nhận lượt giao notification mô phỏng và đánh dấu Outbox đã phát. Dịch vụ Inventory, Payment, Notification hiện mô phỏng trong cùng ứng dụng/Database, chưa phải các microservice độc lập.

## Phạm vi của base code

Đã có luồng nền cho giữ hàng, payment mock, xác nhận/thất bại, giải phóng reservation, refund mock và hết hạn; các nhánh cạnh tranh dùng conditional update theo DB clock. Increment RE-005 bổ sung session/authentication, role/ownership checks, audit, luồng fulfillment theo store, dọn retention cho đơn kết thúc và module lưu hàng đợi đơn offline phía trình duyệt. Chưa có giao diện đặt hàng tích hợp, retry/backoff tự động, lease worker bền vững, cảnh báo quản trị qua kênh ngoài, fault-injection API, payment reconciliation theo lịch D-04, TLS termination hoặc tích hợp broker/provider thật. Hiệu năng 148 người dùng đồng thời và các ngưỡng p95/độ lỗi cần được xác minh bằng môi trường tải riêng trước khi coi là đạt.
