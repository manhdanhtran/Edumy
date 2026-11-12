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
| pk_users (§5.1) | `PRIMARY KEY (id)` | 23 | OK |
| users.email UQ (§5.1, §8 #1) | `uq_users_email UNIQUE (email)` on a `COLLATE utf8mb4_bin` column: exact-character match (`é@x.com` ≠ `e@x.com`), like PostgreSQL; decision after the P10 review | 24 | OK |
| ck_users_status (§5.1) | `CAST(status AS BINARY) IN ('active','locked')` | 25 | OK |
| ck_users_email_normalized (§5.1, §8 #1) | `CAST(email AS BINARY) = CAST(LOWER(TRIM(email)) AS BINARY)` | 26 | OK |
| ck_users_full_name (§5.1) | `CHAR_LENGTH(TRIM(full_name)) > 0` | 27 | OK |
| pk_roles (§5.2) | `PRIMARY KEY (id)` | 36 | OK |
| roles.code UQ (§5.2) | `uq_roles_code UNIQUE (code)` | 37 | OK |
| roles.code values (§7) | `ck_roles_code CAST(code AS BINARY) IN ('student','instructor','admin')` | 39 | DERIVED |
| pk_user_roles (§5.3) | `PRIMARY KEY (id)` | 51 | OK |
| uq_user_roles_user_role (§5.3, §8 #2) | `UNIQUE (user_id, role_id)`; also index of fk #1 | 53 | OK |
| FK #1 user_roles.user_id → users RESTRICT (§6) | `fk_user_roles_user_id` | 54 | OK |
| FK #2 user_roles.role_id → roles RESTRICT (§6) | `fk_user_roles_role_id` | 55 | OK |
| FK #3 user_roles.assigned_by → users RESTRICT (§6) | `fk_user_roles_assigned_by` | 56 | OK |
| ix_user_roles_role (§9) | `(role_id)` | 57 | OK |
| ix_user_roles_assigned_by (§9) | `(assigned_by)` | 58 | OK |
| pk_user_tokens (§5.4) | `PRIMARY KEY (id)` | 70 | OK |
| user_tokens.token_hash UQ (§5.4) | `uq_user_tokens_token_hash UNIQUE (token_hash)` | 71 | OK |
| FK #4 user_tokens.user_id → users CASCADE (§6) | `fk_user_tokens_user_id` | 73 | OK |
| ck_user_tokens_type (§5.4) | `CAST(token_type AS BINARY) IN (...)` | 74 | OK |
| ck_user_tokens_expiry (§5.4) | `expires_at > created_at` | 75 | OK |
| ck_user_tokens_hash_hex (§5.4) | `REGEXP_LIKE(token_hash, '^[0-9a-f]{64}$', 'c')` | 76 | OK |
| ix_user_tokens_active (§5.4, §9) `(user_id, token_type) WHERE used_at IS NULL` | composite `(user_id, token_type, used_at)`; also index of fk #4 | 79 | PARTIAL |
| refresh_tokens table (ADR-002, docs/ai/04 §4.1) | table + `uq_refresh_tokens_token_hash`, `fk_refresh_tokens_user_id` CASCADE, `ck_refresh_tokens_hash_hex`, `ck_refresh_tokens_reason`, `ck_refresh_tokens_revoked_pair`, `ix_refresh_tokens_user`, `ix_refresh_tokens_family (family_id, revoked_at)` (serves "family has a non-revoked token", docs/ai/04 §4.1) | 83–106 | OK (ADR) |
| §8 #3 token single use, not expired (BR-34) | conditional atomic UPDATE in the app (T7) | — | APP-LEVEL |
| §8 #4 at least one active admin; admin cannot lock self (BR-36) | row lock in the app transaction | — | APP-LEVEL |

## V2__authoring.sql (categories, courses, course_co_instructors, chapters, lessons)

| DDD constraint/index (source §) | MySQL implementation | line | status |
|---|---|---|---|
| pk_categories (§5.5) | `PRIMARY KEY (id)` | 24 | OK |
| categories.name UQ (§5.5) | `uq_categories_name UNIQUE (name)` (case-insensitive under ai_ci, as intended for display names) | 25 | OK |
| categories.slug UQ (§5.5) | `uq_categories_slug UNIQUE (slug)` | 26 | OK |
| ck_categories_slug (§5.5) | `REGEXP_LIKE(slug, '^[a-z0-9]+(-[a-z0-9]+)*\\z', 'c')` (`\z`: ICU `$` would accept a trailing `\n`, PostgreSQL `$` does not) | 27 | OK |
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
| ck_courses_slug (§5.6) | `REGEXP_LIKE(..., 'c')`, pattern ends in `\z` like ck_categories_slug | 63 | OK |
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

## V3__commerce.sql (carts, cart_items, orders, order_items, payment_transactions)

| DDD constraint/index (source §) | MySQL implementation | line | status |
|---|---|---|---|
| pk_carts (§5.10) | `PRIMARY KEY (id)` | 21 | OK |
| uq_carts_student (§5.10 UQ, §8 #14 BR-22) | `UNIQUE (student_id)` (name verbatim from §8); also index of FK #13 | 23 | OK |
| FK #13 carts.student_id → users RESTRICT (§6) | `fk_carts_student_id` | 24 | OK |
| pk_cart_items (§5.11) | `PRIMARY KEY (id)` | 34 | OK |
| uq_cart_items_cart_course (§5.11, §8 #14 BR-11) | `UNIQUE (cart_id, course_id)`; also index of FK #14 | 36 | OK |
| FK #14 cart_items.cart_id → carts CASCADE (§6) | `fk_cart_items_cart_id` (no CHECK on cart_id, error 3823) | 38 | OK |
| FK #15 cart_items.course_id → courses RESTRICT (§6) | `fk_cart_items_course_id` | 39 | OK |
| ix_cart_items_course (§5.11, §9) | `(course_id)`; also index of FK #15 | 41 | OK |
| pk_orders (§5.12) | `PRIMARY KEY (id)` | 59 | OK |
| orders.order_code UQ (§5.12) | `uq_orders_order_code UNIQUE (order_code)` (case-insensitive under ai_ci, see Notes) | 60 | OK |
| uq_orders_one_pending (§5.12, §8 #16 BR-29, §9) `(student_id) WHERE status='pending'` | STORED generated `pending_student_id` + `UNIQUE (pending_student_id)` (docs/ai/03 §3.3, ADR-001) | 58, 62 | OK (ADR) |
| FK #16 orders.student_id → users RESTRICT (§6) | `fk_orders_student_id` | 63 | OK |
| ck_orders_total (§5.12, §8 #18 BR-27 "CHECK > 0") | `total_amount > 0` | 64 | OK |
| ck_orders_status (§5.12) | `CAST(status AS BINARY) IN ('pending','paid','cancelled')` | 65 | OK |
| ck_orders_cancel_reason (§5.12) | `cancel_reason IS NULL OR CAST(cancel_reason AS BINARY) IN ('user','replaced','expired')` | 66 | OK |
| ck_orders_state (§5.12, §7, §8 #17 D-25) | same expression, binary status comparisons | 69 | OK |
| ix_orders_student_created (§5.12, §9) | `(student_id, created_at DESC)`; also index of FK #16 | 75 | OK |
| ix_orders_pending_created (§5.12, §9) `(created_at) WHERE status='pending'` | composite `(status, created_at)` | 77 | PARTIAL |
| pk_order_items (§5.13) | `PRIMARY KEY (id)` | 86 | OK |
| uq_order_items_order_course (§5.13) | `UNIQUE (order_id, course_id)`; also index of FK #17 | 88 | OK |
| FK #17 order_items.order_id → orders RESTRICT (§6) | `fk_order_items_order_id` | 89 | OK |
| FK #18 order_items.course_id → courses RESTRICT (§6) | `fk_order_items_course_id` | 90 | OK |
| ck_order_items_price (§5.13) | `unit_price > 0` | 91 | OK |
| ix_order_items_course (§5.13, §9) | `(course_id)`; also index of FK #18 | 93 | OK |
| pk_payment_transactions (§5.14) | `PRIMARY KEY (id)` | 114 | OK |
| uq_payment_ref (§5.14 UQ, §8 #20 NFR-04) | `UNIQUE (transaction_ref)` (name verbatim from §8; case-insensitive under ai_ci, see Notes) | 116 | OK |
| uq_payment_one_pending (§5.14, §8 #19 BR-16, §9) `(order_id) WHERE status='pending'` | STORED generated `pending_order_id` + `UNIQUE (pending_order_id)` (docs/ai/03 §3.3, ADR-001) | 113, 118 | OK (ADR) |
| FK #19 payment_transactions.order_id → orders RESTRICT (§6) | `fk_payment_transactions_order_id` | 119 | OK |
| FK #20 payment_transactions.handled_by → users RESTRICT (§6) | `fk_payment_transactions_handled_by` | 120 | OK |
| ck_payment_amount (§5.14) | `amount > 0` | 121 | OK |
| ck_payment_status (§5.14) | `CAST(status AS BINARY) IN (...)` | 122 | OK |
| ck_payment_action (§5.14) | `handled_action IS NULL OR CAST(handled_action AS BINARY) IN ('activated','refund_outside')` | 124 | OK |
| ck_payment_handling_complete (§5.14, §7, §8 #21 D-29) | same expression (all four handled_* null or all four set) | 127 | OK |
| ck_payment_handling_status (§5.14) | `handled_at IS NULL OR CAST(status AS BINARY) IN ('needs_review','success')` | 131 | OK |
| ck_payment_confirmed (§5.14, §7) | `CAST(status AS BINARY) <> 'success' OR confirmed_at IS NOT NULL` | 133 | OK |
| ck_payment_qr_expiry (§5.14) | `qr_expires_at > created_at` | 134 | OK |
| ix_payment_order_created (§5.14, §9) | `(order_id, created_at DESC)`; also index of FK #19 | 136 | OK |
| ix_payment_review_queue (§5.14, §9) `(created_at) WHERE status='needs_review' AND handled_at IS NULL` | composite `(status, handled_at, created_at)` (order from docs/ai/03 §3.2) | 138 | PARTIAL |
| ix_payment_pending_expiry (§5.14, §9) `(qr_expires_at) WHERE status='pending'` | composite `(status, qr_expires_at)` | 140 | PARTIAL |
| ix_payment_handled_by (§5.14, §9) | `(handled_by)`; index of FK #20 | 141 | OK |
| updated_at triggers (§5) on carts, orders, payment_transactions | `ON UPDATE CURRENT_TIMESTAMP(6)` (cart_items has only added_at, order_items no timestamp) | 20, 56, 111 | OK |
| §8 #15 no course already owned / own course in the cart (BR-10, BR-32) | query Q4 + enrollments check before inserting cart_items | — | APP-LEVEL |
| §8 #18 order has ≥ 1 line; total_amount = sum of unit_price (BR-27) | T1 transaction (only `ck_orders_total` > 0 is in SQL) | — | APP-LEVEL |
| §6 #17 note: order_items.order_id cardinality 1 — 1..N | not expressible by an FK; enforced in T1 (DDD §6 note) | — | APP-LEVEL |
| §8 #20 idempotent payment result — row lock part (NFR-04) | `SELECT … FOR UPDATE` in T2 (the UNIQUE part is `uq_payment_ref` above) | — | APP-LEVEL |
| §8 #31 handled_by has the admin role (BR-26) | role check at the time of reconciliation | — | APP-LEVEL |

Notes
- An unknown `orders.status` breaks both `ck_orders_status` and `ck_orders_state` (the DDD expression of the latter only
  holds for `pending`/`paid`/`cancelled`); MySQL reports only one of them, so the test accepts either name
  (`assertCheckViolatedAnyOf`). The error translator must map both names to the same error. `ck_orders_status` is proven
  enforced and binary by a metadata test. No other V3 CHECK overlaps: every other violation test fills the companion
  columns so that only the target CHECK fails.
- The generated columns keep the verbatim expression of docs/ai/03 §3.3 (`status = 'pending'`, collation comparison);
  `ck_orders_status` / `ck_payment_status` already reject every non-exact status, so only `pending` can produce a key.
  Tests: two pending rows collide; pending beside paid/cancelled (success/failed/expired/needs_review) and rows of
  different students/orders do not; leaving `pending` frees the slot; a row moved back to `pending` collides.
  Metadata test: `EXTRA = 'STORED GENERATED'`; column-list test proves no other column was added.
- `uq_orders_order_code` and `uq_payment_ref` compare under `utf8mb4_0900_ai_ci`, so codes/refs differing only in case
  or accents collide (stricter than PostgreSQL). Both values are generated by the application, so nothing valid is lost;
  tested as a collation-trap case.

## V4__learning.sql (enrollments, lesson_progresses, course_reviews, course_questions, question_answers, certificates)

| DDD constraint/index (source §) | MySQL implementation | line | status |
|---|---|---|---|
| pk_enrollments (§5.15) | `PRIMARY KEY (id)` | 25 | OK |
| uq_enrollments_student_course (§5.15, §8 #22 BR-17) | `UNIQUE (student_id, course_id)`; also index of FK #21 | 27 | OK |
| FK #21 enrollments.student_id → users RESTRICT (§6) | `fk_enrollments_student_id` | 28 | OK |
| FK #22 enrollments.course_id → courses RESTRICT (§6) | `fk_enrollments_course_id` | 29 | OK |
| FK #23 enrollments.order_id → orders RESTRICT (§6) | `fk_enrollments_order_id` (nullable column) | 30 | OK |
| ck_enrollments_source (§5.15, §7) | `CAST(enroll_source AS BINARY) IN ('free','purchase')` | 31 | OK |
| ck_enrollments_source_order (§5.15, §8 #23 BR-14 DB part) | same expression, binary source comparisons | 33 | OK |
| ix_enrollments_course (§5.15, §9) | `(course_id)`; also index of FK #22 | 37 | OK |
| ix_enrollments_order (§5.15, §9) | `(order_id)`; also index of FK #23 | 39 | OK |
| pk_lesson_progresses (§5.16) | `PRIMARY KEY (id)` | 52 | OK |
| uq_lesson_progresses_enrollment_lesson (§5.16, §8 #24 BR-24) | `UNIQUE (enrollment_id, lesson_id)`; also index of FK #24 | 54 | OK |
| FK #24 lesson_progresses.enrollment_id → enrollments RESTRICT (§6) | `fk_lesson_progresses_enrollment_id` | 55 | OK |
| FK #25 lesson_progresses.lesson_id → lessons CASCADE (§6) | `fk_lesson_progresses_lesson_id` (no CHECK on lesson_id, error 3823) | 58 | OK |
| ck_lesson_progresses_completed (§5.16, §7) | `is_completed = (completed_at IS NOT NULL)` | 59 | OK |
| ix_lesson_progresses_lesson (§5.16, §9) | `(lesson_id)`; also index of FK #25 | 61 | OK |
| ix_lesson_progresses_resume (§5.16, §9 D-34) | `(enrollment_id, last_accessed_at DESC)` | 63 | OK |
| trg_lesson_progresses_lesson_course (§5.16, §8 #24 BR-24) | V5 `trg_lesson_progresses_lesson_course_bi` / `_bu` | V5 | see V5 |
| pk_course_reviews (§5.17) | `PRIMARY KEY (id)` | 79 | OK |
| uq_course_reviews_enrollment (§5.17 UQ, §8 #25 BR-18) | `UNIQUE (enrollment_id)` (name verbatim from §8); also index of FK #26 | 81 | OK |
| FK #26 course_reviews.enrollment_id → enrollments RESTRICT (§6) | `fk_course_reviews_enrollment_id` | 82 | OK |
| FK #27 course_reviews.moderated_by → users RESTRICT (§6) | `fk_course_reviews_moderated_by` | 84 | OK |
| ck_course_reviews_rating (§5.17, §8 #25 BR-25) | `rating BETWEEN 1 AND 5` | 86 | OK |
| ck_course_reviews_visibility (§5.17, §7) | `CAST(visibility_status AS BINARY) IN ('visible','withdrawn','hidden')` | 87 | OK |
| ck_course_reviews_moderation_pair (§5.17) | `(moderated_by IS NULL) = (moderated_at IS NULL)` | 89 | OK |
| ck_course_reviews_hidden_moderated (§5.17, §7, §8 #26 BR-35) | `CAST(visibility_status AS BINARY) <> 'hidden' OR moderated_by IS NOT NULL` | 91 | OK |
| ix_course_reviews_moderated_by (§5.17, §9) | `(moderated_by)`; index of FK #27 | 93 | OK |
| pk_course_questions (§5.18) | `PRIMARY KEY (id)` | 111 | OK |
| FK #28 course_questions.enrollment_id → enrollments RESTRICT (§6) | `fk_course_questions_enrollment_id` | 112 | OK |
| FK #29 course_questions.lesson_id → lessons SET NULL (§6) | `fk_course_questions_lesson_id` (no CHECK on lesson_id, error 3823) | 115 | OK |
| FK #30 course_questions.moderated_by → users RESTRICT (§6) | `fk_course_questions_moderated_by` | 116 | OK |
| ck_course_questions_visibility (§5.18, §7) | `CAST(visibility_status AS BINARY) IN ('visible','deleted','hidden')` | 117 | OK |
| ck_course_questions_title (§5.18) `length(btrim(title)) > 0` | `CHAR_LENGTH(TRIM(title)) > 0` | 119 | OK |
| ck_course_questions_content (§5.18) `length(btrim(content)) > 0` | `CHAR_LENGTH(TRIM(content)) > 0` | 120 | OK |
| ck_course_questions_moderation_pair (§5.18) | `(moderated_by IS NULL) = (moderated_at IS NULL)` | 121 | OK |
| ck_course_questions_hidden_moderated (§5.18, §7, §8 #26 BR-35) | `CAST(visibility_status AS BINARY) <> 'hidden' OR moderated_by IS NOT NULL` | 123 | OK |
| ix_course_questions_enrollment (§5.18, §9 UC-S18) | `(enrollment_id, created_at DESC)`; also index of FK #28 | 126 | OK |
| ix_course_questions_lesson (§5.18, §9) | `(lesson_id)`; also index of FK #29 | 128 | OK |
| ix_course_questions_moderated_by (§5.18, §9) | `(moderated_by)`; index of FK #30 | 129 | OK |
| trg_course_questions_lesson_course (§5.18, §8 #27 BR-37) | V5 `trg_course_questions_lesson_course_bi` / `_bu` (only when lesson_id IS NOT NULL) | V5 | see V5 |
| pk_question_answers (§5.19) | `PRIMARY KEY (id)` | 146 | OK |
| FK #31 question_answers.question_id → course_questions RESTRICT (§6) | `fk_question_answers_question_id` | 147 | OK |
| FK #32 question_answers.author_id → users RESTRICT (§6) | `fk_question_answers_author_id` | 149 | OK |
| FK #33 question_answers.moderated_by → users RESTRICT (§6) | `fk_question_answers_moderated_by` | 150 | OK |
| ck_question_answers_author_type (§5.19, §7) | `CAST(author_type AS BINARY) IN ('instructor','student')` | 151 | OK |
| ck_question_answers_visibility (§5.19, §7) | `CAST(visibility_status AS BINARY) IN ('visible','deleted','hidden')` | 152 | OK |
| ck_question_answers_content (§5.19) `length(btrim(content)) > 0` | `CHAR_LENGTH(TRIM(content)) > 0` | 154 | OK |
| ck_question_answers_moderation_pair (§5.19) | `(moderated_by IS NULL) = (moderated_at IS NULL)` | 155 | OK |
| ck_question_answers_hidden_moderated (§5.19, §7, §8 #26 BR-35) | `CAST(visibility_status AS BINARY) <> 'hidden' OR moderated_by IS NOT NULL` | 157 | OK |
| ix_question_answers_question (§5.19, §9) | `(question_id, created_at)`; also index of FK #31 | 160 | OK |
| ix_question_answers_instructor_visible (§5.19, §9 UC-I22) `(question_id) WHERE author_type='instructor' AND visibility_status='visible'` | composite `(question_id, author_type, visibility_status)` (all equality predicates) | 163 | PARTIAL |
| ix_question_answers_author (§5.19, §9) | `(author_id)`; index of FK #32 | 164 | OK |
| ix_question_answers_moderated_by (§5.19, §9) | `(moderated_by)`; index of FK #33 | 165 | OK |
| pk_certificates (§5.20) | `PRIMARY KEY (id)` | 177 | OK |
| uq_certificates_enrollment (§5.20 UQ, §8 #29 BR-39) | `UNIQUE (enrollment_id)` (name verbatim from §8); also index of FK #34 | 180 | OK |
| uq_certificates_code (§5.20 UQ, §8 #29 D-47) | `UNIQUE (certificate_code)` (name verbatim from §8) | 181 | OK |
| FK #34 certificates.enrollment_id → enrollments RESTRICT (§6) | `fk_certificates_enrollment_id` | 182 | OK |
| ck_certificates_code (§5.20, §8 #29 D-47) `~ '^EDU-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$'` | `REGEXP_LIKE(certificate_code, '^EDU-…{4}\\z', 'c')` (`\z` instead of `$`, see V2 slug) | 184 | OK |
| updated_at triggers (§5) on course_reviews, course_questions, question_answers | `ON UPDATE CURRENT_TIMESTAMP(6)` (enrollments has only enrolled_at, certificates issued_at; lesson_progresses.last_accessed_at has no ON UPDATE, set by the app) | 78, 110, 145 | OK |
| §8 #22 duplicate enrollment is a no-op (`ON CONFLICT DO NOTHING`, BR-17) | `INSERT IGNORE` in a native query, or catch the `uq_enrollments_student_course` violation as "already enrolled" (docs/ai/03 §3.2; INSERT IGNORE tested) | — | APP-LEVEL |
| §8 #23 purchase enrollment: order is paid, belongs to the student, has a line for the course (BR-14) | T2 transaction (only `ck_enrollments_source_order` is in SQL) | — | APP-LEVEL |
| §8 #26 hidden content cannot be edited / withdrawn by its author (BR-41) | status check before every write (the CHECK part is `ck_*_hidden_moderated` above) | — | APP-LEVEL |
| §8 #28 only an instructor of the course or the asker answers (BR-38) | query Q4 before inserting question_answers | — | APP-LEVEL |
| §8 #31 moderated_by has the admin role (BR-26) | role check at the time of moderation (course_reviews, course_questions, question_answers) | — | APP-LEVEL |

Notes
- An unknown `enrollments.enroll_source` breaks both `ck_enrollments_source` and `ck_enrollments_source_order` (the DDD
  expression of the latter only holds for `free`/`purchase`); MySQL reports only one of them, so the test accepts either
  name (`assertCheckViolatedAnyOf`). The error translator must map both names to the same error.
  `ck_enrollments_source` is proven enforced and binary by a metadata test. No other V4 CHECK overlaps: an invalid
  visibility value never equals the binary `'hidden'`, so `ck_*_hidden_moderated` holds and only the list CHECK fails.
- `lesson_id` (CASCADE in lesson_progresses, SET NULL in course_questions) appears in no CHECK, so error 3823 does not
  arise; tested: deleting a lesson removes its progress rows and keeps its questions with `lesson_id = NULL`. MySQL does
  not fire triggers for these cascaded changes (docs/ai/03 §3.5); the V5 BR-24 / BR-37 triggers do not need them.
- Until V5, a progress row / question whose lesson belongs to another course is accepted; the V4 tests always use a
  lesson of the enrollment's course so they stay valid once the triggers exist.
- `certificate_code` is `VARCHAR(19)` while the format has 18 characters; a 19-character code reaches the CHECK and is
  rejected (tested), longer values fail with "data too long" before any CHECK. The CHECK only admits upper-case ASCII,
  so `uq_certificates_code` under `utf8mb4_0900_ai_ci` cannot merge two valid codes.
- `INSERT IGNORE` also turns CHECK and FK errors of that statement into warnings; the application must validate the
  student/course first or prefer catching the `uq_enrollments_student_course` violation.
- A metadata test lists every index of the six tables: MySQL created no implicit FK index, so each FK reuses the index
  named in the migration comments.

## V5__triggers.sql (4 DDD business triggers → 7 MySQL triggers)

| DDD constraint/index (source §) | MySQL implementation | line | status |
|---|---|---|---|
| trg_cci_not_main_instructor BEFORE INSERT (§5.7, §8 #6 BR-02) | `trg_cci_not_main_instructor_bi`: `NEW.instructor_id = (SELECT main_instructor_id FROM courses WHERE id = NEW.course_id)` → `SIGNAL '45000' 'BR-02: …'` | 23 | OK |
| trg_cci_not_main_instructor BEFORE UPDATE (§5.7, §8 #6 BR-02) | `trg_cci_not_main_instructor_bu`, same body (instructor_id or course_id changed) | 32 | OK |
| trg_courses_prevent_delete_published (§5.6, §8 #10 BR-19) | `trg_courses_prevent_delete_published_bd`: `OLD.published_at IS NOT NULL` → `'BR-19: …'` (any status: published_at = "ever published") | 42 | OK |
| trg_lesson_progresses_lesson_course BEFORE INSERT (§5.16, §8 #24 BR-24) | `trg_lesson_progresses_lesson_course_bi`: course of lesson (lessons JOIN chapters) `<>` course of enrollment → `'BR-24: …'` | 51 | OK |
| trg_lesson_progresses_lesson_course BEFORE UPDATE (§5.16, §8 #24 BR-24) | `trg_lesson_progresses_lesson_course_bu`, same body | 61 | OK |
| trg_course_questions_lesson_course BEFORE INSERT (§5.18, §8 #27 BR-37) | `trg_course_questions_lesson_course_bi`, only when `NEW.lesson_id IS NOT NULL` → `'BR-37: …'` | 72 | OK |
| trg_course_questions_lesson_course BEFORE UPDATE (§5.18, §8 #27 BR-37) | `trg_course_questions_lesson_course_bu`, same body | 83 | OK |
| 13 updated_at triggers (DDD header "17 trigger" = 13 updated_at + 4 business; §5 "Mọi bảng có trigger cập nhật updated_at") | `ON UPDATE CURRENT_TIMESTAMP(6)` on the 13 DDD `updated_at` columns (V1: 2, V2: 5, V3: 3, V4: 3); no trigger objects | V1–V4 | OK |

Notes
- Every MESSAGE_TEXT starts with the BR code + `:` (error 1644, SQLState 45000); tested with `assertSignals` in
  `com.edumy.schema.TriggerSchemaIT` (violation, valid case, and for `_bu` an UPDATE introducing / not introducing the
  violation).
- Missing parent row: the lookup subquery is NULL, so the comparison is NULL and the trigger does not signal; the FK
  reports the error instead (tested: unknown course → `fk_course_co_instructors_course_id`, unknown lesson / enrollment →
  `fk_lesson_progresses_*`, unknown lesson → `fk_course_questions_lesson_id`).
- MySQL does not fire triggers for rows changed by FK cascades (docs/ai/03 §3.5). Cascades only delete children
  (course_co_instructors, chapters, lessons, lesson_progresses) or set `course_questions.lesson_id` to NULL, which can
  never break BR-02/24/37 (tested: deleting a draft course, a chapter or a lesson cascades without any SIGNAL).
- BR-24 / BR-37 are checked on the child row only: `UPDATE lessons SET chapter_id = <chapter of another course>` or
  changing `enrollments.course_id` is not re-checked — same as the DDD's PL/pgSQL triggers, which are only on these
  tables. Application guard: enrollments are immutable; lessons only move within their course.
- Error 1419 ("You do not have the SUPER privilege and binary logging is enabled"): MySQL 8.4 enables binary logging, and
  the non-root application / Testcontainers user cannot create triggers without `--log-bin-trust-function-creators=1`.
  The option is set in `compose.yaml` and `support/SharedMySql`; production MySQL needs the same server option (or a
  a migration user with the deprecated SUPER privilege; `SET_ANY_DEFINER` alone does NOT avoid 1419 — verified in the
  P10 review). Migration semantics unchanged. Triggers get `DEFINER` = the Flyway user: dropping/renaming that account
  later breaks writes with error 1449.
- BR-02 is only checked on `course_co_instructors`: `UPDATE courses SET main_instructor_id = <a co-instructor>` is not
  re-checked (same as the DDD trigger; `main_instructor_id` never changes in v1, DDD §5.6).
- `ON UPDATE CURRENT_TIMESTAMP(6)` does not fire for FK-cascaded changes (e.g. `course_questions.updated_at` stays put
  when `lesson_id` is SET NULL by a lesson delete) nor for an UPDATE that changes no value; the DDD triggers would have
  fired for the cascade. Accepted.
- Flyway's MySQL parser handles the `BEGIN … END` bodies without `DELIMITER`.

## V6__reference_data.sql (roles)

| DDD constraint/index (source §) | MySQL implementation | line | status |
|---|---|---|---|
| roles loaded as initial data (§5.2 "nạp bằng dữ liệu khởi tạo") | `INSERT INTO roles (code, name)` in db/migration (every environment); ids from AUTO_INCREMENT (explicit ids are a db/seed rule, docs/ai/03 §3.7) | 9 | OK |
| roles.code values student · instructor · admin (§7) | `('student','Học viên')`, `('instructor','Giảng viên')`, `('admin','Quản trị viên')` — names = §7 meanings; codes allowed by `ck_roles_code` (V1, DERIVED) | 10–12 | OK |

Notes
- File is UTF-8; `FlywayMigrationIT` asserts the three Vietnamese names and that `'Học viên'` is stored as 8 characters /
  11 bytes (no double encoding).
- Code looks roles up by `code` (`uq_roles_code`), never by id. `IdentitySchemaIT.roleId(code)` finds the V6 rows; its
  duplicate-code test still fails on `uq_roles_code` as before.

## Totals across V1–V6

- Tables: 21 = 20 DDD tables + `refresh_tokens` (ADR-002).
- FKs: DDD §6 lists 34 FKs on its 20 tables: 27 RESTRICT (§8 #30), 6 CASCADE (#4, 8, 11, 12, 14, 25), 1 SET NULL (#29)
  — all present with these delete rules. `refresh_tokens` adds `fk_refresh_tokens_user_id` CASCADE (ADR-002, not in the
  DDD), so the schema holds 35 FKs (27 RESTRICT, 7 CASCADE, 1 SET NULL). Asserted by `FlywayMigrationIT`.
- Triggers: DDD "17 trigger" = 13 `updated_at` triggers (→ `ON UPDATE CURRENT_TIMESTAMP(6)`, no trigger object) + 4
  business triggers (→ 7 MySQL triggers, one per event). `information_schema.TRIGGERS` holds exactly 7.
- Generated columns: 2 STORED (`orders.pending_student_id` / `uq_orders_one_pending`,
  `payment_transactions.pending_order_id` / `uq_payment_one_pending`).
- Reference data: 3 roles. Flyway history: versions 1–6, all successful.
