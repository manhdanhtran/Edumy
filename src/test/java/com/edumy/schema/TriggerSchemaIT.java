package com.edumy.schema;

import static com.edumy.support.SchemaAssertions.assertSignals;
import static com.edumy.support.SchemaAssertions.assertViolates;
import static org.assertj.core.api.Assertions.assertThat;

import com.edumy.support.MySqlJdbcTest;
import com.edumy.support.SchemaFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V5__triggers.sql on real MySQL 8.4: the 4 business triggers of EDU-DDD-001 (§5.6, §5.7, §5.16, §5.18; §8 rows 6, 10,
 * 24, 27) ported as 7 MySQL triggers (docs/ai/03 §3.5). Each violation asserts error 1644 / SQLState 45000 and the BR
 * code prefix of MESSAGE_TEXT. Spans authoring + learning, hence the cross-cutting package. Each test runs in a
 * rolled-back transaction (@MySqlJdbcTest).
 */
@MySqlJdbcTest
class TriggerSchemaIT {

    private static final String PAST = "2026-01-01 00:00:00";

    @Autowired
    JdbcClient jdbc;

    SchemaFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SchemaFixtures(jdbc);
    }

    // ------------------------------------------------------------- BR-02 trg_cci_not_main_instructor_bi / _bu (§5.7)

    @Test
    void insertInvitation_mainInstructorAsInvitee_rejected_BR02() {
        long owner = fx.user();
        long course = fx.course(owner);
        long inviter = fx.user(); // differs from the invitee, so ck_cci_not_self holds
        assertSignals("BR-02", () -> insertInvitation(course, owner, inviter));
        assertThat(countWhere("course_co_instructors", "course_id", course)).isZero();
    }

    @Test
    void insertInvitation_otherInstructor_accepted_BR02() {
        long owner = fx.user();
        long course = fx.course(owner);
        long id = insertInvitation(course, fx.user(), owner);
        assertThat(id).isPositive();
    }

    @Test
    void insertInvitation_unknownCourse_reportsFkNotBR02() {
        // no parent row -> subquery NULL -> no SIGNAL; the FK reports the error
        long user = fx.user();
        assertViolates("fk_course_co_instructors_course_id", () -> insertInvitation(-1L, user, fx.user()));
    }

    @Test
    void updateInvitation_inviteeToMainInstructor_rejected_BR02() {
        long owner = fx.user();
        long course = fx.course(owner);
        long inviter = fx.user();
        long invitation = insertInvitation(course, fx.user(), inviter);

        assertSignals("BR-02", () -> jdbc.sql("UPDATE course_co_instructors SET instructor_id = ? WHERE id = ?")
                .params(owner, invitation).update());
    }

    @Test
    void updateInvitation_toCourseWhereInviteeIsMainInstructor_rejected_BR02() {
        long owner = fx.user();
        long invitee = fx.user();
        long invitation = insertInvitation(fx.course(owner), invitee, owner);
        long inviteesCourse = fx.course(invitee);

        assertSignals("BR-02", () -> jdbc.sql("UPDATE course_co_instructors SET course_id = ? WHERE id = ?")
                .params(inviteesCourse, invitation).update());
    }

    @Test
    void updateInvitation_keepingInviteeNotMain_accepted_BR02() {
        long owner = fx.user();
        long course = fx.course(owner);
        long invitation = insertInvitation(course, fx.user(), owner);
        long otherInvitee = fx.user();

        jdbc.sql("""
                UPDATE course_co_instructors SET invitation_status = 'accepted', responded_at = ? WHERE id = ?""")
                .params(PAST, invitation).update();
        jdbc.sql("UPDATE course_co_instructors SET instructor_id = ? WHERE id = ?")
                .params(otherInvitee, invitation).update();

        var row = jdbc.sql("SELECT instructor_id, invitation_status FROM course_co_instructors WHERE id = ?")
                .param(invitation).query().singleRow();
        assertThat(row.get("instructor_id")).isEqualTo(otherInvitee);
        assertThat(row.get("invitation_status")).isEqualTo("accepted");
    }

    // ------------------------------------------------------ BR-19 trg_courses_prevent_delete_published_bd (§5.6)

    @Test
    void deleteCourse_neverPublishedDraft_accepted_andCascadesWithoutSignal() {
        // cascades to course_co_instructors, chapters, lessons; MySQL fires no trigger on cascaded rows
        long owner = fx.user();
        long course = fx.course(owner);
        long invitation = insertInvitation(course, fx.user(), owner);
        long chapter = fx.chapter(course, 1);
        long lesson = fx.videoLesson(chapter, 1);

        int deleted = jdbc.sql("DELETE FROM courses WHERE id = ?").param(course).update();

        assertThat(deleted).isEqualTo(1);
        assertThat(countWhere("course_co_instructors", "id", invitation)).isZero();
        assertThat(countWhere("chapters", "id", chapter)).isZero();
        assertThat(countWhere("lessons", "id", lesson)).isZero();
    }

    @Test
    void deleteCourse_rejectedNeverPublished_accepted_BR19() {
        long course = insertCourse("rejected", "Thiếu nội dung", null);
        assertThat(jdbc.sql("DELETE FROM courses WHERE id = ?").param(course).update()).isEqualTo(1);
    }

    /** published_at means "was ever published": protected whatever the current status (only CHECK-valid rows). */
    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "published, NULL",
            "hidden, Vi phạm chính sách",
            "draft, NULL",
            "rejected, Thiếu nội dung"
    })
    void deleteCourse_everPublished_rejected_BR19(String status, String reviewNote) {
        long course = insertCourse(status, reviewNote, PAST);

        assertSignals("BR-19", () -> jdbc.sql("DELETE FROM courses WHERE id = ?").param(course).update());
        assertThat(countWhere("courses", "id", course)).isEqualTo(1L);
    }

    @Test
    void deleteCourse_publishedWithChildren_rejectedBeforeCascade_BR19() {
        long owner = fx.user();
        long course = insertCourse(owner, "published", null, PAST);
        long invitation = insertInvitation(course, fx.user(), owner);
        long chapter = fx.chapter(course, 1);

        assertSignals("BR-19", () -> jdbc.sql("DELETE FROM courses WHERE id = ?").param(course).update());
        assertThat(countWhere("course_co_instructors", "id", invitation)).isEqualTo(1L);
        assertThat(countWhere("chapters", "id", chapter)).isEqualTo(1L);
    }

    // ----------------------------------------------------- BR-24 trg_lesson_progresses_lesson_course_bi / _bu (§5.16)

    @Test
    void insertProgress_lessonOfEnrollmentCourse_accepted_BR24() {
        Enrolled e = enrolled();
        long id = fx.lessonProgress(e.enrollment(), e.lesson());
        assertThat(id).isPositive();
    }

    @Test
    void insertProgress_lessonOfAnotherCourse_rejected_BR24() {
        Enrolled e = enrolled();
        long foreignLesson = lessonOf(fx.course());
        assertSignals("BR-24", () -> fx.lessonProgress(e.enrollment(), foreignLesson));
    }

    @Test
    void insertProgress_unknownLesson_reportsFkNotBR24() {
        Enrolled e = enrolled();
        assertViolates("fk_lesson_progresses_lesson_id", () -> fx.lessonProgress(e.enrollment(), -1L));
    }

    @Test
    void insertProgress_unknownEnrollment_reportsFkNotBR24() {
        long lesson = lessonOf(fx.course());
        assertViolates("fk_lesson_progresses_enrollment_id", () -> fx.lessonProgress(-1L, lesson));
    }

    @Test
    void updateProgress_toLessonOfAnotherCourse_rejected_BR24() {
        Enrolled e = enrolled();
        long progress = fx.lessonProgress(e.enrollment(), e.lesson());
        long foreignLesson = lessonOf(fx.course());

        assertSignals("BR-24", () -> jdbc.sql("UPDATE lesson_progresses SET lesson_id = ? WHERE id = ?")
                .params(foreignLesson, progress).update());
    }

    @Test
    void updateProgress_toEnrollmentOfAnotherCourse_rejected_BR24() {
        Enrolled e = enrolled();
        long progress = fx.lessonProgress(e.enrollment(), e.lesson());
        long otherEnrollment = fx.freeEnrollment(fx.user(), fx.course());

        assertSignals("BR-24", () -> jdbc.sql("UPDATE lesson_progresses SET enrollment_id = ? WHERE id = ?")
                .params(otherEnrollment, progress).update());
    }

    @Test
    void updateProgress_keepingLessonInCourse_accepted_BR24() {
        Enrolled e = enrolled();
        long progress = fx.lessonProgress(e.enrollment(), e.lesson());
        long sameCourseLesson = fx.videoLesson(e.chapter(), 2);

        jdbc.sql("UPDATE lesson_progresses SET is_completed = TRUE, completed_at = ? WHERE id = ?")
                .params(PAST, progress).update();
        jdbc.sql("UPDATE lesson_progresses SET lesson_id = ? WHERE id = ?").params(sameCourseLesson, progress).update();

        assertThat(jdbc.sql("SELECT lesson_id FROM lesson_progresses WHERE id = ?").param(progress)
                .query(Long.class).single()).isEqualTo(sameCourseLesson);
    }

    // ------------------------------------------------------ BR-37 trg_course_questions_lesson_course_bi / _bu (§5.18)

    @Test
    void insertQuestion_withoutLesson_accepted_BR37() {
        long enrollment = enrolled().enrollment();
        long id = fx.question(enrollment, null);
        assertThat(questionLessonId(id)).isNull();
    }

    @Test
    void insertQuestion_lessonOfEnrollmentCourse_accepted_BR37() {
        Enrolled e = enrolled();
        long id = fx.question(e.enrollment(), e.lesson());
        assertThat(id).isPositive();
    }

    @Test
    void insertQuestion_lessonOfAnotherCourse_rejected_BR37() {
        Enrolled e = enrolled();
        long foreignLesson = lessonOf(fx.course());
        assertSignals("BR-37", () -> fx.question(e.enrollment(), foreignLesson));
    }

    @Test
    void insertQuestion_unknownLesson_reportsFkNotBR37() {
        Enrolled e = enrolled();
        assertViolates("fk_course_questions_lesson_id", () -> fx.question(e.enrollment(), -1L));
    }

    @Test
    void updateQuestion_attachLessonOfAnotherCourse_rejected_BR37() {
        Enrolled e = enrolled();
        long question = fx.question(e.enrollment(), null);
        long foreignLesson = lessonOf(fx.course());

        assertSignals("BR-37", () -> jdbc.sql("UPDATE course_questions SET lesson_id = ? WHERE id = ?")
                .params(foreignLesson, question).update());
    }

    @Test
    void updateQuestion_toEnrollmentOfAnotherCourse_rejected_BR37() {
        Enrolled e = enrolled();
        long question = fx.question(e.enrollment(), e.lesson());
        long otherEnrollment = fx.freeEnrollment(fx.user(), fx.course());

        assertSignals("BR-37", () -> jdbc.sql("UPDATE course_questions SET enrollment_id = ? WHERE id = ?")
                .params(otherEnrollment, question).update());
    }

    @Test
    void updateQuestion_keepingLessonInCourseOrDetaching_accepted_BR37() {
        Enrolled e = enrolled();
        long question = fx.question(e.enrollment(), null);
        long sameCourseLesson = fx.videoLesson(e.chapter(), 2);

        jdbc.sql("UPDATE course_questions SET lesson_id = ? WHERE id = ?").params(e.lesson(), question).update();
        jdbc.sql("UPDATE course_questions SET lesson_id = ? WHERE id = ?").params(sameCourseLesson, question).update();
        assertThat(questionLessonId(question)).isEqualTo(sameCourseLesson);

        jdbc.sql("UPDATE course_questions SET lesson_id = NULL WHERE id = ?").param(question).update();
        assertThat(questionLessonId(question)).isNull();
    }

    // ------------------------------------------------------------------ FK cascades fire no trigger (docs/ai/03 §3.5)

    @Test
    void deleteLesson_cascadesProgress_andNullsQuestionLesson_withoutSignal() {
        Enrolled e = enrolled();
        long progress = fx.lessonProgress(e.enrollment(), e.lesson());
        long question = fx.question(e.enrollment(), e.lesson());

        int deleted = jdbc.sql("DELETE FROM lessons WHERE id = ?").param(e.lesson()).update();

        assertThat(deleted).isEqualTo(1);
        assertThat(countWhere("lesson_progresses", "id", progress)).isZero();
        var row = jdbc.sql("SELECT lesson_id, enrollment_id FROM course_questions WHERE id = ?").param(question)
                .query().singleRow();
        assertThat(row.get("lesson_id")).isNull();
        assertThat(row.get("enrollment_id")).isEqualTo(e.enrollment());
    }

    @Test
    void deleteChapter_cascadesLessonsAndTheirProgress_withoutSignal() {
        Enrolled e = enrolled();
        long progress = fx.lessonProgress(e.enrollment(), e.lesson());
        long question = fx.question(e.enrollment(), e.lesson());

        jdbc.sql("DELETE FROM chapters WHERE id = ?").param(e.chapter()).update();

        assertThat(countWhere("lessons", "id", e.lesson())).isZero();
        assertThat(countWhere("lesson_progresses", "id", progress)).isZero();
        assertThat(questionLessonId(question)).isNull();
    }

    // ---------------------------------------------------------------------------------------------------- metadata

    @Test
    void triggers_exactlyTheSevenPortedTriggers() {
        var triggers = jdbc.sql("""
                SELECT CONCAT(TRIGGER_NAME, ' ', ACTION_TIMING, ' ', EVENT_MANIPULATION, ' ', EVENT_OBJECT_TABLE)
                FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE()""")
                .query(String.class).list();

        assertThat(triggers).containsExactlyInAnyOrder(
                "trg_cci_not_main_instructor_bi BEFORE INSERT course_co_instructors",
                "trg_cci_not_main_instructor_bu BEFORE UPDATE course_co_instructors",
                "trg_courses_prevent_delete_published_bd BEFORE DELETE courses",
                "trg_lesson_progresses_lesson_course_bi BEFORE INSERT lesson_progresses",
                "trg_lesson_progresses_lesson_course_bu BEFORE UPDATE lesson_progresses",
                "trg_course_questions_lesson_course_bi BEFORE INSERT course_questions",
                "trg_course_questions_lesson_course_bu BEFORE UPDATE course_questions");
    }

    // ----------------------------------------------------------------------------------------------------- helpers

    /** A course (draft) with one chapter and one video lesson, and a free enrollment of a new student in it. */
    private record Enrolled(long course, long chapter, long lesson, long enrollment) {
    }

    private Enrolled enrolled() {
        long course = fx.course();
        long chapter = fx.chapter(course, 1);
        long lesson = fx.videoLesson(chapter, 1);
        return new Enrolled(course, chapter, lesson, fx.freeEnrollment(fx.user(), course));
    }

    /** A video lesson in a new chapter of {@code courseId}. */
    private long lessonOf(long courseId) {
        return fx.videoLesson(fx.chapter(courseId, 1), 1);
    }

    private long insertInvitation(long courseId, long instructorId, long invitedBy) {
        return fx.insert(jdbc.sql("""
                INSERT INTO course_co_instructors (course_id, instructor_id, invited_by) VALUES (?, ?, ?)""")
                .params(courseId, instructorId, invitedBy));
    }

    private long insertCourse(String status, String reviewNote, String publishedAt) {
        return insertCourse(fx.user(), status, reviewNote, publishedAt);
    }

    /** A course in {@code status} with the columns its CHECKs require (reviewed by a new user when it has a note). */
    private long insertCourse(long mainInstructorId, String status, String reviewNote, String publishedAt) {
        long n = SchemaFixtures.next();
        return fx.insert(jdbc.sql("""
                INSERT INTO courses (main_instructor_id, category_id, title, title_normalized, slug, status,
                                     review_note, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(mainInstructorId, fx.category(), "Khóa học " + n, "khoa hoc " + n, "khoa-trg-" + n, status,
                        reviewNote, publishedAt));
    }

    /** course_questions.lesson_id of {@code questionId}, or null. */
    private Long questionLessonId(long questionId) {
        return (Long) jdbc.sql("SELECT lesson_id FROM course_questions WHERE id = ?").param(questionId)
                .query().singleRow().get("lesson_id");
    }

    private long countWhere(String table, String column, long value) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?").param(value)
                .query(Long.class).single();
    }
}
