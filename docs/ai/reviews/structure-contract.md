# P10 review — structure & contract (snapshot 0374f3d)

Scope / method: read-only review of the worktree at 0374f3d: all of `src/main/java` (31 controllers, 94 mappings, every DTO record), `ErrorCode`, `messages_vi.properties`, `GlobalExceptionHandler`, `SecurityConfig`, `WebConfig`, module `package-info`s, entities, repositories, plus a few tests; docs AGENTS.md, 01, 04, 05, ADR-003, ADR-007.
I built an endpoint inventory from `@(Get|Post|Put|Patch|Delete)Mapping` and diffed it in both directions against §5.4, then compared every request/response record field by field with the per-phase notes. Boundary checks used greps (imports per module, SQL table names, `@Table`, associations, `@Transactional`, Lombok), then I read the code at each hit.
NOT covered in depth: checking that every documented error code is thrown on its endpoint (I spot-checked; I did not trace every service path), the springdoc output at runtime (nothing was built or run), and `src/test` beyond what a finding needed.
Already-known items from the brief (stubs, approved missing endpoints, listed P10 issues) are not repeated.

## BLOCKER

None found. No import of another module's non-`api` package, no cross-module JPA association, no write to a table the module does not own, and catalog is SELECT-only.

## MAJOR

### SC-M1 — learning controllers authorize through authoring's internal `@courseAccess` bean, outside the `api/` gate
- `src/main/java/com/edumy/learning/controller/InstructorQuestionController.java:23`, `src/main/java/com/edumy/learning/controller/InstructorStudentController.java:33`
- Evidence:
  ```java
  @PreAuthorize("@courseAccess.isActiveInstructor()")      // InstructorQuestionController:23
  @PreAuthorize("@courseAccess.isInstructor(#courseId)")   // InstructorStudentController:33
  ```
  The bean is `com.edumy.authoring.security.CourseAccess` (`authoring/security/CourseAccess.java:27`), which is not in `authoring.api`. docs/ai/01 §1.8 (`docs/ai/01-architecture.md:291-293`) says UC-I19 "checks access through `authoring::api.CourseAccessApi`". docs/ai/04 §4.3 and the §5.4 notes, on the other hand, name `@courseAccess`, so the docs contradict each other.
- Why it matters: this is a runtime dependency of `learning` on a non-`api` package of `authoring`, made by a SpEL string. `ModularityTest` cannot see it. `LearningModuleIT` (`@ApplicationModuleTest`) mocks only the `*Api` interfaces, so it would not catch it either. Renaming the bean or changing its methods in authoring breaks these two learning endpoints only at request time.
- Fix: add learning-side methods, e.g. `EnrollmentAccess.isActiveInstructor()` and `isCourseInstructor(courseId)`, built on `RoleQueryApi.hasActiveRole` and `CourseAccessApi.isCourseInstructor`. They must keep the same order: role check first → `false` (403), then relation → 404. Point both `@PreAuthorize` at them. Then align docs/ai/04 §4.3 and the §5.4 learning notes with §1.8.
- Confidence: high on the facts. Severity is MAJOR, not BLOCKER, because `ModularityTest` stays green.

### SC-M2 — query-parameter type errors return English, technical text in `errors[].message`
- `src/main/java/com/edumy/shared/error/GlobalExceptionHandler.java:103-104`
- Evidence:
  ```java
  ex.getBindingResult().getFieldErrors()
          .forEach(fe -> errors.add(new FieldViolation(fe.getField(), fe.getDefaultMessage())));
  ```
  For a binding (type-mismatch) error, Spring sets the default message to the conversion exception's message ("Failed to convert property value of type 'java.lang.String' to required type 'java.lang.Long' for property 'categoryId'; …"). No `typeMismatch*` key exists in `messages_vi.properties` or `ValidationMessages.properties`, and the handler never resolves codes through the `MessageSource`. `CatalogCourseControllerIT.java:270-275` tests `?categoryId=abc` but only asserts `message` is not empty.
