
# 12. Acceptance Criteria – Resilient Order Workflow

## 1. Mục đích

Tài liệu xác định các tiêu chí nghiệm thu có thể kiểm thử cho những chức năng chính của hệ thống Resilient Order Workflow.

Các tiêu chí được viết theo hướng:
- Có điều kiện đầu vào rõ ràng.
- Có hành động hoặc sự kiện kích hoạt.
- Có kết quả mong đợi có thể quan sát và xác minh.
- Bao phủ luồng thành công, thất bại, timeout, retry và phục hồi.
- Hỗ trợ Người 3 xây dựng test case và Người 2 kiểm tra hành vi triển khai.

Các tiêu chí này bổ sung cho Use Case, Business Rules, State Machine và Saga Workflow; không thay thế các tài liệu đó.

## 2. Quy ước

- `Order`: trạng thái nghiệp vụ của đơn hàng.
- `Saga`: tiến trình điều phối các bước đặt hàng và bù trừ.
- `Reservation`: bản ghi giữ hàng tại Inventory Service.
- `Capture`: giao dịch thu tiền.
- `Refund`: giao dịch hoàn tiền, có trạng thái độc lập với Capture.
- `UNKNOWN`: chưa xác minh được kết quả; không đồng nghĩa với `FAILED`.
- `expireAt`: thời điểm hết hạn được tính từ `createdAt + TTL`.
- Thời gian nghiệp vụ được so sánh bằng đồng hồ DB UTC.
- Các hành động có tác động nghiệp vụ phải sử dụng idempotency key ổn định.

## 3. AC-01 – Tạo đơn hàng

### AC-01.1 – Tạo Order hợp lệ

**Given**
- Khách hàng gửi yêu cầu đặt hàng hợp lệ.
- Yêu cầu chưa được xử lý trước đó.

**When**
- Order Service tiếp nhận yêu cầu.

**Then**
- Hệ thống tạo một Order ở trạng thái `PENDING`.
- Hệ thống lưu `createdAt` và `expireAt`.
- `expireAt` được tính bằng `createdAt + TTL`.
- Hệ thống lưu thông tin cần thiết để khôi phục tiến trình Saga.
- API trả về `orderId` và trạng thái `PENDING` sau khi Order được lưu thành công.

### AC-01.2 – Trả kết quả khi yêu cầu trùng

**Given**
- Một yêu cầu đặt hàng đã được xử lý và gắn với idempotency key.
- Khách hàng gửi lại cùng yêu cầu.

**When**
- Order Service nhận yêu cầu trùng.

**Then**
- Hệ thống không tạo thêm Order cho cùng một thao tác logic.
- Hệ thống trả lại `orderId` và trạng thái hiện tại của Order đã tồn tại.
- Hệ thống không tạo thêm chuỗi capture chỉ vì khách hàng gửi lại yêu cầu.

### AC-01.3 – Không tạo Order khi lưu thất bại

**Given**
- Order Service không thể commit việc tạo Order.

**When**
- Hệ thống xử lý yêu cầu đặt hàng.

**Then**
- Hệ thống không trả về xác nhận rằng Order đã được tạo thành công.
- Nếu kết quả commit chưa rõ, hệ thống phải tra cứu theo khóa yêu cầu ổn định trước khi thử tạo lại.
- Hệ thống không được tạo trùng Order do retry.

## 4. AC-02 – Giữ hàng thành công

### AC-02.1 – Reservation thành công

**Given**
- Order đang ở `PENDING`.
- Sản phẩm có đủ tồn kho.
- Yêu cầu reserve hợp lệ.

**When**
- Order Service yêu cầu Inventory Service giữ hàng.

**Then**
- Inventory Service trả kết quả `RESERVED`.
- Reservation được liên kết với Order tương ứng.
- Saga chỉ tiếp tục sang bước thanh toán sau khi kết quả reserve được xác minh.
- Retry cùng hành động sử dụng khóa `reserve:{orderId}:v1`.

### AC-02.2 – Không đủ hàng

**Given**
- Order đang ở `PENDING`.
- Inventory Service xác định không đủ tồn kho.

**When**
- Yêu cầu reserve được xử lý.

**Then**
- Inventory Service trả `REJECTED`.
- Order Service nhận nhánh thất bại bằng chuyển trạng thái có điều kiện `PENDING -> COMPENSATING`.
- Nếu chưa có Reservation và chưa capture tiền, hệ thống không thực hiện release hoặc refund không cần thiết.
- Sau khi xác minh không còn compensation bắt buộc, Order chuyển sang `FAILED`.
- Hệ thống ghi sự kiện nghiệp vụ tương ứng vào Outbox cùng transaction với thay đổi trạng thái Order.

