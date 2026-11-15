# 10 — IDOR / NFR-01 coverage: every endpoint -> owner relation -> proving test (P09 item 3)

Rule (NFR-01, docs/ai/04 §4.3): a protected endpoint with a path id that names a user-owned or relationship-scoped
resource answers a caller **with no relation to it** exactly what it answers for an id that **does not exist**: 404
`RESOURCE_NOT_FOUND`, bodies equal except `traceId` / `instance`, no data of the owner in the body.

**403/404 principle (P10, ADR-008 §2):** only a resource every outsider may know exists — a **published** course — may
answer a missing relation with 403 and a business code (`ENROLLMENT_REQUIRED`, so the SPA shows the course page,
UC-S09); every non-public resource (draft / pending / rejected / hidden course, other users' data) answers 404 as
above; a missing role is 403 `ACCESS_DENIED` before any lookup.

The suite is `src/test/java/com/edumy/security/IdorIT.java` (`@MySqlSpringBootTest`, one table of endpoints, every test
parameterized over it). Existing P05-P07 ITs prove the same per feature (column "Existing test"); `IdorIT` proves it for
all of them in one place, with the same fixtures for every caller, and adds the missing layers (the control request of
the owner, 401, the documented 403s).

## 1. Counts (94 controller mappings, `grep '@(Get|Post|Put|Patch|Delete)Mapping' *Controller.java`)

| Group | Mappings | Proof |
|---|---:|---|
| A. Subject to the A/B rule (path / query id of an owned or relationship-scoped resource) | 43 | 42 with a 404 row in `IdorIT` (46 rows: 4 are state variants of a mapping) + 1 documented 403 (review create); **and** an existing IT each |
| B. Admin-only with an id (admins see every user's data by design) | 17 | `IdorIT` 403 table (non-admin: student, instructor, the data's owner) + existing IT |
| C. Public by id / slug | 4 | 3 in the `IdorIT` public table (unpublished / non-preview is 404 like unknown) + `CatalogCourseDetailIT`; certificate verification is public by design |
| D. No id: collections scoped by the caller, own profile, auth, create (no path id), webhook | 30 | exempt, reasons in §5 |
| **Total** | **94** | |

`IdorIT` = 203 test invocations: 46 stranger 404 rows (each with 2-10 callers) · 46 owner controls · 63 anonymous 401
(46 owned + 17 admin rows) · 23 instructor-role 403 rows · 17 admin 403 rows · 5 public 404 rows · 4 single tests (public
control, QA 403, review create 403). Existing-test-only mappings in group A: none (every one also has an `IdorIT` row);
group D mappings are covered by their own ITs only.

## 2. How `IdorIT` proves it (tests)

| Test | Rows | Asserts |
|---|---:|---|
| `stranger_404_identicalToMissing_noData` | 46 | for each stranger of the row: another user's id -> 404, `IdorAssertions.assertSameNotFound` against a missing id of the same shape, the body (minus `instance`, which echoes the caller's own path) contains none of A's emails, titles, slugs, order / certificate codes, question / answer / review texts (all carry a unique marker) |
| `owner_succeeds_theRowIsNotVacuous` | 46 | the owner of the resource gets the documented 2xx (or the BR-33 409 on the pending course), so a 404 above cannot come from a mistyped fixture; rows run in table order in one world, consuming resources last |
| `noToken_401` | 63 | no `Authorization` -> 401 `UNAUTHENTICATED` (route layer), for every owned and admin row |
| `noInstructorRole_403_beforeAnyLookup` | 23 | `/instructor/**` rows: a user with no active instructor role in the DB (student only, or a token that still claims `instructor` after revoke, BR-26 / D-07) -> 403 `ACCESS_DENIED`, body identical for an existing and a missing id |
| `adminRoute_nonAdmin_403_sameForMissing` | 17 | `/admin/**` rows with an id: student, instructor, the data's owner -> 403, identical for an existing and a missing id |
| `publicCatalog_unpublishedOrNonPreview_404` | 5 | anonymous and a student: a draft / pending course, its reviews, its lesson preview, a non-preview lesson of a published course -> 404 identical to unknown; `publicCatalog_publishedCourse_isReadable_control` proves a published one reads |
| `answer_visibleButNotAllowed_403_QA_REPLY_NOT_ALLOWED_BR38` | 1 | see §4 |
| `reviewCreate_noEnrollmentInPublishedCourse_403_ENROLLMENT_REQUIRED_E1` | 1 | see §4 |
| `reviewCreate_noEnrollmentInNonPublicCourse_404IdenticalToMissing_NFR01` | 1 | draft / pending course, 4 callers: 404 identical to an unknown id (P10, §6) |