- Why it matters: §5.2 / AGENTS §6 promise Vietnamese texts, and the SPA shows `errors[].message`. Every `@ModelAttribute` query record is affected: `categoryId` (catalog), `createdFrom` / `createdTo` (admin orders, documented as "bad date → 400"), `awaitingReview` (admin payments), `lessonId` / `mine` / `unanswered` (course questions), `courseId` (instructor questions). The text also leaks Java type names.
- Fix: for a `FieldError` that `isBindingFailure()`, use a Vietnamese text instead, e.g. `messages.getMessage("typeMismatch", …, VI)` with a new key `typeMismatch=Giá trị không đúng định dạng`, or resolve `fe` through the `MessageSource`. Then make the test assert the exact text.
- Confidence: high.

### SC-M3 — CORS exposes only `Retry-After`: the SPA cannot read `Location`, `X-Edumy-Stub`, `X-Trace-Id` or `Content-Disposition`
- `src/main/java/com/edumy/shared/config/WebConfig.java:43`
- Evidence:
  ```java
  .exposedHeaders(HttpHeaders.RETRY_AFTER) // 429 of the abuse limiters (docs/ai/04 §4.4)
  ```
  The SPA runs on another origin (`http://localhost:5173`, §4.5). The contract promises these headers to it:
  - `Location` on every 201 (§5.4 authoring and Q&A notes: "201 carries `Location`");
  - `X-Edumy-Stub: true` (`shared/web/StubSupport.java:6-7`: "so the front end can see `X-Edumy-Stub: true`"; AGENTS §7);
  - `X-Trace-Id` (`TraceIdFilter.java:28`);
  - `Content-Disposition: attachment; filename="<code>.pdf"` on the certificate PDF (P07 note).

  None of them is a CORS-safelisted response header, so cross-origin JavaScript gets `null` for all four.
- Why it matters: the stub marker cannot do its documented job for the real SPA. A fetch/blob PDF download cannot get the filename. Any SPA logic that follows `Location` breaks. Bodies still carry ids and `traceId`, which softens the impact.
- Fix: `.exposedHeaders(RETRY_AFTER, LOCATION, CONTENT_DISPOSITION, StubSupport.HEADER, TraceIdFilter.HEADER)`. Add one CORS test (an `Origin` header plus an assertion on `Access-Control-Expose-Headers`), and list the exposed headers in docs/ai/04 §4.5.
- Confidence: high on the mechanism; medium on impact (depends on SPA usage).

### SC-M4 — services take `dto/request` records, which §1.4 forbids
- `src/main/java/com/edumy/commerce/service/OrderQueryService.java:5,69` · `commerce/service/OrderService.java:12` · `commerce/service/PaymentQueryService.java:3,62` · `commerce/service/PaymentService.java:13` · `commerce/service/ReconciliationService.java:7` · `learning/service/QaQueryService.java:9-10`
- Evidence:
  ```java
  import com.edumy.commerce.dto.request.AdminOrderSearchRequest;
  public Page<AdminOrderResponse> search(AdminOrderSearchRequest filter, Pageable pageable) {
  ```
  docs/ai/01 §1.4 lists `service → controller/dto.request` as forbidden ("services take ids, primitives or a `record XxxCommand` defined next to the service"). Authoring and identity follow the rule (`CreateCourseCommand`, `UpdateLessonCommand`, primitives). Commerce (5 services) and learning `QaQueryService` do not. The controllers pass the HTTP record straight through, e.g. `AdminOrderController.java:55,81` and `OrderController.java:68`.
- Why it matters: it couples the HTTP contract (Bean Validation, query binding) to the service API. Phase 2 will rewrite exactly these commerce services (T1/T2/UC-A18). That is the cheapest moment to fix this, and otherwise a phase-2 `XxxApi` caller would have to build an HTTP DTO.
- Fix: map to primitives or `XxxCommand` / `XxxCriteria` records in `service/` inside the controllers (e.g. `OrderSearchCriteria`, `ResolvePaymentCommand`, `QuestionFilter`). Do it while replacing the stub bodies.
- Confidence: high.

