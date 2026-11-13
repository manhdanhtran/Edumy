package com.edumy.learning.repository;

import java.util.List;
import java.util.Optional;

import com.edumy.learning.entity.LessonProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code lesson_progresses}. Callers pass an enrollment id they obtained scoped by the current student
 * ({@code EnrollmentRepository.findByStudentIdAndCourseId}), so these lookups need no extra user scoping.
 */
public interface LessonProgressRepository extends JpaRepository<LessonProgress, Long> {

    /** UC-S11 {@code GET /learn/courses/{courseId}}: every progress row of an enrollment (outline ticks). */
    List<LessonProgress> findByEnrollmentId(Long enrollmentId);

    /** UC-S10 / BR-39: completed lessons of an enrollment (percent = floor(completed * 100 / total), D-33). */
    long countByEnrollmentIdAndCompletedTrue(Long enrollmentId);

    /** D-34 "Học tiếp": most recently accessed progress row of an enrollment (ix_lesson_progresses_resume). */
    Optional<LessonProgress> findFirstByEnrollmentIdOrderByLastAccessedAtDescIdDesc(Long enrollmentId);

    /** UC-S09 visit / UC-S10 completion: the row of one lesson (uq_lesson_progresses_enrollment_lesson). */
    Optional<LessonProgress> findByEnrollmentIdAndLessonId(Long enrollmentId, Long lessonId);

    /** UC-S11: ids of the lessons the enrollment has completed (the outline marks them). */
    @Query("select p.lessonId from LessonProgress p where p.enrollment.id = :enrollmentId and p.completed = true")
    List<Long> findCompletedLessonIds(@Param("enrollmentId") Long enrollmentId);
}
