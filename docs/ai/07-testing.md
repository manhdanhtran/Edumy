# 07 — Testing strategy

| Level | Tool | What |
|---|---|---|
| Architecture | `ApplicationModules.of(EdumyApplication.class).verify()` in `ModularityTest`; `Documenter` writes module diagrams to `target/spring-modulith-docs` | module boundaries, cycles |
| DB constraints | `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + Testcontainers `mysql:8.4` (`@ServiceConnection`) | each UNIQUE, CHECK, trigger, generated-column rule from DDD §8 (rows 1–31) — one test per rule, assert the constraint name |
| Module | `@ApplicationModuleTest` (+ `Scenario` for events) — `<Module>ModuleIT`, recipe at the end of this file | services + events of one module, other modules mocked via their `api` |
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
  course / chapter / lesson / enrollment / question / answer rows (a course marked published/hidden with `setStatus` is reset to a never-published draft by `deleteAll()` so the
  BR-19 trigger lets it go; API-created rows via `trackCourse` / `trackCategory`; call it before `TestUsers.deleteAll()`). Unit tests of access beans set the caller with
  `support/TestSecurityContext.authenticate(userId, jwtRoles...)` and mock `RoleQueryApi` for the database roles.
- `*IT` classes run via surefire in `./mvnw test` (decision in V1); if the suite gets too slow they move to failsafe/`verify`.
- Never delete or `@Disabled` a failing test to finish a task; report it.
- Transactions run READ COMMITTED (ADR-010). Lost-update / lock-order scenarios: `support/LockWaits.whileLockedThenCommit`
  (a holder transaction takes a row lock and changes data, the request under test starts, the helper waits until
  `information_schema.innodb_trx` shows `LOCK WAIT` — the test user has `PROCESS` — then lets the holder commit). Never
  `Thread.sleep`; every `Future.get` of a concurrency test has a timeout.

## How to write a MySQL IT
One `mysql:8.4` container is started per `./mvnw test` run (`support/SharedMySql`, removed by Ryuk at JVM exit) and shared
by every cached Spring context. It holds two databases: `edumy` (Flyway `db/migration`) and `edumy_seed`
(`db/migration` + `db/seed`). Put one annotation on the test class, nothing else (no `@Import`, no profile, no container):

| Annotation | Context | Database | Use for |
|---|---|---|---|
| `@MySqlJdbcTest` | `@JdbcTest`: DataSource, Flyway, `JdbcClient`; each test rolled back | `edumy` | pure schema / SQL tests (`*SchemaIT`: constraints, triggers, generated columns) |
| `@MySqlSpringBootTest` | whole application | `edumy` | tests that need the app (services, security, controllers on real MySQL) |
| `@MySqlSeedTest` | `@JdbcTest` + `db/seed` | `edumy_seed` | tests that read the dev seed (e.g. `SeedCoverageIT`) |
| `@MySqlSeedSpringBootTest` | whole application + MockMvc, `db/seed` | `edumy_seed` | GET endpoints on the dev seed (one test per GET endpoint, e.g. `AdminUserControllerIT`) — **read-only** |

Rules
- **Leave no committed data in the shared database.** Roll back (`@MySqlJdbcTest` does it per test; add `@Transactional`
  to `@MySqlSpringBootTest` tests that write), or clean up in `@AfterEach`, or use values unique per test (email/slug
  containing a UUID). Leftover rows break other ITs in the same JVM.
- **Seed data only through `@MySqlSeedTest` / `@MySqlSeedSpringBootTest`** (database `edumy_seed`). Never use the default database for tests that need
  the seed: Flyway would record V1000 there and every context without `db/seed` would fail validation
  ("applied migration not resolved locally"), turning the whole suite red.
- Choose `@MySqlJdbcTest` for schema/SQL tests, `@MySqlSpringBootTest` only when the app is needed.
- Tests with the same annotation (and no extra `@MockitoBean`, `@TestPropertySource`, `@DirtiesContext`…) reuse one cached
  context. Two contexts always exist when both kinds are used (`@SpringBootTest` and `@JdbcTest` cannot share one) plus one
  each for `@MySqlSeedTest` and `@MySqlSeedSpringBootTest`; they differ only in start-up cost, the container is shared.
  `ModularityTest` needs no context.
- Surefire runs one fork. If forks are ever parallelised, each fork starts its own container.
- Proof of the setup: `support/SharedMySqlContainerIT`, `support/SeedDatabaseIT` (same container, separate databases),
  `support/MySqlSeedSpringBootTestIT`, `support/SqlStatementCounterIT`.

### GET endpoints on the dev seed (`@MySqlSeedSpringBootTest`)
- **Read-only, never write**: the seed database is shared by every seed test of the JVM and nothing rolls it back. No
  data-changing request, no `/auth/login` (it inserts a `refresh_tokens` row); `TestUsers` / `JwtTestTokens` are
  deliberately not registered in this context. Write tests stay in `@MySqlSpringBootTest`.
- Tokens: `support/SeedUserTokens` (`@Autowired`). `bearer(userId)` = `Authorization` header value for a seed user, JWT
  `roles` = its active seed roles; `bearer(userId, RoleCode...)` sets the claim explicitly (stale/forged claims). No
  write: it marks the user's fixed `sid` active in the `identity.session-status` cache instead of inserting a
  `refresh_tokens` row; signature, expiry, issuer and the user-status check stay real (user 20 is locked → 401). Call it
  per request. Seed ids as constants: `ADMIN` 1, `INSTRUCTOR` 2 (`INSTRUCTOR_2` 3, `INSTRUCTOR_3` 4),
  `REVOKED_INSTRUCTOR` 5, `STUDENT` 6 (students 6–17), `UNVERIFIED` 18 (and 19), `LOCKED` 20.

### Counting SQL statements (no N+1): `support/SqlStatementCounter`
Registered in `@MySqlSpringBootTest` and `@MySqlSeedSpringBootTest` (inject with `@Autowired`). It wraps the application
`DataSource` (BeanPostProcessor + JDK proxies, no extra dependency) and counts every `execute*` on a JDBC statement —
JPA and `JdbcClient` alike — **on the calling thread** since `reset()` (MockMvc runs requests on the test thread, so
async work of other tests never interferes); `count()`, `statements()` (SQL texts, for assertion messages). The security
filter and access beans read 30–60 s Caffeine caches that cost a statement when cold, so: send the request once to warm
up, then `reset()`, then measure. Every list endpoint gets a test asserting the same (small, exact) count for two page
sizes, both pages full (Spring Data skips the count query when the first page is not full).

### Catalog caches in tests: `support/CatalogCaches` (P08)
The four `catalog.*` caches (docs/ai/05 §5.3) live in the shared Spring context and `catalog.course-list` has no eviction (60 s
TTL), so `CatalogCaches` (a JUnit extension inside `@MySqlSpringBootTest` and `@MySqlSeedSpringBootTest`) empties them **before
every test**. A test that reads a catalog endpoint, writes (API or fixture) and then asserts a fresh list/page calls
`CatalogCaches.clear(cacheManager)` after the write (`@Autowired CacheManager`). A statement-count test of a catalog read clears
before the measured call (a cache hit costs 0 statements). A test of an eviction listener (`CatalogCacheIT`) must NOT clear
between the warm-up and the check. Native Caffeine access: `((CaffeineCache) cacheManager.getCache(n)).getNativeCache()`
(`stats().hitCount()`, `policy()` for TTL / maximumSize — never sleep for a TTL; `getIfPresent` itself counts as a hit).

## Module tests: `<Module>ModuleIT` (`@ApplicationModuleTest`, P09-c)
One class per module in `src/test/java/com/edumy/<module>/<Module>ModuleIT.java` (`identity`, `catalog`, `authoring`,
`commerce`, `learning`, `notification`). They test the SERVICE layer and the EVENT contract of one module on the shared
MySQL container, not HTTP (HTTP, IDOR, security stay in the `*ControllerIT` / `*WriteIT` / `IdorIT` classes).

Composition (copy it, do not invent a variant):
```java
@ApplicationModuleTest(extraIncludes = "shared")   // mode STANDALONE (default): this module + shared only
@MySqlModuleTest                                   // support/: profile test, shared container, MutableClock, CapturingMailGateway,
class AuthoringModuleIT {                          //           TestUsers, CourseFixtures + the two reset extensions
    @MockitoBean RoleQueryApi roles;               // neighbours: ONLY through their api interfaces
    @MockitoBean UserQueryApi userQuery;
    ...
}
```
- `extraIncludes = "shared"` is required: `shared` is an OPEN module but Modulith does not put its beans in a STANDALONE
  context, and `ClockConfig` / `CacheConfig` / `AsyncConfig` / `EdumyProperties` live there (without it: "bean of type
  EdumyProperties not found" from `SecurityConfig`). Never replace it by `@Import` of single shared classes.
- Container: `@MySqlModuleTest` imports `TestcontainersConfiguration` = `SharedMySql.INSTANCE` (same container, database
  `edumy`, no seed). Never declare a `MySQLContainer`. Same rules as `@MySqlSpringBootTest`: leave no committed rows
  (`CourseFixtures.deleteAll()` before `TestUsers.deleteAll()` in `@AfterEach`; commerce also deletes its cart rows).
- Mocking the neighbours: every `@MockitoBean` is an interface of another module's `api/` that a bean of the tested module
  needs; list ALL of them the module's beans inject (a missing one fails the context start, not the test). Needed sets:
  authoring `RoleQueryApi` + `UserQueryApi`; learning `CourseQueryApi`, `CourseAccessApi`, `LessonQueryApi`,
  `UserQueryApi`, `RoleQueryApi`; commerce `CourseQueryApi`, `CourseAccessApi`, `EnrollmentApi`, `UserQueryApi`,
  `RoleQueryApi`; notification `UserQueryApi`, `CourseQueryApi`; identity and catalog none. The other modules' own beans
  (listeners, services) are NOT in the context, so a test can never depend on them by accident. Stub per test
  (`given(...)`), mocks are reset after every test. A mock of a `Map`-returning batch method uses `anyCollection()`.
- Test helpers that need a module bean are `@Import`ed by that class only (identity: `@Import(JwtTestTokens.class)` for
  sessions). A different composition = a different cached context (~15-25 s): keep one composition per module.
- Events: parameter `Scenario scenario` — `scenario.stimulate(() -> service.approve(...)).andWaitForEventOfType(E.class)
  .matching(e -> ...).toArriveAndVerify(e -> assert payload)` for events a service publishes; `scenario.publish(event)
  .andWaitForStateChange(() -> observable, predicate).andVerify(...)` to drive a listener (publish runs in a transaction, so
  `AFTER_COMMIT` / `@Async` listeners fire). The supplier of `andWaitForStateChange` must NOT return `null` (treated as "not
  yet": use `() -> cache.get(k) == null` with `Boolean::booleanValue`, or a list). "No event on a refused operation":
  parameter `PublishedEvents events`, call the service, `assertThat(events.ofType(E.class)).isEmpty()`.
- Mails: `CapturingMailGateway` (`sentTo`, `failedTo`, `failNextSendsTo`) + `OutputCaptureExtension` for the NFR-08 log line.
- Modulith docs: `ModularityTest#writesDocumentation` writes `target/spring-modulith-docs`; a copy is committed in
  `docs/modulith/` (see its README) and refreshed after any module / api change.

