package com.edumy.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.edumy.authoring.api.CourseAccessApi;
import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.authoring.api.LessonQueryApi;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserSummary;
import com.edumy.learning.api.CertificateIssued;
import com.edumy.learning.api.QuestionAnsweredByInstructor;
import com.edumy.learning.api.ReviewChanged;
import com.edumy.learning.dto.response.ProgressResponse;
import com.edumy.learning.service.AnswerService;
import com.edumy.learning.service.ProgressService;
import com.edumy.learning.service.ReviewService;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.MySqlModuleTest;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Module test of {@code learning} (authoring and identity mocked through their {@code api}): the services that
 * publish the events of docs/ai/01 §1.10 — {@link ReviewChanged} (UC-S12…S14), {@link QuestionAnsweredByInstructor}
 * (UC-I21, instructor answers only), {@link CertificateIssued} (UC-S10, BR-39, exactly once, BR-40) — with state
 * checked in MySQL. HTTP, IDOR and the access beans are covered by the {@code *WriteIT} classes.
 */
@ApplicationModuleTest(extraIncludes = "shared")
@MySqlModuleTest
class LearningModuleIT {

    @MockitoBean
    CourseQueryApi courseQuery;
    @MockitoBean
    CourseAccessApi courseAccess;
    @MockitoBean
    LessonQueryApi lessons;
    @MockitoBean
    UserQueryApi userQuery;
    @MockitoBean
    RoleQueryApi roles;

    @Autowired
    ReviewService reviews;
    @Autowired
    AnswerService answers;
    @Autowired
    ProgressService progress;
    @Autowired
    TestUsers users;
    @Autowired
    CourseFixtures fixtures;
    @Autowired
    JdbcClient jdbc;

    TestUser student;
    TestUser instructor;
    long course;
    long enrollment;
    String slug;

