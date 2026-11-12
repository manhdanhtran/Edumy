# 07 — Testing strategy

| Level | Tool | What |
|---|---|---|
| Architecture | `ApplicationModules.of(EdumyApplication.class).verify()` in `ModularityTest`; `Documenter` writes module diagrams to `target/spring-modulith-docs` | module boundaries, cycles |
| DB constraints | `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + Testcontainers `mysql:8.4` (`@ServiceConnection`) | each UNIQUE, CHECK, trigger, generated-column rule from DDD §8 (rows 1–31) — one test per rule, assert the constraint name |
| Module | `@ApplicationModuleTest` (+ `Scenario` for events) | services + events of one module, other modules mocked via their `api` |
| Web slice | `@WebMvcTest` + `spring-boot-starter-webmvc-test` + `spring-security-test` | validation, status codes, ProblemDetail shape, `@PreAuthorize` |
| Security / IDOR (NFR-01) | `@SpringBootTest` + Testcontainers + two seeded users | swap orderCode / courseId / enrollmentId / questionId between users → 404, never data |
| Acceptance | `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `RestClient` | AC-01…AC-37 Given/When/Then as they get implemented |

Rules
- Testcontainers is mandatory for anything touching SQL: H2 cannot run MySQL triggers, generated columns or
  `REGEXP_LIKE` with match types, so H2 tests give false confidence. Do not add H2.
- Reuse one container per JVM (`@ImportTestcontainers` / static container + reuse) to keep the suite fast.
- Test names: `methodOrScenario_condition_expected` and mention the rule: `addToCart_ownedCourse_rejected_BR10`.
- Time-dependent rules (QR 15 min, token 24 h/30 min, order 24 h) use an injected `java.time.Clock` bean; tests use a
  fixed/mutable clock. Never call `Instant.now()` directly in entity/ or service/ code.
- Never delete or `@Disabled` a failing test to finish a task; report it.
