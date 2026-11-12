-- =====================================================================================================================
-- V1 — module identity: users, roles, user_roles, user_tokens (EDU-DDD-001 §5.1–5.4, §6 FK #1–4, §8, §9)
--      + refresh_tokens (ADR-002, DDL from docs/ai/04-security.md §4.1).
-- MySQL port rules: docs/ai/03-data-model-mysql.md §3.1–3.3 (ADR-001).
--   TIMESTAMPTZ -> DATETIME(6) stored in UTC; updated_at trigger -> ON UPDATE CURRENT_TIMESTAMP(6);
--   case/accent-sensitive CHECKs via CAST(... AS BINARY) or REGEXP_LIKE(..., 'c') (collation trap);
--   partial index -> composite index; MySQL ignores PRIMARY KEY names (always PRIMARY).
-- No FK of these tables points to a table of another module.
-- =====================================================================================================================

-- 5.1 users — Tài khoản
CREATE TABLE users (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    -- utf8mb4_bin: uq_users_email and login lookups compare exact characters (é@x.com <> e@x.com), like PostgreSQL;
    -- ck_users_email_normalized keeps the application-normalized form (lower-case, trimmed). Other columns stay ai_ci.
    email             VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    password_hash     VARCHAR(255) NOT NULL,
    full_name         VARCHAR(100) NOT NULL,
    is_email_verified BOOLEAN      NOT NULL DEFAULT FALSE,
    status            VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_status CHECK (CAST(status AS BINARY) IN ('active', 'locked')),
    CONSTRAINT ck_users_email_normalized CHECK (CAST(email AS BINARY) = CAST(LOWER(TRIM(email)) AS BINARY)),
    CONSTRAINT ck_users_full_name CHECK (CHAR_LENGTH(TRIM(full_name)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5.2 roles — Vai trò (rows inserted by V6__reference_data.sql)
CREATE TABLE roles (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       VARCHAR(30)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uq_roles_code UNIQUE (code),
    -- DERIVED (DDD §7): roles.code values are enforced by CHECK; §5.2 omits the constraint.
    CONSTRAINT ck_roles_code CHECK (CAST(code AS BINARY) IN ('student', 'instructor', 'admin'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5.3 user_roles — Phân quyền (never deleted; revoke = is_active = false, D-04)
CREATE TABLE user_roles (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    role_id     BIGINT      NOT NULL,
    assigned_by BIGINT      NULL,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_user_roles PRIMARY KEY (id),
    -- also serves as the index of fk_user_roles_user_id (user_id leads it)
    CONSTRAINT uq_user_roles_user_role UNIQUE (user_id, role_id),
    CONSTRAINT fk_user_roles_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_roles_role_id FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_roles_assigned_by FOREIGN KEY (assigned_by) REFERENCES users (id) ON DELETE RESTRICT,
    INDEX ix_user_roles_role (role_id),
    INDEX ix_user_roles_assigned_by (assigned_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5.4 user_tokens — Mã xác thực (only SHA-256 hex of the code; single use, BR-34)
CREATE TABLE user_tokens (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    token_hash CHAR(64)    NOT NULL,
    token_type VARCHAR(30) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at    DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_user_tokens PRIMARY KEY (id),
    CONSTRAINT uq_user_tokens_token_hash UNIQUE (token_hash),
    -- CASCADE: no CHECK may reference user_id (MySQL error 3823)
    CONSTRAINT fk_user_tokens_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_tokens_type CHECK (CAST(token_type AS BINARY) IN ('email_verification', 'password_reset')),
    CONSTRAINT ck_user_tokens_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_user_tokens_hash_hex CHECK (REGEXP_LIKE(token_hash, '^[0-9a-f]{64}$', 'c')),
    -- DDD: (user_id, token_type) WHERE used_at IS NULL -> composite; all three are equality predicates.
    -- Also serves as the index of fk_user_tokens_user_id.
    INDEX ix_user_tokens_active (user_id, token_type, used_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 4b refresh_tokens — ADR-002 (not in DDD; docs/ai/04-security.md §4.1)
CREATE TABLE refresh_tokens (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    family_id     CHAR(36)     NOT NULL,  -- = JWT sid, one per login session
    token_hash    CHAR(64)     NOT NULL,
    expires_at    DATETIME(6)  NOT NULL,
    revoked_at    DATETIME(6)  NULL,
    revoke_reason VARCHAR(30)  NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    user_agent    VARCHAR(255) NULL,
    ip_address    VARCHAR(45)  NULL,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    -- CASCADE: no CHECK may reference user_id (MySQL error 3823)
    CONSTRAINT fk_refresh_tokens_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_refresh_tokens_hash_hex CHECK (REGEXP_LIKE(token_hash, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_refresh_tokens_reason CHECK (revoke_reason IS NULL OR CAST(revoke_reason AS BINARY) IN
        ('logout', 'rotated', 'reuse_detected', 'password_changed', 'password_reset', 'account_locked',
         'expired_cleanup')),
    CONSTRAINT ck_refresh_tokens_revoked_pair CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL)),
    -- also serves as the index of fk_refresh_tokens_user_id
    INDEX ix_refresh_tokens_user (user_id, revoked_at),
    -- session validity check: WHERE family_id = ? AND revoked_at IS NULL (docs/ai/04 §4.1)
    INDEX ix_refresh_tokens_family (family_id, revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
