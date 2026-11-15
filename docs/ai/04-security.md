# 04 — Security (authentication, sessions, authorization, abuse protection)

## 4.1 Authentication model (ADR-002)
- **Access token**: JWT HS256, TTL **15 min**, issued by `identity` with `NimbusJwtEncoder`; validated by Spring
  Security OAuth2 Resource Server (`NimbusJwtDecoder.withSecretKey`). Secret from env `EDUMY_JWT_SECRET`
  (≥ 32 random bytes, base64). Never commit it; `application-dev.yml` may hold a dev-only value.
- Claims: `sub` = user id, `roles` = active role codes at issue time, `sid` = session family id (UUID), `iat`, `exp`,
  `iss = edumy`. No email, no names, no PII beyond the id.
- **Refresh token**: opaque 256-bit random (base64url), TTL **14 days**, stored only as SHA-256 hex in `refresh_tokens`,
  sent as cookie `edumy_rt` (`HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`). Rotated on every use.
- Front end keeps the access token in memory only (never localStorage).

### `refresh_tokens` (new table, owner identity)
```sql
CREATE TABLE refresh_tokens (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  user_id        BIGINT       NOT NULL,
  family_id      CHAR(36)     NOT NULL,           -- = JWT sid, one per login session
  token_hash     CHAR(64)     NOT NULL,
  expires_at     DATETIME(6)  NOT NULL,
  revoked_at     DATETIME(6)  NULL,
  revoke_reason  VARCHAR(30)  NULL,
  created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  user_agent     VARCHAR(255) NULL,
  ip_address     VARCHAR(45)  NULL,
  CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
  CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
  CONSTRAINT fk_refresh_tokens_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT ck_refresh_tokens_hash_hex CHECK (REGEXP_LIKE(token_hash, '^[0-9a-f]{64}$', 'c')),
  CONSTRAINT ck_refresh_tokens_reason CHECK (revoke_reason IS NULL OR CAST(revoke_reason AS BINARY) IN
    ('logout','rotated','reuse_detected','password_changed','password_reset','account_locked','expired_cleanup')),
  CONSTRAINT ck_refresh_tokens_revoked_pair CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL)),
  INDEX ix_refresh_tokens_user (user_id, revoked_at),
  INDEX ix_refresh_tokens_family (family_id, revoked_at)   -- "family still has a non-revoked token"
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```
Note: `fk_refresh_tokens_user_id` uses CASCADE, so no CHECK may reference `user_id` (MySQL error 3823).
Enum-like CHECK uses `CAST(… AS BINARY) IN (…)` and the UNIQUE follows `uq_<table>_<column>` (docs/ai/03 §3.1).

### Session flows ("kết thúc phiên" requirements)
| Requirement | Action |
|---|---|
| UC-G07 login | verify password + `status = active`; new `family_id`; issue access + refresh |
| `/auth/refresh` | hash cookie → find row; if revoked with reason `rotated` → **reuse detected**: revoke whole family (`reuse_detected`), 401; else revoke current (`rotated`) and insert successor in same family |
| UC-G10 logout | revoke current family (`logout`) |
| UC-G12 change password | revoke all families of the user **except** current `sid` (`password_changed`); email notice |
| UC-G08 reset password | revoke **all** families (`password_reset`); do not auto-login (step 7) |
| UC-A02 lock (D-05) | revoke all families (`account_locked`) |

**Immediate effect on live access tokens:** a `JwtValidator`/`AuthenticationConverter` checks on every request that
(1) the `sid` family still has a non-revoked token and (2) the user is `active`. Both answers are cached in Caffeine
(`identity.session-status`, `identity.user-status`, TTL 30 s) and **evicted explicitly** when revoking/locking, so a
lock or reset is effective instantly on the same instance and within 30 s in the worst case.

## 4.2 Password & token hygiene (NFR-02, BR-34, D-03)
- `BCryptPasswordEncoder(12)`. Policy: ≥ 8 chars, at least one letter and one digit; new ≠ current.
- Email-verification (24 h) and password-reset (30 min) codes: `SecureRandom` 32 bytes → base64url sent by email;
  store SHA-256 hex only; issuing a new code sets `used_at` on older unused codes of the same type; resend allowed
  after 60 s; consume with the atomic UPDATE of T7.
