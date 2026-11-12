-- =====================================================================================================================
-- V5 — cross-table business triggers (EDU-DDD-001 §5.6, §5.7, §5.16, §5.18; §8 rows 6, 10, 24, 27).
-- MySQL port rules: docs/ai/03-data-model-mysql.md §3.2, §3.5 (ADR-001).
--   One PL/pgSQL trigger of the DDD -> one MySQL trigger per event: _bi (BEFORE INSERT), _bu (BEFORE UPDATE),
--   _bd (BEFORE DELETE). A violation raises SIGNAL SQLSTATE '45000' (MySQL error 1644) with a MESSAGE_TEXT that starts
--   with the BR code and ':' — the error translator keys on that prefix.
--   The 13 updated_at triggers of the DDD ("17 trigger" = 13 updated_at + 4 business) are replaced by
--   ON UPDATE CURRENT_TIMESTAMP(6) on every updated_at column in V1–V4; only the 4 business triggers remain
--   (7 MySQL triggers).
-- Missing parent row: the lookup subquery returns NULL, the comparison is NULL, the trigger does not signal, and the FK
-- of the child row reports the error (fk_<table>_<column>), as in PostgreSQL.
-- Known MySQL limitations (accepted, docs/ai/03 §3.5):
--   * Triggers do not fire for rows changed by FK cascades. Cascades here only delete children (course_co_instructors,
--     chapters, lessons, lesson_progresses) or set course_questions.lesson_id to NULL, which never breaks BR-02/24/37.
--   * BR-24 / BR-37 are checked on the child row only. Moving a lesson to a chapter of another course
--     (UPDATE lessons.chapter_id) or changing enrollments.course_id is not re-checked — same as the DDD's triggers on
--     these tables. Application guard: enrollments are immutable; lessons only move within their course.
-- Flyway's MySQL parser handles BEGIN … END blocks, so no DELIMITER statement is needed.
-- =====================================================================================================================

-- BR-02 (DDD §5.7, §8 row 6): a co-instructor differs from the main instructor of the course.
-- (ck_cci_not_self only compares instructor_id with invited_by; the main instructor lives in courses.)
CREATE TRIGGER trg_cci_not_main_instructor_bi
BEFORE INSERT ON course_co_instructors FOR EACH ROW
BEGIN
    IF NEW.instructor_id = (SELECT main_instructor_id FROM courses WHERE id = NEW.course_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'BR-02: co-instructor must differ from main instructor';
    END IF;
END;

-- BR-02, same check when an invitation row is updated (instructor_id or course_id changed).
CREATE TRIGGER trg_cci_not_main_instructor_bu
BEFORE UPDATE ON course_co_instructors FOR EACH ROW
BEGIN
    IF NEW.instructor_id = (SELECT main_instructor_id FROM courses WHERE id = NEW.course_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'BR-02: co-instructor must differ from main instructor';
    END IF;
END;

-- BR-19 (DDD §5.6, §8 row 10): a course that was ever published (published_at set, whatever its current status) is
-- never deleted. Together with the RESTRICT FKs from enrollments / order_items / cart_items (BR-28).
CREATE TRIGGER trg_courses_prevent_delete_published_bd
BEFORE DELETE ON courses FOR EACH ROW
BEGIN
    IF OLD.published_at IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'BR-19: course was published and cannot be deleted';
    END IF;
END;

-- BR-24 (DDD §5.16, §8 row 24): the lesson of a progress row belongs to the course of its enrollment.
CREATE TRIGGER trg_lesson_progresses_lesson_course_bi
BEFORE INSERT ON lesson_progresses FOR EACH ROW
BEGIN
    IF (SELECT c.course_id FROM lessons l JOIN chapters c ON c.id = l.chapter_id WHERE l.id = NEW.lesson_id)
       <> (SELECT e.course_id FROM enrollments e WHERE e.id = NEW.enrollment_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'BR-24: lesson does not belong to the enrollment course';
    END IF;
END;

-- BR-24, same check when lesson_id or enrollment_id of a progress row is updated.
CREATE TRIGGER trg_lesson_progresses_lesson_course_bu
BEFORE UPDATE ON lesson_progresses FOR EACH ROW
BEGIN
    IF (SELECT c.course_id FROM lessons l JOIN chapters c ON c.id = l.chapter_id WHERE l.id = NEW.lesson_id)
       <> (SELECT e.course_id FROM enrollments e WHERE e.id = NEW.enrollment_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'BR-24: lesson does not belong to the enrollment course';
    END IF;
END;

-- BR-37 (DDD §5.18, §8 row 27): a question attached to a lesson (lesson_id is optional) is attached to a lesson of
-- the course of the asker's enrollment. A question without lesson is not looked up.
CREATE TRIGGER trg_course_questions_lesson_course_bi
BEFORE INSERT ON course_questions FOR EACH ROW
BEGIN
    IF NEW.lesson_id IS NOT NULL
       AND (SELECT c.course_id FROM lessons l JOIN chapters c ON c.id = l.chapter_id WHERE l.id = NEW.lesson_id)
           <> (SELECT e.course_id FROM enrollments e WHERE e.id = NEW.enrollment_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'BR-37: lesson does not belong to the enrollment course';
    END IF;
END;

-- BR-37, same check when lesson_id or enrollment_id of a question is updated (only when lesson_id IS NOT NULL).
CREATE TRIGGER trg_course_questions_lesson_course_bu
BEFORE UPDATE ON course_questions FOR EACH ROW
BEGIN
    IF NEW.lesson_id IS NOT NULL
       AND (SELECT c.course_id FROM lessons l JOIN chapters c ON c.id = l.chapter_id WHERE l.id = NEW.lesson_id)
           <> (SELECT e.course_id FROM enrollments e WHERE e.id = NEW.enrollment_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'BR-37: lesson does not belong to the enrollment course';
    END IF;
END;
