# ADR-004 — Payment → enrollment is a synchronous in-transaction call
Status: Accepted · Date: 2026-11-12

Context: NFR-04 requires payment record, order status and enrollments in one DB transaction, idempotent.
Decision: `commerce` calls `learning.api.EnrollmentApi.grantForPaidOrder(...)` inside T2. `EnrollmentApi` is
`@Transactional(propagation = MANDATORY)` so it can only run inside the caller's transaction. Email (BR-20) and
cache eviction stay asynchronous via `OrderPaid`.
Rejected: `@ApplicationModuleListener` for enrollment (runs after commit in a new transaction → half-done state possible).
