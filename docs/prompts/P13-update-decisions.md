# P13 — Ghi nhận quyết định mới

```text
A decision changed: {{DECISION}} (who approved, why).
1. Create docs/ai/adr/ADR-{{NNN}}-{{slug}}.md (Status, Date, Context, Decision, Alternatives, Consequences).
2. Update only the affected lines in AGENTS.md and docs/ai/*.md; keep AGENTS.md under ~120 lines.
3. If scoped rules (.cursor/rules, .github/instructions) mention the old decision, update both copies identically.
4. List code that now violates the new decision (grep) — do not change code in this task.
```
