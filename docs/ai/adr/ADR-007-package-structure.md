# ADR-007 — Package structure inside modules: controller / dto / service / mapper / entity / repository
Status: Accepted · Date: 2026-11-12 · Supersedes the `web / application / domain / infra` naming of the first kit draft

Context: the first draft used DDD/hexagonal names (`web`, `application`, `domain`, `infra`). Spring Boot's reference
documentation does not prescribe sub-package names (its own example puts Controller/Service/Repository side by side in a
feature package) and recommends Spring Modulith for domain-based structure. Spring Modulith only checks module base
packages and named interfaces. The team and the course ("tầng Entity & Repository", "viết Controller") use the classic
Spring vocabulary.

Decision:
- Root stays package-by-module (7 modules, unchanged).
- Inside each module: `api/` (unchanged, @NamedInterface), `controller/`, `dto/request/`, `dto/response/`, `service/`,
  `mapper/`, `entity/`, `repository/`, plus optional `security/`, `listener/`, `integration/`, `scheduler/`, `config/`.
- `api/` is flat; records that other modules use live in `api/`, never in `dto/`.
- Full specification, per-module trees and naming rules: `docs/ai/01-architecture.md` (authoritative).

Alternatives rejected: `domain/` grouping entity + repository (mixed vocabulary); `infra/` or `client/` for external
adapters (`integration/` describes intent better); flat `dto/` (inputs and outputs mixed in one folder; the team prefers seeing
request vs response from the tree).

Consequences: Java types in sub-folders must be `public`; module boundaries are guaranteed by `ModularityTest`
instead of Java visibility. Scoped AI rules target the new folders (`**/entity/**`, `**/repository/**`, `**/service/**`,
`**/controller/**`, `**/dto/**`, `**/api/**`).
