# ADR-003 — Catalog reads other modules' tables through read-only SQL
Status: Accepted · Date: 2026-11-12

Context: subsystem 02 owns no data (SRS §3.1) but its listing joins courses, users, categories and reviews.
Decision: `catalog.repository.*CatalogRepository` classes use `JdbcClient` with hand-written SELECTs returning projection records. No writes,
no entity imports, no JPA in catalog. Visibility rules (only `published`, D-08; only `visible` reviews) are applied in SQL.
Consequences: catalog SQL must be updated when an owner module renames a column (none expected in v1). Cache
eviction relies on owner-module events.
