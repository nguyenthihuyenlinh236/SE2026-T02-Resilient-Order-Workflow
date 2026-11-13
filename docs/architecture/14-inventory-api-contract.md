# Inventory API Contract – Reserve, Release và Cancel

## 1. Phạm vi

Hợp đồng HTTP cho issue #28 ánh xạ payload `ReserveInventory` trong `14-api-event-contract-draft.md` trên nhánh `feature/issue-6-api-event-contracts`: `orderId`, `reservationId`, `storeId`, `items[{sku,quantity}]`. RabbitMQ envelope/consumer chưa thuộc phạm vi. Order Service sinh và lưu `reservationId` một lần trước khi gửi; retry giữ nguyên ID, key và payload.

Reserve và tra cứu Reservation được triển khai trong #28; #29 bổ sung Release, Cancel và snapshot kết quả. Mục 2–8 mô tả API tương thích tại `/api/v1/inventory`. Mục 9–11 ghi trạng thái API nội bộ đang đồng bộ PR #39; chưa thể xem toàn bộ implementation là tương thích #39.

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
| orderId, reservationId | Identifier 1–128 ký tự theo `^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$`, bắt buộc; reservationId do bên gọi cấp trong schema hiện tại |
| storeId | Không trắng, tối đa 100 ký tự; bắt buộc cho command nội bộ |
| items | 1–100 dòng, không có phần tử null |
| sku | Không trắng, tối đa 128 ký tự, phân biệt hoa/thường; không tự trim |
| quantity | JSON integer dương trong phạm vi Java Integer; không nhận chuỗi số, float, boolean hoặc null; gộp SKU trùng không được tràn integer |

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
- `GET /api/v1/inventory/reservations?orderId={orderId}`.
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

Hai ID là Identifier bắt buộc; reservationId trong body phải khớp path. `reason` bắt buộc, không trắng, tối đa 255 ký tự. Không gửi items/storeId hoặc số lượng cần trả: lấy từ Reservation đã lưu để tránh giải phóng sai hàng. Hash bao gồm operation, hai ID và reason; retry không đổi reason hoặc key. Đây là request API cũ; Release nội bộ dùng schema tại mục 9.

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
| Legacy RELEASED thiếu mốc đã xác minh | 409 RELEASE_HISTORY_UNAVAILABLE | Không tự tạo timestamp hoặc trả tồn |

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

orderId là Identifier bắt buộc, khớp path; reason theo quy tắc Release API cũ. Cancel dùng khi cần ngăn tác dụng Reserve muộn, kể cả chưa biết reservationId. Không được dùng chỉ vì timeout/payment UNKNOWN; điều kiện chọn nhánh hủy thuộc Orchestrator, Inventory không truy vấn xuyên DB Payment/Order. Cancel là phần mở rộng #29, chưa có contract được chốt trong #39.

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

V003 dùng bảng inventory_operations để lưu snapshot cả Reserve/Release/Cancel; guard chỉ lưu cancellation_recorded/cancelled_at, không tạo thông tin Cancel giả khi Release ghi dấu hủy. Khi có Reservation legacy nhưng không có snapshot ban đầu có thể xác minh, replay Reserve trả 409 RESERVE_HISTORY_UNAVAILABLE, không trả trạng thái hiện tại như kết quả gốc.

## 9. API nội bộ đang đồng bộ với PR #39

