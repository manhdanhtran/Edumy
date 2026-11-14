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
| `AUTH_ACCOUNT_LOCKED` | 403 | Q1: correct password on a locked account (a wrong password stays `AUTH_INVALID_CREDENTIALS`) |
| `EMAIL_NOT_VERIFIED` | 403 | D-02; UC-G13 E1 |
| `INSTRUCTOR_ROLE_REVOKED` | 403 | UC-G13 E2, D-04: the instructor role was revoked by an admin; only an admin can grant it again (UC-A04) |
| `ENROLLMENT_REQUIRED` | 403 | |
| `ENROLLMENT_COURSE_NOT_FREE` | 422 | BR-09 / UC-S15: the course has a price — add it to the cart instead (P06-G6; the SRS names no text) |
| `ENROLLMENT_OWN_COURSE` | 422 | BR-32 / UC-S15 E2 "đã có quyền xem với tư cách giảng viên": main instructor (any role state) or accepted co-instructor (P06-G6) |
| `QA_REPLY_NOT_ALLOWED` | 403 | BR-38 |
| `RESOURCE_NOT_FOUND` | 404 | "does not exist" **and** "not yours": identical body (NFR-01); only `traceId`/`instance` differ |
| `DATA_CONFLICT` | 409 | unmapped UNIQUE violation (1062) or FK 1451 (parent still referenced); see the translator rules below |
| `EMAIL_ALREADY_REGISTERED` | 409 | UC-G05 E1; pre-check + `uq_users_email` under concurrency |
| `COURSE_NOT_EDITABLE` | 409 | BR-33 |
| `CART_COURSE_ALREADY_OWNED` | 409 | BR-10 |
| `CART_ITEM_DUPLICATE` | 409 | BR-11; `uq_cart_items_cart_course` |
| `COURSE_DELETE_FORBIDDEN` | 409 | BR-19; trigger: the course was published once |
| `REVIEW_HIDDEN_BY_ADMIN` | 409 | BR-35 |
| `ADMIN_LAST_ACTIVE` | 409 | BR-36 "at least one active admin"; reserved — unreachable through UC-A02, because no admin can ever be locked (`ADMIN_LOCK_FORBIDDEN`) and admin roles are never granted/revoked through the API (D-01) |
| `ADMIN_LOCK_FORBIDDEN` | 403 | BR-36 / AC-37: UC-A02 target is the caller or holds an active admin role |
| `ACCOUNT_ALREADY_LOCKED` | 409 | UC-A02 precondition "tài khoản đích đang hoạt động": target already locked |
| `ACCOUNT_NOT_LOCKED` | 409 | UC-A03 precondition "tài khoản bị khóa": target is active |
| `INSTRUCTOR_ROLE_ALREADY_ACTIVE` | 409 | UC-A04 E1: target already holds an active instructor role |
| `INSTRUCTOR_ROLE_NOT_ACTIVE` | 409 | UC-A05 E1: target holds no active instructor role (none, or already revoked) |
| `ACCOUNT_TOKEN_INVALID` | 422 | UC-G06 / UC-G08 E1: emailed code unknown, wrong type, used or expired — never says which (BR-34); also reset of a locked account |
| `PASSWORD_INCORRECT` | 422 | UC-G12: wrong current password |
| `PASSWORD_SAME_AS_OLD` | 422 | UC-G12: new password = current password |
| `COURSE_SUBMIT_CHECKLIST_FAILED` | 422 | BR-07; `errors` lists every failed checklist item (`field` = item, `message` = Vietnamese reason) |
| `CART_OWN_COURSE` | 422 | BR-32 |
| `CART_COURSE_FREE` | 422 | BR-09 / UC-S01 E3: the course is free (price 0) — enroll with UC-S15 instead (P06-G5; the SRS names no error text, only "chuyển sang ghi danh") |
| `COURSE_CO_INSTRUCTOR_IS_MAIN` | 422 | BR-02; trigger: the invited instructor is the course's main instructor |
| `LESSON_COURSE_MISMATCH` | 422 | BR-24 / BR-37; triggers: the lesson does not belong to the enrollment's course |
| `COURSE_CATEGORY_UNAVAILABLE` | 422 | UC-I01 E "danh mục ngừng sử dụng" / UC-I03: the chosen category does not exist or is inactive (keeping a course's current, since deactivated category is allowed) |
| `CATEGORY_ALREADY_EXISTS` | 409 | UC-A06 / UC-A07 E "trùng tên hoặc slug": pre-check (`errors[].field` = `name` / `slug`; name compared accent/case-insensitively like the ai_ci UNIQUE) + `uq_categories_name` / `uq_categories_slug` |
| `COURSE_CO_INSTRUCTOR_NOT_ELIGIBLE` | 422 | UC-I13 E1 "Tài khoản chưa đăng ký làm giảng viên": no account with that email **or** no active instructor role — one code and body for both (no email probing) |
| `COURSE_CO_INSTRUCTOR_ALREADY_INVITED` | 409 | UC-I13 E2: the invitee's invitation is pending or accepted; `uq_cci_course_instructor` under concurrency |
| `INVITATION_NOT_PENDING` | 409 | UC-I14 / UC-I15 E "lời mời đã bị thu hồi": the caller's own invitation is no longer pending (removed, accepted, declined) |
| `COURSE_MAIN_INSTRUCTOR_LEAVE_FORBIDDEN` | 403 | UC-I20 / RBAC §3.3 / BR-02: the main instructor cannot leave the course |
| `COURSE_INVALID_TRANSITION` | 409 | docs/ai/02 §2.2: the course is not in the starting status of the requested transition — UC-I17 submit outside draft/rejected; UC-A10/A11 E1 "không còn chờ duyệt (Admin khác đã xử lý): không ghi đè"; UC-A12 hide outside published; UC-A16 unhide outside hidden |
| `COURSE_LAST_LESSON_DELETE_FORBIDDEN` | 409 | D-14 / UC-I07 E "khóa đã công khai sẽ không còn bài" / UC-I11 E "khóa đã công khai chỉ còn một bài": deleting the only lesson, or a chapter holding every lesson, of a published or hidden course |
| `AUTH_LOGIN_TEMPORARILY_BLOCKED` | 429 | NFR-03: 5 consecutive failed logins for the email → every login for it blocked 15 min (correct password too); header `Retry-After` (docs/ai/04 §4.4) |
| `TOKEN_RESEND_TOO_SOON` | 429 | D-03: resend of the verification code within 60 s of the last one (forgot password never shows it, Q3) |
| `RATE_LIMITED` | 429 | NFR-03: certificate verification over 30 req/min per IP; header `Retry-After` (docs/ai/04 §4.4) |
| `INTERNAL_ERROR` | 500 | generic Vietnamese detail, never the exception message; also unmapped CHECK / trigger violations |
- `ConstraintViolationTranslator`: walks the cause chain down to the root `java.sql.SQLException` and decides by
  **vendor error code**, never by the Spring/Hibernate exception type (Spring JDBC 7.0 does not map CHECK error 3819 to
  `DataIntegrityViolationException`; JPA and `JdbcClient` wrap errors differently). It must work for both paths and
  must not override Spring's `sql-error-codes.xml`.

  | Vendor code | Meaning | Lookup key in the message |
  |---|---|---|
  | 1062 | UNIQUE | constraint name (`uq_cart_items_cart_course`); MySQL 8.4 writes `for key 'cart_items.uq_…'`, take the part after the last `.` |
  | 1451 / 1452 | FK (RESTRICT delete / missing parent) | constraint name (`fk_user_roles_role_id`) |
  | 3819 | CHECK (`Check constraint 'ck_…' is violated.`) | constraint name (`ck_users_status`) |
  | 1644 / SQLState `45000` | trigger `SIGNAL` | message starts with the BR code (`BR-02:` …) |

  **Mapped keys** (production map in `ConstraintViolationTranslator`):

  | Key | ErrorCode | HTTP |
  |---|---|---|
  | `BR-02` | `COURSE_CO_INSTRUCTOR_IS_MAIN` | 422 |
  | `BR-19` | `COURSE_DELETE_FORBIDDEN` | 409 |
  | `BR-24`, `BR-37` | `LESSON_COURSE_MISMATCH` | 422 |
  | `uq_cart_items_cart_course` | `CART_ITEM_DUPLICATE` | 409 |
  | `uq_users_email` | `EMAIL_ALREADY_REGISTERED` | 409 |
  | `uq_categories_name`, `uq_categories_slug` | `CATEGORY_ALREADY_EXISTS` | 409 |
  | `uq_cci_course_instructor` | `COURSE_CO_INSTRUCTOR_ALREADY_INVITED` | 409 |
  | `ck_lessons_type` + `ck_lessons_content_match`; `ck_orders_status` + `ck_orders_state`; `ck_enrollments_source` + `ck_enrollments_source_order` | `INTERNAL_ERROR` | 500 |

  An unknown value in these columns breaks two overlapping CHECKs and MySQL reports only one of them, so **both names of
  a pair are listed** and always give the same result.

  **Defaults when the key is not mapped** (replaces the old rule "unknown → `DATA_CONFLICT`"):

  | Violation | ErrorCode | HTTP | Log |
  |---|---|---|---|
  | 1062 UNIQUE | `DATA_CONFLICT` | 409 | WARN, full cause |
  | 1451 FK, parent still referenced | `DATA_CONFLICT` | 409 | WARN |
  | 1452 FK, missing parent | `RESOURCE_NOT_FOUND` | 404, body identical to every other 404 (NFR-01) | WARN |
  | 3819 CHECK | `INTERNAL_ERROR` | 500 | ERROR, full cause |
  | 1644 / SQLState `45000` trigger | `INTERNAL_ERROR` | 500 | ERROR, full cause |

  A CHECK or trigger violation reaching the database means the application skipped a validation, so it is a server
  error. The constraint name, BR code and MySQL message are only logged, **never** put in the response.
- ErrorCode naming: `UPPER_SNAKE`, module-meaningful, e.g. `AUTH_INVALID_CREDENTIALS`, `AUTH_LOGIN_TEMPORARILY_BLOCKED`,
  `EMAIL_NOT_VERIFIED`, `COURSE_NOT_EDITABLE`, `COURSE_SUBMIT_CHECKLIST_FAILED`, `CART_COURSE_ALREADY_OWNED`,
  `CART_OWN_COURSE`, `CART_ITEM_DUPLICATE`, `COURSE_CO_INSTRUCTOR_IS_MAIN`, `COURSE_DELETE_FORBIDDEN`,
  `LESSON_COURSE_MISMATCH`, `ENROLLMENT_REQUIRED`, `REVIEW_HIDDEN_BY_ADMIN`, `QA_REPLY_NOT_ALLOWED`, `ADMIN_LAST_ACTIVE`.
  Messages (Vietnamese) live in `messages.properties` / `messages_vi.properties`, keyed by code.
- Other framework 4xx (405, 415, 406…) keep their original status and share the code `MALFORMED_REQUEST` (decision).

### Validation messages (decision; file created in P04-c with the keys used so far, extended as DTOs need them)
Bean Validation texts are separate from the error-code texts above (they come from Hibernate Validator, not Spring's `MessageSource`):
- File: `src/main/resources/ValidationMessages.properties` — **no `_vi` suffix** (it is the default bundle, so it applies
  whatever the request locale is), saved as UTF-8.
- Override the default keys `jakarta.validation.constraints.*.message` (`NotBlank`, `NotNull`, `Size`, `Min`, `Max`,
  `Email`, `Pattern`…) with Vietnamese text, so DTOs need no per-field message in the common case.
- Put an explicit `message` on an annotation **only** when the text is specific (e.g. `@Size(max = 100, message = "Họ tên tối đa 100 ký tự")`,
  price rule BR-07). Prefer a key in `ValidationMessages.properties` (`message = "{course.price.invalid}"`) over inline text.
- These texts become the `message` of each entry in the ProblemDetail `errors` array (`VALIDATION_FAILED`).
- Password policy (NFR-02, docs/ai/04 §4.2) on every **new** password (register, reset, change): `@StrongPassword`
  (`shared.validation`) — 8–72 characters, at least one letter and one digit, and at most 72 UTF-8 bytes (BCrypt
  limit; Vietnamese letters take 2–3 bytes). Keys `edumy.password.policy`, `edumy.password.too-many-bytes`. Login and
  "current password" fields get no policy check.

## 5.3 Caching (Spring Cache + Caffeine)
| Cache | Key | TTL | Evicted by |
|---|---|---|---|
| `catalog.categories` | `'active'` | 1 h | `CategoryChanged` (category create/update/deactivate/delete) |
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

Identity response codes (P04-c):
- `POST /auth/register` → **201** + `AuthTokenResponse` + `edumy_rt` cookie (UC-G05 step 7 logs the user in).
- `POST /auth/email-verification/confirm` `{token}` (public, no session needed) → **204**; any bad code → 422
  `ACCOUNT_TOKEN_INVALID`.
- `POST /auth/email-verification/resend` (Bearer, no body) → **202** new code mailed · **204** email already verified,
  nothing sent · 429 `TOKEN_RESEND_TOO_SOON` within 60 s.
- `POST /auth/password/forgot` `{email}` → always **202** `{"message":"Nếu email tồn tại, hướng dẫn đã được gửi"}`
  (unknown email, locked account, within the 60 s cooldown and the normal case look the same, D-03/Q3).
- `POST /auth/password/reset` `{token,newPassword}` → **204**, every session ended, no login, no cookie.
- `GET /me`, `PATCH /me` `{fullName}` → **200** `MeResponse {id,email,fullName,emailVerified,roles,createdAt}`;
  `roles` = lower-case codes active **in the database** (the JWT may be up to 15 min older).
- `PUT /me/password` `{currentPassword,newPassword}` → **204**; other sessions end, the current one keeps working.
- `POST /me/instructor-registration` (no body, Q6) → **201** role granted · **200** already active (E3) · 403
  `EMAIL_NOT_VERIFIED` (E1) · 403 `INSTRUCTOR_ROLE_REVOKED` (E2). Body = `MeResponse`; the SPA then calls
  `/auth/refresh` to get an access token with the new role.
| GET `/admin/users?q=&status=&role=` | A01 | R |
| POST `/admin/users/{id}/lock` · POST `/admin/users/{id}/unlock` | A02, A03 | R |
| PUT `/admin/users/{id}/roles/instructor` · DELETE `/admin/users/{id}/roles/instructor` | A04, A05 | R |

Admin user list (P05):
- `GET /admin/users?q=&status=&role=&page=&size=` → **200** `PagedModel<AdminUserResponse {id,email,fullName,emailVerified,status,roles,createdAt}>`;
  `status` = `active`/`locked`, `roles` = lower-case codes **active** in the database (revoked ones not listed).
  `q` (trimmed) = substring of the email (case-insensitive) or of the full name (case- and accent-insensitive, ai_ci);
  `role` keeps users holding that role actively. Blank parameter = no filter; an unknown `status`/`role` → 400
  `VALIDATION_FAILED` (`errors[].field` = `status`/`role`). Order fixed: newest account first (`createdAt` desc, `id`
  desc); `sort` is ignored. `size` default 20, values above 100 are clamped to 100 (every paged endpoint, §5.1).

Admin user writes (P06-G0, UC-A02…A05). No request body. Every answer **200** `AdminUserResponse` of the changed
account (same shape as a UC-A01 row; `roles` read from the database after the change). Common errors: 401 · 403
`ACCESS_DENIED` (not an admin — JWT claim at the route, **and** active admin role re-checked in the database, BR-26) ·
404 `RESOURCE_NOT_FOUND` (unknown user id). The target `users` row is locked (`SELECT … FOR UPDATE`) for the whole
transaction. Preconditions that do not hold are refused with 409 and change nothing (no silent no-op). No mail (SRS §7).
- `POST /admin/users/{id}/lock` (UC-A02) → `status: locked`; every session of the user ends **at once** (D-05): all
  refresh-token families revoked `account_locked`, `identity.session-status` + `identity.user-status` evicted after
  commit → the user's live access tokens get 401 on their next request, refresh gets 401, login gets 403
  `AUTH_ACCOUNT_LOCKED`. Errors: 403 `ADMIN_LOCK_FORBIDDEN` (self or an admin, BR-36) · 409 `ACCOUNT_ALREADY_LOCKED`.
- `POST /admin/users/{id}/unlock` (UC-A03) → `status: active`; `identity.user-status` evicted; the ended sessions stay
  ended (the user logs in again). Error: 409 `ACCOUNT_NOT_LOCKED`.
- `PUT /admin/users/{id}/roles/instructor` (UC-A04) → instructor role active: a new `user_roles` row or the revoked row
  reactivated (never a duplicate), `assigned_by` = the admin, `assigned_at` = now; `identity.active-roles` evicted.
  No email-verified / account-status precondition (none in the SRS). Error: 409 `INSTRUCTOR_ROLE_ALREADY_ACTIVE` (E1).
- `DELETE /admin/users/{id}/roles/instructor` (UC-A05) → row set `is_active = false` (kept, BR-28);
  `identity.active-roles` evicted → instructor actions refused (403) on the next request (D-07); sessions, published
  courses and enrollments untouched. Error: 409 `INSTRUCTOR_ROLE_NOT_ACTIVE` (E1). The SRS step 1 "impact preview"
  (number of main / co-taught / published courses) has no endpoint in this contract.

### Front-end routes used in emails (contract for the SPA team, Q5)
The identity mails link to two SPA routes, the co-instructor invitation mail to a third, the review-result mails
(P06-G4) to two more. Base = `edumy.frontend.base-url` (env `EDUMY_FRONTEND_BASE_URL`, default
`http://localhost:5173`; a trailing `/` is ignored). `token` is the one-time code, URL-encoded (base64url, 43 chars).

| Route (in the mail) | Mail | What the SPA does |
|---|---|---|
| `{base}/verify-email?token={code}` | "Xác thực địa chỉ email" (UC-G05 register, UC-G06 resend), valid 24 h | calls `POST /api/v1/auth/email-verification/confirm` `{"token": "<code>"}` — public, works without a session (link may be opened on another device); 204 → "email verified", 422 `ACCOUNT_TOKEN_INVALID` → "link no longer valid", offer resend after login |
| `{base}/reset-password?token={code}` | "Đặt lại mật khẩu" (UC-G08), valid 30 min | shows the new-password form (entered twice, NFR-02 policy), then calls `POST /api/v1/auth/password/reset` `{"token": "<code>", "newPassword": "..."}`; 204 → go to login (no auto-login), 422 `ACCOUNT_TOKEN_INVALID` → offer "forgot password" again |

| `{base}/courses/{slug}` | "Khóa học của bạn đã được phê duyệt trên Edumy" (UC-A10, P06-G4) | public course page (UC-G04): `GET /api/v1/catalog/courses/{slug}` |
| `{base}/instructor/courses/{courseId}` | "Khóa học của bạn chưa được phê duyệt trên Edumy" (UC-A11, with the reason) · "Khóa học của bạn đã bị ẩn trên Edumy" (UC-A12, with the reason) (P06-G4) | after login, the instructor's course page: `GET /api/v1/instructor/courses/{id}` (+ `…/review-status`); after a rejection the instructor edits and calls `POST /api/v1/instructor/courses/{id}/submit` |
| `{base}/instructor/invitations` | "Lời mời làm đồng giảng viên khóa học trên Edumy" (UC-I13, P06-G3); no token | after login, lists `GET /api/v1/instructor/invitations` and answers with `POST /api/v1/instructor/invitations/{id}/accept` / `…/decline` (204; 409 `INVITATION_NOT_PENDING` → "lời mời đã bị thu hồi"; 403 without an active instructor role) |

The "password changed" notice (UC-G08 step 6, UC-G12) has no link. Mail sender = `edumy.mail.from`
(env `EDUMY_MAIL_FROM`). Recommended: the SPA does not log or forward the token (analytics, Referer: e.g.
`<meta name="referrer" content="no-referrer">` on these two pages, NFR-08).

### catalog (public, read model)
| Method & path | UC | P1 |
|---|---|---|
| GET `/catalog/categories` | G03 | R |
| GET `/catalog/courses?q=&categoryId=&price=free\|paid&sort=newest\|rating\|price_asc\|price_desc` | G01–G03 | R |
| GET `/catalog/courses/{slug}` (outline, instructors, avg rating, viewer context: owned / in cart / is instructor, D-12) | G04 | R |
| GET `/catalog/courses/{slug}/reviews` (visible only) | G04 | R |
| GET `/catalog/courses/{slug}/lessons/{lessonId}/preview` | G11 | R |
| GET `/catalog/certificates/{code}` (rate-limited) | G14 | R |

Catalog responses (P05):
- All public. No token = anonymous. A valid bearer token is read (viewer context); an invalid/expired token or a locked
  account's token → 401 `UNAUTHENTICATED` as on every other path (unchanged resource-server behaviour; the SPA
  refreshes, or retries without the header).
- `GET /catalog/categories` → **200** `[CategoryResponse {id,name,slug}]`: active categories only, by name (not paged).
- `GET /catalog/courses?q=&categoryId=&price=&sort=&page=&size=` → **200** `CourseSearchResponse` =
  `{ "content": [CourseCardResponse], "page": {size,number,totalElements,totalPages}, "suggestions": [CourseCardResponse] }`
  — the PagedModel shape plus `suggestions`. Card = `{id,slug,title,thumbnailUrl,price,isFree,categoryId,categoryName,
  instructorName,averageRating,ratingCount,publishedAt}`; `averageRating` = mean of the **visible** reviews rounded to
  one decimal (`null` when none), `ratingCount` = number of visible reviews. Published courses only (D-08). `q`
  (trimmed) = part of the title, matched on `title_normalized` after `VietnameseNormalizer` (case/accent/đ-insensitive;
  `%`, `_` matched literally). `price` = `free` (price 0) / `paid`; `categoryId` = a category id (no match → empty).
  `sort` = `newest` (default: `publishedAt` desc) · `rating` (average desc, courses without rating last, then count desc)
  · `price_asc` · `price_desc`; every order ends with `publishedAt` desc, `id` desc. `size` default 20, max 100. Blank
  parameter = no filter / default; an unknown `price`/`sort` (case-sensitive), `categoryId` ≤ 0 or not a number → 400
  `VALIDATION_FAILED` (`errors[].field`). **UC-G02 "no result → newest courses"**: when `q` is given and nothing matches
  at all (`totalElements = 0`), `content` is empty and `suggestions` = the 5 newest published courses; otherwise
  `suggestions` is `[]` (also for an empty filter-only list or a page past the end).
- `GET /catalog/courses/{slug}` → **200** `CourseDetailResponse {id,slug,title,description,thumbnailUrl,price,isFree,
  status,categoryId,categoryName,mainInstructor{id,fullName},coInstructors[{id,fullName}],averageRating,ratingCount,
  totalLessons,totalDurationSeconds,publishedAt,updatedAt,chapters[{id,title,lessons[{id,title,contentType,
  durationSeconds,isPreview}]}],viewer}`. Outline never carries lesson content. `coInstructors` = accepted invitations
  of users still holding an active instructor role ("giảng viên của khóa", SRS §1.4). `viewer` = `null` when
  anonymous, else `{owned (has an enrollment), inCart (in the caller's cart), isInstructor (main or accepted co + active
  instructor role)}` (D-12). Visibility (UC-G04 precondition, D-08, BR-31): `published` → everyone; `hidden` → only an
  enrolled student, a course instructor or the admin (`status: "hidden"`, the SPA offers no purchase); everyone else,
  unknown slug and draft / pending_review / rejected courses → 404 `RESOURCE_NOT_FOUND` (identical body).
- `GET /catalog/courses/{slug}/reviews?page=&size=` → **200** `PagedModel<ReviewItemResponse {id,rating,comment,
  reviewerName,createdAt,updatedAt}>`: visible reviews only, newest first (`sort` ignored); same visibility as the page.
- `GET /catalog/courses/{slug}/lessons/{lessonId}/preview` → **200** `LessonPreviewResponse {id,courseSlug,courseTitle,
  chapterTitle,title,contentType,videoUrl,articleContent,durationSeconds}` only for a preview lesson of that
  **published** course; anything else → 404 (identical body).
- `GET /catalog/certificates/{code}` → **200** `CertificateVerificationResponse {certificateCode,studentName,courseTitle,
  instructorName,issuedAt}` (snapshot, BR-40); unknown code → 404; over 30 req/min per IP → 429 `RATE_LIMITED`.
- SQL cost (ADR-003, JdbcClient): list = 1 statement (cards + total via `COUNT(*) OVER ()`; +1 suggestions on an empty
  search, +1 count for an empty page past the end) · page = 3 (+1 viewer context) · reviews = 2 (+1 viewer for a hidden
  course) · categories = 1.

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

Authoring reads (P05):
- Instructor endpoints need an **active** instructor role in the database (BR-26, D-07): otherwise 403 `ACCESS_DENIED`
  (a revoked instructor's JWT still claiming the role does not help). `{id}` endpoints: a course the caller is not a
  course instructor of (main, or co with an **accepted** invitation — pending/declined/removed do not count) → 404
  `RESOURCE_NOT_FOUND`, identical to an unknown id (NFR-01). Admin endpoints: `/admin/**` route rule + `hasRole('ADMIN')`.
- `GET /instructor/courses?page=&size=` → **200** `PagedModel<InstructorCourseSummaryResponse {id,title,slug,
  thumbnailUrl,price,isFree,status,relation,categoryId,categoryName,chapterCount,lessonCount,reviewNote,submittedAt,
  publishedAt,createdAt,updatedAt}>`: every status; `relation` = `main` / `co`; newest course first (`createdAt` desc,
  `id` desc, `sort` ignored); no filter (UC-I02 describes none). `reviewNote` = latest rejection/hiding reason (UC-I18),
  `null` when none. 3 statements per page (page, count, outline counts).
- `GET /instructor/courses/{id}` → **200** `InstructorCourseDetailResponse {id,title,slug,description,thumbnailUrl,
  price,isFree,status,relation,categoryId,categoryName,categoryActive,mainInstructor{id,fullName},
  coInstructors[{id,fullName,invitationStatus,invitedAt,respondedAt}],reviewNote,submittedAt,reviewedAt,publishedAt,
  createdAt,updatedAt,chapters[ChapterResponse {id,title,sortOrder,lessons[LessonResponse {id,title,contentType,videoUrl,
  articleContent,durationSeconds,isPreview,sortOrder}]}]}` — lessons **with** content; `coInstructors` = every
  invitation of the course (all four statuses, oldest first), shown to the main and to accepted co-instructors alike;
  readable while `pending_review` (BR-33 forbids editing only). 6 statements (access check included).
- `GET /instructor/courses/{id}/review-status` → **200** `ReviewStatusResponse {courseId,status,submittedAt,reviewedAt,
  reviewNote}` (UC-I18: latest submission, latest admin decision, latest rejection/hiding reason).
- `GET /instructor/invitations` → **200** `[InvitationResponse {id,courseId,courseTitle,courseSlug,mainInstructor{id,
  fullName},invitedAt}]`: the caller's **pending** invitations only (UC-I14 precondition), newest first, not paged.
- `GET /admin/categories` → **200** `[AdminCategoryResponse {id,name,slug,active,courseCount,createdAt,updatedAt}]`:
  active and inactive, by name (not paged); `courseCount` = courses of any status using it (D-21). 1 statement.
- `GET /admin/courses/pending?page=&size=` → **200** `PagedModel<PendingCourseResponse {id,title,slug,price,isFree,
  categoryId,categoryName,mainInstructor{id,fullName},submittedAt}>`: `pending_review` only, oldest submission first
  (`sort` ignored). 3 statements per full page. UC-A10 step 1 (admin reads the whole content before deciding) uses
  `GET /learn/courses/{courseId}` + `GET /learn/lessons/{lessonId}` (D-32: admin views content without enrollment, any
  course status) — no authoring endpoint for it.

Authoring writes (P06-G1, UC-A06…A08, UC-I01, I03, I04). Bodies are JSON; PATCH = only the fields sent change
(absent or `null` = unchanged).
- `POST /admin/categories` `CategoryRequest {name, slug?, active?}` → **201** + `Location` + `AdminCategoryResponse`.
  `name` required (trimmed, ≤100); `slug` optional (≤120, `^[a-z0-9]+(-[a-z0-9]+)*$`), generated from the name by
  `shared.text.SlugGenerator` when absent (a name without any letter/digit and no slug → 400 on `slug`); `active`
  defaults to `true`. 409 `CATEGORY_ALREADY_EXISTS` (name or slug taken, `errors[].field` says which).
- `PATCH /admin/categories/{id}` `CategoryRequest` → **200** `AdminCategoryResponse`. A rename keeps the slug (it
  changes only when `slug` is sent); `active:false` / `true` deactivates / reactivates (same value = no-op). 409
  `CATEGORY_ALREADY_EXISTS` · 404 unknown id.
- `DELETE /admin/categories/{id}` (D-21) → **204** deleted (no course of any status uses it) · **200**
  `AdminCategoryResponse` with `active:false` when courses use it (only deactivated, they keep it) · 409
  `DATA_CONFLICT` used and already inactive · 404 unknown id.
- Every category change publishes `CategoryChanged(categoryId)` (catalog cache, P08). Admin only (route rule +
  `hasRole('ADMIN')`): 401 without token, 403 `ACCESS_DENIED` for other roles.
- `POST /instructor/courses` `CreateCourseRequest {title, description?, thumbnailUrl?, categoryId, price}` → **201** +
  `Location` + `InstructorCourseDetailResponse` (status `draft`, caller = main instructor). title ≤200 (trimmed,
  `title_normalized` computed), description ≤16 383 chars (TEXT column), thumbnailUrl ≤500 `http(s)://…`, price BR-07
  (`edumy.course.price.invalid`, 400). Slug = `SlugGenerator(title)` (≤200 chars, `khoa-hoc` when the title has no
  letter/digit); taken → lowest free numeric suffix `-2`, `-3`… (UC-I01 "slug trùng → tự thêm hậu tố"); a concurrent
  create of the same slug → 409 `DATA_CONFLICT` (`uq_courses_slug`). 422 `COURSE_CATEGORY_UNAVAILABLE` (unknown or
  inactive category) · 403 without an active instructor role in the DB (BR-26).
- `PATCH /instructor/courses/{id}` `UpdateCourseRequest {title?, description?, thumbnailUrl?, categoryId?, price?}` →
  **200** detail. `@courseAccess.isMain` (BR-06: co-instructor / stranger → 404, revoked role → 403); 409
  `COURSE_NOT_EDITABLE` while `pending_review` (BR-33, also for `{}`); `""` clears description / thumbnail; the slug
  never changes (SRS UC-I03 lists name, description, image, category, price); a **new** category must be active (422),
  the current one may stay even if deactivated since. Published / hidden courses change at once without review (D-14,
  the new price applies to later orders) and publish `CourseContentChanged(courseId, slug)`; a rejected course stays
  rejected.
- `DELETE /instructor/courses/{id}` → **204** (chapters, lessons, invitations by ON DELETE CASCADE). Main instructor
  only. 409 `COURSE_DELETE_FORBIDDEN` when `published_at` is set whatever the status now (BR-19; the V5 trigger maps to
  the same code) · 409 `COURSE_NOT_EDITABLE` while `pending_review`.

Authoring writes (P06-G2, UC-I05…I12: chapters and lessons). Main instructor **or accepted co-instructor** (BR-04)
via `@courseAccess` / `@chapterAccess` / `@lessonAccess.canEditContent`: no active instructor role in the DB → 403
`ACCESS_DENIED`; course/chapter/lesson unknown or not taught by the caller (pending/declined/removed invitations do not
count) → 404 identical body; `pending_review` → 409 `COURSE_NOT_EDITABLE` (BR-33, re-checked under the course row
lock). Every write locks the course row (`SELECT … FOR UPDATE`): it serializes all content writes of a course. New
chapters/lessons go at the end (max + 1); positions always stay 1..n (a delete moves the following siblings up, UC-I08
"liên tục từ 1"). Published / hidden course: changes take effect at once (D-14) and publish
`CourseContentChanged(courseId, slug)`; draft / rejected: no event.
- `POST /instructor/courses/{id}/chapters` `CreateChapterRequest {title}` (≤200, trimmed) → **201** + `Location:
  /api/v1/instructor/chapters/{chapterId}` + `ChapterResponse` (lessons `[]`).
- `PATCH /instructor/chapters/{id}` `UpdateChapterRequest {title}` (required, the only editable field) → **200**
  `ChapterResponse` with its lessons.
- `DELETE /instructor/chapters/{id}` → **204**; its lessons, their `lesson_progresses` (CASCADE) go, questions about them
  become course-level (`lesson_id` SET NULL); progress % is derived (D-33). 409 `COURSE_LAST_LESSON_DELETE_FORBIDDEN`
  when the chapter holds every lesson of a published / hidden course (an empty chapter may always go).
- `PUT /instructor/courses/{id}/chapters/order` `ReorderRequest {ids:[…]}` → **200** `[ChapterResponse]` (whole outline,
  new order). `ids` must list every chapter of the course exactly once; missing / foreign (other course or unknown — same
  text) / duplicate id, empty or absent list → 400 `VALIDATION_FAILED`, `errors[].field = "ids"` (status principle:
  the request does not describe a permutation, so it is invalid input; no new code). Two-phase update (docs/ai/03 §3.4).
- `POST /instructor/chapters/{id}/lessons` `CreateLessonRequest {title, contentType, videoUrl?, articleContent?,
  durationSeconds?, isPreview?}` → **201** + `Location: /api/v1/instructor/lessons/{lessonId}` + `LessonResponse`.
  title ≤200; `contentType` `video` | `article` (exact lower case, `edumy.lesson.content-type.invalid`); videoUrl ≤500
  `http(s)://…` (`edumy.lesson.video-url.invalid`); articleContent ≤16 383 chars; durationSeconds ≥0; isPreview default
  `false`. Content of the other type (video + articleContent, article + videoUrl) → 400 on that field (D-22, never the
  500 of `ck_lessons_content_match`). Content may be empty while authoring (blank = `null`); BR-07 checks it at submit.
- `PATCH /instructor/lessons/{id}` `UpdateLessonRequest` (same fields, all optional; absent/`null` = unchanged, `""`
  clears videoUrl / articleContent; a blank title → 400) → **200** `LessonResponse`. Changing `contentType` drops the
  old type's content unless it is sent (then 400). `durationSeconds` cannot be reset to `null` (send `0`).
- `DELETE /instructor/lessons/{id}` → **204**; its progress goes, its questions become course-level; 409
  `COURSE_LAST_LESSON_DELETE_FORBIDDEN` for the only lesson of a published / hidden course.
- `PUT /instructor/chapters/{id}/lessons/order` `ReorderRequest {ids}` → **200** `ChapterResponse` (lessons in the new
  order); same 400 rules (a lesson of another chapter of the same course is foreign).

Authoring writes (P06-G3, UC-I13…I16, I20: co-instructors). Every caller needs an active instructor role in the DB
(BR-26 → 403 `ACCESS_DENIED`). No course status blocks these operations, `pending_review` included (UC-I13 names no
course state; BR-05 / BR-21: invitations and their answers are independent of authoring and review; BR-33 locks the
content). Every write locks the course row FOR UPDATE, then reads the invitation row. Transitions docs/ai/02 §2.2.
- `POST /instructor/courses/{id}/co-instructors` `InviteCoInstructorRequest {email}` (required, email format, ≤255,
  trimmed, case-insensitive) → **201** + `Location: /api/v1/instructor/courses/{id}/co-instructors/{userId}` + the
  co-instructor item of the course detail `{id (= invited user id), fullName, invitationStatus: "pending", invitedAt,
  respondedAt: null}`. Main instructor only (`@courseAccess.isMain`: co-instructor / stranger → 404, AC-05). A declined
  or removed invitee gets the **same** row back to `pending` (D-18; inviter + `invitedAt` renewed, `respondedAt`
  cleared), also 201. 422 `COURSE_CO_INSTRUCTOR_NOT_ELIGIBLE` (unknown email or no active instructor role, identical
  bodies) · 422 `COURSE_CO_INSTRUCTOR_IS_MAIN` (own email, BR-02; the V5 triggers map to the same code) · 409
  `COURSE_CO_INSTRUCTOR_ALREADY_INVITED` (pending / accepted) · 400 `VALIDATION_FAILED`. Publishes
  `CoInstructorInvited(invitationId, courseId, instructorId, invitedBy)` → invitation mail after commit (a mail failure
  keeps the invitation, UC-I13 E4; no resend endpoint — the main instructor can remove and invite again).
- `POST /instructor/invitations/{id}/accept` · `…/decline` (`{id}` = invitation id of `GET /instructor/invitations`) →
  **204**. Only the invitee (`@courseAccess.isActiveInstructor()` + scoped by `instructor_id` = caller): someone else's
  invitation → 404 identical to an unknown id (the inviting main instructor too); not pending any more → 409
  `INVITATION_NOT_PENDING`. Accept: co-editing from the next request (BR-04); decline: no rights, review unaffected.
- `DELETE /instructor/courses/{id}/co-instructors/{userId}` → **204** (`pending` or `accepted` → `removed`; the user's
  next request on the course is refused, AC-17). Main only. No pending/accepted invitation of `userId` (none, declined,
  removed, the main instructor itself) → 404.
- `POST /instructor/courses/{id}/leave` → **204** (`accepted` → `removed`, D-19). `@courseAccess.isInstructor`: pending /
  declined / removed invitees and strangers → 404; the main instructor → 403 `COURSE_MAIN_INSTRUCTOR_LEAVE_FORBIDDEN`.
- Accept, removal of an accepted co-instructor and leave on a **published / hidden** course publish
  `CourseContentChanged(courseId, slug)` (the catalog page lists accepted co-instructors; eviction P08).

Authoring writes (P06-G4, UC-I17 submit, UC-A10…A12, A16 moderation; docs/ai/02 §2.2). Every write locks the course
row FOR UPDATE, then checks the status; a status the transition does not start from → 409 `COURSE_INVALID_TRANSITION`,
nothing changes. Every answer is **200** `ReviewStatusResponse {courseId,status,submittedAt,reviewedAt,reviewNote}`
(the UC-I18 view, read after the change). `reviewNote` = the latest rejection / hiding reason: set by reject / hide,
kept by submit / approve / unhide (DDD "lý do từ chối hoặc ẩn gần nhất").
- `POST /instructor/courses/{id}/submit` (no body) → `pending_review`, `submittedAt` = now; read-only from then on
  (BR-33). Main instructor only (`@courseAccess.isMain`: co-instructor / stranger → 404, no active instructor role →
  403). Status draft or rejected, else 409 (checked **before** the checklist). BR-07 checklist, every item checked, all
  failures in **one** 422 `COURSE_SUBMIT_CHECKLIST_FAILED`; `errors[].field` = stable item key, in this order (SPA
  contract), `message` = Vietnamese reason:

  | `field` | Item (SRS UC-I17 step 1 / BR-07) |
  |---|---|
  | `title` | the course has a (non-blank) title |
  | `description` | the course has a description |
  | `categoryId` | its category is active (a category deactivated since creation fails) |
  | `price` | 0 or ≥ 10 000 and a multiple of 1 000 (always true while `ck_courses_price` holds; kept for completeness) |
  | `chapters` | at least one chapter (when it fails, the two items below are not reported) |
  | `emptyChapters` | no chapter without lessons (`message` gives the number) |
  | `lessonContent` | every lesson has content of its type: video → `videoUrl`, article → `articleContent` (`message` gives the number) |

  The thumbnail is not a checklist item (SRS). Pending / declined invitations do not matter (BR-05). No event, no mail.
- Admin moderation (`/admin/courses/{id}/…`): route rule + `hasRole('ADMIN')`, and the admin role is re-checked
  **active in the database** (BR-26, UC-A10 E2: a stale admin JWT → 403 `ACCESS_DENIED`) · 401 without token · 404
  unknown id · 409 wrong status (e.g. a second admin deciding the same submission, UC-A10 E1).
  - `POST …/approve` (no body): `pending_review → published`; `reviewedBy`/`reviewedAt` = admin / now; `publishedAt`
    set only when null (the first publication date is kept). Listed in the catalog at once. Publishes
    `CourseApproved(courseId, slug)` → mail "Khóa học của bạn đã được phê duyệt trên Edumy" to the main instructor.
  - `POST …/reject` `RejectCourseRequest {reason}` (required, trimmed, ≤16 383 chars; missing / null / blank → 400
    `VALIDATION_FAILED` on `reason`, BR-08 — `ck_courses_note_required` is never reached; no body → 400
    `MALFORMED_REQUEST`): `pending_review → rejected`, `reviewNote` = reason. Publishes `CourseRejected(courseId, slug,
    reason)` → mail with the reason to the main instructor (D-16).
  - `POST …/hide` `HideCourseRequest {reason}` (same rules): `published → hidden`, `reviewNote` = reason; leaves the
    catalog list, search and detail; enrolled students keep learning (BR-31). Publishes `CourseHidden(courseId, slug,
    reason)` → mail with the reason to the main instructor (commerce cart flagging: P06-G5).
  - `POST …/unhide` (no body): `hidden → published` (back in the catalog, D-20). Publishes `CourseUnhidden(courseId,
    slug)`; no mail (SRS §7 lists none).

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

Commerce reads (P05):
- `GET /cart` → **200** `CartResponse {items[{courseId,title,slug,thumbnailUrl,price,isFree,instructor{id,fullName},addedAt,
  purchasable,unavailableReason}],itemCount,purchasableCount,totalAmount}`: items in added order at the **current**
  price; `purchasable=false` lines (not in `totalAmount`) carry `unavailableReason` = `course_unavailable` (hidden / not
  published, BR-31) · `already_owned` (BR-10) · `own_course` (main instructor, BR-32) · `now_free` (BR-09), checked in
  that order. No cart → empty cart, nothing is created. The "suggest public courses" hint of an empty cart is the
  catalog list (UC-G02), not part of this response. 4 statements (1 when empty).
- `GET /orders?page=&size=` → **200** `PagedModel<OrderSummaryResponse {orderCode,totalAmount,status,cancelReason,
  itemCount,createdAt,paidAt,cancelledAt}>`, newest first (`sort` ignored), 2 statements per full page.
- `GET /orders/{orderCode}` → **200** `OrderDetailResponse {orderCode,totalAmount,status,cancelReason,createdAt,paidAt,
  cancelledAt,items[{courseId,title,slug,thumbnailUrl,unitPrice}],payments[{transactionRef,amount,status,qrExpiresAt,
  confirmedAt,createdAt}]}`: `unitPrice` = frozen order_items price (BR-12); attempts newest first, no QR payload;
  3 statements. Unknown code or another student's → 404 `RESOURCE_NOT_FOUND` (identical, NFR-01; admins get 404 too).
- `GET /orders/{orderCode}/payments/current` → **200** `PaymentStatusResponse {orderCode,orderStatus,transactionRef,
  status,amount,qrData,qrExpiresAt,confirmedAt,createdAt}`: the pending attempt, else the latest one; `qrData` only while
  `status=pending` (otherwise `null`). Order without any attempt (e.g. cancelled before a QR was made) → 404
  `RESOURCE_NOT_FOUND`, same as unknown / not own.
- `GET /admin/orders?orderCode=&email=&status=&createdFrom=&createdTo=&page=&size=` → **200**
  `PagedModel<AdminOrderResponse {id,orderCode,student{id,fullName,email},totalAmount,status,cancelReason,itemCount,
  createdAt,paidAt,cancelledAt}>`, newest first; `orderCode` / `email` exact (case-insensitive; unknown email = empty
  page), `createdFrom` inclusive / `createdTo` exclusive (ISO-8601 instants), `status` in `pending|paid|cancelled`;
  bad status / date → 400 `VALIDATION_FAILED`. 3 statements per full page (+1 with `email`).
- `GET /admin/payments?status=&awaitingReview=&page=&size=` → **200** `PagedModel<AdminPaymentResponse {id,orderId,
  orderCode,student{id,fullName,email},transactionRef,amount,qrExpiresAt,status,confirmedAt,handledBy{id,fullName},
  handledAction,handledNote,handledAt,createdAt}>` (no QR payload), newest first; `status` in `pending|success|failed|
  expired|needs_review` (else 400 `VALIDATION_FAILED`); `awaitingReview=true` = needs_review and not handled, oldest
  first (UC-A18 queue), overrides `status`. 3 statements per full page.

Commerce writes (P06-G5), login required (D-23; no verified email needed to cart, D-02 applies to ordering):
- `POST /cart/items` `AddCartItemRequest {courseId}` (required; missing / null → 400 `VALIDATION_FAILED`) → **201**
  `CartResponse` (the whole cart after the add). Checked in this order: course not `published` or unknown → 404
  `RESOURCE_NOT_FOUND` (BR-31) · price 0 → 422 `CART_COURSE_FREE` (BR-09) · already enrolled → 409
  `CART_COURSE_ALREADY_OWNED` (BR-10) · instructor of the course (main, or accepted co-instructor with an active
  instructor role; the main instructor id counts whatever the role) → 422 `CART_OWN_COURSE` (BR-32) · already in the
  cart → 409 `CART_ITEM_DUPLICATE` (BR-11, also `uq_cart_items_cart_course` under concurrency). The cart row is created
  lazily by the first successful add (BR-22, race-safe). Nothing is cached.
- `DELETE /cart/items/{courseId}` → **204**; a course that is not in the caller's own cart (no cart, other student's
  item, unknown course) → 404 `RESOURCE_NOT_FOUND`, identical body. Unavailable lines can be removed too.
- Cart flags are derived on every `GET /cart` (hide → `course_unavailable`, unhide → purchasable again); the commerce
  `CourseHiddenListener` persists nothing (it only logs courseId + number of carts).
- Known limit: the `own_course` flag of `GET /cart` still looks at the main instructor only; `POST /cart/items` also
  refuses accepted co-instructors (BR-32).

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

**P05-learning (GET reads, implemented)** — all scoped by the caller / access beans, 404 identical for unknown and not-yours:
- `GET /me/enrollments?page&size` → **200** `PagedModel<EnrollmentResponse {id, course{id,title,slug,thumbnailUrl,published}, enrollSource, enrolledAt, completedLessons, totalLessons, progressPercent, lastAccessedLessonId, lastAccessedAt, certificateCode}>`, newest enrollment first, hidden courses included (BR-31); 5 statements per non-empty page.
- `GET /learn/courses/{courseId}` (`@enrollmentAccess.canViewContent`, any course status) → **200** `LearningOutlineResponse {courseId, title, enrolled, progress{completedLessons,totalLessons,percent}|null, chapters[{id,title,lessons[{id,title,contentType,durationSeconds,preview,completed|null}]}]}`; instructor / admin: `enrolled=false`, no progress (D-32). 6 statements.
- `GET /learn/lessons/{lessonId}` (`canViewLesson`) → **200** `LessonContentResponse {id,courseId,chapterId,title,contentType,durationSeconds,videoUrl,articleContent,completed|null}`; records no visit (POST in P06).
- `GET /courses/{courseId}/questions?lessonId=&mine=&unanswered=&q=` (`canViewContent`) → **200** `PagedModel<QuestionSummaryResponse {id,courseId,courseTitle(null),lessonId,title,resolved,status,asker{id,fullName},answerCount,instructorReplied,createdAt}>`, newest first; visible + the caller's own hidden (+ all hidden for admin); 4 statements.
- `GET /questions/{id}` (`@qaAccess.canView`) → **200** `QuestionThreadResponse {…, content, updatedAt, answers[{id,author{id,fullName},authorType,content,status,createdAt,updatedAt}]}`; answers oldest first, deleted never, hidden only for their author and admin.
- `GET /instructor/questions?courseId=&status=unanswered` (`@courseAccess.isActiveInstructor`: no role → 403) → **200** `PagedModel<QuestionSummaryResponse>` (with `courseTitle`) of visible questions of the courses the caller instructs, newest first; `courseId` not instructed → 404; `status` other than `unanswered` → 400 `VALIDATION_FAILED`; 5 statements.
- `GET /instructor/courses/{courseId}/students` (`@courseAccess.isInstructor`) → **200** `PagedModel<StudentProgressResponse {enrollmentId, student{id,fullName}, enrollSource, enrolledAt, completedLessons, totalLessons, progressPercent, lastAccessedAt}>`, newest enrollment first, no email; 5 statements.
- `GET /me/certificates` → **200** `PagedModel<CertificateResponse {id,certificateCode,enrollmentId,courseId,courseTitle,studentName,instructorName,issuedAt}>`, newest first; 2 statements.

**P06-G6 (learning writes, implemented)** — `EnrollmentController`, `LearningController`:
- `POST /courses/{courseId}/enrollments` (no body, login only — D-02: unverified email allowed) → **201** `EnrollmentResponse` (the UC-S08 row) for a new free enrollment; **200** with the existing row when the student is already enrolled (UC-S15 step 4, BR-17: idempotent, nothing written). Checks in order: not `published` or unknown → 404 `RESOURCE_NOT_FOUND` (BR-31) · price > 0 → 422 `ENROLLMENT_COURSE_NOT_FREE` (BR-09) · instructor of the course → 422 `ENROLLMENT_OWN_COURSE` (BR-32) · then `INSERT IGNORE` (race-safe: concurrent requests → one 201, the rest 200).
- `POST /learn/lessons/{lessonId}/visit` (`@enrollmentAccess.canViewLesson`) → **204**. Enrolled student: `lesson_progresses` row created (not completed) or `last_accessed_at` = now (D-34 lives on `lesson_progresses`, not on `enrollments`); completion untouched. Course instructor / admin (D-32, no enrollment): accepted, **no-op** 204. Anyone else / unknown → 404 identical.
- `PUT /learn/lessons/{lessonId}/completion` `CompletionRequest {completed}` (`@NotNull`; `@enrollmentAccess.isEnrolledForLesson`: not enrolled, a lesson of another course, instructor, admin, unknown → 404 identical) → **200** `ProgressResponse {lessonId, completed, completedAt, completedLessons, totalLessons, progressPercent (floor, D-33), certificateCode|null, certificateIssued}`; `certificateIssued` is true only on the request that issued it, `certificateCode` stays after an unmark (BR-40). A first-time completion also creates the progress row with `last_accessed_at` = now; unmarking a never-started lesson writes nothing. Hidden courses stay completable (BR-31).
- Certificate (BR-39/BR-40): issued by the completion that reaches 100 % of the lessons existing now, once per enrollment (free or purchase), snapshot of student name, course title, main instructor name; `EDU-XXXX-XXXX-XXXX` from `SecureRandom`. Event `CertificateIssued(certificateId, enrollmentId, studentId, courseId)` → `learning.listener.CertificateIssuedListener` logs ids after commit (NFR-08). UC-S20 step 1 / UC-S11 "issue a missing certificate on read" is NOT implemented (see progress.md).

**P06-G7 (reviews, implemented)** — `ReviewController`, `AdminModerationController` (UC-S12…S14, A15, A17; docs/ai/02 §2.2). Responses carry `ReviewResponse {id, courseId, rating, comment, status (visible|withdrawn|hidden), createdAt, updatedAt}`; there is no GET of the caller's own review (the SPA reads the visible list `GET /catalog/courses/{slug}/reviews`).
- `POST /courses/{courseId}/reviews` `CreateReviewRequest {rating 1–5 required, comment ≤2000 optional}` (login only) → **201** new review · **200** the withdrawn review of the enrollment re-rated (same row, UC-S12 step 3). 404 unknown course · 403 `ENROLLMENT_REQUIRED` (existing course, no enrollment; hidden / unpublished courses can still be reviewed by their students, BR-31) · 409 `DATA_CONFLICT` already `visible` (BR-18) · 409 `REVIEW_HIDDEN_BY_ADMIN` (BR-35) · 400 `VALIDATION_FAILED`. Concurrent double POST: one row (201 + 409).
- `PATCH /reviews/{id}` `UpdateReviewRequest {rating required, comment}` (full replacement, an absent comment clears it) → **200** · `DELETE /reviews/{id}` → **204** (`withdrawn`, not deleted). Not the author's / unknown → 404 identical · 409 `REVIEW_HIDDEN_BY_ADMIN` (hidden) · 409 `DATA_CONFLICT` (PATCH or DELETE of a withdrawn review).
- `POST /admin/reviews/{id}/hide` · `/restore` (admin JWT role + role re-checked in the DB, BR-26; optional body `ModerationRequest {reason ≤500}`, only logged — SRS/DDD have no reason) → **200** `ReviewResponse`. 404 unknown · 409 `DATA_CONFLICT` hide of a hidden / restore of a non-hidden review · 403 `ACCESS_DENIED` role revoked in the DB.
- Event `ReviewChanged(courseId, slug)` after every create, re-rate, edit, withdraw, hide, restore (catalog: `catalog.rating` by courseId, `catalog.course-detail` by slug — P08). No mail (SRS §7).
