# P05 — Read endpoints with real data (mỗi module một phiên)

## Prompt
```text
Task: implement all GET endpoints of module {{MODULE}} listed in docs/ai/05-api-contract.md §5.4 (status R).
- Files: controller/ (controllers), dto/request/ (query-param records if any), dto/response/ (records), mapper/ (MapStruct),
  service/ (*QueryService, @Transactional(readOnly = true)), repository/ (query methods/projections) — per docs/ai/01 §1.5.
- catalog only: JdbcClient SQL in catalog/repository/*CatalogRepository per ADR-003 (published courses only, visible reviews only,
  accent-insensitive search on title_normalized with VietnameseNormalizer applied to q, 4 sorts, 20/page,
  "no result → newest courses" hint per UC-G02).
- Authorization per docs/ai/04 §4.3: own resources scoped by current user id; not found or not yours → 404.
- Derived values computed on read: isFree, progressPercent (floor), avg rating (visible reviews), instructorReplied.
- Avoid N+1: use fetch joins / projections; show the SQL count for list endpoints in a test (Hibernate statistics or
  a datasource-proxy counter).
- springdoc annotations with UC codes. Web-slice tests for 200/401/403/404 and one Testcontainers test per endpoint
  that reads the dev seed.
List the endpoints you will implement before coding.
```
