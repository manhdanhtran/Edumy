# 05 — API contract, errors, caching

## 5.1 Conventions
- Base path `/api/v1`. JSON camelCase. Resource nouns, plural. Actions that are state transitions use a verb
  sub-resource: `POST /admin/courses/{id}/approve`.
- Public course pages use `slug`; orders use `orderCode`; certificates use `certificateCode`; everything else uses
  numeric ids.
- Time: ISO-8601 UTC strings (`2026-11-12T03:15:00Z`); the SPA converts to `Asia/Ho_Chi_Minh`.
- Money: integer VND (`"price": 499000`). `isFree` is derived in DTOs (`price == 0`), never stored.
- Progress percent is derived: `floor(completed * 100 / totalLessons)` (D-33).
- Pagination: query `page` (0-based), `size` (default 20, max 100), `sort`. Response = Spring Data `PagedModel`
  (`@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`):
  `{ "content": [...], "page": { "size": 20, "number": 0, "totalElements": 57, "totalPages": 3 } }`.
- Validation limits come from the SRS: full name ≤100, course/question title ≤200, question/answer content ≤5000
  plain text (D-44), review comment ≤2000 (D-35), rating 1–5, price 0 or ≥10 000 and multiple of 1 000 (BR-07).
- Request/response DTOs are records named `XxxRequest` / `XxxResponse` in the module's `dto/request` and `dto/response` folders (docs/ai/01 §1.3).
- OpenAPI via springdoc; every controller has `@Tag(name = "<module>")` and each operation `@Operation(summary = "UC-xx …")`.

## 5.2 Error format (RFC 9457)
```json
{
  "type": "https://edumy.local/problems/course-not-editable",
  "title": "Khóa học đang chờ duyệt, không thể chỉnh sửa",
  "status": 409,
  "detail": "Khóa học #12 đang ở trạng thái pending_review (BR-33).",
  "instance": "/api/v1/instructor/courses/12",
  "code": "COURSE_NOT_EDITABLE",
  "traceId": "8f2c…",
  "errors": [ { "field": "title", "message": "Không được để trống" } ]
}
```
- `shared.error.GlobalExceptionHandler extends ResponseEntityExceptionHandler`; `BusinessException(ErrorCode, args)`;
  `BusinessException.withErrors(code, List<FieldViolation>)` fills `errors`. `errors` is omitted when empty.
- Status mapping: 400 malformed · 401 unauthenticated · 403 authenticated but role missing · 404 not found **or not yours** ·
  409 state conflict / unique violation · 422 business-rule violation on valid input · 429 rate limit.
- Status principle (decides the status of every new code): **403** = the caller has no permission ·
  **409** = the current state of the data does not allow it · **422** = the input is valid but breaks a business rule.

| ErrorCode | HTTP | Note |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Bean Validation; `errors` = field errors |
| `MALFORMED_REQUEST` | 400 | unreadable JSON, wrong type; other framework 4xx keep their status (405, 415…) with this code |
| `UNAUTHENTICATED` | 401 | |
| `AUTH_INVALID_CREDENTIALS` | 401 | |
| `ACCESS_DENIED` | 403 | |
| `EMAIL_NOT_VERIFIED` | 403 | D-02 |
| `ENROLLMENT_REQUIRED` | 403 | |
| `QA_REPLY_NOT_ALLOWED` | 403 | BR-38 |
| `RESOURCE_NOT_FOUND` | 404 | "does not exist" **and** "not yours": identical body (NFR-01); only `traceId`/`instance` differ |
| `DATA_CONFLICT` | 409 | untranslated unique/FK violation |
| `COURSE_NOT_EDITABLE` | 409 | BR-33 |
| `CART_COURSE_ALREADY_OWNED` | 409 | BR-10 |
| `REVIEW_HIDDEN_BY_ADMIN` | 409 | BR-35 |
| `ADMIN_LAST_ACTIVE` | 409 | BR-36 |
| `COURSE_SUBMIT_CHECKLIST_FAILED` | 422 | BR-07; `errors` lists every failed checklist item (`field` = item, `message` = Vietnamese reason) |
| `CART_OWN_COURSE` | 422 | BR-32 |
| `AUTH_LOGIN_TEMPORARILY_BLOCKED` | 429 | |
| `RATE_LIMITED` | 429 | |
| `INTERNAL_ERROR` | 500 | generic Vietnamese detail, never the exception message |
- `ConstraintViolationTranslator`: `DataIntegrityViolationException` → find the constraint/trigger name in the message
  (`uq_cart_items_cart_course`, `BR-02:` …) → known `ErrorCode`; unknown → 409 `DATA_CONFLICT` (log full cause at WARN).
