package com.edumy.learning.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.edumy.authoring.api.CourseAccessApi;
import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.LessonQueryApi;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.learning.repository.EnrollmentRepository;
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

/** {@code @enrollmentAccess} (UC-S09, D-32, NFR-01): true or 404, never false. */
@ExtendWith(MockitoExtension.class)
class EnrollmentAccessTest {

    static final long USER = 7L;
    static final long COURSE = 100L;
    static final long LESSON = 300L;

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

    EnrollmentAccess access;

    @BeforeEach
    void setUp() {
        access = new EnrollmentAccess(enrollments, courseAccess, courseQuery, lessons, roles);
        TestSecurityContext.authenticate(USER, RoleCode.STUDENT);
    }

    @AfterEach
    void tearDown() {
        TestSecurityContext.clear();
    }

    static void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void isEnrolled_enrolled_true() {
        when(enrollments.existsByStudentIdAndCourseId(USER, COURSE)).thenReturn(true);

        assertThat(access.isEnrolled(COURSE)).isTrue();
    }

    @Test
    void isEnrolled_notEnrolledOrMissingCourse_404() {
        assertNotFound(() -> access.isEnrolled(COURSE));
        assertNotFound(() -> access.isEnrolled(Long.MAX_VALUE));
    }

    @Test
    void isEnrolled_courseInstructorWithoutEnrollment_404() {
        lenient().when(courseAccess.isCourseInstructor(USER, COURSE)).thenReturn(true);

        assertNotFound(() -> access.isEnrolled(COURSE));
    }

    @Test
    void canViewContent_enrolled_true() {
        when(enrollments.existsByStudentIdAndCourseId(USER, COURSE)).thenReturn(true);

        assertThat(access.canViewContent(COURSE)).isTrue();
    }

    @Test
    void canViewContent_courseInstructor_true_D32() {
        when(courseAccess.isCourseInstructor(USER, COURSE)).thenReturn(true);

        assertThat(access.canViewContent(COURSE)).isTrue();
    }

    @Test
    void canViewContent_adminFromDatabase_existingCourse_true_D32() {
        when(roles.hasActiveRole(USER, RoleCode.ADMIN)).thenReturn(true);
        when(courseQuery.exists(COURSE)).thenReturn(true);

        assertThat(access.canViewContent(COURSE)).isTrue();
    }

    @Test
    void canViewContent_admin_missingCourse_404() {
        when(roles.hasActiveRole(USER, RoleCode.ADMIN)).thenReturn(true);

        assertNotFound(() -> access.canViewContent(Long.MAX_VALUE));
    }

    @Test
    void canViewContent_adminOnlyInStaleJwt_404_BR26() {
        TestSecurityContext.authenticate(USER, RoleCode.STUDENT, RoleCode.ADMIN);
        when(roles.hasActiveRole(USER, RoleCode.ADMIN)).thenReturn(false);

        assertNotFound(() -> access.canViewContent(COURSE));
    }

    @Test
    void canViewContent_stranger_404() {
        assertNotFound(() -> access.canViewContent(COURSE));
    }

    @Test
    void canViewLesson_resolvesCourse_missingLesson404() {
        lenient().when(lessons.findCourseId(LESSON)).thenReturn(Optional.of(COURSE));
        when(enrollments.existsByStudentIdAndCourseId(USER, COURSE)).thenReturn(true);

        assertThat(access.canViewLesson(LESSON)).isTrue();
        assertThat(access.isEnrolledForLesson(LESSON)).isTrue();
        assertNotFound(() -> access.canViewLesson(LESSON + 1));
        assertNotFound(() -> access.isEnrolledForLesson(LESSON + 1));
    }

    @Test
    void isEnrolledForLesson_instructorOfTheCourse_404_noProgressWithoutEnrollment_D32() {
        when(lessons.findCourseId(LESSON)).thenReturn(Optional.of(COURSE));
        lenient().when(courseAccess.isCourseInstructor(anyLong(), anyLong())).thenReturn(true);

        assertNotFound(() -> access.isEnrolledForLesson(LESSON));
    }
}
