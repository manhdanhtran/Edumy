package com.edumy.identity.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.edumy.identity.entity.UserRole;
import com.edumy.identity.entity.UserStatus;
import com.edumy.shared.security.RoleCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    /** The (unique) row of a user + role, active or revoked (uq_user_roles_user_role; grant / revoke / D-04 check). */
    @Query("select ur from UserRole ur where ur.user.id = :userId and ur.role.code = :role")
    Optional<UserRole> findByUserIdAndRoleCode(@Param("userId") Long userId, @Param("role") RoleCode role);

    /** Active role codes of a user: JWT {@code roles} claim at login/refresh, {@code GET /me}. */
    @Query("select ur.role.code from UserRole ur where ur.user.id = :userId and ur.active = true order by ur.role.id")
    List<RoleCode> findActiveRoleCodesByUserId(@Param("userId") Long userId);

    /** Active role codes of several users at once (one query for a page of UC-A01). */
    @Query("""
            select ur.user.id as userId, ur.role.code as roleCode from UserRole ur
            where ur.user.id in :userIds and ur.active = true
            order by ur.user.id, ur.role.id
            """)
    List<UserRoleCodeView> findActiveRoleCodesByUserIdIn(@Param("userIds") Collection<Long> userIds);

    /** {@code RoleQueryApi.hasActiveRole} — BR-26: permissions re-checked from the DB at action time. */
    @Query("""
            select case when count(ur) > 0 then true else false end from UserRole ur
            where ur.user.id = :userId and ur.role.code = :role and ur.active = true
            """)
    boolean existsActiveRole(@Param("userId") Long userId, @Param("role") RoleCode role);

    /** Users with an active {@code role} whose account has {@code userStatus} — e.g. active admins for BR-36. */
    @Query("""
            select count(distinct ur.user.id) from UserRole ur
            where ur.role.code = :role and ur.active = true and ur.user.status = :userStatus
            """)
    long countActiveHolders(@Param("role") RoleCode role, @Param("userStatus") UserStatus userStatus);

    /** One active role of one user. */
    interface UserRoleCodeView {
        Long getUserId();

        RoleCode getRoleCode();
    }
}
