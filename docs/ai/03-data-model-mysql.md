# 03 — Data model on MySQL 8.4 (port of EDU-DDD-001 v1.0)

EDU-DDD-001 was designed for PostgreSQL 16. The teacher requires MySQL, so ADR-001 ports it. **Table names, column
names, nullability, defaults, FK actions and constraint names stay exactly as in DDD §5–§9.** Only the mechanism
changes where MySQL lacks the feature. Constraint names (`uq_*`, `ck_*`, `fk_<table>_<column>`, `ix_*`) are kept
because the error translator maps them to API error codes.

## 3.1 Global DDL conventions
```sql
-- every table
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```
| DDD (PostgreSQL) | MySQL 8.4 | Java |
|---|---|---|
| `BIGINT GENERATED ALWAYS AS IDENTITY` | `BIGINT NOT NULL AUTO_INCREMENT` | `Long` + `@GeneratedValue(strategy = IDENTITY)` |
| `BIGINT` money (VND) | `BIGINT` | `Long` / `long` |
| `INTEGER` / `SMALLINT` | `INT` / `SMALLINT` | `Integer` / `Short` |
| `TIMESTAMPTZ … DEFAULT now()` | `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)` (stored UTC) | `Instant` |
| `updated_at` + trigger | `DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)` | `Instant` (read-only in entity or `@UpdateTimestamp`) |
| `BOOLEAN` | `BOOLEAN` (= `TINYINT(1)`) | `boolean` / `Boolean` |
| `VARCHAR(n)`, `TEXT`, `CHAR(64)` | same | `String` |
| `VARCHAR(20) + CHECK IN (…)` | same type; enum-like CHECK as `CAST(col AS BINARY) IN (…)` (collation trap below) | Java enum + `CodeEnumConverter` |
| regex `~ '…'` | `REGEXP_LIKE(col, '…', 'c')` (`'c'` = case-sensitive) | — |
| `btrim(x)` | `TRIM(x)` | — |

**Why `DATETIME(6)` and not `TIMESTAMP`:** `TIMESTAMP` overflows in 2038. `DATETIME` has no time zone, so UTC must be
forced everywhere:
- JDBC URL: `jdbc:mysql://localhost:3306/edumy?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`
- `spring.jpa.properties.hibernate.jdbc.time_zone=UTC`
- MySQL container: `--default-time-zone=+00:00` (so `CURRENT_TIMESTAMP(6)` defaults are UTC too).

**Collation trap (important):** `utf8mb4_0900_ai_ci` compares case- and accent-insensitively, so
`CHECK (email = LOWER(TRIM(email)))` would always pass. Write case-sensitive checks with binary comparison:
```sql
CONSTRAINT ck_users_email_normalized CHECK (CAST(email AS BINARY) = CAST(LOWER(TRIM(email)) AS BINARY))
```
and regex checks with the `'c'` match type. The application still normalizes email before saving.

**Enum-like CHECK:** the same trap lets `status = 'Active'` (or `'actívé'`) pass `status IN ('active','locked')`,
which PostgreSQL would reject. Every CHECK that lists allowed values is written with a binary comparison, in every
table (including ADR tables such as `refresh_tokens`):
```sql
CONSTRAINT ck_users_status CHECK (CAST(status AS BINARY) IN ('active','locked'))
```

**Naming when the DDD gives none:** a column marked only `UQ` in DDD §5 gets `uq_<table>_<column>` with the full
column name (`uq_users_email`, `uq_roles_code`, `uq_user_tokens_token_hash`, `uq_refresh_tokens_token_hash`).
Do not abbreviate. Constraint names the DDD does spell out are kept verbatim, even when they abbreviate
(`uq_carts_student`, `uq_cci_course_instructor`). MySQL ignores the name of a PRIMARY KEY (always `PRIMARY`); still
write `CONSTRAINT pk_<table> PRIMARY KEY (id)` for readability; nothing may depend on the PK name.

### DDD inconsistencies
EDU-DDD-001 sometimes states a rule in prose (e.g. §7 "giá trị hợp lệ được thực thi bằng CHECK") but omits it from
the table's constraint list in §5. **The stated rule wins over the incomplete list:** implement it, name it by the
convention, and mark it `DERIVED (DDD §n)` in the migration comment and in the session checklist.
Known cases:
| Object | Source | Note |
|---|---|---|
| `ck_roles_code` CHECK (`student`,`instructor`,`admin`) | DDD §7 | §5.2 lists no CHECK for `roles.code` (V1) |

Also missing from the DDD file: §10 (queries), §11 (transactions T1–T7), Appendix A (DDL) and Appendix B (test
cases), although §2.4/§6/§8 refer to them. Ask before relying on any of them.

