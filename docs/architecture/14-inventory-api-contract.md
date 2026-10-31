# Inventory API Contract – Reserve, Release và Cancel

## 1. Phạm vi

Hợp đồng HTTP cho issue #28 ánh xạ payload `ReserveInventory` trong `14-api-event-contract-draft.md` trên nhánh `feature/issue-6-api-event-contracts`: `orderId`, `reservationId`, `storeId`, `items[{sku,quantity}]`. RabbitMQ envelope/consumer chưa thuộc phạm vi. Order Service sinh và lưu `reservationId` một lần trước khi gửi; retry giữ nguyên ID, key và payload.

Reserve và tra cứu Reservation đã được triển khai trong #28. Mục 5–8 đặc tả phần sẽ triển khai trong #29; các endpoint mới chưa tồn tại trong code #28. Công thức khóa theo D-02 và issue #29 được ưu tiên so với khóa Release cũ trong draft.

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

Không sửa checksum V001 hoặc tự reset database. Payload/key theo contract cũ không được tái sử dụng như một request mới; Reservation cũ vẫn query được. Hash cũ được giữ nguyên nên replay legacy dưới payload SKU mới sẽ conflict; cần dùng đúng workflow/định danh mới khi test contract mới. Thiết kế database trên nhánh #21 được cập nhật riêng để phản ánh SKU/store và nguồn sinh Reservation ID.

## 5. ReleaseReservation – phạm vi #29

`POST /api/v1/inventory/reservations/{reservationId}/release`

Header: `Idempotency-Key: inventory:release:{orderId}:{reservationId}:v1`.

```json
{
  "orderId": "00000000-0000-4000-8000-000000000010",
  "reservationId": "00000000-0000-4000-8000-000000000011",
  "reason": "ORDER_EXPIRED"
}
```

Hai ID bắt buộc UUID; reservationId trong body phải khớp path. `reason` bắt buộc, không trắng, tối đa 255 ký tự. Không gửi items/storeId hoặc số lượng cần trả: lấy từ Reservation đã lưu để tránh giải phóng sai hàng. Hash bao gồm operation, hai ID và reason; retry không đổi reason hoặc key.

| Tình huống | HTTP / kết quả | Tác dụng |
|---|---|---|
| RESERVED thuộc đúng Order | 200, `status=RELEASED` | Giảm reserved_quantity đúng lượng đã giữ; on_hand_quantity không đổi |
| Đã RELEASED, cùng key/hash | 200, replay response đã lưu | Không sửa tồn kho |
| Đã RELEASED nhưng chưa có record Release, ví dụ Cancel đã giải phóng | 200, `status=RELEASED` | Lưu kết quả Release, không trả hàng thêm |
| Reservation thuộc Order khác | 409 RESERVATION_ORDER_MISMATCH | Không đổi tồn hoặc guard của Order nào |
| Reservation REJECTED | 409 RESERVATION_NOT_RESERVED | Không tự chuyển REJECTED sang RELEASED |
| Không tồn tại | 404 RESERVATION_NOT_FOUND | Không báo giải phóng thành công; không trả tồn |
| Cùng key khác payload | 409 IDEMPOTENCY_KEY_CONFLICT | Không có tác dụng mới |
| Path/body/key sai | 400 INVALID_REQUEST | Không có tác dụng |

Response 200 gồm `orderId`, `reservationId`, `idempotencyKey`, `status=RELEASED`, `releasedAt` (UTC). Release thành công hoặc xác minh đã RELEASED ghi dấu hủy theo Order. Lỗi validation, sai chủ sở hữu, REJECTED hoặc NOT_FOUND không commit dấu hủy/kết quả thành công. Không lưu NOT_FOUND như kết quả cuối của key, vì Reserve đang bay có thể hoàn tất sau đó; bên gọi tra cứu Reserve hoặc dùng Cancel theo Order.

## 6. CancelInventoryOrder – phạm vi #29

`POST /api/v1/inventory/orders/{orderId}/cancel`

Header: `Idempotency-Key: inventory:cancel:{orderId}:v1`.

```json
{
  "orderId": "00000000-0000-4000-8000-000000000010",
  "reason": "ORDER_EXPIRED"
}
```

orderId bắt buộc UUID, khớp path; reason theo quy tắc Release. Cancel dùng khi cần ngăn tác dụng Reserve muộn, kể cả chưa biết reservationId. Không được dùng chỉ vì timeout/payment UNKNOWN; điều kiện chọn nhánh hủy thuộc Orchestrator, Inventory không truy vấn xuyên DB Payment/Order.

Cancel khóa cùng guard với Reserve/Release và ghi dấu hủy đơn điệu false → true. Nếu đã RESERVED, giải phóng đúng Reservation trong cùng transaction; nếu chưa có, chỉ chặn Reserve tương lai; nếu RELEASED/REJECTED, không sửa tồn. Hash bao gồm operation, orderId và reason.

Response 200 có `orderId`, `idempotencyKey`, `status=CANCELLATION_RECORDED`, `reservationId` (nullable), `reservationStatus` (NOT_CREATED/RELEASED/REJECTED), `cancelledAt` (UTC). Đây là kết quả lệnh hủy, không phải trạng thái Reservation mới. Không có Reservation thì không trả reservationStatus=RELEASED.

Cùng key/hash replay response ban đầu; khác hash trả 409. Cancel vẫn lưu được kết quả của chính nó khi Release đã ghi dấu hủy trước, không tạo khóa/hash Cancel giả từ reason Release.

## 7. Truy vấn kết quả lệnh và replay – phạm vi #29

`GET /api/v1/inventory/operations?idempotencyKey={key}` trả HTTP 200 với `operation`, `idempotencyKey`, `responseCode`, `responsePayload`, `completedAt` của lệnh đã commit. Chưa có kết quả trả 404 OPERATION_NOT_FOUND, không đồng nghĩa lệnh đang bay đã thất bại. Chưa xác minh do lỗi DB trả 5xx; bên gọi giữ UNKNOWN và truy vấn lại bằng cùng key.

`GET reservations` tiếp tục trả trạng thái resource hiện tại. Sau Release, GET Reservation là RELEASED nhưng replay Reserve và GET operation Reserve vẫn trả kết quả Reserve ban đầu RESERVED, không giữ lại hàng. Record lệnh và tác dụng nghiệp vụ commit cùng transaction.

## 8. Thứ tự khóa và nâng cấp #29

Mọi Reserve/Release/Cancel khóa guard theo Order trước, rồi khóa các dòng Inventory theo SKU tăng dần trong cùng store. Chỉ một chuyển RESERVED → RELEASED được giảm reserved_quantity. Reserve mới thấy guard đã hủy trả kết quả REJECTED/ORDER_CANCELLED và lưu để replay; không tạo giữ hàng còn hiệu lực.

Đã có record Reserve thì kiểm tra hash và replay trước khi xét guard hủy. Hai lệnh Reserve/Cancel cạnh tranh: Reserve thắng trước thì Cancel giải phóng; Cancel thắng trước thì Reserve bị từ chối. Guard, chuyển trạng thái, cập nhật tồn và kết quả lệnh rollback toàn bộ nếu transaction lỗi.

Migration #29 bổ sung dấu hủy, released_at và nơi lưu snapshot kết quả lệnh; không sửa V001/V002. Backfill Reserve đã lưu từ #28 trước khi cho phép Release thay đổi resource, giữ nguyên payload hash cũ khi chưa có cách xác minh payload legacy. Không backfill response RESERVED từ Reservation đã RELEASED nếu không có bằng chứng kết quả gốc. Giữ khóa/guard theo retention và cửa sổ replay của NFR-10.
