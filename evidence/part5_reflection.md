# BT3 – APP-08 – VT-006: Reflection

Confidence của em tăng lên khi kiểm tra được cả API, intent trong PostgreSQL, request fixture Inventory và trạng thái đã commit. Test claim/fencing còn xác nhận một worker sở hữu step trong tình huống tranh chấp được mô phỏng. Đây là bằng chứng cụ thể cho các nhánh đã chạy, chưa chứng minh toàn bộ workflow an toàn khi vận hành.

Test-first phát hiện lần chạy ban đầu không gửi Reserve dù PostgreSQL, Spring Boot, Flyway và fixture đã sẵn sàng. Assertion bắt behavioral failure, không phải lỗi biên dịch hay hạ tầng. Sau implementation, T03 thấy request từ worker. Phân loại `RESERVED`, `REJECTED` và `UNKNOWN` cũng ngăn timeout bị hiểu thành thất bại. GET chỉ thấy `FAILED` sau transaction rejection; `COMPENSATING` là trạng thái trung gian.

Mutation testing cho thấy mapping sai vẫn biên dịch: `status` đọc từ JSON property không tồn tại khiến worker nhận `UNKNOWN`. Test đã bắt được khác biệt; sau khi khôi phục file, clean test PASS. Coverage 86,42% line và 72,59% branch là số đo một lần chạy, không thay thế bằng chứng nghiệp vụ và vẫn còn nhánh chưa được kiểm tra.

Chưa được chứng minh gồm retry sau khi Inventory commit nhưng mất response, phục hồi sau restart, nhiều worker chạy lâu và race giữa expiry với Reserve. Vì vậy worker vẫn tắt mặc định. Benchmark 60 task chỉ đo POST và GET, không đo Order hoàn tất. Test tiếp theo có giá trị nhất là để Inventory lưu effect rồi làm rơi response, retry cùng key/payload, kiểm tra số reservation và tiếp tục đối soát sau restart. Evidence hiện có chưa đủ để kết luận sẵn sàng production.
