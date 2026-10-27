# Resilient Order Workflow

Kho tài liệu phân tích yêu cầu và thiết kế cho hệ thống xử lý đơn hàng có khả năng phục hồi, tập trung vào Saga, Outbox, idempotency và hết hạn đơn hàng (TTL).

## Tài liệu yêu cầu

- [Tổng quan phân tích nghiệp vụ](docs/requirements/README.md)
- [Yêu cầu nghiệp vụ](docs/requirements/01-business-requirements.md)
- [Yêu cầu chức năng](docs/requirements/02-functional-requirements.md)
- [Quy tắc nghiệp vụ](docs/requirements/03-business-rules.md)
- [Tình huống lỗi](docs/requirements/04-failure-scenarios.md)
- [Use case tổng quan](docs/requirements/05-use-cases.md)
- [Quyết định thiết kế](docs/requirements/06-design-decisions.md)
- [Yêu cầu phi chức năng](docs/requirements/07-non-functional-requirements.md)
- [Use case chi tiết](docs/requirements/08-detailed-use-cases.md)

## Tài liệu kiến trúc

- [Máy trạng thái](docs/architecture/09-state-machine.md)
- [Luồng Saga và bù trừ](docs/architecture/10-saga-workflow-and-compensation.md)
- [Biểu đồ tuần tự](docs/architecture/11-sequence-diagrams.md)
- [Tiêu chí nghiệm thu](docs/architecture/12-acceptance-criteria.md)

## Phạm vi kho mã

Phiên bản hiện tại chỉ lưu tài liệu yêu cầu và thiết kế. Mã nguồn ứng dụng, kiểm thử, script kiểm thử tải và cấu hình build/chạy đã được gỡ bỏ. Các mô tả kỹ thuật trong tài liệu thể hiện thiết kế và yêu cầu của hệ thống.
