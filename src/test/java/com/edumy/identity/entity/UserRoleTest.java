package com.edumy.identity.entity;

import static com.edumy.identity.entity.UserTest.NOW;
import static com.edumy.identity.entity.UserTest.assertConflict;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class UserRoleTest {

    static final Instant LATER = NOW.plusSeconds(3600);

    private final User user = UserTest.newUser();
    private final User admin = User.register("admin@example.com", UserTest.HASH, "Quản trị", NOW);
    private final Role instructor = new Role();

    @Test
    void grant_selfService_activeWithoutAssigner() {
        UserRole userRole = UserRole.grant(user, instructor, null, NOW);

        assertThat(userRole.isActive()).isTrue();
        assertThat(userRole.getUser()).isSameAs(user);
        assertThat(userRole.getRole()).isSameAs(instructor);
        assertThat(userRole.getAssignedBy()).isNull();
        assertThat(userRole.getAssignedAt()).isEqualTo(NOW);
        assertThat(userRole.getUpdatedAt()).isNull();
    }

    @Test
    void grant_byAdmin_recordsAssigner() {
        assertThat(UserRole.grant(user, instructor, admin, NOW).getAssignedBy()).isSameAs(admin);
    }

    @Test
    void grant_missingUserRoleOrNow_rejected() {
        assertThatNullPointerException().isThrownBy(() -> UserRole.grant(null, instructor, null, NOW));
        assertThatNullPointerException().isThrownBy(() -> UserRole.grant(user, null, null, NOW));
        assertThatNullPointerException().isThrownBy(() -> UserRole.grant(user, instructor, null, null));
    }

    @Test
    void revoke_active_inactive_D04() {
        UserRole userRole = UserRole.grant(user, instructor, null, NOW);

        userRole.revoke();

        assertThat(userRole.isActive()).isFalse();
        assertThat(userRole.getAssignedAt()).isEqualTo(NOW);
    }

    @Test
    void revoke_alreadyRevoked_rejected() {
        UserRole userRole = UserRole.grant(user, instructor, null, NOW);
        userRole.revoke();

        assertConflict(userRole::revoke);
        assertThat(userRole.isActive()).isFalse();
    }

    @Test
    void reactivate_revokedByAdmin_activeWithNewAssignment_D04() {
        UserRole userRole = UserRole.grant(user, instructor, null, NOW);
        userRole.revoke();

        userRole.reactivate(admin, LATER);

        assertThat(userRole.isActive()).isTrue();
        assertThat(userRole.getAssignedBy()).isSameAs(admin);
        assertThat(userRole.getAssignedAt()).isEqualTo(LATER);
    }

    @Test
    void reactivate_alreadyActive_rejectedAndUnchanged() {
        UserRole userRole = UserRole.grant(user, instructor, null, NOW);

        assertConflict(() -> userRole.reactivate(admin, LATER));
        assertThat(userRole.getAssignedBy()).isNull();
        assertThat(userRole.getAssignedAt()).isEqualTo(NOW);
    }

    @Test
    void reactivate_withoutAdmin_rejected_D04() {
        UserRole userRole = UserRole.grant(user, instructor, null, NOW);
        userRole.revoke();

        assertThatNullPointerException().isThrownBy(() -> userRole.reactivate(null, LATER));
        assertThat(userRole.isActive()).isFalse();
    }
}
