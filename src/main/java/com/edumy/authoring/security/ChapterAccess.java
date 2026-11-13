package com.edumy.authoring.security;

import com.edumy.authoring.repository.ChapterRepository;
import com.edumy.shared.error.BusinessException;
import org.springframework.stereotype.Component;

/**
 * {@code @chapterAccess}: the {@link CourseAccess} checks for chapter endpoints. The role is checked first (no active
 * instructor role → {@code false}, 403, without looking the chapter up, so non-instructors cannot probe ids); then
 * the chapter's course is resolved (unknown chapter → 404) and the course check decides.
 */
@Component("chapterAccess")
public class ChapterAccess {

    private final ChapterRepository chapters;
    private final CourseAccess courseAccess;

    public ChapterAccess(ChapterRepository chapters, CourseAccess courseAccess) {
        this.chapters = chapters;
        this.courseAccess = courseAccess;
    }

    public boolean isMain(Long chapterId) {
        return courseAccess.callerHasInstructorRole() && courseAccess.isMain(courseIdOf(chapterId));
    }

    public boolean isInstructor(Long chapterId) {
        return courseAccess.callerHasInstructorRole() && courseAccess.isInstructor(courseIdOf(chapterId));
    }

    public boolean canEditContent(Long chapterId) {
        return courseAccess.callerHasInstructorRole() && courseAccess.canEditContent(courseIdOf(chapterId));
    }

    private Long courseIdOf(Long chapterId) {
        if (chapterId == null) {
            throw BusinessException.notFound();
        }
        return chapters.findCourseIdById(chapterId).orElseThrow(BusinessException::notFound);
    }
}
