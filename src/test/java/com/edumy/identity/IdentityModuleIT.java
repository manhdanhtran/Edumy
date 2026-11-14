package com.edumy.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edumy.identity.api.RoleQueryApi;
import com.edumy.identity.dto.response.AdminUserResponse;
import com.edumy.identity.security.SessionStatusCache;
import com.edumy.identity.service.UserAdminService;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlModuleTest;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;

/**
 * Module test of {@code identity} (no module dependency, so nothing to mock): {@link UserAdminService} on the real
 * services, repositories, caches and MySQL, without HTTP (the controllers are covered by {@code AdminUserWriteIT}).
 * UC-A02 lock / UC-A03 unlock with session revocation and status-cache eviction (D-05), UC-A04 grant / UC-A05 revoke
 * with the {@code identity.active-roles} eviction read through {@link RoleQueryApi} (D-07, BR-26), BR-36.
 * The service publishes no event (SRS §7: no mail for these four use cases).
 */
@ApplicationModuleTest(extraIncludes = "shared")
@MySqlModuleTest
@Import(JwtTestTokens.class)
class IdentityModuleIT {

    @Autowired
    UserAdminService admin;
    @Autowired
    RoleQueryApi roleQuery;
    @Autowired
    SessionStatusCache sessionStatus;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        users.deleteAll();
    }

    @Test
    void lock_activeStudent_revokesSessionsAndEvictsStatusCaches_UCA02_D05() {
        TestUser adminUser = users.create(RoleCode.ADMIN);
        Session target = tokens.newUserSession(RoleCode.STUDENT);
        // warm both caches with the "active" answer: the lock must not leave it behind
        assertThat(sessionStatus.isUserActive(target.userId())).isTrue();
        assertThat(sessionStatus.isSessionActive(target.sid())).isTrue();

        AdminUserResponse response = admin.lock(adminUser.id(), target.userId());

        assertThat(response.status()).isEqualTo("locked");
        assertThat(response.roles()).containsExactly("student");
        assertThat(sessionStatus.isUserActive(target.userId())).isFalse();
        assertThat(sessionStatus.isSessionActive(target.sid())).isFalse();
        assertThat(jdbc.sql("SELECT revoke_reason FROM refresh_tokens WHERE user_id = ?").param(target.userId())
                .query(String.class).list()).containsExactly("account_locked");
    }

    @Test
    void lock_rejected_selfAnotherAdminUnknownNonAdminCallerAndLocked_nothingChanges_BR36_BR26() {
        TestUser adminUser = users.create(RoleCode.ADMIN);
        TestUser otherAdmin = users.create(RoleCode.ADMIN);
        Session student = tokens.newUserSession(RoleCode.STUDENT);
        TestUser notAdmin = users.create(RoleCode.STUDENT);

        assertCode(() -> admin.lock(adminUser.id(), adminUser.id()), ErrorCode.ADMIN_LOCK_FORBIDDEN);
        assertCode(() -> admin.lock(adminUser.id(), otherAdmin.id()), ErrorCode.ADMIN_LOCK_FORBIDDEN);
        assertCode(() -> admin.lock(adminUser.id(), Long.MAX_VALUE), ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> admin.lock(notAdmin.id(), student.userId()), ErrorCode.ACCESS_DENIED);
        assertThat(status(student.userId())).isEqualTo("active");

        admin.lock(adminUser.id(), student.userId());
        assertCode(() -> admin.lock(adminUser.id(), student.userId()), ErrorCode.ACCOUNT_ALREADY_LOCKED);

        assertThat(status(adminUser.id())).isEqualTo("active");
        assertThat(status(otherAdmin.id())).isEqualTo("active");
        assertThat(status(student.userId())).isEqualTo("locked");
    }

    @Test
    void unlock_lockedUser_activeAgainSessionsStayRevoked_UCA03() {
        TestUser adminUser = users.create(RoleCode.ADMIN);
        Session target = tokens.newUserSession(RoleCode.STUDENT);
        admin.lock(adminUser.id(), target.userId());
        assertThat(sessionStatus.isUserActive(target.userId())).isFalse(); // caches the "locked" answer

        AdminUserResponse response = admin.unlock(adminUser.id(), target.userId());

        assertThat(response.status()).isEqualTo("active");
        assertThat(sessionStatus.isUserActive(target.userId())).isTrue(); // eviction after commit
        assertThat(sessionStatus.isSessionActive(target.sid())).isFalse(); // the user logs in again
        assertCode(() -> admin.unlock(adminUser.id(), target.userId()), ErrorCode.ACCOUNT_NOT_LOCKED);
    }

    @Test
    void grantAndRevokeInstructor_evictRoleCache_roleRowReused_UCA04_UCA05_D04_D07_BR28() {
        TestUser adminUser = users.create(RoleCode.ADMIN);
        TestUser student = users.create(RoleCode.STUDENT);
        assertThat(roleQuery.hasActiveRole(student.id(), RoleCode.INSTRUCTOR)).isFalse(); // cached

        AdminUserResponse granted = admin.grantInstructor(adminUser.id(), student.id());

        assertThat(granted.roles()).containsExactlyInAnyOrder("student", "instructor");
        assertThat(roleQuery.hasActiveRole(student.id(), RoleCode.INSTRUCTOR)).isTrue();
        assertCode(() -> admin.grantInstructor(adminUser.id(), student.id()),
                ErrorCode.INSTRUCTOR_ROLE_ALREADY_ACTIVE);

        AdminUserResponse revoked = admin.revokeInstructor(adminUser.id(), student.id());

        assertThat(revoked.roles()).containsExactly("student");
        assertThat(roleQuery.hasActiveRole(student.id(), RoleCode.INSTRUCTOR)).isFalse(); // effective at once
        assertCode(() -> admin.revokeInstructor(adminUser.id(), student.id()), ErrorCode.INSTRUCTOR_ROLE_NOT_ACTIVE);

        admin.grantInstructor(adminUser.id(), student.id()); // re-grant by an admin reactivates the same row
        assertThat(roleQuery.hasActiveRole(student.id(), RoleCode.INSTRUCTOR)).isTrue();
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = ? AND r.code = 'instructor'""").param(student.id()).query(Long.class).single())
                .isEqualTo(1L);
    }

    @Test
    void grantInstructor_callerWithoutActiveAdminRole_403AndNothingWritten_BR26() {
        TestUser formerAdmin = users.create(RoleCode.ADMIN);
        users.revokeRole(formerAdmin.id(), RoleCode.ADMIN);
        TestUser student = users.create(RoleCode.STUDENT);

        assertCode(() -> admin.grantInstructor(formerAdmin.id(), student.id()), ErrorCode.ACCESS_DENIED);

        assertThat(roleQuery.activeRoles(student.id())).containsExactly(RoleCode.STUDENT);
    }

    private String status(Long userId) {
        return jdbc.sql("SELECT status FROM users WHERE id = ?").param(userId).query(String.class).single();
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(expected));
    }
}
