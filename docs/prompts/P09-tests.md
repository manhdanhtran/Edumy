# P09 — Constraint, module and IDOR test suite

## Prompt
```text
Task: build the test suites described in docs/ai/07-testing.md.
1. ConstraintsIT (Testcontainers mysql:8.4): one test per row of EDU-DDD-001 §8 that says "CSDL" (UNIQUE, CHECK,
   trigger, generated-column uniques). Insert a violating row via JdbcClient and assert the exception message contains
   the exact constraint/trigger name or BR code. Also one valid-row test per table.
2. @ApplicationModuleTest per module for its services/events (use Scenario for published events).
3. IdorIT: for every protected endpoint with a path id (orders, courses, chapters, lessons, enrollments, questions,
   answers, reviews, certificates) user A's id requested by user B → 404 and no data in body.
4. Keep ModularityTest green; regenerate module docs.
Report a coverage table: DDD §8 row → test name; endpoint → IDOR test.
```