### AC-02.3 – Reserve timeout

**Given**
- Yêu cầu reserve đã được gửi.
- Order Service không nhận được phản hồi rõ ràng.

**When**
- Yêu cầu reserve timeout.

**Then**
- Hệ thống không mặc định Reservation đã thất bại.
- Hệ thống truy vấn Reservation bằng khóa `reserve:{orderId}:v1`.
- Hệ thống chỉ tiếp tục theo kết quả đã xác minh.
- Không tạo Reservation trùng khi thử lại.

## 5. AC-03 – Thanh toán thành công

### AC-03.1 – Capture thành công trước hạn

**Given**
- Order đang ở `PENDING`.
- Reservation được xác minh là `RESERVED`.
- Capture được xác minh là `SUCCEEDED`.
- DB UTC time nhỏ hơn `expireAt`.

**When**
- Order Service đánh giá điều kiện xác nhận.

**Then**
- Order chuyển sang `CONFIRMED` bằng cập nhật có điều kiện.
- Nếu cập nhật trạng thái và ghi `OrderConfirmed` vào Outbox cùng một bước commit, hai thao tác phải thuộc cùng transaction DB.
- Hệ thống không phát `OrderConfirmed` trước khi transaction commit.
- Notification Service chỉ gửi thông báo dựa trên trạng thái đã commit.

### AC-03.2 – Hai nhánh xử lý cạnh tranh

**Given**
- Order đang ở `PENDING`.
- Hai worker đồng thời cố gắng nhận nhánh xác nhận và nhánh hết hạn hoặc thất bại.

**When**
- Hai worker thực hiện cập nhật có điều kiện.

**Then**
- Chỉ một nhánh nhận được quyền chuyển trạng thái.
- Nhánh còn lại không được ghi đè trạng thái đã được commit.
- Order không thể vừa `CONFIRMED` vừa `EXPIRED`.
- Hệ thống đọc lại trạng thái hiện tại và xử lý theo nhánh hợp lệ.

## 6. AC-04 – Thanh toán thất bại

### AC-04.1 – Capture thất bại đã xác minh

**Given**
- Order đang ở `PENDING`.
- Reservation đang `RESERVED`.
- Payment Service trả về `FAILED` đã xác minh.

**When**
- Order Service nhận kết quả thanh toán.

**Then**
- Order chuyển qua `COMPENSATING` bằng cập nhật có điều kiện.
- Saga yêu cầu Inventory Service release Reservation.
- Hệ thống chỉ kết thúc nhánh thất bại sau khi xác minh các compensation bắt buộc đã hoàn tất.
- Order chuyển sang `FAILED` khi đủ điều kiện.
- Không refund nếu capture chưa từng thành công.

### AC-04.2 – Release thất bại hoặc chưa rõ

**Given**
- Order đang ở `COMPENSATING`.
- Yêu cầu release thất bại hoặc kết quả chưa xác định.

**When**
- Saga thực hiện retry.

**Then**
- Hệ thống sử dụng cùng khóa `inventory:release:{orderId}:{reservationId}:v1` cho cùng một hành động release.
- Hệ thống tra cứu kết quả nếu cần để tránh release trùng.
- Hệ thống không kết thúc nhánh thất bại khi Reservation vẫn chưa được giải quyết.
- Khi vượt 5 lần gửi, trạng thái xử lý bù trừ là `COMPENSATION_FAILED`.
- Hệ thống tiếp tục thử lại nền mỗi 60 giây.

## 7. AC-05 – Thanh toán timeout và UNKNOWN

### AC-05.1 – Capture timeout

**Given**
- Order đã có Reservation được xác minh.
- Yêu cầu capture đã được gửi.
- Payment Service không trả về kết quả rõ ràng.

**When**
- Capture timeout.

**Then**
- Capture status được lưu là `UNKNOWN` nếu chưa có kết quả xác minh.
- Saga chuyển sang `RECONCILING`.
- Hệ thống không tự chuyển Payment sang `FAILED`.
- Hệ thống không release Reservation hoặc refund chỉ dựa trên timeout.
- Hệ thống không tạo yêu cầu capture mới với khóa khác để né kết quả chưa rõ.

### AC-05.2 – Đối soát tìm thấy capture thành công

**Given**
- Capture đang `UNKNOWN`.
- Reconciliation Worker truy vấn được kết quả `SUCCEEDED`.

**When**
- Kết quả được xác minh và lưu lại.