- Forgot password always answers the same message: "Nếu email tồn tại, hướng dẫn đã được gửi".
- Login errors are generic: "Email hoặc mật khẩu không đúng".

## 4.3 Authorization (SRS §3.3 RBAC + data relationship)
Two layers, both server-side:
1. **Route level** (`SecurityFilterChain`): `/api/v1/catalog/**`, `/api/v1/auth/**` (except logout), and
   `GET /api/v1/catalog/certificates/*` are public; `/api/v1/admin/**` → `hasRole('ADMIN')`; everything else
   authenticated. JWT `roles` claim → `ROLE_*` authorities.
2. **Method level** with `@EnableMethodSecurity` + SpEL beans, re-checking roles from DB (BR-26), because JWT roles
   can be up to 15 min stale (D-07: revoked instructor loses editing rights **immediately**):

| Bean | Methods | Used for |
|---|---|---|
| `@courseAccess` (authoring) | `isMain(#courseId)`, `isInstructor(#courseId)`, `canEditContent(#courseId)` (instructor + status ≠ pending_review); `isActiveInstructor()` (active instructor role only, for endpoints without a course id: UC-I02 list, UC-I14 invitations; never 404) | UC-I01…I20 |
| `@chapterAccess` / `@lessonAccess` (authoring) | same three methods by chapter / lesson id: role first, then resolve the parent course (unknown → 404) and delegate | chapter/lesson endpoints |
| `@enrollmentAccess` (learning) | `isEnrolled(#courseId)`, `canViewContent(#courseId)` (enrolled OR course instructor OR admin, D-32), `canViewLesson(#lessonId)`, `isEnrolledForLesson(#lessonId)` | UC-S09…S20 |
| `@qaAccess` (learning) | `canView(#questionId)` (thread read: not deleted, hidden only asker/admin, visible = `canViewContent`; else 404), `canReply(#questionId)` (asker or course instructor, BR-38), `isQuestionAuthor(#questionId)`, `isAnswerAuthor(#answerId)` | Q&A |

**403 vs 404 principle (approved in P10, applies to every endpoint):**
1. A resource every outsider may know exists — a **published** course — whose relation the caller lacks → 403 with a
   specific business code (e.g. `ENROLLMENT_REQUIRED`), so the SPA can send the user to the course page (UC-S09
   "Không có quyền → trang giới thiệu khóa").
2. A resource that is **not public** — a draft / pending / rejected / hidden course, someone else's order, question,
   answer, review, enrollment, invitation — for a caller with no relation → 404 `RESOURCE_NOT_FOUND`, body identical to
   an unknown id (NFR-01, UC-G04 E1).
3. A missing **role** → 403 `ACCESS_DENIED`, decided before any lookup.
Inventory and proofs: docs/ai/10.

Outcomes (Q7, P04-e): **`false` → 403 `ACCESS_DENIED`** only when the caller lacks the needed role in the database
(`@courseAccess`/`@chapterAccess`/`@lessonAccess`: no active instructor role — checked before any lookup, so ids cannot be
probed). Unknown resource, or no relation to it → **`RESOURCE_NOT_FOUND` (404, identical body)**. State conflicts are not
permissions: `canEditContent` on a `pending_review` course → `COURSE_NOT_EDITABLE` (409, BR-33, only for a course
instructor; a stranger still gets 404). `canReply`: a caller who can see the question (enrolled, admin) but is neither
the asker nor a course instructor → `QA_REPLY_NOT_ALLOWED` (403); a caller who cannot see it → 404. Question visibility:
`deleted` → nobody; `hidden` → only the asker and an admin; `visible` → whoever passes `canViewContent`. The
`@enrollmentAccess` methods never return false (true or 404). A `BusinessException` thrown inside the SpEL call leaves
the expression unwrapped (SpEL `MethodReference` rethrows a `RuntimeException` cause as is, Spring Security only catches
`AuthorizationDeniedException`) and is rendered by `GlobalExceptionHandler`; proved by `AuthoringAccessIT` /
`LearningAccessIT` through the test-only `AccessProbeController`.

