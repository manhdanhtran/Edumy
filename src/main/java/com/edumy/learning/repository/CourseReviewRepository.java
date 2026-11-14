package com.edumy.learning.repository;

import java.util.Optional;

import com.edumy.learning.entity.CourseReview;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code course_reviews}. Public review lists and ratings are read by the catalog read model (ADR-003); this
 * repository serves the student's own review and admin moderation.
 */
public interface CourseReviewRepository extends JpaRepository<CourseReview, Long> {

    /** UC-S12 (BR-18 pre-check; uq_course_reviews_enrollment still decides) / the student's review of a course. */
    Optional<CourseReview> findByEnrollmentId(Long enrollmentId);

    /** UC-S13 / UC-S14 {@code PATCH|DELETE /reviews/{id}}: the review only if it belongs to the student (NFR-01). */
    Optional<CourseReview> findByIdAndEnrollmentStudentId(Long id, Long studentId);

    /**
     * Same, with the review row locked ({@code FOR UPDATE}) so that a student edit and an admin hide of one review are
     * serialized; the enrollment is fetched in the same statement (course id for the event).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CourseReview r join fetch r.enrollment e where r.id = :id and e.studentId = :studentId")
    Optional<CourseReview> findOwnForUpdate(@Param("id") Long id, @Param("studentId") Long studentId);

    /** UC-A15 / UC-A17: the review by id, locked, with its enrollment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CourseReview r join fetch r.enrollment where r.id = :id")
    Optional<CourseReview> findByIdForUpdate(@Param("id") Long id);
}
