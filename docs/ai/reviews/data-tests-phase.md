# P10 review — data, tests, phase rules (snapshot 0374f3d)

Scope / method: read-only review of the git worktree at `0374f3d` (nothing was built or run). Read: AGENTS.md, docs/ai 03/06/07, ADR-001, V1–V6 + seed, `application*.yml`, `.env.example`, `compose.yaml`, every repository and `JdbcClient` class, every `service/` and `listener/` class of identity, authoring, commerce and learning, the entities (types, `updatable`, generated columns), the stub controllers and services, the `support/` test annotations, and the concurrency and stub ITs. I compared every query on a growing table with the DDL indexes.
NOT covered: no line-by-line diff of the DDD column lists against the DDL (only the constraint, index and trigger names were compared: all DDD names are present); `docs/ai/09-constraint-coverage.md`, `schema-checklist.md` and `progress.md` were not re-read; notification `MailService` / `MailComposer` internals; IDOR coverage only checked to exist (`IdorIT`, `10-idor-coverage.md`), not audited per endpoint; no surefire timing data.

## BLOCKER

### DT-B1 — Authoring writes read a REPEATABLE READ snapshot taken *before* the course lock → lost updates (revoked co-instructor becomes accepted; concurrent lesson PATCH overwritten)
- `src/main/java/com/edumy/authoring/service/CoInstructorService.java:149-157` (`accept` / `decline` through `lockedPendingInvitation`)
- `src/main/java/com/edumy/authoring/service/LessonService.java:64-80` (`update`)
- Same root cause in `ChapterService` and `LessonService` `create` / `rename` / `delete`, see DT-M1.

Evidence (CoInstructorService):
```java
Long courseId = coInstructors.findCourseIdByIdAndInstructorId(invitationId, instructorId)...; // 1st plain SELECT → read view
lockCourse(courseId);                                                                          // SELECT … FOR UPDATE (may wait)
CourseCoInstructor row = coInstructors.findById(invitationId)...;                              // plain SELECT → old read view
```
Why it matters: MySQL runs at its default REPEATABLE READ. Nothing sets the isolation (no `transaction-isolation`, no Hikari setting), and the entities have no `@Version` or `@DynamicUpdate`. InnoDB builds the read view at the transaction's **first plain (consistent) read**. A later plain SELECT keeps reading that view even after the transaction has waited for and obtained `FOR UPDATE` on the course. Each method listed here starts with a plain lookup of the course id, so its view dates from before the lock wait. The later `findById` therefore returns the row as it was *before* the transaction that held the lock committed. Hibernate then writes the whole stale row back.
`ProgressService.java:26-28` already describes this exact pitfall ("under REPEATABLE READ its snapshot, taken by the first read, would miss them") and uses READ COMMITTED for it. The authoring services claim the course lock makes their checks reliable (`CoInstructorService.java:30-31`: "so the pre-checks are reliable and nothing deadlocks"), but that only holds if the lock is taken before any plain read.

Failure scenarios:
1. **Lost revocation (UC-I16 / BR-04).** The main instructor revokes a pending invitation (`remove`: holds the course lock, sets `removed`, commits). At the same moment the invitee clicks accept: T1 has already run step 1 and waits on the lock, then `findById` returns the snapshot row (still `pending`), `accept()` succeeds, and `UPDATE … SET invitation_status='accepted'` overwrites `removed`. The revoked user is now an accepted co-instructor with edit rights. `decline` can overwrite `removed` the same way.
2. **Lost content edit (UC-I10).** Two course instructors PATCH the same lesson (T1 changes the title, T2 changes the `videoUrl`). T2 waits on the course lock, then reads the snapshot lesson (old title). Its PATCH merge (`command.title() != null ? … : lesson.getTitle()`) writes the old title back. T1's change is lost without any error. The full-row UPDATE also writes a stale `sort_order` (see DT-M1).

