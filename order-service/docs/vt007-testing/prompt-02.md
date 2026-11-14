# VT-007 — Prompt 02: sửa plan, tạo evidence RED → GREEN trong một lượt

Đây là nhiệm vụ tiếp nối plan VT-007 đã lập. Tối ưu thời gian/token: làm việc trong repo, báo cáo ngắn các đường dẫn file, lệnh và SHA; không in log dài lên chat. **Không làm mutation, load test hay AI review patch trong lượt này** — sẽ có lượt riêng sau khi chạy test.

**Review của người thực hiện (phải ghi lại vào `docs/vt007-testing/plan-review.md`):**

1. **Bác bỏ A-08:** ngưỡng bắt buộc đề VT-007 là **150 tác vụ đồng thời, p95 ≤ 4.0 giây**, KHÔNG phải 2 giây; 2 giây chỉ là stretch goal không bắt buộc.
2. **Sửa A-05:** Auto-save Draft được phép chưa hoàn chỉnh; không dùng `CreateOrderRequest` làm constraint bắt buộc vì file `OrderDtos.java` có `@NotBlank` customerId/storeId và `@NotEmpty items`, dành cho Order thật. Dùng DTO/payload riêng, không ép validate Catalog/Reserve/Payment.
3. **Sửa test strategy:** trước code cần `unit / integration-API / E2E` và nêu rõ vì sao full frontend E2E chưa cần (`frontend/README.md` nói frontend chỉ là placeholder); Decision Table và ≥10 test cases có đúng 6 cột `ID, risk/AC, level, input/precondition, expected, priority`, ≥2 negative và ≥1 failure-path. Mỗi test expected xuất phát từ specification, không từ code.
4. **RED evidence:** test-only commit phải **compile và chạy**, fail đúng lý do chưa có API/behavior (ví dụ status 404 thay vì expected). Không chỉ nộp compile error do import class chưa tồn tại.
5. **Concurrency/load contract:** stale/revision conflict `409` là kết quả nghiệp vụ dự kiến, không được tính là `5xx`. Mutation/later load phải xác nhận revision cuối lớn nhất. Tách `X-Client-Id` demo scope với thực tế authentication. Canonicalization của draft không được tự ý gộp/sắp item khiến mất snapshot.

**Thực hiện trong lượt này theo thứ tự nghiêm ngặt:**

A. Kiểm tra HEAD `origin/dev` mới nhất và nhánh `feature/vt007-autosave-testfirst` đã có. Lưu SHA gốc của nhánh trước khi fast-forward; cập nhật nhánh này từ `dev` bằng fast-forward nếu sạch và hợp lệ. Không tạo branch mới, không sửa `dev` và không đụng Issue #27.

B. Lưu specification/AC/NFR/invariant, test strategy, Decision Table ≥10 case, AI plan review có evidence repository, và prompt này thành file; commit riêng phần specification trước code.

C. Viết focused HTTP/integration tests từ requirements trước implementation; tạo commit **test-only**, chạy thực tế và lưu log RED kèm nguyên nhân fail. Chỉ dùng source đã tồn tại để test compile; không tạo implementation trước RED. Nếu test không chạy vì môi trường, ghi blocker trung thực và chỉ tiếp tục khi đủ evidence.

D. Triển khai vertical slice nhỏ nhất PUT/GET Order Draft độc lập, migration V006 sau V001–V005, JDBC atomic conditional update/upsert; bảo vệ revision monotonic, same-revision replay, conflict hash, concurrency, transaction. Không sửa các schema V001–V005, Saga, Inventory, Payment, frontend. Test dùng PostgreSQL Testcontainers thật.

E. Chạy focused GREEN sau patch, sửa đúng lỗi nếu có; commit implementation riêng, lưu log, diff và SHA. Chạy `mvn -B clean verify` nếu môi trường cho phép; bổ sung README chạy tests và đối chiếu AC. Nếu thêm V006 nhớ cập nhật test kiểm tra số migration (hiện OrderDemoProfileTests kỳ vọng 5).

F. Push nhánh cá nhân, **không tự merge**. Có thể tạo DRAFT PR vào `dev` để kích hoạt CI nếu đầy đủ evidence (không cần reviewer/merge ngay). Báo cáo ngắn: baseline SHA, specification commit, RED test-only commit + log, GREEN implementation commit + log, số test pass/fail thật, CI/PR URL, các hạn chế và đường dẫn evidence. **Tuyệt đối không tuyên bố PASS nếu chưa chạy.**
