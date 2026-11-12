This repository's agent instructions live in `AGENTS.md` at the repo root (Copilot reads it natively).
Key points if AGENTS.md is not loaded:
- Spring Boot 4.1 modular monolith (Spring Modulith) on MySQL 8.4; modules: identity, catalog, authoring, commerce, learning, notification, shared.
- Inside each module only these folders: api/, controller/, dto/request/, dto/response/, service/, mapper/, entity/, repository/ (+ optional security/, listener/, integration/, scheduler/, config/). Full spec: docs/ai/01-architecture.md (ADR-007).
- Only the owner module writes its tables; cross-module access only via `<module>.api`; no JPA associations across modules.
- Flyway owns the schema (`ddl-auto=validate`); never edit applied migrations; never invent columns — see `docs/ai/03-data-model-mysql.md`.
- Never return entities from controllers; records + MapStruct; ProblemDetail errors with Vietnamese messages.
- Every protected endpoint: role + data-relationship check; others' resources → 404.
- Phase 1: complex flows are `STUB-P1` endpoints that never write to the DB (`docs/ai/06-phase-plan.md`).
