# ADR-014 — Accepted race "enroll / add to cart ‖ hide course"; reading of the login lock-out rule
Status: Accepted · Date: 2026-11-16 · P13 (backlog BC-m5, BC-m6 of the P10 business review)

## 1. Free enrollment or cart add racing an admin hide (BC-m5)
**Context.** UC-A12 step 3 / BR-31: a hidden course may not be added to a cart, bought or newly enrolled. UC-S15 (free
enrollment, learning) and UC-S01 (add to cart, commerce) read "is the course published?" through
`authoring::api.CourseQueryApi`, then write their own row. An admin hide committed between that read and the write
lets one enrollment / cart line through.

**Decision.** Accept the window; do not add a cross-module lock.
- Closing it would need learning / commerce to lock the `courses` row of authoring (`SELECT … FOR UPDATE` through an
  api), i.e. a lock on another module's table and a new lock order across three modules — against the module rules
  (docs/ai/01 §1.8, AGENTS.md §4) for a window of milliseconds.
- Consequences are bounded and already handled by the rules: a cart line of a hidden course is flagged
  `course_unavailable` and left out of every order (D-26, P11 T1 re-validation; R3 b cancels an order that holds an
  unavailable / owned course before a QR); an enrollment that slipped through keeps the access a hidden course grants to
  enrolled students anyway (BR-31: "học viên đã ghi danh giữ nguyên quyền học"). No payment can complete for it.
- Revisit if hide becomes frequent or the window must be zero: add `CourseQueryApi.lockPublished(courseId)` in authoring
  (locks and re-checks the course row inside the caller's transaction) and call it first in UC-S15 / UC-S01.

## 2. Login lock-out: "5 consecutive failures within 15 minutes" (BC-m6)
**Context.** UC-G07: "sai 5 lần trong 15 phút → tạm chặn 15 phút"; NFR-03: "5 lần đăng nhập sai **liên tiếp** cho một
email trong 15 phút". `LoginAttemptLimiter` counts consecutive failures; every failure restarts the 15-minute life of
the counter, a success resets it, a gap of 15 minutes or more restarts at 1 (docs/ai/04 §4.4).

**Decision.** Keep this reading: "liên tiếp" = an unbroken run of failures with no success, where no two consecutive
failures are 15 minutes or more apart. So failures at t = 0, 14, 28, 42, 56 min block at the fifth (each gap < 15 min);
a strict sliding window "5 failures inside any 15 minutes" would not. The stricter reading was chosen because NFR-03
stresses *consecutive* failures, it can only block earlier (never later) than the sliding window, the block is short
(15 min) and never permanent, and a correct password still works until the fifth failure.

**Consequences.** A user who mistypes once every 10 minutes for almost an hour is blocked for 15 minutes; acceptable.
Changing to a sliding window = store the failure times (deque of 5) instead of a counter; no API change.
