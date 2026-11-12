# Schema checklist — EDU-DDD-001 → MySQL 8.4 (P01)

One section per migration file. Every constraint / index / trigger / rule of the DDD for the tables of that file, how
it is implemented, the line in the migration, and a status:

| Status | Meaning |
|---|---|
| OK | Ported 1:1 (same name, same semantics; only the MySQL mechanism differs per docs/ai/03 §3.1–3.3) |
| PARTIAL | Ported with a documented loss of strength or shape (e.g. partial index → composite index, GIN → B-tree) |
| DERIVED | Not in the DDD constraint list, but required by a rule the DDD states elsewhere (docs/ai/03 "DDD inconsistencies") |
| APP-LEVEL | The DDD itself assigns the rule to the application (§8 "Ứng dụng"); nothing in SQL |

`PK` names are written for readability only (MySQL always names the primary key `PRIMARY`).
`updated_at` triggers of the DDD are replaced by `ON UPDATE CURRENT_TIMESTAMP(6)` on every `updated_at` column (OK).

## V1__identity.sql (users, roles, user_roles, user_tokens; refresh_tokens ADR-002)

| DDD constraint/index (source §) | MySQL implementation | line | status |
|---|---|---|---|
| pk_users (§5.1) | `PRIMARY KEY (id)` | 21 | OK |
| users.email UQ (§5.1, §8 #1) | `uq_users_email UNIQUE (email)` | 22 | OK |
| ck_users_status (§5.1) | `CAST(status AS BINARY) IN ('active','locked')` | 23 | OK |
| ck_users_email_normalized (§5.1, §8 #1) | `CAST(email AS BINARY) = CAST(LOWER(TRIM(email)) AS BINARY)` | 24 | OK |
| ck_users_full_name (§5.1) | `CHAR_LENGTH(TRIM(full_name)) > 0` | 25 | OK |
| pk_roles (§5.2) | `PRIMARY KEY (id)` | 34 | OK |
| roles.code UQ (§5.2) | `uq_roles_code UNIQUE (code)` | 35 | OK |
| roles.code values (§7) | `ck_roles_code CAST(code AS BINARY) IN ('student','instructor','admin')` | 37 | DERIVED |
| pk_user_roles (§5.3) | `PRIMARY KEY (id)` | 49 | OK |
| uq_user_roles_user_role (§5.3, §8 #2) | `UNIQUE (user_id, role_id)`; also index of fk #1 | 51 | OK |
| FK #1 user_roles.user_id → users RESTRICT (§6) | `fk_user_roles_user_id` | 52 | OK |
| FK #2 user_roles.role_id → roles RESTRICT (§6) | `fk_user_roles_role_id` | 53 | OK |
| FK #3 user_roles.assigned_by → users RESTRICT (§6) | `fk_user_roles_assigned_by` | 54 | OK |
| ix_user_roles_role (§9) | `(role_id)` | 55 | OK |
| ix_user_roles_assigned_by (§9) | `(assigned_by)` | 56 | OK |
| pk_user_tokens (§5.4) | `PRIMARY KEY (id)` | 68 | OK |
| user_tokens.token_hash UQ (§5.4) | `uq_user_tokens_token_hash UNIQUE (token_hash)` | 69 | OK |
| FK #4 user_tokens.user_id → users CASCADE (§6) | `fk_user_tokens_user_id` | 71 | OK |
| ck_user_tokens_type (§5.4) | `CAST(token_type AS BINARY) IN (...)` | 72 | OK |
| ck_user_tokens_expiry (§5.4) | `expires_at > created_at` | 73 | OK |
| ck_user_tokens_hash_hex (§5.4) | `REGEXP_LIKE(token_hash, '^[0-9a-f]{64}$', 'c')` | 74 | OK |
| ix_user_tokens_active (§5.4, §9) `(user_id, token_type) WHERE used_at IS NULL` | composite `(user_id, token_type, used_at)`; also index of fk #4 | 77 | PARTIAL |
| refresh_tokens table (ADR-002, docs/ai/04 §4.1) | table + `uq_refresh_tokens_token_hash`, `fk_refresh_tokens_user_id` CASCADE, `ck_refresh_tokens_hash_hex`, `ck_refresh_tokens_reason`, `ck_refresh_tokens_revoked_pair`, `ix_refresh_tokens_user`, `ix_refresh_tokens_family` | 81–103 | OK (ADR) |
| §8 #3 token single use, not expired (BR-34) | conditional atomic UPDATE in the app (T7) | — | APP-LEVEL |
| §8 #4 at least one active admin; admin cannot lock self (BR-36) | row lock in the app transaction | — | APP-LEVEL |

## V2__authoring.sql (categories, courses, course_co_instructors, chapters, lessons)

| DDD constraint/index (source §) | MySQL implementation | line | status |
|---|---|---|---|
| pk_categories (§5.5) | `PRIMARY KEY (id)` | 24 | OK |
| categories.name UQ (§5.5) | `uq_categories_name UNIQUE (name)` (case-insensitive under ai_ci, as intended for display names) | 25 | OK |
| categories.slug UQ (§5.5) | `uq_categories_slug UNIQUE (slug)` | 26 | OK |
| ck_categories_slug (§5.5) | `REGEXP_LIKE(slug, '^[a-z0-9]+(-[a-z0-9]+)*$', 'c')` | 27 | OK |
| pk_courses (§5.6) | `PRIMARY KEY (id)` | 50 | OK |
| courses.slug UQ (§5.6) | `uq_courses_slug UNIQUE (slug)` | 51 | OK |
| FK #5 courses.main_instructor_id → users RESTRICT (§6, §8 #5) | `fk_courses_main_instructor_id` (NOT NULL column) | 52 | OK |
| FK #6 courses.category_id → categories RESTRICT (§6) | `fk_courses_category_id` | 53 | OK |
| FK #7 courses.reviewed_by → users RESTRICT (§6) | `fk_courses_reviewed_by` | 54 | OK |
| ck_courses_status (§5.6) | `CAST(status AS BINARY) IN (...)` | 55 | OK |
| ck_courses_price (§5.6, §8 #8 BR-07) | `price = 0 OR (price >= 10000 AND price % 1000 = 0)` | 57 | OK |
| ck_courses_note_required (§5.6, §8 #9 BR-08) | `CAST(status AS BINARY) NOT IN ('rejected','hidden') OR review_note IS NOT NULL` | 58 | OK |
| ck_courses_published_at (§5.6) | `CAST(status AS BINARY) NOT IN ('published','hidden') OR published_at IS NOT NULL` | 60 | OK |
| ck_courses_submitted_at (§5.6) | `CAST(status AS BINARY) <> 'pending_review' OR submitted_at IS NOT NULL` | 62 | OK |
| ck_courses_slug (§5.6) | `REGEXP_LIKE(..., 'c')` | 63 | OK |
| ix_courses_main_instructor (§9) | `(main_instructor_id)` | 64 | OK |
| ix_courses_category_status (§9) | `(category_id, status)`; also index of FK #6 | 66 | OK |
| ix_courses_reviewed_by (§9) | `(reviewed_by)` | 67 | OK |
| ix_courses_published (§9) `(published_at DESC) WHERE status='published'` | composite `(status, published_at DESC)` | 69 | PARTIAL |
| ix_courses_pending (§9) `(submitted_at) WHERE status='pending_review'` | composite `(status, submitted_at)` | 71 | PARTIAL |
| ix_courses_title_trgm (§9) GIN pg_trgm on title_normalized | `ix_courses_status_title (status, title_normalized)` + `LIKE '%kw%'` (docs/ai/03 §3.2); title_normalized stays application-computed | 73 | PARTIAL |
| trg_courses_prevent_delete_published (§5.6, §8 #10 BR-19) | V5 `trg_courses_prevent_delete_published_bd` | V5 | see V5 |
| pk_course_co_instructors (§5.7) | `PRIMARY KEY (id)` | 87 | OK |
| uq_cci_course_instructor (§5.7, §8 #7 D-18) | `UNIQUE (course_id, instructor_id)`; also index of FK #8 | 89 | OK |
| FK #8 course_co_instructors.course_id → courses CASCADE (§6) | `fk_course_co_instructors_course_id` (no CHECK on course_id, error 3823) | 91 | OK |
| FK #9 course_co_instructors.instructor_id → users RESTRICT (§6) | `fk_course_co_instructors_instructor_id` | 92 | OK |
| FK #10 course_co_instructors.invited_by → users RESTRICT (§6) | `fk_course_co_instructors_invited_by` | 94 | OK |
| ck_cci_status (§5.7) | `CAST(invitation_status AS BINARY) IN (...)` | 95 | OK |
| ck_cci_not_self (§5.7) | `instructor_id <> invited_by` | 96 | OK |
| ck_cci_responded (§5.7) | `CAST(invitation_status AS BINARY) NOT IN ('accepted','declined') OR responded_at IS NOT NULL` | 97 | OK |
| ix_cci_instructor_status (§9) | `(instructor_id, invitation_status)`; also index of FK #9 | 100 | OK |
| ix_cci_invited_by (§9) | `(invited_by)` | 101 | OK |
| trg_cci_not_main_instructor (§5.7, §8 #6 BR-02) | V5 `_bi` / `_bu` | V5 | see V5 |
| pk_chapters (§5.8) | `PRIMARY KEY (id)` | 112 | OK |
| uq_chapters_course_order (§5.8, §8 #12) `DEFERRABLE INITIALLY DEFERRED` | plain `UNIQUE (course_id, sort_order)` + two-phase reorder with +1 000 000 offset (docs/ai/03 §3.4, tested) | 115 | PARTIAL |
| FK #11 chapters.course_id → courses CASCADE (§6) | `fk_chapters_course_id` | 117 | OK |
| ck_chapters_order (§5.8) | `sort_order > 0` (kept: the reorder offset is positive) | 118 | OK |
| pk_lessons (§5.9) | `PRIMARY KEY (id)` | 134 | OK |
| uq_lessons_chapter_order (§5.9, §8 #12) `DEFERRABLE` | plain `UNIQUE (chapter_id, sort_order)` + two-phase reorder (§3.4, tested) | 137 | PARTIAL |
| FK #12 lessons.chapter_id → chapters CASCADE (§6) | `fk_lessons_chapter_id` | 139 | OK |
| ck_lessons_type (§5.9) | `CAST(content_type AS BINARY) IN ('video','article')` | 140 | OK |
| ck_lessons_content_match (§5.9, §8 #13 D-22) | same expression, binary comparisons | 141 | OK |
| ck_lessons_duration (§5.9) | `duration_seconds IS NULL OR duration_seconds >= 0` | 144 | OK |
| ck_lessons_order (§5.9) | `sort_order > 0` | 145 | OK |
| updated_at triggers (§5) on the 5 tables | `ON UPDATE CURRENT_TIMESTAMP(6)` | 23, 49, 86, 111, 133 | OK |
| §8 #11 pending_review course is read-only (BR-33) | status check before every write | — | APP-LEVEL |

Notes
- An unknown `lessons.content_type` breaks both `ck_lessons_type` and `ck_lessons_content_match` (the DDD expression of
  the latter only holds for `video`/`article`); MySQL reports `ck_lessons_content_match`. The error translator must map
  both names to the same error. `ck_lessons_type` is proven enforced by a metadata test.
