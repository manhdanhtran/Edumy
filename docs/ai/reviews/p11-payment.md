# Review P11 — commerce thật (thanh toán, đơn hàng, đối soát, bộ hẹn giờ)

Ngày: 2026-11-15 · Phạm vi: `3c856d3~1..026ab12` (P11-a → P11-f) · Chỉ đọc, không sửa `src/`.
Đối chiếu: ADR-004, ADR-010, ADR-011; SRS UC-S04/S05/S16/A18, AC-01/02/03/10/11/18/19/21..24, NFR-04/08/09.

## Kết luận

Không có BLOCKER. Có 3 MAJOR và 4 MINOR.

| Mã | Mức | Tóm tắt |
|---|---|---|
| P11-R-01 | MAJOR | Câu claim "QR hết hạn" khóa **mọi** đơn ứng viên chứ không phải 1 đơn |
| P11-R-02 | MAJOR | Hai kết quả T2 đồng thời của hai đơn khác nhau cùng học viên, trùng khóa học: cả hai đơn thành `paid`, không vào đối soát (AC-24) |
| P11-R-03 | MAJOR | 6/8 test "đồng thời" tương đương chạy tuần tự; nhiều kịch bản race chưa có test |
| P11-R-04 | MINOR | T1 kiểm tra sở hữu trước khi khóa đơn chờ → có thể tạo đơn chứa khóa đã sở hữu |
| P11-R-05 | MINOR | Lần thanh toán `pending` bị treo vĩnh viễn trên đơn đã `paid` |
| P11-R-06 | MINOR | Timeout: connect 5 s + read 5 s = tx-timeout 10 s; trang chi tiết đối soát gọi cổng trong transaction |
| P11-R-07 | MINOR | Log `e.getMessage()` của cổng thanh toán; log lỗi bộ hẹn giờ không có stack trace |

Cách kiểm: đọc toàn bộ code service/repository/entity/scheduler/cổng giả lập của commerce và `EnrollmentApiImpl`.
P11-R-01 và hành vi khóa của P11-R-04 được kiểm bằng thực nghiệm trên MySQL 8.4 tạm (schema V1–V4, READ COMMITTED,
hai phiên), không phải bằng bộ test của dự án. P11-R-02 và P11-R-05 suy ra từ code, **chưa tái hiện bằng test**.
Bộ test của dự án không được chạy trong lần review này.

---

## Phát hiện

### P11-R-01 — MAJOR — Claim "QR hết hạn" khóa mọi đơn ứng viên
`commerce/repository/OrderRepository.java:79-85` (`claimOrderWithExpiredQr`), dùng tại
`commerce/service/CommerceExpiryService.java:75-81`.

`ORDER BY p.qr_expires_at, o.id` sắp theo cột của hai bảng nên MySQL phải đọc hết các dòng khớp rồi mới sắp xếp
và `LIMIT 1`. InnoDB khóa từng dòng `orders` khi đọc, nên mọi đơn ứng viên bị khóa đến hết transaction, tức là
suốt lần gọi cổng thanh toán.

Thực nghiệm (4 đơn chờ có QR hết hạn, phiên A chạy câu claim rồi giữ transaction):

| Quan sát | Claim QR hết hạn (hiện tại) | Claim đơn quá 24 h |
|---|---|---|
| Khóa bản ghi `orders` phiên A giữ | đơn 1, 2, 3, 4 | đơn 1 |
| Phiên B (instance thứ hai) claim được | không có gì | đơn 2 |
| `SELECT … FOR UPDATE NOWAIT` trên đơn 2, 3, 4 | lỗi 3572 (đang bị khóa) | tự do |

Kế hoạch thực thi: `Sort: p.qr_expires_at, o.id` phía trên nested loop join.

Hậu quả:
- Instance thứ hai thấy "không còn gì" và kết thúc lượt chạy. Không xử lý trùng (NFR-09 vẫn đạt), nhưng hai
  instance không chia việc được.
- Webhook, tạo QR mới, hủy đơn của **học viên khác** có QR vừa hết hạn phải chờ đến khi lần gọi cổng của bộ hẹn
  giờ xong. Tạo QR mới cho đơn có QR hết hạn chính là thao tác phổ biến nhất trên các đơn này. Với cổng chậm
  (5 s/lần) và tồn 200 đơn, mỗi vòng lặp lại khóa lại toàn bộ; request của học viên có thể chạm tx-timeout 10 s.