## 3.2 PostgreSQL-only features and their MySQL replacement
| DDD feature | Objects | MySQL implementation |
|---|---|---|
| Partial UNIQUE index | `uq_orders_one_pending` (student_id WHERE status='pending'), `uq_payment_one_pending` (order_id WHERE status='pending') | STORED generated column + UNIQUE (NULLs never collide). See 3.3 |
| Partial (non-unique) index | `ix_courses_published`, `ix_courses_pending`, `ix_orders_pending_created`, `ix_payment_review_queue`, `ix_payment_pending_expiry`, `ix_user_tokens_active`, `ix_question_answers_instructor_visible` | Normal composite index whose column order matches the real query (equality columns first, range/sort columns last; an FK column leading it also serves as the FK index), e.g. `ix_user_tokens_active (user_id, token_type, used_at)`, `ix_courses_published (status, published_at)`, `ix_payment_review_queue (status, handled_at, created_at)` |
| `UNIQUE … DEFERRABLE INITIALLY DEFERRED` | `uq_chapters_course_order`, `uq_lessons_chapter_order` | Plain UNIQUE + two-phase reorder in one transaction (3.4) |
| PL/pgSQL triggers | `trg_cci_not_main_instructor`, `trg_courses_prevent_delete_published`, `trg_lesson_progresses_lesson_course`, `trg_course_questions_lesson_course` | MySQL triggers, one per event (`_bi` before insert, `_bu` before update, `_bd` before delete), raising `SIGNAL SQLSTATE '45000'` with `MESSAGE_TEXT` starting with the BR code (3.5) |
| `pg_trgm` + GIN on `title_normalized` | `ix_courses_title_trgm` | `title_normalized LIKE CONCAT('%', ?, '%')` (≈300 courses in year 1 → a scan is a few ms). Optional later: `FULLTEXT … WITH PARSER ngram`. Keep a plain index `ix_courses_status_title (status, title_normalized)` |
| `ON CONFLICT DO NOTHING` | enrollment insert (BR-17) | `INSERT IGNORE` in a native query, or catch `DataIntegrityViolationException` on `uq_enrollments_student_course` and treat as "already enrolled" |
| `FOR UPDATE SKIP LOCKED` | schedulers | Supported as-is |

Note: `utf8mb4_0900_ai_ci` does **not** fold `đ` to `d`. `title_normalized` stays an application-computed column:
`VietnameseNormalizer` = NFD → strip combining marks → `đ/Đ → d` → lowercase → collapse spaces. Use the same function
on the search keyword.

## 3.3 Generated columns for "at most one pending"
```sql
ALTER TABLE orders
  ADD COLUMN pending_student_id BIGINT
    GENERATED ALWAYS AS (CASE WHEN status = 'pending' THEN student_id END) STORED,
  ADD CONSTRAINT uq_orders_one_pending UNIQUE (pending_student_id);

ALTER TABLE payment_transactions
  ADD COLUMN pending_order_id BIGINT
    GENERATED ALWAYS AS (CASE WHEN status = 'pending' THEN order_id END) STORED,
  ADD CONSTRAINT uq_payment_one_pending UNIQUE (pending_order_id);
```
Entities **do not map** these columns (Hibernate `validate` ignores unmapped columns).
If one must be read: `@Column(insertable = false, updatable = false)`.

## 3.4 Reordering chapters / lessons without DEFERRABLE (UC-I08, UC-I12)
The DDD portability note suggests "set a temporary negative order", but that would violate
`ck_chapters_order CHECK (sort_order > 0)`. **Chosen: temporary positive offset**, which keeps the CHECK intact.
Inside one `@Transactional` method:
1. Lock siblings: `SELECT id FROM chapters WHERE course_id = ? FOR UPDATE`.
2. Validate that the submitted id list equals exactly the current siblings (no missing, no extra, no duplicates).
3. Phase 1: `UPDATE chapters SET sort_order = sort_order + 1000000 WHERE course_id = ?`.
4. Phase 2: `UPDATE chapters SET sort_order = ? WHERE id = ?` for positions 1..n (JDBC batch).
Repository methods use `@Modifying(clearAutomatically = true) @Query`. Same algorithm for lessons per chapter.