Example:
```java
@PreAuthorize("@courseAccess.isMain(#courseId)")
@PatchMapping("/instructor/courses/{courseId}")
CourseDetailResponse update(@PathVariable Long courseId, @Valid @RequestBody UpdateCourseRequest req) { ... }
```
- Own-resource queries are always scoped by the current user id (`findByOrderCodeAndStudentId`). Not found or not
  yours → **404** with the same body (NFR-01 anti-IDOR, prevents enumeration).
- Admin is also a student (RBAC row "với tư cách học viên") but a course instructor can never buy/enroll their own
  course (BR-32).
- Every protected endpoint gets an IDOR test with two users (see `07-testing.md`).

## 4.4 Abuse protection (NFR-03) — single-instance, in-memory (documented limitation, ADR-002)
- Login: `identity.security.LoginAttemptLimiter`, keyed by the normalized email (unknown emails too), Caffeine
  `expireAfterWrite(15 min)` + `maximumSize(100 000)`, ticker = the application `Clock`. Called by
  `SessionService.login`:
  - failures 1–4 → normal 401 `AUTH_INVALID_CREDENTIALS`; the **5th consecutive failure** also answers 401 and starts
    the block: `blockedUntil = time of failure #5 + 15 min`;
  - "consecutive within 15 min" = each failure rewrites the entry and restarts its 15-min life; a gap of 15 min or
    more between two failures starts again at 1;
  - while blocked, **every** attempt for that email (correct password too) → 429 `AUTH_LOGIN_TEMPORARILY_BLOCKED` +
    `Retry-After` (seconds until `blockedUntil`, rounded up), checked before any user lookup or BCrypt. A blocked
    attempt only reads: it never extends the block nor refreshes the entry;
  - never permanent: from `blockedUntil` on (compared with the `Clock`, not left to cache eviction) the next attempt
    is checked normally and a correct password logs in;
  - a successful password check removes the entry; that includes the correct password on a locked account, which then
    still gets 403 `AUTH_ACCOUNT_LOCKED` (Q1). The email is never logged.
- Certificate verification `GET /api/v1/catalog/certificates/{code}`: `identity.security.CertificateVerifyRateLimiter`,
  a filter registered only in the Spring Security chain (before token processing), Bucket4j bucket per
  `request.getRemoteAddr()` (never `X-Forwarded-For`): capacity 30, greedy refill 30/min (one token every 2 s); over the
  limit → 429 `RATE_LIMITED` ProblemDetail + `Retry-After` (seconds to the next token, rounded up). Buckets in Caffeine
  (`expireAfterAccess(5 min)`, `maximumSize(100 000)`); Bucket4j time = the application `Clock`, never going backwards.
  Behind a reverse proxy, configure `server.forward-headers-strategy` for the trusted proxy only (ADR-002).
- `Retry-After` comes from `shared.error.RetryAfterException` (a `BusinessException`), added by
  `GlobalExceptionHandler`; it is in the CORS exposed headers so the SPA can read it.
- Email resend: 60 s cooldown from `user_tokens.created_at` (no extra storage).
If the app is ever scaled to >1 instance, move these to Redis (new ADR); see ADR-002 "Abuse protection — known
limitations".

## 4.5 Transport & headers
- Logs: user free text only through `shared.text.LogSanitizer` (CR/LF/control characters → `_`, ≤200 characters;
  P10). LIKE keywords only through `shared.text.LikePattern` / the equivalent catalog and Q&A helpers (`ESCAPE '!'`).
- CORS: allowed origins from `edumy.cors.allowed-origins` (dev: `http://localhost:5173,http://localhost:3000`),
  `allowCredentials=true` (refresh cookie).
- CSRF: disabled for bearer-token endpoints; `/auth/refresh` and `/auth/logout` rely on `SameSite=Strict` cookie +
  `Origin` header check against the CORS allow-list.
- Stateless: `SessionCreationPolicy.STATELESS`. HTTPS is terminated in front of the app in production.
- Actuator: only `health` and `info` exposed (`application.yml`) and public. `caches` / `metrics` (Caffeine statistics) are exposed in the `dev` profile only, and `/actuator/**` other than health/info needs the admin role (401 without token, 403 for any other role; P08).
