---
applyTo: "src/main/java/com/edumy/**/api/**/*.java"
---
# Module public API rules (api/)
- api/ is the ONLY package other modules may import (@NamedInterface("api") in api/package-info.java). Keep it flat: no sub-packages.
- Allowed content: interfaces `XxxQueryApi` / `XxxAccessApi` / `XxxApi`, immutable records they accept/return, event records (past tense: `OrderPaid`).
- Forbidden: entities, repositories, Spring beans/implementations, logic, mutable classes.
- Events carry ids and minimal snapshot data only. Changing an api/ signature = tell the owners of dependent modules (docs/ai/01 §1.8–1.10).
