# ADR-011 — Commerce: payment gateway port, lock order and transaction boundaries (P11)
Status: Accepted · Date: 2026-11-15 · Plan approved with Q1–Q5 and the amendments R1–R3 (P11 plan review)

## Context
P11 replaces the phase-1 commerce stubs (T1 create order, create QR, T2 payment result, cancel, UC-A18, schedulers).
Every flow touches the same rows (cart, order, attempt, enrollments) from different entry points (student request,
provider webhook, admin, two scheduler jobs, possibly on several instances), under READ COMMITTED (ADR-010: no gap
locks). DDD §11 (T1–T7) is missing from our copy; the transactions are derived from UC-S04/S05/S16/A18, BR-13…17,
BR-20, BR-23, BR-27, BR-29, BR-30, D-24…D-29, AC-01…03, 10, 11, 18, 19, 21…24, NFR-04, NFR-09.

## Decision
### Payment gateway port (`commerce.integration.PaymentGateway`)
`createQr(reference, amount, expiresAt)`, `query(reference)` (`Paid(amount, paidAt) | Failed | NotPaid`, provider
down → `PaymentGatewayException`), `verifyWebhook(rawBody, headers)` (signature over the RAW body before parsing →
`PaymentResult`). The reference sent to the provider is the **transaction reference** (one per QR attempt, D-27),
never the order code. `FakeQrPaymentGateway` (every profile but `payos`) is the dev/test provider; a payOS adapter will
be a new bean under the profile `payos` plus its own webhook controller calling `PaymentService.receiveWebhook`, with
no service change. Real adapters must use `edumy.payment.connect-timeout` / `read-timeout` (≤ 5 s).

### Lock order (every flow, never reversed) — per-student lock since P11-fix-a
`carts` row of the student → `orders` row → `payment_transactions` row → writes to `enrollments` (`INSERT IGNORE`
through `EnrollmentApi.grantForPaidOrder`, `MANDATORY`, ADR-004) and deletes of `cart_items`.

**Rule (P11-fix-a, review P11-R-02 / R-04):** every flow that may apply a payment result or grant enrollments locks the
student's `carts` row **first** (`StudentPaymentLock`: `INSERT … ON DUPLICATE KEY UPDATE` on `uq_carts_student` when the
student has no cart — BR-22 allows an empty cart — then `SELECT id … FOR UPDATE`): T2 (webhook), UC-S16 cancel, UC-S05
create QR, UC-A18 and T1 (which locks the cart entity). So two flows of one student are serialised **even on two
different orders**, and "which courses does the student own" (BR-30, Q3) is always read after the other flow
committed: the race "late result of P0 ‖ on-time result of P1, same course" can no longer make both orders `paid`
without a `needs_review` (proved by `PaymentRaceIT`). T1 reads ownership after the cart lock, which now excludes any
concurrent grant for that student (R-04). Unknown / foreign ids are rejected by a scalar read **before** the cart is
touched, so nobody gets a cart row for someone else's order.

**Timers:** the claim transaction locks only the order (`SKIP LOCKED`) and must not wait for a cart (a request holding
the cart may be waiting for that order). It therefore only looks the attempt up: not paid → `expired`, failed →
`failed`, 24 h job → order cancelled (nothing granted, no cart lock needed). **Paid** → the claim transaction ends
without a change and T2 runs in a **new** transaction in the standard order (`PaymentService.applyProviderResult`);
T2 is idempotent, so a webhook or another instance applying the same result meanwhile is harmless
(`PaymentRaceIT#timerFindsPaid_whileTheStudentsCreateOrderHoldsTheCart_noDeadlock_paidOnce`).

No "SELECT says absent → INSERT" relies on a read alone: each insert is covered by a parent row lock **and** a UNIQUE
key (`uq_orders_one_pending`, `uq_payment_one_pending`, `uq_payment_ref`, `uq_enrollments_student_course`,
`uq_carts_student`).

