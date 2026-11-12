package com.edumy.learning.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.edumy.learning.entity.AuthorType;
import com.edumy.learning.entity.Certificate;
import com.edumy.learning.entity.ContentStatus;
import com.edumy.learning.entity.CourseQuestion;
import com.edumy.learning.entity.CourseReview;
import com.edumy.learning.entity.Enrollment;
import com.edumy.learning.entity.EnrollmentSource;
import com.edumy.learning.entity.LessonProgress;
import com.edumy.learning.entity.QuestionAnswer;
import com.edumy.learning.entity.ReviewStatus;
import com.edumy.learning.repository.CertificateRepository.StudentCertificateView;
import com.edumy.learning.repository.CourseQuestionRepository.QuestionSummaryView;
import com.edumy.learning.repository.EnrollmentRepository.CourseStudentView;
import com.edumy.learning.repository.EnrollmentRepository.StudentEnrollmentView;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.SchemaFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Learning repositories on real MySQL 8.4 (database {@code edumy}, no seed). Every test runs in a rolled-back
 * transaction; users, courses, chapters, lessons and orders (other modules' tables, FK parents only) are inserted with
 * plain SQL in the same transaction, and every unique value is unique per test, so nothing is left in the shared
 * database (docs/ai/07). A test that provokes a constraint or trigger violation ends with it.
 */
@MySqlSpringBootTest
@Transactional
class LearningRepositoryIT {

    static final Instant NOW = Instant.parse("2026-03-04T05:06:07.123456Z");

    @Autowired
    EntityManager em;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    EnrollmentRepository enrollments;
    @Autowired
    LessonProgressRepository progresses;
    @Autowired
    CourseReviewRepository reviews;
    @Autowired
    CourseQuestionRepository questions;
    @Autowired
    QuestionAnswerRepository answers;
    @Autowired
    CertificateRepository certificates;

    SchemaFixtures fx;
    long student;
    long otherStudent;
    long admin;
    long instructor;
    long course;
    long lesson1;
    long lesson2;
    long lesson3;

    @BeforeEach
    void setUp() {
        fx = new SchemaFixtures(jdbc);
        student = fx.user();
        otherStudent = fx.user();
        admin = fx.user();
        instructor = fx.user();
        course = fx.course(instructor);
        long chapter = fx.chapter(course, 1);
        lesson1 = fx.videoLesson(chapter, 1);
        lesson2 = fx.videoLesson(chapter, 2);
        lesson3 = fx.videoLesson(chapter, 3);
    }

    // -------------------------------------------------------------------------------------------------- enrollments

    @Test
    void enrollment_freeAndPurchase_roundTrip_lookups_UCS15_BR10() {
        long paidCourse = fx.course(instructor);
        long order = fx.order(student, "paid");
        Enrollment free = enrollments.save(Enrollment.free(student, course, NOW));
        Enrollment bought = enrollments.saveAndFlush(Enrollment.purchase(student, paidCourse, order, NOW));
        em.clear();

        Map<String, Object> row = jdbc.sql("""
                SELECT enroll_source, order_id, DATE_FORMAT(enrolled_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS enrolled
                FROM enrollments WHERE id = ?""").param(bought.getId()).query().singleRow();
        assertThat(row.get("enroll_source")).isEqualTo("purchase");
        assertThat(row.get("order_id")).isEqualTo(order);
        assertThat(row.get("enrolled")).isEqualTo(NOW.toString());

        Enrollment loaded = enrollments.findByStudentIdAndCourseId(student, course).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(free.getId());
        assertThat(loaded.getEnrollSource()).isEqualTo(EnrollmentSource.FREE);
        assertThat(loaded.getOrderId()).isNull();
        assertThat(loaded.getEnrolledAt()).isEqualTo(NOW);
        assertThat(enrollments.findByStudentIdAndCourseId(otherStudent, course)).isEmpty();
        assertThat(enrollments.existsByStudentIdAndCourseId(student, paidCourse)).isTrue();
        assertThat(enrollments.existsByStudentIdAndCourseId(otherStudent, paidCourse)).isFalse();
    }

    @Test
    void enrollment_sameStudentAndCourseTwice_violatesUqEnrollmentsStudentCourse_BR17() {
        enrollments.saveAndFlush(Enrollment.free(student, course, NOW));

        assertThatThrownBy(() -> enrollments.saveAndFlush(Enrollment.free(student, course, NOW.plusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("uq_enrollments_student_course");
    }

    @Test
    void insertPurchaseIfAbsent_newThenDuplicate_returnsOneThenZero_BR17() {
        long order = fx.order(student, "paid");
        long freeCourse = fx.course(instructor);
        Enrollment free = enrollments.saveAndFlush(Enrollment.free(student, freeCourse, NOW));

        assertThat(enrollments.insertPurchaseIfAbsent(student, course, order, NOW)).isEqualTo(1);
        assertThat(enrollments.insertPurchaseIfAbsent(student, course, order, NOW.plusSeconds(5))).isZero();
        assertThat(enrollments.insertPurchaseIfAbsent(student, freeCourse, order, NOW)).isZero();

        Map<String, Object> row = jdbc.sql("""
                SELECT enroll_source, order_id, DATE_FORMAT(enrolled_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS enrolled
                FROM enrollments WHERE student_id = ? AND course_id = ?""").params(student, course).query()
                .singleRow();
        assertThat(row.get("enroll_source")).isEqualTo("purchase");
        assertThat(row.get("order_id")).isEqualTo(order);
        assertThat(row.get("enrolled")).isEqualTo(NOW.toString());
        assertThat(jdbc.sql("SELECT enroll_source FROM enrollments WHERE id = ?").param(free.getId())
                .query(String.class).single()).isEqualTo("free");
        em.clear();
        Enrollment granted = enrollments.findByStudentIdAndCourseId(student, course).orElseThrow();
        assertThat(granted.getEnrollSource()).isEqualTo(EnrollmentSource.PURCHASE);
        assertThat(granted.getEnrolledAt()).isEqualTo(NOW);
    }

    // ----------------------------------------------------------------------------------------------------- progress

    @Test
    void progress_countsResumeAndSummaries_UCS08_UCS11_D33_D34() {
        long laterCourse = fx.course(instructor);
        Enrollment enrollment = enrollments.save(Enrollment.free(student, course, NOW));
        Enrollment untouched = enrollments.save(Enrollment.free(student, laterCourse, NOW.plusSeconds(100)));
        enrollments.save(Enrollment.free(otherStudent, course, NOW.plusSeconds(50)));
        LessonProgress p1 = progresses.save(LessonProgress.start(enrollment, lesson1, NOW));
        LessonProgress p2 = progresses.save(LessonProgress.start(enrollment, lesson2, NOW.plusSeconds(10)));
        LessonProgress p3 = progresses.save(LessonProgress.start(enrollment, lesson3, NOW.plusSeconds(20)));
        p1.complete(NOW.plusSeconds(5));
        p2.complete(NOW.plusSeconds(15));
        p3.complete(NOW.plusSeconds(25));
        em.flush();
        assertThat(jdbc.sql("SELECT completed_at IS NOT NULL FROM lesson_progresses WHERE id = ?")
                .param(p3.getId()).query(Long.class).single()).isEqualTo(1L);
        p3.uncomplete();
        p1.visit(NOW.plusSeconds(30));
        em.flush();
        em.clear();

        Map<String, Object> row = jdbc.sql("""
                SELECT is_completed, completed_at FROM lesson_progresses WHERE id = ?""").param(p3.getId()).query()
                .singleRow();
        assertThat(row.get("is_completed")).isEqualTo(false);
        assertThat(row.get("completed_at")).isNull();
        assertThat(progresses.countByEnrollmentIdAndCompletedTrue(enrollment.getId())).isEqualTo(2);
        assertThat(progresses.countByEnrollmentIdAndCompletedTrue(untouched.getId())).isZero();
        assertThat(progresses.findByEnrollmentId(enrollment.getId())).extracting(LessonProgress::getLessonId)
                .containsExactlyInAnyOrder(lesson1, lesson2, lesson3);
        LessonProgress resume = progresses.findFirstByEnrollmentIdOrderByLastAccessedAtDescIdDesc(enrollment.getId())
                .orElseThrow();
        assertThat(resume.getLessonId()).isEqualTo(lesson1);
        assertThat(resume.getLastAccessedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(progresses.findFirstByEnrollmentIdOrderByLastAccessedAtDescIdDesc(untouched.getId())).isEmpty();
        LessonProgress second = progresses.findByEnrollmentIdAndLessonId(enrollment.getId(), lesson2).orElseThrow();
        assertThat(second.isCompleted()).isTrue();
        assertThat(second.getCompletedAt()).isEqualTo(NOW.plusSeconds(15));
        assertThat(progresses.findByEnrollmentIdAndLessonId(untouched.getId(), lesson2)).isEmpty();

        Page<StudentEnrollmentView> mine = enrollments.findSummariesByStudentId(student, PageRequest.of(0, 10));
        assertThat(mine.getTotalElements()).isEqualTo(2);
        assertThat(mine.getContent()).extracting(StudentEnrollmentView::getId)
                .containsExactly(untouched.getId(), enrollment.getId());
        StudentEnrollmentView fresh = mine.getContent().get(0);
        assertThat(fresh.getCourseId()).isEqualTo(laterCourse);
        assertThat(fresh.getCompletedLessonCount()).isZero();
        assertThat(fresh.getLastAccessedLessonId()).isNull();
        assertThat(fresh.getLastAccessedAt()).isNull();
        StudentEnrollmentView started = mine.getContent().get(1);
        assertThat(started.getCourseId()).isEqualTo(course);
        assertThat(started.getEnrollSource()).isEqualTo(EnrollmentSource.FREE);
        assertThat(started.getEnrolledAt()).isEqualTo(NOW);
        assertThat(started.getCompletedLessonCount()).isEqualTo(2);
        assertThat(started.getLastAccessedLessonId()).isEqualTo(lesson1);
        assertThat(started.getLastAccessedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(enrollments.findSummariesByStudentId(student, PageRequest.of(1, 1)).getContent())
                .extracting(StudentEnrollmentView::getId).containsExactly(enrollment.getId());

        Page<CourseStudentView> students = enrollments.findStudentsByCourseId(course, PageRequest.of(0, 10));
        assertThat(students.getTotalElements()).isEqualTo(2);
        assertThat(students.getContent()).extracting(CourseStudentView::getStudentId)
                .containsExactly(otherStudent, student);
        CourseStudentView active = students.getContent().get(1);
        assertThat(active.getId()).isEqualTo(enrollment.getId());
        assertThat(active.getCompletedLessonCount()).isEqualTo(2);
        assertThat(active.getLastAccessedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(students.getContent().get(0).getLastAccessedAt()).isNull();
    }

    @Test
    void progress_lessonOfAnotherCourse_triggerSignals_BR24() {
        Enrollment enrollment = enrollments.save(Enrollment.free(student, course, NOW));
        long foreignLesson = fx.videoLesson(fx.chapter(fx.course(instructor), 1), 1);

        Throwable thrown = catchThrowable(() -> progresses.saveAndFlush(
                LessonProgress.start(enrollment, foreignLesson, NOW)));

        assertThat(thrown).isNotNull().rootCause().isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(1644);
            assertThat(e.getSQLState()).isEqualTo("45000");
            assertThat(e.getMessage()).startsWith("BR-24:");
        });
    }

    // ------------------------------------------------------------------------------------------------------ reviews

    @Test
    void review_withdrawHideRestore_persistedUnderChecks_UCA15_UCA17_BR35() {
        long secondAdmin = fx.user();
        Enrollment enrollment = enrollments.save(Enrollment.free(student, course, NOW));
        CourseReview review = reviews.saveAndFlush(CourseReview.create(enrollment, 4, "Hay", NOW));
        review.withdraw();
        review.hide(admin, NOW.plusSeconds(60));
        reviews.flush();

        Map<String, Object> hidden = reviewRow(review.getId());
        assertThat(hidden.get("visibility_status")).isEqualTo("hidden");
        assertThat(hidden.get("moderated_by")).isEqualTo(admin);
        assertThat(hidden.get("moderated")).isEqualTo(NOW.plusSeconds(60).toString());
        assertThat(hidden.get("rating")).isEqualTo(4);

        review.restore(secondAdmin, NOW.plusSeconds(120));
        review.update(5, "  ");
        reviews.flush();
        em.clear();

        Map<String, Object> restored = reviewRow(review.getId());
        assertThat(restored.get("visibility_status")).isEqualTo("visible");
        assertThat(restored.get("moderated_by")).isEqualTo(secondAdmin);
        assertThat(restored.get("comment")).isNull();
        CourseReview loaded = reviews.findByEnrollmentId(enrollment.getId()).orElseThrow();
        assertThat(loaded.getRating()).isEqualTo((short) 5);
        assertThat(loaded.getVisibilityStatus()).isEqualTo(ReviewStatus.VISIBLE);
        assertThat(loaded.getModeratedAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void review_lookupScopedByStudent_otherStudentEmpty_NFR01() {
        Enrollment enrollment = enrollments.save(Enrollment.free(student, course, NOW));
        Enrollment othersEnrollment = enrollments.save(Enrollment.free(otherStudent, course, NOW));
        CourseReview review = reviews.saveAndFlush(CourseReview.create(enrollment, 3, null, NOW));
        em.clear();

        assertThat(reviews.findByIdAndEnrollmentStudentId(review.getId(), student)).isPresent();
        assertThat(reviews.findByIdAndEnrollmentStudentId(review.getId(), otherStudent)).isEmpty();
        assertThat(reviews.findByEnrollmentId(othersEnrollment.getId())).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------- questions

    @Test
    void questions_ofCourse_visibleForViewer_hiddenForAdmin_neverDeleted_UCS17_UCS18() {
        Enrollment mine = enrollments.save(Enrollment.free(student, course, NOW));
        Enrollment others = enrollments.save(Enrollment.free(otherStudent, course, NOW));
        CourseQuestion oldest = ask(mine, lesson1, NOW);
        CourseQuestion hidden = ask(others, null, NOW.plusSeconds(10));
        hidden.hide(admin, NOW.plusSeconds(11));
        CourseQuestion deleted = ask(mine, lesson1, NOW.plusSeconds(20));
        deleted.delete();
        CourseQuestion newest = ask(others, lesson2, NOW.plusSeconds(30));
        newest.markResolved();
        answers.save(QuestionAnswer.post(oldest, instructor, AuthorType.INSTRUCTOR, "Đáp 1", NOW.plusSeconds(40)));
        answers.save(QuestionAnswer.post(oldest, student, AuthorType.STUDENT, "Cảm ơn", NOW.plusSeconds(41)))
                .delete();
        CourseQuestion otherCourse = ask(enrollments.save(Enrollment.free(student, fx.course(instructor), NOW)),
                null, NOW.plusSeconds(50));
        em.flush();
        em.clear();

        Page<QuestionSummaryView> forViewer = questions.findByCourse(course, null, false, PageRequest.of(0, 10));
        assertThat(forViewer.getTotalElements()).isEqualTo(2);
        assertThat(forViewer.getContent()).extracting(QuestionSummaryView::getId)
                .containsExactly(newest.getId(), oldest.getId());
        QuestionSummaryView first = forViewer.getContent().get(1);
        assertThat(first.getCourseId()).isEqualTo(course);
        assertThat(first.getAskerId()).isEqualTo(student);
        assertThat(first.getLessonId()).isEqualTo(lesson1);
        assertThat(first.getTitle()).isEqualTo("Câu hỏi IT");
        assertThat(first.isResolved()).isFalse();
        assertThat(first.getVisibilityStatus()).isEqualTo(ContentStatus.VISIBLE);
        assertThat(first.getCreatedAt()).isEqualTo(NOW);
        assertThat(first.getAnswerCount()).isEqualTo(1);
        assertThat(forViewer.getContent().get(0).isResolved()).isTrue();
        assertThat(questions.findByCourse(course, null, true, PageRequest.of(0, 10)).getContent())
                .extracting(QuestionSummaryView::getId)
                .containsExactly(newest.getId(), hidden.getId(), oldest.getId());
        assertThat(questions.findByCourse(course, lesson1, true, PageRequest.of(0, 10)).getContent())
                .extracting(QuestionSummaryView::getId).containsExactly(oldest.getId());

        Page<QuestionSummaryView> asked = questions.findByStudent(student, PageRequest.of(0, 10));
        assertThat(asked.getContent()).extracting(QuestionSummaryView::getId)
                .containsExactly(otherCourse.getId(), oldest.getId());
        assertThat(questions.findByStudent(otherStudent, PageRequest.of(0, 10)).getContent())
                .extracting(QuestionSummaryView::getId).containsExactly(newest.getId(), hidden.getId());

        assertThat(questions.findCourseIdById(newest.getId())).contains(course);
        assertThat(questions.findCourseIdById(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void questionAndAnswer_lookupsScopedByAskerAndAuthor_otherUserEmpty_NFR01() {
        Enrollment mine = enrollments.save(Enrollment.free(student, course, NOW));
        CourseQuestion question = ask(mine, null, NOW);
        QuestionAnswer answer = answers.save(
                QuestionAnswer.post(question, instructor, AuthorType.INSTRUCTOR, "Đáp", NOW));
        em.flush();
        em.clear();

        assertThat(questions.findByIdAndEnrollmentStudentId(question.getId(), student)).isPresent();
        assertThat(questions.findByIdAndEnrollmentStudentId(question.getId(), otherStudent)).isEmpty();
        assertThat(questions.existsByIdAndEnrollmentStudentId(question.getId(), student)).isTrue();
        assertThat(questions.existsByIdAndEnrollmentStudentId(question.getId(), instructor)).isFalse();
        assertThat(answers.findByIdAndAuthorId(answer.getId(), instructor)).isPresent();
        assertThat(answers.findByIdAndAuthorId(answer.getId(), student)).isEmpty();
        assertThat(answers.existsByIdAndAuthorId(answer.getId(), instructor)).isTrue();
        assertThat(answers.existsByIdAndAuthorId(answer.getId(), otherStudent)).isFalse();
    }

    @Test
    void findForInstructor_unanswered_excludesVisibleInstructorAnswerOnly_UCI22() {
        long otherCourse = fx.course(instructor);
        long foreignCourse = fx.course();
        Enrollment mine = enrollments.save(Enrollment.free(student, course, NOW));
        Enrollment inOther = enrollments.save(Enrollment.free(student, otherCourse, NOW));
        Enrollment inForeign = enrollments.save(Enrollment.free(student, foreignCourse, NOW));
        CourseQuestion answered = ask(mine, null, NOW);
        answers.save(QuestionAnswer.post(answered, instructor, AuthorType.INSTRUCTOR, "Đáp", NOW.plusSeconds(1)));
        answers.save(QuestionAnswer.post(answered, student, AuthorType.STUDENT, "Hỏi thêm", NOW.plusSeconds(2)));
        CourseQuestion onlyFollowUp = ask(mine, null, NOW.plusSeconds(10));
        answers.save(QuestionAnswer.post(onlyFollowUp, student, AuthorType.STUDENT, "Bổ sung", NOW.plusSeconds(11)));
        CourseQuestion hiddenAnswer = ask(inOther, null, NOW.plusSeconds(20));
        answers.save(QuestionAnswer.post(hiddenAnswer, instructor, AuthorType.INSTRUCTOR, "Sai", NOW.plusSeconds(21)))
                .hide(admin, NOW.plusSeconds(22));
        CourseQuestion deletedAnswer = ask(mine, null, NOW.plusSeconds(30));
        answers.save(QuestionAnswer.post(deletedAnswer, instructor, AuthorType.INSTRUCTOR, "Xóa",
                NOW.plusSeconds(31))).delete();
        CourseQuestion noAnswer = ask(mine, null, NOW.plusSeconds(40));
        ask(mine, null, NOW.plusSeconds(50)).hide(admin, NOW.plusSeconds(51));
        ask(mine, null, NOW.plusSeconds(60)).delete();
        ask(inForeign, null, NOW.plusSeconds(70));
        em.flush();
        em.clear();
        List<Long> courseIds = List.of(course, otherCourse);

        Page<QuestionSummaryView> unanswered = questions.findForInstructor(courseIds, true, PageRequest.of(0, 10));
        assertThat(unanswered.getTotalElements()).isEqualTo(4);
        assertThat(unanswered.getContent()).extracting(QuestionSummaryView::getId)
                .containsExactly(noAnswer.getId(), deletedAnswer.getId(), hiddenAnswer.getId(), onlyFollowUp.getId());
        assertThat(unanswered.getContent().get(2).getCourseId()).isEqualTo(otherCourse);
        assertThat(unanswered.getContent().get(2).getAnswerCount()).isZero();
        assertThat(unanswered.getContent().get(3).getAnswerCount()).isEqualTo(1);
        assertThat(questions.findForInstructor(courseIds, true, PageRequest.of(1, 3)).getContent())
                .extracting(QuestionSummaryView::getId).containsExactly(onlyFollowUp.getId());

        Page<QuestionSummaryView> all = questions.findForInstructor(courseIds, false, PageRequest.of(0, 10));
        assertThat(all.getTotalElements()).isEqualTo(5);
        assertThat(all.getContent().getLast().getId()).isEqualTo(answered.getId());
        assertThat(all.getContent().getLast().getAnswerCount()).isEqualTo(2);
    }

    @Test
    void findThread_chronological_noDeleted_hiddenOnlyForAdmin_UCS17() {
        Enrollment mine = enrollments.save(Enrollment.free(student, course, NOW));
        CourseQuestion question = ask(mine, lesson1, NOW);
        QuestionAnswer second = answers.save(
                QuestionAnswer.post(question, student, AuthorType.STUDENT, "Hai", NOW.plusSeconds(20)));
        QuestionAnswer first = answers.save(
                QuestionAnswer.post(question, instructor, AuthorType.INSTRUCTOR, "Một", NOW.plusSeconds(10)));
        QuestionAnswer hidden = answers.save(
                QuestionAnswer.post(question, instructor, AuthorType.INSTRUCTOR, "Ẩn", NOW.plusSeconds(30)));
        hidden.hide(admin, NOW.plusSeconds(31));
        answers.save(QuestionAnswer.post(question, student, AuthorType.STUDENT, "Xóa", NOW.plusSeconds(40)))
                .delete();
        em.flush();
        em.clear();

        assertThat(answers.findThread(question.getId(), false)).extracting(QuestionAnswer::getId)
                .containsExactly(first.getId(), second.getId());
        List<QuestionAnswer> forAdmin = answers.findThread(question.getId(), true);
        assertThat(forAdmin).extracting(QuestionAnswer::getId)
                .containsExactly(first.getId(), second.getId(), hidden.getId());
        QuestionAnswer loadedHidden = forAdmin.getLast();
        assertThat(loadedHidden.getAuthorType()).isEqualTo(AuthorType.INSTRUCTOR);
        assertThat(loadedHidden.getModeratedBy()).isEqualTo(admin);
        assertThat(loadedHidden.getModeratedAt()).isEqualTo(NOW.plusSeconds(31));
        assertThat(loadedHidden.getCreatedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(jdbc.sql("SELECT visibility_status FROM question_answers WHERE id = ?").param(hidden.getId())
                .query(String.class).single()).isEqualTo("hidden");
    }

    // ------------------------------------------------------------------------------------------------- certificates

    @Test
    void certificate_byCodeEnrollmentAndStudent_scoped_BR39_UCS20() {
        Enrollment mine = enrollments.save(Enrollment.free(student, course, NOW));
        Enrollment second = enrollments.save(Enrollment.free(student, fx.course(instructor), NOW));
        Enrollment others = enrollments.save(Enrollment.free(otherStudent, course, NOW));
        String code = code();
        Certificate certificate = certificates.save(Certificate.issue(mine, code, "Trần Thị Bình", "Java cơ bản",
                "Nguyễn Văn An", NOW));
        Certificate newer = certificates.save(Certificate.issue(second, code(), "Trần Thị Bình", "Spring",
                "Nguyễn Văn An", NOW.plusSeconds(60)));
        certificates.save(Certificate.issue(others, code(), "Lê Văn C", "Java cơ bản", "Nguyễn Văn An", NOW));
        em.flush();
        em.clear();

        assertThat(jdbc.sql("SELECT DATE_FORMAT(issued_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM certificates WHERE id = ?")
                .param(certificate.getId()).query(String.class).single()).isEqualTo(NOW.toString());
        Certificate byCode = certificates.findByCertificateCode(code).orElseThrow();
        assertThat(byCode.getId()).isEqualTo(certificate.getId());
        assertThat(byCode.getStudentName()).isEqualTo("Trần Thị Bình");
        assertThat(byCode.getIssuedAt()).isEqualTo(NOW);
        assertThat(certificates.findByCertificateCode("EDU-0000-0000-0000")).isEmpty();
        assertThat(certificates.existsByCertificateCode(code)).isTrue();
        assertThat(certificates.findByEnrollmentId(mine.getId())).get()
                .extracting(Certificate::getCertificateCode).isEqualTo(code);
        assertThat(certificates.findByCertificateCodeAndEnrollmentStudentId(code, student)).isPresent();
        assertThat(certificates.findByCertificateCodeAndEnrollmentStudentId(code, otherStudent)).isEmpty();

        Page<StudentCertificateView> mineList = certificates.findByStudent(student, PageRequest.of(0, 10));
        assertThat(mineList.getTotalElements()).isEqualTo(2);
        assertThat(mineList.getContent()).extracting(StudentCertificateView::getId)
                .containsExactly(newer.getId(), certificate.getId());
        StudentCertificateView view = mineList.getContent().get(1);
        assertThat(view.getEnrollmentId()).isEqualTo(mine.getId());
        assertThat(view.getCourseId()).isEqualTo(course);
        assertThat(view.getCertificateCode()).isEqualTo(code);
        assertThat(view.getCourseTitle()).isEqualTo("Java cơ bản");
        assertThat(view.getInstructorName()).isEqualTo("Nguyễn Văn An");
        assertThat(view.getIssuedAt()).isEqualTo(NOW);
    }

    @Test
    void certificate_secondForEnrollment_violatesUqCertificatesEnrollment_BR39() {
        Enrollment mine = enrollments.save(Enrollment.free(student, course, NOW));
        certificates.saveAndFlush(Certificate.issue(mine, code(), "A", "B", "C", NOW));

        assertThatThrownBy(() -> certificates.saveAndFlush(Certificate.issue(mine, code(), "A", "B", "C", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("uq_certificates_enrollment");
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    CourseQuestion ask(Enrollment enrollment, Long lessonId, Instant createdAt) {
        return questions.save(CourseQuestion.ask(enrollment, lessonId, "Câu hỏi IT", "Nội dung", createdAt));
    }

    Map<String, Object> reviewRow(long reviewId) {
        return jdbc.sql("""
                SELECT visibility_status, rating, comment, moderated_by,
                       DATE_FORMAT(moderated_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS moderated
                FROM course_reviews WHERE id = ?""").param(reviewId).query().singleRow();
    }

    /** A certificate code unique within the test JVM. */
    static String code() {
        long n = SchemaFixtures.next();
        return String.format("EDU-%04d-ITLR-%04d", n % 10_000, (n / 10_000) % 10_000);
    }
}
