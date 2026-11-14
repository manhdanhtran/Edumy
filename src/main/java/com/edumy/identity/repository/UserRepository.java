package com.edumy.identity.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.edumy.identity.entity.User;
import com.edumy.identity.entity.UserStatus;
import com.edumy.shared.security.RoleCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Login, register, forgot password, resend. {@code email} must be normalized with {@link User#normalizeEmail}. */
    Optional<User> findByEmail(String email);

    /** Registration pre-check (uq_users_email still decides under concurrency). Normalized email. */
    boolean existsByEmail(String email);

    /** {@code identity::api.UserQueryApi.isEmailVerified} (D-02). */
    boolean existsByIdAndEmailVerifiedTrue(Long id);

    /** Status only (no entity load): access-token check on every request (ADR-002, {@code SessionStatusCache}). */
    @Query("select u.status from User u where u.id = :id")
    Optional<UserStatus> findStatusById(@Param("id") Long id);

    /** Row lock for lock/unlock and role changes of one user (UC-A02…A05, BR-36 checks in the same transaction). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /**
     * UC-A01 {@code GET /admin/users?q=&status=&role=}. Every filter is optional (null = no filter). {@code q} matches a
     * substring of the email (compared lower-case, column is utf8mb4_bin) or of the full name (ai_ci: case- and
     * accent-insensitive); {@code %}/{@code _} in {@code q} act as LIKE wildcards. {@code role} keeps users holding that
     * role actively. Roles of the page: {@link UserRoleRepository#findActiveRoleCodesByUserIdIn}.
     */
    @Query(value = """
            select u.id as id, u.email as email, u.fullName as fullName, u.emailVerified as emailVerified,
                   u.status as status, u.createdAt as createdAt
            from User u
            where (:q is null or u.email like concat('%', lower(:q), '%') or u.fullName like concat('%', :q, '%'))
              and (:status is null or u.status = :status)
              and (:role is null or exists (select 1 from UserRole ur
                                            where ur.user = u and ur.active = true and ur.role.code = :role))
            """,
            countQuery = """
            select count(u) from User u
            where (:q is null or u.email like concat('%', lower(:q), '%') or u.fullName like concat('%', :q, '%'))
              and (:status is null or u.status = :status)
              and (:role is null or exists (select 1 from UserRole ur
                                            where ur.user = u and ur.active = true and ur.role.code = :role))
            """)
    Page<AdminUserView> searchForAdmin(@Param("q") String q, @Param("status") UserStatus status,
                                       @Param("role") RoleCode role, Pageable pageable);

    /** {@code identity::api.UserQueryApi.findIdByEmail}: normalized email to id, no entity load. */
    @Query("select u.id from User u where u.email = :email")
    Optional<Long> findIdByEmail(@Param("email") String email);

    /** {@code identity::api.UserQueryApi}: public columns of several users in one statement (no N+1). */
    @Query("select u.id as id, u.fullName as fullName, u.email as email, u.status as status from User u where u.id in :ids")
    List<UserSummaryView> findSummariesByIdIn(@Param("ids") Collection<Long> ids);

    /** Row of {@link #findSummariesByIdIn}. */
    interface UserSummaryView {
        Long getId();

        String getFullName();

        String getEmail();

        UserStatus getStatus();
    }

    /** Row of the admin user list (UC-A01). */
    interface AdminUserView {
        Long getId();

        String getEmail();

        String getFullName();

        boolean isEmailVerified();

        UserStatus getStatus();

        Instant getCreatedAt();
    }
}
