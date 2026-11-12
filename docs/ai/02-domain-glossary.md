# 02 — Domain glossary, state machines, rule index

## 2.1 Glossary (VI → code)
| Tiếng Việt | Code term | Note |
|---|---|---|
| Tài khoản / Người dùng | `User` | one account per email; many roles (BR-01) |
| Học viên / Giảng viên / Quản trị viên | `STUDENT` / `INSTRUCTOR` / `ADMIN` | `roles.code`: student, instructor, admin |
| GV chính | main instructor (`courses.main_instructor_id`) | exactly one, never removed (BR-02) |
| Đồng GV | co-instructor (`course_co_instructors`, status accepted) | edits chapters/lessons only (BR-04) |
| Giảng viên của khóa | course instructor | main OR accepted co, AND active instructor role |
| Ghi danh | `Enrollment` | lifetime access, source free/purchase (BR-17, D-31) |
| Giỏ hàng / Mục giỏ | `Cart` / `CartItem` | 1 cart per user (BR-22) |
| Đơn hàng / Dòng đơn | `Order` / `OrderItem` | price snapshot (BR-12, BR-27) |
| Giao dịch thanh toán | `PaymentTransaction` | one QR attempt, 15 min (BR-13) |
| Đối soát | reconciliation (`needs_review` handling) | UC-A18, D-29 |
| Tiến độ bài học | `LessonProgress` | percent is derived, never stored (D-33) |
| Đánh giá | `CourseReview` | one per enrollment (BR-18) |
| Câu hỏi / Phản hồi | `CourseQuestion` / `QuestionAnswer` | only course instructors + asker reply (BR-38) |
| Chứng chỉ | `Certificate` | code `EDU-XXXX-XXXX-XXXX`, snapshot names (BR-39, BR-40) |
| Snapshot | snapshot | copied at event time, never updated |
| Bộ hẹn giờ | scheduler | every 5 min (NFR-09) |

## 2.2 State machines (SRS §3.4 — any transition not listed is rejected)
**Course** `draft → pending_review` (main GV, BR-07 checklist) · `rejected → pending_review` · `pending_review → published`
(admin, set `reviewed_by`, `published_at` if null) · `pending_review → rejected` (reason required) ·
`published → hidden` (reason required; enrolled students keep access BR-31) · `hidden → published` ·
delete only if `published_at IS NULL` and status in (draft, rejected) (BR-19).
Editing: forbidden while `pending_review` (BR-33); allowed when published without re-review (D-14), but a
published course must keep ≥1 lesson.

**Order** `pending → paid` (system, with enrollments, same tx) · `pending → cancelled` (reason user|replaced|expired,
only after provider lookup says unpaid) · `cancelled → paid` (late valid result or admin activation, BR-30).

**Payment transaction** `pending → success|failed|expired` · `pending|expired|failed → success` (late valid) ·
`pending|expired|failed → needs_review` (amount/order mismatch, ownership conflict) · `needs_review → success`
(admin activated) · `needs_review` + handled (admin refund_outside).

**Co-instructor invitation** `new → pending` · `pending → accepted|declined` (invitee) · `pending|accepted → removed`
(main GV removes / co leaves) · `declined|removed → pending` (re-invite reuses row, D-18).

**Review** `new → visible` · `visible → withdrawn` (student) · `withdrawn → visible` (student re-rates) ·
`visible|withdrawn → hidden` (admin) · `hidden → visible` (admin only, BR-35).

**Question / Answer** `new → visible` · `visible → deleted` (author, final) · `visible → hidden` (admin) ·
`hidden → visible` (admin). Deleted question hides the whole thread.

**User** `active ⇄ locked` (admin; never self, never another admin; ≥1 active admin, BR-36). Lock ends all sessions.

## 2.3 Business rules quick index (BR → where enforced)
| BR | Short | Enforced by |
|---|---|---|
| 01 | multi-role per account | `user_roles` + UQ |
| 02 | 1 main GV, co ≠ main | FK NOT NULL + trigger `trg_cci_not_main_instructor_*` |
| 04/06 | co edits only chapters/lessons after accept; main owns info/price/invites/submit | `CourseAccessApi` + `@PreAuthorize` |
| 07 | submit checklist (title, description, active category, price 0 or ≥10.000 & %1000, ≥1 chapter, no empty chapter, every lesson has content) | `SubmitForReviewService` + CHECK price |
| 08 | reject/hide need reason | CHECK `ck_courses_note_required` + DTO validation |
| 09 | free course: direct enroll | learning |
| 10/32 | no owned course / own course in cart | commerce via `EnrollmentApi.owns`, `CourseAccessApi.isCourseInstructor` |
| 13/16/23 | QR 15 min, ≤1 pending tx per order, many tx per order | generated-column UQ + service |
| 14/30 | only valid result grants access; late valid accepted if no ownership conflict | T2 |
| 17 | 1 enrollment per student-course, lifetime | UQ + `INSERT IGNORE` semantics |
| 18/25 | 1 review per enrollment | UQ `enrollment_id` |
| 19 | no delete once published | trigger + RESTRICT |
| 20 | purchase email failure never cancels order | async event |
| 24/37 | progress/question lesson belongs to enrollment's course | triggers |
| 26 | actor permissions checked at action time | `RoleQueryApi` (not JWT claims alone) |
| 27 | order ≥1 line, total = Σ unit_price | T1 + CHECK >0 |
| 28 | no history deletion; status instead | RESTRICT FKs, soft states |
| 29 | ≤1 pending order per student, auto-cancel 24 h | generated-column UQ + scheduler |
| 31 | hidden course: not sold/listed, enrolled keep access | catalog filters `published`; learning ignores course status |
| 33 | pending_review course read-only | authoring service guard |
| 34 | tokens hashed SHA-256, single use, new token voids old, 24 h / 30 min, resend ≥60 s | identity T7 |
| 35/41 | hidden content: only admin restores, author cannot edit | service guard + CHECK |
| 36 | ≥1 active admin, admin can't lock self/other admin | identity service with row lock |
| 38 | only course instructors + asker reply | learning service |
| 39/40 | auto certificate once at 100% (free or paid), permanent snapshot | learning on progress change |

## 2.4 Key decisions to remember (D-xx)
D-01 admin seeded only · D-02 unverified email may log in & free-enroll, must verify before ordering / becoming GV ·
D-03 resend cooldown 60 s, forgot-password response identical whether email exists · D-04 self-register as GV,
blocked if previously revoked · D-05 lock ends all sessions · D-09 accent-insensitive search, 4 sorts ·
D-12 GV never buys own course · D-23 cart requires login, no "Buy now" · D-26 re-validate cart at order time ·
D-32 GV/Admin view content without enrollment, no progress · D-33 manual complete/uncomplete, percent = floor(done/total*100)
· D-34 `last_accessed_at` for "Học tiếp" · D-44 title ≤200, content ≤5000, plain text · D-47 certificate verify rate-limited ·
D-48 PDF generated on demand, not stored.
