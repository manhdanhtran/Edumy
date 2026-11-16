# ADR-002 — JWT access token (15 min) + rotating refresh tokens table
Status: Accepted · Date: 2026-11-12

Context: SRS requires ending the current session (UC-G10), other sessions (UC-G12), all sessions (UC-G08, D-05).
Pure stateless JWT cannot revoke. The 20-table DDD has no session storage.

Decision: add table `refresh_tokens` (owner identity, DDL in `04-security.md`). Access JWT carries `sid` = family id;
each request validates family + user status through 30 s Caffeine caches evicted on change. Refresh tokens rotate;
reuse of a rotated token revokes the family.

Alternatives rejected: `users.token_version` (cannot end only the current or only other sessions);
Spring Session JDBC (cookie session + CSRF on every endpoint, less familiar for the SPA team).
Consequences: one table outside DDD (21 tables total); cleanup job deletes rows expired > 30 days (phase 2).

## Abuse protection — known limitations (P04-f, NFR-03, docs/ai/04 §4.4)
Login brute force (`identity.security.LoginAttemptLimiter`, Caffeine) and certificate verification
(`identity.security.CertificateVerifyRateLimiter`, Bucket4j local buckets in Caffeine) are accepted with these limits:
- **In-memory, single instance only.** With more than one app instance every instance counts on its own: limits
  multiply by the number of instances (behind round-robin, 5 failures per instance before a block, 30 req/min per
  instance) and a login block is not shared. State is lost on restart (a restart lifts every block). Before running
  more than one instance, move both limiters to a shared store (e.g. Redis + Bucket4j distributed) **with a new ADR**.
- **Certificate limiter keys on `request.getRemoteAddr()`; `X-Forwarded-For` is never read** (client-controlled).
  Behind a reverse proxy / load balancer every client would share the proxy's address (one bucket for everyone).
  Production must set `server.forward-headers-strategy` (`NATIVE` or `FRAMEWORK`) and trust the forwarded headers
  **only from that proxy** (e.g. Tomcat `server.tomcat.remoteip.internal-proxies`), so `remoteAddr` becomes the real
  client IP; never trust `X-Forwarded-For` blindly. Not configured yet (TODO in `CertificateVerifyRateLimiter`).
- Both caches are bounded (100 000 keys) against memory exhaustion. Evicting a login entry only forgets failures;
  evicting an idle certificate bucket (5 min) loses nothing because it is full again after one minute. Under a flood of
  distinct keys, eviction can forget a real client's state: accepted for a single-instance v1.
- The login limiter is keyed by email only (no per-IP limit): an attacker can keep a victim's login blocked by sending
  5 wrong passwords every 15 minutes. Accepted (SRS NFR-03 asks for exactly this); the block is never permanent and
  password reset still works.

## P13 hardening (2026-11-16)
- **Per-IP limits of the anonymous account endpoints** (`identity.security.AuthEndpointRateLimiter`, Bucket4j in
  memory, same IP rule as the certificate check — `remoteAddr`, never `X-Forwarded-For`): `POST /auth/register` 10 per
  hour, `POST /auth/password/forgot` 5 per 15 min, `POST /auth/password/reset` 10 per 15 min; greedy refill; over the
  limit → 429 `RATE_LIMITED` + `Retry-After`. Configurable (`edumy.security.auth-rate-limit.*`); the test profile raises
  them because one IP runs the suite. Same known limitations as below (single instance, proxy address without
  `forward-headers-strategy`).
- **Reset checks the code before BCrypt:** `AccountTokenService.isUsable` (plain read) refuses a wrong / used / expired
  code with 422 `ACCOUNT_TOKEN_INVALID` before the new password is hashed; the atomic `consume` still decides.
- **Public reads ignore a bad token:** on `GET /api/v1/catalog/**` an expired, malformed, revoked or locked-account token
  is treated as absent (anonymous answer, no viewer) instead of 401; protected paths still answer 401.
- **Token check without database → 503:** a database failure while the token is validated answers 503
  `SERVICE_UNAVAILABLE` (ProblemDetail with `traceId`), not 401 / 500 (`AuthenticationUnavailableFilter`).
- **Foreign CORS origin → 403 ProblemDetail** `ACCESS_DENIED` (`CorsRejectionFilter`, before Spring's `CorsFilter`),
  no new dependency.