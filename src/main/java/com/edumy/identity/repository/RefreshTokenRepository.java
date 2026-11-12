package com.edumy.identity.repository;

import java.time.Instant;
import java.util.Optional;

import com.edumy.identity.entity.RefreshToken;
import com.edumy.identity.entity.RevokeReason;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Session flows of docs/ai/04 §4.1. Bulk revocations set {@code revoked_at} and {@code revoke_reason} together
 * (ck_refresh_tokens_revoked_pair), skip already revoked rows (their first reason is kept) and return the number of
 * rows revoked. They flush pending changes first and clear the persistence context afterwards: entities loaded before
 * the call are detached, re-load them if needed.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** {@code /auth/refresh}: row lock so two concurrent uses of one token cannot both rotate it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findForUpdateByTokenHash(String tokenHash);

    /** Session validity of an access token: its {@code sid} family still has a non-revoked token. */
    boolean existsByFamilyIdAndRevokedAtIsNull(String familyId);

    /** Logout (UC-G10) and reuse detection: revoke the whole family. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken r set r.revokedAt = :now, r.revokeReason = :reason
            where r.familyId = :familyId and r.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") String familyId, @Param("reason") RevokeReason reason,
                     @Param("now") Instant now);

    /** Reset password (UC-G08) and lock (UC-A02, D-05): revoke every family of the user. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken r set r.revokedAt = :now, r.revokeReason = :reason
            where r.user.id = :userId and r.revokedAt is null
            """)
    int revokeAllOfUser(@Param("userId") Long userId, @Param("reason") RevokeReason reason,
                        @Param("now") Instant now);

    /** Change password (UC-G12): revoke every family of the user except the current session. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken r set r.revokedAt = :now, r.revokeReason = :reason
            where r.user.id = :userId and r.familyId <> :keepFamilyId and r.revokedAt is null
            """)
    int revokeAllOfUserExceptFamily(@Param("userId") Long userId, @Param("keepFamilyId") String keepFamilyId,
                                    @Param("reason") RevokeReason reason, @Param("now") Instant now);
}
