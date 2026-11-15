# P10 review: business correctness (snapshot 0374f3d)

Scope / method: read-only review of the worktree at 0374f3d against EDU-SRS-001 v3.0 (§3.3 RBAC, §3.4 state machines, UC main/A/E flows, BR-01…41, D-xx, AC-xx) and docs/ai/progress.md "P06 specs". I traced each path from the controller `@PreAuthorize`, through the access bean and the service, down to the entity and repository.
Covered: `identity` (auth, sessions, tokens, profile, G13, admin A02–A05), `authoring` (course, chapter, lesson, co-instructor, submit, moderation, category, access beans), `learning` (enrollment, progress + certificate, reviews, Q&A, moderation, access beans, Q&A read side), `commerce` cart (S01–S03 + listener), `catalog` (list/detail/reviews/preview/certificate verify + cache eviction), `notification` listeners.
Not covered, or only skimmed: the commerce order/payment read side (`OrderQueryService`, `PaymentQueryService`, mappers: only ordering checked); the authoring read DTOs other than the UC-I02 list; mail templates. The STUB-P1 endpoints, the schedulers and the items on the "already known" list were excluded as instructed.

## BLOCKER

None found.

## MAJOR

### BC-M1: Creating a review when a visible one exists answers 409 instead of updating it
- **SRS:** AC-14 (Given: "Học viên đã có đánh giá đang hiển thị". When: "Gửi yêu cầu tạo đánh giá mới". Then: "**Bản ghi hiện có được cập nhật**; không có bản ghi thứ hai"). Also UC-S12 step 3 "Có bản ghi đang hiển thị: chuyển sang UC-S13" and BR-18 / BR-25.
- **Where:** `src/main/java/com/edumy/learning/service/ReviewService.java:83-85`
  ```java
  if (review.isVisible()) {
      throw new BusinessException(ErrorCode.DATA_CONFLICT); // BR-18: one review per enrollment, use UC-S13
  }
  ```
- **Scenario:** A student has a visible 3-star review. They call `POST /courses/{id}/reviews {rating:5}`. They get 409 `DATA_CONFLICT`, and the review stays at 3 stars. AC-14 expects 200 with the same row updated to 5 (no second row). The P06-G7 decision ("visible → 409") contradicts the acceptance criterion, and AC-14 is the test the examiner will run.
- **Fix:** In `create`, when the review is visible, call `review.edit(rating, comment)` (UC-S13 semantics) and return `Result(response, false)`, which is 200. Keep `hidden` → `REVIEW_HIDDEN_BY_ADMIN`. Adjust `ReviewWriteIT`.
- **Confidence:** high. AC-14 is explicit. Only "chuyển sang UC-S13" could be read as "redirect the user", and AC-14 rules that reading out.

### BC-M2: A removed, left or revoked instructor can still edit or delete their old instructor answers
- **SRS:**
  - UC-I21 E1: "Không còn là giảng viên của khóa (bị gỡ, rời khóa hoặc bị thu hồi vai trò): từ chối". E1 covers the whole UC, including A1 "Giảng viên sửa hoặc xóa phản hồi của chính mình".
  - UC-I16 postcondition: "mọi thao tác kế tiếp của người bị gỡ bị từ chối; phản hồi hỏi đáp cũ giữ nguyên".
  - UC-A05 step 4: "mọi thao tác … trả lời hỏi đáp của tài khoản đều bị từ chối".
  - D-43.
- **Where:**
  - `src/main/java/com/edumy/learning/controller/AnswerController.java:57,66`: the guard is `@qaAccess.isAnswerAuthor(#id)` only.
  - `src/main/java/com/edumy/learning/security/QaAccess.java:88-94`: authorship only.
  - `src/main/java/com/edumy/learning/service/AnswerService.java:117-124`: `own()` checks the author and the status, never the course relation or the role.
  ```java
  if (!answer.isAuthoredBy(userId) || answer.getVisibilityStatus() == ContentStatus.DELETED
          || answer.getQuestion().getVisibilityStatus() == ContentStatus.DELETED) {
  ```