**Then**
- Capture status chuyển sang `SUCCEEDED`.
- Orchestrator đánh giá lại điều kiện chuyển trạng thái Order.
- Chỉ xác nhận Order nếu Order vẫn `PENDING`, DB UTC time nhỏ hơn `expireAt` và các điều kiện nghiệp vụ khác được thỏa mãn.
- Nếu nhánh hết hạn hoặc thất bại đã nhận quyền xử lý, hệ thống không ghi đè trạng thái đó.

### AC-05.3 – Đối soát tìm thấy capture thất bại

**Given**
- Capture đang `UNKNOWN`.
- Reconciliation Worker xác minh capture `FAILED`.

**When**
- Kết quả được lưu lại.

**Then**
- Hệ thống nhận nhánh thất bại nếu Order vẫn `PENDING`.
- Order chuyển qua `COMPENSATING`.
- Các compensation cần thiết được thực hiện theo trạng thái thực tế của Reservation.
- Không refund giao dịch chưa capture thành công.

### AC-05.4 – Hết lịch đối soát chính

**Given**
- Capture vẫn `UNKNOWN`.
- Lịch đối soát chính đã thực hiện tối đa 30 lần truy vấn trong khoảng 10 phút.

**When**
- Không xác minh được kết quả cuối cùng.

**Then**
- Saga chuyển sang `RECONCILIATION_REQUIRED`.
- Hệ thống tiếp tục polling nền mỗi 5 phút.
- Không tự kết luận Payment `FAILED`.
- Không release Reservation, refund hoặc hoàn tất `EXPIRED` khi điều kiện an toàn chưa được thỏa mãn.
- Không gia hạn `expireAt` chỉ vì đối soát kéo dài.

## 8. AC-06 – Hết hạn đơn hàng

### AC-06.1 – Nhận nhánh hết hạn

**Given**
- Order đang ở `PENDING`.
- DB UTC time đã đến hoặc vượt `expireAt`.
- Không có kết quả Payment chưa xác định cần giải quyết trước khi nhận nhánh hết hạn.

**When**
- TTL Worker quét Order.

**Then**
- Worker nhận xử lý bằng cập nhật có điều kiện `PENDING -> EXPIRING`.
- `claimToken`, `claimedAt` và `leaseUntil` được lưu trong cùng thao tác nhận quyền.
- Nếu worker khác đã nhận nhánh xử lý, cập nhật của worker hiện tại thất bại và không được ghi đè.
- TTL Worker tiếp tục quét theo chu kỳ mặc định 5 giây.

### AC-06.2 – Reservation chưa được xác minh

**Given**
- Order đã vào `EXPIRING`.
- Trạng thái Reservation chưa rõ.

**When**
- TTL Worker chuẩn bị giải phóng hàng.

**Then**
- Worker truy vấn Reservation bằng khóa `reserve:{orderId}:v1`.
- Nếu Reservation đã `RESERVED`, worker yêu cầu release.
- Nếu Reservation không tồn tại hoặc đã `RELEASED`, worker không gửi release không cần thiết.
- Nếu trạng thái vẫn chưa rõ, worker không hoàn tất `EXPIRED`.

### AC-06.3 – Hoàn tất hết hạn

**Given**
- Order đang ở `EXPIRING`.
- Nhánh hết hạn vẫn có quyền xử lý.
- Không còn giao dịch capture chưa được giải quyết.
- Reservation được xác minh không tồn tại hoặc đã `RELEASED`.
- Nếu đã capture tiền, refund được xác minh hoàn tất.

**When**
- TTL Worker hoàn tất quy trình hết hạn.

**Then**
- Order chuyển sang `EXPIRED`.
- Saga được đánh dấu hoàn tất.
- Sự kiện `OrderExpired` được ghi vào Outbox trong cùng transaction DB với cập nhật trạng thái.
- Nếu bất kỳ điều kiện bắt buộc nào chưa đạt, Order không được kết thúc ở `EXPIRED`.

### AC-06.4 – Worker bị gián đoạn

**Given**
- Worker đã nhận xử lý Order và lưu lease.
- Worker chết trước khi hoàn thành.

**When**
- `leaseUntil` đã hết hạn.

**Then**
- Worker khác có thể tiếp quản.
- Hệ thống tiếp tục từ trạng thái đã lưu.
- Các hành động đã thực hiện được kiểm tra bằng idempotency key trước khi gửi lại.
- Không tạo thêm capture, Reservation, release hoặc refund chỉ vì worker được khởi động lại.

## 9. AC-07 – Refund

### AC-07.1 – Refund sau capture thành công

**Given**
- Capture được xác minh là `SUCCEEDED`.
- Quy trình nghiệp vụ yêu cầu hoàn tiền.