Fix: run these transactions at READ COMMITTED, like learning does: `@Transactional(isolation = Isolation.READ_COMMITTED)` on every `ChapterService`, `LessonService` and `CoInstructorService` write method. Under READ COMMITTED each statement after the lock sees the committed state. Alternatives: make the course lock the first statement, with the course id resolved before the transaction starts; or add `@Version` to `CourseCoInstructor`, `Lesson` and `Chapter`. Add a two-thread IT that holds the course lock in one transaction while the other calls `accept` (or `update`), and assert that the final row is `removed` (or keeps both edits).
Confidence: **high** for the mechanism (documented InnoDB behaviour, confirmed in the code: no isolation setting, no version column, plain read before the lock). The race window is the lock wait, so it needs near-simultaneous requests. No authoring concurrency test exists, so the suite cannot catch it.

## MAJOR

### DT-M1 — Same stale read view breaks the "under the course lock" invariants: D-14 last-lesson guard bypass, wrong `max + 1`, stale `sort_order` / gap close
- `src/main/java/com/edumy/authoring/service/LessonService.java:48-52` (`create`: `courseIdOfChapter` first, `findMaxSortOrderByChapterId` after the lock), `:88-97` (`delete`: `countByCourseId` and `lesson.getSortOrder()` read from the snapshot)
- `src/main/java/com/edumy/authoring/service/ChapterService.java:62-69` (`rename`), `:77-90` (`delete`: `countByChapterId` / `countByCourseId`, `chapter.getSortOrder()` for `closeGap`)

Evidence:
```java
Course course = lockEditableCourse(courseIdOfLesson(lessonId));      // read view created by courseIdOfLesson
if (course.isPublishedOrHidden() && lessons.countByCourseId(course.getId()) <= 1) { … } // counted in the old view
```
Failure scenarios:
- **D-14 bypass.** A published course has chapter A (lesson a1) and chapter B (lesson b1). DELETE /chapters/A and DELETE /lessons/b1 arrive together. The chapter delete passes (2 lessons) and commits. The lesson delete's count still sees a1 + b1 = 2 in its old view, so it deletes b1. Result: a published course with zero lessons, the state that `COURSE_LAST_LESSON_DELETE_FORBIDDEN` exists to prevent.
- **`max + 1` race.** A double-click on "add lesson" (or two instructors adding at once): the second create computes a stale max and hits `uq_lessons_chapter_order`. That gives a spurious 409 `DATA_CONFLICT` (unmapped unique key, WARN log), even though the javadoc promises `max + 1 under the course lock`.
- **Stale `sort_order` after a concurrent reorder.** A rename or lesson update writes back the old position, which collides with the permutation, so a spurious 409. A delete with a stale position runs `closeGap` from the wrong place and leaves positions that are not 1..n (e.g. 2,3).

(`reorder` itself is safe: `lockIdsByChapterId` / `lockIdsByCourseId` are locking reads, and the bulk UPDATEs read current rows. `ChapterService.create`, `CourseService`, `SubmitForReviewService` and `CategoryService` take the lock as their first statement, so they are safe too.)
Fix: same as DT-B1 (READ COMMITTED on the authoring write methods), plus a concurrency IT for the D-14 scenario.
Confidence: **high** (same mechanism as DT-B1).

## MINOR

### DT-m1 — Read-before-lock in identity: password-reset cooldown race, wrong role-change outcomes
- `src/main/java/com/edumy/identity/service/AuthService.java:146-153`: `findByEmail` loads `User` into the persistence context and starts the read view. The following `findByIdForUpdate` returns the **same managed, stale instance**, and `canResend` → `findLatestCreatedAt` reads the old view. Two concurrent "forgot password" requests can both pass the 60 s cooldown, so two codes and two mails (the D-03 cooldown is bypassed; the earlier code is invalidated, so there is no security impact). Fix: lock first (look up the id and lock in one statement, or use READ COMMITTED).
- `src/main/java/com/edumy/identity/service/UserAdminService.java:122-124`, `:150-152`: `requireActiveAdmin` (a plain read) runs before `lockUser`, so `findByUserIdAndRoleCode` reads the old view. Two admins granting at the same time: the second one inserts a duplicate and gets an unmapped `uq_user_roles_user_role`, so 409 `DATA_CONFLICT` instead of `INSTRUCTOR_ROLE_ALREADY_ACTIVE`. A grant racing a revoke gets the wrong error code. Fix: call `lockUser` first, or use READ COMMITTED.
- Confidence: high (mechanism); impact low.

