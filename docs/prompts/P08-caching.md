# P08 — Caching

## Prompt
```text
Task: configure Spring Cache with Caffeine exactly per docs/ai/05-api-contract.md §5.3.
- @EnableCaching in shared config; CacheManagerCustomizer<CaffeineCacheManager> registering each cache with its spec
  (maximumSize sensible, expireAfterWrite per table, recordStats).
- CacheConfig lives in shared/config. @Cacheable on catalog service/*QueryService / RoleQueryApi / session-status lookups only; values must be immutable records.
- Eviction: @TransactionalEventListener(phase = AFTER_COMMIT) (or @ApplicationModuleListener) in catalog reacting to
  authoring/learning events; identity evicts its own caches synchronously when revoking/locking.
- Do NOT cache anything in commerce or per-user learning data.
- Tests: second call served from cache (verify repository/JdbcClient invoked once); event → evicted; lock user →
  next request 401 without waiting for TTL.
- Expose cache metrics through actuator in dev only.
```
