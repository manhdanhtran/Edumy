package com.edumy.learning.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.edumy.authoring.api.CourseAccessApi;
import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.LessonQueryApi;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.learning.entity.ContentStatus;
import com.edumy.learning.repository.CourseQuestionRepository;
import com.edumy.learning.repository.CourseQuestionRepository.QuestionAccessView;
import com.edumy.learning.repository.EnrollmentRepository;
import com.edumy.learning.repository.QuestionAnswerRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.TestSecurityContext;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@code @qaAccess} (BR-37, BR-38, D-38, NFR-01). */
@ExtendWith(MockitoExtension.class)
class QaAccessTest {

    static final long ASKER = 1L;
    static final long CALLER = 7L;
    static final long COURSE = 100L;
    static final long QUESTION = 500L;
    static final long ANSWER = 600L;

    @Mock
    CourseQuestionRepository questions;
    @Mock
    QuestionAnswerRepository answers;
    @Mock
    EnrollmentRepository enrollments;
    @Mock
    CourseAccessApi courseAccess;
    @Mock
    CourseQueryApi courseQuery;
    @Mock
    LessonQueryApi lessons;
    @Mock
    RoleQueryApi roles;

    QaAccess access;

    record View(Long getCourseId, Long getAskerId, ContentStatus getVisibilityStatus) implements QuestionAccessView {
    }

    @BeforeEach
    void setUp() {
        EnrollmentAccess enrollmentAccess = new EnrollmentAccess(enrollments, courseAccess, courseQuery, lessons, roles);
        access = new QaAccess(questions, answers, courseAccess, roles, enrollmentAccess);
    }

    @AfterEach
    void tearDown() {
        TestSecurityContext.clear();
    }

    private void question(ContentStatus status) {
        lenient().when(questions.findAccessViewById(QUESTION)).thenReturn(Optional.of(new View(COURSE, ASKER, status)));
    }

    static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    // ----------------------------------------------------------------------------------------------------- canReply

    @Test
    void canReply_asker_true_BR38() {
        TestSecurityContext.authenticate(ASKER, RoleCode.STUDENT);
        question(ContentStatus.VISIBLE);

        assertThat(access.canReply(QUESTION)).isTrue();
    }

    @Test
    void canReply_courseInstructor_true_BR38() {
        TestSecurityContext.authenticate(CALLER, RoleCode.INSTRUCTOR);
        question(ContentStatus.VISIBLE);
        when(courseAccess.isCourseInstructor(CALLER, COURSE)).thenReturn(true);

        assertThat(access.canReply(QUESTION)).isTrue();
    }

    @Test
    void canReply_otherEnrolledStudent_403QaReplyNotAllowed_BR38() {
        TestSecurityContext.authenticate(CALLER, RoleCode.STUDENT);
        question(ContentStatus.VISIBLE);
        when(enrollments.existsByStudentIdAndCourseId(CALLER, COURSE)).thenReturn(true);

        assertCode(() -> access.canReply(QUESTION), ErrorCode.QA_REPLY_NOT_ALLOWED);
    }

    @Test
    void canReply_stranger_404_NFR01() {
        TestSecurityContext.authenticate(CALLER, RoleCode.STUDENT);
        question(ContentStatus.VISIBLE);

        assertCode(() -> access.canReply(QUESTION), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void canReply_revokedInstructorNotEnrolled_404_D07() {
        TestSecurityContext.authenticate(CALLER, RoleCode.INSTRUCTOR); // stale JWT
        question(ContentStatus.VISIBLE);
        when(courseAccess.isCourseInstructor(CALLER, COURSE)).thenReturn(false); // role inactive in the database

        assertCode(() -> access.canReply(QUESTION), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void canReply_missingQuestion_404() {
        TestSecurityContext.authenticate(CALLER, RoleCode.STUDENT);

        assertCode(() -> access.canReply(QUESTION), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void canReply_deletedQuestion_404EvenForAsker() {
        TestSecurityContext.authenticate(ASKER, RoleCode.STUDENT);
        question(ContentStatus.DELETED);

        assertCode(() -> access.canReply(QUESTION), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void canReply_hiddenQuestion_courseInstructorNotAdmin_404() {
        TestSecurityContext.authenticate(CALLER, RoleCode.INSTRUCTOR);
        question(ContentStatus.HIDDEN);

        assertCode(() -> access.canReply(QUESTION), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void canReply_hiddenQuestion_asker_true_serviceDecidesTheConflict_BR41() {
        TestSecurityContext.authenticate(ASKER, RoleCode.STUDENT);
        question(ContentStatus.HIDDEN);

        assertThat(access.canReply(QUESTION)).isTrue();
    }

    @Test
    void canReply_hiddenQuestion_admin_403() {
        TestSecurityContext.authenticate(CALLER, RoleCode.ADMIN);
        question(ContentStatus.HIDDEN);
        when(roles.hasActiveRole(CALLER, RoleCode.ADMIN)).thenReturn(true);

        assertCode(() -> access.canReply(QUESTION), ErrorCode.QA_REPLY_NOT_ALLOWED);
    }

    @Test
    void canReply_adminNotEnrolled_canSeeButNotReply_403_D32() {
        TestSecurityContext.authenticate(CALLER, RoleCode.ADMIN);
        question(ContentStatus.VISIBLE);
        when(roles.hasActiveRole(CALLER, RoleCode.ADMIN)).thenReturn(true);
        when(courseQuery.exists(COURSE)).thenReturn(true);

        assertCode(() -> access.canReply(QUESTION), ErrorCode.QA_REPLY_NOT_ALLOWED);
    }

    // ------------------------------------------------------------------------------------------- isQuestionAuthor

    @Test
    void isQuestionAuthor_asker_true_hiddenStillTrue() {
        TestSecurityContext.authenticate(ASKER, RoleCode.STUDENT);
        question(ContentStatus.VISIBLE);
        assertThat(access.isQuestionAuthor(QUESTION)).isTrue();

        question(ContentStatus.HIDDEN);
        assertThat(access.isQuestionAuthor(QUESTION)).isTrue();
    }

    @Test
    void isQuestionAuthor_notAuthor_missing_deleted_404() {
        TestSecurityContext.authenticate(CALLER, RoleCode.STUDENT);
        question(ContentStatus.VISIBLE);
        assertCode(() -> access.isQuestionAuthor(QUESTION), ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> access.isQuestionAuthor(QUESTION + 1), ErrorCode.RESOURCE_NOT_FOUND);

        TestSecurityContext.authenticate(ASKER, RoleCode.STUDENT);
        question(ContentStatus.DELETED);
        assertCode(() -> access.isQuestionAuthor(QUESTION), ErrorCode.RESOURCE_NOT_FOUND);
    }

    // --------------------------------------------------------------------------------------------- isAnswerAuthor

    @Test
    void isAnswerAuthor_author_true_otherwise404() {
        TestSecurityContext.authenticate(CALLER, RoleCode.STUDENT);
        lenient().when(answers.existsNotDeletedByIdAndAuthorId(ANSWER, CALLER)).thenReturn(true);

        assertThat(access.isAnswerAuthor(ANSWER)).isTrue();
        assertCode(() -> access.isAnswerAuthor(ANSWER + 1), ErrorCode.RESOURCE_NOT_FOUND);
    }
}
