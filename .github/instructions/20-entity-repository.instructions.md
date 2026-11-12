---
applyTo: "src/main/java/com/edumy/**/entity/**/*.java,src/main/java/com/edumy/**/repository/**/*.java"
---
# Entity & repository rules (entity/, repository/)
- Folders: entities + status enums + converters in `<module>/entity/`; Spring Data interfaces in `<module>/repository/` (docs/ai/01 §1.3).
- Map exactly the DDD columns; names via @Table/@Column. Do not map generated columns (pending_student_id, pending_order_id).
- Lombok: @Getter + @NoArgsConstructor(access = PROTECTED). No @Data/@EqualsAndHashCode/@ToString/@Builder on entities.
- equals/hashCode by id (Hibernate-proxy safe); toString without associations.
- Inside a module: @ManyToOne(fetch = LAZY). Across modules: plain Long xxxId. Never reference another module's entity.
- Types: BIGINT→Long, INT→Integer, SMALLINT→Short, money→Long VND, time→Instant, status→enum + CodeEnumConverter (lowercase DB code).
- State changes through intention-revealing entity methods enforcing SRS §3.4 transitions; invalid transition → BusinessException.
- Entities never call services or repositories. Repositories hold no business rules and never write another module's tables.
- Reads: interface/record projections; @Lock(PESSIMISTIC_WRITE) where DDD transactions lock rows. catalog repositories use JdbcClient SELECT only.
- If ddl-auto=validate fails, fix the mapping (docs/ai/03 §3.8), never the migration.