- ADR-011 "other students are unaffected" không đúng với job này.

Tái hiện: tạo ≥ 2 đơn chờ có lần thanh toán `pending` đã hết hạn; mở transaction READ COMMITTED chạy câu claim;
xem `performance_schema.data_locks` hoặc thử `FOR UPDATE NOWAIT` trên đơn thứ hai.

Đề xuất: sắp theo cột của một bảng để đi theo thứ tự index và dừng sớm — `ORDER BY p.qr_expires_at, p.id`.
Đã thử: kế hoạch thành index range scan trên `ix_payment_pending_expiry` không còn bước Sort, phiên A chỉ khóa
đơn 1, phiên B claim được đơn 2. Nên thêm test khẳng định phạm vi khóa (đơn thứ hai không bị khóa khi đơn thứ
nhất đang được xử lý), vì kế hoạch thực thi có thể đổi theo dữ liệu.

### P11-R-02 — MAJOR — Hai T2 đồng thời trên hai đơn của cùng học viên không được tuần tự hóa
`commerce/service/PaymentResultApplier.java:96-98` (đọc sở hữu, quyết định), `:108-110` (ghi `success`/`paid`,
cấp quyền), `:126` (`grantForPaidOrder`); `learning/service/EnrollmentApiImpl.java:47-56` (`INSERT IGNORE`).

T2 khóa đơn → lần thanh toán của **đơn đó**. Việc "học viên đã sở hữu khóa nào" là một lần đọc thường trên
`enrollments`, không có khóa nào chung giữa hai đơn khác nhau của cùng học viên.

Kịch bản:
1. Đơn cũ P0 (đã hủy/thay thế) chứa khóa A; đơn mới P1 đang chờ cũng chứa A. Học viên trả cả hai QR, hoặc cổng
   dồn webhook rồi gửi cùng lúc sau sự cố.
2. T2(P0, kết quả muộn) và T2(P1, đúng hạn) chạy song song. Cả hai đọc `owned = {}`.
3. Cả hai đi nhánh "paid". `INSERT IGNORE` của bên sau chờ bên trước commit rồi trả 0, không báo lỗi.
4. Kết cục: P0 và P1 đều `paid`, một ghi danh A, học viên trả hai lần. Không có lần thanh toán nào ở
   `needs_review`, không có log WARN (cả hai đều thấy `owned` rỗng).

Chạy tuần tự thì bên sau sẽ vào `needs_review` (muộn, AC-24 / BR-30) hoặc có WARN (đúng hạn). Race làm mất cả
hai tín hiệu; dấu vết duy nhất là `grantedCourseIds` thiếu A ở dòng log "Order paid".

Cùng cơ chế áp dụng cho T2 chạy song song với "Kích hoạt" của UC-A18 (`ReconciliationService.java:98-105`) trên
một đơn khác của cùng học viên.

Phụ: hai T2 như trên chèn ghi danh theo thứ tự dòng đơn của từng đơn. Nếu hai đơn chứa cùng hai khóa theo thứ
tự ngược nhau thì có thể deadlock trên `uq_enrollments_student_course`. MySQL hủy một bên, webhook trả 500, cổng
gửi lại và lần sau xử lý đúng — khả năng thấp, tự hồi phục.

Đề xuất (chọn một):
- Tuần tự hóa T2 theo học viên: sau khi khóa đơn và lần thanh toán, khóa thêm một dòng theo học viên (ví dụ
  `users` của học viên `FOR UPDATE`) rồi mới đọc sở hữu. Thứ bậc thành carts → orders → payment_transactions →
  khóa học viên → enrollments; không flow nào giữ khóa đó rồi mới xin khóa đơn nên không tạo vòng. Cách này cũng
  loại luôn deadlock phụ ở trên. Cần cập nhật ADR-011.
- Hoặc dùng kết quả của `grantForPaidOrder`: nếu số khóa thực cấp ít hơn số khóa "chưa sở hữu" đã đọc, coi là
  xung đột — rollback phần ghi `paid` và đưa lần thanh toán vào `needs_review`.

Kèm test xen kẽ có điều khiển (xem P11-R-03, kịch bản i).

