# P11 — Debug có kỷ luật

```text
Bug: {{SYMPTOM}} (endpoint/test, request, actual vs expected, stack trace/log excerpt).
Process: 1) Reproduce with a failing test first (Testcontainers if SQL involved). 2) State 2–3 hypotheses ranked,
with the evidence that would confirm each. 3) Check the cheapest hypothesis first; show the evidence.
4) Fix the root cause with the smallest change; do not weaken constraints, validation, security or tests.
5) Run the failing test + the module's tests. Report root cause, fix, tests run.
If the fix needs a schema change or touches another module's write side, stop and ask.
```
