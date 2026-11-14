# 16. Lưu tiến trình Saga và phục hồi sau sự cố

## 1. Phạm vi và căn cứ

Thiết kế cho Issue #22, chỉ tài liệu, chưa triển khai Java hoặc migration SQL. Order Service là orchestrator; Inventory và Payment sở hữu tác dụng nghiệp vụ trong database riêng. Không dùng transaction hoặc JOIN xuyên database.

Đọc cùng [State Machine](09-state-machine.md), [Saga Workflow](10-saga-workflow-and-compensation.md), [Design Decisions](../requirements/06-design-decisions.md), [Functional Requirements](../requirements/02-functional-requirements.md) và [Business Rules](../requirements/03-business-rules.md). Thiết kế dưới đây dùng trạng thái trong State Machine; tên cột là logic, schema vật lý thuộc Issue #21.

## 2. Bảng quyết định luồng

| Điều kiện đã xác minh | Hành động | Điều kiện kết thúc |
|---|---|---|
| Order PENDING, Reserve chưa thực hiện | Lưu ý định Reserve rồi gọi Inventory | Chỉ Capture khi RESERVED đã xác minh và Order vẫn PENDING |
| Reserve REJECTED | Claim PENDING → COMPENSATING; không Capture | FAILED sau khi xác minh không còn tác dụng cần bù |
| Reserve UNKNOWN | Query theo định danh/key đã lưu; không Capture | Chưa có bằng chứng thì giữ việc đối soát |
| Capture SUCCEEDED, RESERVED, trước hạn | Conditional update PENDING → CONFIRMED | Order + Saga COMPLETED + OrderConfirmed cùng commit |
| Capture FAILED đã xác minh | Claim nhánh thất bại, Release nếu cần | FAILED + Saga COMPLETED + OrderFailed cùng commit |
| Capture UNKNOWN | RECONCILING, sau lịch chính là RECONCILIATION_REQUIRED | Không tự FAILED, release/refund hoặc chốt EXPIRED |
| Capture IN_PROGRESS khi quá hạn | Được claim EXPIRING, dừng lệnh tiến mới; query kết quả | Không chốt EXPIRED khi Capture/Refund còn IN_PROGRESS hoặc UNKNOWN |
| Order quá hạn, Capture không UNKNOWN | Claim PENDING → EXPIRING bằng token/lease | Release cần thiết đã xác minh; Capture thành công thì Refund thành công |
| Refund FAILED đã xác minh | RECONCILIATION_REQUIRED, ghi lý do cần can thiệp | Không tự retry bằng key mới hoặc coi đã hoàn tiền |
| Capture thành công phát hiện sau EXPIRED | Giữ Order EXPIRED, đề xuất mở lại Saga để refund theo Q-07 | Chờ phê duyệt Q-07; không phát OrderConfirmed |

CONFIRMED, FAILED và EXPIRED là trạng thái cuối của Order. COMPENSATION_FAILED là trạng thái trung gian. Saga COMPLETED chỉ có ngoại lệ refund muộn được đề xuất tại Q-07; không cho phép tùy ý chạy lại toàn bộ Saga.

## 3. Dữ liệu phải lưu bền vững

| Nhóm | Trường logic tối thiểu | Mục đích |
|---|---|---|
| Order | orderId, status, createdAt, expireAt, version, terminalAt | Phân xử nhánh và không gia hạn TTL khi retry |
| Kết quả cục bộ | reservationId/status, captureId/status, refundId/status, verified flags, verifiedAt, amount/currency, unknownOperation | Không gọi DB của service khác để quyết định trạng thái |
| Saga | sagaId, orderId duy nhất, status, branch, currentOperation, completedAt | Khôi phục đúng nhánh FORWARD/FAILURE/EXPIRATION |
| Bước | stepId, sagaId, operation, idempotencyKey, commandId, correlationId, requestPayload, payloadHash | Gửi lại đúng command, không sinh key mới sau crash |
| Kết quả bước | executionStatus, businessResult, responsePayload, transactionId, verifiedAt | Tách trạng thái thực thi khỏi RESERVED/REJECTED hoặc kết quả thanh toán |
| Lịch | sendCount, nextAttemptAt, unknownSince, reconciliationDeadline, queryCount, backgroundQueryCount, nextQueryAt | Không reset bộ đếm/lịch sau restart |
| Quyền worker | claimToken, claimedAt, leaseUntil, version | Tiếp quản nguyên tử, ngăn worker cũ ghi |
| Lịch sử | attemptId, kind, sequenceNo, startedAt, finishedAt, outcome, errorCode | Phân biệt lần gửi chưa rõ và kết quả đã xác minh |
| Can thiệp | reason, requestedAt, resolvedAt, actor, evidence, decision | Kiểm toán xử lý ngoại lệ; không xóa bằng chứng |
| Outbox | eventId, eventType, schemaVersion, payload, occurredAt, publishStatus, nextAttemptAt | Publish lại cùng event sau crash |

Không tái tạo request từ dữ liệu giá/sản phẩm hiện tại: dùng snapshot đã lưu. Hash chỉ bao gồm dữ liệu nghiệp vụ theo contract đã thống nhất; metadata tracing không được tùy ý làm thay đổi ý nghĩa cùng key.

