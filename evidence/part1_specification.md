# BT3 – APP-08 – VT-006: Specification

## Thông tin bài

| Trường | Giá trị |
|---|---|
| Sinh viên | Vũ Thị Hà |
| MSSV | 23001866 |
| Team | SE2026-T02 |
| Project | APP-08 – Resilient Order Workflow |
| Variant | VT-006 |
| Repository | `D:\SE2026-T02-Resilient-Order-Workflow` |
| Branch | `feature/vt006-async-progress-23001866` |
| Baseline commit | `a513241d0e71e2c0eb7adaa32198e0b3902cf766` |

**Change request:** Cho phép tác vụ dài chạy bất đồng bộ và hiển thị trạng thái tiến trình cho người dùng.

## Problem statement

Nếu xử lý một tác vụ dài trong luồng request đồng bộ, người dùng phải chờ trong khi các bước nghiệp vụ chạy; kết nối có thể timeout trước khi biết tác vụ đã được nhận hoặc hoàn tất hay chưa. Người dùng cần được xác nhận tác vụ đã được tiếp nhận, có định danh để tra cứu và nhìn thấy trạng thái được cập nhật trong khi hệ thống xử lý nền.

Retry sau timeout làm tăng rủi ro lặp tác dụng: tạo Order/Saga/step lần nữa, giữ hàng hoặc thu/hoàn tiền trùng, hay gửi thông báo lặp. Hệ thống cần giữ định danh và dữ liệu yêu cầu ổn định cho cùng một tác vụ logic, đồng thời chỉ phản ánh kết quả đã được ghi nhận/xác minh.

Repository hiện có Order API `POST /api/v1/orders` trả `202 Accepted` với `OrderResponse`; response có `orderId` và `status`, và có `GET /api/v1/orders/{orderId}` để đọc trạng thái Order. `OrderService.create` thực hiện validation, ghi Order/items, Saga và RESERVE intent trong transaction request; repository đã lưu trạng thái Saga/step. Tuy nhiên, source Order Service chưa có dispatcher/worker gọi service đích, nên các tài liệu này không khẳng định tiến trình nền đã hoạt động hoặc status hiện được cập nhật tự động. Acceptance criteria dưới đây phân biệt contract hiện có với hành vi cần bổ sung.

## Acceptance Criteria

### AC-01 — Tiếp nhận tác vụ dài

**Given** client gửi yêu cầu tạo Order hợp lệ với scope client và idempotency key hợp lệ
**When** hệ thống lưu yêu cầu thành công để xử lý
**Then** API xác nhận đã tiếp nhận trước khi các bước xử lý dài hoàn tất, trả định danh Order và trạng thái ban đầu theo HTTP/DTO contract đã công bố. Với contract hiện tại, các giá trị quan sát được là `202 Accepted`, `orderId` và `status=PENDING`; không tạo API `taskId` mới.

### AC-02 — Theo dõi trạng thái tiến trình

**Given** client đã nhận `orderId`
**When** client truy vấn trạng thái trong lúc tác vụ đang xử lý hoặc sau khi tác vụ kết thúc
**Then** phản hồi thể hiện trạng thái Order mới nhất đã được lưu bền vững cho đúng Order. Không yêu cầu phần trăm tiến độ, ETA hoặc field mới khi chưa có contract được duyệt.

### AC-03 — Replay cùng yêu cầu không tạo tác vụ logic thứ hai

**Given** một request đã được tiếp nhận trong cùng client scope
**When** client gửi lại cùng idempotency key và payload chuẩn hóa giống ban đầu
**Then** hệ thống trả lại kết quả/định danh gắn với request gốc và không tạo Order, Saga hay Saga step thứ hai.

### AC-04 — Retry không lặp business effect

**Given** service đích có thể đã thực hiện một operation nhưng phản hồi bị mất/timeout
**When** hệ thống retry operation đó
**Then** retry không được tạo thêm tác động nghiệp vụ có hiệu lực cho cùng tác vụ logic. Cơ chế retry phải tuân theo idempotency và query/reconciliation contract của operation đích; không được suy ra timeout là thất bại.

