# P01 — Flyway schema (MySQL port of DDD)

Chạy **mỗi module một phiên**: identity → authoring → commerce → learning → triggers → reference data.
Đính kèm: bản DDD (mục 5 phần bảng của module, mục 6 FK, mục 9 index) + `docs/ai/03-data-model-mysql.md` + `docs/ai/04-security.md` (khi làm identity).

## Prompt
```text
Task: write Flyway migration `src/main/resources/db/migration/{{FILE}}` for module {{MODULE}}
covering tables: {{TABLES}}.

Inputs: EDU-DDD-001 §5 (columns/constraints of these tables), §6 (FKs), §9 (indexes), and
docs/ai/03-data-model-mysql.md (porting rules), docs/ai/04-security.md (refresh_tokens DDL, identity only).

Rules:
- Exact table/column names, types, nullability, defaults and constraint names from the DDD.
  Do not add, rename or drop any column except the ADR-approved ones (generated columns, refresh_tokens).
- Apply every porting rule in docs/ai/03 §3.1–3.3 (DATETIME(6) UTC, ON UPDATE for updated_at, binary/'c' checks,
  generated columns for partial uniques, composite indexes for partial indexes).
- Name FKs fk_<table>_<column> with the ON DELETE action from DDD §6. FKs to tables of a module not yet migrated
  go into that later module's file (state this in a comment).
- Index every FK column not already leading a UNIQUE/PK.
- Table order respects FK dependencies. One statement per table where possible.
- At the end, print a checklist table: each DDD constraint/index of these tables → how it is implemented → line.
  Mark anything you could not port as UNRESOLVED instead of silently dropping it.
```
## Prompt cho trigger (V5)
```text
Task: write V5__triggers.sql with the 4 DDD triggers ported to MySQL per docs/ai/03 §3.5:
trg_cci_not_main_instructor (_bi,_bu), trg_courses_prevent_delete_published (_bd),
trg_lesson_progresses_lesson_course (_bi,_bu), trg_course_questions_lesson_course (_bi,_bu, only when lesson_id IS NOT NULL).
MESSAGE_TEXT must start with the BR code (BR-02, BR-19, BR-24, BR-37). Then write V6__reference_data.sql inserting
roles (student, instructor, admin) with Vietnamese names. Add an integration test FlywayMigrationIT (Testcontainers)
asserting: 21 tables exist, both generated columns exist with UNIQUE, 7 triggers exist, 3 roles exist.
```
## Kiểm tra
`./mvnw -Dtest=FlywayMigrationIT test` xanh · bảng checklist không còn UNRESOLVED (hoặc đã hỏi lại).
