package com.edumy.identity.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.edumy.identity.entity.TokenType;
import com.edumy.identity.entity.UserToken;
import com.edumy.identity.repository.UserRepository;
import com.edumy.identity.repository.UserTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emailed single-use codes of {@code user_tokens} (BR-34, D-03, docs/ai/04 §4.2): email verification (24 h) and
 * password reset (30 min), lifetimes from {@link TokenType#ttl()}. The raw code (32 random bytes, base64url) only
 * leaves through the event that carries it to the mail; the table stores its SHA-256 hex. Never log a raw code
 * (NFR-08).
 * <p>{@link #issue} and {@link #consume} run bulk UPDATEs that clear the persistence context: entities loaded before
 * the call are detached afterwards (re-load them, or read what you need first).
 */
@Service
public class AccountTokenService {

    /** D-03: a new code of the same type at most every 60 s (resend, forgot password). */
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    private final UserTokenRepository tokens;
    private final UserRepository users;
    private final Clock clock;

    public AccountTokenService(UserTokenRepository tokens, UserRepository users, Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.clock = clock;
    }

    /**
     * BR-34: voids every unused code of the user + type, then stores the hash of a new one valid for
     * {@code type.ttl()}.
     *
     * @return the raw code, for the mail link only
     */
    @Transactional
    public String issue(Long userId, TokenType type) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(type, "type");
        Instant now = clock.instant();
        tokens.invalidateUnused(userId, type, now);
        String raw = SecureTokens.newToken();
        tokens.save(UserToken.issue(users.getReferenceById(userId), SecureTokens.sha256Hex(raw), type, now));
        return raw;
    }

    /**
     * T7 (docs/ai/01 §1.11): one atomic UPDATE marks the code used if it exists with this type, is unused and not
     * expired; only then is its owner read. Two concurrent uses of one code: exactly one wins.
     *
     * @return the owner's id, or empty for an unknown, wrong-type, used or expired code (never says which)
     */
    @Transactional
    public Optional<Long> consume(String rawCode, TokenType type) {
        Objects.requireNonNull(type, "type");
        if (rawCode == null || rawCode.isBlank()) {
            return Optional.empty();
        }
        String hash = SecureTokens.sha256Hex(rawCode);
        if (tokens.consume(hash, type, clock.instant()) != 1) {
            return Optional.empty();
        }
        return tokens.findUserIdByTokenHashAndType(hash, type);
    }

    /** P13: the raw code is a usable code of {@code type} right now (no write; {@link #consume} decides). */
    @Transactional(readOnly = true)
    public boolean isUsable(String rawCode, TokenType type) {
        return rawCode != null && !rawCode.isBlank()
                && tokens.isUsable(SecureTokens.sha256Hex(rawCode), type, clock.instant());
    }

    /** D-03: true when the user has no code of this type yet or the latest one is at least 60 s old. */
    @Transactional(readOnly = true)
    public boolean canResend(Long userId, TokenType type) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(type, "type");
        Instant now = clock.instant();
        return tokens.findLatestCreatedAt(userId, type)
                .map(latest -> !latest.plus(RESEND_COOLDOWN).isAfter(now))
                .orElse(true);
    }
}
