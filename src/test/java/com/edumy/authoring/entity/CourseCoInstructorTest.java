package com.edumy.authoring.entity;

import static com.edumy.authoring.entity.CourseTest.INSTRUCTOR;
import static com.edumy.authoring.entity.CourseTest.NOW;
import static com.edumy.authoring.entity.CourseTest.assertCode;
import static com.edumy.authoring.entity.CourseTest.assertConflict;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.edumy.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CourseCoInstructorTest {

    static final Long CO = 20L;
    static final Instant LATER = NOW.plusSeconds(3600);

    static CourseCoInstructor invitation() {
        return CourseCoInstructor.invite(CourseTest.newCourse(), CO, INSTRUCTOR, NOW);
    }

    /** An invitation driven through the real transitions into {@code status}. */
    static CourseCoInstructor invitationIn(InvitationStatus status) {
        CourseCoInstructor row = invitation();
        switch (status) {
            case PENDING -> { }
            case ACCEPTED -> row.accept(NOW);
            case DECLINED -> row.decline(NOW);
            case REMOVED -> row.remove();
        }
        assertThat(row.getInvitationStatus()).isEqualTo(status);
        return row;
    }

    @Test
    void invite_otherInstructor_pending_UCI13() {
        Course course = CourseTest.newCourse();

        CourseCoInstructor row = CourseCoInstructor.invite(course, CO, INSTRUCTOR, NOW);

        assertThat(row.getCourse()).isSameAs(course);
        assertThat(row.getInstructorId()).isEqualTo(CO);
        assertThat(row.getInvitedBy()).isEqualTo(INSTRUCTOR);
        assertThat(row.getInvitationStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(row.getInvitedAt()).isEqualTo(NOW);
        assertThat(row.getRespondedAt()).isNull();
        assertThat(row.isAccepted()).isFalse();
    }

    @Test
    void invite_mainInstructor_rejected_BR02() {
        Course course = CourseTest.newCourse();

        assertCode(() -> CourseCoInstructor.invite(course, INSTRUCTOR, 30L, NOW),
                ErrorCode.COURSE_CO_INSTRUCTOR_IS_MAIN);
    }

    @Test
    void invite_self_rejected_ckCciNotSelf() {
        Course course = CourseTest.newCourse();

        assertCode(() -> CourseCoInstructor.invite(course, CO, CO, NOW), ErrorCode.COURSE_CO_INSTRUCTOR_IS_MAIN);
    }

    @Test
    void invite_nullArguments_rejected() {
        Course course = CourseTest.newCourse();
        assertThatThrownBy(() -> CourseCoInstructor.invite(null, CO, INSTRUCTOR, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CourseCoInstructor.invite(course, null, INSTRUCTOR, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CourseCoInstructor.invite(course, CO, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CourseCoInstructor.invite(course, CO, INSTRUCTOR, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void accept_pending_acceptedWithRespondedAt_UCI14() {
        CourseCoInstructor row = invitation();

        row.accept(LATER);

        assertThat(row.getInvitationStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(row.getRespondedAt()).isEqualTo(LATER);
        assertThat(row.isAccepted()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = InvitationStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void accept_notPending_rejected(InvitationStatus status) {
        CourseCoInstructor row = invitationIn(status);

        assertConflict(() -> row.accept(LATER));
        assertThat(row.getInvitationStatus()).isEqualTo(status);
    }

    @Test
    void decline_pending_declinedWithRespondedAt_UCI15() {
        CourseCoInstructor row = invitation();

        row.decline(LATER);

        assertThat(row.getInvitationStatus()).isEqualTo(InvitationStatus.DECLINED);
        assertThat(row.getRespondedAt()).isEqualTo(LATER);
    }

    @ParameterizedTest
    @EnumSource(value = InvitationStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void decline_notPending_rejected(InvitationStatus status) {
        CourseCoInstructor row = invitationIn(status);

        assertConflict(() -> row.decline(LATER));
        assertThat(row.getInvitationStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = InvitationStatus.class, names = {"PENDING", "ACCEPTED"})
    void remove_pendingOrAccepted_removed_UCI16_UCI20(InvitationStatus status) {
        CourseCoInstructor row = invitationIn(status);

        row.remove();

        assertThat(row.getInvitationStatus()).isEqualTo(InvitationStatus.REMOVED);
        assertThat(row.isAccepted()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = InvitationStatus.class, names = {"DECLINED", "REMOVED"})
    void remove_declinedOrRemoved_rejected(InvitationStatus status) {
        CourseCoInstructor row = invitationIn(status);

        assertConflict(row::remove);
        assertThat(row.getInvitationStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = InvitationStatus.class, names = {"DECLINED", "REMOVED"})
    void reinvite_declinedOrRemoved_pendingOnSameRowWithResetResponse_D18(InvitationStatus status) {
        CourseCoInstructor row = invitationIn(status);

        row.reinvite(30L, LATER);

        assertThat(row.getInvitationStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(row.getInvitedBy()).isEqualTo(30L);
        assertThat(row.getInvitedAt()).isEqualTo(LATER);
        assertThat(row.getRespondedAt()).isNull();
        assertThat(row.getInstructorId()).isEqualTo(CO);
    }

    @ParameterizedTest
    @EnumSource(value = InvitationStatus.class, names = {"PENDING", "ACCEPTED"})
    void reinvite_pendingOrAccepted_rejected(InvitationStatus status) {
        CourseCoInstructor row = invitationIn(status);

        assertConflict(() -> row.reinvite(INSTRUCTOR, LATER));
        assertThat(row.getInvitationStatus()).isEqualTo(status);
        assertThat(row.getInvitedAt()).isEqualTo(NOW);
    }

    @Test
    void reinvite_byInvitee_rejected_ckCciNotSelf() {
        CourseCoInstructor row = invitationIn(InvitationStatus.DECLINED);

        assertCode(() -> row.reinvite(CO, LATER), ErrorCode.COURSE_CO_INSTRUCTOR_IS_MAIN);
        assertThat(row.getInvitationStatus()).isEqualTo(InvitationStatus.DECLINED);
    }
}
