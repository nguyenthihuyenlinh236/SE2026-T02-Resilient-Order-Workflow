# Business Decision Register – APP-08 Resilient Order Workflow

> **Purpose:** Tập trung các quyết định nghiệp vụ còn mở được nhắc trong `06-design-decisions.md` và `09-state-machine.md`, kèm phương án khuyến nghị, lý do, người chịu trách nhiệm, người xác nhận và kiểm thử cần có.
>
> **Trạng thái hiện tại:** Các mục dưới đây là **đề xuất để nhóm phê duyệt**, không tự nhận là quyết định đã được nhóm thống nhất. Chỉ chuyển sang `Accepted` sau khi người có trách nhiệm xác nhận trên PR/Issue hoặc trong cuộc họp nhóm. Khi được chấp thuận, cập nhật tài liệu gốc và đánh dấu ngày/người phê duyệt tại đây.

## 1. Tóm tắt quyết định cần chốt

| ID | Khuyến nghị | Người chịu trách nhiệm chính | Người cần xác nhận | Trạng thái |
|---|---|---|---|---|
| Q-01 | Giữ quy tắc hiện tại: chỉ xác nhận nếu payment được xác minh và conditional update thắng trước `expireAt`; nếu xác minh sau hạn thì không xác nhận, refund nếu đã thu tiền | BA (Người 1) | Nhóm; Dev xác nhận khả năng cung cấp timestamp | Proposed – awaiting approval |
| Q-02 | Thiết lập runbook xử lý thủ công cho Saga `RECONCILIATION_REQUIRED`, có người trực/owner, audit trail và điều kiện đóng rõ ràng | BA (Người 1) soạn; Dev (Người 2) triển khai | Dev + BA | Proposed – awaiting approval |
| Q-03 | Giữ reservation trong khi payment là `UNKNOWN`; cảnh báo và đối soát liên tục, không tự release chỉ vì TTL | BA (Người 1) | Nhóm; Dev + Tester xác nhận khả thi/kiểm thử | Proposed – awaiting approval |
| Q-07 | Không đổi trạng thái Order `EXPIRED`; quản lý refund đến muộn bằng tác vụ compensation bền vững, có thể liên kết Saga cũ nhưng không cần mở lại Saga `COMPLETED` | Dev (Người 2) thiết kế; BA cập nhật quy tắc | Dev + BA | Proposed – awaiting approval |
| Q-08 | Refund đã xác minh `FAILED` phải chuyển sang xử lý thủ công; chỉ cho phép key `v2` sau khi xác minh lần refund cũ không thể hoàn tất và có phê duyệt rõ ràng | BA (Người 1) | Dev + BA | Proposed – awaiting approval |

## 2. Q-01 – Payment hoàn tất trước hạn nhưng chỉ xác minh sau hạn

**Vấn đề:** Payment Service có thể thực hiện capture trước `expireAt`, nhưng phản hồi/đối soát chỉ xác nhận `SUCCEEDED` sau hạn. Chọn thời điểm hoàn tất thực tế hay thời điểm xác minh sẽ ảnh hưởng fairness với khách hàng và độ tin cậy của timestamp.

**Phương án khuyến nghị:** Giữ quy tắc hiện tại trong D-01: Order chỉ được `CONFIRMED` nếu trạng thái vẫn `PENDING`, reservation đã `RESERVED`, payment đã được xác minh `SUCCEEDED`, và conditional update dùng DB UTC clock với `now() < expireAt` thắng. Nếu xác minh thành công sau hạn, Order không được xác nhận; xử lý nhánh hết hạn và refund nếu tiền đã thu.

**Lý do:** Không phụ thuộc timestamp do payment provider bên ngoài cung cấp hoặc độ lệch đồng hồ; phù hợp state machine hiện tại và dễ kiểm thử. Nhược điểm là có thể bất lợi cho người dùng khi payment thực tế hoàn tất đúng hạn nhưng phản hồi trễ.

**Cần xác nhận:**
- Nhóm/giảng viên: chấp nhận ưu tiên tính xác định và nhất quán hơn tính công bằng theo thời điểm capture thực tế?
- Dev (Người 2): Payment Service có lưu timestamp capture đáng tin cậy, bất biến và truy vấn được không? Nếu có, nhóm có muốn đánh giá phương án thay thế không?
- Tester (Người 3): kiểm thử capture trước hạn nhưng xác minh sau hạn, cùng với race giữa TTL và confirm.

**Tài liệu bị ảnh hưởng:** `06-design-decisions.md` D-01.1/D-01.4, `09-state-machine.md` T-01/SM-21, BR-13.5, FR-09, FR-15, AC liên quan hết hạn.

## 3. Q-02 – Can thiệp thủ công khi Saga ở RECONCILIATION_REQUIRED

