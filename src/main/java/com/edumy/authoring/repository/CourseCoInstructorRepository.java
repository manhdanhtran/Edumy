package com.edumy.authoring.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.edumy.authoring.entity.CourseCoInstructor;
import com.edumy.authoring.entity.CourseStatus;
import com.edumy.authoring.entity.InvitationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseCoInstructorRepository extends JpaRepository<CourseCoInstructor, Long> {

    /** The (unique) row of a pair, any status: invite vs. re-invite (D-18), remove (UC-I16), leave (UC-I20). */
    Optional<CourseCoInstructor> findByCourseIdAndInstructorId(Long courseId, Long instructorId);

    /**
     * UC-I14 / UC-I15: course of the invitation {@code id} sent to {@code instructorId}; empty when unknown or sent to
     * someone else (one answer for both, NFR-01). A scalar, so no entity is loaded before the course row lock.
     */
    @Query("select cci.course.id from CourseCoInstructor cci where cci.id = :id and cci.instructorId = :instructorId")
    Optional<Long> findCourseIdByIdAndInstructorId(@Param("id") Long id, @Param("instructorId") Long instructorId);

    /** Access check: {@code ACCEPTED} = the user is a co-instructor of the course (BR-04, {@code @courseAccess}). */
    boolean existsByCourseIdAndInstructorIdAndInvitationStatus(Long courseId, Long instructorId,
                                                               InvitationStatus invitationStatus);

    /** Co-instructor rows of a course (instructor course detail, UC-I02), oldest invitation first. */
    List<CourseCoInstructor> findByCourseIdOrderByInvitedAtAscIdAsc(Long courseId);

    /**
     * UC-I14/I15 {@code GET /instructor/invitations}: invitations received by {@code instructorId}, newest first;
     * {@code status} null = every status.
     */
    @Query("""
            select cci.id as id, c.id as courseId, c.title as courseTitle, c.slug as courseSlug,
                   c.status as courseStatus, c.mainInstructorId as mainInstructorId, cci.invitedBy as invitedBy,
                   cci.invitationStatus as invitationStatus, cci.invitedAt as invitedAt,
                   cci.respondedAt as respondedAt
            from CourseCoInstructor cci join cci.course c
            where cci.instructorId = :instructorId
              and (:status is null or cci.invitationStatus = :status)
            order by cci.invitedAt desc, cci.id desc
            """)
    List<InvitationView> findInvitationsOfInstructor(@Param("instructorId") Long instructorId,
                                                     @Param("status") InvitationStatus status);

    /** One invitation of an instructor (UC-I14). Names of users come from {@code identity::api}. */
    interface InvitationView {
        Long getId();

        Long getCourseId();

        String getCourseTitle();

        String getCourseSlug();

        CourseStatus getCourseStatus();

        Long getMainInstructorId();

        Long getInvitedBy();

        InvitationStatus getInvitationStatus();

        Instant getInvitedAt();

        Instant getRespondedAt();
    }
}
