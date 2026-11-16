package com.edumy.identity.repository;

import java.time.Instant;
import java.util.Optional;

import com.edumy.identity.entity.TokenType;
import com.edumy.identity.entity.UserToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Bulk updates flush pending changes first and clear the persistence context afterwards: entities loaded before the
 * call are detached, re-load them if needed.
 */
public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    /**
     * T7 consume (BR-34, docs/ai/01 §1.11): one atomic UPDATE; returns 1 when the code was unused and not expired at
     * {@code now} (success), 0 otherwise (unknown, wrong type, already used or expired — never say which).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update UserToken t set t.usedAt = :now
            where t.tokenHash = :hash and t.tokenType = :type and t.usedAt is null and t.expiresAt > :now
            """)
    int consume(@Param("hash") String tokenHash, @Param("type") TokenType type, @Param("now") Instant now);

    /**
     * P13: plain read — an unused, unexpired code of {@code type} with this hash exists. Lets the reset reject a wrong code
     * before spending a BCrypt hash; {@link #consume} still decides atomically inside the transaction.
     */
    @Query("""
            select case when count(t) > 0 then true else false end from UserToken t
            where t.tokenHash = :hash and t.tokenType = :type and t.usedAt is null and t.expiresAt > :now
            """)
    boolean isUsable(@Param("hash") String tokenHash, @Param("type") TokenType type, @Param("now") Instant now);

    /** Owner of a code, read after a successful {@link #consume}. */
    @Query("select t.user.id from UserToken t where t.tokenHash = :hash and t.tokenType = :type")
    Optional<Long> findUserIdByTokenHashAndType(@Param("hash") String tokenHash, @Param("type") TokenType type);

    /** BR-34 "new token voids old": marks every unused code of the user + type as used before issuing a new one. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update UserToken t set t.usedAt = :now
            where t.user.id = :userId and t.tokenType = :type and t.usedAt is null
            """)
    int invalidateUnused(@Param("userId") Long userId, @Param("type") TokenType type, @Param("now") Instant now);

    /**
     * Cleanup timer (SRS §6 "chỉ được xóa vật lý … mã xác thực đã hết hạn quá 30 ngày", P11-e): deletes at most
     * {@code limit} codes that expired before {@code cutoff}; returns the number deleted (call again until it is below
     * {@code limit}). Idempotent, so two instances running it at once only delete each row once.
     */
    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM user_tokens WHERE expires_at < :cutoff LIMIT :limit")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** Creation time of the latest code of the user + type: resend cooldown 60 s (BR-34, D-03, docs/ai/04 §4.4). */
    @Query("select max(t.createdAt) from UserToken t where t.user.id = :userId and t.tokenType = :type")
    Optional<Instant> findLatestCreatedAt(@Param("userId") Long userId, @Param("type") TokenType type);
}