### DT-m2 — Catalog list aggregates every visible review on each cache miss
`src/main/java/com/edumy/catalog/repository/CourseCatalogRepository.java:89-95` (`RATINGS`, used by `search` at `:151` and `newest` at `:166`)
```sql
LEFT JOIN (SELECT e.course_id, ROUND(AVG(r.rating), 1) … FROM course_reviews r JOIN enrollments e …
           WHERE r.visibility_status = 'visible' GROUP BY e.course_id) rt ON rt.course_id = c.id
```
The derived table is materialized over the whole of `course_reviews` (no index on `visibility_status`, and the join condition is not pushed into it), even for `sort=newest` and for a 20-row page. Keyword searches are effectively never cached (every distinct `q` is its own key), so this public hot path grows linearly with all reviews. It is fine at year-1 volume. Fix later: compute the rating only for the page's course ids (a second statement, or `catalog.rating` cache hits), or a LATERAL subquery for `sort=rating` only. Confidence: medium (depends on the optimizer plan; not EXPLAINed).

### DT-m3 — Unfiltered admin order / payment lists sort the whole table
`src/main/java/com/edumy/commerce/repository/OrderRepository.java:83` and `PaymentTransactionRepository.java:63`: `order by createdAt desc, id desc` with all filters optional. No index starts with `created_at` (`ix_orders_pending_created` and `ix_payment_review_queue` / `ix_payment_pending_expiry` lead with `status`), so the default admin view does a full scan plus filesort of `orders` / `payment_transactions` for every page. Admin-only, low traffic. Fix if it shows up: a `(created_at, id)` index (needs V7 + ADR) or a mandatory date range. Confidence: medium.

### DT-m4 — Test assertions on shared state, or that pass without proving anything
- `src/test/java/com/edumy/learning/controller/ReviewWriteIT.java:536-537`: `SELECT COUNT(*) FROM course_reviews` with no `WHERE`, asserted `isZero()` / `isOne()` at `:147`, `:218`, `:242`, on the shared `edumy` database. It is correct only while every other class (including `LearningModuleIT`) cleans up perfectly. Scope it to the test's enrollment(s), as `onlyReview()` already does.
- `src/test/java/com/edumy/notification/NotificationModuleIT.java:148`, `:161`: `assertThat(mails.sentTo(mainEmail)).isEmpty()` right after waiting for a *different* recipient's async mail. A wrong mail produced by the same `@Async` listener could arrive later, so these negative checks can pass for the wrong reason. `courseUnhidden_sendsNoMail_UCA16` relies on the order of two async executions, which is not guaranteed.
- Concurrency helpers (`ProgressWriteIT.java:458-478`, `EnrollmentWriteIT.java:264-281`, `ReviewWriteIT.java:591-608`, `CartWriteIT.java:446-463`): a start latch, and results from every thread are asserted (good, not vacuous). But (a) nothing proves the requests overlapped, and the correct serial outcome is also the expected one, so a removed lock usually still passes. Add a statement-level check (`for update` in `SqlStatementCounter`, as the authoring ITs do) for `ProgressService` / `ReviewService.create`. (b) `future.get()` has no timeout (`AdminUserWriteIT` uses 30 s), so a deadlock or lock wait hangs the fork until `innodb_lock_wait_timeout`.
- Confidence: high (code read); the impact is flakiness or weak protection, not wrong production code.

