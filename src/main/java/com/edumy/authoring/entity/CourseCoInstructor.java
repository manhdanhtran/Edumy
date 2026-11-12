package com.edumy.authoring.entity;

import java.time.Instant;
import java.util.Objects;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.jpa.JpaEntities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code course_co_instructors} (EDU-DDD-001 §5.7): one row per (course, instructor) pair (uq_cci_course_instructor).
 * State machine (docs/ai/02 §2.2): new → pending → accepted|declined; pending|accepted → removed;
 * declined|removed → pending (re-invite reuses the row, D-18). Any other transition → {@code DATA_CONFLICT}.
 * Only accepted rows give editing rights on chapters/lessons (BR-04).
 */
@Entity
@Table(name = "course_co_instructors")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseCoInstructor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false, updatable = false)
    private Course course;

    /** Invited user (identity): plain id. Never changes: the pair is the row's identity (D-18). */
    @Column(name = "instructor_id", nullable = false, updatable = false)
    private Long instructorId;

    @Column(name = "invited_by", nullable = false)
    private Long invitedBy;

    @Column(name = "invitation_status", nullable = false, length = 20)
    private InvitationStatus invitationStatus;

    @Column(name = "invited_at", nullable = false)
    private Instant invitedAt;

    /** Set by accept/decline (ck_cci_responded); reset by a re-invite. */
    @Column(name = "responded_at")
    private Instant respondedAt;

    /** Maintained by MySQL (ON UPDATE CURRENT_TIMESTAMP(6)); null on a freshly persisted instance until reloaded. */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /**
     * UC-I13: first invitation of {@code instructorId} to {@code course} → pending. The invitee must differ from the
     * main instructor (BR-02, also the V5 trigger) and from the inviter (ck_cci_not_self). In v1 only the main
     * instructor invites (BR-06), so a self-invitation is the same BR-02 case: both raise
     * {@code COURSE_CO_INSTRUCTOR_IS_MAIN}. Role and email-verification checks of the invitee are service concerns.
     */
    public static CourseCoInstructor invite(Course course, Long instructorId, Long invitedBy, Instant now) {
        Objects.requireNonNull(course, "course");
        Objects.requireNonNull(instructorId, "instructorId");
        Objects.requireNonNull(invitedBy, "invitedBy");
        Objects.requireNonNull(now, "now");
        if (course.isMainInstructor(instructorId)) {
            throw new BusinessException(ErrorCode.COURSE_CO_INSTRUCTOR_IS_MAIN);
        }
        requireNotSelf(instructorId, invitedBy);
        CourseCoInstructor row = new CourseCoInstructor();
        row.course = course;
        row.instructorId = instructorId;
        row.invitedBy = invitedBy;
        row.invitationStatus = InvitationStatus.PENDING;
        row.invitedAt = now;
        return row;
    }

    /** UC-I14: pending → accepted (by the invitee). */
    public void accept(Instant now) {
        respond(InvitationStatus.ACCEPTED, now);
    }

    /** UC-I15: pending → declined (by the invitee). */
    public void decline(Instant now) {
        respond(InvitationStatus.DECLINED, now);
    }

    /** UC-I16 (main instructor removes) / UC-I20 (co-instructor leaves): pending|accepted → removed. */
    public void remove() {
        if (invitationStatus != InvitationStatus.PENDING && invitationStatus != InvitationStatus.ACCEPTED) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        invitationStatus = InvitationStatus.REMOVED;
    }

    /** UC-I13 again (D-18): declined|removed → pending on the same row; invitation time and inviter are renewed. */
    public void reinvite(Long newInvitedBy, Instant now) {
        Objects.requireNonNull(newInvitedBy, "invitedBy");
        Objects.requireNonNull(now, "now");
        if (invitationStatus != InvitationStatus.DECLINED && invitationStatus != InvitationStatus.REMOVED) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        requireNotSelf(instructorId, newInvitedBy);
        invitationStatus = InvitationStatus.PENDING;
        invitedBy = newInvitedBy;
        invitedAt = now;
        respondedAt = null;
    }

    public boolean isAccepted() {
        return invitationStatus == InvitationStatus.ACCEPTED;
    }

    private void respond(InvitationStatus answer, Instant now) {
        Objects.requireNonNull(now, "now");
        if (invitationStatus != InvitationStatus.PENDING) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        invitationStatus = answer;
        respondedAt = now;
    }

    private static void requireNotSelf(Long instructorId, Long invitedBy) {
        if (instructorId.equals(invitedBy)) {
            throw new BusinessException(ErrorCode.COURSE_CO_INSTRUCTOR_IS_MAIN);
        }
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || JpaEntities.effectiveClass(this) != JpaEntities.effectiveClass(o)) {
            return false;
        }
        Long id = getId();
        return id != null && id.equals(((CourseCoInstructor) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "CourseCoInstructor[id=" + getId() + ", instructorId=" + instructorId + ", status=" + invitationStatus
                + "]";
    }
}
