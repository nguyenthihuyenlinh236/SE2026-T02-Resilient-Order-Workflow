# Bàn giao VT-005 — 2026-11-14

Đã hoàn thành triển khai và các lượt kiểm thử yêu cầu. Các thay đổi sau RED được
chia thành ba commit theo scope bên dưới; xem lịch sử Git để lấy hash cuối.
Chưa tạo PR, không merge và không sửa PDF. Nhánh bàn giao:
`feature/vt005-order-search-lananh`. Commit RED làm mốc: `40d2192`.

## Hành vi và tương thích

GET `/api/v1/orders` lọc customerId/status (AND), page mặc định 0, size 20 (1–100),
phân trang PostgreSQL và sắp xếp `created_at DESC, order_id DESC`. Tham số sai trả
400; không khớp trả 200 trang rỗng; query lỗi trả 500/503 qua handler hiện có.
Count và page dùng cùng filter, cùng transaction read-only REPEATABLE_READ.
Offset nhân bằng long để không tràn int. Không nối trực tiếp input vào SQL.

POST 202, GET-by-ID, năm trường OrderResponse và idempotency tạo đơn được giữ
nguyên, có raw-JSON contract tests độc lập DTO. Toàn bộ 16 test gốc tiếp tục pass.
Inventory/Payment/Saga không thay đổi; không sửa migration V001 hoặc thêm schema mới.

## Các file triển khai thay đổi sau RED

Đường dẫn dưới đây tương đối với `order-service/`:

| File | Lý do |
|---|---|
| src/main/java/com/example/orderworkflow/order/api/OrderController.java | Thêm collection GET và query parameters |
| src/main/java/com/example/orderworkflow/order/api/OrderDtos.java | Thêm OrderPage riêng, giữ nguyên OrderResponse cũ |
| src/main/java/com/example/orderworkflow/order/api/OrderErrorHandler.java | Query integer sai kiểu/ngoài int trả 400 theo error envelope |
| src/main/java/com/example/orderworkflow/order/service/OrderService.java | Validate bộ lọc/page, snapshot count/list, tính totalPages |
| src/main/java/com/example/orderworkflow/order/repository/OrderRepository.java | Count và query có bound parameters, AND, LIMIT/OFFSET và thứ tự cố định |
| pom.xml | JaCoCo agent/report trong verify |
| src/test/java/com/example/orderworkflow/order/OrderSearchLoadHarness.java | Harness opt-in, DB riêng 10000 đơn, 120 tác vụ, JSON số đo thô |
| scripts/vt005-load.ps1 | Script độc lập gọi harness, kiểm tra exit code Maven, hỗ trợ đường dẫn Maven/local repo |

Test RED đã commit trước: `OrderSearchFixtures.java`, `OrderSearchIntegrationTests.java`,
`OrderApiContractTests.java`. Không sửa expected values/test source này trong GREEN
hoặc mutation để làm test pass.

## File bằng chứng thay đổi hoặc tạo sau RED

Tất cả thuộc `docs/vt005-testing/`:

| File | Mục đích |
|---|---|
| README.md | Hướng dẫn chạy và liên kết bằng chứng |
| test-traceability.md | Liên kết AC1–AC5/NFC2 và kết quả cuối |
| green-summary.md | Giải thích GREEN và giới hạn |
| green.log | 29 test mới/contract pass |
| green-full.log | Toàn bộ 45 test pass sau GREEN |
| mutation-summary.md | Nhánh mutation, lỗi bị phát hiện và hash khôi phục |
| mutation.diff | Chênh lệch mutant so với GREEN đúng |
| mutation.log | T11 fail đúng expected 500 / actual 200 |
| coverage-summary.md | Phần trăm toàn module/lớp/method và nhánh còn thiếu |
| coverage.csv | Số liệu JaCoCo theo lớp |
| coverage.xml | Số liệu JaCoCo theo lớp/method/dòng |
| coverage.log | verify 45 test pass sau khôi phục mutation |
| flaky-tests.md | Ba lượt chạy lặp, hai nhóm rủi ro chính và giới hạn |
| repeat-1.log | Lượt 1: 28 pass |
| repeat-2.log | Lượt 2: 28 pass |
| repeat-3.log | Lượt 3: 28 pass |
| load-test-results.md | Số đo tải, môi trường, cách chạy, giới hạn |
| load-results.json | Metadata và đủ 600 mẫu đo tải |
| load.log | Script tải hoàn chỉnh: BUILD SUCCESS |
| review-summary.md | Bàn giao, danh sách file, quyết định và đề xuất commit/PR |