### DT-m5 — Up to ~100 idle MySQL connections held by the cached test contexts
`src/main/resources/application-test.yml` sets no Hikari size. Each of the ~10 cached contexts (see the cost notes below) keeps a HikariCP pool with Boot's defaults (`maximumPoolSize` 10, `minimumIdle` = max). That is about 100 connections against the shared container's default `max_connections` of 151. A few more distinct contexts (one extra `@MockitoBean` set) would fail with "Too many connections". Fix: `spring.datasource.hikari.maximum-pool-size: 4` and `minimum-idle: 1` in `application-test.yml`, or `--max-connections=500` in `SharedMySql`. Confidence: medium (default pool sizing; not observed at runtime).

### DT-m6 — `dev` profile carries a committed JWT secret and seed accounts
`src/main/resources/application-dev.yml:23` (`secret: ghGCINn4…`, labelled DEV ONLY) and `:3` (seed with the shared BCrypt password of every seed account, admin included). Correctly absent from `application.yml`, where the secret has no usable default and an empty secret fails at startup (`SecurityConfig.java:165-180`). Risk only if someone deploys with `SPRING_PROFILES_ACTIVE=dev`. Optional: fail startup when `dev` is active and the datasource host is not localhost or Docker Compose. Confidence: high (fact); low risk.

## Checked and found clean
- Migrations: `git diff schema-v1 0374f3d -- src/main/resources/db/migration` is empty; only V1–V6 exist (no V7+). All DDD `uq_/ck_/ix_/trg_` names are present (the extras are the ADR-002 `refresh_tokens` ones, `uq_roles_code`, `ck_roles_code` (DERIVED), and the `_bi/_bu/_bd` trigger suffixes).
- Entity mapping: no `double`/`BigDecimal`/`LocalDateTime`/`Date` in any `entity/`; money is `long`; no `@Enumerated` or `@Lob`; TEXT columns use `columnDefinition = "TEXT"`; immutable columns are `updatable = false` (main_instructor_id, snapshots, order/payment amounts, FKs); `updated_at` is `insertable/updatable = false`; the generated `pending_student_id` / `pending_order_id` are unmapped. `ddl-auto: validate` and `open-in-view: false` are set in `application.yml`.
- Time: no `Instant.now()` / `LocalDateTime.now()` / `new Date()` / `currentTimeMillis` in `src/main`; everything uses the injected `Clock`.
- `@Transactional` appears only in `service/`; query services are class-level `readOnly = true`; the stubs are `readOnly = true` (a write would be refused); `MANDATORY` propagation is used for `CertificateService` / `SessionService.startSessionForNewUser`; refresh rotation and password change use `TransactionTemplate` (no self-invocation); the READ COMMITTED reasoning in `ProgressService` / `ReviewService.create` holds.
- Events are published inside transactions; catalog eviction is AFTER_COMMIT and synchronous with no DB access; mail and cart listeners are `@Async` AFTER_COMMIT; role and session cache evictions are deferred to after commit (`RoleCacheEvictor`, `SessionStatusCache`).
- Lock order: authoring always locks the course row first; learning locks a single row (enrollment, question, review or answer); identity locks the user row; commerce locks the cart row. I found no service that locks two of these in different orders. FK shared locks (progress → lesson, course → category) create no cycle.
- `INSERT IGNORE` (`EnrollmentRepository.java:31-37`, `:56-62`): only called with ids just read in the same transaction (a published free course, the JWT user); the caveat is documented. `createIfAbsent` uses `ON DUPLICATE KEY UPDATE`, which does not hide FK errors.
- N+1: list services batch the cross-module lookups (`findSummaries(ids)`, `findByEnrollmentIdIn`, `countByCourseIdIn`, `findActiveRoleCodesByUserIdIn`); catalog pages are one statement with `COUNT(*) OVER ()`; there is no paging with fetch joins. The other growing-table queries are supported by an index (uq/ix prefixes on student_id, enrollment_id, question_id, token_hash, family_id, `ix_payment_review_queue`, `ix_lesson_progresses_resume`).
- Seed: `db/seed` is only in `application-dev.yml` and in the two seed test annotations (`@TestPropertySource`); `application.yml` and `application-test.yml` use `db/migration` only.
- Stubs (6 endpoints: POST /orders, /orders/{code}/cancel, /orders/{code}/payments, /webhooks/payments/fake, /admin/payments/{id}/resolve, GET /me/certificates/{code}/pdf): every one calls `StubSupport.mark` and has a `// STUB-P1:` comment; the services only read; the webhook is `@Profile({"dev","test"})` and fails closed on a blank secret. `CommerceStubsIT` / `CertificatePdfStubIT` assert no write statement, no `FOR UPDATE`, unchanged row counts and the header. The scheduler packages are empty; `edumy.scheduler.enabled: false`.
- Secrets: `.env` is git-ignored and not tracked (only `.env.example`); the JWT secret and webhook secret have no production default; the `toString()` of the secret holders is masked. Logs carry ids, not emails, tokens or JWTs. The only unique key holding an email (`uq_users_email`) is mapped, so its 1062 message (which contains the value) is never logged.
- Out-of-scope features (AGENTS.md §8): none found.
- Tests: no H2, no `@Disabled`, no commented-out `@Test`, no `assumeTrue`, no `Thread.sleep`, no `@DirtiesContext`, no `@TestMethodOrder`. Time-dependent tests use `MutableClock` / `ManualClock`; fixture users use BCrypt(4); write ITs assert DB rows, not only status codes.

