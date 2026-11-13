package com.edumy.authoring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.edumy.authoring.entity.CourseStatus;
import com.edumy.authoring.repository.ChapterRepository;
import com.edumy.authoring.repository.CourseRepository;
import com.edumy.authoring.repository.LessonRepository;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.TestSecurityContext;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@code @courseAccess}, {@code @chapterAccess}, {@code @lessonAccess} semantics (Q7, BR-26, BR-33, D-07, NFR-01). */
@ExtendWith(MockitoExtension.class)
class CourseAccessTest {

    static final long USER = 7L;
    static final long COURSE = 100L;
    static final long CHAPTER = 200L;
    static final long LESSON = 300L;

    @Mock
    CourseRepository courses;
    @Mock
    ChapterRepository chapters;
    @Mock
    LessonRepository lessons;
    @Mock
    RoleQueryApi roles;

    CourseAccess courseAccess;
    ChapterAccess chapterAccess;
    LessonAccess lessonAccess;

    @BeforeEach
    void setUp() {
        courseAccess = new CourseAccess(courses, roles);
        chapterAccess = new ChapterAccess(chapters, courseAccess);
        lessonAccess = new LessonAccess(lessons, courseAccess);
        TestSecurityContext.authenticate(USER, RoleCode.STUDENT, RoleCode.INSTRUCTOR);
    }

    @AfterEach
    void tearDown() {
        TestSecurityContext.clear();
    }

    private void instructorRoleInDatabase(boolean active) {
        when(roles.hasActiveRole(USER, RoleCode.INSTRUCTOR)).thenReturn(active);
    }

