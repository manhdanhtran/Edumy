package com.edumy.identity.entity;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.jpa.JpaEntities;
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
 * {@code refresh_tokens} (ADR-002, docs/ai/04 §4.1). One family per login session ({@code family_id} = JWT {@code sid});
 * rotated on every use. Only the SHA-256 hex of the opaque token is stored.
 * <p>{@code revoked_at} and {@code revoke_reason} are always set together (ck_refresh_tokens_revoked_pair). Revoking an
 * already revoked token is <b>rejected</b>: the first reason is evidence (a {@code rotated} token presented again means
 * reuse) and must never be overwritten. Bulk revocations use {@code RefreshTokenRepository}, which skips revoked rows.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    static final int USER_AGENT_MAX = 255;
    static final int IP_ADDRESS_MAX = 45;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "family_id", nullable = false, length = 36, updatable = false)
    private String familyId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 30)
    private RevokeReason revokeReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "user_agent", length = USER_AGENT_MAX, updatable = false)
    private String userAgent;

    @Column(name = "ip_address", length = IP_ADDRESS_MAX, updatable = false)
    private String ipAddress;

    /**
     * New refresh token of session {@code familyId} (a fresh UUID at login, the same family on rotation), valid for
     * {@code ttl} (edumy.security.jwt.refresh-ttl). {@code userAgent}/{@code ipAddress} are informational and cut to
     * the column length.
     */
    public static RefreshToken issue(User user, String familyId, String tokenHash, Duration ttl,
                                     String userAgent, String ipAddress, Instant now) {
        Objects.requireNonNull(now, "now");
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        RefreshToken token = new RefreshToken();
        token.user = Objects.requireNonNull(user, "user");
        token.familyId = requireUuid(familyId);
        token.tokenHash = UserToken.requireSha256Hex(tokenHash);
        token.createdAt = now;
        token.expiresAt = now.plus(ttl);
        token.userAgent = truncate(userAgent, USER_AGENT_MAX);
        token.ipAddress = truncate(ipAddress, IP_ADDRESS_MAX);
        return token;
    }

    /** Not revoked and not expired at {@code now}. */
    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Sets {@code revoked_at} and {@code revoke_reason} together; a second revoke is rejected (see class doc). */
    public void revoke(RevokeReason reason, Instant now) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(now, "now");
        if (revokedAt != null) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        revokedAt = now;
        revokeReason = reason;
    }

    /** Canonical lower-case 36-character UUID (= JWT sid). */
    private static String requireUuid(String familyId) {
        Objects.requireNonNull(familyId, "familyId");
        if (familyId.length() != 36 || !UUID.fromString(familyId).toString().equals(familyId)) {
            throw new IllegalArgumentException("familyId must be a canonical UUID");
        }
        return familyId;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || JpaEntities.effectiveClass(this) != JpaEntities.effectiveClass(o)) {
            return false;
        }
        Long id = getId();
        return id != null && id.equals(((RefreshToken) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "RefreshToken[id=" + getId() + ", familyId=" + familyId + ", revokeReason=" + revokeReason + "]";
    }
}
