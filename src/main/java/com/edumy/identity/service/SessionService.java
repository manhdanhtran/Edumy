package com.edumy.identity.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.edumy.identity.dto.response.AuthTokenResponse;
import com.edumy.identity.entity.RefreshToken;
import com.edumy.identity.entity.RevokeReason;
import com.edumy.identity.entity.User;
import com.edumy.identity.repository.RefreshTokenRepository;
import com.edumy.identity.repository.UserRepository;
import com.edumy.identity.repository.UserRoleRepository;
import com.edumy.identity.security.JwtIssuer;
import com.edumy.identity.security.LoginAttemptLimiter;
import com.edumy.identity.security.SessionStatusCache;
import com.edumy.shared.config.EdumyProperties;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Login sessions (ADR-002, docs/ai/04 §4.1): one session = one refresh-token family whose id is the JWT {@code sid}.
 * Login (UC-G07) opens a family, refresh rotates its token, logout (UC-G10) and the bulk revokes (UC-G08, UC-G12,
 * UC-A02 / D-05) end families. Every revoke evicts the session-status cache after commit
 * ({@link SessionStatusCache}), so the access tokens of an ended session are rejected immediately.
 * <p>Transactions: {@link #login} and {@link #refresh} use {@link TransactionTemplate} on purpose.
 * <ul>
 *   <li>login: the BCrypt check (~250 ms at cost 12) runs before the transaction, so no pooled connection is held
 *       while hashing; only the session insert is transactional.</li>
 *   <li>refresh: the transaction returns an outcome and the 401 is thrown <b>after</b> it committed. A reused rotated
 *       token must revoke its whole family ({@code reuse_detected}) even though the call fails; throwing inside the
 *       transaction would roll that revocation back.</li>
 * </ul>
 * Raw passwords and tokens are never logged (NFR-08).
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    /** Not a secret: only hashed once at start-up to give unknown emails the same BCrypt cost as real accounts. */
    private static final String DUMMY_PASSWORD = "edumy-login-timing-equalizer";

    private final UserRepository users;
    private final UserRoleRepository userRoles;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtIssuer jwtIssuer;
    private final SessionStatusCache sessionStatus;
    private final LoginAttemptLimiter loginAttempts;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration refreshTtl;
    private final String dummyHash;

    public SessionService(UserRepository users, UserRoleRepository userRoles, RefreshTokenRepository refreshTokens,
                          PasswordEncoder passwordEncoder, JwtIssuer jwtIssuer, SessionStatusCache sessionStatus,
                          LoginAttemptLimiter loginAttempts, TransactionTemplate transactions, Clock clock,
                          EdumyProperties properties) {
        this.users = users;
        this.userRoles = userRoles;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtIssuer = jwtIssuer;
        this.sessionStatus = sessionStatus;
        this.loginAttempts = loginAttempts;
        this.transactions = transactions;
        this.clock = clock;
        this.refreshTtl = properties.security().jwt().refreshTtl();
        // same encoder bean → same cost (12, NFR-02) as every stored hash
        this.dummyHash = passwordEncoder.encode(DUMMY_PASSWORD);
    }

    /**
     * UC-G07. Unknown email and wrong password give the same 401 {@code AUTH_INVALID_CREDENTIALS} after the same
     * BCrypt work; only the right password on a locked account reveals the lock (403 {@code AUTH_ACCOUNT_LOCKED}, Q1).
     * An unverified email may log in (D-02 restricts actions, not login). NFR-03: 5 consecutive failures for an email
     * block it for 15 min (429 {@code AUTH_LOGIN_TEMPORARILY_BLOCKED}, {@link LoginAttemptLimiter}).
     */
    public SessionTokens login(String email, String password, String userAgent, String ipAddress) {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(password, "password");
        String normalizedEmail = User.normalizeEmail(email);
        // NFR-03: a blocked email gets 429 before any lookup or BCrypt, whatever the password (read-only check)
        loginAttempts.checkNotBlocked(normalizedEmail);

        Optional<User> found = users.findByEmail(normalizedEmail);
        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash); // equal timing for unknown emails (D-03)
            throw invalidCredentials(normalizedEmail);
        }
        User user = found.get();
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw invalidCredentials(normalizedEmail);
        }
        // successful password check (also on a locked account, which then gets 403) → forget the failures
        loginAttempts.reset(normalizedEmail);
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED);
        }
        // a lock committed after this check is still effective: the lock revokes every session of the user, and the
        // access-token check and refresh both require status = active
        return Objects.requireNonNull(transactions.execute(status -> openSession(user.getId(), userAgent, ipAddress)));
    }

    /**
     * UC-G05 step 7: opens a session for a user that was just created in the caller's transaction (no password check:
     * the caller has just set it). {@code MANDATORY}: the account, its first session and its verification code commit
     * or roll back together, so a 201 always carries tokens of a committed account and a failure leaves no half-made
     * account behind.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SessionTokens startSessionForNewUser(Long userId, String userAgent, String ipAddress) {
        Objects.requireNonNull(userId, "userId");
        return openSession(userId, userAgent, ipAddress);
    }

    /**
     * {@code /auth/refresh}: rotates the presented refresh token. Every rejection is the same 401
     * {@code UNAUTHENTICATED} (unknown, expired, revoked, user not active), without saying why. A token already
     * revoked as {@code rotated} is a reuse: its whole family is revoked as {@code reuse_detected} and that revocation
     * is committed before the 401 (see class doc).
     */
    public SessionTokens refresh(String rawRefreshToken, String userAgent, String ipAddress) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw unauthenticated();
        }
        String tokenHash = SecureTokens.sha256Hex(rawRefreshToken);
        Optional<SessionTokens> rotated = transactions.execute(status -> rotate(tokenHash, userAgent, ipAddress));
        // reached after commit: a reuse_detected revocation is already durable when the 401 is thrown
        return Objects.requireNonNull(rotated).orElseThrow(SessionService::unauthenticated);
    }

    /** UC-G10: ends session {@code sid} (the caller's own, from the access token). */
    @Transactional
    public void logout(String sid) {
        Objects.requireNonNull(sid, "sid");
        refreshTokens.revokeFamily(sid, RevokeReason.LOGOUT, clock.instant());
        sessionStatus.evictSession(sid);
    }

    /**
     * Ends every session of the user (UC-G08 reset password: {@code password_reset}; UC-A02 lock, D-05:
     * {@code account_locked}). The lock flow must also evict the user status ({@link SessionStatusCache#evictUser}).
     *
     * @return number of refresh tokens revoked
     */
    @Transactional
    public int revokeAll(Long userId, RevokeReason reason) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(reason, "reason");
        // the bulk UPDATE cannot return the family ids: read them first to evict each session after commit
        List<String> sids = refreshTokens.findActiveFamilyIdsByUserId(userId);
        int revoked = refreshTokens.revokeAllOfUser(userId, reason, clock.instant());
        sids.forEach(sessionStatus::evictSession);
        return revoked;
    }

    /**
     * Ends every session of the user except {@code keepSid} (UC-G12 change password: {@code password_changed}).
     *
     * @return number of refresh tokens revoked
     */
    @Transactional
    public int revokeAllExcept(Long userId, String keepSid, RevokeReason reason) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(keepSid, "keepSid");
        Objects.requireNonNull(reason, "reason");
        List<String> sids = refreshTokens.findActiveFamilyIdsByUserId(userId);
        int revoked = refreshTokens.revokeAllOfUserExceptFamily(userId, keepSid, reason, clock.instant());
        sids.stream().filter(sid -> !sid.equals(keepSid)).forEach(sessionStatus::evictSession);
        return revoked;
    }

    /** BCrypt hash used for unknown emails (package-private for the cost test). */
    String dummyHash() {
        return dummyHash;
    }

    // ------------------------------------------------------------------------------------------- inside transaction

    private SessionTokens openSession(Long userId, String userAgent, String ipAddress) {
        String sid = UUID.randomUUID().toString();
        String refreshToken = SecureTokens.newToken();
        refreshTokens.save(RefreshToken.issue(users.getReferenceById(userId), sid,
                SecureTokens.sha256Hex(refreshToken), refreshTtl, userAgent, ipAddress, clock.instant()));
        return issueTokens(userId, sid, refreshToken);
    }

    /** Empty = reject with 401 after commit. */
    private Optional<SessionTokens> rotate(String tokenHash, String userAgent, String ipAddress) {
        Optional<RefreshToken> found = refreshTokens.findForUpdateByTokenHash(tokenHash); // row lock: one winner
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken current = found.get();
        Instant now = clock.instant();
        String sid = current.getFamilyId();
        User user = current.getUser();
        Long userId = user.getId();

        if (current.getRevokeReason() == RevokeReason.ROTATED) {
            // checked before expiry: an old stolen token replayed late still ends the session it belongs to
            int revoked = refreshTokens.revokeFamily(sid, RevokeReason.REUSE_DETECTED, now);
            sessionStatus.evictSession(sid);
            log.warn("Refresh token reuse detected, session ended: userId={}, revokedTokens={}", userId, revoked);
            return Optional.empty();
        }
        if (!current.isActive(now) || !user.isActive()) {
            return Optional.empty(); // revoked for another reason, expired, or account locked
        }
        current.revoke(RevokeReason.ROTATED, now);
        String next = SecureTokens.newToken();
        refreshTokens.save(RefreshToken.issue(user, sid, SecureTokens.sha256Hex(next), refreshTtl,
                userAgent, ipAddress, now));
        return Optional.of(issueTokens(userId, sid, next));
    }

    /** Access JWT with the roles active in the database right now (BR-26), plus the raw refresh token. */
    private SessionTokens issueTokens(Long userId, String sid, String refreshToken) {
        List<RoleCode> roles = userRoles.findActiveRoleCodesByUserId(userId);
        JwtIssuer.AccessToken access = jwtIssuer.issue(userId, roles, sid);
        return new SessionTokens(new AuthTokenResponse(access.value(), access.expiresAt()), refreshToken, refreshTtl);
    }

    private BusinessException invalidCredentials(String normalizedEmail) {
        loginAttempts.recordFailure(normalizedEmail); // NFR-03, unknown emails included
        return new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    private static BusinessException unauthenticated() {
        return new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
}
