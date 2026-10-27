# APP-08 – Resilient Order Workflow: Business Analysis

Tài liệu phân tích nghiệp vụ cho hệ thống mô phỏng quy trình xử lý đơn hàng phân tán, tập trung vào Saga Pattern, Outbox Pattern, idempotency và Order Expiration / TTL.

## Danh mục tài liệu

| File | Nội dung |
|---|---|
| [01-business-requirements.md](01-business-requirements.md) | Tài liệu nghiệp vụ ngắn: mục tiêu, phạm vi, tác nhân, luồng tổng quan |
| [02-functional-requirements.md](02-functional-requirements.md) | Danh sách yêu cầu chức năng FR-01 đến FR-15 |
| [03-business-rules.md](03-business-rules.md) | Quy tắc nghiệp vụ BR-01 đến BR-13 |
| [04-failure-scenarios.md](04-failure-scenarios.md) | Danh sách tình huống lỗi EC-01 đến EC-11 |
| [05-use-cases.md](05-use-cases.md) | Use case tổng quát UC-01 đến UC-06 |
| [06-design-decisions.md](06-design-decisions.md) | Quyết định thiết kế D-01 đến D-08 và các câu hỏi cần xác nhận |
| [07-non-functional-requirements.md](07-non-functional-requirements.md) | Yêu cầu phi chức năng, ngưỡng đo và ma trận phân loại nguồn |
| [08-detailed-use-cases.md](08-detailed-use-cases.md) | Đặc tả chi tiết UC-01 đến UC-11 và ma trận truy vết |

## Tài liệu kiến trúc liên quan

- [State Machine](../architecture/09-state-machine.md)
- [Saga Workflow and Compensation](../architecture/10-saga-workflow-and-compensation.md)
- [Sequence Diagrams](../architecture/11-sequence-diagrams.md)
- [Acceptance Criteria](../architecture/12-acceptance-criteria.md)
- [API and Event Contract Draft](../architecture/14-api-event-contract-draft.md)

## Phạm vi

- Order Service
- Inventory Service
- Payment Service (giả lập)
- Notification Service
- TTL Worker / Expiration Scheduler (có thể triển khai bên trong Order Service)

## Quy ước nghiệp vụ chính

- TTL mặc định đề xuất: 15 phút kể từ thời điểm tạo đơn thành công.
- `expireAt = createdAt + TTL`.
- Đơn hết hạn không đồng nghĩa với việc bản ghi bị xóa.
- `UNKNOWN` không đồng nghĩa với `FAILED`; kết quả thanh toán chưa rõ phải được xác minh.
- Nếu đơn hết hạn và đã giữ hàng, phải giải phóng reservation trước khi coi compensation đã hoàn tất.
- Retry và phát lại sự kiện phải an toàn nhờ idempotency.
