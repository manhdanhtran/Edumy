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
- One container per JVM: `support/SharedMySql` (singleton, see "How to write a MySQL IT"). Never declare another `MySQLContainer`.
- Test names: `methodOrScenario_condition_expected` and mention the rule: `addToCart_ownedCourse_rejected_BR10`.
- Time-dependent rules (QR 15 min, token 24 h/30 min, order 24 h) use an injected `java.time.Clock` bean; tests use a
  fixed/mutable clock. Never call `Instant.now()` directly in entity/ or service/ code. In `@MySqlSpringBootTest` the
  application Clock is `support/MutableClock` (`advance`, `setInstant`); it is reset to the real time after every test.
  Only application time moves (not MySQL `CURRENT_TIMESTAMP`, not the Spring Cache Caffeine expiry); the two abuse
  limiters (`LoginAttemptLimiter`, `CertificateVerifyRateLimiter`) read the `Clock`, so it moves their expiry and
  refill too. Their state lives in the shared context: use a UUID email / random `remoteAddr` per test. Unit tests
  that need a clock standing still use `support/ManualClock`.
- Published events in `@MySqlSpringBootTest`: put `@RecordApplicationEvents` on the class and inject
  `ApplicationEvents` (Spring test support, same cached context); events published in the request thread of MockMvc
  are recorded.
- Mails in `@MySqlSpringBootTest`: the `MailGateway` is `support/CapturingMailGateway` (`@Primary`, cleared after every
  test). Mails are sent async after commit, so wait with `awaitMail(to, template)` / `awaitFailuresConsumed(to)`
  (Awaitility, 10 s) and filter by the UUID-unique recipient; `failNextSendsTo(to, n)` simulates SMTP failures.
- Access beans / IDOR (NFR-01): until the real controllers exist, `support/AccessProbeController` (registered only by
  the `@Import` of `@MySqlSpringBootTest`, `@TestComponent` + `@Profile("test")`) exposes `GET /api/v1/test-probe/...`
  guarded by each access bean. `support/IdorAssertions.assertSameNotFound(mvc, othersResource, missing)` asserts both
  are 404 `RESOURCE_NOT_FOUND` with bodies equal except `traceId` / `instance`. `support/CourseFixtures` inserts committed
  course / chapter / lesson / enrollment / question / answer rows (draft courses, so BR-19 lets `deleteAll()` remove
  them; call it before `TestUsers.deleteAll()`). Unit tests of access beans set the caller with
  `support/TestSecurityContext.authenticate(userId, jwtRoles...)` and mock `RoleQueryApi` for the database roles.
- `*IT` classes run via surefire in `./mvnw test` (decision in V1); if the suite gets too slow they move to failsafe/`verify`.
- Never delete or `@Disabled` a failing test to finish a task; report it.

## How to write a MySQL IT
One `mysql:8.4` container is started per `./mvnw test` run (`support/SharedMySql`, removed by Ryuk at JVM exit) and shared
by every cached Spring context. It holds two databases: `edumy` (Flyway `db/migration`) and `edumy_seed`
(`db/migration` + `db/seed`). Put one annotation on the test class, nothing else (no `@Import`, no profile, no container):

| Annotation | Context | Database | Use for |
|---|---|---|---|
| `@MySqlJdbcTest` | `@JdbcTest`: DataSource, Flyway, `JdbcClient`; each test rolled back | `edumy` | pure schema / SQL tests (`*SchemaIT`: constraints, triggers, generated columns) |
| `@MySqlSpringBootTest` | whole application | `edumy` | tests that need the app (services, security, controllers on real MySQL) |
| `@MySqlSeedTest` | `@JdbcTest` + `db/seed` | `edumy_seed` | tests that read the dev seed (e.g. `SeedCoverageIT`) |

Rules
- **Leave no committed data in the shared database.** Roll back (`@MySqlJdbcTest` does it per test; add `@Transactional`
  to `@MySqlSpringBootTest` tests that write), or clean up in `@AfterEach`, or use values unique per test (email/slug
  containing a UUID). Leftover rows break other ITs in the same JVM.
- **Seed data only through `@MySqlSeedTest`** (database `edumy_seed`). Never use the default database for tests that need
  the seed: Flyway would record V1000 there and every context without `db/seed` would fail validation
  ("applied migration not resolved locally"), turning the whole suite red.
- Choose `@MySqlJdbcTest` for schema/SQL tests, `@MySqlSpringBootTest` only when the app is needed.
- Tests with the same annotation (and no extra `@MockitoBean`, `@TestPropertySource`, `@DirtiesContext`…) reuse one cached
  context. Two contexts always exist when both kinds are used (`@SpringBootTest` and `@JdbcTest` cannot share one) plus one
  for `@MySqlSeedTest`; they differ only in start-up cost, the container is shared. `ModularityTest` needs no context.
- Surefire runs one fork. If forks are ever parallelised, each fork starts its own container.
- Proof of the setup: `support/SharedMySqlContainerIT`, `support/SeedDatabaseIT` (same container, separate databases).
