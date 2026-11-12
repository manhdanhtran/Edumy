# P07 — Stub endpoints STUB-P1

## Prompt
```text
Task: implement the phase-1 stub endpoints listed in docs/ai/06-phase-plan.md for module {{MODULE}}.
Each stub: final URL/method, final request record with full validation, final response record,
real @PreAuthorize + ownership check, deterministic sample built from real rows when cheap (e.g. POST /orders
returns a preview computed from the current cart: items, unit prices, total — WITHOUT inserting),
`StubSupport.mark(response)` (X-Edumy-Stub: true), comment `// STUB-P1: replace with <Service>.<method> — UC-xx, BR-xx`.
Forbidden: any repository save/update/delete, external calls, @Transactional write methods.
Add a test per stub asserting the header, the response shape, 401/403/404 behaviour, and that row counts
of the related tables are unchanged after the call.
Finally print `grep -rn "STUB-P1" src/main` output.
```
