# Inventory API Contract – Reserve

## 1. Phạm vi

Hợp đồng HTTP cho issue #28 ánh xạ payload `ReserveInventory` trong `14-api-event-contract-draft.md` trên nhánh `feature/issue-6-api-event-contracts`: `orderId`, `reservationId`, `storeId`, `items[{sku,quantity}]`. RabbitMQ envelope/consumer chưa thuộc phạm vi. Order Service sinh và lưu `reservationId` một lần trước khi gửi; retry giữ nguyên ID, key và payload.

## 2. ReserveInventory

`POST /api/v1/inventory/reservations`

Header bắt buộc: `Idempotency-Key: reserve:{orderId}:v1`.

```json
{
  "orderId": "00000000-0000-4000-8000-000000000010",
  "reservationId": "00000000-0000-4000-8000-000000000011",
  "storeId": "DEMO-STORE",
  "items": [{ "sku": "DEMO-ITEM", "quantity": 2 }]
}
```

| Trường | Quy tắc |
|---|---|
| orderId, reservationId | UUID, bắt buộc; reservationId do bên gọi cấp |
| storeId | Không trắng, tối đa 100 ký tự; bắt buộc cho command nội bộ |
| items | 1–20 dòng, không có phần tử null |
| sku | Không trắng, tối đa 128 ký tự, phân biệt hoa/thường; không tự trim |
| quantity | Số nguyên dương; số thập phân bị từ chối, gộp SKU trùng không được tràn integer |

SKU được phân giải trong đúng storeId, không giữ hàng từ cửa hàng khác. SKU trùng được gộp và sắp xếp trước khi hash/khóa; toàn bộ đơn được phân bổ hoặc từ chối. `productId` vẫn là UUID nội bộ để quản lý tồn, không dùng trong payload Reserve mới. Request chứa trường không biết bị từ chối 400 để tránh im lặng bỏ qua sai contract.

HTTP 200 trả kết quả nghiệp vụ đã commit:

```json
{
  "reservationId": "00000000-0000-4000-8000-000000000011",
  "orderId": "00000000-0000-4000-8000-000000000010",
  "storeId": "DEMO-STORE",
  "reserveKey": "reserve:00000000-0000-4000-8000-000000000010:v1",
  "status": "RESERVED",
  "rejectionReason": null,
  "items": [{ "sku": "DEMO-ITEM", "quantity": 2 }],
  "createdAt": "2026-10-31T08:00:00Z"
}
```

Thiếu tồn trả `REJECTED/INSUFFICIENT_STOCK`; không tìm thấy SKU trong store trả `REJECTED/PRODUCT_NOT_FOUND`. Kết quả từ chối vẫn lưu caller reservationId, storeId và items để query/replay. Khi nhiều dòng lỗi, PRODUCT_NOT_FOUND được ưu tiên nếu có SKU không tồn tại.

| HTTP | code | Ý nghĩa |
|---|---|---|
| 400 | INVALID_REQUEST | Thiếu/sai trường, key sai công thức hoặc số lượng không hợp lệ |
| 409 | IDEMPOTENCY_KEY_CONFLICT | Cùng Order/key nhưng khác reservationId, storeId hoặc items sau chuẩn hóa |
| 409 | RESERVATION_ID_CONFLICT | reservationId đã dùng cho Order khác; transaction mới rollback |
| 5xx | Lỗi hạ tầng | Không suy ra đã thất bại; tra cứu/retry cùng định danh |

Hash chứa operation, orderId, reservationId, storeId và items đã chuẩn hóa. Thứ tự item hoặc cách chia nhiều dòng cùng SKU không làm thay đổi tác dụng logic. Không dùng dấu phân cách chuỗi tùy ý để hash payload vì SKU/storeId có thể chứa dấu đó.

## 3. Tra cứu

- `GET /api/v1/inventory/reservations/{reservationId}`.
- `GET /api/v1/inventory/reservations?orderId={uuid}`.
- `GET /api/v1/inventory/reservations?idempotencyKey={key}`; `reserveKey` là alias HTTP tương thích.
- Chỉ dùng một selector; nhiều selector trả 400.
- Tìm thấy trả 200 và Reservation đã lưu; không thấy trả 404 `RESERVATION_NOT_FOUND`, tương ứng NOT_FOUND trong draft command. Lỗi truy cập DB là 5xx, không giả thành NOT_FOUND/REJECTED.
- NOT_FOUND không hủy một Reserve đang bay; Cancel/Release và guard chống lệnh muộn thuộc #29.
- `GET /api/v1/inventory/stock?storeId=DEMO-STORE&sku=DEMO-ITEM` tra cứu tồn theo contract.
- `GET /api/v1/inventory/products/{productId}` tiếp tục hỗ trợ tra cứu theo ID nội bộ. Stock trả thêm `sku`, `storeId` cùng các lượng tồn.

## 4. Nâng cấp dữ liệu

V002 bổ sung SKU/store, chuyển các sản phẩm demo cố định thành DEMO-ITEM/DEMO-EMPTY và DEMO-STORE. Sản phẩm cũ khác dùng UUID dạng text làm SKU trong DEMO-STORE. Giữ nguyên tồn kho, Reservation ID và trạng thái; unknown product trong kết quả REJECTED có reference nội bộ NULL và giữ SKU snapshot. FK chỉ nối tới tồn kho khi SKU đã được phân giải.

Không sửa checksum V001 hoặc tự reset database. Payload/key theo contract cũ không được tái sử dụng như một request mới; Reservation cũ vẫn query được. Hash cũ được giữ nguyên nên replay legacy dưới payload SKU mới sẽ conflict; cần dùng đúng workflow/định danh mới khi test contract mới. Thống nhất lại nguồn sinh ID và SKU với tài liệu database khi review #21, không đổi đồng thời file đang ở PR khác.