Mỗi Saga có tối đa một bước logic cho mỗi operation trong demo v1; nhiều lần SEND/QUERY là attempt của bước đó. Kết quả tác dụng phụ và bước tương ứng phải được liên kết bằng Order, operation, key, payload và transaction ID. Không suy ra thất bại nghiệp vụ chỉ từ executionStatus hoặc lỗi mạng.

## 4. Transaction boundary

1. **Tiếp nhận:** ghi Order, items, Saga và ý định bước đầu trong cùng transaction; chỉ trả 202 sau commit. Không tự thêm OrderCreated khi contract chỉ công bố ba event kết thúc.
2. **Chuẩn bị gửi:** khóa dòng Order trước rồi bước Saga; kiểm tra nhánh cho phép, ghi key/payload và attempt SEND, tăng bộ đếm, commit trước khi gọi mạng.
3. **Gọi service:** không giữ transaction DB trong khi chờ HTTP. Request đã gửi có thể hoàn tất dù worker mất lease hoặc Order đổi nhánh.
4. **Nhận kết quả:** worker có quyền đọc lại Order/bước, đối chiếu định danh và payload, lưu kết quả xác minh và quyết định bước tiếp theo cùng transaction. Kết quả cuối đã xác minh không bị timeout/phản hồi cũ ghi đè.
5. **Kết thúc:** conditional update Order, Saga COMPLETED và Outbox tương ứng trong một transaction; chỉ ghi event nếu UPDATE thắng. Publish sau commit.

Đối với IN_PROGRESS, polling lấy kết quả hiện tại; replay snapshot POST ban đầu không thay thế GET trạng thái hiện tại. UNKNOWN có thể chuyển về IN_PROGRESS khi service xác nhận đang xử lý; vẫn chưa đủ để hoàn tất nhánh.

## 5. Claim, lease và tranh chấp

- Mọi thay đổi nhánh và ý định gửi lệnh tiến dùng cùng khóa dòng Order hoặc cơ chế CAS tương đương. Thứ tự khóa thống nhất Order → Saga/bước để giảm deadlock.
- Claim TTL chỉ thành công nếu Order PENDING, DB UTC time >= expireAt và Capture khác UNKNOWN. Claim ghi token mới, claimedAt, leaseUntil nguyên tử.
- Gia hạn chỉ khi token trùng và lease còn hiệu lực; không hồi sinh quyền đã hết hạn.
- Tiếp quản khi leaseUntil <= giờ DB, bằng UPDATE có guard kiểm tra token/version cũ; sinh token mới. Hai worker cạnh tranh chỉ một worker thắng.
- Mọi ghi tiến trình của worker phải kiểm tra token và lease. UPDATE 0 dòng nghĩa mất quyền: đọc lại, không ghi đè.
- Token không chặn request đã bay đến service đích. Idempotency và xử lý tác dụng muộn vẫn bắt buộc.
- Dùng clock_timestamp() của PostgreSQL cho hạn/lease; dữ liệu timestamptz và UTC. Không dùng CURRENT_TIMESTAMP trong transaction dài để quyết định còn hạn.

CONFIRMED yêu cầu Capture SUCCEEDED, Reservation RESERVED đã xác minh, đúng Order/amount/currency, Order còn PENDING và chưa đến expireAt. EXPIRED yêu cầu token/lease hợp lệ, không còn Capture/Refund IN_PROGRESS hoặc UNKNOWN, hàng đã giải quyết và khoản phải hoàn đã hoàn.

## 6. Trình tự phục hồi

1. Đọc công việc chưa hoàn tất và đến lịch từ DB; không tạo Saga mới chỉ vì process restart.
2. Claim công việc có lease hết hạn hoặc chưa có chủ; đọc lại Order, branch, bước và kết quả đã lưu.
3. Nếu attempt SEND đã ghi nhưng chưa có kết quả, query bằng key/ID cũ trước. Không thể biết chỉ từ DB cục bộ request đã rời máy hay chưa.
4. Nếu kết quả đã xác minh, bỏ qua tác dụng đã hoàn tất và tính bước tiếp theo theo nhánh hiện tại.
5. Nếu vẫn UNKNOWN/IN_PROGRESS, giữ lịch đối soát/polling. Không reset unknownSince hoặc mở lại cửa sổ retry chính.
6. Nếu lỗi tạm thời đã xác định, retry đúng key khi đến lịch. Sau 5 lần gửi chính tính cả lần đầu thì chuyển nhánh lỗi bù trừ tương ứng và retry nền theo cấu hình.
7. Phục hồi Outbox độc lập: event chưa xác nhận publish được phát lại cùng ID; consumer dedupe.

