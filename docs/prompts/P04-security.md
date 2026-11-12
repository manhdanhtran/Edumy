# P04 — Security (PLAN FIRST)

## Prompt
```text
Task: implement authentication + authorization foundation as specified in docs/ai/04-security.md and ADR-002.
Folders (docs/ai/01 §1.5): identity/security (SecurityConfig, JwtIssuer, JwtSessionValidator, SessionStatusCache,
LoginAttemptLimiter, CertificateVerifyRateLimiter), identity/service, identity/controller, identity/dto/{request,response},
shared/security, plus access beans in authoring/security (CourseAccess, ChapterAccess, LessonAccess) and
learning/security (EnrollmentAccess, QaAccess).

Scope:
1. SecurityFilterChain (stateless, CORS from EdumyProperties, route rules §4.3), @EnableMethodSecurity,
   OAuth2 resource server with HS256 NimbusJwtDecoder; JwtIssuer with NimbusJwtEncoder; claims per §4.1.
2. Converter/validator that checks sid family + user active via Caffeine caches (identity.session-status,
   identity.user-status) with explicit eviction methods.
3. RefreshToken entity/repo (if missing), SessionService: login, refresh with rotation + reuse detection,
   logout, revokeAllExcept(sid), revokeAll(reason). Refresh cookie per §4.1.
4. Endpoints: /auth/register, /auth/email-verification/confirm|resend, /auth/login, /auth/refresh, /auth/logout,
   /auth/password/forgot|reset, GET/PATCH /me, PUT /me/password, POST /me/instructor-registration.
   Tokens per §4.2 (SHA-256, single use via atomic UPDATE, 60 s resend, generic responses). Emails via events only.
5. LoginAttemptLimiter (5 fails / 15 min) and certificate-verify Bucket4j limiter (30/min/IP) per §4.4.
6. RoleQueryApi (cached identity.active-roles) used by access beans (BR-26) — never trust JWT roles for
   instructor actions.
7. Tests: login ok/wrong/locked/blocked-6th-attempt; refresh rotation + reuse detection; change password ends other
   sessions only; lock ends all sessions immediately (request with old access token → 401); access bean unit tests.

Output a plan first (classes, endpoints, tests). Wait for approval. Never log tokens or passwords.
```