## MINOR

### SC-m1 — §1.5 per-module trees are out of date
- `docs/ai/01-architecture.md:115-236` versus the code.
- Classes the doc lists that do not exist:
  - authoring `api/SellableCourse`, `api/LessonRef` (the code has `CourseSummary`, `ChapterOutline`, `LessonContent`, which §1.9 does describe);
  - shared `config/OpenApiConfig`, `web/PageResponses`;
  - learning `api/GrantEnrollmentCommand` and commerce `api/OrderPaid`. These belong to the phase-2 payment work: `EnrollmentApi` today has only `owns` / `findOwnedCourseIds` (`learning/api/EnrollmentApi.java`) and `commerce/api` holds only `package-info`.
- Classes in the code that the doc omits:
  - authoring `CategoryQueryService`, `InvitationQueryService`;
  - learning `CertificateQueryService`;
  - identity `SecureTokens`, `SessionTokens`, `AuthEndpointBearerTokenResolver`, `AuthOriginFilter`;
  - shared `AsyncConfig`, `FieldViolation`, `JpaEntities`, `AuthenticatedUser`, `TraceIdFilter`;
  - DTOs `CourseSearchResponse`, `AdminUserSearchRequest`, `AdminOrderSearchRequest`, `AdminPaymentSearchRequest`, `AdminOrderResponse`, `PaymentWebhookResponse`, `CourseQuestionSearchRequest`, `InstructorQuestionSearchRequest`, `ResolveQuestionRequest`.
- Why it matters: §1.5 is labelled "AUTHORITATIVE"; agents placing new classes read it first.
- Fix: refresh the trees and mark the phase-2 items "(phase 2)".
- Confidence: high.

### SC-m2 — no OpenAPI configuration: no bearer security scheme in Swagger UI
- `src/main/java/com/edumy/shared/config/` contains no `OpenApiConfig`. The repo has no `@SecurityScheme` / `OpenAPI` bean (springdoc is enabled in `application-dev.yml:14-18`).
- Why it matters: in dev, Swagger UI has no "Authorize" button for the JWT, so almost every endpoint cannot be tried from `/swagger-ui.html` (AGENTS §9).
- Fix: add `shared/config/OpenApiConfig` with an HTTP bearer `SecurityScheme` applied globally (`/auth/**` and `/catalog/**` can override it).
- Confidence: medium (runtime output not checked).

### SC-m3 — one operation summary does not start with `UC-xx`
- `src/main/java/com/edumy/learning/controller/LearningController.java:59`: `@Operation(summary = "D-34 Ghi nhận thời điểm truy cập bài học …")`
- §5.1 requires `summary = "UC-xx …"`. All other 93 operations comply.
- Fix: `"UC-S09 D-34 Ghi nhận …"`, or record the D-code exception in §5.1.
- Confidence: high.

### SC-m4 — the catalog list advertises two `sort` query parameters
- `src/main/java/com/edumy/catalog/controller/CatalogCourseController.java:47-48`: `CourseSearchRequest.sort` (catalog order) and `@ParameterObject Pageable` (Spring `sort`) both bind `?sort=`.
- At runtime the Pageable sort is ignored (`CachedCourseQueryService.java:56` rebuilds `PageRequest.of(page, size)`). The generated OpenAPI, however, lists `sort` twice (different types), which is invalid for codegen.
- Fix: hide the Pageable sort (`@Parameter(hidden = true)` plus explicit `page` / `size` parameters, or a custom parameter object).
- Confidence: medium.

### SC-m5 — broken Markdown tables in docs/ai/05 §5.4
- `docs/ai/05-api-contract.md:198-200`: the admin-user rows (`GET /admin/users`, lock / unlock, roles) come after the "Identity response codes" bullet list with no table header, so they render as plain text and drop out of the catalog.
- `docs/ai/05-api-contract.md:238-239`: a blank line splits the front-end-routes table, so the review-result routes lose their header.
- Fix: move the three identity rows into the identity table and remove the blank line.
- Confidence: high.