**Vấn đề:** Sau lịch đối soát tự động, kết quả có thể vẫn chưa rõ hoặc refund đã thất bại được xác minh. Không có runbook sẽ khiến tác vụ bị bỏ quên hoặc có người retry sai cách.

**Phương án khuyến nghị:** Có hàng đợi tác vụ cần can thiệp và runbook tối thiểu:
1. Tạo cảnh báo/ticket gắn `orderId`, `sagaId`, operation, idempotency key, thời điểm thử gần nhất, lỗi cuối và liên kết audit.
2. Owner vận hành được chỉ định bởi nhóm/giảng viên chịu trách nhiệm tiếp nhận; không tự mặc định một cá nhân ngoài phân công hiện hành.
3. Người xử lý kiểm tra trạng thái cuối từ Payment/Inventory bằng API tra cứu chính thức; không suy ra kết quả từ timeout hoặc log thiếu bằng chứng.
4. Không tạo capture/refund/release mới hoặc đổi key khi trạng thái cũ còn `UNKNOWN`.
5. Chỉ đóng tác vụ khi capture/refund/reservation đã xác minh và Order/Saga/Outbox nhất quán; nếu không thể xác minh, giữ mở và escalte theo quy trình được nhóm thống nhất.
6. Ghi người xử lý, bằng chứng, quyết định, thời điểm và mã giao dịch; không ghi credential hoặc dữ liệu nhạy cảm.

**Lý do:** Tạo ownership, khả năng kiểm toán và tránh tác dụng phụ tài chính/tồn kho do xử lý thủ công thiếu an toàn.

**Cần xác nhận:** Dev (Người 2) chốt nơi lưu/hiển thị queue và khả năng query; BA (Người 1) chốt runbook/điều kiện đóng; nhóm chỉ định vai trò chịu trách nhiệm trực/nhận cảnh báo; Tester (Người 3) kiểm thử tác vụ không bị mất sau restart.

**Tài liệu bị ảnh hưởng:** D-04, `09-state-machine.md` Saga states S-05/S-06/S-13, NFR-05, FR-13/FR-14.

## 4. Q-03 – Reservation khi payment UNKNOWN kéo dài

**Vấn đề:** Giữ reservation có thể làm tồn kho bị khóa lâu; release khi payment chưa rõ có thể khiến khách bị thu tiền nhưng mất hàng/đơn bị hủy.

**Phương án khuyến nghị:** Giữ reservation cho đến khi payment được xác minh. Khi vượt lịch đối soát chính, chuyển Saga sang `RECONCILIATION_REQUIRED`, phát cảnh báo và tiếp tục truy vấn nền mỗi 5 phút theo D-04. Không tự release, refund hoặc chốt `EXPIRED` chỉ vì TTL đã qua. Có thể escalation thủ công theo Q-02, nhưng người xử lý vẫn phải có bằng chứng trạng thái payment trước khi thực hiện side effect.

**Lý do:** Ưu tiên tránh thu tiền không có đơn hoặc giải phóng hàng trong khi payment có thể đã thành công. Đánh đổi: reservation có thể bị giữ lâu và làm giảm lượng hàng khả dụng; cần giám sát và cảnh báo để tránh bị bỏ quên.

**Cần xác nhận:** Nhóm chấp nhận đánh đổi tồn kho; Dev xác nhận khả năng truy vấn payment/reservation và giám sát; Tester xác minh không release/refund trong toàn bộ khoảng `UNKNOWN`.

**Tài liệu bị ảnh hưởng:** D-04, BR-07, BR-13, `09-state-machine.md` S-05/S-06, NFR-05, EC-03/EC-04/EC-11.

## 5. Q-07 – Refund muộn sau khi Saga đã COMPLETED và Order EXPIRED

**Vấn đề:** Capture có thể được phát hiện thành công sau khi Order đã `EXPIRED`. Order không được mở lại, nhưng cần theo dõi refund bền vững. Chuyển Saga `COMPLETED → COMPENSATING` làm mờ ý nghĩa trạng thái kết thúc.

**Phương án khuyến nghị:** Giữ Order ở `EXPIRED` vĩnh viễn và **không mở lại Saga `COMPLETED`**. Tạo/duy trì một bản ghi tác vụ compensation/refund riêng có liên kết tới `orderId`, `sagaId`, `paymentId` và khóa idempotency; tác vụ có trạng thái riêng như `PENDING`, `IN_PROGRESS`, `UNKNOWN`, `SUCCEEDED`, `FAILED_REQUIRES_REVIEW`. Đây là tác vụ hậu xử lý, không thay đổi kết quả Order đã chốt.

**Lý do:** Giữ invariant rằng `COMPLETED` là terminal của Saga; giúp retry và audit refund muộn mà không diễn giải lại lịch sử Saga. Trạng thái tác vụ mới cần Dev thiết kế để không tạo một mô hình compensation song song thiếu kiểm soát.

