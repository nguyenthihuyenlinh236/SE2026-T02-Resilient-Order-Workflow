# BT3 – APP-08 – VT-006: AI Usage Log

Phạm vi ghi nhận: các trao đổi Codex trong lịch sử công việc của bài và artifacts tương ứng trong `evidence/`. AI không phải nguồn xác nhận test; lệnh và log Maven/JaCoCo là bằng chứng kết quả.

| STT | Công cụ AI | Prompt chính | Output được sử dụng | Nội dung đã sửa/bác bỏ | Evidence |
|---:|---|---|---|---|---|
| 1 | Codex | Lập implementation plan cho async order workflow, chỉ dựa trên repo baseline; phân biệt hiện trạng, đề xuất và assumptions | Bản nháp plan để lập checklist implementation và test-first | Reviewer đối chiếu source, bỏ các giả định `taskId`, worker có sẵn, polling là bắt buộc; giữ lại rủi ro và điều kiện cần xác minh | `evidence/part1_ai_plan.md`; `evidence/part1_plan_review.md` |
| 2 | Codex | Xây dựng risk-based test strategy, phân lớp input, chọn tests theo tầng | R1–R7, equivalence partitions và test-case mapping làm kế hoạch | Các mục “new/blocked” không được báo thành PASS; test Order-expiry claim không được dùng thay cho Saga-step claim | `evidence/part2_test_strategy.md` |
| 3 | Codex | Review contract/status/Technical Plan, chuẩn bị T03 và ghi kết quả test-first | Contract review, T03 evidence, V2 decisions và execution record | Sửa expected GET rejection từ trạng thái trung gian `COMPENSATING` sang `FAILED` sau full transaction; ghi nhận failure ban đầu do thiếu Reserve request là behavioral khi fixture đã ready | `evidence/part3_order_status_contract.md`; `evidence/part3_technical_plan*.md`; `evidence/part3_test_T03.md`; `evidence/part3_test_first_execution.md` |
| 4 | Codex | Implement persisted-intent → Inventory Reserve slice với claim/fencing, public API DTO và outcome rõ ràng | `SagaRepository` claim/fence, `InventoryClient`, worker, result handler, integration tests | Không thêm CAPTURE semantics, không bật worker mặc định, không diễn giải timeout là REJECTED; để expiry/late-Reserve coordination là giới hạn | `evidence/part3_implementation.md`; source/test files được liệt kê trong record |
| 5 | Codex | Thực hiện mutation, coverage, flaky review, performance harness và ghi kết quả thực tế | Mutation experiment, one-off JaCoCo run, static flake review, 60-task local harness | Mutation được khôi phục và clean test chạy lại; tỷ lệ coverage được gắn với 36-test run; performance được giới hạn ở POST acceptance/GET khi worker tắt | `evidence/part4_mutation_testing.md`; `evidence/part4_coverage_flaky_review.md`; `evidence/part4_performance_test.md` |
| 6 | Codex | Tạo traceability, usage log, reflection, README và báo cáo Markdown từ evidence/source/test; không bịa số liệu | Các tài liệu Part 5 và `BT3_23001866_VT-006.md` để người học tự chuyển PDF | Các khoảng trống được đánh dấu NOT COVERED/giới hạn thay vì suy diễn từ test plan | `evidence/part5_traceability.md`; `evidence/part5_reflection.md`; README; báo cáo Markdown |

## Hai ví dụ cần review thay vì tin ngay

### 1. Kế hoạch ban đầu nói đến worker và “hoàn tất happy path”

Đề xuất nghe hợp lý vì repository đã có Saga, trạng thái, lease columns và API Order. Tuy nhiên, ở baseline chưa có Saga dispatcher/worker hoặc Inventory client; cột lease và finder không tự tạo atomic claim. Nếu chấp nhận bản plan như mô tả source thì sẽ báo nhầm thiết kế thành implementation. Việc kiểm tra danh sách source, repository SQL, migration và tests đã phát hiện khoảng trống. Plan review đổi phần này thành đề xuất có điều kiện; implementation sau đó thêm atomic claim, token fencing và ghi test riêng. `current_operation` vẫn không được tự đổi sang CAPTURE vì source chưa định nghĩa semantics đủ rõ.

### 2. Expected GET `COMPENSATING` sau Reserve rejection

Expectation này có vẻ hợp lý vì state machine có transition `PENDING → COMPENSATING`. Nhưng nếu handler ghi `COMPENSATING`, hoàn tất `FAILED`, Saga và Outbox trong cùng transaction thì client GET chỉ thấy trạng thái đã commit `FAILED`; `COMPENSATING` là trạng thái trung gian nội bộ. Source của `startCompensation`, `fail`, transaction boundary và UC-02-A được đối chiếu trong Technical Plan V2. Vì vậy test dispatch thực tế assert GET `FAILED`, không chờ một trạng thái trung gian không được bảo đảm quan sát.

### 3. Mapping sai vẫn có thể trông hợp lệ với JSON

Ở mutation experiment, field response thật `status` được map từ JSON property không tồn tại `status-mutation`. Code vẫn biên dịch, response record vẫn hợp lệ và failure được phân loại thành UNKNOWN nên đây không phải lỗi compile rõ ràng. Test dispatch đã phát hiện khi persisted step nhận `UNKNOWN` thay vì `SUCCEEDED`; log ghi assertion và sau đó source được khôi phục byte-for-byte. Đây là deliberate experiment có bằng chứng, không phải lỗi production còn tồn tại.

## Ranh giới xác minh

Các prompt được tóm tắt từ lịch sử bài và artifacts, không khẳng định là bản chép nguyên văn ngoài prompt đầy đủ được lưu trong `part1_ai_plan.md`. AI không tự tạo kết quả PASS: kết quả chỉ được ghi khi có lệnh/log Surefire/JaCoCo/harness tương ứng. Không có link PR, commit mới hoặc activity GitHub nào được thực hiện trong phần này.
