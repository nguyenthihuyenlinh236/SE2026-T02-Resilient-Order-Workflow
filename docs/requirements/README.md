# APP-08 – Resilient Order Workflow: Business Analysis

Tài liệu phân tích nghiệp vụ cho hệ thống mô phỏng quy trình xử lý đơn hàng phân tán, tập trung vào Saga Pattern, Outbox Pattern, idempotency và Order Expiration / TTL.

## Danh mục tài liệu

| File | Nội dung |
|---|---|
| [01-business-requirements.md](01-business-requirements.md) | Tài liệu nghiệp vụ ngắn: mục tiêu, phạm vi, tác nhân, luồng tổng quan |
| [02-functional-requirements.md](02-functional-requirements.md) | Danh sách yêu cầu chức năng FR-01 đến FR-15 |
| [03-business-rules.md](03-business-rules.md) | Quy tắc nghiệp vụ BR-01 đến BR-13 |
| [04-failure-scenarios.md](04-failure-scenarios.md) | Danh sách tình huống lỗi EC-01 đến EC-11 |
| [05-use-cases.md](05-use-cases.md) | Use case tổng quát, bao gồm Expire Order / TTL Cleanup |

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