| Điểm crash | Bằng chứng sau restart | Xử lý |
|---|---|---|
| Trước commit tiếp nhận | Không có Order/Saga đã commit | Client retry cùng key |
| Sau commit, trước response 202 | Có Order và idempotency record | Trả kết quả đã lưu, không tạo đơn mới |
| Sau ghi SEND, trước/sau gọi mạng | Attempt chưa hoàn tất | Query; nếu retry được phép dùng key/payload cũ |
| Sau remote commit, trước lưu kết quả | Local chưa rõ, remote có kết quả | Query lấy bằng chứng, không Capture/Refund mới |
| Sau kết quả bước, trước bước tiếp | Kết quả bền vững | Đánh giá lại Order/nhánh rồi tiếp tục |
| Sau claim TTL | Token/lease còn trong DB | Chờ hết lease, tiếp quản nguyên tử |
| Sau publish, trước mark | Outbox còn NEW | Phát lại cùng eventId |

HTTP 404 sau timeout không chứng minh không còn request đang bay. Đặc biệt Reservation chỉ được coi là không tồn tại an toàn nếu chưa từng gửi Reserve, hoặc Inventory cung cấp bằng chứng chặn tác dụng muộn. Contract Cancel/guard đang chờ thống nhất; thiếu bằng chứng thì giữ việc đối soát và cảnh báo, không tự chốt EXPIRED.

## 7. Đối chiếu và quyết định còn mở

Đối chiếu snapshot PR #34 d42c11c và PR #39 fdc1f23; đây là đầu vào đang review, không phải tài liệu đã được nhóm phê duyệt. Chủ sở hữu các PR phải xác nhận lại khi thay đổi.

| ID | Điểm cần thống nhất | Hướng xử lý / chủ sở hữu |
|---|---|---|
| I22-01 | #34 Reserve dùng storeId/SKU và caller reservationId; #39 dùng productId và service sinh ID | Chủ sở hữu Inventory/API/DB chốt một contract; Saga lưu nguyên snapshot theo contract đã chốt |
| I22-02 | UUID DB so với Identifier API; key DB 255 so với API 512 | Chủ sở hữu #21/#20 thống nhất kiểu và giới hạn; không cắt key |
| I22-03 | #34 ghi OrderCreated, #39 chỉ ba event cuối | Chủ sở hữu #21/#20 thống nhất event; Issue #22 không tự thêm event |
| I22-04 | #34 có Cancel/guard; contract chung chưa có API tương ứng | Chốt bằng chứng chặn Reserve muộn trước triển khai nhánh hoàn tất khi query 404 |
| I22-05 | D-03 nói Reserve query nền 30 giây; State Machine mục 4.5 dùng lịch chung 30 query | Đề xuất theo State Machine; chủ sở hữu Design Decisions cập nhật sau review |
| I22-06 | D-06.2 chưa nêu rõ IN_PROGRESS; State Machine T-08 chặn cả UNKNOWN/IN_PROGRESS | Áp dụng guard chặt của T-08; đồng bộ D-06.2 trước nghiệm thu |
| I22-07 | D-05.3 tra Refund theo paymentId; #39 theo refundId/key | Dùng API refundId/key theo contract được duyệt, không suy trạng thái Refund từ Capture |
| I22-08 | Q-01/Q-02/Q-03/Q-07/Q-08 chưa được duyệt | Giữ ghi chú chờ nhóm; không cho phép key v2 hoặc ngoại lệ hoàn tất tự động |

Mâu thuẫn trong phạm vi Saga Workflow đã sửa: nhánh thất bại vượt ngưỡng dùng Order COMPENSATION_FAILED theo T-06/T-07; nhánh hết hạn giữ EXPIRING. Các điểm liên PR được ghi nhận, không sửa thay công việc của chủ sở hữu #21.

Không đưa retention 29 ngày hoặc tải 155 người dùng vào tiêu chí Issue #22. Chính sách lưu giữ phải bảo vệ công việc chưa hoàn tất và cửa sổ replay; tham số tải/retention riêng thuộc cấu hình kịch bản kiểm thử được phê duyệt.

## 8. Kiểm chứng và bàn giao

Các trường hợp cần kiểm thử khi triển khai: hai worker cùng claim; token cũ trả kết quả muộn; crash ở từng hàng mục 6; Reserve bị từ chối; Capture FAILED; UNKNOWN kéo dài; query trả IN_PROGRESS; Capture thành công sau hạn; Refund FAILED; 404 rồi Reserve đến muộn; publish trùng; cùng key khác payload. Quan sát cả Order, Saga, attempts và số tác dụng thực tế tại service đích.

| Nhóm yêu cầu Issue #22 | Bằng chứng thiết kế |
|---|---|
| Danh sách/chuyển trạng thái | State Machine mục 3–6, bổ sung mục 15 |
| Orchestration và compensation | Saga Workflow mục 5–9; bảng mục 2 ở đây |
| Lưu tiến trình/restart | Mục 3, 4, 6 |
| Conditional update, TTL, lease, UTC | Mục 5; State Machine T-01/T-04/T-08 |
| API/schema/giả định mở | Mục 7 |
| Khả năng kiểm thử | Ma trận crash và danh sách kịch bản trên |

Trạng thái bàn giao: thiết kế đề xuất để review. Chưa chạy thử Saga, chưa chứng minh recovery runtime, chưa đánh dấu các quyết định mở là đã được nhóm chấp thuận.