- **Scenario:**
  1. Co-instructor C answers question Q, so the answer is stored with `author_type = instructor`.
  2. The main instructor removes C (UC-I16), or an admin revokes C's instructor role (UC-A05).
  3. C can still read the thread only if C is enrolled. Even without that, C can send `PATCH /answers/{id}` with a new text, or `DELETE /answers/{id}`, because both only need authorship.
  4. The content shown under the "Giảng viên" label changes after C lost the right, or the old answer disappears, which contradicts "phản hồi hỏi đáp cũ giữ nguyên".
- **Fix:** For answers with `author_type = instructor`, the edit/delete guard (or `AnswerService.own`) must also require `courseAccess.isCourseInstructor(userId, courseId)` at action time. Return 403 `QA_REPLY_NOT_ALLOWED`, or 404 to stay consistent with NFR-01. Student-type answers keep the authorship check.
- **Confidence:** medium-high. It depends on reading E1 as covering A1; UC-I16 and UC-A05 step 4 support that reading.

### BC-M3: An enrolled student who later becomes a co-instructor keeps the student-only rights on that course
These rights are rating the course, progress, a certificate and asking questions.
- **SRS:**
  - RBAC §3.3 row "Tiến độ, đặt câu hỏi, đánh giá, chứng chỉ | — | Đã ghi danh | **—** (GV chính) | **—** (Đồng GV) | —".
  - BR-32 "Giảng viên của khóa không mua và không ghi danh khóa đó".
  - D-12.
- **Where:**
  - `src/main/java/com/edumy/authoring/service/CoInstructorService.java:64-87`: the invite never checks whether the invitee is enrolled.
  - `src/main/java/com/edumy/learning/service/ReviewService.java:76-77`: only the enrollment is checked.
  - `src/main/java/com/edumy/learning/security/EnrollmentAccess.java:38-43,59-61`: `isEnrolled` and `isEnrolledForLesson` check only the enrollment, so completion, certificate and asking a question stay open.
  - The team saw the case for answers (`AnswerService.java:79-82`: "instructor wins over student"), but not for reviews or progress.
- **Scenario:**
  1. H buys course A, which creates an enrollment.
  2. H registers as an instructor (UC-G13).
  3. A's main instructor invites H, and H accepts. H is now an accepted co-instructor and still enrolled.
  4. H sends `POST /courses/A/reviews {rating:5}` and gets 201. The 5-star rating of a course H teaches counts in the public average (UC-G04 step 3).
  5. H can also complete lessons and receive a certificate for H's own course.
- **Fix:** Pick one of these:
  - (a) Refuse the invite (UC-I13 step 2) when the invitee is enrolled in the course, with a new 422.
  - (b) In `ReviewService.create`/`update`, `ProgressService.setCompletion` and `QuestionService.ask` (or in `EnrollmentAccess.isEnrolled*`), refuse when `courseAccess.isCourseInstructor(caller, course)` is true, mirroring the rule `AnswerService` already applies.

  Option (b) also covers a main instructor who somehow holds an enrollment.
- **Confidence:** medium. The SRS does not name the "enrolled first, invited later" sequence, but RBAC denies these rights to course instructors without exception.

## MINOR

### BC-m1: The UC-I02 instructor course list has no student count
- **SRS:** UC-I02 "Khóa là GV chính và khóa cộng tác đã nhận lời, kèm trạng thái, **số học viên**".
- **Where:**
  - `src/main/java/com/edumy/authoring/dto/response/InstructorCourseSummaryResponse.java:12-29`: no such field.
  - `src/main/java/com/edumy/authoring/repository/CourseRepository.java:87-109`: the query selects no enrollment count.
- **Scenario:** The instructor dashboard cannot show the number of students per course without one `/students` call per course.
- **Fix:** Add `studentCount`, using one batched count through a new `learning::api` method or a JdbcClient count by course ids (enrollments belong to learning, so not a JPQL join).
- **Confidence:** high.

### BC-m2: UC-S17 E3 has no server-side protection against double submits
- **SRS:** UC-S17 E3 "Bấm gửi nhiều lần: nút gửi bị vô hiệu trong lúc xử lý; **chỉ lưu một câu hỏi**".
- **Where:** `src/main/java/com/edumy/learning/service/QuestionService.java:56-68`. It does a plain `save` without any lock or de-duplication. The decision is recorded as "front-end only" in progress P06-G8.
- **Scenario:** A double click, or a retry after a timeout, sends two identical POSTs and stores two questions.
- **Fix:** Lock the enrollment row (`findByStudentIdAndCourseIdForUpdate`). Then refuse the request, or return the existing question, when the same enrollment asked a question with the same title and content within N seconds. An `Idempotency-Key` header is an alternative.
- **Confidence:** medium. "chỉ lưu một câu hỏi" can be read as a postcondition for the system, not only the UI.