- ErrorCode naming: `UPPER_SNAKE`, module-meaningful, e.g. `AUTH_INVALID_CREDENTIALS`, `AUTH_LOGIN_TEMPORARILY_BLOCKED`,
  `EMAIL_NOT_VERIFIED`, `COURSE_NOT_EDITABLE`, `COURSE_SUBMIT_CHECKLIST_FAILED`, `CART_COURSE_ALREADY_OWNED`,
  `CART_OWN_COURSE`, `ENROLLMENT_REQUIRED`, `REVIEW_HIDDEN_BY_ADMIN`, `QA_REPLY_NOT_ALLOWED`, `ADMIN_LAST_ACTIVE`.
  Messages (Vietnamese) live in `messages.properties` / `messages_vi.properties`, keyed by code.
- Other framework 4xx (405, 415, 406…) keep their original status and share the code `MALFORMED_REQUEST` (decision).

### Validation messages (decision — implement in P05/P06, not in P00)
Bean Validation texts are separate from the error-code texts above (they come from Hibernate Validator, not Spring's `MessageSource`):
- File: `src/main/resources/ValidationMessages.properties` — **no `_vi` suffix** (it is the default bundle, so it applies
  whatever the request locale is), saved as UTF-8.
- Override the default keys `jakarta.validation.constraints.*.message` (`NotBlank`, `NotNull`, `Size`, `Min`, `Max`,
  `Email`, `Pattern`…) with Vietnamese text, so DTOs need no per-field message in the common case.
- Put an explicit `message` on an annotation **only** when the text is specific (e.g. `@Size(max = 100, message = "Họ tên tối đa 100 ký tự")`,
  price rule BR-07). Prefer a key in `ValidationMessages.properties` (`message = "{course.price.invalid}"`) over inline text.
- These texts become the `message` of each entry in the ProblemDetail `errors` array (`VALIDATION_FAILED`).

## 5.3 Caching (Spring Cache + Caffeine)
| Cache | Key | TTL | Evicted by |
|---|---|---|---|
| `catalog.categories` | `'active'` | 1 h | category create/update/deactivate |
| `catalog.course-detail` | slug | 10 min | `CourseContentChanged`, `CourseApproved/Hidden/Unhidden`, `ReviewChanged` |
| `catalog.course-list` | hash of (q, categoryId, price, sort, page, size) | 60 s | TTL only (short, accepted staleness) |
| `catalog.rating` | courseId | 10 min | `ReviewChanged` |
| `identity.user-status` | userId | 30 s | lock/unlock |
| `identity.session-status` | sid | 30 s | any revoke |
| `identity.active-roles` | userId | 60 s | grant/revoke role, self-register GV |
Rules: cache only immutable DTO records, never entities, never `Optional`. Eviction listeners run AFTER_COMMIT.
**Never cache** carts, orders, payments, progress, Q&A threads, or anything whose staleness affects money or access.
Configure per-cache specs with a `CacheManagerCustomizer<CaffeineCacheManager>`; enable `recordStats()` and expose
via Actuator `caches`/metrics in dev.

## 5.4 Endpoint catalog (R = real in phase 1, S = stub `STUB-P1`)
### identity
| Method & path | UC | P1 |
|---|---|---|
| POST `/auth/register` | G05 | R |
| POST `/auth/email-verification/confirm` · POST `/auth/email-verification/resend` | G06 | R |
| POST `/auth/login` · POST `/auth/refresh` · POST `/auth/logout` | G07, G10 | R |
| POST `/auth/password/forgot` · POST `/auth/password/reset` | G08 | R |
| GET `/me` · PATCH `/me` · PUT `/me/password` | G09, G12 | R |
| POST `/me/instructor-registration` | G13 | R |
| GET `/admin/users?q=&status=&role=` | A01 | R |
| POST `/admin/users/{id}/lock` · POST `/admin/users/{id}/unlock` | A02, A03 | R |
| PUT `/admin/users/{id}/roles/instructor` · DELETE `/admin/users/{id}/roles/instructor` | A04, A05 | R |

### catalog (public, read model)
| Method & path | UC | P1 |
|---|---|---|
| GET `/catalog/categories` | G03 | R |
| GET `/catalog/courses?q=&categoryId=&price=free\|paid&sort=newest\|rating\|price_asc\|price_desc` | G01–G03 | R |
| GET `/catalog/courses/{slug}` (outline, instructors, avg rating, viewer context: owned / in cart / is instructor, D-12) | G04 | R |
| GET `/catalog/courses/{slug}/reviews` (visible only) | G04 | R |
| GET `/catalog/courses/{slug}/lessons/{lessonId}/preview` | G11 | R |
| GET `/catalog/certificates/{code}` (rate-limited) | G14 | R |

### authoring
| Method & path | UC | P1 |
|---|---|---|
| POST `/instructor/courses` · GET `/instructor/courses` · GET `/instructor/courses/{id}` | I01, I02 | R |
| PATCH `/instructor/courses/{id}` · DELETE `/instructor/courses/{id}` | I03, I04 | R |
| POST `/instructor/courses/{id}/chapters` · PATCH/DELETE `/instructor/chapters/{id}` · PUT `/instructor/courses/{id}/chapters/order` | I05–I08 | R |
| POST `/instructor/chapters/{id}/lessons` · PATCH/DELETE `/instructor/lessons/{id}` · PUT `/instructor/chapters/{id}/lessons/order` | I09–I12 | R |
| POST `/instructor/courses/{id}/co-instructors` (by email) · DELETE `/instructor/courses/{id}/co-instructors/{userId}` | I13, I16 | R |
| GET `/instructor/invitations` · POST `/instructor/invitations/{id}/accept` · POST `…/decline` | I14, I15 | R |
| POST `/instructor/courses/{id}/leave` | I20 | R |
| POST `/instructor/courses/{id}/submit` · GET `/instructor/courses/{id}/review-status` | I17, I18 | R |
| GET/POST `/admin/categories` · PATCH `/admin/categories/{id}` · DELETE `/admin/categories/{id}` (deactivate if used, D-21) | A06–A08 | R |
| GET `/admin/courses/pending` | A09 | R |
| POST `/admin/courses/{id}/approve` · `/reject` · `/hide` · `/unhide` | A10–A12, A16 | R |

### commerce
| Method & path | UC | P1 |
|---|---|---|
| GET `/cart` · POST `/cart/items` · DELETE `/cart/items/{courseId}` | S02, S01, S03 | R |
| POST `/orders` (T1) | S04 | **S** |
| GET `/orders` · GET `/orders/{orderCode}` | S06, S07 | R |
| POST `/orders/{orderCode}/cancel` | S16 | **S** |
| POST `/orders/{orderCode}/payments` (create QR) | S05 | **S** |
| GET `/orders/{orderCode}/payments/current` (polling) | S05 | R |
| POST `/webhooks/payments/fake` (dev only, HMAC header) | S05 | **S** |
| GET `/admin/orders` · GET `/admin/payments?status=` | A13, A14 | R |
| POST `/admin/payments/{id}/resolve` (`activated` \| `refund_outside`, note required) | A18 | **S** |

### learning
| Method & path | UC | P1 |
|---|---|---|
| POST `/courses/{courseId}/enrollments` (free only, BR-09) | S15 | R |
| GET `/me/enrollments` (with progress %, last accessed lesson) | S08 | R |
| GET `/learn/courses/{courseId}` (outline + progress) | S11 | R |
| GET `/learn/lessons/{lessonId}` · POST `/learn/lessons/{lessonId}/visit` | S09, D-34 | R |
| PUT `/learn/lessons/{lessonId}/completion` `{ "completed": true }` (issues certificate at 100%, BR-39) | S10 | R |
| POST `/courses/{courseId}/reviews` · PATCH `/reviews/{id}` · DELETE `/reviews/{id}` (→ withdrawn) | S12–S14 | R |
| POST `/admin/reviews/{id}/hide` · `/restore` | A15, A17 | R |
| GET/POST `/courses/{courseId}/questions` · GET/PATCH/DELETE `/questions/{id}` · POST `/questions/{id}/resolve` | S17–S19 | R |
| POST `/questions/{id}/answers` · PATCH/DELETE `/answers/{id}` | I21, S19 | R |
| GET `/instructor/questions?courseId=&status=unanswered` | I22 | R |
| GET `/instructor/courses/{courseId}/students` | I19 | R |
| POST `/admin/questions/{id}/hide` · `/restore` · `/admin/answers/{id}/hide` · `/restore` | A19 | R |
| GET `/me/certificates` | S20 | R |
| GET `/me/certificates/{code}/pdf` | S20 | **S** (static sample PDF) |
