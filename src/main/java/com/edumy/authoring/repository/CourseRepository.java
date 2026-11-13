package com.edumy.authoring.repository;

import java.time.Instant;
import java.util.Optional;

import com.edumy.authoring.entity.Course;
import com.edumy.authoring.entity.CourseStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Write side and instructor/admin reads of {@code courses}. The public catalog does not use this repository (it has
 * its own JdbcClient read model, ADR-003).
 */
public interface CourseRepository extends JpaRepository<Course, Long> {

    /** UC-I02 {@code GET /instructor/courses/{id}}: the course with its category in one query. */
    @EntityGraph(attributePaths = "category")
    Optional<Course> findWithCategoryById(Long id);

    /** Row lock for status transitions (submit with the BR-07 checklist in the same transaction, approve/reject…). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Course c where c.id = :id")
    Optional<Course> findByIdForUpdate(@Param("id") Long id);

    /** {@code @courseAccess.isMain}: BR-04/06 (info, price, invitations, submit). */
    boolean existsByIdAndMainInstructorId(Long id, Long mainInstructorId);

    /**
     * {@code @courseAccess.isInstructor}: main instructor OR accepted co-instructor (docs/ai/02 "course instructor").
     * The active instructor role (BR-26) is checked separately through {@code identity::api.RoleQueryApi}.
     */
    @Query("""
            select case when count(c) > 0 then true else false end from Course c
            where c.id = :courseId
              and (c.mainInstructorId = :userId
                   or exists (select 1 from CourseCoInstructor cci
                              where cci.course = c and cci.instructorId = :userId
                                and cci.invitationStatus = com.edumy.authoring.entity.InvitationStatus.ACCEPTED))
            """)
    boolean isCourseInstructor(@Param("courseId") Long courseId, @Param("userId") Long userId);

    /** {@code @courseAccess.canEditContent} (BR-33) and the course-status checks of the access beans. */
    @Query("select c.status from Course c where c.id = :id")
    Optional<CourseStatus> findStatusById(@Param("id") Long id);

    /** Slug pre-check at creation (uq_courses_slug still decides under concurrency). */
    boolean existsBySlug(String slug);

    /** D-21 / UC-A08: a used category is deactivated instead of deleted. */
    boolean existsByCategoryId(Long categoryId);

    /**
     * UC-I02 {@code GET /instructor/courses?status=}: courses where {@code instructorId} is the main instructor or an
     * accepted co-instructor (pending/declined/removed invitations do not count), newest first; {@code status} null =
     * every status. {@code mainInstructorId} tells the caller its role (main vs co).
     */
    @Query(value = """
            select c.id as id, c.title as title, c.slug as slug, c.thumbnailUrl as thumbnailUrl, c.price as price,
                   c.status as status, cat.id as categoryId, cat.name as categoryName,
                   c.mainInstructorId as mainInstructorId, c.reviewNote as reviewNote, c.submittedAt as submittedAt,
                   c.publishedAt as publishedAt, c.createdAt as createdAt, c.updatedAt as updatedAt
            from Course c join c.category cat
            where (c.mainInstructorId = :instructorId
                   or exists (select 1 from CourseCoInstructor cci
                              where cci.course = c and cci.instructorId = :instructorId
                                and cci.invitationStatus = com.edumy.authoring.entity.InvitationStatus.ACCEPTED))
              and (:status is null or c.status = :status)
            order by c.createdAt desc, c.id desc
            """,
            countQuery = """
            select count(c) from Course c
            where (c.mainInstructorId = :instructorId
                   or exists (select 1 from CourseCoInstructor cci
                              where cci.course = c and cci.instructorId = :instructorId
                                and cci.invitationStatus = com.edumy.authoring.entity.InvitationStatus.ACCEPTED))
              and (:status is null or c.status = :status)
            """)
    Page<InstructorCourseView> findInstructorCourses(@Param("instructorId") Long instructorId,
                                                     @Param("status") CourseStatus status, Pageable pageable);

    /**
     * UC-A09 {@code GET /admin/courses/pending}: review queue, oldest submission first (ix_courses_pending). Pass an
     * unsorted {@link Pageable}: the order is fixed here.
     */
    @Query(value = """
            select c.id as id, c.title as title, c.slug as slug, c.price as price, cat.id as categoryId,
                   cat.name as categoryName, c.mainInstructorId as mainInstructorId, c.submittedAt as submittedAt
            from Course c join c.category cat
            where c.status = com.edumy.authoring.entity.CourseStatus.PENDING_REVIEW
            order by c.submittedAt asc, c.id asc
            """,
            countQuery = """
            select count(c) from Course c where c.status = com.edumy.authoring.entity.CourseStatus.PENDING_REVIEW
            """)
    Page<PendingCourseView> findPendingQueue(Pageable pageable);

    /** Row of the instructor's course list (UC-I02). */
    interface InstructorCourseView {
        Long getId();

        String getTitle();

        String getSlug();

        String getThumbnailUrl();

        long getPrice();

        CourseStatus getStatus();

        Long getCategoryId();

        String getCategoryName();

        Long getMainInstructorId();

        String getReviewNote();

        Instant getSubmittedAt();

        Instant getPublishedAt();

        Instant getCreatedAt();

        Instant getUpdatedAt();
    }

    /** Row of the admin review queue (UC-A09). The instructor name comes from {@code identity::api}. */
    interface PendingCourseView {
        Long getId();

        String getTitle();

        String getSlug();

        long getPrice();

        Long getCategoryId();

        String getCategoryName();

        Long getMainInstructorId();

        Instant getSubmittedAt();
    }
}
