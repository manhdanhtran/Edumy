---
applyTo: "src/test/**/*.java"
---
# Test rules
- MySQL behaviour is tested with Testcontainers mysql:8.4 (@ServiceConnection). No H2.
- ModularityTest must stay green. Add an IDOR test (two users) for every new protected endpoint.
- Test names: scenario_condition_expected_BRxx. Use an injected Clock for time-based rules.
- Never @Disabled or delete a failing test to finish a task — report it instead.
