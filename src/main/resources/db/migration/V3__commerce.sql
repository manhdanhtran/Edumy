-- =====================================================================================================================
-- V3 — module commerce: carts, cart_items, orders, order_items, payment_transactions
--      (EDU-DDD-001 §5.10–5.14, §6 FK #13–20, §8 rows 14–21, §9).
-- MySQL port rules: docs/ai/03-data-model-mysql.md §3.1–3.3 (ADR-001).
--   TIMESTAMPTZ -> DATETIME(6) stored in UTC; updated_at trigger -> ON UPDATE CURRENT_TIMESTAMP(6);
--   enum-like / state CHECKs via CAST(... AS BINARY) (collation trap);
--   partial UNIQUE index -> STORED generated column + UNIQUE (§3.3: orders.pending_student_id,
--   payment_transactions.pending_order_id; NULLs never collide); partial index -> composite index (equality columns
--   first). The two generated columns are the only additions to the DDD (ADR-001); entities do not map them.
-- FKs to users and courses point to V1/V2.
-- CASCADE FK (cart_items.cart_id): no CHECK references this column (MySQL error 3823). The base columns of the
-- generated columns (orders.student_id, payment_transactions.order_id) keep RESTRICT FKs, as MySQL requires.
-- =====================================================================================================================

-- 5.10 carts — Giỏ hàng (at most one per account; created with the first item)
CREATE TABLE carts (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    student_id BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_carts PRIMARY KEY (id),
    -- name given by DDD §8 row 14 (BR-22); also serves as the index of fk_carts_student_id
    CONSTRAINT uq_carts_student UNIQUE (student_id),
    CONSTRAINT fk_carts_student_id FOREIGN KEY (student_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5.11 cart_items — Mục giỏ hàng (removed once the course is bought)
-- BR-10 / BR-32 (course already owned, own course) are checked by the application (DDD §8 row 15).
CREATE TABLE cart_items (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    cart_id   BIGINT      NOT NULL,
    course_id BIGINT      NOT NULL,
    added_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_cart_items PRIMARY KEY (id),
    -- BR-11; also serves as the index of fk_cart_items_cart_id
    CONSTRAINT uq_cart_items_cart_course UNIQUE (cart_id, course_id),
    -- CASCADE: no CHECK may reference cart_id (MySQL error 3823)
    CONSTRAINT fk_cart_items_cart_id FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_items_course_id FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE RESTRICT,
    -- also serves as the index of fk_cart_items_course_id
    INDEX ix_cart_items_course (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5.12 orders — Đơn hàng (at most one pending order per student; total_amount is a snapshot)
-- BR-27 "at least one order line, total = sum of unit prices" is enforced in the T1 transaction (DDD §8 row 18, §6 #17).
CREATE TABLE orders (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    student_id         BIGINT      NOT NULL,
    order_code         VARCHAR(30) NOT NULL,
    total_amount       BIGINT      NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'pending',
    cancel_reason      VARCHAR(20) NULL,
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    paid_at            DATETIME(6) NULL,
    cancelled_at       DATETIME(6) NULL,
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    -- ADR-001 (docs/ai/03 §3.3): replaces the partial UNIQUE index uq_orders_one_pending (student_id) WHERE pending
    pending_student_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'pending' THEN student_id END) STORED,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT uq_orders_order_code UNIQUE (order_code),
    -- BR-29: at most one pending order per student (NULL for paid / cancelled rows never collides)
    CONSTRAINT uq_orders_one_pending UNIQUE (pending_student_id),
    CONSTRAINT fk_orders_student_id FOREIGN KEY (student_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_orders_total CHECK (total_amount > 0),
    CONSTRAINT ck_orders_status CHECK (CAST(status AS BINARY) IN ('pending', 'paid', 'cancelled')),
    CONSTRAINT ck_orders_cancel_reason CHECK (cancel_reason IS NULL
        OR CAST(cancel_reason AS BINARY) IN ('user', 'replaced', 'expired')),
    -- D-25; a late-paid order keeps cancelled_at / cancel_reason (DDD §7)
    CONSTRAINT ck_orders_state CHECK (
        (CAST(status AS BINARY) = 'pending' AND paid_at IS NULL AND cancelled_at IS NULL)
        OR (CAST(status AS BINARY) = 'paid' AND paid_at IS NOT NULL)
        OR (CAST(status AS BINARY) = 'cancelled' AND cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL
            AND paid_at IS NULL)),
    -- UC-S06 order history, newest first; also serves as the index of fk_orders_student_id
    INDEX ix_orders_student_created (student_id, created_at DESC),
    -- DDD: (created_at) WHERE status = 'pending' -> composite, equality column first (UC-S16 expiry timer)
    INDEX ix_orders_pending_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5.13 order_items — Dòng đơn hàng (unit_price is the price locked when the order was created)
CREATE TABLE order_items (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    order_id   BIGINT NOT NULL,
    course_id  BIGINT NOT NULL,
    unit_price BIGINT NOT NULL,
    CONSTRAINT pk_order_items PRIMARY KEY (id),
    -- also serves as the index of fk_order_items_order_id
    CONSTRAINT uq_order_items_order_course UNIQUE (order_id, course_id),
    CONSTRAINT fk_order_items_order_id FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_items_course_id FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE RESTRICT,
    CONSTRAINT ck_order_items_price CHECK (unit_price > 0),
    -- also serves as the index of fk_order_items_course_id
    INDEX ix_order_items_course (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5.14 payment_transactions — Giao dịch thanh toán (one row per QR; at most one pending per order; reconciliation)
CREATE TABLE payment_transactions (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    order_id         BIGINT       NOT NULL,
    transaction_ref  VARCHAR(100) NOT NULL,
    amount           BIGINT       NOT NULL,
    qr_data          TEXT         NOT NULL,
    qr_expires_at    DATETIME(6)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'pending',
    confirmed_at     DATETIME(6)  NULL,
    handled_by       BIGINT       NULL,
    handled_action   VARCHAR(20)  NULL,
    handled_note     TEXT         NULL,
    handled_at       DATETIME(6)  NULL,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    -- ADR-001 (docs/ai/03 §3.3): replaces the partial UNIQUE index uq_payment_one_pending (order_id) WHERE pending
    pending_order_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'pending' THEN order_id END) STORED,
    CONSTRAINT pk_payment_transactions PRIMARY KEY (id),
    -- name given by DDD §8 row 20 (NFR-04 idempotency key)
    CONSTRAINT uq_payment_ref UNIQUE (transaction_ref),
    -- BR-16: at most one pending transaction per order (NULL for other statuses never collides)
    CONSTRAINT uq_payment_one_pending UNIQUE (pending_order_id),
    CONSTRAINT fk_payment_transactions_order_id FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_transactions_handled_by FOREIGN KEY (handled_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_status CHECK (CAST(status AS BINARY) IN
        ('pending', 'success', 'failed', 'expired', 'needs_review')),
    CONSTRAINT ck_payment_action CHECK (handled_action IS NULL
        OR CAST(handled_action AS BINARY) IN ('activated', 'refund_outside')),
    -- D-29: the four handled_* columns are all set or all empty
    CONSTRAINT ck_payment_handling_complete CHECK (
        (handled_by IS NULL AND handled_action IS NULL AND handled_note IS NULL AND handled_at IS NULL)
        OR (handled_by IS NOT NULL AND handled_action IS NOT NULL AND handled_note IS NOT NULL
            AND handled_at IS NOT NULL)),
    CONSTRAINT ck_payment_handling_status CHECK (handled_at IS NULL
        OR CAST(status AS BINARY) IN ('needs_review', 'success')),
    CONSTRAINT ck_payment_confirmed CHECK (CAST(status AS BINARY) <> 'success' OR confirmed_at IS NOT NULL),
    CONSTRAINT ck_payment_qr_expiry CHECK (qr_expires_at > created_at),
    -- UC-S07 / UC-A14 attempts of an order, newest first; also serves as the index of fk_payment_transactions_order_id
    INDEX ix_payment_order_created (order_id, created_at DESC),
    -- DDD: (created_at) WHERE status = 'needs_review' AND handled_at IS NULL -> composite (UC-A18 review queue)
    INDEX ix_payment_review_queue (status, handled_at, created_at),
    -- DDD: (qr_expires_at) WHERE status = 'pending' -> composite (QR expiry timer)
    INDEX ix_payment_pending_expiry (status, qr_expires_at),
    INDEX ix_payment_handled_by (handled_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