## Test-suite cost notes
Distinct Spring contexts (each one is a full start; the MySQL container itself is shared):
1. `@MySqlSpringBootTest`: whole app + MockMvc, database `edumy`, ~37 classes (`@RecordApplicationEvents`, `@Transactional`, `OutputCaptureExtension` and `@TestInstance` do not split the cache key).
2. `@MySqlSeedSpringBootTest`: whole app, `edumy_seed`, plus `@TestPropertySource` and different `@Import`s (17 classes).
3. `@MySqlJdbcTest`: `@JdbcTest` on `edumy` (6 classes).
4. `@MySqlSeedTest`: `@JdbcTest` on `edumy_seed` (1 class).
5–10. Six `@ApplicationModuleTest` contexts, all different by construction: identity (+`@Import(JwtTestTokens)`), catalog (no mocks), authoring (2 `@MockitoBean`), commerce (5 `@MockitoBean` + 1 `@MockitoSpyBean`), learning (5 `@MockitoBean`), notification (2 `@MockitoBean`). docs/ai/07 estimates 15–25 s each, i.e. about 1.5–2.5 min of the run just to start them.
- Plus cheap ad-hoc `AnnotationConfigApplicationContext`s in `PaymentWebhookProfileTest`.

Structural cost drivers seen in the code:
- 10 cached contexts stay alive for the whole fork (pools, async executors, Caffeine), see DT-m5.
- Most of the 1338 `@Test`s are MockMvc ITs that insert users and fixtures and run `CourseFixtures.deleteAll()` + `TestUsers.deleteAll()` in `@AfterEach` (several DELETEs per test).
- Identity flows go through the real `PasswordEncoder` at cost 12 (`SecurityConfig.java:65`), roughly 0.25 s per hash. Each login, register, change or reset request in `AccountFlowsIT`, `AuthSessionIT`, `MeControllerIT`, `AbuseLimitersIT`, `JwtSecurityIT` and `IdentityMailListenerIT` pays it, often twice (`matches` + `encode`). A test-profile property for the strength would remove most of it.
- Concurrency tests repeat rounds (e.g. 4 rounds in `ProgressWriteIT`).
- Awaitility waits (≤10 s) only cost time when the async work is slow.
- Surefire runs a single fork with the ITs inside `./mvnw test` (docs/ai/07).

## Verdict
Schema discipline, type mapping, Clock usage, stub rules and secret handling are clean, and the test infrastructure is well built (real MySQL, no H2, no skipped tests).
One systemic transaction defect remains: authoring write services, and two identity paths, make a plain read before taking the course or user lock, so under REPEATABLE READ they act on stale rows (DT-B1, DT-M1).
Recommended: put READ COMMITTED on those write methods and add one concurrency IT per case before Phase 2, since T1 and T2 will repeat the same "lock, then read" pattern.