Nguồn đối chiếu: [API/Event Contract trong PR #39](https://github.com/nguyenthihuyenlinh236/SE2026-T02-Resilient-Order-Workflow/pull/39), blob `d6f4a28a2171c5553f6b243b42074f64d7cc165f` được kiểm tra ngày 2026-11-03. Đây là trạng thái implementation hiện tại, không phải tuyên bố đã hoàn thành mọi yêu cầu của PR.

Mọi endpoint `/internal/v1/**` bắt buộc `X-Correlation-Id` không trắng, tối đa 128 ký tự ASCII hiển thị được. `X-Command-Id` tùy chọn, nếu gửi thì cùng giới hạn. Correlation header được trả trong response thành công/lỗi do Inventory xử lý; lỗi thiếu/sai correlation dùng UUID thay thế. Chưa quy định ưu tiên hoặc kiểm tra khớp giữa tracing header và body.

| Endpoint | Request hiện hỗ trợ | Response |
|---|---|---|
| POST `/internal/v1/reservations` | Schema mục 2: orderId, caller reservationId, storeId, items[{sku,quantity}]; key reserve:{orderId}:v1 | reservationId, orderId, status, reservedAt, reasonCode |
| GET `/internal/v1/reservations/by-order/{orderId}` | Order Identifier trên path; không body | reservationId, orderId, status, idempotencyKey, reasonCode |
| POST `/internal/v1/reservations/{reservationId}/release` | commandId, orderId, correlationId bắt buộc; key inventory:release:{orderId}:{reservationId}:v1 | reservationId, orderId, status=RELEASED, releasedAt |

Các field response trên luôn có mặt. Reserve REJECTED có reservationId/reservedAt=null; RESERVED có hai field này và reasonCode=null. Tra cứu REJECTED có reservationId=null. Không tự tạo UNKNOWN hoặc NOT_CREATED khi chưa có Reservation: trả 404. GET trả trạng thái resource hiện tại; retry Reserve cùng key/body trả kết quả lệnh gốc dù resource đã RELEASED.

| Mã lưu nội bộ/API cũ | reasonCode API nội bộ |
|---|---|
| INSUFFICIENT_STOCK | INVENTORY_NOT_AVAILABLE |
| PRODUCT_NOT_FOUND | PRODUCT_NOT_FOUND |
| ORDER_CANCELLED | ORDER_ALREADY_RELEASED |

Release nội bộ lấy Reservation ID từ path, không nhận reason hoặc reservationId trong body. commandId/correlationId không trắng, tối đa 128 ký tự. Giữ nguyên key/body khi retry. Hash cấu trúc gồm RELEASE_INTERNAL_V1, orderId, reservationId, commandId, correlationId; đổi tracing trong body cùng key trả 409. Header không tham gia hash. Đổi thứ tự field/whitespace JSON không đổi kết quả. Snapshot reason-based cũ không được diễn giải lại thành body mới: trả conflict, không trả tồn thêm.

Các lỗi validation, không tìm thấy và xung đột do handler Inventory xử lý dùng `{error:{code,message,details,correlationId,retryable}}`. Validation dùng VALIDATION_ERROR; ID sai trên path dùng INVALID_IDENTIFIER; các lỗi 4xx này có retryable=false.

DataAccessException, TransactionException và IllegalStateException cũng được ánh xạ an toàn: lỗi DB transient/recoverable, mất kết nối, không tạo được transaction hoặc timeout transaction trả 503 SERVICE_UNAVAILABLE/retryable=true; các lỗi toàn vẹn/trạng thái và transaction còn lại trả 500 INTERNAL_SERVER_ERROR/retryable=false. Không trả message exception, SQL hoặc chi tiết kết nối cho caller. Response giữ correlation header; lỗi phía service không trở thành kết quả REJECTED. Caller vẫn phải query trước khi retry và giữ nguyên key/payload; retryable không xác nhận tác dụng nghiệp vụ đã xảy ra hay chưa. Chưa chuẩn hóa mọi loại exception/routing, chưa triển khai xác thực/phân quyền nội bộ.

Cancel/operation và các alias tra cứu khác tại `/internal/v1` là phần mở rộng hiện có; không mặc nhiên thuộc contract #39. Cancel chưa có Reservation chỉ lưu guard; GET Reservation vẫn 404. Reserve đến sau guard bị từ chối; replay Cancel giữ kết quả NOT_CREATED ban đầu. Release 404 không lưu guard hoặc snapshot thành công. Reserve/Release/Cancel dùng cùng khóa Order, không trả tồn hai lần.

## 10. Migration và bằng chứng kiểm thử

| Migration | Thay đổi và tính tương thích |
|---|---|
| V001–V003 | Giữ nguyên checksum; dữ liệu tồn kho, Reservation và snapshot cũ không reset |
| V004 | Mở rộng reservations.reserve_key và inventory_operations.idempotency_key lên VARCHAR(512) |
| V005 | Đổi Order/Reservation ID và các cột tham chiếu sang VARCHAR(128); khôi phục FK, giữ nguyên chuỗi UUID, hash, key và response_payload |

Tra cứu operation chấp nhận key 1–512 ký tự ASCII hiển thị không whitespace. Công thức key POST vẫn được kiểm tra chính xác; giới hạn 512 không cho phép dùng key tùy ý thay công thức. Không tự sửa payload hash hoặc dựng snapshot lịch sử khi thiếu bằng chứng.

Lần chạy toàn bộ test gần nhất: `mvn -pl inventory-service test`, 148 test, 0 failure/error/skipped, BUILD SUCCESS (2026-11-03). Bao gồm unit test, PostgreSQL/HTTP integration, migration, replay và rollback. Đây không phải kết quả thử tải hay phục hồi đầy đủ Order/Saga. Các sửa tài liệu sau lần chạy này không thay đổi code.

| Nội dung | Bằng chứng trong inventory-service/src/test/java |
|---|---|
| Validation/chuẩn hóa dữ liệu và kiểm tra trước DB | InventoryServiceTests, InventoryLifecycleServiceTests |
| Reserve, cách ly store, cạnh tranh tồn | InventoryReserveIntegrationTests |
| Rollback sau cập nhật tồn và insert item lỗi | InventoryRollbackTests |
| ID 128 ký tự, key 512 ký tự, nâng cấp UUID cũ và FK | InventoryLifecycleIntegrationTests: stringIdentifiersSupportReserveReleaseCancelAndReplay, v004PreservesExistingResultsAndExpandsBothKeyColumnsTo512Characters, v005PreservesUuidDataSnapshotsAndForeignKeys |
| Schema response, rejection, Release lặp, tracing và hash | InventoryLifecycleIntegrationTests: các test internalReserve*, internalRelease*, releaseJsonFieldOrderAndWhitespaceDoNotChangeHashOrReplay |
| Guard, late Reserve, Cancel/Release cạnh tranh qua HTTP | InventoryLifecycleIntegrationTests: internalCancelBeforeLateReserveKeepsOriginalCancelAndRejectionSnapshots, internalReserveAndCancelRaceLeavesNoActiveStockAndBothCommandsReplay, internalReleaseAndCancelRaceKeepOneReleaseTimestampAndReturnStockOnce |

## 11. Các điểm chưa đồng bộ và cần chốt

| Phần | Còn thiếu hoặc chưa rõ | Giữ nguyên hiện tại |
|---|---|---|
| Request Reserve | productId là UUID nội bộ hay SKU? Không có storeId thì chọn kho nào? | Caller reservationId, storeId, items SKU; không chọn kho ngầm |
| Nguồn sinh Reservation ID | #39 yêu cầu service sở hữu sinh ID; chưa nối payload mới và xử lý replay cũ | Caller sinh ID; chưa triển khai Inventory sinh ID |
| Item trùng và quantity | #39 cấm productId trùng, yêu cầu ngưỡng quantity cấu hình; chưa có mapping/ngưỡng đã chốt | SKU trùng được gộp; quantity trong phạm vi Integer |
| Tracing | Regex tracing, header/body khác nhau và việc loại tracing body khỏi hash | Chỉ kiểm tra giới hạn hiện tại; không chọn nguồn ưu tiên, không đổi hash cũ |
| Cancel | #39 chưa định nghĩa endpoint/schema/key/quyền và ý nghĩa reasonCode khi mới có guard | Giữ extension #29; không đổi Release 404 thành thành công |
| Xác minh Order | #39 có ORDER_NOT_FOUND nhưng chưa chốt cách Inventory xác minh Order | Không truy vấn xuyên DB hoặc tự thêm call vòng sang Order |
| Error/auth | Đã ánh xạ lỗi DB/transaction/trạng thái; chưa bao phủ mọi exception/routing hoặc có identity nội bộ | Không tuyên bố tuân thủ đầy đủ lỗi/bảo mật của #39 |

Request Reserve nguyên bản trong #39 hiện chưa được hỗ trợ. Chỉ chốt hoàn tất đồng bộ sau khi các điểm trên được giải quyết và các ví dụ contract tương ứng có integration test.
