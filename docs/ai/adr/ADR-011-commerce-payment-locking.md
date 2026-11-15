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

### Lock order (every flow, never reversed)
`carts` row of the student → `orders` row → `payment_transactions` row → writes to `enrollments` (`INSERT IGNORE`
through `EnrollmentApi.grantForPaidOrder`, `MANDATORY`, ADR-004) and deletes of `cart_items`. T2 never locks `carts`
(it resolves the cart id with a plain read and deletes the lines by `cart_id` + `course_id`), so T1 (cart → order) and
T2 (order → attempt → cart lines) cannot deadlock. No "SELECT says absent → INSERT" relies on a read alone: each insert
is covered by a parent row lock **and** a UNIQUE key (`uq_orders_one_pending`, `uq_payment_one_pending`,
`uq_payment_ref`, `uq_enrollments_student_course`).

| Flow | Locks | Backstop |
|---|---|---|
| T1 create order (UC-S04) | cart `FOR UPDATE` → pending order `FOR UPDATE` → its pending attempt | `uq_orders_one_pending` |
| Create QR (UC-S05) | order `FOR UPDATE` → pending attempt `FOR UPDATE` | `uq_payment_one_pending`, `uq_payment_ref` |
| T2 result (webhook / lookups) | scalar order id → order `FOR UPDATE` → attempt `FOR UPDATE` | `uq_payment_ref`, `uq_enrollments_student_course` |
| Cancel (UC-S16) | order `FOR UPDATE` → pending attempt `FOR UPDATE` | — |
| Reconciliation (UC-A18) | scalar order id → order `FOR UPDATE` → attempt `FOR UPDATE` | handled_* CHECKs |
| Schedulers (NFR-09) | claim one order `FOR UPDATE [OF o] SKIP LOCKED`, one transaction per order | as above |

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
- A provider call holds one student's order (and cart) lock for at most `tx-timeout`; other students are unaffected.
- `SKIP LOCKED` makes two scheduler instances skip each other's orders; a lookup that failed may be retried by the other
  instance in the same cycle (harmless, nothing changes on failure).
- The fake provider keeps its state in memory (dev/test only).
