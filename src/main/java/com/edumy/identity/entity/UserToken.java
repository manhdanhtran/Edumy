package com.edumy.identity.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code user_tokens} (EDU-DDD-001 §5.4). Only the SHA-256 hex of the emailed code is stored; single use (BR-34).
 * The real consume path is the atomic UPDATE of T7 ({@code UserTokenRepository.consume}); {@link #markUsed} is the
 * in-memory equivalent for code that already holds the entity.
 */
@Entity
@Table(name = "user_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserToken {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "token_type", nullable = false, length = 30, updatable = false)
    private TokenType tokenType;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * New single-use code valid for {@link TokenType#ttl()} (BR-34). The caller first voids older unused codes of the
     * same type ({@code UserTokenRepository.invalidateUnused}).
     */
    public static UserToken issue(User user, String tokenHash, TokenType type, Instant now) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(now, "now");
        UserToken token = new UserToken();
        token.user = Objects.requireNonNull(user, "user");
        token.tokenHash = requireSha256Hex(tokenHash);
        token.tokenType = type;
        token.createdAt = now;
        token.expiresAt = now.plus(type.ttl());
        return token;
    }

    /** Not used yet and not expired at {@code now} (expiry is exclusive, like T7's {@code expires_at > now}). */
    public boolean isUsable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    /** Single use (BR-34): only a usable token can be used; a used or expired one is rejected. */
    public void markUsed(Instant now) {
        if (!isUsable(now)) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        usedAt = now;
    }

    /** ck_user_tokens_hash_hex / ck_refresh_tokens_hash_hex: 64 lower-case hex characters. */
    static String requireSha256Hex(String hash) {
        if (hash == null || !SHA256_HEX.matcher(hash).matches()) {
            throw new IllegalArgumentException("token hash must be 64 lower-case hex characters");
        }
        return hash;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || User.effectiveClass(this) != User.effectiveClass(o)) {
            return false;
        }
        Long id = getId();
        return id != null && id.equals(((UserToken) o).getId());
    }

    @Override
    public final int hashCode() {
        return User.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "UserToken[id=" + getId() + ", type=" + tokenType + "]";
    }
}
