package com.edumy.learning.mapper;

import com.edumy.authoring.api.ChapterOutline;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.authoring.api.LessonContent;
import com.edumy.identity.api.UserSummary;
import com.edumy.learning.dto.response.EnrollmentResponse;
import com.edumy.learning.dto.response.LearningOutlineResponse;
import com.edumy.learning.dto.response.LessonContentResponse;
import com.edumy.learning.dto.response.ProgressResponse;
import com.edumy.learning.dto.response.StudentProgressResponse;
import com.edumy.learning.repository.EnrollmentRepository.CourseStudentView;
import com.edumy.learning.repository.EnrollmentRepository.StudentEnrollmentView;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.mapstruct.Mapper;

/** Enrollments, outline, lesson content and student lists → responses. Progress is derived here, never stored. */
@Mapper(componentModel = "spring")
public interface EnrollmentMapper {

    /** D-33: floor(completed * 100 / total); 0 for a course without lessons. */
    static int percent(long completed, long total) {
        return total <= 0 ? 0 : (int) Math.min(100, completed * 100 / total);
    }

    /** UC-S08 row; {@code course} may be null only if the course vanished (impossible by FK). */
    default EnrollmentResponse toEnrollment(StudentEnrollmentView e, CourseSummary course, int totalLessons,
                                            String certificateCode) {
        EnrollmentResponse.Course card = course == null ? null
                : new EnrollmentResponse.Course(course.id(), course.title(), course.slug(), course.thumbnailUrl(),
                        course.published());
        return new EnrollmentResponse(e.getId(), card, e.getEnrollSource().code(), e.getEnrolledAt(),
                (int) e.getCompletedLessonCount(), totalLessons, percent(e.getCompletedLessonCount(), totalLessons),
                e.getLastAccessedLessonId(), e.getLastAccessedAt(), certificateCode);
    }

    /**
     * UC-S11: {@code completedLessonIds} null = the caller has no enrollment (instructor / admin, D-32): no progress
     * and no per-lesson flag.
     */
    default LearningOutlineResponse toOutline(Long courseId, String title, List<ChapterOutline> chapters,
                                              Set<Long> completedLessonIds) {
        boolean enrolled = completedLessonIds != null;
        int total = 0;
        int done = 0;
        List<LearningOutlineResponse.Chapter> out = new java.util.ArrayList<>();
        for (ChapterOutline chapter : chapters) {
            List<LearningOutlineResponse.Lesson> lessons = new java.util.ArrayList<>();
            for (ChapterOutline.LessonOutline l : chapter.lessons()) {
                Boolean completed = enrolled ? completedLessonIds.contains(l.id()) : null;
                total++;
                if (Boolean.TRUE.equals(completed)) {
                    done++;
                }
                lessons.add(new LearningOutlineResponse.Lesson(l.id(), l.title(), l.contentType(),
                        l.durationSeconds(), l.preview(), completed));
            }
            out.add(new LearningOutlineResponse.Chapter(chapter.id(), chapter.title(), lessons));
        }
        LearningOutlineResponse.Progress progress = enrolled
                ? new LearningOutlineResponse.Progress(done, total, percent(done, total)) : null;
        return new LearningOutlineResponse(courseId, title, enrolled, progress, out);
    }

    /** UC-S09; {@code completed} null for a caller without enrollment. */
    default LessonContentResponse toLessonContent(LessonContent l, Boolean completed) {
        return new LessonContentResponse(l.id(), l.courseId(), l.chapterId(), l.title(), l.contentType(),
                l.durationSeconds(), l.videoUrl(), l.articleContent(), completed);
    }

    /** UC-S10 result; {@code certificateIssued} only when this very request issued the certificate. */
    default ProgressResponse toProgress(Long lessonId, Instant completedAt, long completedLessons, long totalLessons,
                                        int percent, String certificateCode, boolean certificateIssued) {
        return new ProgressResponse(lessonId, completedAt != null, completedAt, (int) completedLessons,
                (int) totalLessons, percent, certificateCode, certificateIssued);
    }

    /** UC-I19 row; {@code student} null = account gone. */
    default StudentProgressResponse toStudent(CourseStudentView e, UserSummary student, int totalLessons) {
        return new StudentProgressResponse(e.getId(),
                new StudentProgressResponse.Student(e.getStudentId(), student == null ? null : student.fullName()),
                e.getEnrollSource().code(), e.getEnrolledAt(), (int) e.getCompletedLessonCount(), totalLessons,
                percent(e.getCompletedLessonCount(), totalLessons), e.getLastAccessedAt());
    }
}
