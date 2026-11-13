package com.edumy.identity.service;

import java.time.Clock;
import java.util.Objects;

import com.edumy.identity.api.PasswordChanged;
import com.edumy.identity.dto.response.MeResponse;
import com.edumy.identity.entity.RevokeReason;
import com.edumy.identity.entity.User;
import com.edumy.identity.mapper.UserMapper;
import com.edumy.identity.repository.UserRepository;
import com.edumy.identity.repository.UserRoleRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The caller's own account: profile (UC-G09) and change password (UC-G12). The user id always comes from the access
 * token, never from the request.
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final UserRepository users;
    private final UserRoleRepository userRoles;
    private final UserMapper mapper;
    private final SessionService sessions;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ProfileService(UserRepository users, UserRoleRepository userRoles, UserMapper mapper,
                          SessionService sessions, PasswordEncoder passwordEncoder, ApplicationEventPublisher events,
                          TransactionTemplate transactions, Clock clock) {
        this.users = users;
        this.userRoles = userRoles;
        this.mapper = mapper;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** {@code GET /me}: profile + roles active in the database now (BR-26), not the JWT claim. */
    @Transactional(readOnly = true)
    public MeResponse getMe(Long userId) {
        return toResponse(requireUser(userId));
    }

    /** UC-G09: only the full name changes (stored trimmed; email read-only, D-06). */
    @Transactional
    public MeResponse updateFullName(Long userId, String fullName) {
        User user = requireUser(userId);
        user.updateFullName(fullName);
        return toResponse(user);
    }

    /**
     * UC-G12: the current password must match (else 422 {@code PASSWORD_INCORRECT}) and the new one must differ
     * from it (else 422 {@code PASSWORD_SAME_AS_OLD}); then the new hash is stored, every <b>other</b> session ends
     * ({@code password_changed}; session {@code currentSid} keeps working) and {@link PasswordChanged} is published.
     * <p>The three BCrypt operations (~750 ms) run before the transaction. Inside it the row is locked and the stored
     * hash must still be the one just checked: a password changed or reset in between gives
     * {@code PASSWORD_INCORRECT} instead of silently overwriting it.
     */
    public void changePassword(Long userId, String currentSid, String currentPassword, String newPassword) {
        Objects.requireNonNull(currentSid, "currentSid");
        Objects.requireNonNull(currentPassword, "currentPassword");
        Objects.requireNonNull(newPassword, "newPassword");
        String checkedHash = requireUser(userId).getPasswordHash();
        if (!passwordEncoder.matches(currentPassword, checkedHash)) {
            throw new BusinessException(ErrorCode.PASSWORD_INCORRECT);
        }
        if (passwordEncoder.matches(newPassword, checkedHash)) {
            throw new BusinessException(ErrorCode.PASSWORD_SAME_AS_OLD);
        }
        String newHash = passwordEncoder.encode(newPassword);
        transactions.executeWithoutResult(status -> {
            User user = users.findByIdForUpdate(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
            if (!user.getPasswordHash().equals(checkedHash)) {
                throw new BusinessException(ErrorCode.PASSWORD_INCORRECT);
            }
            user.changePasswordHash(newHash);
            PasswordChanged changed = new PasswordChanged(userId, user.getEmail(), user.getFullName(),
                    clock.instant());
            int revoked = sessions.revokeAllExcept(userId, currentSid, RevokeReason.PASSWORD_CHANGED);
            events.publishEvent(changed);
            log.info("Password changed: userId={}, revokedTokens={}", userId, revoked);
        });
    }

    private User requireUser(Long userId) {
        Objects.requireNonNull(userId, "userId");
        // the access-token check guarantees an existing, active user; a vanished one is treated as logged out
        return users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }

    private MeResponse toResponse(User user) {
        return mapper.toMeResponse(user, userRoles.findActiveRoleCodesByUserId(user.getId()));
    }
}
