package com.edumy.identity.service;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import com.edumy.identity.dto.response.MeResponse;
import com.edumy.identity.entity.Role;
import com.edumy.identity.entity.User;
import com.edumy.identity.entity.UserRole;
import com.edumy.identity.mapper.UserMapper;
import com.edumy.identity.repository.RoleRepository;
import com.edumy.identity.repository.UserRepository;
import com.edumy.identity.repository.UserRoleRepository;
import com.edumy.shared.config.CacheConfig;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * UC-G13 self-registration as instructor (D-04: no admin approval; quality control happens at course review). No
 * request body (Q6: the SPA shows the terms, steps 1–2).
 */
@Service
public class InstructorRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(InstructorRegistrationService.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final UserMapper mapper;
    private final Cache activeRoles;
    private final Clock clock;

    public InstructorRegistrationService(UserRepository users, RoleRepository roles, UserRoleRepository userRoles,
                                         UserMapper mapper, CacheManager cacheManager, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.userRoles = userRoles;
        this.mapper = mapper;
        this.activeRoles = Objects.requireNonNull(cacheManager.getCache(CacheConfig.ACTIVE_ROLES),
                "cache " + CacheConfig.ACTIVE_ROLES);
        this.clock = clock;
    }

    /**
     * Checks, in the SRS order:
     * <ol>
     *   <li>E1: email not verified → 403 {@code EMAIL_NOT_VERIFIED} (D-02);</li>
     *   <li>E2: the instructor row exists but is inactive (revoked by an admin, UC-A05) → 403
     *       {@code INSTRUCTOR_ROLE_REVOKED}: only an admin may grant it again (UC-A04, D-04);</li>
     *   <li>E3: already active → nothing changes ({@code granted = false}, the controller answers 200);</li>
     *   <li>otherwise step 4: grant now, {@code assigned_by} null ({@code granted = true}, 201).</li>
     * </ol>
     * The user row is locked, which serializes this with an admin grant/revoke of the same user (UC-A04/A05 lock it
     * too). A grant evicts the user's {@value CacheConfig#ACTIVE_ROLES} entry (key = user id) after commit. The new
     * role is in the database (and in the returned roles) at once; the access token gets it at the next refresh.
     */
    @Transactional
    public InstructorRegistrationResult register(Long userId) {
        Objects.requireNonNull(userId, "userId");
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        if (!user.isEmailVerified()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        Optional<UserRole> existing = userRoles.findByUserIdAndRoleCode(userId, RoleCode.INSTRUCTOR);
        if (existing.isPresent() && !existing.get().isActive()) {
            throw new BusinessException(ErrorCode.INSTRUCTOR_ROLE_REVOKED);
        }
        boolean granted = existing.isEmpty();
        if (granted) {
            Role instructor = roles.findByCode(RoleCode.INSTRUCTOR).orElseThrow(
                    () -> new IllegalStateException("Role instructor missing (V6__reference_data.sql)"));
            userRoles.save(UserRole.grant(user, instructor, null, clock.instant()));
            evictActiveRolesAfterCommit(userId);
            log.info("Instructor role self-registered: userId={}", userId);
        }
        MeResponse me = mapper.toMeResponse(user, userRoles.findActiveRoleCodesByUserId(userId));
        return new InstructorRegistrationResult(granted, me);
    }

    /** Evicting before commit would let a concurrent read re-cache the old roles (same reasoning as SessionStatusCache). */
    private void evictActiveRolesAfterCommit(Long userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                activeRoles.evict(userId);
            }
        });
    }

    /** Outcome of {@link #register}: {@code granted} = a new role row was created (201) rather than E3 (200). */
    public record InstructorRegistrationResult(boolean granted, MeResponse me) {

        public InstructorRegistrationResult {
            Objects.requireNonNull(me, "me");
        }
    }
}
