package com.edumy.learning.repository;

import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Optional;

import com.edumy.learning.entity.Enrollment;
import com.edumy.learning.entity.EnrollmentSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code enrollments}. Student lookups always include the student id (IDOR scoping, NFR-01).
 * <p>BR-17 duplicate enrollment (docs/ai/03 §3.2): T2 grants enrollments with {@link #insertPurchaseIfAbsent}, a native
 * {@code INSERT IGNORE} that returns 0 instead of failing when uq_enrollments_student_course already holds the pair.
 * Catching the unique violation is not an option there: the exception would pass through the repository's
 * transactional proxy and mark the payment transaction (NFR-04, {@code propagation = MANDATORY}) rollback-only.
 * Caveat: {@code IGNORE} also turns FK (1452) and CHECK (3819) errors into warnings, so the caller passes only an
 * existing student, course and paid order (T2 has locked / read all three).
 */
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    /** {@code @enrollmentAccess}, UC-S09…S20: the enrollment of a student in a course. */
    Optional<Enrollment> findByStudentIdAndCourseId(Long studentId, Long courseId);

    /** {@code EnrollmentApi.owns} (BR-10, cart / order checks) and {@code @enrollmentAccess.isEnrolled}. */
    boolean existsByStudentIdAndCourseId(Long studentId, Long courseId);

    /** {@code EnrollmentApi.findOwnedCourseIds}: which of these courses the student owns, one statement. */
    @Query("select e.courseId from Enrollment e where e.studentId = :studentId and e.courseId in :courseIds")
    List<Long> findCourseIdsByStudentIdAndCourseIdIn(@Param("studentId") Long studentId,
                                                     @Param("courseIds") Collection<Long> courseIds);

    /**
     * BR-17 / T2: purchase enrollment of {@code studentId} in {@code courseId} through {@code orderId}, unless the
     * student already has one for the course (then nothing is written). Returns 1 when inserted, 0 when it existed.
     * {@code orderId} must not be null (ck_enrollments_source_order would be ignored as well, see class comment).
     */
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT IGNORE INTO enrollments (student_id, course_id, order_id, enroll_source, enrolled_at)
            VALUES (:studentId, :courseId, :orderId, 'purchase', :enrolledAt)
            """)
    int insertPurchaseIfAbsent(@Param("studentId") Long studentId, @Param("courseId") Long courseId,
                               @Param("orderId") Long orderId, @Param("enrolledAt") Instant enrolledAt);

    /**
     * UC-S08 {@code GET /me/enrollments}: enrollments of a student, newest first, with the number of completed lessons
     * (percent = floor(completed * 100 / total lessons), D-33, total from {@code authoring::api.LessonQueryApi}) and
     * the most recently accessed lesson ("Học tiếp", D-34; null before the first access). Pass an unsorted
     * {@link Pageable}: the order is fixed here.
     */
    @Query(value = """
            select e.id as id, e.courseId as courseId, e.enrollSource as enrollSource, e.enrolledAt as enrolledAt,
                   (select count(p) from LessonProgress p where p.enrollment = e and p.completed = true)
                       as completedLessonCount,
                   (select p.lessonId from LessonProgress p where p.enrollment = e
                    order by p.lastAccessedAt desc, p.id desc limit 1) as lastAccessedLessonId,
                   (select max(p.lastAccessedAt) from LessonProgress p where p.enrollment = e) as lastAccessedAt
            from Enrollment e
            where e.studentId = :studentId
            order by e.enrolledAt desc, e.id desc
            """,
            countQuery = "select count(e) from Enrollment e where e.studentId = :studentId")
    Page<StudentEnrollmentView> findSummariesByStudentId(@Param("studentId") Long studentId, Pageable pageable);

    /**
     * UC-I19 {@code GET /instructor/courses/{courseId}/students}: students of a course, newest enrollment first, with
     * completed lesson count and last activity. Names / emails come from {@code identity::api}. Pass an unsorted
     * {@link Pageable}.
     */
    @Query(value = """
            select e.id as id, e.studentId as studentId, e.enrollSource as enrollSource, e.enrolledAt as enrolledAt,
                   (select count(p) from LessonProgress p where p.enrollment = e and p.completed = true)
                       as completedLessonCount,
                   (select max(p.lastAccessedAt) from LessonProgress p where p.enrollment = e) as lastAccessedAt
            from Enrollment e
            where e.courseId = :courseId
            order by e.enrolledAt desc, e.id desc
            """,
            countQuery = "select count(e) from Enrollment e where e.courseId = :courseId")
    Page<CourseStudentView> findStudentsByCourseId(@Param("courseId") Long courseId, Pageable pageable);

    /** Row of "my courses" (UC-S08). */
    interface StudentEnrollmentView {
        Long getId();

        Long getCourseId();

        EnrollmentSource getEnrollSource();

        Instant getEnrolledAt();

        long getCompletedLessonCount();

        Long getLastAccessedLessonId();

        Instant getLastAccessedAt();
    }

    /** Row of the instructor's student list (UC-I19). */
    interface CourseStudentView {
        Long getId();

        Long getStudentId();

        EnrollmentSource getEnrollSource();

        Instant getEnrolledAt();

        long getCompletedLessonCount();

        Instant getLastAccessedAt();
    }
}
