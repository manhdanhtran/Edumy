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
| P02-2 db/seed/V1000__dev_seed.sql | todo | | |
| P02-3 SeedCoverageIT (@MySqlSeedTest) + app starts with profile dev | todo | | |
| P03-identity entities + repositories | todo | | |
| P03-authoring entities + repositories | todo | | |
| P03-commerce entities + repositories | todo | | |
| P03-learning entities + repositories | todo | | |
| P04-plan (HARD STOP: approval) | todo | | |
| P04-a JWT, SecurityConfig, TraceIdFilter | todo | | |
| P04-b SessionService + /auth/login, refresh, logout | todo | | |
| P04-c account tokens, register, verify, reset, /me | todo | | |
| P04-d notification module (mail) | todo | | |
| P04-e RoleQueryApi + access beans | todo | | |
| P04-f LoginAttemptLimiter + CertificateVerifyRateLimiter | todo | | |
| P10 self-review of P02–P04 | todo | | |