### P11-R-03 — MAJOR — Test đồng thời phần lớn xanh giả
`src/test/java/com/edumy/support/Concurrently.java:19-34`: các luồng chờ chung một latch rồi chạy; không có rào
"đã sẵn sàng", không lặp, không có bằng chứng hai luồng thực sự chồng lên nhau. Cổng giả lập trả lời tức thì
(`FakeQrPaymentGateway.java:67-73`) nên không transaction nào giữ khóa đủ lâu.

Điểm tốt: không test nào `@Transactional`, fixture được commit trước, mỗi request chạy trên luồng và kết nối
riêng, ngoại lệ trong luồng được ném ra chứ không bị nuốt, không dùng `Thread.sleep`.

Test mà mọi assertion vẫn đúng nếu hai lời gọi chạy lần lượt:

| Test | Vị trí | Ghi chú |
|---|---|---|
| `OrderWriteIT#create_twoConcurrentRequests_…_UCS04E3` | `OrderWriteIT.java:323-335` | Giống hệt kết quả của test tuần tự `:229` |
| `OrderWriteIT#create_twoConcurrentRequestsReplacingAnOldOrder_…_AC21` | `:337-351` | Không kiểm lần thanh toán cũ thành `expired` |
| `PaymentQrIT#twoConcurrentRequests_…_BR16` | `PaymentQrIT.java:247-262` | Giống test tuần tự `:133` |
| `PaymentWebhookIT#twoConcurrentDeliveries_oneEffect_AC11` | `PaymentWebhookIT.java:211-225` | Tên nói 2, code dùng 4 luồng; AC-11 yêu cầu 10 |
| `ReconciliationIT#twoAdminsAtOnce_…_E1` | `ReconciliationIT.java:222-234` | Assertion DB `:232-233` đếm theo khóa chính, không thể vượt 1 |
| `CommerceExpiryIT#twoRunsAtTheSameTime_…_NFR09` | `CommerceExpiryIT.java:190-210` | Vẫn xanh nếu bỏ `SKIP LOCKED`; `>= 10` (`:204`) chấp nhận cả xử lý trùng; không kiểm cả hai lượt đều có việc |

`PaymentWebhookIT#sameResultTenTimes_oneEffect_AC11_E3` (`:191-209`) gửi 10 lần **tuần tự**.

Hai test thật sự ép xen kẽ, nhưng bên giữ khóa là SQL viết tay chứ không phải flow thật:
`PaymentWebhookIT#deliveryWaitingForTheOrderLock_…_R1` (`:232-256`, qua `LockWaits`) và
`CommerceExpiryIT#anOrderLockedElsewhere_…_SKIP_LOCKED` (`:212-248`).

Kịch bản chưa có test đồng thời nào:
- (b) webhook 10 lần **song song** (AC-11, NFR-04).
- (c) webhook đua với T1 thay đơn; (d) với hủy đơn; (e) với tạo QR.
- (f) hai lượt `expireQrAttempts` song song — job này không có test hai instance nào, và đó chính là job có lỗi
  P11-R-01.
- (g) bộ hẹn giờ đua với webhook / request thật của học viên.
- (i) kết quả muộn của đơn cũ và kết quả đúng hạn của đơn mới, trùng khóa học, song song (P11-R-02).
- (j) một đơn lỗi không chặn các đơn khác trong cùng lượt; nhánh lỗi chung và nhánh commit lỗi của
  `CommerceExpiryService.java:112-118`, `:154-158`; `expireQrAttempts` khi cổng sập.

Đề xuất:
- Thêm hook chặn vào cổng giả lập (latch trong `query` / `createQr`) để giữ một flow thật đứng giữa lần gọi
  cổng trong khi đang giữ khóa, rồi thả flow thứ hai và chờ `LOCK WAIT` như `LockWaits` đang làm. Đây là điều
  kiện để viết được (c), (d), (e), (g), (i).
- Với các test dựa vào may rủi: lặp nhiều vòng và thêm assertion chứng minh có chồng lấn (ví dụ cả hai lượt
  scheduler đều `processed >= 1`, tổng đúng bằng 10).
- Sửa assertion yếu: `ReconciliationIT:232-233` nên kiểm `handled_by` / `handled_action` khớp admin nhận 200,
  trạng thái đơn và ghi danh khớp hành động thắng, đúng một mail.

### P11-R-04 — MINOR — T1 kiểm tra sở hữu trước khi khóa đơn chờ
`commerce/service/OrderService.java:122` (`revalidate`, đọc sở hữu) đứng trước `:128`
(`findPendingByStudentIdForUpdate`); đơn mới được lập ở `:144-146` từ `lines` đã tính trước khi chờ khóa.

