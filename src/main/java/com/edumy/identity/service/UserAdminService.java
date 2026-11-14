package com.edumy.identity.service;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import com.edumy.identity.dto.response.AdminUserResponse;
import com.edumy.identity.entity.RevokeReason;
import com.edumy.identity.entity.Role;
import com.edumy.identity.entity.User;
import com.edumy.identity.entity.UserRole;
import com.edumy.identity.mapper.UserMapper;
import com.edumy.identity.repository.RoleRepository;
import com.edumy.identity.repository.UserRepository;
import com.edumy.identity.repository.UserRoleRepository;
import com.edumy.identity.security.SessionStatusCache;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin writes on user accounts: lock / unlock (UC-A02 / UC-A03) and grant / revoke of the instructor role
 * (UC-A04 / UC-A05). Every method
 * <ol>
 *   <li>re-checks that the caller still holds an active admin role in the database (BR-26: the JWT {@code roles}
 *       claim may be up to 15 min old) — else 403 {@code ACCESS_DENIED};</li>
 *   <li>locks the target {@code users} row ({@code SELECT … FOR UPDATE}): two admins acting on the same account, or
 *       an admin action racing the user's own UC-G13, are serialized; an unknown id → 404
 *       {@code RESOURCE_NOT_FOUND};</li>
 *   <li>refuses a transition whose precondition does not hold with a 409 (no silent no-op) and changes nothing;</li>
 *   <li>answers the changed account as {@link AdminUserResponse} (roles read from the database after the change).</li>
 * </ol>
 * Cache evictions run after commit. No mail: SRS §7 lists none for these four use cases.
 */
@Service
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final SessionService sessions;
    private final SessionStatusCache sessionStatus;
    private final RoleCacheEvictor roleCache;
    private final UserMapper mapper;
    private final Clock clock;

    public UserAdminService(UserRepository users, RoleRepository roles, UserRoleRepository userRoles,
                            SessionService sessions, SessionStatusCache sessionStatus, RoleCacheEvictor roleCache,
                            UserMapper mapper, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.userRoles = userRoles;
        this.sessions = sessions;
        this.sessionStatus = sessionStatus;
        this.roleCache = roleCache;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * UC-A02. BR-36: the target must be neither the caller nor an account holding an active admin role → else 403
     * {@code ADMIN_LOCK_FORBIDDEN}, nothing changes (AC-37). Because no admin can ever be locked (and admin roles
     * are never granted or revoked through the API, D-01), "at least one active admin" holds by construction, so
     * {@code ADMIN_LAST_ACTIVE} cannot occur here. A locked target → 409 {@code ACCOUNT_ALREADY_LOCKED}.
     * <p>D-05: every session ends at once — all refresh-token families revoked {@code account_locked}, the session
     * and user status caches evicted after commit, so the live access tokens get 401 on their next request.
     * Enrollments and orders are untouched.
     */
    @Transactional
    public AdminUserResponse lock(Long adminId, Long userId) {
        requireActiveAdmin(adminId);
        if (userId.equals(adminId)) {
            throw new BusinessException(ErrorCode.ADMIN_LOCK_FORBIDDEN);
        }
        User user = lockUser(userId);
        // BR-36: read under the row lock of the target; admin roles cannot change through the API (D-01)
        if (userRoles.existsActiveRole(userId, RoleCode.ADMIN)) {
            throw new BusinessException(ErrorCode.ADMIN_LOCK_FORBIDDEN);
        }
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_LOCKED);
        }
        user.lock();
        // flushes the status change, then clears the persistence context (user is detached, already loaded)
        int revoked = sessions.revokeAll(userId, RevokeReason.ACCOUNT_LOCKED);
        sessionStatus.evictUser(userId);
        log.info("User locked: userId={}, by adminId={}, revokedTokens={}", userId, adminId, revoked);
        return toResponse(user);
    }

    /**
     * UC-A03: locked → active; an active target → 409 {@code ACCOUNT_NOT_LOCKED}. The sessions ended by the lock stay
     * ended: the user logs in again.
     */
    @Transactional
    public AdminUserResponse unlock(Long adminId, Long userId) {
        requireActiveAdmin(adminId);
        User user = lockUser(userId);
        if (user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_LOCKED);
        }
        user.unlock();
        sessionStatus.evictUser(userId);
        log.info("User unlocked: userId={}, by adminId={}", userId, adminId);
        return toResponse(user);
    }

    /**
     * UC-A04: creates the instructor role row, or reactivates the one revoked earlier (one row per user and role,
     * never a duplicate; D-04: only an admin can re-grant), with {@code assigned_by} = the admin and
     * {@code assigned_at} = now. Already active → 409 {@code INSTRUCTOR_ROLE_ALREADY_ACTIVE} (E1). The SRS sets no
     * other precondition (email verification and account status are not checked).
     */
    @Transactional
    public AdminUserResponse grantInstructor(Long adminId, Long userId) {
        requireActiveAdmin(adminId);
        User user = lockUser(userId);
        Optional<UserRole> existing = userRoles.findByUserIdAndRoleCode(userId, RoleCode.INSTRUCTOR);
        if (existing.isPresent() && existing.get().isActive()) {
            throw new BusinessException(ErrorCode.INSTRUCTOR_ROLE_ALREADY_ACTIVE);
        }
        User admin = users.getReferenceById(adminId);
        if (existing.isPresent()) {
            existing.get().reactivate(admin, clock.instant());
        } else {
            Role instructor = roles.findByCode(RoleCode.INSTRUCTOR).orElseThrow(
                    () -> new IllegalStateException("Role instructor missing (V6__reference_data.sql)"));
            userRoles.save(UserRole.grant(user, instructor, admin, clock.instant()));
        }
        roleCache.evict(userId);
        log.info("Instructor role granted: userId={}, by adminId={}, reactivated={}", userId, adminId,
                existing.isPresent());
        return toResponse(user);
    }

    /**
     * UC-A05: sets the active instructor role inactive (row kept, BR-28); no active role → 409
     * {@code INSTRUCTOR_ROLE_NOT_ACTIVE} (E1). D-07: the cached roles are evicted after commit, so authoring and
     * learning (which re-check the role through {@code RoleQueryApi}, BR-26) refuse editing, co-teaching and Q&A
     * replies on the next request. Published courses, enrolled students and the user's sessions are untouched.
     */
    @Transactional
    public AdminUserResponse revokeInstructor(Long adminId, Long userId) {
        requireActiveAdmin(adminId);
        User user = lockUser(userId);
        UserRole role = userRoles.findByUserIdAndRoleCode(userId, RoleCode.INSTRUCTOR)
                .filter(UserRole::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSTRUCTOR_ROLE_NOT_ACTIVE));
        role.revoke();
        roleCache.evict(userId);
        log.info("Instructor role revoked: userId={}, by adminId={}", userId, adminId);
        return toResponse(user);
    }

    /** BR-26: the acting admin must still hold the admin role in the database at the time of the action. */
    private void requireActiveAdmin(Long adminId) {
        Objects.requireNonNull(adminId, "adminId");
        if (!userRoles.existsActiveRole(adminId, RoleCode.ADMIN)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private User lockUser(Long userId) {
        Objects.requireNonNull(userId, "userId");
        return users.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private AdminUserResponse toResponse(User user) {
        return mapper.toAdminUserResponse(user, userRoles.findActiveRoleCodesByUserId(user.getId()));
    }
}
