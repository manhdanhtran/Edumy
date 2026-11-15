# P10 review triage — every finding of the four reviewers, verified by the implementer

Each finding was re-checked by reading the code at the cited lines (and, where a fix was made, by a test that fails
without it). Verdicts: **fix** (unit that fixes it) · **false positive** (reason) · **backlog** (reason; not fixed in
P10). Severity as reported; a re-graded severity is noted.

## Security (`security.md`) — 0 BLOCKER · 0 MAJOR · 5 MINOR
All five confirmed and fixed in P10-fix-a (`7a8ee8e`); proofs in `security.md`.

## Structure & contract (`structure-contract.md`) — 0 BLOCKER · 4 MAJOR · 7 MINOR
| ID | Verdict | Notes |
|---|---|---|
| SC-M1 learning authorizes through authoring's internal `@courseAccess` | **fixed** P10-fix-c `3bc5fdc` | confirmed (`InstructorQuestionController`, `InstructorStudentController` use the SpEL bean of `authoring.security`); learning-side `@enrollmentAccess.isActiveInstructor()` / `isCourseInstructor(id)` on `RoleQueryApi` + `CourseAccessApi` |
| SC-M2 type-mismatch errors in English with Java class names | **fixed** P10-fix-e `b1ddcdf` | confirmed (`GlobalExceptionHandler` uses `getDefaultMessage()` for binding failures) |
| SC-M3 CORS exposes only `Retry-After` | **fixed** P10-fix-e `b1ddcdf` | confirmed (`WebConfig`); expose `Location`, `Content-Disposition`, `X-Trace-Id`, `X-Edumy-Stub` |
| SC-M4 services take `dto/request` records | **fixed** partly: `QaQueryService` in P10-fix-e `b1ddcdf`; the five commerce services in P11 (their bodies are rewritten there) | confirmed |
| SC-m1 stale §1.5 trees | backlog → P14 (docs refresh with the RTM) | |
| SC-m2 no OpenAPI bearer scheme | backlog → P14 (OpenAPI item) | |
| SC-m3 one `@Operation` summary starts with `D-34` | **fixed** P10-fix-e `b1ddcdf` | |
| SC-m4 two `sort` parameters in OpenAPI | backlog → P14 (OpenAPI item) | runtime unaffected |
| SC-m5 two broken Markdown tables in 05 §5.4 | **fixed** P10-fix-e `b1ddcdf` (docs) | |
| SC-m6 `preview` vs `isPreview` | backlog | renaming a response field breaks the existing contract (hard-stop rule); listed for the SPA team |
| SC-m7 foreign `Origin` 403 missing in §5.4 | **fixed** P10-fix-e `b1ddcdf` (docs) | |

