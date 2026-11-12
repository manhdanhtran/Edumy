# P03 — Entities & repositories (mỗi module một phiên)

## Prompt
```text
Task: create JPA entities, enums and Spring Data repositories for module {{MODULE}}, tables {{TABLES}}.
Read docs/ai/03-data-model-mysql.md (§3.6, §3.8) and docs/ai/01 §1.3, §1.5, §1.7.

- Folders: entities + enums in com.edumy.{{MODULE}}.entity, repositories in com.edumy.{{MODULE}}.repository,
  class names exactly as listed in docs/ai/01 §1.5. Entities map exactly the columns in db/migration (source of truth for names);
  do not map generated columns.
- Status/type columns → enums implementing CodeEnum with the lowercase DB code + autoApply converters.
- Relations inside this module: @ManyToOne(fetch = LAZY) owning side only; add @OneToMany only if a use case in
  docs/ai/05-api-contract.md needs it. References to other modules: Long ids.
- Add entity methods for the state transitions of SRS §3.4 that this module owns (e.g. Course.submitForReview(clock),
  approve(adminId, clock), reject(adminId, note, clock) …) with guard clauses throwing BusinessException.
  Phase 1 only needs the transitions used by real endpoints in docs/ai/06; still write them all, unit-tested.
- Repositories: derived queries / @Query with projections needed by the GET endpoints of this module (list them first).
- Unit tests for every transition (allowed + rejected). Then start the app (profile dev) to prove ddl-auto=validate passes;
  if it fails, apply docs/ai/03 §3.8 and report which setting was needed so ADR-001 can be updated.
Report: entities, enums, repository methods, validate result.
```
