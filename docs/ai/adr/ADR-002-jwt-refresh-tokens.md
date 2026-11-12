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