    static void assertBusiness(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    // ------------------------------------------------------------------------------------------------ no role → false

    @Test
    void allMethods_instructorRoleRevokedInDatabase_staleJwtStillSaysInstructor_false_D07_BR26() {
        instructorRoleInDatabase(false);

        assertThat(courseAccess.isMain(COURSE)).isFalse();
        assertThat(courseAccess.isInstructor(COURSE)).isFalse();
        assertThat(courseAccess.canEditContent(COURSE)).isFalse();
        assertThat(chapterAccess.isMain(CHAPTER)).isFalse();
        assertThat(chapterAccess.isInstructor(CHAPTER)).isFalse();
        assertThat(chapterAccess.canEditContent(CHAPTER)).isFalse();
        assertThat(lessonAccess.isMain(LESSON)).isFalse();
        assertThat(lessonAccess.isInstructor(LESSON)).isFalse();
        assertThat(lessonAccess.canEditContent(LESSON)).isFalse();
        // the course / chapter / lesson is not even looked up: a non-instructor cannot probe ids
        verifyNoInteractions(courses, chapters, lessons);
    }

    @Test
    void isMain_studentWithoutInstructorRole_falseEvenForMissingCourse() {
        TestSecurityContext.authenticate(USER, RoleCode.STUDENT);
        instructorRoleInDatabase(false);

        assertThat(courseAccess.isMain(Long.MAX_VALUE)).isFalse();
        verifyNoInteractions(courses);
    }

    @Test
    void noCaller_unauthenticated() {
        TestSecurityContext.clear();

        assertBusiness(() -> courseAccess.isMain(COURSE), ErrorCode.UNAUTHENTICATED);
    }

    // ----------------------------------------------------------------------------------------------------- isMain

    @Test
    void isMain_mainInstructor_true() {
        instructorRoleInDatabase(true);
        when(courses.existsByIdAndMainInstructorId(COURSE, USER)).thenReturn(true);

        assertThat(courseAccess.isMain(COURSE)).isTrue();
    }

    @Test
    void isMain_instructorButNotMain_orMissingCourse_404() {
        instructorRoleInDatabase(true);
        when(courses.existsByIdAndMainInstructorId(anyLong(), any())).thenReturn(false);

        assertBusiness(() -> courseAccess.isMain(COURSE), ErrorCode.RESOURCE_NOT_FOUND);
        assertBusiness(() -> courseAccess.isMain(Long.MAX_VALUE), ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ----------------------------------------------------------------------------------------------- isInstructor

    @Test
    void isInstructor_mainOrAcceptedCoInstructor_true() {
        instructorRoleInDatabase(true);
        when(courses.isCourseInstructor(COURSE, USER)).thenReturn(true);

        assertThat(courseAccess.isInstructor(COURSE)).isTrue();
    }

    @Test
    void isInstructor_roleButNoRelation_orMissingCourse_404_NFR01() {
        instructorRoleInDatabase(true);
        when(courses.isCourseInstructor(anyLong(), any())).thenReturn(false);

        assertBusiness(() -> courseAccess.isInstructor(COURSE), ErrorCode.RESOURCE_NOT_FOUND);
        assertBusiness(() -> courseAccess.isInstructor(Long.MAX_VALUE), ErrorCode.RESOURCE_NOT_FOUND);
    }

    // --------------------------------------------------------------------------------------------- canEditContent

    @ParameterizedTest
    @EnumSource(value = CourseStatus.class, names = "PENDING_REVIEW", mode = EnumSource.Mode.EXCLUDE)
    void canEditContent_courseInstructor_editableStatus_true_D14(CourseStatus status) {
        instructorRoleInDatabase(true);
        when(courses.isCourseInstructor(COURSE, USER)).thenReturn(true);
        when(courses.findStatusById(COURSE)).thenReturn(Optional.of(status));

        assertThat(courseAccess.canEditContent(COURSE)).isTrue();
    }

    @Test
    void canEditContent_courseInstructor_pendingReview_409CourseNotEditable_BR33() {
        instructorRoleInDatabase(true);
        when(courses.isCourseInstructor(COURSE, USER)).thenReturn(true);
        when(courses.findStatusById(COURSE)).thenReturn(Optional.of(CourseStatus.PENDING_REVIEW));

        assertBusiness(() -> courseAccess.canEditContent(COURSE), ErrorCode.COURSE_NOT_EDITABLE);
    }

    @Test
    void canEditContent_notCourseInstructor_pendingReview_404NotConflict() {
        instructorRoleInDatabase(true);
        when(courses.isCourseInstructor(COURSE, USER)).thenReturn(false);

        // the status is never revealed to a stranger
        assertBusiness(() -> courseAccess.canEditContent(COURSE), ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ------------------------------------------------------------------------------------------- chapter / lesson

    @Nested
    class ChapterAndLesson {

        @Test
        void missingChapterOrLesson_404() {
            instructorRoleInDatabase(true);
            when(chapters.findCourseIdById(CHAPTER)).thenReturn(Optional.empty());
            when(lessons.findCourseIdById(LESSON)).thenReturn(Optional.empty());

            assertBusiness(() -> chapterAccess.canEditContent(CHAPTER), ErrorCode.RESOURCE_NOT_FOUND);
            assertBusiness(() -> lessonAccess.isInstructor(LESSON), ErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        void delegatesToParentCourse() {
            instructorRoleInDatabase(true);
            when(chapters.findCourseIdById(CHAPTER)).thenReturn(Optional.of(COURSE));
            when(lessons.findCourseIdById(LESSON)).thenReturn(Optional.of(COURSE));
            when(courses.isCourseInstructor(COURSE, USER)).thenReturn(true);
            when(courses.existsByIdAndMainInstructorId(COURSE, USER)).thenReturn(false);
            when(courses.findStatusById(COURSE)).thenReturn(Optional.of(CourseStatus.DRAFT));

            assertThat(chapterAccess.isInstructor(CHAPTER)).isTrue();
            assertThat(chapterAccess.canEditContent(CHAPTER)).isTrue();
            assertThat(lessonAccess.isInstructor(LESSON)).isTrue();
            assertThat(lessonAccess.canEditContent(LESSON)).isTrue();
            // co-instructor: not main
            assertBusiness(() -> chapterAccess.isMain(CHAPTER), ErrorCode.RESOURCE_NOT_FOUND);
            assertBusiness(() -> lessonAccess.isMain(LESSON), ErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        void parentCoursePendingReview_409_BR33() {
            instructorRoleInDatabase(true);
            when(lessons.findCourseIdById(LESSON)).thenReturn(Optional.of(COURSE));
            when(courses.isCourseInstructor(COURSE, USER)).thenReturn(true);
            when(courses.findStatusById(COURSE)).thenReturn(Optional.of(CourseStatus.PENDING_REVIEW));

            assertBusiness(() -> lessonAccess.canEditContent(LESSON), ErrorCode.COURSE_NOT_EDITABLE);
        }

        @Test
        void otherInstructorsChapter_404() {
            instructorRoleInDatabase(true);
            when(chapters.findCourseIdById(CHAPTER)).thenReturn(Optional.of(COURSE));
            when(courses.isCourseInstructor(COURSE, USER)).thenReturn(false);

            assertBusiness(() -> chapterAccess.canEditContent(CHAPTER), ErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}