### BC-m3: An answer's author can edit or delete it while the admin has hidden the question
- **SRS:** BR-41 "Nội dung hỏi đáp bị Admin ẩn … tác giả không sửa được"; UC-I21 E2 "Câu hỏi đã bị xóa hoặc bị ẩn: không cho phản hồi".
- **Where:** `src/main/java/com/edumy/learning/service/AnswerService.java:117-124`. `own()` rejects only a deleted question, not a hidden one. This is listed as "open" in progress P06-G8, without a decision.
- **Scenario:** The admin hides question Q for a violation. The asker's follow-up answer on Q can still be edited, and the edit becomes visible again once the admin restores Q.
- **Fix:** In `own()`, refuse with 409 `QA_CONTENT_HIDDEN_BY_ADMIN` when `answer.getQuestion()` is hidden.
- **Confidence:** medium. It depends on reading a hidden question as hiding its whole thread.

### BC-m4: A hidden course stays in the catalog list and search for up to 60 s
- **SRS:** UC-A12 step 3 "Khóa không còn xuất hiện trong danh sách và tìm kiếm"; AC-28 "Khóa biến khỏi tìm kiếm".
- **Where:**
  - `src/main/java/com/edumy/catalog/listener/CatalogCacheEvictionListener.java:233-236`: `CourseHidden` evicts only the detail cache.
  - `src/main/java/com/edumy/catalog/service/CachedCourseQueryService.java:165`: `catalog.course-list` uses the 60 s TTL only.
- **Scenario:** The admin hides course X. For up to 60 s the list and search still show X's card, and clicking it gives a 404 detail page. Selling is not affected: the cart reads authoring directly.
- **Context:** This is a documented decision (docs/ai/05 §5.3, "accepted staleness"), but it does not literally meet AC-28.
- **Fix:** On `CourseHidden` (and `CourseApproved` / `CourseUnhidden`), call `evictor` to clear `catalog.course-list` entirely. Those events are rare, so the cost is negligible.
- **Confidence:** high on the facts; whether it matters is the PO's call.

### BC-m5: Free enrollment and cart add are check-then-act against a concurrent hide
- **SRS:** UC-A12 step 3 "không thể thêm vào giỏ, mua hoặc ghi danh mới"; BR-31.
- **Where:**
  - `src/main/java/com/edumy/learning/service/EnrollmentService.java:42-55`
  - `src/main/java/com/edumy/commerce/service/CartService.java:50-70`

  Both read the course status with a plain read (`CourseQueryApi.findSummaries`), then insert. The course row is never locked or re-checked.
- **Scenario:** An admin hide commits between the status read and the `INSERT IGNORE` or cart insert. The result is a new free enrollment, or a new cart line, on a hidden course. The cart line is harmless because it is flagged at read time and checked again at order time. The enrollment is permanent (BR-17, D-31).
- **Fix:** For the enrollment, use `INSERT … SELECT … FROM courses WHERE id = ? AND status = 'published' AND price = 0`, or take a shared lock (`FOR SHARE`) on the course row through an authoring api method.
- **Confidence:** medium. The race window is milliseconds.

### BC-m6: The login lock-out rule is broader than "5 failures within 15 minutes"
- **SRS:** UC-G07 "sai 5 lần trong 15 phút → tạm chặn 15 phút"; NFR-03 "5 lần đăng nhập sai liên tiếp cho một email trong 15 phút".
- **Where:** `src/main/java/com/edumy/identity/security/LoginAttemptLimiter.java:55-58,99-110`. `expireAfterWrite(15 min)` restarts on every failure.
- **Scenario:** Failures at t = 0, 14, 28, 42 and 56 min block the email at the fifth one, although no 15-minute window holds 5 failures.
- **Fix:** Store the timestamps of the last 5 failures and block only when `now - oldest ≤ 15 min`. Otherwise, document the "consecutive with gaps under 15 min" interpretation in an ADR as a decision.
- **Confidence:** medium. This is an interpretation of the SRS, and the class Javadoc already describes it.