## Business correctness (`business-correctness.md`) — 0 BLOCKER · 3 MAJOR · 6 MINOR
| ID | Verdict | Notes |
|---|---|---|
| BC-M1 AC-14: create on a visible review answers 409 instead of updating | **fixed** P10-fix-e `b1ddcdf` | confirmed; AC-14 "Bản ghi hiện có được cập nhật" beats the P06-G7 decision. Turning a 409 into a 200 is a relaxation (no client request that worked before fails), so not a contract break |
| BC-M2 removed / left / revoked instructor still edits or deletes old instructor answers | **fixed** P10-fix-e `b1ddcdf` | confirmed (`AnswerService.own` checks authorship only); UC-I21 E1, UC-I16 post-condition, UC-A05 step 4, D-43 "phản hồi cũ giữ nguyên" |
| BC-M3 enrolled student later made co-instructor keeps student rights on that course | **fixed** P10-fix-e `b1ddcdf` | confirmed (`ReviewService`, `ProgressService`, `QuestionService` check only the enrollment). Refusing the invitation (option a) is impossible without authoring → learning (cycle), so learning refuses review / completion / question for a course instructor (option b) with 422 `ENROLLMENT_OWN_COURSE` (BR-32, RBAC §3.3) |
| BC-m1 UC-I02 list has no student count | backlog | needs enrollment counts in authoring's list = authoring → learning dependency (cycle, docs/ai/01 §1.8); a learning-side endpoint would be a new contract not on the approved list |
| BC-m2 UC-S17 E3 double-submit only in the front end | backlog | documented P06-G8 decision; server-side de-dup needs a new rule (time window) the SRS does not define |
| BC-m3 answer editable while its question is hidden | **fixed** P10-fix-e `b1ddcdf` | confirmed; BR-41 → 409 `QA_CONTENT_HIDDEN_BY_ADMIN` |
| BC-m4 hidden course stays up to 60 s in the cached list | **fixed** P10-fix-d `6fd049a` (with Q-a) | confirmed; evict `catalog.course-list` on `CourseApproved` / `CourseHidden` / `CourseUnhidden` |
| BC-m5 free enroll / cart add race with a concurrent hide | backlog → ADR in P13 | window of milliseconds; closing it needs a cross-module row lock (learning/commerce locking an authoring row) |
| BC-m6 login lock-out "consecutive with gaps < 15 min" vs "5 in 15 min" | backlog → ADR in P13 | interpretation already documented in docs/ai/04 §4.4; recorded as a decision |

## Data, tests, phase (`data-tests-phase.md`) — 1 BLOCKER · 1 MAJOR · 6 MINOR
| ID | Verdict | Notes |
|---|---|---|
| DT-B1 lost updates: plain read fixes the REPEATABLE READ snapshot before the course lock, later reads are stale | **fixed** P10-fix-e `b1ddcdf` | confirmed on `CoInstructorService.accept` (scalar course-id read → `FOR UPDATE` → `findById` reads the old snapshot → accept overwrites a concurrent remove) and `LessonService.update`. Fix: READ COMMITTED as the connection default (ADR-010) + concurrency ITs |
| DT-M1 same cause in chapter / lesson create / delete / reorder | **fixed** P10-fix-e `b1ddcdf` | same fix |
| DT-m1 same cause in identity (cooldown, concurrent role change) | **fixed** P10-fix-e `b1ddcdf` | same fix |
| DT-m2 catalog list aggregates every visible review on a cache miss | backlog | performance only; NFR-05 load testing is out of scope (P14 list) |
| DT-m3 unfiltered admin order / payment lists sort the whole table | backlog → P13 check of indexes (no schema change without approval) | |
| DT-m4 weak assertions (whole-table counts, early negative mail checks, `future.get()` without timeout) | **fixed** partly P10-fix-e `b1ddcdf` (timeouts on concurrency helpers); rest backlog | |
| DT-m5 ~10 cached contexts × default pool size ≈ 100 idle connections | **fix** P13 (suite hardening: smaller test pool) | |
| DT-m6 committed dev JWT secret / seed accounts | false positive for production (dev profile only, `application.yml` has no default); runbook warning in P14 | |

## Totals
| Reviewer | BLOCKER | MAJOR | MINOR | fixed | false positive | backlog |
|---|---:|---:|---:|---:|---:|---:|
| security | 0 | 0 | 5 | 5 | 0 | 0 |
| structure & contract | 0 | 4 | 7 | 4 MAJOR (SC-M4 commerce part → P11) + 3 MINOR | 0 | 4 MINOR (3 → P14, SC-m6 contract) |
| business correctness | 0 | 3 | 6 | 3 MAJOR + 2 MINOR | 0 | 4 MINOR |
| data, tests, phase | 1 | 1 | 6 | 1 BLOCKER + 1 MAJOR + 2 MINOR (DT-m1, DT-m4 partly) | 1 (DT-m6) | 3 MINOR (DT-m5 → P13) |

Open after P10: 0 BLOCKER, 0 MAJOR (SC-M4's commerce services are rewritten in P11 and must take commands, not HTTP records).