**When**
- Saga yêu cầu refund.

**Then**
- Payment Service nhận khóa `payment:refund:{orderId}:{paymentId}:v1`.
- Refund status được theo dõi độc lập với capture status.
- Order không được kết thúc ở `EXPIRED` nếu refund bắt buộc chưa được xác minh hoàn tất.
- Việc gửi lại cùng refund request không tạo nhiều tác động refund ngoài ý muốn.

### AC-07.2 – Refund chưa rõ

**Given**
- Capture đã `SUCCEEDED`.
- Refund đang `UNKNOWN`.

**When**
- Payment Service không trả về kết quả refund rõ ràng.

**Then**
- Capture status vẫn là `SUCCEEDED`.
- Refund status được giữ là `UNKNOWN`.
- Hệ thống đối soát refund hoặc thử lại theo chính sách tương ứng.
- Nếu vượt ngưỡng gửi, đánh dấu `COMPENSATION_FAILED` và tiếp tục retry nền mỗi 60 giây.
- Không kết luận refund thành công khi chưa được xác minh.

## 10. AC-08 – Outbox và thông báo

### AC-08.1 – Ghi sự kiện nhất quán

**Given**
- Order cần chuyển sang trạng thái nghiệp vụ mới và phát domain event tương ứng.

**When**
- Order Service commit thay đổi trạng thái.

**Then**
- Cập nhật trạng thái và ghi sự kiện Outbox thuộc cùng transaction DB.
- Nếu transaction rollback, không được tồn tại sự kiện nghiệp vụ đã commit mà không có thay đổi trạng thái tương ứng.
- Publisher chỉ phát các sự kiện đã được lưu bền vững.

### AC-08.2 – Publisher phát lại sự kiện

**Given**
- Publisher đã phát sự kiện nhưng gặp sự cố trước khi đánh dấu đã gửi.

**When**
- Publisher khởi động lại và phát lại sự kiện.

**Then**
- Consumer chống xử lý trùng bằng `UNIQUE(consumerName, eventId)`.
- Hệ thống không tạo tác động nghiệp vụ trùng do cùng một event được nhận nhiều lần.
- Cơ chế gửi thông báo được mô tả là at-least-once; không cam kết exactly-once tuyệt đối với hệ thống bên ngoài.

### AC-08.3 – Thông báo phản ánh trạng thái thật

**Given**
- Notification Service nhận sự kiện nghiệp vụ đã commit.

**When**
- Hệ thống gửi thông báo cho khách hàng.

**Then**
- Nội dung thông báo phản ánh trạng thái Order đã commit.
- Không thông báo xác nhận đơn thành công khi Order chưa `CONFIRMED`.
- Không tạo domain event mới chỉ để ghi nhận một lần retry kỹ thuật.

## 11. AC-09 – Retry và phục hồi

### AC-09.1 – Khởi động lại service

**Given**
- Service, Publisher hoặc TTL Worker dừng khi còn công việc chưa hoàn tất.

**When**
- Thành phần khởi động lại.

**Then**
- Hệ thống phục hồi tiến trình từ trạng thái đã lưu.
- Các Saga chưa hoàn tất được tiếp tục.
- Outbox chưa phát được tiếp tục xử lý.
- Order hết hạn chưa được nhận xử lý được quét lại.
- Order `EXPIRING` có lease hết hạn được tiếp quản.
- Các công việc đối soát và bù trừ tiếp tục theo chính sách đã cấu hình.
- Mục tiêu là tiếp tục xử lý trong vòng không quá 30 giây sau khi khởi động lại.

### AC-09.2 – Tiếp quản sau lỗi worker

**Given**
- Worker chết sau khi nhận lease nhưng trước khi hoàn thành release.
- Lease mặc định là 30 giây và chu kỳ scan là 5 giây.

**When**
- Worker khác phát hiện lease hết hạn.

**Then**
- Worker mới tiếp quản công việc.
- Mục tiêu tiếp quản là không quá 45 giây theo cấu hình lease, scan và phần dự phòng.
- Hệ thống không thực hiện release trùng gây tác động nghiệp vụ lặp.
- Trạng thái Order chỉ được cập nhật khi đủ điều kiện.

## 12. AC-10 – Tính nhất quán và tải đồng thời

### AC-10.1 – Không vượt tồn kho

**Given**
- Có tối đa 155 người dùng hoạt động đồng thời.
- Nhiều yêu cầu đặt cùng sản phẩm.

**When**
- Các yêu cầu cùng cố gắng giữ hàng.