Callers used as strangers: a student with no enrollment; a classmate (enrolled in the same course, so a "stranger" only
for the question / answer / review / order of another student); the course's main instructor on student-owned
endpoints; the admin on "own only" endpoints; an instructor of another course; co-instructors whose invitation is
`pending` (two), `declined`, `removed` (BR-04: only `accepted` is a relation); an `accepted` co-instructor on
main-only endpoints (BR-06) and on invitations of somebody else; a revoked instructor.

## 3. Group A: endpoint -> resource -> owner relation -> proof

`IdorIT` = a row of `IdorIT.OWNED`. Existing test = class#method under `src/test/java/com/edumy/`.

| Endpoint | Resource | Owner relation (who may access) | Existing test |
|---|---|---|---|
| GET `/instructor/courses/{id}` | course | main or accepted co-instructor | `authoring/controller/InstructorCourseControllerIT#otherInstructorsCourse_sameNotFoundAsMissing_NFR01`, `#pendingOrDeclinedInvitation_notACourseInstructor_404` |
| GET `/instructor/courses/{id}/review-status` | course | main or accepted co-instructor | same IT (suffix parameter) |
| PATCH `/instructor/courses/{id}` | course | main only (BR-06) | `authoring/controller/InstructorCourseWriteIT#updateAndDelete_otherInstructorsCourse_404IdenticalToMissing_NFR01` |
| DELETE `/instructor/courses/{id}` | course | main only | same |
| POST `/instructor/courses/{id}/submit` | course | main only | `authoring/controller/SubmitForReviewWriteIT#submit_coInstructorOrStranger_404Identical_statusUnchanged_BR06` |
| POST `/instructor/courses/{id}/co-instructors` | course | main only | `authoring/controller/CoInstructorWriteIT#inviteAndRemove_otherInstructorsCourse_404IdenticalToMissing_NFR01` |
| DELETE `/instructor/courses/{id}/co-instructors/{userId}` | course (target) | main only | same |
| POST `/instructor/courses/{id}/leave` | course | accepted co-instructor (main -> 403 by rule, not by id) | `CoInstructorWriteIT#leave_withoutAcceptedInvitation_404IdenticalToMissing_NFR01` |
| POST `/instructor/courses/{id}/chapters` | course | main or accepted co-instructor; `pending_review` -> 409 only for them (BR-33) | `authoring/controller/ChapterWriteIT#otherInstructor_404IdenticalToMissing_onEveryEndpoint_NFR01`, `authoring/security/AuthoringAccessIT#otherInstructor_onCourseOfA_sameBodyAsMissing_404_NFR01` |
| PUT `/instructor/courses/{id}/chapters/order` | course | same | `ChapterWriteIT` (same) |
| PATCH `/instructor/chapters/{id}` | chapter -> course | same | `ChapterWriteIT` (same) |
| DELETE `/instructor/chapters/{id}` | chapter -> course | same | `ChapterWriteIT` (same) |
| POST `/instructor/chapters/{id}/lessons` | chapter -> course | same | `authoring/controller/LessonWriteIT#otherInstructor_404IdenticalToMissing_onEveryEndpoint_NFR01` |
| PUT `/instructor/chapters/{id}/lessons/order` | chapter -> course | same | `LessonWriteIT` (same) |
| PATCH `/instructor/lessons/{id}` | lesson -> course | same | `LessonWriteIT` (same) |
| DELETE `/instructor/lessons/{id}` | lesson -> course | same | `LessonWriteIT` (same) |
| POST `/instructor/invitations/{id}/accept` | co-instructor invitation | the invitee | `CoInstructorWriteIT#acceptAndDecline_someoneElsesInvitation_404IdenticalToMissing_NFR01` |
| POST `/instructor/invitations/{id}/decline` | co-instructor invitation | the invitee | same |
| POST `/courses/{courseId}/invitations/{invitationId}/resend` (P10) | invitation of the course | main instructor; an invitation id under another course is 404 like an unknown id | `CoInstructorWriteIT#resend_foreignOrUnknownInvitation_orNotMain_404Identical_studentOnly403_noToken401_NFR01` (no `IdorIT` row: the 60 s cooldown of a freshly created invitation makes the owner control 429) |
| GET `/instructor/courses/{courseId}/students` | course | main or accepted co-instructor | `learning/controller/InstructorStudentControllerIT#students_idor_unrelatedCourse_404IdenticalToUnknown` |
| GET `/instructor/questions?courseId=` | course (query id) | main or accepted co-instructor | `learning/controller/QuestionReadsIT#instructorList_courseNotInstructed_404IdenticalToUnknown` |
| GET `/learn/courses/{courseId}` | course | enrolled, course instructor, admin (D-32); no relation: published → 403 `ENROLLMENT_REQUIRED` (§4), otherwise 404 | `learning/controller/LearningReadsIT#outline_notEnrolledOrNotRelated_publishedCourse403_nonPublic404IdenticalToUnknown` |
| GET `/learn/lessons/{lessonId}` | lesson -> course | same | `LearningReadsIT#lesson_notEnrolled_publishedCourse403_nonPublic404IdenticalToUnknown` |
| POST `/learn/lessons/{lessonId}/visit` | lesson -> course | same | `learning/controller/ProgressWriteIT#visit_notEnrolled_published403_hidden404IdenticalToUnknown_NFR01` |
| PUT `/learn/lessons/{lessonId}/completion` | lesson -> enrollment | enrolled only (instructor / admin too: published → 403, otherwise 404) | `ProgressWriteIT#completion_notEnrolled_otherCourse_instructor_published403_hidden404_NFR01` |
| POST `/courses/{courseId}/enrollments` | course | open only to **published** courses; draft / pending / rejected / hidden look unknown (BR-31) | `learning/controller/EnrollmentWriteIT#enroll_notPublished_404_sameBodyAsUnknown_BR31` |
| GET `/courses/{courseId}/questions` | course | enrolled, course instructor, admin; published → 403, otherwise 404 | `learning/controller/QuestionReadsIT#courseList_notEnrolled_publishedCourse403_nonPublic404IdenticalToUnknown` |
| POST `/courses/{courseId}/questions` | course | enrolled only (BR-37); published → 403, otherwise 404 | `learning/controller/QuestionWriteIT#ask_notEnrolled_publishedCourse403_hiddenCourse404_instructorAndAdminToo_E1_BR37` |
| GET `/questions/{id}` | question | visible: who can view the course; hidden: asker + admin; deleted: nobody | `QuestionReadsIT#thread_idor_studentOfAnotherCourse_404IdenticalToUnknown`, `#thread_hiddenQuestion_askerAndAdminOnly_others404`, `#thread_deletedQuestion_404ForEveryone` |
| PATCH `/questions/{id}` | question | the asker only | `QuestionWriteIT#edit_someoneElsesQuestion_404_identicalToUnknown_IDOR_NFR01` |
| DELETE `/questions/{id}` | question | the asker only | `QuestionWriteIT#delete_someoneElsesQuestion_404_identicalToUnknown_IDOR_NFR01` |
| POST `/questions/{id}/resolve` | question | the asker only | `QuestionWriteIT#resolve_notTheAsker_404_identical_IDOR_NFR01` |
| POST `/questions/{id}/answers` | question | asker or course instructor; a caller who **cannot see** it -> 404; one who can see it but is neither -> 403 (§4) | `learning/controller/AnswerWriteIT#answer_callerWhoCannotSeeTheQuestion_404_identicalToUnknown_BR38` |
| PATCH `/answers/{id}` | answer | its author only | `AnswerWriteIT#editAndDelete_someoneElsesAnswer_404_identicalToUnknown_IDOR_NFR01` |
| DELETE `/answers/{id}` | answer | its author only | same |
| PATCH `/reviews/{id}` | review | its author only | `learning/controller/ReviewWriteIT#update_someoneElsesReview_404_identicalToUnknown_IDOR_NFR01` |
| DELETE `/reviews/{id}` | review | its author only | `ReviewWriteIT#delete_someoneElsesReview_404_identicalToUnknown_IDOR_NFR01` |
| POST `/courses/{courseId}/reviews` | course | enrolled; no enrollment -> 403 `ENROLLMENT_REQUIRED` on a published course (§4), 404 identical to unknown on any other status (§6) | `ReviewWriteIT#create_notEnrolled_403_enrollmentRequired_nothingWritten_E1`, `#create_notEnrolled_nonPublicCourse_404IdenticalToUnknown_nothingWritten_NFR01` |
| GET `/me/certificates/{code}/pdf` | certificate | its student | `learning/controller/CertificatePdfStubIT#anotherUsersCode_unknownCode_identical404_IDOR`, `#malformedCodes_sameNotFound_noWrites` |
| GET `/orders/{orderCode}` | order | its student | `commerce/controller/StudentCommerceReadsIT#orderDetail_otherStudentsOrder_sameNotFoundAsUnknown_IDOR` |
| GET `/orders/{orderCode}/payments/current` | order -> payment | its student | `StudentCommerceReadsIT#currentPayment_otherStudentsOrder_sameNotFoundAsUnknown_IDOR` |
| POST `/orders/{orderCode}/cancel` | order | its student | `commerce/controller/OrderWriteIT#cancel_anotherStudentsOrder_404IdenticalToAnUnknownCode_untouched_NFR01` (P11-b) |
| POST `/orders/{orderCode}/payments` | order | its student | `PaymentQrIT#notPending_409_foreign404IdenticalToUnknown_noToken401` (P11-c) |
| DELETE `/cart/items/{courseId}` | cart line | the cart's student (isolation: another student's cart row stays untouched) | `commerce/controller/CartWriteIT#remove_courseOnlyInSomeoneElsesCart_404_theirRowUntouched_NFR01`, `#remove_isScopedToTheCallersCart_otherStudentsRowUntouched_NFR01` |