    @BeforeEach
    void setUp() {
        student = users.create(RoleCode.STUDENT);
        instructor = users.create(RoleCode.INSTRUCTOR);
        course = fixtures.draftCourse(instructor.id());
        enrollment = fixtures.enrollment(student.id(), course);
        slug = jdbc.sql("SELECT slug FROM courses WHERE id = ?").param(course).query(String.class).single();
        given(courseQuery.findSummaries(anyCollection())).willAnswer(call -> Map.of(course,
                new CourseSummary(course, "Khóa học kiểm thử", slug, null, 0, true, instructor.id())));
        given(userQuery.findSummaries(anyCollection())).willAnswer(call -> {
            Map<Long, UserSummary> found = new HashMap<>();
            found.put(student.id(), new UserSummary(student.id(), "Học viên", student.email(), "active"));
            found.put(instructor.id(), new UserSummary(instructor.id(), "Giảng viên", instructor.email(), "active"));
            return found;
        });
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    // ------------------------------------------------------------------------------------------ ReviewService

    @Test
    void createReview_enrolledStudent_publishesReviewChangedWithSlug_UCS12(Scenario scenario) {
        scenario.stimulate(() -> reviews.create(student.id(), course, 5, "Rất hay"))
                .andWaitForEventOfType(ReviewChanged.class)
                .matching(event -> event.courseId().equals(course))
                .toArriveAndVerify(event -> assertThat(event.slug()).isEqualTo(slug));

        assertThat(jdbc.sql("SELECT visibility_status FROM course_reviews WHERE enrollment_id = ?")
                .param(enrollment).query(String.class).single()).isEqualTo("visible");
    }

    @Test
    void editAndWithdrawReview_eachPublishesReviewChanged_UCS13_UCS14(PublishedEvents events) {
        Long reviewId = reviews.create(student.id(), course, 3, "Tạm").review().id();

        reviews.update(student.id(), reviewId, 4, "Khá hơn");
        reviews.withdraw(student.id(), reviewId);

        assertThat(events.ofType(ReviewChanged.class)).hasSize(3); // create, edit, withdraw
        assertThat(events.ofType(ReviewChanged.class)).allSatisfy(event -> {
            assertThat(event.courseId()).isEqualTo(course);
            assertThat(event.slug()).isEqualTo(slug);
        });
        assertThat(jdbc.sql("SELECT visibility_status FROM course_reviews WHERE id = ?").param(reviewId)
                .query(String.class).single()).isEqualTo("withdrawn");
    }

    @Test
    void createReview_twiceUpdatesTheSameRow_refusedPublishesNothing_BR18_AC14(PublishedEvents events) {
        TestUser stranger = users.create(RoleCode.STUDENT);
        reviews.create(student.id(), course, 5, "Lần một");

        assertThat(reviews.create(student.id(), course, 4, "Lần hai").created()).isFalse(); // AC-14: same row
        assertCode(() -> reviews.create(stranger.id(), course, 4, "Chưa ghi danh"), ErrorCode.ENROLLMENT_REQUIRED);

        assertThat(events.ofType(ReviewChanged.class)).hasSize(2); // create + update, nothing for the refusal
        assertThat(jdbc.sql("SELECT COUNT(*) FROM course_reviews WHERE enrollment_id = ?").param(enrollment)
                .query(Long.class).single()).isEqualTo(1L);
    }

    // ------------------------------------------------------------------------------------------ AnswerService

    @Test
    void postAnswer_courseInstructor_publishesQuestionAnsweredByInstructor_UCI21(Scenario scenario) {
        long question = fixtures.question(enrollment);
        given(courseAccess.isCourseInstructor(instructor.id(), course)).willReturn(true);

        scenario.stimulate(() -> answers.post(instructor.id(), question, "Đây là lời giải"))
                .andWaitForEventOfType(QuestionAnsweredByInstructor.class)
                .matching(event -> event.questionId().equals(question))
                .toArriveAndVerify(event -> {
                    assertThat(event.courseId()).isEqualTo(course);
                    assertThat(event.askerId()).isEqualTo(student.id());
                    assertThat(event.instructorId()).isEqualTo(instructor.id());
                    assertThat(event.questionTitle()).isEqualTo("Câu hỏi");
                    assertThat(event.answerId()).isNotNull();
                });

        assertThat(jdbc.sql("SELECT author_type FROM question_answers WHERE question_id = ?").param(question)
                .query(String.class).single()).isEqualTo("instructor");
    }

    @Test
    void postAnswer_askerFollowUpPublishesNothing_strangerRefused_BR38(PublishedEvents events) {
        long question = fixtures.question(enrollment);
        TestUser stranger = users.create(RoleCode.STUDENT);

        answers.post(student.id(), question, "Em hỏi thêm");
        assertCode(() -> answers.post(stranger.id(), question, "Chen ngang"), ErrorCode.QA_REPLY_NOT_ALLOWED);

        assertThat(events.ofType(QuestionAnsweredByInstructor.class)).isEmpty();
        assertThat(jdbc.sql("SELECT author_type FROM question_answers WHERE question_id = ?").param(question)
                .query(String.class).list()).containsExactly("student");
    }

    // ------------------------------------------------------------------------------------------ ProgressService

    @Test
    void setCompletion_lastLesson_issuesCertificateOnceAndPublishesOneEvent_UCS10_BR39_BR40(PublishedEvents events) {
        long lesson = fixtures.lesson(fixtures.chapter(course));
        given(lessons.findCourseId(lesson)).willReturn(Optional.of(course));
        given(lessons.countLessons(Set.of(course))).willReturn(Map.of(course, 1));

        ProgressResponse first = progress.setCompletion(student.id(), lesson, true);
        progress.setCompletion(student.id(), lesson, false); // A1: unmarking never touches the certificate
        ProgressResponse again = progress.setCompletion(student.id(), lesson, true);

        assertThat(first.certificateIssued()).isTrue();
        assertThat(again.certificateIssued()).isFalse();
        assertThat(again.certificateCode()).isEqualTo(first.certificateCode());
        assertThat(events.ofType(CertificateIssued.class)).hasSize(1);
        assertThat(events.ofType(CertificateIssued.class)).singleElement().satisfies(event -> {
            assertThat(event.enrollmentId()).isEqualTo(enrollment);
            assertThat(event.studentId()).isEqualTo(student.id());
            assertThat(event.courseId()).isEqualTo(course);
        });
        assertThat(jdbc.sql("SELECT COUNT(*) FROM certificates WHERE enrollment_id = ?").param(enrollment)
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void setCompletion_notLastLesson_noCertificateNoEvent_UCS10(PublishedEvents events) {
        long lesson = fixtures.lesson(fixtures.chapter(course));
        given(lessons.findCourseId(lesson)).willReturn(Optional.of(course));
        given(lessons.countLessons(Set.of(course))).willReturn(Map.of(course, 2));

        ProgressResponse response = progress.setCompletion(student.id(), lesson, true);

        assertThat(response.certificateIssued()).isFalse();
        assertThat(events.ofType(CertificateIssued.class)).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM certificates WHERE enrollment_id = ?").param(enrollment)
                .query(Long.class).single()).isZero();
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(expected));
    }
}
