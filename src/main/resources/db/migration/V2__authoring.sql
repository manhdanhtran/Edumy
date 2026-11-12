-- =====================================================================================================================
-- V2 — module authoring: categories, courses, course_co_instructors, chapters, lessons
--      (EDU-DDD-001 §5.5–5.9, §6 FK #5–12, §8 rows 5–13, §9).
-- MySQL port rules: docs/ai/03-data-model-mysql.md §3.1–3.4 (ADR-001).
--   TIMESTAMPTZ -> DATETIME(6) stored in UTC; updated_at trigger -> ON UPDATE CURRENT_TIMESTAMP(6);
--   enum-like CHECKs via CAST(... AS BINARY); slug regex REGEXP_LIKE(..., 'c') ending in \z (ICU $ accepts 'x\n');
--   partial index -> composite index (equality columns first); pg_trgm GIN -> ix_courses_status_title (§3.2);
--   UNIQUE ... DEFERRABLE -> plain UNIQUE + two-phase reorder with a +1 000 000 offset (§3.4), so the
--   sort_order > 0 CHECKs stay.
-- FKs to users point to V1. Triggers of these tables (trg_cci_not_main_instructor, trg_courses_prevent_delete_published)
-- are created in V5__triggers.sql.
-- CASCADE FKs (course_co_instructors.course_id, chapters.course_id, lessons.chapter_id): no CHECK references these
-- columns (MySQL error 3823).
-- =====================================================================================================================

-- 5.5 categories — Danh mục (one level; a used category is only deactivated, D-21)
CREATE TABLE categories (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(100) NOT NULL,
    slug       VARCHAR(120) NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT uq_categories_name UNIQUE (name),
    CONSTRAINT uq_categories_slug UNIQUE (slug),
    CONSTRAINT ck_categories_slug CHECK (REGEXP_LIKE(slug, '^[a-z0-9]+(-[a-z0-9]+)*\\z', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5.6 courses — Khóa học (a course that was ever published is never deleted, BR-19 -> V5 trigger)
-- title_normalized is computed by the application (VietnameseNormalizer) together with title; not a generated column.
CREATE TABLE courses (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    main_instructor_id BIGINT       NOT NULL,
    category_id        BIGINT       NOT NULL,
    reviewed_by        BIGINT       NULL,
    title              VARCHAR(200) NOT NULL,
    title_normalized   VARCHAR(200) NOT NULL,
    slug               VARCHAR(220) NOT NULL,
    description        TEXT         NULL,
    thumbnail_url      VARCHAR(500) NULL,
    price              BIGINT       NOT NULL DEFAULT 0,
    status             VARCHAR(20)  NOT NULL DEFAULT 'draft',
    review_note        TEXT         NULL,
    submitted_at       DATETIME(6)  NULL,
    reviewed_at        DATETIME(6)  NULL,
    published_at       DATETIME(6)  NULL,
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_courses PRIMARY KEY (id),
    CONSTRAINT uq_courses_slug UNIQUE (slug),
    CONSTRAINT fk_courses_main_instructor_id FOREIGN KEY (main_instructor_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_courses_category_id FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_courses_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_courses_status CHECK (CAST(status AS BINARY) IN
        ('draft', 'pending_review', 'rejected', 'published', 'hidden')),
    CONSTRAINT ck_courses_price CHECK (price = 0 OR (price >= 10000 AND price % 1000 = 0)),
    CONSTRAINT ck_courses_note_required CHECK (CAST(status AS BINARY) NOT IN ('rejected', 'hidden')
        OR review_note IS NOT NULL),
    CONSTRAINT ck_courses_published_at CHECK (CAST(status AS BINARY) NOT IN ('published', 'hidden')
        OR published_at IS NOT NULL),
    CONSTRAINT ck_courses_submitted_at CHECK (CAST(status AS BINARY) <> 'pending_review' OR submitted_at IS NOT NULL),
    CONSTRAINT ck_courses_slug CHECK (REGEXP_LIKE(slug, '^[a-z0-9]+(-[a-z0-9]+)*\\z', 'c')),
    INDEX ix_courses_main_instructor (main_instructor_id),
    -- also serves as the index of fk_courses_category_id
    INDEX ix_courses_category_status (category_id, status),
    INDEX ix_courses_reviewed_by (reviewed_by),
    -- DDD: (published_at DESC) WHERE status = 'published' -> composite, equality column first (UC-G01 newest first)
    INDEX ix_courses_published (status, published_at DESC),
    -- DDD: (submitted_at) WHERE status = 'pending_review' -> composite (UC-A09 review queue, oldest first)
    INDEX ix_courses_pending (status, submitted_at),
    -- replaces ix_courses_title_trgm (pg_trgm GIN): search uses title_normalized LIKE CONCAT('%', ?, '%') (§3.2)
    INDEX ix_courses_status_title (status, title_normalized)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5.7 course_co_instructors — Lời mời cộng tác (one row per pair; re-invite updates it, D-18)
-- BR-02 "invitee differs from the main instructor" -> V5 triggers trg_cci_not_main_instructor_bi/_bu.
CREATE TABLE course_co_instructors (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    course_id         BIGINT      NOT NULL,
    instructor_id     BIGINT      NOT NULL,
    invited_by        BIGINT      NOT NULL,
    invitation_status VARCHAR(20) NOT NULL DEFAULT 'pending',
    invited_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    responded_at      DATETIME(6) NULL,
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_course_co_instructors PRIMARY KEY (id),
    -- also serves as the index of fk_course_co_instructors_course_id
    CONSTRAINT uq_cci_course_instructor UNIQUE (course_id, instructor_id),
    -- CASCADE: no CHECK may reference course_id (MySQL error 3823)
    CONSTRAINT fk_course_co_instructors_course_id FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE CASCADE,
    CONSTRAINT fk_course_co_instructors_instructor_id FOREIGN KEY (instructor_id) REFERENCES users (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_course_co_instructors_invited_by FOREIGN KEY (invited_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_cci_status CHECK (CAST(invitation_status AS BINARY) IN ('pending', 'accepted', 'declined', 'removed')),
    CONSTRAINT ck_cci_not_self CHECK (instructor_id <> invited_by),
    CONSTRAINT ck_cci_responded CHECK (CAST(invitation_status AS BINARY) NOT IN ('accepted', 'declined')
        OR responded_at IS NOT NULL),
    -- also serves as the index of fk_course_co_instructors_instructor_id
    INDEX ix_cci_instructor_status (instructor_id, invitation_status),
    INDEX ix_cci_invited_by (invited_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5.8 chapters — Chương học
CREATE TABLE chapters (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    course_id  BIGINT       NOT NULL,
    title      VARCHAR(200) NOT NULL,
    sort_order INT          NOT NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_chapters PRIMARY KEY (id),
    -- DDD: DEFERRABLE INITIALLY DEFERRED -> plain UNIQUE; reorder in two phases (+1 000 000 offset, docs/ai/03 §3.4).
    -- Also serves as the index of fk_chapters_course_id.
    CONSTRAINT uq_chapters_course_order UNIQUE (course_id, sort_order),
    -- CASCADE: no CHECK may reference course_id (MySQL error 3823)
    CONSTRAINT fk_chapters_course_id FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE CASCADE,
    CONSTRAINT ck_chapters_order CHECK (sort_order > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5.9 lessons — Bài học (video: video_url; article: article_content, D-22)
CREATE TABLE lessons (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    chapter_id       BIGINT       NOT NULL,
    title            VARCHAR(200) NOT NULL,
    content_type     VARCHAR(20)  NOT NULL,
    video_url        VARCHAR(500) NULL,
    article_content  TEXT         NULL,
    duration_seconds INT          NULL,
    is_preview       BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order       INT          NOT NULL,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_lessons PRIMARY KEY (id),
    -- DDD: DEFERRABLE INITIALLY DEFERRED -> plain UNIQUE; reorder in two phases (+1 000 000 offset, docs/ai/03 §3.4).
    -- Also serves as the index of fk_lessons_chapter_id.
    CONSTRAINT uq_lessons_chapter_order UNIQUE (chapter_id, sort_order),
    -- CASCADE: no CHECK may reference chapter_id (MySQL error 3823)
    CONSTRAINT fk_lessons_chapter_id FOREIGN KEY (chapter_id) REFERENCES chapters (id) ON DELETE CASCADE,
    CONSTRAINT ck_lessons_type CHECK (CAST(content_type AS BINARY) IN ('video', 'article')),
    CONSTRAINT ck_lessons_content_match CHECK (
        (CAST(content_type AS BINARY) = 'video' AND article_content IS NULL)
        OR (CAST(content_type AS BINARY) = 'article' AND video_url IS NULL)),
    CONSTRAINT ck_lessons_duration CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    CONSTRAINT ck_lessons_order CHECK (sort_order > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
