# P06 — Simple write endpoints (mỗi nhóm UC một phiên)

## Prompt
```text
Task: implement the write endpoints for {{UC_LIST}} in module {{MODULE}} (status R in docs/ai/05 §5.4).
For each UC first write a 3-line spec: preconditions (SRS §4.2/4.3), state transition (§3.4), BR/D rules.
Then implement in the folders of docs/ai/01 §1.5: dto/request record with Bean Validation (SRS limits), controller method
with @PreAuthorize using the module's security/ access bean, service/ method with @Transactional, entity/ method for the
transition, api/ events for side effects (email, cache eviction) per docs/ai/01 §1.10.
Must-handle rules for this module: {{RULES}}   e.g. authoring: BR-06, BR-07 checklist, BR-08, BR-19, BR-33, D-14, D-18, D-21
Constraint violations from MySQL must surface as the mapped ErrorCode (ConstraintViolationTranslator), not 500.
Tests: happy path + each rule violation + IDOR. Report UC → endpoint → tests.
```
