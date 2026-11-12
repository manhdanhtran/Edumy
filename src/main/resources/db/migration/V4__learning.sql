-- =====================================================================================================================
-- V4 — module learning: enrollments, lesson_progresses, course_reviews, course_questions, question_answers,
--      certificates (EDU-DDD-001 §5.15–5.20, §6 FK #21–34, §8 rows 22–31, §9).
-- MySQL port rules: docs/ai/03-data-model-mysql.md §3.1–3.3 (ADR-001).
--   TIMESTAMPTZ -> DATETIME(6) stored in UTC; updated_at trigger -> ON UPDATE CURRENT_TIMESTAMP(6);
--   enum-like / state CHECKs via CAST(... AS BINARY), code CHECK via REGEXP_LIKE(..., 'c') (collation trap);
--   length(btrim(x)) > 0 -> CHAR_LENGTH(TRIM(x)) > 0; partial index -> composite index (equality columns).
-- FKs to users, courses, lessons and orders point to V1/V2/V3. Triggers of these tables
-- (trg_lesson_progresses_lesson_course BR-24, trg_course_questions_lesson_course BR-37) are created in
-- V5__triggers.sql.
-- CASCADE FK (lesson_progresses.lesson_id) and SET NULL FK (course_questions.lesson_id): no CHECK references
-- lesson_id in either table (MySQL error 3823); none of the DDD CHECKs of these tables does.
-- =====================================================================================================================

-- 5.15 enrollments — Ghi danh (permanent access; free has no order, purchase must point to its order)
-- BR-17 duplicate enrollment: INSERT IGNORE or catch uq_enrollments_student_course (docs/ai/03 §3.2).
-- BR-14 "the order is paid, belongs to the student and has a line for the course" is checked in T2 (DDD §8 row 23).
CREATE TABLE enrollments (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    student_id    BIGINT      NOT NULL,
    course_id     BIGINT      NOT NULL,
    order_id      BIGINT      NULL,
    enroll_source VARCHAR(20) NOT NULL,
    enrolled_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_enrollments PRIMARY KEY (id),
    -- BR-17; also serves as the index of fk_enrollments_student_id
    CONSTRAINT uq_enrollments_student_course UNIQUE (student_id, course_id),
    CONSTRAINT fk_enrollments_student_id FOREIGN KEY (student_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_enrollments_course_id FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE RESTRICT,
    CONSTRAINT fk_enrollments_order_id FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT ck_enrollments_source CHECK (CAST(enroll_source AS BINARY) IN ('free', 'purchase')),
    -- BR-14 (database part)
    CONSTRAINT ck_enrollments_source_order CHECK (
        (CAST(enroll_source AS BINARY) = 'free' AND order_id IS NULL)
        OR (CAST(enroll_source AS BINARY) = 'purchase' AND order_id IS NOT NULL)),
    -- also serves as the index of fk_enrollments_course_id
    INDEX ix_enrollments_course (course_id),
    -- also serves as the index of fk_enrollments_order_id
    INDEX ix_enrollments_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5.16 lesson_progresses — Tiến độ bài học (deleting a lesson deletes its progress: deliberate exception to BR-28)
-- BR-24 "the lesson belongs to the enrollment's course" -> V5 triggers trg_lesson_progresses_lesson_course_bi/_bu.
-- last_accessed_at is set by the application ("Học tiếp", D-34); it has no ON UPDATE.
CREATE TABLE lesson_progresses (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    enrollment_id    BIGINT      NOT NULL,
    lesson_id        BIGINT      NOT NULL,
    is_completed     BOOLEAN     NOT NULL DEFAULT FALSE,
    completed_at     DATETIME(6) NULL,
    last_accessed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_lesson_progresses PRIMARY KEY (id),
    -- BR-24 (one row per pair); also serves as the index of fk_lesson_progresses_enrollment_id
    CONSTRAINT uq_lesson_progresses_enrollment_lesson UNIQUE (enrollment_id, lesson_id),
    CONSTRAINT fk_lesson_progresses_enrollment_id FOREIGN KEY (enrollment_id) REFERENCES enrollments (id)
        ON DELETE RESTRICT,
    -- CASCADE: no CHECK may reference lesson_id (MySQL error 3823)
    CONSTRAINT fk_lesson_progresses_lesson_id FOREIGN KEY (lesson_id) REFERENCES lessons (id) ON DELETE CASCADE,
    CONSTRAINT ck_lesson_progresses_completed CHECK (is_completed = (completed_at IS NOT NULL)),
    -- also serves as the index of fk_lesson_progresses_lesson_id
    INDEX ix_lesson_progresses_lesson (lesson_id),
    -- D-34 most recently accessed lesson
    INDEX ix_lesson_progresses_resume (enrollment_id, last_accessed_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5.17 course_reviews — Đánh giá khóa học (at most one per enrollment; student and course come from the enrollment)
-- BR-41 "a hidden review cannot be edited or withdrawn" and BR-26 "moderated_by is an admin" are checked by the
-- application (DDD §8 rows 26, 31).
CREATE TABLE course_reviews (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    enrollment_id     BIGINT        NOT NULL,
    rating            SMALLINT      NOT NULL,
    comment           VARCHAR(2000) NULL,
    visibility_status VARCHAR(20)   NOT NULL DEFAULT 'visible',
    moderated_by      BIGINT        NULL,
    moderated_at      DATETIME(6)   NULL,
    created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_course_reviews PRIMARY KEY (id),
    -- name given by DDD §8 row 25 (BR-18); also serves as the index of fk_course_reviews_enrollment_id
    CONSTRAINT uq_course_reviews_enrollment UNIQUE (enrollment_id),
    CONSTRAINT fk_course_reviews_enrollment_id FOREIGN KEY (enrollment_id) REFERENCES enrollments (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_course_reviews_moderated_by FOREIGN KEY (moderated_by) REFERENCES users (id) ON DELETE RESTRICT,
    -- BR-25
    CONSTRAINT ck_course_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_course_reviews_visibility CHECK (CAST(visibility_status AS BINARY) IN
        ('visible', 'withdrawn', 'hidden')),
    CONSTRAINT ck_course_reviews_moderation_pair CHECK ((moderated_by IS NULL) = (moderated_at IS NULL)),
    -- BR-35
    CONSTRAINT ck_course_reviews_hidden_moderated CHECK (CAST(visibility_status AS BINARY) <> 'hidden'
        OR moderated_by IS NOT NULL),
    INDEX ix_course_reviews_moderated_by (moderated_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5.18 course_questions — Câu hỏi hỏi đáp (tied to the asker's enrollment; optionally to a lesson of the same course)
-- BR-37 "the lesson belongs to the enrollment's course" -> V5 triggers trg_course_questions_lesson_course_bi/_bu
-- (only when lesson_id IS NOT NULL). BR-41 / BR-26 as for course_reviews (application).
CREATE TABLE course_questions (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    enrollment_id     BIGINT        NOT NULL,
    lesson_id         BIGINT        NULL,
    title             VARCHAR(200)  NOT NULL,
    content           VARCHAR(5000) NOT NULL,
    is_resolved       BOOLEAN       NOT NULL DEFAULT FALSE,
    visibility_status VARCHAR(20)   NOT NULL DEFAULT 'visible',
    moderated_by      BIGINT        NULL,
    moderated_at      DATETIME(6)   NULL,
    created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_course_questions PRIMARY KEY (id),
    CONSTRAINT fk_course_questions_enrollment_id FOREIGN KEY (enrollment_id) REFERENCES enrollments (id)
        ON DELETE RESTRICT,
    -- SET NULL: no CHECK may reference lesson_id (MySQL error 3823)
    CONSTRAINT fk_course_questions_lesson_id FOREIGN KEY (lesson_id) REFERENCES lessons (id) ON DELETE SET NULL,
    CONSTRAINT fk_course_questions_moderated_by FOREIGN KEY (moderated_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_course_questions_visibility CHECK (CAST(visibility_status AS BINARY) IN
        ('visible', 'deleted', 'hidden')),
    CONSTRAINT ck_course_questions_title CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT ck_course_questions_content CHECK (CHAR_LENGTH(TRIM(content)) > 0),
    CONSTRAINT ck_course_questions_moderation_pair CHECK ((moderated_by IS NULL) = (moderated_at IS NULL)),
    -- BR-35
    CONSTRAINT ck_course_questions_hidden_moderated CHECK (CAST(visibility_status AS BINARY) <> 'hidden'
        OR moderated_by IS NOT NULL),
    -- UC-S18 questions of a student, newest first; also serves as the index of fk_course_questions_enrollment_id
    INDEX ix_course_questions_enrollment (enrollment_id, created_at DESC),
    -- also serves as the index of fk_course_questions_lesson_id
    INDEX ix_course_questions_lesson (lesson_id),
    INDEX ix_course_questions_moderated_by (moderated_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5.19 question_answers — Phản hồi hỏi đáp (author_type is a snapshot taken when the answer is posted)
-- BR-38 "only an instructor of the course or the asker may answer" is checked by the application (query Q4,
-- DDD §8 row 28). BR-41 / BR-26 as for course_reviews (application).
CREATE TABLE question_answers (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    question_id       BIGINT        NOT NULL,
    author_id         BIGINT        NOT NULL,
    author_type       VARCHAR(20)   NOT NULL,
    content           VARCHAR(5000) NOT NULL,
    visibility_status VARCHAR(20)   NOT NULL DEFAULT 'visible',
    moderated_by      BIGINT        NULL,
    moderated_at      DATETIME(6)   NULL,
    created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_question_answers PRIMARY KEY (id),
    CONSTRAINT fk_question_answers_question_id FOREIGN KEY (question_id) REFERENCES course_questions (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_question_answers_author_id FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_question_answers_moderated_by FOREIGN KEY (moderated_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_question_answers_author_type CHECK (CAST(author_type AS BINARY) IN ('instructor', 'student')),
    CONSTRAINT ck_question_answers_visibility CHECK (CAST(visibility_status AS BINARY) IN
        ('visible', 'deleted', 'hidden')),
    CONSTRAINT ck_question_answers_content CHECK (CHAR_LENGTH(TRIM(content)) > 0),
    CONSTRAINT ck_question_answers_moderation_pair CHECK ((moderated_by IS NULL) = (moderated_at IS NULL)),
    -- BR-35
    CONSTRAINT ck_question_answers_hidden_moderated CHECK (CAST(visibility_status AS BINARY) <> 'hidden'
        OR moderated_by IS NOT NULL),
    -- thread in chronological order; also serves as the index of fk_question_answers_question_id
    INDEX ix_question_answers_question (question_id, created_at),
    -- DDD: (question_id) WHERE author_type = 'instructor' AND visibility_status = 'visible' -> composite; all three
    -- are equality predicates of the "instructor has answered" query (UC-I22)
    INDEX ix_question_answers_instructor_visible (question_id, author_type, visibility_status),
    INDEX ix_question_answers_author (author_id),
    INDEX ix_question_answers_moderated_by (moderated_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5.20 certificates — Chứng chỉ hoàn thành (one per enrollment completed 100 %; snapshot; permanent)
CREATE TABLE certificates (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    enrollment_id    BIGINT       NOT NULL,
    certificate_code VARCHAR(19)  NOT NULL,
    student_name     VARCHAR(100) NOT NULL,
    course_title     VARCHAR(200) NOT NULL,
    instructor_name  VARCHAR(100) NOT NULL,
    issued_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_certificates PRIMARY KEY (id),
    -- names given by DDD §8 row 29 (BR-39, D-47); uq_certificates_enrollment also serves as the index of
    -- fk_certificates_enrollment_id
    CONSTRAINT uq_certificates_enrollment UNIQUE (enrollment_id),
    CONSTRAINT uq_certificates_code UNIQUE (certificate_code),
    CONSTRAINT fk_certificates_enrollment_id FOREIGN KEY (enrollment_id) REFERENCES enrollments (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_certificates_code CHECK (REGEXP_LIKE(certificate_code,
        '^EDU-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}\\z', 'c'))  -- \z, not $: ICU $ also matches before a final '\n'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
