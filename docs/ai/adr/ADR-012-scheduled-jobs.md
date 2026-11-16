# ADR-012 — Scheduled jobs: schedules, multi-instance safety, code cleanup
Status: Accepted · Date: 2026-11-16 · P11-e, P11-fix-e (brief 2026-11-16)

## Context
SRS §3 "Bộ hẹn giờ" and NFR-09 ask for timers every 5 minutes that are safe when several instances run, without a
lock library (no ShedLock, no new dependency). SRS §6 allows deleting account codes (`user_tokens`) that expired more
than 30 days ago. The code cleanup has no business deadline; running it every 5 minutes only adds load.

## Decision
| Job | Bean | Schedule | Multi-instance safety |
|---|---|---|---|
| Expired QR attempts (UC-S05 A1) | `commerce.scheduler.PaymentExpiryScheduler` | `fixedDelay = edumy.scheduler.interval` (default `PT5M`), initial delay `PT1M` | one order per transaction, `FOR UPDATE [OF o] SKIP LOCKED` (ADR-011) |
| Pending orders older than 24 h (BR-29, AC-22) | `commerce.scheduler.PendingOrderExpiryScheduler` | same | same |
| Account codes expired > 30 days (SRS §6) | `identity.scheduler.TokenCleanupScheduler` | **once a day**, `cron = edumy.scheduler.token-cleanup-cron` (default `0 0 3 * * *`, 03:00) in `edumy.scheduler.zone` (default `Asia/Ho_Chi_Minh`) | `DELETE … LIMIT 1000` per short transaction; idempotent, two instances only race to delete the same rows once |

- All three exist only with `edumy.scheduler.enabled=true` (production / dev default in `application.yml`; `false` in the
  test profile — tests call the services directly, `SchedulerBeansTest` covers the switch).
- Timers only call their service (docs/ai/01 §1.4).

## Consequences
- `user_tokens` has no index on `expires_at` (DDD: only `ix_user_tokens_active (user_id, token_type, used_at)`): the
  daily cleanup scans the table. Acceptable while the table is small (codes of a few thousand users). **At the next
  schema change**, if the table is large, add `ix_user_tokens_expires_at (expires_at)` in a new Flyway file (V1–V6 are
  frozen; a schema change is a hard stop that needs approval).
- A failed lookup or apply is retried at the next run (5 minutes later); a failed cleanup the next day.
