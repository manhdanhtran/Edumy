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
Hibernate validate settings that turned out necessary (module by module, P03):
- **identity** — none. `ddl-auto=validate` passes with the defaults of Hibernate 7.4 + Connector/J (test DB and dev DB
  at v1000): `Instant` ↔ `DATETIME(6)`, `boolean` ↔ `TINYINT(1)`, `String` ↔ `CHAR(64)`/`CHAR(36)` mapped with
  `@JdbcTypeCode(SqlTypes.CHAR)` + `length`. Entity `Instant`s are stored as the UTC wall time (verified in
  `IdentityRepositoryIT`). Pitfall: a *native* query that selects a raw `DATETIME` column as a scalar returns a
  `LocalDateTime` shifted to the JVM zone; select `DATE_FORMAT(...)` or map through an entity/`Instant` instead.
- **authoring** — none. Validate passes on the test DB and the dev DB (v1000) with the defaults. `TEXT` columns
  (`courses.description`, `courses.review_note`, `lessons.article_content`) are mapped `@Column(columnDefinition =
  "TEXT")` (docs/ai/03 §3.8, no `@Lob`); `courses.main_instructor_id` is `updatable = false` (verified in
  `AuthoringRepositoryIT`). `@Lock(PESSIMISTIC_WRITE)` on a scalar JPQL query (`select ch.id … order by …`) renders
  `… FOR UPDATE OF <alias>`, which MySQL 8.4 accepts (two-phase reorder, docs/ai/03 §3.4).
- **commerce** — none. Validate passes on the test DB and the dev DB (v1000) with the defaults. The generated columns
  `orders.pending_student_id` / `payment_transactions.pending_order_id` are not mapped: validate ignores them, entity
  INSERTs omit them and MySQL fills them, so uq_orders_one_pending / uq_payment_one_pending fire on entity saves
  (verified in `CommerceRepositoryIT`). Pitfall: with IDENTITY ids Hibernate runs the INSERT at `persist` time, before
  pending UPDATEs are flushed, so "cancel the old pending order / close the old pending QR, then save the new one"
  needs an explicit `flush()` in between, or the unique key fires. `qr_data` / `handled_note` are
  `@Column(columnDefinition = "TEXT")`. The entity name `Order` is accepted by HQL (`from Order o`); enum literals in
  JPQL (`OrderStatus.PENDING`) are rendered through the converter (`status='pending'`).
- **learning** — none. Validate passes on the test DB and the dev DB (v1000) with the defaults: `course_reviews.rating`
  `SMALLINT` ↔ `Short`, `is_completed` / `is_resolved` `BOOLEAN` ↔ `boolean`. BR-17 in T2 uses a native
  `INSERT IGNORE` (`EnrollmentRepository.insertPurchaseIfAbsent`, returns 0 for a duplicate) instead of catching
  uq_enrollments_student_course: an exception through the repository proxy would mark the payment transaction
  rollback-only. Pitfall: `IGNORE` also downgrades FK (1452) and CHECK (3819) errors to warnings, so only valid
  student / course / order ids may be passed. An `Instant` bound as a native-query parameter is stored as UTC (verified
  in `LearningRepositoryIT`). The BR-24 trigger fires on an entity INSERT (root cause `SQLException` 1644, message
  `BR-24: …`). HQL scalar subqueries with `order by … limit 1` (last accessed lesson, D-34) work on MySQL 8.4.

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

## SRS deviations decided during the build
- **UC-G08 E2 vs D-03 (P04, Q3, approved 2026-11-13).** UC-G08 E2 asks to tell the user to wait when a reset is
  requested again within 60 s; D-03 asks for one uniform answer to "forgot password". D-03 wins: `POST
  /auth/password/forgot` always returns the same 202 and body; inside the 60 s cooldown no new code and no mail are
  produced, without any visible signal. Unknown email and locked account (E3) give the same answer. The 60 s rule
  stays visible (429 `TOKEN_RESEND_TOO_SOON`) only on the authenticated email-verification resend (UC-G06).
