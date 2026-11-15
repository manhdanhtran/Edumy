# 06 — Phase plan & Definition of Done

## Phase 1 — "vòi cấp nước" for the front end (ADR-005)
Goal: the SPA can call every endpoint in `05-api-contract.md` and get contract-correct JSON backed by real MySQL data.

| Step | Output | Prompt |
|---|---|---|
| 1 | Project from start.spring.io + compose.yaml + config profiles | P00 |
| 2 | Flyway V1–V6 (20 tables + refresh_tokens, triggers, roles) | P01 |
| 3 | Dev seed `db/seed/V1000__dev_seed.sql` covering every status | P02 |
| 4 | Entities + repositories per module, `validate` passes | P03 |
| 5 | Security: JWT + refresh tokens + method security beans | P04 |
| 6 | Read endpoints (all GET) real | P05 |
| 7 | Simple write endpoints real | P06 |
| 8 | Complex write endpoints as stubs | P07 |
| 9 | Caching | P08 |
| 10 | Tests: modularity, constraints, IDOR, slices | P09 |

Inserted step: **after P01 session 5 (V5 triggers)** implement `ConstraintViolationTranslator` by vendor error code
(1062, 1451/1452, 3819, 1644/SQLState 45000; see `05-api-contract.md` §5.2) and test it through both JPA and
`JdbcClient`. It needs the V5 triggers to exercise 1644.

### Stub contract (STUB-P1)
A stub endpoint:
- has the final URL, method, request DTO with full Bean Validation, final response DTO;
- runs the real authorization (`@PreAuthorize`, ownership check against real data where cheap);
- returns a deterministic sample built from real rows when possible (e.g. `POST /orders` returns an order preview
  computed from the current cart but **does not insert**);
- sets header `X-Edumy-Stub: true` via `StubSupport.mark(response)`;
- has a `// STUB-P1: replace with <Service>.<method> — UC-xx, BR-xx` comment (grep `STUB-P1` to list remaining work);
- never writes to the database and never calls external services.

Stub list (phase 1): POST `/orders` (T1) · POST `/orders/{code}/cancel` · POST `/orders/{code}/payments` · POST
`/webhooks/payments/fake` · POST `/admin/payments/{id}/resolve` · GET `/me/certificates/{code}/pdf` · all schedulers.
**Since P11** only GET `/me/certificates/{code}/pdf` is left (P12); the commerce endpoints and the schedulers are real
(ADR-011; schedulers on with `edumy.scheduler.enabled=true`).

## Phase 2 — business logic
Replace each `STUB-P1` with a real use case (prompt P12), in this order: T1 create order → fake QR provider + T2 →
cancel/expiry schedulers → reconciliation A18 → certificate PDF (needs Q-11 template). Enable Modulith event
publication registry. Load test NFR-05.

**Done in P11 (2026-11-15):** fake QR provider + T2 (P11-a), T1 + cancel (P11-b), create QR (P11-c), reconciliation A18
+ purchase / reconciliation mails (P11-d), expiry schedulers + code cleanup (P11-e). Not done: Modulith event publication (would be a new dependency / schema: mails stay at-most-once, ADR-006); NFR-05 load test (out of scope list, P14).

## Definition of Done (every task)
- [ ] UC/BR codes listed in the PR description; behaviour matches SRS §3.4 state machine and §5 rules
- [ ] Authorization: role + relationship check; IDOR test added for new protected endpoints
- [ ] Validation limits match SRS; error responses are ProblemDetail with a known `code`
- [ ] No entity leaves the module; cross-module calls only through `api`; `ModularityTest` green
- [ ] Migrations: only new files; `ddl-auto=validate` passes
- [ ] Tests added/updated and `./mvnw test` green (state which tests ran)
- [ ] OpenAPI annotations present; no secrets/tokens in logs
- [ ] Cache eviction updated if catalog-visible data changed
