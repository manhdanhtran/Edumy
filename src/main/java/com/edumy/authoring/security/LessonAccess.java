package com.edumy.authoring.security;

import com.edumy.authoring.repository.LessonRepository;
import com.edumy.shared.error.BusinessException;
import org.springframework.stereotype.Component;

/**
 * {@code @lessonAccess}: the {@link CourseAccess} checks for lesson endpoints. The role is checked first (no active
 * instructor role → {@code false}, 403, without looking the lesson up, so non-instructors cannot probe ids); then
 * the lesson's course is resolved through its chapter (unknown lesson → 404) and the course check decides.
 */
@Component("lessonAccess")
public class LessonAccess {

    private final LessonRepository lessons;
    private final CourseAccess courseAccess;

    public LessonAccess(LessonRepository lessons, CourseAccess courseAccess) {
        this.lessons = lessons;
        this.courseAccess = courseAccess;
    }

    public boolean isMain(Long lessonId) {
        return courseAccess.callerHasInstructorRole() && courseAccess.isMain(courseIdOf(lessonId));
    }

    public boolean isInstructor(Long lessonId) {
        return courseAccess.callerHasInstructorRole() && courseAccess.isInstructor(courseIdOf(lessonId));
    }

    public boolean canEditContent(Long lessonId) {
        return courseAccess.callerHasInstructorRole() && courseAccess.canEditContent(courseIdOf(lessonId));
    }

    private Long courseIdOf(Long lessonId) {
        if (lessonId == null) {
            throw BusinessException.notFound();
        }
        return lessons.findCourseIdById(lessonId).orElseThrow(BusinessException::notFound);
    }
}