### AC-05 — Trạng thái và thông báo phù hợp kết quả đã xác minh

**Given** một bước xử lý chưa có kết quả xác định, hoặc một trạng thái Order chưa commit
**When** hệ thống cập nhật tiến trình hoặc phát thông báo
**Then** không hiển thị trạng thái cuối/ thông báo thành công hoặc thất bại trái với trạng thái đã xác minh và commit. Event được phát lại không được tạo tác dụng consumer trùng nếu consumer thuộc phạm vi triển khai.

## Non-functional constraints

1. **Tải đồng thời:** hệ thống phải được đánh giá với **60 người dùng/tác vụ đồng thời**. Cần thống nhất kịch bản đo gồm số tác vụ đang hoạt động, tỉ lệ submit/poll và thời lượng chạy trước nghiệm thu.
2. **Độ trễ tiếp nhận:** dưới kịch bản tải 60 người dùng/tác vụ đồng thời, p95 của thao tác tiếp nhận bất đồng bộ không vượt **2.0 giây**. Đo từ lúc API nhận request tới lúc trả phản hồi xác nhận đã lưu/tiếp nhận; môi trường, payload và cách tính p95 phải được cố định trong bằng chứng benchmark.
3. **Bền vững tiến trình:** định danh và trạng thái đã tiếp nhận phải có thể đọc lại sau khi service restart; không được mất tác vụ đã xác nhận. Đây là constraint phù hợp với yêu cầu phục hồi Saga và lưu trạng thái bền vững trong requirements (BR/EC), không áp đặt loại database/queue hoặc cơ chế triển khai cụ thể.

## Invariant / contract

Với cùng một phạm vi client, idempotency key và payload chuẩn hóa đại diện cùng một tác vụ logic. Retry request không tạo Order/Saga/step mới; retry một business operation không tạo tác động có hiệu lực trùng. Kết quả trạng thái hiển thị phải tương ứng với trạng thái đã được ghi nhận và xác minh.

Specification này **không** quy định cụ thể dùng polling hay broker, scheduler hay listener, claim/lease/fencing implementation, endpoint mới, tiến độ phần trăm, số lần retry/backoff, event delivery semantics hoặc consumer inbox. Các quyết định đó cần được xác minh trong implementation plan và review contract; không được trình bày như implementation hiện hữu khi repository chưa chứng minh.

## Căn cứ đã đọc

- `README.md`: mô tả ba service và lưu ý README khởi đầu còn có phần hướng dẫn skeleton; đối chiếu với code hiện tại, không dùng nội dung cũ làm bằng chứng code chưa có.
- `docs/requirements/01-business-requirements.md`: mục tiêu ngăn giữ hàng/thu tiền lặp do retry, trạng thái bền vững và thông báo dựa trên trạng thái nghiệp vụ đã xác nhận.
- `docs/requirements/02-functional-requirements.md`: FR-01, FR-06, FR-10, FR-11, FR-13, FR-14.
- `docs/requirements/03-business-rules.md`: BR-03, BR-09, BR-10, BR-11, BR-12 và quy tắc idempotency.
- `docs/requirements/04-failure-scenarios.md`: EC-04, EC-06, EC-07, EC-08, EC-10.
- `docs/requirements/05-use-cases.md`: UC-01 và quy tắc chung về retry/trạng thái trung gian.
- `docs/architecture/README.md`, `docs/architecture/12-acceptance-criteria.md`.
- Order API/source/tests: `OrderController`, `OrderDtos`, `OrderService`, `OrderRepository`, `SagaRepository`, `OrderStateMachine`, `OrderIntegrationTests`, `OrderStateMachineIntegrationTests`.
- Các file `docs/phan1.md`, `docs/test_strategy.md` và `docs/spec_and_plan.md` có trong working tree tại thời điểm thực hiện nhưng là untracked/local artifacts, không phải files được theo dõi ở baseline.