Kịch bản: học viên có đơn chờ P1 chứa A, bấm "thanh toán" lần nữa đúng lúc webhook của P1 về.
1. T1 khóa giỏ, đọc `owned = {}`, rồi chờ khóa P1 do T2 giữ.
2. T2 ghi P1 `paid`, cấp A, xóa A khỏi giỏ, commit.
3. T1 được khóa; P1 không còn `pending` nên câu truy vấn trả rỗng (đã kiểm thực nghiệm dưới READ COMMITTED).
4. T1 lập đơn P2 chứa A — khóa vừa mua, không còn trong giỏ — và trả 201.

Biến thể không có đơn chờ nào (kết quả muộn của đơn đã hủy được chấp nhận theo AC-23 giữa lúc T1 đang chạy)
cho cùng kết quả.

Không mất tiền: tạo QR từ chối đơn này (R3 b, `PaymentService.java:122-131`, hủy `replaced`, 409
`ORDER_CART_CHANGED`). Nhưng vi phạm UC-S04 bước 1 trong chốc lát và trả 201 cho một đơn vô nghĩa. Đây không
phải lỗi R1 theo nghĩa hẹp (không có entity cũ), mà là giá trị đọc trước khi chờ khóa được dùng sau khóa.

Đề xuất: khóa đơn chờ ngay sau khóa giỏ, rồi mới `revalidate` và `checkExpected` (thứ bậc khóa không đổi), hoặc
đọc lại sở hữu và các dòng giỏ sau khi có khóa đơn. Biến thể thứ hai chỉ đóng được hẳn bằng khóa theo học viên
ở P11-R-02.

### P11-R-05 — MINOR — Lần thanh toán `pending` treo trên đơn đã `paid`
`commerce/service/PaymentResultApplier.java:108-109`; `commerce/service/ReconciliationService.java:101-104`;
`commerce/repository/OrderRepository.java:81` (claim đòi `o.status = 'pending'`);
`commerce/service/PaymentQueryService.java:68`.

Kịch bản: QR #1 hết hạn, tra cứu "chưa trả" → `expired`. Học viên tạo QR #2 (`pending`). Kết quả thành công muộn
của #1 về: đơn còn chờ, chưa sở hữu gì → đơn `paid` (đúng theo A3). Lần #2 vẫn `pending`. Tương tự khi admin
"Kích hoạt" một lần `needs_review` trong khi đơn có lần `pending` khác.

Hậu quả: không flow nào đóng #2 nữa — bộ hẹn giờ chỉ claim đơn `pending`. `GET …/payments/current` ưu tiên lần
`pending` nên giao diện tiếp tục hiện QR chờ thanh toán cho một đơn đã trả. Nếu học viên trả #2 thì T2 đưa vào
`needs_review` (đúng), nhưng đó là lần trả thừa do giao diện mời.

Đề xuất: bỏ điều kiện `o.status = 'pending'` trong `claimOrderWithExpiredQr` để bộ hẹn giờ vẫn tra cứu và đóng
lần đó (trả rồi → `needs_review` do đơn đã `paid`; chưa trả → `expired`); và cho `payments/current` trả lần
`success` khi đơn đã `paid`.

### P11-R-06 — MINOR — Timeout
- `commerce/config/PaymentProperties.java:29-31`: `connectTimeout` 5 s + `readTimeout` 5 s = `txTimeout` 10 s.
  Chú thích `:19-20` nói lời gọi "kết thúc hẳn bên trong txTimeout", nhưng trường hợp xấu nhất dùng hết 10 s và
  không còn thời gian để ghi kết quả. Timeout transaction của Spring chỉ được kiểm ở câu lệnh SQL kế tiếp, nên
  lời gọi cổng bị treo vẫn giữ khóa đơn đến khi HTTP timeout. Đề xuất: ràng buộc `connect + read < txTimeout`
  khi khởi động, hoặc hạ mặc định (ví dụ 2 s + 4 s).
- `commerce/service/PaymentQueryService.java:37` (`@Transactional(readOnly = true)` cấp lớp) và `:122-128`:
  `getAdminDetail` gọi `gateway.query` trong transaction, giữ một kết nối trong pool suốt lần gọi, không có
  tx-timeout. Không giữ khóa nào. Đề xuất: gọi cổng ngoài transaction.

