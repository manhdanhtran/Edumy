# ADR-008 — Decisions beyond the SRS taken in P10 (approved by the product owner)
Status: Accepted · Date: 2026-11-15

Context: the P04 → P10 build left questions the SRS (EDU-SRS-001 v3.0) does not answer. The product owner answered them
in the P10 brief (Q-a … Q-h and the 403/404 principle). This ADR records the answers that go **beyond** the SRS text,
so that a later reader does not take them for SRS rules or "fix" them back.

## 1. Locked and unverified accounts (Q-g)
| Case | Decision | Code | Where |
|---|---|---|---|
| Admin grants the instructor role (UC-A04) to a **locked** account | refused, nothing written; unlock first (UC-A03) | 409 `ACCOUNT_LOCKED_TARGET` (new) | `identity.service.UserAdminService.grantInstructor`, checked under the target row lock |
| Admin grants the instructor role to an account whose email is **not verified** | **allowed** (admin override): D-02 ("xác thực trước khi … đăng ký làm GV") binds self-registration UC-G13 only | — | same |
| Main instructor invites (UC-I13) a co-instructor whose account is **locked** | refused like an ineligible invitee (one answer, no account probing) | 422 `COURSE_CO_INSTRUCTOR_NOT_ELIGIBLE` | `authoring.service.CoInstructorService.invite` (P10-fix-b) |

Why: a locked account must not gain rights while it is locked (UC-A02 ends every session; granting a role or a
collaboration to it would be dormant rights nobody reviewed). The unverified-email rule exists against fake
self-registrations, which an admin decision is not.

## 2. 403 vs 404 (principle, applies everywhere — docs/ai/04 §4.3)
A published course is public, so a caller lacking the needed relation gets 403 with a business code
(`ENROLLMENT_REQUIRED`) and the SPA shows the course page (UC-S09). Anything not public (draft / pending / rejected /
hidden course, other users' orders, questions, answers, reviews, enrollments, invitations) answers a caller without a
relation the 404 of an unknown id (NFR-01, UC-G04 E1). A missing role stays 403 `ACCESS_DENIED`. First application:
`POST /courses/{id}/reviews` without enrollment on a non-published course → 404 (was 403, P10 security MINOR-2).

## 3. Free text in logs, LIKE keywords (P10 security MINOR-3 / MINOR-4)
Free text typed by users is logged only through `shared.text.LogSanitizer` (control characters → `_`, ≤200
characters); the moderation reason (D-36 / D-41 ask for who and when only, stored as `moderated_by` / `moderated_at`)
stays log-only. LIKE keywords escape `%`, `_` and the escape character `!` (`ESCAPE '!'`, independent of
`NO_BACKSLASH_ESCAPES`; `shared.text.LikePattern`, catalog and Q&A helpers).

Consequences: two new error codes in docs/ai/05 §5.2 (`ACCOUNT_LOCKED_TARGET`); the other answers above reuse existing
codes. Further P10 decisions (resend of invitation mail, revocation impact preview, lesson content on public courses,
certificate catch-up on read) are recorded where they are implemented (docs/ai/05, ADR-009).
