# AGENTS.md — Edumy Backend

> Single source of truth for every AI coding agent (Claude Code, Cursor, Copilot, Codex, Gemini CLI…).
> Keep this file short. Details live in `docs/ai/`. Read the relevant doc BEFORE writing code.

## 1. Project in one paragraph
Edumy is an online-course marketplace (Udemy-like, simplified): instructors author courses, admins review
before publishing, students buy via QR payment or enroll for free, learn chapter/lesson, ask Q&A, review
courses and receive completion certificates. Backend = **Spring Boot modular monolith**, **MySQL 8.4 LTS**,
REST JSON API consumed by a separate SPA. UI/user messages are Vietnamese; code is English.

## 2. Sources of truth (precedence, highest first)
1. `docs/ai/adr/*` — approved deviations (PostgreSQL→MySQL, `refresh_tokens`, package structure ADR-007). ADR beats DDD.
2. **EDU-DDD-001 v1.0** (Thiết kế CSDL) — 20 tables, columns, constraints, indexes. Summary: `docs/ai/03-data-model-mysql.md`.
3. **EDU-SRS-001 v3.0** (Đặc tả yêu cầu) — UC-xx, BR-01…BR-41, D-01…D-48, NFR-01…NFR-10. Summary: `docs/ai/02-domain-glossary.md`.
4. `Edumy_4_ERD_va_Quan_he.pdf` is the **OUTDATED v2 Chen ERD (17 entities, has `token_code`, `is_free`)**. Never use it as a source.

If two sources conflict, or a requirement is missing → **STOP and ask**. Do not guess business rules.