## Commerce / payment tests (P11)
- `support/CommerceFixtures`: carts, orders (builder: lines, `createdAt`, `paid()`, `cancelled(reason)`), attempts
  (`payment`, `pendingPayment`), column readers that accept `null`; `deleteAll()` removes everything of the tracked
  students (orders, attempts, enrollments the API created, carts) — call it before `CourseFixtures.deleteAll()`.
- The fake provider (`FakeQrPaymentGateway`) is scripted per test (`simulatePaid`, `simulateFailed`,
  `setUnavailable`, `queryCount`) and reset after every test with `support/PaymentTestHooks` (in-transaction `OrderPaid`
  recorder, `failNextOrderPaid()` = NFR-04 rollback probe) by `PaymentTestHooksResetExtension` (both in
  `@MySqlSpringBootTest`).
- Races: `support/Concurrently.run / times` (all tasks start together, 30 s cap); lock interleavings:
  `support/LockWaits` (R1: a request that waited for a lock must see the committed row).
- Never move the `MutableClock` past the access-token TTL (15 min) in an HTTP test: the token expires (401). Move the
  data instead (e.g. `qr_expires_at` in the past).
- Timers are off in the test profile; tests call `CommerceExpiryService` / `TokenCleanupService` directly, the switch
  itself is `SchedulerBeansTest` (`ApplicationContextRunner`).
- VS Code writes its own classes into `target/`; "Unresolved compilation problems", "Failed to introspect class" or
  "ClassSelector resolution failed" mean a stale class: run narrow tests with `./mvnw clean test -Dtest=…` as well.