# Schema checklist — EDU-DDD-001 → MySQL 8.4 (P01)

One section per migration file. Every constraint / index / trigger / rule of the DDD for the tables of that file, how
it is implemented, the line in the migration, and a status:

| Status | Meaning |
|---|---|
| OK | Ported 1:1 (same name, same semantics; only the MySQL mechanism differs per docs/ai/03 §3.1–3.3) |
| PARTIAL | Ported with a documented loss of strength or shape (e.g. partial index → composite index, GIN → B-tree) |
| DERIVED | Not in the DDD constraint list, but required by a rule the DDD states elsewhere (docs/ai/03 "DDD inconsistencies") |
| APP-LEVEL | The DDD itself assigns the rule to the application (§8 "Ứng dụng"); nothing in SQL |

`PK` names are written for readability only (MySQL always names the primary key `PRIMARY`).
`updated_at` triggers of the DDD are replaced by `ON UPDATE CURRENT_TIMESTAMP(6)` on every `updated_at` column (OK).

## V1__identity.sql (users, roles, user_roles, user_tokens; refresh_tokens ADR-002)

| DDD constraint/index (source §) | MySQL implementation | line | status |
|---|---|---|---|
| pk_users (§5.1) | `PRIMARY KEY (id)` | 21 | OK |
| users.email UQ (§5.1, §8 #1) | `uq_users_email UNIQUE (email)` | 22 | OK |
| ck_users_status (§5.1) | `CAST(status AS BINARY) IN ('active','locked')` | 23 | OK |
| ck_users_email_normalized (§5.1, §8 #1) | `CAST(email AS BINARY) = CAST(LOWER(TRIM(email)) AS BINARY)` | 24 | OK |
| ck_users_full_name (§5.1) | `CHAR_LENGTH(TRIM(full_name)) > 0` | 25 | OK |
| pk_roles (§5.2) | `PRIMARY KEY (id)` | 34 | OK |
| roles.code UQ (§5.2) | `uq_roles_code UNIQUE (code)` | 35 | OK |
| roles.code values (§7) | `ck_roles_code CAST(code AS BINARY) IN ('student','instructor','admin')` | 37 | DERIVED |
| pk_user_roles (§5.3) | `PRIMARY KEY (id)` | 49 | OK |
| uq_user_roles_user_role (§5.3, §8 #2) | `UNIQUE (user_id, role_id)`; also index of fk #1 | 51 | OK |
| FK #1 user_roles.user_id → users RESTRICT (§6) | `fk_user_roles_user_id` | 52 | OK |
| FK #2 user_roles.role_id → roles RESTRICT (§6) | `fk_user_roles_role_id` | 53 | OK |
| FK #3 user_roles.assigned_by → users RESTRICT (§6) | `fk_user_roles_assigned_by` | 54 | OK |
| ix_user_roles_role (§9) | `(role_id)` | 55 | OK |
| ix_user_roles_assigned_by (§9) | `(assigned_by)` | 56 | OK |
| pk_user_tokens (§5.4) | `PRIMARY KEY (id)` | 68 | OK |
| user_tokens.token_hash UQ (§5.4) | `uq_user_tokens_token_hash UNIQUE (token_hash)` | 69 | OK |
| FK #4 user_tokens.user_id → users CASCADE (§6) | `fk_user_tokens_user_id` | 71 | OK |
| ck_user_tokens_type (§5.4) | `CAST(token_type AS BINARY) IN (...)` | 72 | OK |
| ck_user_tokens_expiry (§5.4) | `expires_at > created_at` | 73 | OK |
| ck_user_tokens_hash_hex (§5.4) | `REGEXP_LIKE(token_hash, '^[0-9a-f]{64}$', 'c')` | 74 | OK |
| ix_user_tokens_active (§5.4, §9) `(user_id, token_type) WHERE used_at IS NULL` | composite `(user_id, token_type, used_at)`; also index of fk #4 | 77 | PARTIAL |
| refresh_tokens table (ADR-002, docs/ai/04 §4.1) | table + `uq_refresh_tokens_token_hash`, `fk_refresh_tokens_user_id` CASCADE, `ck_refresh_tokens_hash_hex`, `ck_refresh_tokens_reason`, `ck_refresh_tokens_revoked_pair`, `ix_refresh_tokens_user`, `ix_refresh_tokens_family` | 81–103 | OK (ADR) |
| §8 #3 token single use, not expired (BR-34) | conditional atomic UPDATE in the app (T7) | — | APP-LEVEL |
| §8 #4 at least one active admin; admin cannot lock self (BR-36) | row lock in the app transaction | — | APP-LEVEL |
