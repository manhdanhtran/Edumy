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
  CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
  CONSTRAINT fk_refresh_tokens_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT ck_refresh_tokens_hash_hex CHECK (REGEXP_LIKE(token_hash, '^[0-9a-f]{64}$', 'c')),
  CONSTRAINT ck_refresh_tokens_reason CHECK (revoke_reason IS NULL OR revoke_reason IN
    ('logout','rotated','reuse_detected','password_changed','password_reset','account_locked','expired_cleanup')),
  CONSTRAINT ck_refresh_tokens_revoked_pair CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL)),
  INDEX ix_refresh_tokens_user (user_id, revoked_at),
  INDEX ix_refresh_tokens_family (family_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```
Note: `fk_refresh_tokens_user_id` uses CASCADE, so no CHECK may reference `user_id` (MySQL error 3823).

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
| `@courseAccess` (authoring) | `isMain(#courseId)`, `isInstructor(#courseId)`, `canEditContent(#courseId)` (instructor + status ≠ pending_review) | UC-I01…I20 |
| `@chapterAccess` / `@lessonAccess` (authoring) | resolve parent course then delegate | chapter/lesson endpoints |
| `@enrollmentAccess` (learning) | `isEnrolled(#courseId)`, `canViewContent(#courseId)` (enrolled OR course instructor OR admin, D-32) | UC-S09…S20 |
| `@qaAccess` (learning) | `canReply(#questionId)` (asker or course instructor, BR-38), `isAuthor(...)` | Q&A |

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

## 4.4 Abuse protection (NFR-03) — single-instance, in-memory (documented limitation)
- Login: `LoginAttemptLimiter` keyed by normalized email, Caffeine `expireAfterWrite(15 min)`; 5 consecutive
  failures → reject further attempts for 15 min with 429 + generic message; success resets the counter.
- Certificate verification `GET /api/v1/catalog/certificates/{code}`: Bucket4j bucket per client IP, 30 req/min → 429.
- Email resend: 60 s cooldown from `user_tokens.created_at` (no extra storage).
If the app is ever scaled to >1 instance, move these to Redis (new ADR).

## 4.5 Transport & headers
- CORS: allowed origins from `edumy.cors.allowed-origins` (dev: `http://localhost:5173,http://localhost:3000`),
  `allowCredentials=true` (refresh cookie).
- CSRF: disabled for bearer-token endpoints; `/auth/refresh` and `/auth/logout` rely on `SameSite=Strict` cookie +
  `Origin` header check against the CORS allow-list.
- Stateless: `SessionCreationPolicy.STATELESS`. HTTPS is terminated in front of the app in production.
- Actuator: only `health` and `info` exposed publicly.
