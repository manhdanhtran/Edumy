# ADR-001 — Implement EDU-DDD-001 (PostgreSQL 16) on MySQL 8.4 LTS
Status: Accepted · Date: 2026-11-12

Context: DDD v1.0 targets PostgreSQL 16 (partial indexes, DEFERRABLE, PL/pgSQL, pg_trgm, TIMESTAMPTZ). The course
requires Spring Boot + MySQL Driver. MySQL 8.0 reached end of life on 2026-04-30; 8.4 is the LTS line.

Decision: MySQL 8.4 LTS in Docker. Keep every table, column, FK action and constraint name from DDD. Replace
mechanisms as in `docs/ai/03-data-model-mysql.md` §3.2–3.5: generated columns for partial uniques, composite indexes
for partial indexes, offset-based two-phase reorder, MySQL triggers with BR-coded SIGNAL messages, `title_normalized`
LIKE search, `DATETIME(6)` in UTC, binary/`'c'` comparisons in CHECKs.

Consequences: two extra generated columns (`orders.pending_student_id`, `payment_transactions.pending_order_id`).
Search is a scan (fine for year-1 volume). XAMPP/MariaDB is not supported (different generated-column/CHECK behaviour).
Hibernate validate settings that turned out necessary: _record here after first run_.