### P11-R-07 — MINOR — Log (NFR-08)
- `PaymentService.java:158-159`, `:168`; `OrderService.java:272`; `CommerceExpiryService.java:151-152` ghi
  `e.getMessage()` của `PaymentGatewayException`. Với cổng giả lập thì vô hại. Adapter thật (payOS) thường đưa
  URL, tham số hoặc thân phản hồi vào message. Đề xuất: quy định trong hợp đồng `PaymentGateway` rằng message
  chỉ chứa mã lỗi đã làm sạch, hoặc chỉ log tên lớp + mã lỗi.
- `CommerceExpiryService.java:112-118`, `:154-158` chỉ log tên lớp ngoại lệ, không stack trace, không message.
  Một đơn lỗi lặp lại mỗi 5 phút sẽ rất khó chẩn đoán. Đề xuất: log kèm stack trace ở mức ERROR.

---

## Đã kiểm và không có phát hiện

### 1. R1 — từng chỗ "đọc rồi khóa"
`open-in-view: false` (`application.yml:16`) và mọi flow chạy trong `TransactionTemplate` riêng, nên không có
entity nào được mang từ ngoài vào transaction.

| Flow | Đọc trước khóa | Khóa / lần nạp entity đầu tiên | Kết luận |
|---|---|---|---|
| T1 tạo đơn | không có entity | `OrderService.java:117` giỏ `FOR UPDATE`; dòng giỏ nạp lazy sau khóa | Đạt |
| T1 đơn chờ | sở hữu, giá (giá trị, `:122`) | `:128` đơn chờ `FOR UPDATE`, nạp lần đầu | Entity đạt; giá trị cũ → P11-R-04 |
| Hủy đơn | id vô hướng `:252` | `:254` | Đạt |
| Tạo QR | id vô hướng `PaymentService.java:107` | `:109` đơn, `:132` lần thanh toán | Đạt |
| Tạo QR → `settle` | — | `PendingPaymentSettler.java:59` truy vấn lại lần thanh toán đã nạp ở `:132` | Đạt: dòng đã bị khóa bởi chính transaction này từ lần nạp đầu |
| T2 webhook | id vô hướng `PaymentService.java:205` | `:211` đơn, `:213` lần thanh toán | Đạt |
| T2 dòng giỏ | id giỏ vô hướng `PaymentResultApplier.java:128` | xóa native, không nạp entity | Đạt |
| Đối soát | id vô hướng `ReconciliationService.java:87` | `:88` đơn, `:89` lần thanh toán | Đạt |
| Bộ hẹn giờ | id vô hướng từ câu claim | `CommerceExpiryService.java:145` | Đạt |

Không có chỗ nào cần `refresh(entity, PESSIMISTIC_WRITE)`.

### 2. Thứ bậc khóa
- Mọi flow đi đúng carts → orders → payment_transactions → enrollments / cart_items; không flow nào khóa ngược.
- T2 không khóa `carts`; T1 chỉ đọc thường các dòng giỏ, nên T1 (giỏ → đơn) và T2 (đơn → dòng giỏ) không tạo vòng.
- `INSERT IGNORE` vào `enrollments` lấy khóa chia sẻ trên dòng cha (`users`, `courses`, `orders`) do khóa ngoại.
  Không tìm thấy flow nào giữ các dòng đó rồi xin khóa đơn hàng, nên chỉ có chờ, không có vòng.
- Nguy cơ deadlock duy nhất tìm thấy là trường hợp phụ ở P11-R-02.

### 3. Idempotency T2 và kết quả muộn
- Lặp cùng kết quả: các lần sau chờ khóa đơn rồi thấy `success` / `needs_review` → bỏ qua (AC-11, E3).
- Thất bại sau thành công: bỏ qua. Thành công sau `failed` / `expired`: xử lý như kết quả muộn.
- Sai số tiền hoặc sai mã đơn → `needs_review`, không cấp quyền (AC-19).
- Muộn + chưa sở hữu khóa nào → `paid` (AC-23); muộn + đã sở hữu bất kỳ khóa nào hoặc đơn đã `paid` →
  `needs_review` (AC-24). Đúng khi chạy tuần tự; ngoại lệ khi song song là P11-R-02.
- "Commit rồi mới 409": T1 (`OrderService.java:108-111`), hủy (`:244-247`), tạo QR
  (`PaymentService.java:99-102`) đều trả kết quả ra khỏi transaction rồi mới ném lỗi. Commit hỏng thì ngoại lệ
  của commit được ném, không có 409 sai.
