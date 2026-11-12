---
applyTo: "src/main/java/com/edumy/**/service/**/*.java"
---
# Service rules (service/)
- `XxxService` = writes (@Transactional on public methods); `XxxQueryService` = reads (@Transactional(readOnly = true)); `XxxApiImpl` = package-private implementation of this module's api/ interface.
- Inputs: ids, primitives or `record XxxCommand` placed next to the service. Outputs: dto/response records (for controllers) or api/ records (for other modules). Never return entities; never accept XxxRequest types or HTTP types.
- May call: own repository/, mapper/, integration/ interfaces, other modules' api/ (only those allowed in docs/ai/01 §1.8).
- Business-rule violations → BusinessException(ErrorCode.X) with a `// BR-xx` comment when non-obvious.
- Side effects that may fail independently → publish an api/ event (ApplicationEventPublisher); payment→enrollment stays synchronous (ADR-004).
- Time from the injected Clock, never Instant.now().
