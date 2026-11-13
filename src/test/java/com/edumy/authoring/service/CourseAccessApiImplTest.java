package com.edumy.authoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.edumy.authoring.repository.CourseRepository;
import com.edumy.authoring.repository.LessonRepository;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.shared.security.RoleCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@code CourseAccessApi} / {@code LessonQueryApi} for other modules (BR-26, D-07). */
@ExtendWith(MockitoExtension.class)
class CourseAccessApiImplTest {

    static final long USER = 7L;
    static final long COURSE = 100L;

    @Mock
    CourseRepository courses;
    @Mock
    LessonRepository lessons;
    @Mock
    RoleQueryApi roles;

    @Test
    void courseInstructorWithActiveRole_true() {
        when(roles.hasActiveRole(USER, RoleCode.INSTRUCTOR)).thenReturn(true);
        when(courses.isCourseInstructor(COURSE, USER)).thenReturn(true);
        when(courses.existsByIdAndMainInstructorId(COURSE, USER)).thenReturn(true);
        CourseAccessApiImpl api = new CourseAccessApiImpl(courses, roles);

        assertThat(api.isCourseInstructor(USER, COURSE)).isTrue();
        assertThat(api.isMainInstructor(USER, COURSE)).isTrue();
    }

    @Test
    void revokedInstructorRole_falseWithoutQueryingTheCourse_D07() {
        when(roles.hasActiveRole(USER, RoleCode.INSTRUCTOR)).thenReturn(false);
        CourseAccessApiImpl api = new CourseAccessApiImpl(courses, roles);

        assertThat(api.isCourseInstructor(USER, COURSE)).isFalse();
        assertThat(api.isMainInstructor(USER, COURSE)).isFalse();
        verifyNoInteractions(courses);
    }

    @Test
    void activeRoleButNoRelation_false() {
        when(roles.hasActiveRole(USER, RoleCode.INSTRUCTOR)).thenReturn(true);
        CourseAccessApiImpl api = new CourseAccessApiImpl(courses, roles);

        assertThat(api.isCourseInstructor(USER, COURSE)).isFalse();
        assertThat(api.isMainInstructor(USER, COURSE)).isFalse();
    }

    @Test
    void lessonQuery_courseOfLesson() {
        lenient().when(lessons.findCourseIdById(5L)).thenReturn(Optional.of(COURSE));
        LessonQueryApiImpl api = new LessonQueryApiImpl(lessons, mock(com.edumy.authoring.repository.ChapterRepository.class));

        assertThat(api.findCourseId(5L)).contains(COURSE);
        assertThat(api.findCourseId(6L)).isEmpty();
        assertThat(api.findCourseId(null)).isEmpty();
    }
}
