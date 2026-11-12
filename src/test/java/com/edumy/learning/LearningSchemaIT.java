package com.edumy.learning;

import static com.edumy.support.SchemaAssertions.assertCheckViolated;
import static com.edumy.support.SchemaAssertions.assertCheckViolatedAnyOf;
import static com.edumy.support.SchemaAssertions.assertViolates;
import static com.edumy.support.SchemaAssertions.checkConstraint;
import static com.edumy.support.SchemaAssertions.foreignKeyDeleteRules;
import static com.edumy.support.SchemaAssertions.indexColumns;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import com.edumy.support.MySqlJdbcTest;
import com.edumy.support.SchemaFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V4__learning.sql on real MySQL 8.4: every CHECK / UNIQUE / FK of enrollments, lesson_progresses, course_reviews,
 * course_questions, question_answers, certificates (EDU-DDD-001 §5.15–5.20, §6 #21–34, §8 rows 22–31, §9).
 * Each violation asserts the constraint name. Each test runs in a rolled-back transaction (@JdbcTest).
 * Progress rows and lesson-bound questions always use a lesson of the enrollment's course, so these tests stay valid
 * once the V5 triggers (BR-24, BR-37) exist; those triggers are tested with V5.
 */
@MySqlJdbcTest
class LearningSchemaIT {

    private static final String PAST = "2026-01-01 00:00:00";

    @Autowired
    JdbcClient jdbc;

    SchemaFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SchemaFixtures(jdbc);
    }

    // ---------------------------------------------------------------------------------------------------- enrollments

    @Test
    void insertEnrollment_free_appliesDefaults() {
        long id = fx.freeEnrollment(fx.user(), fx.course());

        var row = jdbc.sql("SELECT order_id, enroll_source, enrolled_at FROM enrollments WHERE id = ?")
                .param(id).query().singleRow();
        assertThat(row.get("order_id")).isNull();
        assertThat(row.get("enroll_source")).isEqualTo("free");
        assertThat(row.get("enrolled_at")).isNotNull();
    }

    @Test
    void insertEnrollment_purchaseWithOrder_accepted() {
        long student = fx.user();
        long order = fx.order(student, "paid");
        long id = fx.purchaseEnrollment(student, fx.course(), order);

        assertThat(jdbc.sql("SELECT order_id FROM enrollments WHERE id = ?").param(id).query(Long.class).single())
                .isEqualTo(order);
    }

    @Test
    void insertEnrollment_sameStudentAndCourseTwice_rejected_BR17() {
        long student = fx.user();
        long course = fx.course();
        fx.freeEnrollment(student, course);
        long order = fx.order(student, "paid");
        assertViolates("uq_enrollments_student_course", () -> fx.purchaseEnrollment(student, course, order));
    }

    @Test
    void insertIgnoreEnrollment_duplicate_isSkipped_BR17() {
        // docs/ai/03 §3.2: ON CONFLICT DO NOTHING -> INSERT IGNORE
        long student = fx.user();
        long course = fx.course();
        fx.freeEnrollment(student, course);

        int inserted = jdbc.sql("""
                INSERT IGNORE INTO enrollments (student_id, course_id, enroll_source) VALUES (?, ?, 'free')""")
                .params(student, course).update();

        assertThat(inserted).isZero();
        assertThat(countWhere("enrollments", "student_id", student)).isEqualTo(1L);
    }

    @Test
    void insertEnrollment_sameCourseForAnotherStudent_accepted() {
        long course = fx.course();
        fx.freeEnrollment(fx.user(), course);
        long id = fx.freeEnrollment(fx.user(), course);
        assertThat(id).isPositive();
    }

    /**
     * DDD ck_enrollments_source_order only holds for 'free' or 'purchase', so an unknown source breaks it as well as
     * ck_enrollments_source; MySQL reports only one of them. Either name means "invalid enrollment source".
     */
    @ParameterizedTest
    @ValueSource(strings = {"gift", "Free", "PURCHASE", "freé"})
    void insertEnrollment_invalidSource_rejected(String source) {
        long student = fx.user();
        long course = fx.course();
        assertCheckViolatedAnyOf(() -> insertEnrollment(student, course, null, source),
                "ck_enrollments_source", "ck_enrollments_source_order");
    }

    @Test
    void enrollSourceCheck_isEnforcedAndCaseSensitive() {
        assertThat(checkConstraint(jdbc, "enrollments", "ck_enrollments_source")).singleElement().asString()
                .startsWith("YES ")
                .contains("cast(`enroll_source` as char charset binary)")
                .contains("free").contains("purchase");
    }

    @Test
    void insertEnrollment_freeWithOrder_rejected_BR14() {
        long student = fx.user();
        long course = fx.course();
        long order = fx.order(student, "paid");
        assertCheckViolated("ck_enrollments_source_order", () -> insertEnrollment(student, course, order, "free"));
    }

    @Test
    void insertEnrollment_purchaseWithoutOrder_rejected_BR14() {
        long student = fx.user();
        long course = fx.course();
        assertCheckViolated("ck_enrollments_source_order", () -> insertEnrollment(student, course, null, "purchase"));
    }

    @Test
    void insertEnrollment_unknownStudent_rejected() {
        long course = fx.course();
        assertViolates("fk_enrollments_student_id", () -> fx.freeEnrollment(-1L, course));
    }

    @Test
    void insertEnrollment_unknownCourse_rejected() {
        long student = fx.user();
        assertViolates("fk_enrollments_course_id", () -> fx.freeEnrollment(student, -1L));
    }

    @Test
    void insertEnrollment_unknownOrder_rejected() {
        long student = fx.user();
        long course = fx.course();
        assertViolates("fk_enrollments_order_id", () -> fx.purchaseEnrollment(student, course, -1L));
    }

    // ---------------------------------------------------------------------------------------------- lesson_progresses

    @Test
    void insertProgress_valid_appliesDefaults() {
        Enrolled e = enrolled();
        long id = fx.lessonProgress(e.enrollment(), e.lesson());

        var row = jdbc.sql("SELECT is_completed, completed_at, last_accessed_at FROM lesson_progresses WHERE id = ?")
                .param(id).query().singleRow();
        assertThat(row.get("is_completed")).isEqualTo(false);
        assertThat(row.get("completed_at")).isNull();
        assertThat(row.get("last_accessed_at")).isNotNull();
    }

    @Test
    void insertProgress_completedFlagMatchingCompletedAt_accepted() {
        Enrolled e = enrolled();
        long second = fx.videoLesson(e.chapter(), 2);
        insertProgress(e.enrollment(), e.lesson(), true, PAST);
        insertProgress(e.enrollment(), second, false, null);

        assertThat(countWhere("lesson_progresses", "enrollment_id", e.enrollment())).isEqualTo(2L);
    }

    @Test
    void insertProgress_completedWithoutCompletedAt_rejected() {
        Enrolled e = enrolled();
        assertCheckViolated("ck_lesson_progresses_completed",
                () -> insertProgress(e.enrollment(), e.lesson(), true, null));
    }

    @Test
    void insertProgress_notCompletedWithCompletedAt_rejected() {
        Enrolled e = enrolled();
        assertCheckViolated("ck_lesson_progresses_completed",
                () -> insertProgress(e.enrollment(), e.lesson(), false, PAST));
    }

    @Test
    void updateProgress_unmarkWithoutClearingCompletedAt_rejected() {
        Enrolled e = enrolled();
        long id = insertProgress(e.enrollment(), e.lesson(), true, PAST);
        assertCheckViolated("ck_lesson_progresses_completed",
                () -> jdbc.sql("UPDATE lesson_progresses SET is_completed = FALSE WHERE id = ?").param(id).update());
    }

    @Test
    void insertProgress_samePairTwice_rejected_BR24() {
        Enrolled e = enrolled();
        fx.lessonProgress(e.enrollment(), e.lesson());
        assertViolates("uq_lesson_progresses_enrollment_lesson", () -> fx.lessonProgress(e.enrollment(), e.lesson()));
    }

    @Test
    void insertProgress_sameLessonForAnotherEnrollment_accepted() {
        Enrolled e = enrolled();
        fx.lessonProgress(e.enrollment(), e.lesson());
        long other = fx.freeEnrollment(fx.user(), e.course());
        long id = fx.lessonProgress(other, e.lesson());
        assertThat(id).isPositive();
    }

    @Test
    void insertProgress_unknownEnrollment_rejected() {
        Enrolled e = enrolled();
        assertViolates("fk_lesson_progresses_enrollment_id", () -> fx.lessonProgress(-1L, e.lesson()));
    }

    @Test
    void insertProgress_unknownLesson_rejected() {
        Enrolled e = enrolled();
        assertViolates("fk_lesson_progresses_lesson_id", () -> fx.lessonProgress(e.enrollment(), -1L));
    }

    @Test
    void updateProgress_complete_keepsLastAccessedAt() {
        // last_accessed_at has no ON UPDATE: the application sets it when the lesson is opened (D-34)
        Enrolled e = enrolled();
        long id = fx.lessonProgress(e.enrollment(), e.lesson());
        jdbc.sql("UPDATE lesson_progresses SET last_accessed_at = '2020-01-01 00:00:00' WHERE id = ?").param(id)
                .update();

        jdbc.sql("UPDATE lesson_progresses SET is_completed = TRUE, completed_at = CURRENT_TIMESTAMP(6) WHERE id = ?")
                .param(id).update();

        assertThat(jdbc.sql("SELECT last_accessed_at FROM lesson_progresses WHERE id = ?").param(id)
                .query(LocalDateTime.class).single()).isEqualTo(LocalDateTime.of(2020, 1, 1, 0, 0));
    }

    // ------------------------------------------------------------------------------------------------- course_reviews

    @Test
    void insertReview_valid_appliesDefaults() {
        long id = insertReview(enrollment(), 5);

        var row = jdbc.sql("""
                SELECT comment, visibility_status, moderated_by, moderated_at, created_at, updated_at
                FROM course_reviews WHERE id = ?""").param(id).query().singleRow();
        assertThat(row.get("visibility_status")).isEqualTo("visible");
        assertThat(row).containsEntry("comment", null).containsEntry("moderated_by", null)
                .containsEntry("moderated_at", null);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    void insertReview_everyVisibilityWithItsRequiredColumns_accepted() {
        long admin = fx.user();
        insertReview(enrollment(), 4, "visible", null, null);
        insertReview(enrollment(), 4, "withdrawn", null, null);
        insertReview(enrollment(), 4, "hidden", admin, PAST);
        // shown again by an admin: the last moderator stays recorded
        insertReview(enrollment(), 4, "visible", admin, PAST);

        assertThat(countWhere("course_reviews", "moderated_by", admin)).isEqualTo(2L);
    }

    @Test
    void insertReview_secondForEnrollment_rejected_BR18() {
        long enrollment = enrollment();
        insertReview(enrollment, 5);
        assertViolates("uq_course_reviews_enrollment", () -> insertReview(enrollment, 3));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void insertReview_ratingAtBounds_accepted_BR25(int rating) {
        long id = insertReview(enrollment(), rating);
        assertThat(id).isPositive();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6, -1})
    void insertReview_ratingOutOfRange_rejected_BR25(int rating) {
        long enrollment = enrollment();
        assertCheckViolated("ck_course_reviews_rating", () -> insertReview(enrollment, rating));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted", "Visible", "WITHDRAWN", "Hidden", "hiddén"})
    void insertReview_invalidVisibility_rejected(String visibility) {
        long enrollment = enrollment();
        assertCheckViolated("ck_course_reviews_visibility",
                () -> insertReview(enrollment, 4, visibility, null, null));
    }

    @Test
    void insertReview_moderatorWithoutModeratedAt_rejected() {
        long enrollment = enrollment();
        long admin = fx.user();
        assertCheckViolated("ck_course_reviews_moderation_pair",
                () -> insertReview(enrollment, 4, "visible", admin, null));
    }

    @Test
    void insertReview_moderatedAtWithoutModerator_rejected() {
        long enrollment = enrollment();
        assertCheckViolated("ck_course_reviews_moderation_pair",
                () -> insertReview(enrollment, 4, "visible", null, PAST));
    }

    @Test
    void insertReview_hiddenWithoutModerator_rejected_BR35() {
        long enrollment = enrollment();
        assertCheckViolated("ck_course_reviews_hidden_moderated",
                () -> insertReview(enrollment, 4, "hidden", null, null));
    }

    @Test
    void insertReview_unknownEnrollment_rejected() {
        assertViolates("fk_course_reviews_enrollment_id", () -> insertReview(-1L, 5));
    }

    @Test
    void insertReview_unknownModerator_rejected() {
        long enrollment = enrollment();
        assertViolates("fk_course_reviews_moderated_by", () -> insertReview(enrollment, 4, "hidden", -1L, PAST));
    }

    @Test
    void updateReview_withdraw_refreshesUpdatedAt() {
        long id = insertReview(enrollment(), 5);
        assertUpdatedAtRefreshed("course_reviews", id, "visibility_status = 'withdrawn'");
    }

    // ----------------------------------------------------------------------------------------------- course_questions

    @Test
    void insertQuestion_valid_appliesDefaults() {
        long id = fx.question(enrollment(), null);

        var row = jdbc.sql("""
                SELECT lesson_id, is_resolved, visibility_status, moderated_by, moderated_at, created_at, updated_at
                FROM course_questions WHERE id = ?""").param(id).query().singleRow();
        assertThat(row.get("is_resolved")).isEqualTo(false);
        assertThat(row.get("visibility_status")).isEqualTo("visible");
        assertThat(row).containsEntry("lesson_id", null).containsEntry("moderated_by", null)
                .containsEntry("moderated_at", null);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    void insertQuestion_attachedToLessonOfTheCourse_accepted() {
        Enrolled e = enrolled();
        long id = fx.question(e.enrollment(), e.lesson());
        assertThat(jdbc.sql("SELECT lesson_id FROM course_questions WHERE id = ?").param(id).query(Long.class).single())
                .isEqualTo(e.lesson());
    }

    @Test
    void insertQuestion_everyVisibilityWithItsRequiredColumns_accepted() {
        long enrollment = enrollment();
        long admin = fx.user();
        insertQuestion(enrollment, "Tiêu đề", "Nội dung", "visible", null, null);
        insertQuestion(enrollment, "Tiêu đề", "Nội dung", "deleted", null, null);
        insertQuestion(enrollment, "Tiêu đề", "Nội dung", "hidden", admin, PAST);
        insertQuestion(enrollment, "Tiêu đề", "Nội dung", "visible", admin, PAST);

        assertThat(countWhere("course_questions", "enrollment_id", enrollment)).isEqualTo(4L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"withdrawn", "Visible", "DELETED", "Hidden", "hiddén"})
    void insertQuestion_invalidVisibility_rejected(String visibility) {
        long enrollment = enrollment();
        assertCheckViolated("ck_course_questions_visibility",
                () -> insertQuestion(enrollment, "Tiêu đề", "Nội dung", visibility, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void insertQuestion_blankTitle_rejected(String title) {
        long enrollment = enrollment();
        assertCheckViolated("ck_course_questions_title",
                () -> insertQuestion(enrollment, title, "Nội dung", "visible", null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void insertQuestion_blankContent_rejected(String content) {
        long enrollment = enrollment();
        assertCheckViolated("ck_course_questions_content",
                () -> insertQuestion(enrollment, "Tiêu đề", content, "visible", null, null));
    }

    @Test
    void insertQuestion_moderatorWithoutModeratedAt_rejected() {
        long enrollment = enrollment();
        long admin = fx.user();
        assertCheckViolated("ck_course_questions_moderation_pair",
                () -> insertQuestion(enrollment, "Tiêu đề", "Nội dung", "visible", admin, null));
    }

    @Test
    void insertQuestion_moderatedAtWithoutModerator_rejected() {
        long enrollment = enrollment();
        assertCheckViolated("ck_course_questions_moderation_pair",
                () -> insertQuestion(enrollment, "Tiêu đề", "Nội dung", "visible", null, PAST));
    }

    @Test
    void insertQuestion_hiddenWithoutModerator_rejected_BR35() {
        long enrollment = enrollment();
        assertCheckViolated("ck_course_questions_hidden_moderated",
                () -> insertQuestion(enrollment, "Tiêu đề", "Nội dung", "hidden", null, null));
    }

    @Test
    void insertQuestion_unknownEnrollment_rejected() {
        assertViolates("fk_course_questions_enrollment_id", () -> fx.question(-1L, null));
    }

    @Test
    void insertQuestion_unknownLesson_rejected() {
        long enrollment = enrollment();
        assertViolates("fk_course_questions_lesson_id", () -> fx.question(enrollment, -1L));
    }

    @Test
    void insertQuestion_unknownModerator_rejected() {
        long enrollment = enrollment();
        assertViolates("fk_course_questions_moderated_by",
                () -> insertQuestion(enrollment, "Tiêu đề", "Nội dung", "hidden", -1L, PAST));
    }

    @Test
    void updateQuestion_resolve_refreshesUpdatedAt() {
        long id = fx.question(enrollment(), null);
        assertUpdatedAtRefreshed("course_questions", id, "is_resolved = TRUE");
    }

    // ----------------------------------------------------------------------------------------------- question_answers

    @Test
    void insertAnswer_valid_appliesDefaults() {
        long id = insertAnswer(question(), fx.user(), "instructor");

        var row = jdbc.sql("""
                SELECT visibility_status, moderated_by, moderated_at, created_at, updated_at
                FROM question_answers WHERE id = ?""").param(id).query().singleRow();
        assertThat(row.get("visibility_status")).isEqualTo("visible");
        assertThat(row).containsEntry("moderated_by", null).containsEntry("moderated_at", null);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    void insertAnswer_everyAuthorTypeAndVisibility_accepted() {
        long question = question();
        long author = fx.user();
        long admin = fx.user();
        insertAnswer(question, author, "instructor", "Trả lời", "visible", null, null);
        insertAnswer(question, author, "student", "Trả lời", "visible", null, null);
        insertAnswer(question, author, "student", "Trả lời", "deleted", null, null);
        insertAnswer(question, author, "instructor", "Trả lời", "hidden", admin, PAST);
        insertAnswer(question, author, "instructor", "Trả lời", "visible", admin, PAST);

        assertThat(countWhere("question_answers", "question_id", question)).isEqualTo(5L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "Instructor", "STUDENT", "studént"})
    void insertAnswer_invalidAuthorType_rejected(String authorType) {
        long question = question();
        long author = fx.user();
        assertCheckViolated("ck_question_answers_author_type", () -> insertAnswer(question, author, authorType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"withdrawn", "Visible", "DELETED", "Hidden", "hiddén"})
    void insertAnswer_invalidVisibility_rejected(String visibility) {
        long question = question();
        long author = fx.user();
        assertCheckViolated("ck_question_answers_visibility",
                () -> insertAnswer(question, author, "instructor", "Trả lời", visibility, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void insertAnswer_blankContent_rejected(String content) {
        long question = question();
        long author = fx.user();
        assertCheckViolated("ck_question_answers_content",
                () -> insertAnswer(question, author, "instructor", content, "visible", null, null));
    }

    @Test
    void insertAnswer_moderatorWithoutModeratedAt_rejected() {
        long question = question();
        long author = fx.user();
        long admin = fx.user();
        assertCheckViolated("ck_question_answers_moderation_pair",
                () -> insertAnswer(question, author, "instructor", "Trả lời", "visible", admin, null));
    }

    @Test
    void insertAnswer_moderatedAtWithoutModerator_rejected() {
        long question = question();
        long author = fx.user();
        assertCheckViolated("ck_question_answers_moderation_pair",
                () -> insertAnswer(question, author, "instructor", "Trả lời", "visible", null, PAST));
    }

    @Test
    void insertAnswer_hiddenWithoutModerator_rejected_BR35() {
        long question = question();
        long author = fx.user();
        assertCheckViolated("ck_question_answers_hidden_moderated",
                () -> insertAnswer(question, author, "instructor", "Trả lời", "hidden", null, null));
    }

    @Test
    void insertAnswer_unknownQuestion_rejected() {
        long author = fx.user();
        assertViolates("fk_question_answers_question_id", () -> insertAnswer(-1L, author, "instructor"));
    }

    @Test
    void insertAnswer_unknownAuthor_rejected() {
        long question = question();
        assertViolates("fk_question_answers_author_id", () -> insertAnswer(question, -1L, "instructor"));
    }

    @Test
    void insertAnswer_unknownModerator_rejected() {
        long question = question();
        long author = fx.user();
        assertViolates("fk_question_answers_moderated_by",
                () -> insertAnswer(question, author, "instructor", "Trả lời", "hidden", -1L, PAST));
    }

    @Test
    void updateAnswer_edit_refreshesUpdatedAt() {
        long id = insertAnswer(question(), fx.user(), "student");
        assertUpdatedAtRefreshed("question_answers", id, "content = 'Đã sửa'");
    }

    // --------------------------------------------------------------------------------------------------- certificates

    @Test
    void insertCertificate_valid_storesSnapshotAndIssuedAt() {
        long id = insertCertificate(enrollment(), "EDU-AB12-CD34-EF56");

        var row = jdbc.sql("""
                SELECT certificate_code, student_name, course_title, instructor_name, issued_at
                FROM certificates WHERE id = ?""").param(id).query().singleRow();
        assertThat(row.get("certificate_code")).isEqualTo("EDU-AB12-CD34-EF56");
        assertThat(row.get("student_name")).isEqualTo("Nguyễn Văn A");
        assertThat(row.get("course_title")).isEqualTo("Lập trình Java");
        assertThat(row.get("instructor_name")).isEqualTo("Trần Thị B");
        assertThat(row.get("issued_at")).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"EDU-0000-0000-0000", "EDU-ZZZZ-9999-A1B2"})
    void insertCertificate_validCode_accepted_D47(String code) {
        long id = insertCertificate(enrollment(), code);
        assertThat(id).isPositive();
    }

    @Test
    void insertCertificate_secondForEnrollment_rejected_BR39() {
        long enrollment = enrollment();
        insertCertificate(enrollment, uniqueCode());
        assertViolates("uq_certificates_enrollment", () -> insertCertificate(enrollment, uniqueCode()));
    }

    @Test
    void insertCertificate_duplicateCode_rejected_D47() {
        insertCertificate(enrollment(), "EDU-TRUN-G000-0001");
        long other = enrollment();
        assertViolates("uq_certificates_code", () -> insertCertificate(other, "EDU-TRUN-G000-0001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"edu-abcd-efgh-ijkl", "EDU-abcd-EFGH-IJKL", "EDU-ÁBCD-EFGH-IJKL", "ĐEU-ABCD-EFGH-IJKL",
            "EDU-ABC-EFGH-IJKL", "EDU-ABCD-EFGH-IJKL5", "EDU-ABCDE-FGH-IJKL", "EDX-ABCD-EFGH-IJKL",
            "EDU_ABCD_EFGH_IJKL", " EDU-ABCD-EFGH-IJK", "EDU-ABCD-EFGH-IJK ", ""})
    void insertCertificate_invalidCode_rejected_D47(String code) {
        long enrollment = enrollment();
        assertCheckViolated("ck_certificates_code", () -> insertCertificate(enrollment, code));
    }

    @Test
    void insertCertificate_unknownEnrollment_rejected() {
        assertViolates("fk_certificates_enrollment_id", () -> insertCertificate(-1L, uniqueCode()));
    }

    // ----------------------------------------------------------------------------------- ON DELETE (DDD §6 #21–34)

    @Test
    void deleteLesson_cascadesProgress_andDetachesQuestions() {
        Enrolled e = enrolled();
        long progress = fx.lessonProgress(e.enrollment(), e.lesson());
        long question = fx.question(e.enrollment(), e.lesson());

        jdbc.sql("DELETE FROM lessons WHERE id = ?").param(e.lesson()).update();

        assertThat(countWhere("lesson_progresses", "id", progress)).isZero();
        var row = jdbc.sql("SELECT lesson_id, enrollment_id FROM course_questions WHERE id = ?").param(question)
                .query().singleRow();
        assertThat(row.get("lesson_id")).isNull();
        assertThat(row.get("enrollment_id")).isEqualTo(e.enrollment());
    }

    @Test
    void deleteEnrollment_withProgress_restricted_BR28() {
        Enrolled e = enrolled();
        fx.lessonProgress(e.enrollment(), e.lesson());
        assertViolates("fk_lesson_progresses_enrollment_id", () -> deleteEnrollment(e.enrollment()));
    }

    @Test
    void deleteEnrollment_withReview_restricted_BR28() {
        long enrollment = enrollment();
        insertReview(enrollment, 5);
        assertViolates("fk_course_reviews_enrollment_id", () -> deleteEnrollment(enrollment));
    }

    @Test
    void deleteEnrollment_withQuestion_restricted_BR28() {
        long enrollment = enrollment();
        fx.question(enrollment, null);
        assertViolates("fk_course_questions_enrollment_id", () -> deleteEnrollment(enrollment));
    }

    @Test
    void deleteEnrollment_withCertificate_restricted_BR28() {
        long enrollment = enrollment();
        insertCertificate(enrollment, uniqueCode());
        assertViolates("fk_certificates_enrollment_id", () -> deleteEnrollment(enrollment));
    }

    @Test
    void deletePaidOrder_referencedByEnrollment_restricted_BR28() {
        long student = fx.user();
        long order = fx.order(student, "paid");
        fx.purchaseEnrollment(student, fx.course(), order);
        assertViolates("fk_enrollments_order_id",
                () -> jdbc.sql("DELETE FROM orders WHERE id = ?").param(order).update());
    }

    @Test
    void deleteCourse_withEnrollments_restricted_BR28() {
        long course = fx.course();
        fx.freeEnrollment(fx.user(), course);
        assertViolates("fk_enrollments_course_id",
                () -> jdbc.sql("DELETE FROM courses WHERE id = ?").param(course).update());
    }

    @Test
    void deleteQuestion_withAnswers_restricted_BR28() {
        long question = question();
        insertAnswer(question, fx.user(), "instructor");
        assertViolates("fk_question_answers_question_id",
                () -> jdbc.sql("DELETE FROM course_questions WHERE id = ?").param(question).update());
    }

    @Test
    void deleteUser_whoIsEnrolled_restricted_BR28() {
        long student = fx.user();
        fx.freeEnrollment(student, fx.course());
        assertViolates("fk_enrollments_student_id", () -> deleteUser(student));
    }

    @Test
    void deleteUser_whoAnswered_restricted_BR28() {
        long author = fx.user();
        insertAnswer(question(), author, "instructor");
        assertViolates("fk_question_answers_author_id", () -> deleteUser(author));
    }

    @Test
    void deleteUser_whoModeratedReview_restricted_BR28() {
        long admin = fx.user();
        insertReview(enrollment(), 1, "hidden", admin, PAST);
        assertViolates("fk_course_reviews_moderated_by", () -> deleteUser(admin));
    }

    @Test
    void deleteUser_whoModeratedQuestion_restricted_BR28() {
        long admin = fx.user();
        insertQuestion(enrollment(), "Tiêu đề", "Nội dung", "hidden", admin, PAST);
        assertViolates("fk_course_questions_moderated_by", () -> deleteUser(admin));
    }

    @Test
    void deleteUser_whoModeratedAnswer_restricted_BR28() {
        long admin = fx.user();
        insertAnswer(question(), fx.user(), "student", "Trả lời", "hidden", admin, PAST);
        assertViolates("fk_question_answers_moderated_by", () -> deleteUser(admin));
    }

    // -------------------------------------------------------------------------------------------- schema metadata

    @Test
    void foreignKeys_haveDddNamesAndDeleteRules() {
        assertThat(foreignKeyDeleteRules(jdbc, "enrollments", "lesson_progresses", "course_reviews", "course_questions",
                "question_answers", "certificates"))
                .containsExactly(
                        "fk_certificates_enrollment_id=RESTRICT",
                        "fk_course_questions_enrollment_id=RESTRICT",
                        "fk_course_questions_lesson_id=SET NULL",
                        "fk_course_questions_moderated_by=RESTRICT",
                        "fk_course_reviews_enrollment_id=RESTRICT",
                        "fk_course_reviews_moderated_by=RESTRICT",
                        "fk_enrollments_course_id=RESTRICT",
                        "fk_enrollments_order_id=RESTRICT",
                        "fk_enrollments_student_id=RESTRICT",
                        "fk_lesson_progresses_enrollment_id=RESTRICT",
                        "fk_lesson_progresses_lesson_id=CASCADE",
                        "fk_question_answers_author_id=RESTRICT",
                        "fk_question_answers_moderated_by=RESTRICT",
                        "fk_question_answers_question_id=RESTRICT");
    }

    @Test
    void indexes_haveExpectedColumns() {
        assertThat(indexColumns(jdbc, "enrollments", "uq_enrollments_student_course"))
                .containsExactly("student_id", "course_id");
        assertThat(indexColumns(jdbc, "enrollments", "ix_enrollments_course")).containsExactly("course_id");
        assertThat(indexColumns(jdbc, "enrollments", "ix_enrollments_order")).containsExactly("order_id");
        assertThat(indexColumns(jdbc, "lesson_progresses", "uq_lesson_progresses_enrollment_lesson"))
                .containsExactly("enrollment_id", "lesson_id");
        assertThat(indexColumns(jdbc, "lesson_progresses", "ix_lesson_progresses_lesson")).containsExactly("lesson_id");
        assertThat(indexColumns(jdbc, "lesson_progresses", "ix_lesson_progresses_resume"))
                .containsExactly("enrollment_id", "last_accessed_at DESC");
        assertThat(indexColumns(jdbc, "course_reviews", "uq_course_reviews_enrollment"))
                .containsExactly("enrollment_id");
        assertThat(indexColumns(jdbc, "course_reviews", "ix_course_reviews_moderated_by"))
                .containsExactly("moderated_by");
        assertThat(indexColumns(jdbc, "course_questions", "ix_course_questions_enrollment"))
                .containsExactly("enrollment_id", "created_at DESC");
        assertThat(indexColumns(jdbc, "course_questions", "ix_course_questions_lesson")).containsExactly("lesson_id");
        assertThat(indexColumns(jdbc, "course_questions", "ix_course_questions_moderated_by"))
                .containsExactly("moderated_by");
        assertThat(indexColumns(jdbc, "question_answers", "ix_question_answers_question"))
                .containsExactly("question_id", "created_at");
        assertThat(indexColumns(jdbc, "question_answers", "ix_question_answers_instructor_visible"))
                .containsExactly("question_id", "author_type", "visibility_status");
        assertThat(indexColumns(jdbc, "question_answers", "ix_question_answers_author")).containsExactly("author_id");
        assertThat(indexColumns(jdbc, "question_answers", "ix_question_answers_moderated_by"))
                .containsExactly("moderated_by");
        assertThat(indexColumns(jdbc, "certificates", "uq_certificates_enrollment")).containsExactly("enrollment_id");
        assertThat(indexColumns(jdbc, "certificates", "uq_certificates_code")).containsExactly("certificate_code");
    }

    @Test
    void indexes_areOnlyTheDddOnes_everyFkReusesOne() {
        // MySQL would add an index named after the FK if no existing index led with the FK column
        assertThat(indexNames("enrollments")).containsExactlyInAnyOrder("ix_enrollments_course", "ix_enrollments_order",
                "PRIMARY", "uq_enrollments_student_course");
        assertThat(indexNames("lesson_progresses")).containsExactlyInAnyOrder("ix_lesson_progresses_lesson",
                "ix_lesson_progresses_resume", "PRIMARY", "uq_lesson_progresses_enrollment_lesson");
        assertThat(indexNames("course_reviews")).containsExactlyInAnyOrder("ix_course_reviews_moderated_by", "PRIMARY",
                "uq_course_reviews_enrollment");
        assertThat(indexNames("course_questions")).containsExactlyInAnyOrder("ix_course_questions_enrollment",
                "ix_course_questions_lesson", "ix_course_questions_moderated_by", "PRIMARY");
        assertThat(indexNames("question_answers")).containsExactlyInAnyOrder("ix_question_answers_author",
                "ix_question_answers_instructor_visible", "ix_question_answers_moderated_by",
                "ix_question_answers_question", "PRIMARY");
        assertThat(indexNames("certificates")).containsExactlyInAnyOrder("PRIMARY", "uq_certificates_code",
                "uq_certificates_enrollment");
    }

    @Test
    void columns_matchDdd() {
        assertThat(columns("enrollments")).containsExactly("id", "student_id", "course_id", "order_id",
                "enroll_source", "enrolled_at");
        assertThat(columns("lesson_progresses")).containsExactly("id", "enrollment_id", "lesson_id", "is_completed",
                "completed_at", "last_accessed_at");
        assertThat(columns("course_reviews")).containsExactly("id", "enrollment_id", "rating", "comment",
                "visibility_status", "moderated_by", "moderated_at", "created_at", "updated_at");
        assertThat(columns("course_questions")).containsExactly("id", "enrollment_id", "lesson_id", "title", "content",
                "is_resolved", "visibility_status", "moderated_by", "moderated_at", "created_at", "updated_at");
        assertThat(columns("question_answers")).containsExactly("id", "question_id", "author_id", "author_type",
                "content", "visibility_status", "moderated_by", "moderated_at", "created_at", "updated_at");
        assertThat(columns("certificates")).containsExactly("id", "enrollment_id", "certificate_code", "student_name",
                "course_title", "instructor_name", "issued_at");
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /** A free enrollment in a course that has one chapter with one video lesson. */
    private record Enrolled(long course, long chapter, long lesson, long enrollment) {
    }

    private Enrolled enrolled() {
        long course = fx.course();
        long chapter = fx.chapter(course, 1);
        long lesson = fx.videoLesson(chapter, 1);
        return new Enrolled(course, chapter, lesson, fx.freeEnrollment(fx.user(), course));
    }

    /** A free enrollment of a new student in a new course. */
    private long enrollment() {
        return fx.freeEnrollment(fx.user(), fx.course());
    }

    /** A visible question of a new enrollment, attached to no lesson. */
    private long question() {
        return fx.question(enrollment(), null);
    }

    private long insertEnrollment(long studentId, long courseId, Long orderId, String source) {
        return fx.insert(jdbc.sql("""
                INSERT INTO enrollments (student_id, course_id, order_id, enroll_source) VALUES (?, ?, ?, ?)""")
                .params(studentId, courseId, orderId, source));
    }

    private long insertProgress(long enrollmentId, long lessonId, boolean completed, String completedAt) {
        return fx.insert(jdbc.sql("""
                INSERT INTO lesson_progresses (enrollment_id, lesson_id, is_completed, completed_at)
                VALUES (?, ?, ?, ?)""")
                .params(enrollmentId, lessonId, completed, completedAt));
    }

    private long insertReview(long enrollmentId, int rating) {
        return fx.insert(jdbc.sql("INSERT INTO course_reviews (enrollment_id, rating) VALUES (?, ?)")
                .params(enrollmentId, rating));
    }

    private long insertReview(long enrollmentId, int rating, String visibility, Long moderatedBy, String moderatedAt) {
        return fx.insert(jdbc.sql("""
                INSERT INTO course_reviews (enrollment_id, rating, comment, visibility_status, moderated_by,
                                            moderated_at)
                VALUES (?, ?, 'Khóa học hay', ?, ?, ?)""")
                .params(enrollmentId, rating, visibility, moderatedBy, moderatedAt));
    }

    private long insertQuestion(long enrollmentId, String title, String content, String visibility, Long moderatedBy,
                                String moderatedAt) {
        return fx.insert(jdbc.sql("""
                INSERT INTO course_questions (enrollment_id, title, content, visibility_status, moderated_by,
                                              moderated_at)
                VALUES (?, ?, ?, ?, ?, ?)""")
                .params(enrollmentId, title, content, visibility, moderatedBy, moderatedAt));
    }

    private long insertAnswer(long questionId, long authorId, String authorType) {
        return fx.insert(jdbc.sql("""
                INSERT INTO question_answers (question_id, author_id, author_type, content)
                VALUES (?, ?, ?, 'Trả lời')""")
                .params(questionId, authorId, authorType));
    }

    private long insertAnswer(long questionId, long authorId, String authorType, String content, String visibility,
                              Long moderatedBy, String moderatedAt) {
        return fx.insert(jdbc.sql("""
                INSERT INTO question_answers (question_id, author_id, author_type, content, visibility_status,
                                              moderated_by, moderated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(questionId, authorId, authorType, content, visibility, moderatedBy, moderatedAt));
    }

    private long insertCertificate(long enrollmentId, String code) {
        return fx.insert(jdbc.sql("""
                INSERT INTO certificates (enrollment_id, certificate_code, student_name, course_title, instructor_name)
                VALUES (?, ?, 'Nguyễn Văn A', 'Lập trình Java', 'Trần Thị B')""")
                .params(enrollmentId, code));
    }

    /** A valid certificate code unique within the test JVM. */
    private static String uniqueCode() {
        String n = String.format("%012d", SchemaFixtures.next());
        return "EDU-" + n.substring(0, 4) + "-" + n.substring(4, 8) + "-" + n.substring(8);
    }

    private void deleteEnrollment(long id) {
        jdbc.sql("DELETE FROM enrollments WHERE id = ?").param(id).update();
    }

    private void deleteUser(long id) {
        jdbc.sql("DELETE FROM users WHERE id = ?").param(id).update();
    }

    private long countWhere(String table, String column, long value) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?").param(value)
                .query(Long.class).single();
    }

    private List<String> columns(String table) {
        return jdbc.sql("""
                SELECT COLUMN_NAME FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION""")
                .param(table).query(String.class).list();
    }

    /** Distinct index names of a table (the PRIMARY KEY is named PRIMARY). */
    private List<String> indexNames(String table) {
        return jdbc.sql("""
                SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?""")
                .param(table).query(String.class).list();
    }

    /** Backdates updated_at, runs the update and asserts ON UPDATE CURRENT_TIMESTAMP(6) moved it forward. */
    private void assertUpdatedAtRefreshed(String table, long id, String assignments) {
        jdbc.sql("UPDATE " + table + " SET updated_at = '2020-01-01 00:00:00' WHERE id = ?").param(id).update();
        jdbc.sql("UPDATE " + table + " SET " + assignments + " WHERE id = ?").param(id).update();

        LocalDateTime updatedAt = jdbc.sql("SELECT updated_at FROM " + table + " WHERE id = ?")
                .param(id).query(LocalDateTime.class).single();
        assertThat(updatedAt).isAfter(LocalDateTime.of(2020, 1, 2, 0, 0));
    }
}
