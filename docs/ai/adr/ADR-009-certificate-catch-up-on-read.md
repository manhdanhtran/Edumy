# ADR-009 — Missing certificates are issued by two GET endpoints (idempotent write behind a read)
Status: Accepted · Date: 2026-11-15 · Decided by the product owner in the P10 brief (Q-c)

## Context
BR-39 issues a certificate once per enrollment when the student has completed every lesson **existing now**. UC-S10
issues it on the completion that reaches 100 %. A student can also reach 100 % without completing anything: an
instructor deletes the last lessons the student had not done (UC-I11, D-33). SRS UC-S20 step 1 ("Hệ thống kiểm tra
các ghi danh đạt 100% nhưng chưa có chứng chỉ (do bài học bị xóa) và cấp bổ sung"), UC-S11 ("cấp chứng chỉ bổ sung
nếu đạt 100% mà chưa có") and D-48 ("cấp bổ sung khi học viên mở tiến độ hoặc chứng chỉ") ask the system to catch up
when the student **reads** the progress or the certificate list. Until P10 this was open (P06-G6).

## Decision
- `GET /api/v1/me/certificates` (UC-S20) and `GET /api/v1/learn/courses/{courseId}` (UC-S11) first call
  `learning.service.CertificateCatchUpService.issueMissing(callerId, courseId|null)`, then run their unchanged
  read-only query.
- The write is one method, `CertificateService.issueMissing` — `@Transactional(isolation = READ_COMMITTED)`, not
  read-only. It scans **only the calling student's** enrollments (one course for UC-S11) that have completed lessons
  and no certificate (one statement — the usual result is empty), compares the completed count with the lessons that
  exist now (`authoring::api.LessonQueryApi.countLessons`), and for each candidate at 100 % locks the enrollment row
  (`SELECT … FOR UPDATE`, the same lock UC-S10 takes), re-counts, and issues through the existing
  `issueIfAbsent` (snapshot names BR-40, event `CertificateIssued`).
- Idempotent and race-safe: two concurrent reads queue on the enrollment lock; the second re-checks under READ
  COMMITTED, sees the certificate and writes nothing. `uq_certificates_enrollment` is the safety net: the
  non-transactional `CertificateCatchUpService` catches its `DataIntegrityViolationException` (the other request won)
  so a GET never answers 409 / 500 because of it.
- Instructors and admins reading the outline (D-32) have no enrollment: the scan is empty, nothing is written.

## Consequences
- A GET can write one `certificates` row (plus the `CertificateIssued` event and its log line). It is safe to repeat:
  the second call changes nothing (HTTP-idempotent in effect, though not "safe" in the RFC 9110 sense).
- Cost on the read path: +1 statement, +1 more when the student has a partially completed enrollment without a
  certificate (the lesson count); documented in docs/ai/05 and asserted by the statement-count tests.
- The catch-up does not run on `GET /me/enrollments` (UC-S08): the SRS names the progress view and the certificate
  list only. A student who sees 100 % there gets the certificate on the next of those two reads.
- Tests: `learning/controller/CertificateCatchUpIT` (issue once via each read, idempotent, caller's enrollments only,
  two concurrent reads → one certificate and two 200s).