State variants in `IdorIT` (same mapping, a second row): POST `/instructor/courses/{pendingCourse}/chapters`, PATCH
`/instructor/chapters/{pendingCh}`, PATCH `/instructor/lessons/{pendingLesson}` (a stranger gets 404, the owner 409
`COURSE_NOT_EDITABLE`: the state conflict is never visible to a non-instructor, BR-33) and GET `/questions/{hiddenQuestion}`
(a classmate and the course's instructor get 404, the asker 200).

Access beans are additionally proved through the probe controller (`authoring/security/AuthoringAccessIT`,
`learning/security/LearningAccessIT`).

## 4. Rows that legitimately answer 403 (and why that is not an enumeration leak)

| Row | Status | Why the status does not reveal anything |
|---|---|---|
| every `/instructor/**` mapping, caller without an **active instructor role in the database** (student only, or stale JWT after revoke) | 403 `ACCESS_DENIED` | decided from the caller's own roles **before** any lookup (`@courseAccess.*` returns `false`); the body is identical for an existing and a missing id (`noInstructorRole_403_beforeAnyLookup`) |
| every `/admin/**` mapping with an id, non-admin | 403 `ACCESS_DENIED` | route rule `hasRole('ADMIN')` (admins see all users' data by design); identical for existing and missing (`adminRoute_nonAdmin_403_sameForMissing`); revoked-admin / stale token: `AdminUserWriteIT`, `QaModerationIT`, `ReviewWriteIT`, `AdminCourseReviewWriteIT` |
| POST `/questions/{id}/answers`, a caller who **can already read the thread** (classmate, admin) but neither asked it nor instructs the course | 403 `QA_REPLY_NOT_ALLOWED` (BR-38) | the same caller gets the whole question from `GET /questions/{id}` (200), so "it exists" is already known to them; a caller who cannot see it gets the 404 of §3 (`answer_visibleButNotAllowed_403_QA_REPLY_NOT_ALLOWED_BR38` asserts both the 200 and the 403 and that nothing was written) |
| learning endpoints of a **published** course (`/learn/courses/{id}`, `/learn/lessons/{id}` + visit / completion, `GET`/`POST /courses/{id}/questions`), no relation | 403 `ENROLLMENT_REQUIRED` (UC-S09, P10) | the course is public; the body is one and the same for every caller and endpoint and holds no data (`IdorIT#learning_publishedCourseWithoutRelation_403_ENROLLMENT_REQUIRED_sameBody_noData`); the main course of the `IdorIT` world is **hidden**, so the `OWNED` rows prove the 404 side |
| POST `/courses/{courseId}/reviews`, no enrollment | 403 `ENROLLMENT_REQUIRED` (UC-S12 E1) | only for a **published** course: its id is public (catalog, cards), nothing owned by another user is disclosed; unknown **or non-published** id -> 404 identical (P10) |
| `POST /courses/{id}/enrollments` on a published course | 409 / 422 by course state (`ENROLLMENT_OWN_COURSE`, `ENROLLMENT_COURSE_NOT_FREE`) | only for published (public) courses and only after the caller's own state is evaluated |
| `PATCH`/`DELETE` on a `pending_review` course's chapters / lessons / chapter create | 409 `COURSE_NOT_EDITABLE` | only for a course instructor; a stranger gets 404 first (`IdorIT` pending rows) |
| accept / decline of an invitation already answered or revoked | 409 `INVITATION_NOT_PENDING` | only for the invitee; any other caller gets 404 |

## 5. Groups B, C, D

### B. Admin-only with an id (17): 403 for non-admin, tested in `IdorIT` and in

| Endpoint | Existing test of the 403 |
|---|---|
| POST `/admin/courses/{id}/approve\|reject\|hide\|unhide` | `AdminCourseReviewWriteIT#everyAction_instructorOrStudent_403_withoutToken_401_unchanged` |
| PATCH / DELETE `/admin/categories/{id}` | `AdminCategoryWriteIT#writes_asInstructorOrStudent_403_nothingChanges` |
| POST `/admin/users/{id}/lock\|unlock`, PUT / DELETE `/admin/users/{id}/roles/instructor` | `AdminUserWriteIT#nonAdmin_403_everyEndpoint_nothingChanges`, `#adminRoleRevokedInDatabase_staleJwt_403_BR26` |
| GET `/admin/users/{userId}/instructor-revocation-impact` (P10) | `IdorIT` admin row + `AdminInstructorControllerIT#impact_nonAdmin_403_noToken_401` |
| POST `/admin/payments/{id}/resolve` | `ReconciliationIT#adminRoleRevokedInTheDatabase_403_nonAdmin403_unknown404_noToken401_BR26` (P11-d) |
| GET `/admin/payments/{id}` (P11-d, Q4) | `ReconciliationIT#detail_linesOwnedCoursesAndTheAmountTheProviderReports_Q4` + `IdorIT` admin row |
| POST `/admin/reviews/{id}/hide\|restore` | `ReviewWriteIT#adminActions_studentOrInstructor_403_withoutToken_401_unchanged` |
| POST `/admin/questions\|answers/{id}/hide\|restore` | `QaModerationIT#nonAdmin_403_accessDenied_withoutToken_401_unchanged` |

### C. Public by id / slug (4)
| Endpoint | Reason / proof |
|---|---|
| GET `/catalog/courses/{slug}` | public; a draft / pending / rejected / hidden course is 404 like unknown, also for its instructor (D-08): `IdorIT` public rows, `CatalogCourseDetailIT#detail_notPublished_404EvenForItsInstructor_D08`, `#detail_hiddenCourse_anonymousOrUnrelated_404SameAsUnknown_UCG04E1_NFR01` |
| GET `/catalog/courses/{slug}/reviews` | same visibility as the page: `IdorIT`, `CatalogCourseDetailIT#reviews_hiddenCourse_sameVisibilityAsPage_UCG04` |
| GET `/catalog/courses/{slug}/lessons/{lessonId}/preview` | only preview lessons of published courses: `IdorIT`, `CatalogCourseDetailIT#preview_notAvailable_404SameAsUnknownLesson_UCG11` |
| GET `/catalog/certificates/{code}` | public by design (UC-G14: the code is the credential, rate-limited NFR-03); unknown code 404: `CertificateVerificationControllerIT#verify_unknownCode_404_UCG14E1` |

### D. No id to swap (30): exempt
| Mappings | Reason |
|---|---|
| GET `/catalog/categories`, GET `/catalog/courses` (2) | public collections |
| POST `/auth/register`, `/email-verification/confirm`, `/email-verification/resend`, `/password/forgot`, `/password/reset`, `/login`, `/refresh`, `/logout` (8) | public / cookie-bound, no resource id (`AccountFlowsIT`, `AuthSessionIT`) |
| GET / PATCH `/me`, PUT `/me/password`, POST `/me/instructor-registration` (4) | the caller's own profile, no id |
| GET `/orders`, GET `/cart`, GET `/me/certificates`, GET `/me/enrollments`, GET `/instructor/courses`, GET `/instructor/invitations` (6) | collections scoped by the caller's id in the query (tested for scoping in their ITs: `StudentCommerceReadsIT`, `CartWriteIT`, `CertificatePdfStubIT`/`LearningReadsIT`, `InstructorCourseControllerIT`, `InvitationControllerIT`) |
| POST `/orders` (stub), POST `/cart/items` (id in the body; unpublished / unknown -> same 404: `CartWriteIT#add_notPublished_404_sameBodyAsUnknown_BR31`), POST `/instructor/courses` (3) | create: no existing resource addressed; instructor role -> 403 (`InstructorCourseWriteIT`) |
| GET `/admin/categories`, `/admin/courses/pending`, `/admin/orders`, `/admin/payments`, `/admin/users` (5) | admin collections over all users by design; 403 for non-admin tested in `AdminCategoryControllerIT`, `AdminCourseReviewControllerIT`, `AdminOrderControllerIT#nonAdmin_403`, `AdminUserControllerIT`; POST `/admin/categories` (1) in `AdminCategoryWriteIT` |
| POST `/webhooks/payments/fake` (1) | authenticated by HMAC signature (`X-Signature`), not by user; dev / test profile only (`PaymentWebhookProfileTest`, `CommerceStubsIT`) |

## 6. Findings

- **Closed in P10 (security MINOR-2):** review create used to answer 403 `ENROLLMENT_REQUIRED` for any existing course,
  so a draft / pending / rejected course id of another instructor could be told apart from an unknown id. Now
  `ReviewService.create` answers the unknown-id 404 when the caller has no enrollment and the course is not published
  (`IdorIT#reviewCreate_noEnrollmentInNonPublicCourse_404IdenticalToMissing_NFR01`,
  `ReviewWriteIT#create_notEnrolled_nonPublicCourse_404IdenticalToUnknown_nothingWritten_NFR01`).
- No other endpoint of the matrix answered 200 with another user's data, a differing 404 body, or a 403 / 409 that
  depends on whether a foreign id exists.
