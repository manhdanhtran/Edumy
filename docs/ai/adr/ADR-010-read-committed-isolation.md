# ADR-010 — READ COMMITTED is the isolation level of every transaction
Status: Accepted · Date: 2026-11-15 · From the P10 data review (DT-B1 BLOCKER, DT-M1, DT-m1)

## Context
MySQL's default isolation is REPEATABLE READ: a transaction's plain `SELECT`s all read the snapshot taken by its first
plain read. Our write services follow one pattern: resolve an id with a plain read, lock a parent row
(`SELECT … FOR UPDATE` on the course, enrollment, user …), then read and change child rows. Under REPEATABLE READ the
reads **after** the lock wait still came from the snapshot taken **before** it, and Hibernate wrote the stale row back
on flush. Reproduced (red before the fix):
- an invitee's accept waiting for the course lock held by the main instructor's removal accepted the invitation the
  removal had just committed → the removed co-instructor was back with edit rights
  (`CoInstructorWriteIT#accept_racingARemoveThatHoldsTheCourseLock_409NotPending_staysRemoved_noLostUpdate`);
- a lesson PATCH waiting for the course lock overwrote the title another instructor had just committed
  (`LessonWriteIT#patch_racingAnotherEditThatHoldsTheCourseLock_keepsTheOtherEdit_noLostUpdate`).
The same mechanism threatened the D-14 last-lesson guard, positions after a concurrent reorder, the identity cooldowns
and the T1 / T2 payment flows of P11, which follow the same lock-then-read pattern. Two services (UC-S10 progress,
UC-S12 review) had already switched to READ COMMITTED for this reason.

## Decision
`spring.datasource.hikari.transaction-isolation: TRANSACTION_READ_COMMITTED` in `application.yml`: every connection,
hence every transaction, runs READ COMMITTED. A read after a lock sees what was committed while waiting. The explicit
`@Transactional(isolation = READ_COMMITTED)` annotations stay as documentation of the services that depend on it.

## Consequences
- Lost updates of the lock-then-read pattern are gone without per-method annotations; P11 inherits the safe default.
- Read-only multi-statement reads (a page + its count) may see a row committed between the two statements: the
  `totalElements` of a page can be off by the rows written in that instant. Accepted (no money or access depends on it).
- InnoDB takes no gap locks under READ COMMITTED: uniqueness still comes from the UNIQUE keys (generated-column keys
  for "one pending order / payment"), which keep their record locks. Binary logging must be ROW based (MySQL 8.4
  default).
- Test support: `support/LockWaits` interleaves a lock holder and a request deterministically by waiting for
  `information_schema.innodb_trx` to show `LOCK WAIT` (the test user gets `PROCESS`, `support/SharedMySql`);
  `shared/config/TransactionIsolationIT` asserts the configured level.

## Conditions of approval (P11 brief, 2026-11-15)
- No gap locks under READ COMMITTED: never use "plain SELECT says the row is absent → INSERT" unless a UNIQUE key or a
  lock on a parent row protects the insert (commerce applies this in ADR-011).
- The `PROCESS` privilege is granted **only to the test database user** (for `support/LockWaits`); the production user
  does not need it.
- Production requires `binlog_format=ROW` (READ COMMITTED with statement-based binary logging is refused by MySQL);
  the runbook (P14) states both points.
