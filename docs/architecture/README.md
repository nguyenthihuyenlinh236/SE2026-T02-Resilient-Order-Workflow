# Architecture and Acceptance Criteria

Thư mục này lưu các tài liệu mô tả thiết kế kiến trúc và tiêu chí nghiệm thu cho hệ thống **APP-08 – Resilient Order Workflow**.

Tài liệu tập trung vào cách các thành phần phối hợp để xử lý đơn hàng phân tán an toàn, duy trì tính nhất quán và phục hồi khi một thành phần hoặc bước tích hợp gặp lỗi.

## Danh mục tài liệu

| File | Nội dung |
|---|---|
| [09-state-machine.md](09-state-machine.md) | Máy trạng thái Order và các điều kiện chuyển trạng thái |
| [10-saga-workflow-and-compensation.md](10-saga-workflow-and-compensation.md) | Luồng Saga, các bước xử lý, retry và compensation |
| [11-sequence-diagrams.md](11-sequence-diagrams.md) | Biểu đồ tuần tự mô tả tương tác giữa các service và các nhánh lỗi |
| [12-acceptance-criteria.md](12-acceptance-criteria.md) | Acceptance Criteria theo Given–When–Then, liên kết với Use Case và yêu cầu |

## Phạm vi kiến trúc

Các thành phần và cơ chế được mô tả trong nhóm tài liệu này gồm:

- **Order Service:** điều phối vòng đời đơn hàng và Saga.
- **Inventory Service:** kiểm tra tồn kho, tạo và giải phóng reservation.
- **Payment Service:** mô phỏng thanh toán, truy vấn kết quả và hỗ trợ đối soát.
- **Notification Service:** gửi thông báo về kết quả xử lý.
- **Saga Pattern:** điều phối quy trình nhiều bước và xử lý nhánh thất bại.
- **Outbox Pattern:** bảo đảm sự kiện được ghi nhận bền vững trước khi phát.
- **Idempotency:** ngăn tác dụng phụ lặp khi retry hoặc nhận message trùng.
- **Order Expiration / TTL:** phát hiện đơn quá hạn và kích hoạt nhánh xử lý, giải phóng reservation khi đủ điều kiện.

## Nguyên tắc đọc tài liệu

1. Đọc `09-state-machine.md` để hiểu trạng thái Order và các bất biến chuyển trạng thái.
2. Đọc `10-saga-workflow-and-compensation.md` để hiểu trình tự xử lý, retry, đối soát và bù trừ.
3. Đọc `11-sequence-diagrams.md` để theo dõi tương tác giữa các thành phần trong luồng thành công và luồng lỗi.
4. Đọc `12-acceptance-criteria.md` để kiểm tra cách các yêu cầu được chuyển thành điều kiện nghiệm thu có thể xác minh.

## Quy ước

- `UNKNOWN` không đồng nghĩa với `FAILED`; kết quả chưa xác định cần được xác minh trước khi quyết định compensation.
- Không xác nhận Order khi điều kiện nghiệp vụ bắt buộc chưa được xác minh.
- Các thao tác retry, phát lại sự kiện, release và refund phải có tính idempotent.
- So sánh thời gian nghiệp vụ sử dụng nguồn thời gian thống nhất theo quyết định thiết kế.
- Thông số vận hành và ngưỡng hiệu năng phải được cấu hình hoặc thống nhất theo bối cảnh triển khai; không coi giá trị thử nghiệm riêng là ràng buộc mặc định.

## Liên kết

- [Tài liệu yêu cầu và phân tích nghiệp vụ](../requirements/README.md)
- [README tổng quan repository](../../README.md)