## 3. Tech stack (do not change without an ADR)
Java 21 · Spring Boot 4.1.x · Spring Modulith 2.1.x · Maven wrapper · MySQL 8.4 (Docker) · Flyway ·
Spring Data JPA (Hibernate 7) · Spring Security + OAuth2 Resource Server (JWT HS256) · Spring Cache + Caffeine ·
Bean Validation · Lombok · MapStruct · springdoc-openapi 3.x · JUnit 5 + Testcontainers.
Boot 4 uses **modular starters** (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`, …) and **Jackson 3**.
**Never invent dependency versions.** Boot-managed deps have no `<version>`; non-BOM deps are pinned in `pom.xml`
`<properties>` by a human. If you need a new dependency, propose it and ask.

## 4. Architecture rules (details: `docs/ai/01-architecture.md`)
Modules (root package `com.edumy`), each owns its tables — **only the owner writes them**:

| Module | SRS | Owns tables |
|---|---|---|
| `identity` | 01 | users, roles, user_roles, user_tokens, refresh_tokens (ADR-002) |
| `catalog` | 02 | none — read model (SQL projections, ADR-003) |
| `authoring` | 03 | categories, courses, course_co_instructors, chapters, lessons |
| `commerce` | 04 | carts, cart_items, orders, order_items, payment_transactions |
| `learning` | 05 | enrollments, lesson_progresses, course_reviews, course_questions, question_answers, certificates |
| `notification` | — | none — async email listeners |
| `shared` | — | none — errors, security principal, text utils, JPA converters (OPEN module) |

- Allowed dependencies are declared in each `package-info.java` (`@ApplicationModule(allowedDependencies=…)`)
  and verified by `ModularityTest` (`ApplicationModules.of(EdumyApplication.class).verify()`). Must stay green.
- Cross-module access ONLY through the target module's `api/` package (`@NamedInterface("api")`): `*Api` interfaces,
  their records, events. Never import any other package of another module.
- **No JPA associations across modules.** Keep the FK as `Long xxxId`. Inside a module use `@ManyToOne(fetch = LAZY)`.
- Payment success → order paid → enrollment created **in ONE transaction** (NFR-04): `commerce` calls
  `learning.api.EnrollmentApi` **synchronously**. Never use async events for this.
- Side effects that may fail independently (emails, cache eviction) → events + `@ApplicationModuleListener` / AFTER_COMMIT.

## 5. Folder structure inside a module (ADR-007 — full spec, per-module trees: `docs/ai/01-architecture.md`)
```
com.edumy.<module>/
├── package-info.java   api/   controller/   dto/request/   dto/response/
├── service/   mapper/   entity/   repository/
└── optional: security/  listener/  integration/  scheduler/  config/
```
- Calls: `controller → service → repository → entity`; `service → mapper | integration | other module's api/`;
  `listener`, `scheduler → service`; `api/` interfaces are implemented by `service/XxxApiImpl`.
- `dto/request/` holds `XxxRequest`, `dto/response/` holds `XxxResponse` — HTTP only, nothing else in `dto/`. Records used by other modules live in `api/` (flat, no sub-packages).
- Never create other sub-folders inside `dto/`, nor `web/`, `domain/`, `application/`, `infra/`, `impl/`, `utils/`, `helper/`, `common/`, `model/`, `exception/`
  inside a module. Shared helpers → `shared/`; errors → `shared.error.ErrorCode`.
- Before creating a class, find its folder in `docs/ai/01-architecture.md` §1.6 and its name pattern in §1.7.

## 6. Coding rules
- Controllers never touch repositories or entities; `@Transactional` only in `service/`.
- Never return entities from controllers. Use Java `record` DTOs + MapStruct.
- Entities: `@Getter`, `@NoArgsConstructor(access = PROTECTED)`; **no `@Data`, `@EqualsAndHashCode`, `@ToString`** on entities;
  state changes through intention-revealing methods that enforce the SRS §3.4 state machines.
- Types: `BIGINT→Long`, `INT→Integer`, `SMALLINT→Short`, money VND → `long/Long` (never double/BigDecimal),
  time → `Instant` (UTC), status columns → Java enum + `CodeEnumConverter` storing the lowercase DB code.
- `spring.jpa.open-in-view=false`, `ddl-auto=validate`. Schema changes = **new** Flyway file `V{n}__desc.sql`.
  Never edit an applied migration. Never "fix" a validate error by changing the DB to fit Hibernate — fix the mapping.
- Migrations V1–V6 are frozen at tag schema-v1; any schema change = new V7+ file + ADR.
- Authorization: role check + data-relationship check on the server for every protected endpoint
  (`docs/ai/04-security.md`). Resources of another user → **404**, not 403 (anti-enumeration, NFR-01).
- Errors: RFC 9457 `ProblemDetail` with `code` + Vietnamese `title/detail` (`docs/ai/05-api-contract.md`).
- Reference rules in code only where non-obvious: `// BR-33: pending_review courses are read-only`.
- Never log passwords, raw tokens, JWTs, secrets (NFR-08). Log `orderCode`, `transactionRef`, `userId`.

## 7. Phase 1 scope (ADR-005) — "GET thật + write phức tạp trả mẫu"
- All GET endpoints read **real MySQL data** (dev seed via Flyway `db/seed`).
- Simple writes are real (auth, CRUD course/chapter/lesson, cart, free enroll, progress, reviews, Q&A, moderation).
- Complex flows are **stubs** that return contract-correct sample responses: create order (T1), create QR,
  payment webhook, cancel order, reconciliation (UC-A18), certificate PDF, schedulers.
  Stub rules: tag `// STUB-P1:` + header `X-Edumy-Stub: true`, still validate input + check authorization,
  **never write to DB**. Full list: `docs/ai/06-phase-plan.md`.

## 8. Out of scope v1 (SRS §1.2) — never implement
Revenue share, automatic refund, coupons, subscriptions, wishlist, guest cart / "Buy now", multi-level categories,
fine-grained co-instructor permissions, quizzes, announcements, Q&A attachments, students answering other students,
account deletion / email change, livestream, AI recommendations.

## 9. Commands
```bash
docker compose up -d                 # MySQL 8.4 + Mailpit (Spring Boot also auto-starts compose.yaml)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
./mvnw test                          # unit + module + Testcontainers tests
./mvnw -Dtest=ModularityTest test    # module boundary check — run after every cross-module change
```
Swagger UI (dev): `http://localhost:8080/swagger-ui.html` · Mailpit: `http://localhost:8025`

## 10. How you (the agent) must work
1. Restate the task with UC/BR codes and the module(s) touched. If the task spans >1 module's write side, ask first.
2. Read the relevant `docs/ai/*` section. Plan in ≤10 bullets before editing for anything non-trivial.
3. Make the smallest change that satisfies the task. No drive-by refactors, no new dependencies without asking.
4. Write/adjust tests. Run `./mvnw test` (or the narrowest relevant test). **Never claim "done" without saying what ran.**
5. Finish with: changed files · UC/BR covered · tests run + result · open questions / TODOs.
Never: invent table/column names, BCrypt hashes, versions or business rules; disable tests or security to make
something pass; commit secrets; touch `db/migration` files already merged to `main`.

Commit style: Conventional Commits, scope = module, UC in footer — `feat(commerce): add cart item [UC-S01]`.
