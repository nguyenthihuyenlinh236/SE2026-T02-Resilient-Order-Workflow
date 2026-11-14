# Resilient Order Workflow – VT-006

Repository gồm Order, Inventory và Payment Spring Boot services; VT-006 bổ sung vertical slice lưu Order/Saga/RESERVE intent rồi gọi Inventory Reserve bất đồng bộ. Worker **tắt mặc định** vì expiry/late-Reserve coordination chưa được giải quyết đầy đủ.

## Yêu cầu môi trường

- JDK 17 (một số lần kiểm thử ghi nhận JVM runtime 21.0.2; project compile target Java 17).
- Maven 3.9+.
- Docker Desktop/Engine để chạy Testcontainers cho integration/API tests.
- Maven tải dependencies khai báo trong POM khi chạy lần đầu; không cần cài dependency riêng bằng tay.
- Integration tests dùng PostgreSQL 16 Testcontainers và HTTP fixture cục bộ; không cần khởi động PostgreSQL, RabbitMQ hay Inventory service riêng cho các test được liệt kê.

Các lệnh test đã được chạy trên Windows bằng Maven IntelliJ IDEA 2023.3.3. Thiết lập executable/repository local trong PowerShell:

~~~powershell
$mvn = 'D:\IntelliJ IDEA Community Edition 2023.3.3\plugins\maven\lib\maven3\bin\mvn.cmd'
$repo = 'C:\Users\fah4i\.m2\repository'
~~~

Nếu cài Maven ở vị trí khác, đổi giá trị $mvn sang executable Maven của máy đó.

## Unit tests

Đã chạy OrderService unit tests: 4 tests, 0 failures, 0 errors.

~~~powershell
& $mvn "-Dmaven.repo.local=$repo" -pl order-service '-Dtest=OrderServiceTests' test
~~~

## Integration/API và vertical-slice tests

Docker phải hoạt động để Testcontainers chạy PostgreSQL 16. Lệnh focused suite dưới đây đã chạy: 36 tests, 0 failures, 0 errors, 0 skipped.

~~~powershell
& $mvn "-Dmaven.repo.local=$repo" -pl order-service '-Dtest=OrderDispatchIntegrationTests,SagaRepositoryIntegrationTests,OrderIntegrationTests,OrderStateMachineIntegrationTests' test
~~~

T03 sau implementation:

~~~powershell
& $mvn "-Dmaven.repo.local=$repo" -pl order-service '-Dtest=OrderDispatchIntegrationTests#postDispatchesPersistedReserveIntentToInventory' test
~~~

T03 trước implementation là behavioral failure được ghi ở evidence/part3_test_first_execution.md. Hai test source hiện tại gồm OrderDispatchIntegrationTests.java và OrderIntegrationTests.java; tên test T03 đã được chuyển sang class dispatch để cách ly worker context.

## Mutation experiment

Mutation dùng test method T03 nêu trên. Experiment đã đổi mapping thật của Inventory response field status, chạy test để quan sát FAIL, khôi phục source rồi chạy clean test để xác nhận PASS. Không giữ mutation trong code. Chi tiết mutation, hash bản gốc, assertion và lệnh thực tế trong evidence/part4_mutation_testing.md.

## Coverage và performance harness

JaCoCo 0.8.12 chạy one-off; chưa được cấu hình thường trực trong POM. Lệnh, run và số liệu ở evidence/part4_coverage_flaky_review.md.

Harness performance 60 tasks, đo riêng POST acceptance và GET status:

~~~powershell
& $mvn "-Dmaven.repo.local=$repo" -pl order-service '-Dtest=OrderPerformanceHarnessTest' test
~~~

Một burst thực tế đo POST p95 1,658 ms. Harness đặt order.worker.enabled=false; không đo Inventory dispatch hoặc Order completion. Kết quả và giới hạn tại evidence/part4_performance_test.md. Không bật worker runtime trong khi expiry/late-Reserve coordination chưa được giải quyết.

## Database và service configuration

Application YAML cấu hình Order Service mặc định:

| Thành phần | Cấu hình mặc định |
|---|---|
| Order HTTP | port 8081 (ORDER_SERVICE_PORT) |
| PostgreSQL | localhost:5432, database order_db, user workflow; password mặc định workflow_local |
| RabbitMQ | localhost:5672, user workflow; password mặc định workflow_local |
| Flyway | Chạy migration khi Order Service khởi động |
| Worker Reserve | Không khởi tạo mặc định; integration test bật worker với Inventory fixture riêng |

Docker Compose khai báo PostgreSQL 16 và RabbitMQ 4-management; PostgreSQL init script tạo order_db, inventory_db, payment_db khi data volume mới. Các biến POSTGRES_*, RABBITMQ_* và service port được định nghĩa trong .env.example/application.yml. Testcontainers tự cấp PostgreSQL test database; RabbitMQ health indicator bị tắt trong các Order test liên quan. README này không ghi nhận việc khởi động Compose như một phần của các test trên.

## Evidence và báo cáo

- Specification/plan: evidence/part1_*.md.
- Test strategy: evidence/part2_test_strategy.md.
- Contract, test-first, implementation: evidence/part3_*.md.
- Mutation, coverage/flaky review, performance: evidence/part4_*.md.
- Traceability, AI Usage Log, reflection: evidence/part5_*.md.
- Báo cáo nộp bài Markdown để tự chuyển PDF: BT3_23001866_VT-006.md.

\n