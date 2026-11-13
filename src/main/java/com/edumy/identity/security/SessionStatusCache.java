package com.edumy.identity.security;

import java.util.Objects;

import com.edumy.identity.entity.UserStatus;
import com.edumy.identity.repository.RefreshTokenRepository;
import com.edumy.identity.repository.UserRepository;
import com.edumy.shared.config.CacheConfig;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Answers the two questions asked for every access token (ADR-002, docs/ai/04 §4.1): does session {@code sid} still
 * have a non-revoked refresh token, and is the user {@code active}? Both are read through 30 s Caffeine caches
 * ({@value CacheConfig#SESSION_STATUS}, {@value CacheConfig#USER_STATUS}) holding {@code Boolean} values.
 * <p>Uses the {@link Cache} API directly (no {@code @Cacheable}), so there is no proxy self-invocation trap.
 * <p>{@link #evictSession} / {@link #evictUser} must be called by every revoke / lock / unlock. Inside a transaction the
 * eviction is deferred to after commit: evicting earlier would let a concurrent request re-cache the old, still
 * committed "active" answer for up to 30 s; after a rollback nothing changed, so nothing is evicted. Without a
 * transaction the eviction is immediate.
 */
@Component
public class SessionStatusCache {

    private final Cache sessions;
    private final Cache users;
    private final RefreshTokenRepository refreshTokens;
    private final UserRepository userRepository;

    public SessionStatusCache(CacheManager cacheManager, RefreshTokenRepository refreshTokens,
                              UserRepository userRepository) {
        this.sessions = requireCache(cacheManager, CacheConfig.SESSION_STATUS);
        this.users = requireCache(cacheManager, CacheConfig.USER_STATUS);
        this.refreshTokens = refreshTokens;
        this.userRepository = userRepository;
    }

    /** Session {@code sid} (= {@code refresh_tokens.family_id}) still has a non-revoked token. */
    public boolean isSessionActive(String sid) {
        Objects.requireNonNull(sid, "sid");
        return Boolean.TRUE.equals(sessions.get(sid, () -> refreshTokens.existsByFamilyIdAndRevokedAtIsNull(sid)));
    }

    /** The user exists and its status is {@code active}. */
    public boolean isUserActive(Long userId) {
        Objects.requireNonNull(userId, "userId");
        return Boolean.TRUE.equals(users.get(userId, () -> userRepository.findStatusById(userId)
                .map(status -> status == UserStatus.ACTIVE)
                .orElse(Boolean.FALSE)));
    }

    /** After any revoke of session {@code sid} (logout, rotation reuse, password change/reset, lock). */
    public void evictSession(String sid) {
        Objects.requireNonNull(sid, "sid");
        afterCommit(() -> sessions.evict(sid));
    }

    /** After lock / unlock of the user. */
    public void evictUser(Long userId) {
        Objects.requireNonNull(userId, "userId");
        afterCommit(() -> users.evict(userId));
    }

    private static void afterCommit(Runnable eviction) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eviction.run();
                }
            });
        } else {
            eviction.run();
        }
    }

    private static Cache requireCache(CacheManager cacheManager, String name) {
        Cache cache = cacheManager.getCache(name);
        if (cache == null) {
            throw new IllegalStateException("Cache " + name + " is not configured (shared.config.CacheConfig)");
        }
        return cache;
    }
}