**Cần xác nhận:** Dev (Người 2) đánh giá schema/migration và recovery; BA xác nhận trạng thái/tên nghiệp vụ; Tester kiểm thử capture đến muộn, refund timeout, restart và duplicate. Nếu nhóm muốn giữ mô hình chuyển Saga `COMPLETED → COMPENSATING` hiện có, cần ghi rõ ngoại lệ và bất biến để tránh hiểu `COMPLETED` là terminal tuyệt đối.

**Tài liệu bị ảnh hưởng:** D-05.4, A-17, `09-state-machine.md` mục 4.2/4.3/4.6/S-11, `10-saga-workflow-and-compensation.md`, `14-api-event-contract-draft.md`.

## 6. Q-08 – Refund được xác minh FAILED

**Vấn đề:** Một refund đã được xác minh `FAILED` không giống timeout/`UNKNOWN`. Dùng lại cùng idempotency key có thể chỉ trả lại kết quả thất bại đã lưu; tạo key mới có thể tạo refund trùng nếu trạng thái provider không được xác minh kỹ.

**Phương án khuyến nghị:** Chuyển sang `RECONCILIATION_REQUIRED`/manual review, không tự động sinh key `v2`. Chỉ cho phép một lệnh refund mới với key phiên bản mới khi đồng thời:
1. Payment provider xác nhận refund trước đó thất bại cuối cùng và chưa tạo hoàn tiền;
2. trạng thái capture và số tiền còn phải hoàn được xác minh;
3. có quyết định được ghi audit của người có thẩm quyền;
4. lệnh mới có reference tới refund trước đó và không vượt quá tổng số tiền đã capture.

Nếu provider không thể chứng minh refund cũ chưa được thực hiện, không gửi lệnh mới; tiếp tục đối soát/escalate.

**Lý do:** Ngăn hoàn tiền hai lần; tách lỗi xác định khỏi kết quả chưa rõ; vẫn cho phép khôi phục có kiểm soát khi provider xác nhận refund thất bại cuối cùng.

**Cần xác nhận:** Dev (Người 2) xác nhận semantics của mock/provider, unique key và kiểm soát tổng refund; BA xác nhận policy vận hành; Tester kiểm thử refund `FAILED`, `UNKNOWN`, duplicate và key v2 sau phê duyệt.

**Tài liệu bị ảnh hưởng:** D-02, D-05, `09-state-machine.md` S-13, R-04/R-07, FR-08, BR-08.

## 7. Ownership cho các quyết định kỹ thuật còn lại

Các quyết định bên dưới không thể được BA tự xác nhận chỉ từ tài liệu. Chúng phải có owner và bằng chứng chốt trước khi triển khai:

| Nhóm quyết định | Owner | Bằng chứng cần ghi nhận |
|---|---|---|
| Canonical state/event names, service ownership, DB schema và lưu bản kết quả đã xác minh | Dev – Người 2 | Comment/approval trong PR, cập nhật state machine và schema |
| Claim token, lease, DB clock, worker recovery, Outbox/Inbox và broker/transport | Dev – Người 2 | Thiết kế kỹ thuật, migration/config, contract/integration tests |
| Capture/refund/reservation query và idempotency semantics | Dev – Người 2 | Interface/contract tests mô phỏng timeout và duplicate |
| Kiểm thử race, UNKNOWN kéo dài, restart, late payment/refund và duplicate event | Tester – Người 3 | Test cases + kết quả chạy và bằng chứng |
| Chính sách chấp nhận Q-01/Q-03 và ownership vận hành Q-02 | BA – Người 1 điều phối; cả nhóm/giảng viên phê duyệt | Quyết định được ghi trên PR/issue hoặc biên bản nhóm |

## 8. Điều kiện đóng Issue #3

- [x] Có phương án đề xuất, lý do, rủi ro và tài liệu bị ảnh hưởng cho Q-01, Q-02, Q-03, Q-07, Q-08.
- [x] Có người chịu trách nhiệm và người cần xác nhận cho từng quyết định.
- [x] Các quyết định chưa được nhóm xác nhận được đánh dấu rõ, không giả vờ là quyết định cuối.
- [ ] Nhóm/giảng viên xác nhận các lựa chọn nghiệp vụ Q-01/Q-03 và owner của Q-02.
- [ ] Dev xác nhận Q-07/Q-08 cùng các giả định kỹ thuật liên quan.
- [ ] Sau khi có xác nhận, cập nhật đồng bộ D-01/D-02/D-04/D-05, state machine, Saga workflow, API/event contract và acceptance tests; ghi người/ngày phê duyệt.

**Kết luận:** Bản đăng ký này hoàn tất phần BA đề xuất và phân công. Không nên đóng Issue #3 cho đến khi các xác nhận được ghi nhận và tài liệu nguồn được đồng bộ, vì các quyết định Q-01/Q-02/Q-03/Q-07/Q-08 ảnh hưởng trực tiếp đến tính đúng đắn của Saga, thanh toán và tồn kho.
