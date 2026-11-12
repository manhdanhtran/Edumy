# P01 progress — Flyway schema (docs/prompts/P01-flyway-schema.md)

Resume from the first unit that is not `done`. Never redo a committed unit.
Constraint-by-constraint status: [schema-checklist.md](schema-checklist.md).

| unit | status | commit | notes |
|---|---|---|---|
| P01-1 V1__identity.sql + IdentitySchemaIT | done | 5fc1f34 | reference for style and tests; checklist section rebuilt from the SQL |
| P01-2 V2__authoring.sql + AuthoringSchemaIT | done | fc298b8 | 169 tests green; an unknown lessons.content_type is reported as ck_lessons_content_match (both CHECKs fail) |
| P01-3 V3__commerce.sql + CommerceSchemaIT | done | d426098 | 265 tests green (subagent); unknown orders.status breaks ck_orders_status and ck_orders_state; order_code / transaction_ref UNIQUE is case-insensitive (ai_ci) |
| P01-4 V4__learning.sql + LearningSchemaIT | done | 6280918 | 383 tests green (subagent); unknown enroll_source breaks ck_enrollments_source and ck_enrollments_source_order; certificate_code is VARCHAR(19) for an 18-char format (DDD kept) |
| P01-5 V5__triggers.sql + V6__reference_data.sql + TriggerSchemaIT + FlywayMigrationIT | done | d02f012 | 419 tests green (subagent); error 1419 fixed with --log-bin-trust-function-creators=1 (SharedMySql + compose.yaml; prod needs the same); FKs = 34 DDD (27 RESTRICT, 6 CASCADE, 1 SET NULL) + 1 ADR-002 |
| P01-6 ConstraintViolationTranslator (shared/error) | todo | | |
| P01-7 Self-review (P10) of all P01 migrations | todo | | |