## Checked and found correct

- **identity**
  - Register: normalisation, student role with `assigned_by` null, 24 h code, login in the same transaction; mail is async after commit (E3).
  - Verify / resend: 60 s cooldown under the user row lock; a new code voids the old ones (BR-34).
  - Forgot password: uniform answer, locked account gets no code.
  - Reset: single-use code (atomic UPDATE), all sessions revoked, no auto-login.
  - Change password: other sessions revoked; mail sent.
  - Login: locked account → 403; roles taken from the DB.
  - G13: E1 → E2 (revoked row) → E3 → grant, all under the user row lock.
  - A02–A05: BR-26 DB re-check, BR-36 self/admin, row lock, 409 instead of a no-op, sessions revoked and caches evicted after commit.
  - Role-cache eviction after commit (no stale put; the loader and invalidate are atomic per key).
- **authoring**
  - Course state machine: every §3.4.1 transition and nothing else; status checked before the checklist.
  - Approve sets `reviewed_by` / `reviewed_at`, and `published_at` only when null.
  - Reject / hide need a reason.
  - Delete only when `published_at` is null and the status is draft / rejected (BR-19, AC-31).
  - BR-33 re-checked under the course row lock for info and content writes (AC-27).
  - Last-lesson delete on published / hidden courses serialised by the course lock (D-14).
  - Positions kept as 1..n.
  - Invitation state machine (§3.4.4), D-18 row reuse, accept/decline/remove/leave all lock the course first (accept vs remove race OK).
  - BR-06 main-only operations (AC-05); a co-instructor loses rights at once (AC-17).
  - Two admins deciding the same course: the second gets 409 (UC-A10 E1).
  - D-21 category delete or deactivate.
- **learning**
  - Free enroll: SRS check order; idempotent 200; `INSERT IGNORE` (BR-17, AC-09); BR-32 main instructor whatever the role.
  - Completion: enrollment `FOR UPDATE` + READ COMMITTED → exactly one certificate (AC-34); floor percent; snapshot names (BR-40, AC-35); unmarking keeps the certificate.
  - Review state machine (§3.4.5): hidden → `REVIEW_HIDDEN_BY_ADMIN` (AC-30); withdrawn re-rated on the same row.
  - Q&A state machine (§3.4.6): deleted is final; hidden blocks author edits (BR-41).
  - Only the asker and course instructors reply (BR-38, AC-33); `author_type` is a snapshot; the mail goes only for instructor answers, after commit.
  - Admin moderation re-checks the role in the DB and records `moderated_by` / `moderated_at`.
  - The thread read hides hidden and deleted content correctly.
- **commerce (cart)**
  - S01 check order and codes; cart creation race-safe (BR-22); duplicate → 409 (AC-08); co-instructor refused (AC-29).
  - Remove is scoped to the caller's cart.
  - GET /cart flags hidden / owned / free lines and excludes them from the total (UC-S02).
  - The listener writes nothing (the flag is derived).
- **catalog**
  - Only published courses listed; a hidden page is open to enrolled students, course instructors and admins (admin role from the DB), 404 for others (UC-G04 E1).
  - Average = ROUND(AVG, 1) over visible reviews only.
  - Preview only for published courses with a preview lesson.
  - Certificate verify reads the snapshot columns only (AC-36).
  - Detail and rating evicted after commit on every relevant event.
- **notification**
  - Every listener is `@Async` + AFTER_COMMIT and swallows failures, so a mail failure never rolls back the business change (BR-20 pattern, UC-G05 E3, UC-I13 E4, UC-A10 E3, UC-I21 E3).
  - The SRS §7 mails for the implemented UCs are all present.

## Verdict

The non-stub code follows the SRS state machines, the BR guards and the concurrency rules closely: row locks are taken where concurrent actors exist, and BR-26 is re-checked in the DB everywhere except the already-known category endpoints.
Three MAJOR deviations remain: AC-14 (409 instead of updating the review), UC-I21 E1 / UC-I16 (ex-instructors can still edit or delete their answers), and RBAC §3.3 (an enrolled student who becomes co-instructor can rate their own course and earn its certificate).
No BLOCKER found. Fix the three MAJORs before acceptance testing; the six MINORs can be scheduled or recorded as decisions.