## 3.5 Trigger templates
```sql
CREATE TRIGGER trg_cci_not_main_instructor_bi
BEFORE INSERT ON course_co_instructors FOR EACH ROW
BEGIN
  IF NEW.instructor_id = (SELECT main_instructor_id FROM courses WHERE id = NEW.course_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'BR-02: co-instructor must differ from main instructor';
  END IF;
END;
-- + _bu (BEFORE UPDATE) with the same body

CREATE TRIGGER trg_courses_prevent_delete_published_bd
BEFORE DELETE ON courses FOR EACH ROW
BEGIN
  IF OLD.published_at IS NOT NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'BR-19: course was published and cannot be deleted';
  END IF;
END;

CREATE TRIGGER trg_lesson_progresses_lesson_course_bi
BEFORE INSERT ON lesson_progresses FOR EACH ROW
BEGIN
  IF (SELECT c.course_id FROM lessons l JOIN chapters c ON c.id = l.chapter_id WHERE l.id = NEW.lesson_id)
     <> (SELECT e.course_id FROM enrollments e WHERE e.id = NEW.enrollment_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'BR-24: lesson does not belong to the enrollment course';
  END IF;
END;
-- + _bu ; course_questions: same idea, only when NEW.lesson_id IS NOT NULL (BR-37)
```
Flyway's MySQL parser handles `BEGIN … END` blocks; put each trigger in the same versioned file as its table or in a
dedicated `V3__triggers.sql`. **MySQL does not fire triggers for rows changed by FK cascades** — acceptable here:
cascades only delete children or set `course_questions.lesson_id` to NULL.

CHECK + FK caveat: MySQL rejects a CHECK on a column that has an FK with `CASCADE`/`SET NULL` (error 3823).
Reviewed: no DDD CHECK touches such a column. If you add one, expect this error.

## 3.6 Tables (owner module, MySQL-specific notes)
| # | Table | Owner | MySQL notes |
|---|---|---|---|
| 1 | users | identity | email binary CHECK (3.1); status CHECK active/locked |
| 2 | roles | identity | seed: student, instructor, admin; `ck_roles_code` binary IN — DERIVED (DDD §7) |
| 3 | user_roles | identity | UQ (user_id, role_id); revoke = `is_active=false` |
| 4 | user_tokens | identity | `token_hash CHAR(64)` UQ, `REGEXP_LIKE(token_hash,'^[0-9a-f]{64}$','c')`; `ix_user_tokens_active (user_id, token_type, used_at)` |
| 4b | refresh_tokens | identity | **new, ADR-002**; `uq_refresh_tokens_token_hash` |
| 5 | categories | authoring | slug regex CHECK |
| 6 | courses | authoring | CHECKs on price/note/published_at/submitted_at/slug; `title_normalized`; trigger `_bd` |
| 7 | course_co_instructors | authoring | `ck_cci_not_self`; triggers `_bi/_bu` |
| 8 | chapters | authoring | UQ (course_id, sort_order) — reorder 3.4 |
| 9 | lessons | authoring | `ck_lessons_content_match`; UQ (chapter_id, sort_order) |
| 10 | carts | commerce | UQ student_id |
| 11 | cart_items | commerce | UQ (cart_id, course_id) |
| 12 | orders | commerce | `ck_orders_state`; generated `pending_student_id` |
| 13 | order_items | commerce | UQ (order_id, course_id) |
| 14 | payment_transactions | commerce | handled_* all-or-nothing CHECK; generated `pending_order_id` |
| 15 | enrollments | learning | `ck_enrollments_source_order` |
| 16 | lesson_progresses | learning | `ck_lesson_progresses_completed`; triggers |
| 17 | course_reviews | learning | UQ enrollment_id; rating 1–5 |
| 18 | course_questions | learning | `lesson_id` ON DELETE SET NULL; triggers when lesson_id not null |
| 19 | question_answers | learning | `author_type` snapshot |
| 20 | certificates | learning | code regex `'^EDU-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$'` with `'c'` |

All 34 FKs and their ON DELETE actions: copy DDD §6 verbatim. Name them `fk_<table>_<column>`.

## 3.7 Flyway layout
```
src/main/resources/db/migration/   V1__identity.sql, V2__authoring.sql, V3__commerce.sql, V4__learning.sql,
                                   V5__triggers.sql, V6__reference_data.sql (roles)
src/main/resources/db/seed/        V1000__dev_seed.sql   (only in profile dev: spring.flyway.locations adds it)
```
Seed rules: deterministic explicit IDs; every status value of every state machine appears at least once; one admin
(D-01); passwords are real BCrypt (cost 12) hashes **generated by running code**, never typed by an AI
(e.g. `htpasswd -bnBC 12 "" 'Edumy@123' | tr -d ':\n' | sed 's/^\$2y/\$2a/'`).

## 3.8 Hibernate `validate` first-run checklist
Run the app once after entities are written; if validation fails, fix the **mapping**, not the DDL:
- Boolean vs `tinyint(1)`: `spring.jpa.properties.hibernate.type.preferred_boolean_jdbc_type=TINYINT` if needed.
- `Instant` vs `datetime(6)`: `spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP` if needed.
- `CHAR(64)`: `@JdbcTypeCode(SqlTypes.CHAR) @Column(length = 64)`.
- `TEXT`: `@JdbcTypeCode(SqlTypes.LONG32VARCHAR)` or `@Column(columnDefinition = "TEXT")`. Do not use `@Lob` (maps to LONGTEXT).
- `SMALLINT`: field type `Short`.
Record whatever setting was needed in ADR-001 so the team does it once.
