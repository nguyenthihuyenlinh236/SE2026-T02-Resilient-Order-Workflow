# APP-08 – Resilient Order Workflow

Thư mục này chứa tài liệu phân tích nghiệp vụ và thiết kế kiến trúc cho hệ thống mô phỏng quy trình xử lý đơn hàng phân tán. Tài liệu tập trung vào khả năng phục hồi khi một thành phần gặp lỗi, điều phối Saga, bù trừ (compensation), phát sự kiện bằng Outbox, xử lý idempotency và tự động hết hạn đơn hàng.

## Bắt đầu từ đâu?

Nếu bạn mới tiếp cận dự án, nên đọc theo thứ tự:

1. [Yêu cầu nghiệp vụ](requirements/01-business-requirements.md) – mục tiêu, phạm vi, tác nhân và luồng nghiệp vụ tổng quan.
2. [Yêu cầu chức năng](requirements/02-functional-requirements.md) và [Quy tắc nghiệp vụ](requirements/03-business-rules.md) – hệ thống cần làm gì và các quy tắc phải tuân thủ.
3. [Tình huống lỗi](requirements/04-failure-scenarios.md) – các lỗi và nhánh xử lý cần xem xét.
4. [Use case tổng quát](requirements/05-use-cases.md) và [Use case chi tiết](requirements/08-detailed-use-cases.md).
5. [Các quyết định thiết kế](requirements/06-design-decisions.md) – quyết định hiện có cùng những câu hỏi còn cần xác nhận.
6. [State Machine](architecture/09-state-machine.md) và [Saga Workflow & Compensation](architecture/10-saga-workflow-and-compensation.md).
7. [Sequence Diagrams](architecture/11-sequence-diagrams.md), [Acceptance Criteria](architecture/12-acceptance-criteria.md) và [API/Event Contract Draft](architecture/14-api-event-contract-draft.md).

## Danh mục tài liệu

### Phân tích nghiệp vụ – `requirements/`

| Tài liệu | Nội dung |
|---|---|
| [README phân tích nghiệp vụ](requirements/README.md) | Mục lục tài liệu requirements và các quy ước nghiệp vụ chính |
| [01 – Business Requirements](requirements/01-business-requirements.md) | Mục tiêu, phạm vi, tác nhân và luồng tổng quan |
| [02 – Functional Requirements](requirements/02-functional-requirements.md) | Yêu cầu chức năng FR |
| [03 – Business Rules](requirements/03-business-rules.md) | Quy tắc nghiệp vụ BR |
| [04 – Failure Scenarios](requirements/04-failure-scenarios.md) | Tình huống lỗi EC |
| [05 – Use Cases](requirements/05-use-cases.md) | Use case tổng quát |
| [06 – Design Decisions](requirements/06-design-decisions.md) | Quyết định thiết kế và vấn đề đang chờ xác nhận |
| [07 – Non-Functional Requirements](requirements/07-non-functional-requirements.md) | Yêu cầu phi chức năng và tiêu chí đo lường |
| [08 – Detailed Use Cases](requirements/08-detailed-use-cases.md) | Đặc tả use case chi tiết và truy vết |

### Kiến trúc và kiểm thử – `architecture/`

| Tài liệu | Nội dung |
|---|---|
| [09 – State Machine](architecture/09-state-machine.md) | Trạng thái và chuyển trạng thái của Order/Saga |
| [10 – Saga Workflow and Compensation](architecture/10-saga-workflow-and-compensation.md) | Luồng Saga, retry và bù trừ |
| [11 – Sequence Diagrams](architecture/11-sequence-diagrams.md) | Tương tác giữa các thành phần theo từng luồng |
| [12 – Acceptance Criteria](architecture/12-acceptance-criteria.md) | Tiêu chí chấp nhận có thể kiểm chứng |
| [14 – API and Event Contract Draft](architecture/14-api-event-contract-draft.md) | Bản nháp API và event contract |

## Bối cảnh và tham số cố định

Các tham số dưới đây là ràng buộc của bài tập và cần được giữ nhất quán giữa tài liệu, cấu hình và bằng chứng kiểm thử:

- **Tải đồng thời:** 115 người dùng/tác vụ.
- **Hiệu năng:** mục tiêu p95 không quá 5,0 giây.
- **Lưu giữ dữ liệu vận hành:** 28 ngày.
- **Thời gian phản hồi change request:** 14 giờ.
- **Thuộc tính chất lượng trọng tâm:** Reliability – khả năng hoàn thành hoặc khôi phục quy trình khi một thành phần gặp lỗi.

## Phạm vi hệ thống

Các thành phần nghiệp vụ được mô tả trong tài liệu gồm:

- **Order Service:** điều phối vòng đời đơn hàng.
- **Inventory Service:** kiểm tra và giữ/giải phóng tồn kho.
- **Payment Service:** xử lý hoặc mô phỏng kết quả thanh toán.
- **Notification Service:** nhận kết quả để gửi thông báo.
- **TTL / Expiration Worker:** phát hiện đơn hết hạn và khởi động nhánh xử lý tương ứng.

Các tài liệu thiết kế sử dụng Saga và Outbox để mô tả điều phối, bù trừ và phát sự kiện; retry và xử lý lặp cần bảo đảm idempotency.

## Quy ước trạng thái quan trọng

- Trạng thái của **Order** và trạng thái của **Saga** là hai khái niệm riêng.
- `COMPENSATION_FAILED` là trạng thái của Saga, không phải trạng thái của Order.
- Nếu bù trừ vượt ngưỡng retry, Order giữ ở trạng thái xử lý bù trừ phù hợp (`COMPENSATING` hoặc `EXPIRING`), còn Saga có thể ở `COMPENSATION_FAILED`.
- `UNKNOWN` không đồng nghĩa với `FAILED`; kết quả thanh toán chưa rõ cần được xác minh.
- Đơn `EXPIRED` không có nghĩa là bản ghi đơn hàng bị xóa ngay lập tức.

## Trạng thái tài liệu và việc cần tiếp tục

Một số tài liệu, đặc biệt API/event contract và các quyết định liên quan đến thanh toán đến muộn, hoàn tiền, đối soát Saga, vẫn là bản nháp hoặc cần xác nhận. Không xem các vấn đề đang mở là quyết định đã được phê duyệt. Trước khi triển khai hoặc viết test, hãy đối chiếu [Design Decisions](requirements/06-design-decisions.md), [Acceptance Criteria](architecture/12-acceptance-criteria.md) và [API/Event Contract Draft](architecture/14-api-event-contract-draft.md).

## Quy tắc cập nhật tài liệu

- Khi thay đổi yêu cầu, cập nhật đồng bộ các tài liệu liên quan và ma trận truy vết.
- Không tự suy diễn hành vi chưa được thống nhất; ghi rõ câu hỏi cần xác nhận.
- Tiêu chí chấp nhận cần đủ cụ thể để có thể kiểm thử và liên kết với yêu cầu/tình huống lỗi tương ứng.
