# P02 — Dev seed data

## Người làm trước
Sinh hash BCrypt cost 12 cho mật khẩu chung `Edumy@123` bằng tool thật (AI không được tự gõ hash):
`docker run --rm httpd:2.4 htpasswd -nbBC 12 "" 'Edumy@123' | tr -d ':\n' | sed 's/^\$2y/\$2a/'` → dán vào prompt.

## Prompt
```text
Task: write src/main/resources/db/seed/V1000__dev_seed.sql (dev profile only).
Use this BCrypt hash for every account: {{BCRYPT_HASH}} (password Edumy@123). Never generate another hash.

Data plan (explicit IDs, UTC times relative to 2026-11-01, Vietnamese realistic names/titles):
- users 1..20: id 1 admin (D-01), 2–5 instructors (one also student-only courses buyer), 6–19 students,
  20 locked student; mix verified/unverified emails; user_roles incl. one revoked instructor (is_active=false, D-04).
- categories 8 (one inactive). courses 14 covering every status: draft, pending_review, rejected (review_note),
  published (≥8, free and paid, prices valid per BR-07), hidden (review_note, published_at set).
  title_normalized must equal VietnameseNormalizer output (no diacritics, đ→d, lowercase).
- course_co_instructors with every invitation_status.
- chapters/lessons: published courses 2–4 chapters × 2–5 lessons, video + article, some is_preview,
  content matches type (ck_lessons_content_match).
- carts/cart_items for 5 students (one with a now-hidden course).
- orders in every status/cancel_reason, order_items with unit_price snapshot ≠ current price for one order,
  total_amount = Σ unit_price. At most one pending order per student.
- payment_transactions with every status incl. needs_review unhandled and needs_review handled (refund_outside);
  at most one pending per order; transaction_ref unique.
- enrollments free + purchase (purchase → paid order of same student containing the course).
- lesson_progresses: one student 100% complete in a course (→ certificate), several partial.
- course_reviews visible/withdrawn/hidden; course_questions/question_answers incl. instructor answers,
  student follow-ups, resolved, deleted, hidden (moderated_by set).
- certificates 2 with codes matching ^EDU-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$ and name snapshots.
Every row must satisfy all CHECKs, UNIQUEs and triggers. After the SQL, print a coverage table:
state machine value → seed row ids. Then run the app with profile dev and confirm Flyway applied it.
```
