# P01 progress — Flyway schema (docs/prompts/P01-flyway-schema.md)

Resume from the first unit that is not `done`. Never redo a committed unit.
Constraint-by-constraint status: [schema-checklist.md](schema-checklist.md).

| unit | status | commit | notes |
|---|---|---|---|
| P01-1 V1__identity.sql + IdentitySchemaIT | done | 5fc1f34 | reference for style and tests; checklist section rebuilt from the SQL |
| P01-2 V2__authoring.sql + AuthoringSchemaIT | done | fc298b8 | 169 tests green; an unknown lessons.content_type is reported as ck_lessons_content_match (both CHECKs fail) |
| P01-3 V3__commerce.sql + CommerceSchemaIT | todo | | |
| P01-4 V4__learning.sql + LearningSchemaIT | todo | | |
| P01-5 V5__triggers.sql + V6__reference_data.sql + TriggerSchemaIT + FlywayMigrationIT | todo | | |
| P01-6 ConstraintViolationTranslator (shared/error) | todo | | |
| P01-7 Self-review (P10) of all P01 migrations | todo | | |