| Flow | Locks | Backstop |
|---|---|---|
| T1 create order (UC-S04) | cart `FOR UPDATE` → pending order `FOR UPDATE` → its pending attempt | `uq_orders_one_pending` |
| Create QR (UC-S05) | scalar order id → cart → order `FOR UPDATE` → pending attempt `FOR UPDATE` | `uq_payment_one_pending`, `uq_payment_ref` |
| T2 result (webhook, timers' "paid") | scalar order id + buyer → cart → order `FOR UPDATE` → attempt `FOR UPDATE` | `uq_payment_ref`, `uq_enrollments_student_course` |
| Cancel (UC-S16) | scalar order id → cart → order `FOR UPDATE` → pending attempt `FOR UPDATE` | — |
| Reconciliation (UC-A18) | scalar order id + buyer → cart → order `FOR UPDATE` → attempt `FOR UPDATE` | handled_* CHECKs |
| Timers (NFR-09) | claim one order `FOR UPDATE [OF o] SKIP LOCKED` → attempt; "paid" → T2 in a new transaction | as above |
### Claim queries walk their index (P11-fix-b, review P11-R-01)
The expired-QR claim sorted by `p.qr_expires_at, o.id` (columns of two tables): MySQL read **every** candidate,
locked each `orders` row, sorted and only then applied `LIMIT 1`, so one timer instance held all candidate orders
during its provider call (a second instance found nothing; students' requests on those orders waited). Now
`ORDER BY p.qr_expires_at, p.id` follows `ix_payment_pending_expiry (status, qr_expires_at, id)` and stops at the first
free row; the 24 h claim already followed `ix_orders_pending_created (status, created_at, id)`.
EXPLAIN on MySQL 8.4 (`ClaimQueryPlanIT`, 4 expired orders):

| Query | Before | After |
|---|---|---|
| expired QR | `p`: ref on `ix_payment_review_queue`, **Using temporary; Using filesort** | `p`: **range on `ix_payment_pending_expiry`**, Using index condition; `o`: eq_ref PRIMARY |
| 24 h order | — | `o`: index `ix_orders_pending_created`, Using where; Using index (no filesort) |

`ClaimQueryPlanIT` asserts the plans (no filesort, the expected index) and the lock scope: while instance A is inside
the lookup of one order, instance B processes the 3 other orders at once and a webhook on an unclaimed order goes
through. With the old `ORDER BY` 3 of its 4 tests fail (mutation check, see progress).

### R1 — no stale entity under a lock
A flow that must read before it locks reads a **scalar** (`findOrderIdByTransactionRef`, `findOrderIdById`), never an
entity: `SELECT … FOR UPDATE` (or `find` with `PESSIMISTIC_WRITE`) on an entity that is already in the persistence
context returns the managed instance without reloading its fields, so a value read before the lock wait would be
written back. The order and the attempt are loaded for the first time under their locks; if an entity is already
managed, `EntityManager.refresh(entity, PESSIMISTIC_WRITE)` is used. Proved by
`PaymentWebhookIT#deliveryWaitingForTheOrderLock_seesTheCommittedSuccess_noSecondEffect_R1` (`support/LockWaits`).

### T2 decision table (`PaymentResultApplier`)
failed result: pending → `failed`, else ignored · success on `success` / `needs_review` → ignored (AC-11) · amount or
echoed order code mismatch → `needs_review` (AC-19) · late (attempt not pending or order not pending): any course owned
or order already paid → `needs_review` (AC-24), else paid (AC-23) · on time: every course owned → `needs_review` (Q3),
some owned → paid, the others granted, WARN log · paid = attempt `success`, order `paid`, enrollments, bought lines
leave the cart, `OrderPaid` (mail after commit) — one transaction (NFR-04).

### Provider calls inside transactions
T1, create QR, cancel and the schedulers call the provider while they hold the order (and for T1 the cart) row lock of
**one** student: that is what makes "P1 cancelled before P2 is created" (AC-21) and "lookup then close" atomic. The
transactions that call the provider run in a `TransactionTemplate` with `edumy.payment.tx-timeout` (10 s). A lookup that
finds the payment already made records it (T2) and **commits first**, then the request answers 409
`ORDER_ALREADY_PAID`; a provider failure answers 503 `PAYMENT_PROVIDER_UNAVAILABLE` and changes nothing.

### Approved decisions (P11 plan)
- **Q1** (UC-S04 E3 vs D-24): an existing pending order with exactly the same courses and prices as the re-validated
  cart is returned (200) instead of being replaced; otherwise lookup, replace (`replaced`), create (201).
- **Q2** (UC-S04 step 3): optional `expectedTotalAmount` / `expectedCourseIds` in `CreateOrderRequest`; a difference →
  409 `ORDER_CART_CHANGED`, checked before the old order is touched.
- **Q3**: on-time result for an order whose courses are all owned → `needs_review`.
- **Q4**: `GET /admin/payments/{id}` shows the order lines, owned courses and the amount the provider reports now (no
  schema change).
- **Q5 / R2**: transaction reference = 15 random digits in [10^14, 10^15) (no leading zero, fits a 53-bit integer for
  numeric provider references); order code `ORD-<yyyyMMdd>-<8 base32>`.
- **R3**: create QR re-checks the order first — older than 24 h → lookup, cancel `expired`, 409; a line already owned →
  lookup, cancel `replaced`, 409 `ORDER_CART_CHANGED`.

## Consequences / risks
- **Double payment, partial ownership (R3):** SRS UC-S05 step 7 grants "the courses not owned yet"; when an on-time
  result arrives for an order with some courses already owned (e.g. a late result of an older order was accepted in
  between), the student paid twice for those courses. Logged WARN with orderCode and course ids; refund is manual
  (out of scope v1: automatic refund). Create QR refuses to show a QR for such an order (R3 b), which narrows the window
  to QRs shown before the ownership changed.
- A provider call holds one student's cart / order lock for at most `tx-timeout`; other students are unaffected (for
  the timers since P11-fix-b, see the claim query below).
- `SKIP LOCKED` makes two scheduler instances skip each other's orders; a lookup that failed may be retried by the other
  instance in the same cycle (harmless, nothing changes on failure).
- The fake provider keeps its state in memory (dev/test only).