### SC-m6 — the same flag is `preview` in learning but `isPreview` everywhere else
- `src/main/java/com/edumy/learning/dto/response/LearningOutlineResponse.java:22` (`boolean preview`) versus `catalog/dto/response/CourseDetailResponse.java:73` and `authoring/dto/response/LessonResponse.java:15` (`isPreview`).
- §5.4 documents each as coded, so this is not drift from the doc. It is still an inconsistency the SPA must special-case.
- Fix: rename it to `isPreview` while the SPA is still being built, and update the §5.4 P05-learning note.
- Confidence: high.

### SC-m7 — the foreign-`Origin` refusal on refresh/logout is not in the contract
- `src/main/java/com/edumy/identity/security/AuthOriginFilter.java:54` answers 403 `ACCESS_DENIED`. docs/ai/04 §4.5 describes the check, but §5.4 identity lists no 403 for `/auth/refresh` or `/auth/logout`.
- Fix: add "foreign `Origin` → 403 `ACCESS_DENIED`" to the identity response codes.
- Confidence: high.

## Checked and found clean
- **Endpoint inventory**: all 94 mappings, in both directions, match the §5.4 catalog: path, method, and success status (201/200 split, 202/204 split, 204 vs 200 on category delete).
- **Record fields**: every request/response field matches the per-phase notes, for all 6 modules.
- **ErrorCode vs §5.2**: 48 codes on each side, identical names and HTTP statuses. Every code has `error.<CODE>.title` and `.detail` in `messages_vi.properties`, with no missing or extra keys. `ADMIN_LAST_ACTIVE` is unused, as §5.2 says ("reserved").
- **ProblemDetail shape** (`type`, `title`, `status`, `detail`, `instance`, `code`, `traceId`, `errors` omitted when empty) comes from one place, `GlobalExceptionHandler.problem`. Filter-chain errors are routed through `ProblemResponder`.
- **Validation limits** match: full name 100, course/question title 200, Q&A content 5000, review comment 2000, rating 1–5, BR-07 price via `@CoursePrice`, category name/slug, reject/hide reasons, moderation reason 500.
- **Module declarations**: `package-info` `allowedDependencies` match §1.8 exactly; every module has `api/` with `@NamedInterface("api")`; there is no import of another module's non-`api` package (grepped per module).
- **No forbidden folders** inside any module; the only `dto/` sub-folders are `request` and `response` (`shared/web` is allowed by §1.5).
- **JPA associations** stay inside their module. Each `@Table` is a table the module owns (AGENTS §4).
- **Native/modifying SQL** writes only own tables (`chapters`, `lessons`, `carts`, `enrollments`). Catalog repositories are SELECT-only `JdbcClient`.
- **Controllers** import no repository or entity. `@Transactional` appears only in `service/`, and every `*QueryService` is `readOnly` at class level. Entities have no `@Data` / `@EqualsAndHashCode` / `@ToString`. No entity is returned from a controller.
- **OpenAPI and paging**: every controller has `@Tag(name = "<module>")`; 94/94 operations have `@Operation`. Paged endpoints return `PagedModel` (VIA_DTO), `size` is capped at 100 (`WebConfig.java:53-55`), and `sort` is ignored as documented.

## Verdict
No blocker: module boundaries and table ownership hold, and the endpoint, record and ErrorCode contracts match docs/ai/05 almost exactly.
Fix before the SPA integrates: SC-M2 (Vietnamese texts for type errors) and SC-M3 (CORS exposed headers). Fix SC-M1 and SC-M4 while rewriting the phase-2 commerce services.
The minors are mostly doc hygiene (stale §1.5 trees, broken §5.4 tables, missing OpenAPI bearer scheme).
