# ADR-001 — Implement EDU-DDD-001 (PostgreSQL 16) on MySQL 8.4 LTS
Status: Accepted · Date: 2026-11-12

Context: DDD v1.0 targets PostgreSQL 16 (partial indexes, DEFERRABLE, PL/pgSQL, pg_trgm, TIMESTAMPTZ). The course
requires Spring Boot + MySQL Driver. MySQL 8.0 reached end of life on 2026-04-30; 8.4 is the LTS line.

Decision: MySQL 8.4 LTS in Docker. Keep every table, column, FK action and constraint name from DDD. Replace
mechanisms as in `docs/ai/03-data-model-mysql.md` §3.2–3.5: generated columns for partial uniques, composite indexes
for partial indexes, offset-based two-phase reorder, MySQL triggers with BR-coded SIGNAL messages, `title_normalized`
LIKE search, `DATETIME(6)` in UTC, binary/`'c'` comparisons in CHECKs.

Consequences: two extra generated columns (`orders.pending_student_id`, `payment_transactions.pending_order_id`).
Search is a scan (fine for year-1 volume). XAMPP/MariaDB is not supported (different generated-column/CHECK behaviour).
Hibernate validate settings that turned out necessary: _record here after first run_.

## Known limitations and decisions after V1–V6
Recorded after the P01 review (2026-11-12). Migrations V1–V6 are frozen at tag `schema-v1`.
- **`BOOLEAN` = `TINYINT(1)`, no CHECK.** MySQL accepts any small integer (e.g. 5) in a BOOLEAN column; the DDD "no
  third state" rule is guaranteed by the application (Java `boolean`/`Boolean` mapping), not by a `CHECK (col IN (0,1))`.
  Decision: no extra constraint (it would not be in the DDD).
- **Accent-insensitive UNIQUE.** Under `utf8mb4_0900_ai_ci`, UNIQUE keys fold case and accents (PostgreSQL does not).
  Kept on purpose for `categories.name`, `orders.order_code`, `payment_transactions.transaction_ref` (display/generated
  values). **`users.email` is the exception: `COLLATE utf8mb4_bin`**, so `é@x.com` and `e@x.com` are distinct accounts
  and lookups match exact characters; `ck_users_email_normalized` stays.
- **`ON UPDATE CURRENT_TIMESTAMP(6)` does not run for rows changed by an FK cascade or `SET NULL`** (e.g.
  `course_questions.updated_at` when a lesson is deleted), nor for an UPDATE that changes no value. Accepted.
- **BR-02 trigger does not run when `courses.main_instructor_id` changes** (it exists only on `course_co_instructors`,
  as in the DDD). The column never changes in v1, so the `Course` entity must map it `@Column(updatable = false)`
  (rule in `.cursor/rules/20-entity-repository.mdc`, P03).
- **Triggers need `--log-bin-trust-function-creators=1`** (or an equivalent privilege such as a migration user with
  SUPER) on every MySQL server, production included: with binary logging on (default in 8.4), a non-SUPER user gets
  error 1419 on `CREATE TRIGGER`. Set in `compose.yaml` and `support/SharedMySql`. Triggers get `DEFINER` = the Flyway
  user; dropping/renaming that account later breaks writes (error 1449).
- **Triggers do not fire for FK cascades**, and BR-24/BR-37 are checked on the child row only (docs/ai/03 §3.5).
- **`certificate_code VARCHAR(19)`** while the format has 18 characters: minor DDD error, kept as is
  (docs/ai/03 "DDD inconsistencies").
