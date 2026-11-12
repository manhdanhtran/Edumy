package com.edumy.learning.repository;

import java.util.Optional;

import com.edumy.learning.entity.CourseReview;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code course_reviews}. Public review lists and ratings are read by the catalog read model (ADR-003); this
 * repository serves the student's own review and admin moderation.
 */
public interface CourseReviewRepository extends JpaRepository<CourseReview, Long> {

    /** UC-S12 (BR-18 pre-check; uq_course_reviews_enrollment still decides) / the student's review of a course. */
    Optional<CourseReview> findByEnrollmentId(Long enrollmentId);

    /** UC-S13 / UC-S14 {@code PATCH|DELETE /reviews/{id}}: the review only if it belongs to the student (NFR-01). */
    Optional<CourseReview> findByIdAndEnrollmentStudentId(Long id, Long studentId);
}
