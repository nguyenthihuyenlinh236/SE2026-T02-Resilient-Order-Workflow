# VT-007 Auto-save Order Draft Specification

## Baseline

- Remote `dev`: `a513241d0e71e2c0eb7adaa32198e0b3902cf766`.
- Nhánh `feature/vt007-autosave-testfirst` trước fast-forward: `ec965d6fad867f9fb9ef756f5650d69731017abb`.
- Nhánh được fast-forward, không sửa `dev` và không tạo nhánh remote mới.

## Problem statement

Order Service cần lưu snapshot Order Draft chưa hoàn chỉnh. Mỗi request mang revision do client cấp. Với cùng `(X-Client-Id, draftId)`, dữ liệu đã lưu không được giảm revision và payload của revision hiện tại không được bị request cũ hoặc xung đột ghi đè.

`X-Client-Id` chỉ là scope demo phù hợp convention hiện tại; nó không thay thế authentication/authorization thực tế.

## API contract cho vertical slice

### PUT `/api/v1/order-drafts/{draftId}`

Header bắt buộc: `X-Client-Id`. `X-Correlation-Id` optional.

Body:

```json
{
  "revision": 7,
  "customerId": "CUSTOMER-001",
  "storeId": null,
  "items": [{"sku": "SKU-001", "quantity": 2}]
}
```

Draft được phép chưa hoàn chỉnh: `customerId`, `storeId`, `items`, và các field của item có thể null/empty. `revision` phải >= 1. Không tra Catalog và không gọi Saga/Inventory/Payment. Snapshot giữ nguyên thứ tự và dòng item; không gộp hoặc sắp xếp.

Response thành công `200 OK` gồm `draftId`, `revision`, `outcome` (`CREATED`, `UPDATED`, `REPLAYED`), `savedAt`, `correlationId`, và snapshot đã lưu.

### GET `/api/v1/order-drafts/{draftId}`

Header bắt buộc: `X-Client-Id`. Trả snapshot hiện tại hoặc `404 ORDER_DRAFT_NOT_FOUND`.

## Acceptance criteria

- **AC-01:** Lần lưu đầu với revision >= 1 tạo đúng một draft và GET đọc lại nguyên snapshot.
- **AC-02:** Revision lớn hơn thay thế snapshot cũ, kể cả khi revision không liên tiếp.
- **AC-03:** Revision nhỏ hơn trả `409 STALE_DRAFT_REVISION`; record không đổi.
- **AC-04:** Cùng revision và cùng snapshot trả `REPLAYED`, không đổi `savedAt`; cùng revision khác snapshot trả `409 DRAFT_REVISION_CONFLICT`.
- **AC-05:** Khi request đồng thời cạnh tranh trên cùng draft, record cuối có revision lớn nhất và đúng payload của revision đó; `409` là kết quả nghiệp vụ, không phải lỗi hệ thống.

## NFR

- **NFR-01 Atomicity:** Phân xử revision bằng conditional PostgreSQL write trong transaction; không dùng read-then-write không guard.
- **NFR-02 Durability:** Draft đã commit đọc lại được từ PostgreSQL, không phụ thuộc state JVM.
- **NFR-03 Load contract:** 150 tác vụ đồng thời, p95 <= 4.0 giây, không có 5xx; revision cuối là lớn nhất. p95 <= 2.0 giây chỉ là stretch goal.

## Invariant

Với mỗi `(clientId, draftId)`:

```text
storedRevision(t+1) >= storedRevision(t)
storedRevision không đổi => payloadHash không đổi
```

## Revision decision table

| Trạng thái hiện tại | Request | Kết quả | Thay đổi DB |
|---|---|---|---|
| Chưa có | revision >= 1 | CREATED | Insert |
| revision = r | request > r | UPDATED | Replace snapshot |
| revision = r, cùng hash | request = r | REPLAYED | Không đổi |
| revision = r, khác hash | request = r | 409 DRAFT_REVISION_CONFLICT | Không đổi |
| revision = r | request < r | 409 STALE_DRAFT_REVISION | Không đổi |

