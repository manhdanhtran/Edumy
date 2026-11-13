# Build progress — P01 Flyway schema, P02 dev seed, P03 entities, P04 security (docs/prompts/)

Resume from the first unit that is not `done`. Never redo a committed unit.
Constraint-by-constraint status: [schema-checklist.md](schema-checklist.md).

| unit | status | commit | notes |
|---|---|---|---|
| P01-1 V1__identity.sql + IdentitySchemaIT | done | 5fc1f34 | reference for style and tests; checklist section rebuilt from the SQL |
| P01-2 V2__authoring.sql + AuthoringSchemaIT | done | fc298b8 | 169 tests green; an unknown lessons.content_type is reported as ck_lessons_content_match (both CHECKs fail) |
| P01-3 V3__commerce.sql + CommerceSchemaIT | done | d426098 | 265 tests green (subagent); unknown orders.status breaks ck_orders_status and ck_orders_state; order_code / transaction_ref UNIQUE is case-insensitive (ai_ci) |
| P01-4 V4__learning.sql + LearningSchemaIT | done | 6280918 | 383 tests green (subagent); unknown enroll_source breaks ck_enrollments_source and ck_enrollments_source_order; certificate_code is VARCHAR(19) for an 18-char format (DDD kept) |
| P01-5 V5__triggers.sql + V6__reference_data.sql + TriggerSchemaIT + FlywayMigrationIT | done | d02f012 | 419 tests green (subagent); error 1419 fixed with --log-bin-trust-function-creators=1 (SharedMySql + compose.yaml; prod needs the same); FKs = 34 DDD (27 RESTRICT, 6 CASCADE, 1 SET NULL) + 1 ADR-002 |
| P01-6 ConstraintViolationTranslator (shared/error) | done | 2240baa | 447 tests green (subagent); production key map empty — ErrorCodes for known constraints/BR codes are an open question |
| P01-7 Self-review (P10) of all P01 migrations | done | 64589eb | 0 BLOCKER; MAJOR regex `$` → `\z` fixed; MINORs listed in final report; 450 tests green |
| P02-0 ConstraintViolationTranslator by vendor code | skipped | 2240baa, 48cd9cb | already done in P01 (maps 1062/1451/1452/3819/1644); verified, not redone. Baseline before P02: 472 tests green, 3 min 16 s |
| P02-1 BCrypt cost-12 hash of Edumy@123 | done | — | `docker run --rm httpd:2.4 htpasswd -nbBC 12 "" 'Edumy@123'` → `:$2y$12$245qetfs3AM6usLDQjGfoune4jnHuQEfHmoEEpkL/TEh9.D8tLAPq` → strip `:`/newline, `$2y`→`$2a` |
| P02-2 db/seed/V1000__dev_seed.sql | done | 62c4041 | 20 users, 14 courses, 14 orders, 15 payments, 19 enrollments, 2 certificates; every row passes all CHECK/UNIQUE/FK/triggers; 497 tests green, suite 2 min 32 s; app started with profile dev, Flyway at v1000 |
| P02-3 SeedCoverageIT (@MySqlSeedTest) + app starts with profile dev | done | 62c4041 | 25 tests (read-only, edumy_seed); 497 tests green, suite 2 min 32 s; app started with profile dev, Flyway at v1000 |
| P03-identity entities + repositories | done | fc7cea1 | 586 tests green (+89: 72 entity unit, 17 IdentityRepositoryIT), suite 1 min 48 s; ddl-auto=validate OK on test DB and dev DB (v1000), no Hibernate setting needed (ADR-001) |
| P03-authoring entities + repositories | done | a11566b | 744 tests green (+158: 146 entity unit, 12 AuthoringRepositoryIT), suite 1 min 50 s; ddl-auto=validate OK on test DB and dev DB (v1000), no Hibernate setting needed (ADR-001); equality helper moved to shared.jpa.JpaEntities |
| P03-commerce entities + repositories | done | df96e32 | 846 tests green (+102: 88 entity unit, 14 CommerceRepositoryIT), suite 1 min 51 s; ddl-auto=validate OK on test DB and dev DB (v1000), no Hibernate setting needed (ADR-001); generated pending_* columns unmapped — flush the cancel/close before inserting the next pending row |
| P03-learning entities + repositories | done | 6c4ea20 | 975 tests green (+129: 116 entity unit, 13 LearningRepositoryIT), suite 3 min 26 s; ddl-auto=validate OK on test DB and dev DB (v1000), no Hibernate setting needed (ADR-001); T2 uses INSERT IGNORE (insertPurchaseIfAbsent) for BR-17 — IGNORE also hides FK/CHECK errors |
| P04-plan (HARD STOP: approval) | approved | — | approved 2026-11-13 with Q1–Q7 = A/approved + 7 SRS constraints (NFR-02/03/08, UC-G05 E3, UC-G08/G12) |
| P04-a JWT, SecurityConfig, TraceIdFilter | done | 084c7a3 | 1015 tests green (+40), suite 2 min 59 s, one boot context; 401/403 from the filter chain rendered by GlobalExceptionHandler via ProblemResponder; session/user status through Cache API, evicted after commit |
| P04-b SessionService + /auth/login, refresh, logout | done | 48254c8 | 1040 tests green (+25), suite 3 min 35 s, one boot context; BCrypt(12) + same-cost dummy hash; reuse_detected revoke committed before the 401 (TransactionTemplate); Authorization ignored on public /auth/**; Origin check on refresh/logout; AUTH_ACCOUNT_LOCKED (Q1) |
| P04-c account tokens, register, verify, reset, /me | done | 9814132 | 1088 tests green (+48), suite 2 min 07 s, one boot context; register logs in inside the same transaction (startSessionForNewUser, MANDATORY); events only published (mail in P04-d); forgot always 202 (Q3, ADR-001 note); @StrongPassword (8–72 chars, letter+digit, ≤72 UTF-8 bytes); MutableClock test Clock |
| P04-d notification module (mail) | done | (this commit) | 1108 tests green (+20), suite 2 min 25 s, one boot context; @Async + @TransactionalEventListener instead of @ApplicationModuleListener (events-api jar not on classpath, ADR-006 note); HTML templates with escaped ${...}, fail-fast placeholders; no retry (ADR-006); NFR-08 log-capture test |
| P04-e RoleQueryApi + access beans | todo | | |
| P04-f LoginAttemptLimiter + CertificateVerifyRateLimiter | todo | | |
| P10 self-review of P02–P04 | todo | | |

## P02 seed coverage
State-machine value → seed row ids in `db/seed/V1000__dev_seed.sql` (asserted by `SeedCoverageIT`).

| table.column | value → ids |
|---|---|
| users.status | active → 1–19 · locked → 20 |
| users.is_email_verified | true → 1–17, 20 · false → 18, 19 |
| user_roles.is_active | false → 9 (instructor role of user 5, D-04) · admin (D-01) → user 1 only |
| categories.is_active | false → 8 (used by draft course 14) |
| courses.status | draft → 11, 14 · pending_review → 12 · rejected → 13 · published → 1–9 (free 2, 6) · hidden → 10 |
| course_co_instructors.invitation_status | pending → 2 · accepted → 1 · declined → 3 · removed → 4 |
| orders.status | pending → 4, 10 · paid → 1, 2, 7, 8, 11, 12, 13, 14 · cancelled → 3, 5, 6, 9 |
| orders.cancel_reason | user → 3 · replaced → 6 · expired → 5, 9, 11 (11 later paid, BR-30) |
| order_items price snapshot ≠ current price | 1 (course 1 at 699 000, now 799 000) |
| payment_transactions.status | pending → 6 · success → 1, 3, 8, 9, 12, 13, 14, 15 · failed → 2 · expired → 4, 5, 7 · needs_review → 10, 11 |
| payment_transactions.handled_action | refund_outside → 10 (needs_review handled) · activated → 12 (success) · unhandled needs_review → 11 |
| enrollments.enroll_source | purchase → 1–10 · free → 11–19 |
| lesson_progresses | 100 % → enrollments 11, 3 · partial → enrollments 1, 4, 6, 8, 12, 13 |
| course_reviews.visibility_status | visible → 1, 2, 3, 6, 7 · withdrawn → 4 · hidden → 5 |
| course_questions.visibility_status | visible → 1, 2, 3, 6 (resolved 1) · deleted → 5 · hidden → 4 |
| question_answers.visibility_status | visible → 1, 2, 3, 4, 6 (instructor 1, 3, 6; student 2, 4) · deleted → 5 · hidden → 7 |
| carts with a now-hidden course | cart 2 / cart_item 3 (course 10) |
| certificates | 1 (enrollment 11), 2 (enrollment 3) |
