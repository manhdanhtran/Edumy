# Spring Modulith module documentation (generated)

These files (`all-docs.adoc`, `components.puml`, `module-*.adoc`, `module-*.puml`) are written by `ModularityTest#writesDocumentation`
to `target/spring-modulith-docs`; this folder holds a committed copy. Do not edit them by hand.
Regenerate with `./mvnw -Dtest=ModularityTest test`, then `cp target/spring-modulith-docs/* docs/modulith/`.
Regenerate and commit after ANY module or `api/` change (new dependency, `@NamedInterface`, event, `*Api` bean reference).
The dependency arrows of `components.puml` must stay inside docs/ai/01-architecture.md §1.8 (acyclic); P09-c check: they do.
