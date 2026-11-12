# P10 — Code review (dùng cho mỗi PR)

```text
Review the diff of this branch against main as a senior Spring/MySQL reviewer for Edumy. Check, citing file:line:
1. Structure & boundaries: every class in the folder required by docs/ai/01 §1.3/§1.6 with §1.7 naming; no forbidden folders
   (web, domain, application, infra, utils, impl…); imports of another module's non-api packages; JPA associations across
   modules; writes to tables the module does not own; records for other modules placed in dto/ instead of api/.
2. Business correctness: UC/BR in the PR vs SRS §3.4 transitions and §5 rules; missing guards; derived data stored.
3. Security: missing @PreAuthorize or relationship check, 403-vs-404 leaks, trusting JWT roles for instructor actions,
   secrets/tokens in logs, CORS/cookie settings (docs/ai/04).
4. Data: migrations edited instead of added, DDD names changed, money not Long, time not Instant/UTC,
   N+1 queries, missing indexes for new queries, transaction boundaries (T1/T2/T7 semantics).
5. API: contract drift vs docs/ai/05, validation limits, ProblemDetail codes, OpenAPI annotations.
6. Tests: Testcontainers for SQL, IDOR tests, no @Disabled, Clock injected.
7. Phase rules: STUB-P1 endpoints writing to DB; out-of-scope features (AGENTS.md §8).
Output: BLOCKER / MAJOR / MINOR lists with concrete fixes, then a 3-line verdict. Do not rewrite code unless asked.
```