- Cổng sập: `PaymentGatewayException` làm rollback toàn bộ, trả 503, không đổi gì.
- Ràng buộc `ck_payment_*` khớp các chuyển trạng thái trong entity.

### 4. Bộ hẹn giờ
- `seen` rỗng: giá trị canh `-1` (`CommerceExpiryService.java:163-169`), không sinh `NOT IN ()`.
- Lỗi cổng hoặc lỗi khác: rollback riêng đơn đó, đơn vào `seen`, lượt chạy tiếp tục, thử lại ở lượt sau.
  `setRollbackOnly` cục bộ nên không phát sinh `UnexpectedRollbackException`.
- Lỗi ngay ở câu claim: ném ra, kết thúc lượt, lượt sau chạy lại.
- Hai instance: không xử lý trùng ở cả hai job. Job đơn quá 24 h chia việc đúng (thực nghiệm: A khóa đơn 1, B
  claim đơn 2). Job QR hết hạn → P11-R-01.
- Giới hạn 200 đơn mỗi lượt; phần còn lại chờ lượt sau.

### 6. Log (NFR-08)
- Không có email, mật khẩu, khóa bí mật, chữ ký hay thân webhook thô trong log mới của P11.
- Giá trị lấy từ webhook đi qua `LogSanitizer.clean` (`PaymentService.java:193-195`, `:207-208`).
- Chữ ký sai: chỉ log "invalid or missing signature". So sánh chữ ký bằng `MessageDigest.isEqual`.
- Lỗi gửi mail: chỉ log mã đơn và tên lớp ngoại lệ (`CommerceMailListener.java:53-57`).
- `transactionRef`, `orderCode`, `studentId`, số tiền, id khóa học có trong log — đây là mã tham chiếu NFR-08
  yêu cầu, không phải dữ liệu bí mật.
- Lưu ý còn lại: P11-R-07.

---

## Trạng thái sau P11-fix (2026-11-16)

| Mã | Trạng thái | Commit (code / docs) | Bằng chứng |
|---|---|---|---|
| P11-R-01 | Đã sửa | `6372d3d` / `5714aab` | `ORDER BY p.qr_expires_at, p.id`; `ClaimQueryPlanIT` (EXPLAIN không filesort trên `ix_payment_pending_expiry`; instance B xử lý 3 đơn còn lại trong khi A giữ 1; webhook trên đơn chưa claim không bị chặn); đột biến về `o.id` → 3 test đỏ |
| P11-R-02 | Đã sửa | `83aaa2c` / `150f38a` | Khóa theo học viên (dòng `carts`) trước mọi luồng áp kết quả / cấp quyền; bộ hẹn giờ áp "đã trả" trong transaction mới. `PaymentRaceIT` (muộn ‖ đúng hạn theo cả hai thứ tự, một phần); bỏ khóa → 3 test đỏ |
| P11-R-03 | Đã sửa | `2d9c545` / `068cd25` | `support/Interleave` + hold trong cổng giả / listener; viết lại 6 test, thêm kịch bản (b) (c) (d) (e) (f) (g) (i) (j); bảng đột biến trong progress.md (mọi test đỏ khi gỡ khóa tương ứng) |
| P11-R-04 | Đã sửa | `83aaa2c` / `150f38a` | T1 đọc sở hữu sau khi khóa giỏ; mọi luồng cấp quyền khác cũng khóa giỏ trước, nên không còn cấp quyền xen giữa |
| P11-R-05 | Đã sửa | `0d3e9f2` / `7271e1b` | Đơn chuyển `paid` → các lần `pending` khác `expired` cùng transaction; polling đơn đã trả hiện lần `success`; `PaymentWebhookIT`, `ReconciliationIT` (đỏ khi gỡ) |
| P11-R-06 | Đã sửa | `0d3e9f2` / `7271e1b` | Mặc định 2 s + 3 s, khởi động từ chối `connect + read >= tx-timeout`; trang chi tiết đối soát gọi cổng sau khi đóng transaction (0 kết nối được giữ trong lúc tra cứu, đỏ khi gỡ) |
| P11-R-07 | Đã sửa | `83aaa2c` / `150f38a` | `PaymentGatewayException(code)` + `describe` (loại + mã đã làm sạch, không message); lỗi bộ hẹn giờ log kèm stack trace |