Baseline/RED log, .gitignore và red-metadata.json giữ nguyên bằng chứng lịch sử.
Raw log trong target không stage. Log bàn giao đã thay các dòng cấu hình credential
bằng marker; không dựng lại số liệu hoặc xóa failure của test.

## Lệnh và kết quả thực tế

Các lệnh Maven chạy với Maven 3.9.5, local repo C:/Users/tuyet/.m2/repository,
ngoài sandbox để truy cập Docker; lệnh ngắn tái lập:

| Lệnh | Kết quả |
|---|---|
| mvn -pl order-service "-Dtest=OrderSearchIntegrationTests,OrderApiContractTests" test | 29 pass |
| mvn -pl order-service test | 45 pass |
| mvn -pl order-service "-Dtest=OrderSearchIntegrationTests#t11DatabaseFailureMustNotBecomeSuccessfulEmptyPage" test (nhánh mutation) | 1 failure đúng hành vi sai, không lỗi môi trường |
| mvn -pl order-service verify (đã khôi phục) | 45 pass, line 208/233 = 89,27%, branch 73/98 = 74,49% |
| mvn -pl order-service "-Dtest=OrderIntegrationTests#concurrentRequestsWithSameKeyCreateOneOrder,OrderSearchIntegrationTests" "-Djacoco.skip=true" test (3 lượt) | 28 pass/lượt, tổng 84 |
| ./order-service/scripts/vt005-load.ps1 | 1 harness pass; 600 request, 0 lỗi, p50 428,783 ms, p95 689,191 ms, p99 896,299 ms |
| git diff --check | Pass cho thay đổi tracked; log nguyên bản có thể có whitespace từ Maven |

Tổng số lượt trên không phải số test case duy nhất. Không cộng chúng thành một
suite mới. Coverage run và load run là hai lần khác nhau; load bỏ JaCoCo.

## Kiểm tra mutation và Git

Service hiện tại có cùng SHA256 với bản GREEN lưu trước mutation; xem
mutation-summary.md. Không có catch DataAccessException trả OrderPage rỗng trong
production. Nhánh mutation tạm vẫn local để truy vết tên; không commit/push mutant.
`git diff --stat` cuối cho tracked files (đường dẫn rút gọn):

```text
README.md                  | 39
test-traceability.md        | 23
pom.xml                    | 19
OrderController.java       | 11
OrderDtos.java             |  2
OrderErrorHandler.java     |  8
OrderRepository.java       | 31
OrderService.java          | 20
8 files changed, 149 insertions(+), 4 deletions(-)
```

`git diff --stat` không bao gồm file untracked; danh sách đầy đủ nằm ở hai bảng
trên. Dùng `git status --short` và `git ls-files --others --exclude-standard`
để đối chiếu trước khi stage. Diffstat cuối thực tế được trình bày khi bàn giao.

## Assumptions và rủi ro còn lại

- Filter status chính xác và phân biệt hoa/thường theo bảy trạng thái DB; không tự
  bổ sung trạng thái của Payment/Saga. customerId dùng identifier validator hiện có.
- `customerId` là bộ lọc, không thay thế authentication/authorization. Chưa có
  consumer contract bên ngoài; đây là provider-side JSON characterization.
- Snapshot chỉ nhất quán trong một response. Offset pages ở request khác có thể
  dịch chuyển khi có insert/update. Không cam kết snapshot xuyên nhiều request.
- Chưa cần migration/index để đạt scenario local đã đo. Deep offset, dữ liệu lớn,
  sustained load và tải mạng thực cần đo riêng trước tối ưu schema.
- Các nhánh infrastructure 503, idempotency snapshot hỏng và một số nhánh create
  chưa cover; xem coverage-summary.md. Ba lượt pass chưa chứng minh hết race.
- Chỉ thử một semantic mutation; không tuyên bố mutation score toàn ứng dụng.

## Phân chia commit và đề xuất PR

Có thể tách commit theo scope, giữ nguyên RED đã push:

1. `feat(order): triển khai tìm kiếm đơn hàng VT-005 và bằng chứng GREEN`
2. `test(order): bổ sung JaCoCo và bằng chứng mutation, chạy lặp`
3. `perf(order): thêm tải 120 tác vụ và tổng hợp bằng chứng VT-005`

PR base `dev`, head `feature/vt005-order-search-lananh`.
Title: `feat(order): tìm kiếm đơn hàng có lọc và phân trang VT-005`.
Description: thêm GET collection, bound SQL + thứ tự cố định, giữ API cũ;
45 test pass, mutation T11 phát hiện, coverage và số đo tải như trên. Nêu rõ
giới hạn offset khi có ghi đồng thời, contract phía provider và local burst load.