**Then**
- Hệ thống không xác nhận số lượng Reservation vượt quá tồn kho có thể bán.
- Tồn kho được cập nhật theo cơ chế bảo đảm tính nhất quán của Inventory Service.
- Các yêu cầu không giữ được hàng phải đi vào nhánh thất bại phù hợp.

### AC-10.2 – Không thanh toán trùng

**Given**
- Cùng một Order được gửi capture nhiều lần do retry hoặc mất phản hồi.

**When**
- Payment Service nhận ít nhất 10 yêu cầu capture có cùng idempotency key.

**Then**
- Chỉ một lần capture có hiệu lực.
- Các yêu cầu lặp trả về hoặc tra cứu cùng kết quả giao dịch.
- Không phát sinh giao dịch thu tiền thứ hai cho cùng thao tác nghiệp vụ.

### AC-10.3 – Không có hai trạng thái kết thúc mâu thuẫn

**Given**
- Các worker xác nhận, thất bại và hết hạn chạy đồng thời.

**When**
- Các worker cập nhật cùng Order.

**Then**
- Chỉ một nhánh nhận quyền chuyển trạng thái từ `PENDING`.
- Order không thể vừa `CONFIRMED` vừa `EXPIRED`.
- Order đã ở `FAILED` hoặc `EXPIRED` không được chuyển lại `CONFIRMED`.

## 13. AC-11 – Khả năng cấu hình và kiểm thử

### AC-11.1 – Cấu hình thời gian

**Given**
- Môi trường kiểm thử cần điều chỉnh TTL hoặc chu kỳ worker.

**When**
- Người có trách nhiệm cấu hình thay đổi tham số.

**Then**
- TTL, scan interval, lease và chính sách retry/đối soát có thể được cấu hình theo thiết kế.
- Giá trị mặc định là TTL 15 phút, scan 5 giây và lease 30 giây.
- Mọi phép so sánh thời gian nghiệp vụ vẫn sử dụng DB UTC.
- Thay đổi cấu hình không tự động gia hạn `expireAt` của Order đã tạo.

### AC-11.2 – Fault injection

**Given**
- Môi trường kiểm thử có cấu hình fault injection.

**When**
- Kịch bản kiểm thử yêu cầu mô phỏng lỗi.

**Then**
- Có thể kiểm thử các điểm lỗi như trước commit, sau commit nhưng trước phản hồi, sau publish nhưng trước đánh dấu, hoặc sau claim nhưng trước release.
- Fault injection mặc định tắt ngoài môi trường được cấu hình cho kiểm thử.
- Sau khi fault injection kết thúc, hệ thống có thể phục hồi các công việc bị gián đoạn.

## 14. Liên kết yêu cầu

| Nhóm tiêu chí | Use Case liên quan | Tài liệu đối chiếu |
|---|---|---|
| Tạo Order và gửi yêu cầu | UC-01 | Functional Requirements, Business Rules |
| Giữ hàng và hết hàng | UC-02 | State Machine, Saga Workflow |
| Capture thành công/thất bại | UC-03 | State Machine, Saga Workflow |
| Compensation và refund | UC-04, UC-11 | Business Rules, Saga Workflow |
| Notification và Outbox | UC-05 | Functional Requirements, Saga Workflow |
| TTL và hết hạn | UC-06 | State Machine, Business Rules |
| Đối soát Payment | UC-07 | Design Decisions, Saga Workflow |
| Khôi phục sau sự cố | UC-08 | Non-functional Requirements |
| Trạng thái đơn hàng | UC-09 | State Machine |
| Idempotency | UC-10 | Design Decisions |
| Thanh toán muộn và refund | UC-11 | State Machine, Saga Workflow |

Các mã yêu cầu cụ thể phải được đối chiếu với danh mục FR, BR, EC, D và NFR trong thư mục `docs/requirements/` trước khi dùng làm tiêu chí nghiệm thu chính thức.

## 15. Điều kiện hoàn thành

- Mỗi chức năng chính có tiêu chí nghiệm thu với điều kiện đầu vào và kết quả mong đợi.
- Có tiêu chí cho luồng thành công, hết hàng, thanh toán thất bại, timeout, retry và hết hạn.
- Các tiêu chí không coi `UNKNOWN` là `FAILED`.
- Các tiêu chí phân biệt capture và refund.
- Các tiêu chí phù hợp với State Machine, Saga Workflow và Use Case.
- Người 3 có thể chuyển tiêu chí thành test case mà không phải tự đoán hành vi nghiệp vụ.
- Các ngưỡng cấu hình và mục tiêu hiệu năng cần được xác nhận với nhóm trước khi dùng làm kết quả nghiệm thu cuối cùng.
