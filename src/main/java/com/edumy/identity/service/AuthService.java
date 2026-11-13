package com.edumy.identity.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.edumy.identity.api.EmailVerificationRequested;
import com.edumy.identity.api.PasswordChanged;
import com.edumy.identity.api.PasswordResetRequested;
import com.edumy.identity.api.UserRegistered;
import com.edumy.identity.entity.RevokeReason;
import com.edumy.identity.entity.Role;
import com.edumy.identity.entity.TokenType;
import com.edumy.identity.entity.User;
import com.edumy.identity.entity.UserRole;
import com.edumy.identity.repository.RoleRepository;
import com.edumy.identity.repository.UserRepository;
import com.edumy.identity.repository.UserRoleRepository;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Account flows without a password login: register (UC-G05), email verification (UC-G06), forgot / reset password
 * (UC-G08). Codes: {@link AccountTokenService} (BR-34, D-03).
 * <p>Mail: every flow only <b>publishes</b> an {@code identity.api} event inside its transaction; the notification
 * module sends the mail asynchronously after commit, so nothing mail-related runs in the transaction and a mail
 * failure never rolls back the account change (UC-G05 E3).
 * <p>BCrypt (~250 ms at cost 12) runs before the transaction in {@link #register} and {@link #resetPassword}
 * ({@link TransactionTemplate}), so no pooled connection is held while hashing. Raw passwords and codes are never
 * logged (NFR-08).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final AccountTokenService accountTokens;
    private final SessionService sessions;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AuthService(UserRepository users, RoleRepository roles, UserRoleRepository userRoles,
                       AccountTokenService accountTokens, SessionService sessions, PasswordEncoder passwordEncoder,
                       ApplicationEventPublisher events, TransactionTemplate transactions, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.userRoles = userRoles;
        this.accountTokens = accountTokens;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * UC-G05: account (active, email not verified) + student role (assigned_by null, D-01) + 24 h verification code
     * + {@link UserRegistered}, then logs the user in (step 7) in the <b>same</b> transaction
     * ({@link SessionService#startSessionForNewUser}). The email must be free (E1, 409
     * {@code EMAIL_ALREADY_REGISTERED}); under concurrency {@code uq_users_email} decides and is translated to the same
     * code. The password policy was checked on the request (NFR-02).
     */
    public SessionTokens register(String email, String password, String fullName, String userAgent,
                                  String ipAddress) {
        Objects.requireNonNull(password, "password");
        String normalizedEmail = User.normalizeEmail(email);
        if (users.existsByEmail(normalizedEmail)) { // before BCrypt: a taken email costs no hashing
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        String passwordHash = passwordEncoder.encode(password);
        return Objects.requireNonNull(transactions.execute(status -> {
            Instant now = clock.instant();
            User user = users.save(User.register(normalizedEmail, passwordHash, fullName, now));
            Long userId = user.getId();
            userRoles.save(UserRole.grant(user, requireRole(RoleCode.STUDENT), null, now));
            UserRegistered registered = new UserRegistered(userId, user.getEmail(), user.getFullName(),
                    accountTokens.issue(userId, TokenType.EMAIL_VERIFICATION));
            events.publishEvent(registered);
            log.info("User registered: userId={}", userId);
            return sessions.startSessionForNewUser(userId, userAgent, ipAddress);
        }));
    }

    /**
     * UC-G06 confirm (public, the link may be opened on another device): consumes the code (T7) and marks the email
     * verified. Unknown, used, expired or wrong-type codes are all 422 {@code ACCOUNT_TOKEN_INVALID}.
     */
    @Transactional
    public void confirmEmail(String rawCode) {
        Long userId = accountTokens.consume(rawCode, TokenType.EMAIL_VERIFICATION)
                .orElseThrow(AuthService::tokenInvalid);
        User user = users.findById(userId).orElseThrow(AuthService::tokenInvalid);
        if (!user.isEmailVerified()) { // a still-valid code of an already verified account: nothing left to do
            user.verifyEmail();
        }
    }

    /**
     * UC-G06 resend (Q2: logged-in caller, no body). Already verified → nothing sent ({@code false}). A code issued
     * less than 60 s ago → 429 {@code TOKEN_RESEND_TOO_SOON} (D-03). Otherwise a new code voids the older ones (BR-34)
     * and {@link EmailVerificationRequested} is published ({@code true}). The user row is locked so two concurrent
     * resends cannot both pass the cooldown.
     *
     * @return whether a new code was issued (a mail will be sent)
     */
    @Transactional
    public boolean resendVerification(Long userId) {
        Objects.requireNonNull(userId, "userId");
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        if (user.isEmailVerified()) {
            return false;
        }
        if (!accountTokens.canResend(userId, TokenType.EMAIL_VERIFICATION)) {
            throw new BusinessException(ErrorCode.TOKEN_RESEND_TOO_SOON);
        }
        String email = user.getEmail();
        String fullName = user.getFullName();
        String code = accountTokens.issue(userId, TokenType.EMAIL_VERIFICATION);
        events.publishEvent(new EmailVerificationRequested(userId, email, fullName, code));
        return true;
    }

    /**
     * UC-G08 steps 1–3. The caller always answers the same 202 (D-03, Q3), whatever happens here: unknown email,
     * locked account (E3: no code), a code issued less than 60 s ago (no new code, no visible signal — D-03's uniform
     * answer overrides E2's "please wait"), or the normal case: older reset codes voided, a new 30 min code issued
     * and {@link PasswordResetRequested} published. No BCrypt here; the extra work of the normal case is a few
     * indexed queries, and the mail itself is sent after commit, outside the request.
     */
    @Transactional
    public void forgotPassword(String email) {
        Optional<User> found = users.findByEmail(User.normalizeEmail(email));
        if (found.isEmpty() || !found.get().isActive()) {
            return;
        }
        Long userId = found.get().getId();
        User user = users.findByIdForUpdate(userId).orElse(null); // serializes concurrent requests of one account
        if (user == null || !user.isActive() || !accountTokens.canResend(userId, TokenType.PASSWORD_RESET)) {
            return;
        }
        String userEmail = user.getEmail();
        String fullName = user.getFullName();
        String code = accountTokens.issue(userId, TokenType.PASSWORD_RESET);
        events.publishEvent(new PasswordResetRequested(userId, userEmail, fullName, code));
        log.info("Password reset code issued: userId={}", userId);
    }

    /**
     * UC-G08 steps 5–7: consumes the reset code (T7), sets the new password, ends <b>every</b> session
     * ({@code password_reset}, docs/ai/04 §4.1) and publishes {@link PasswordChanged}. Does not log in (step 7).
     * Invalid code (E1) and locked account → the same 422 {@code ACCOUNT_TOKEN_INVALID}; for a locked account the
     * whole transaction rolls back, so the code stays unused until it expires.
     */
    public void resetPassword(String rawCode, String newPassword) {
        Objects.requireNonNull(newPassword, "newPassword");
        String passwordHash = passwordEncoder.encode(newPassword);
        transactions.executeWithoutResult(status -> {
            Long userId = accountTokens.consume(rawCode, TokenType.PASSWORD_RESET)
                    .orElseThrow(AuthService::tokenInvalid);
            User user = users.findByIdForUpdate(userId).orElseThrow(AuthService::tokenInvalid);
            if (!user.isActive()) {
                throw tokenInvalid(); // no reset of a locked account (UC-G08 E3)
            }
            user.changePasswordHash(passwordHash);
            PasswordChanged changed = new PasswordChanged(userId, user.getEmail(), user.getFullName(),
                    clock.instant());
            int revoked = sessions.revokeAll(userId, RevokeReason.PASSWORD_RESET); // flushes the new hash first
            events.publishEvent(changed);
            log.info("Password reset: userId={}, revokedTokens={}", userId, revoked);
        });
    }

    private Role requireRole(RoleCode code) {
        return roles.findByCode(code)
                .orElseThrow(() -> new IllegalStateException("Role " + code + " missing (V6__reference_data.sql)"));
    }

    private static BusinessException tokenInvalid() {
        return new BusinessException(ErrorCode.ACCOUNT_TOKEN_INVALID);
    }
}
