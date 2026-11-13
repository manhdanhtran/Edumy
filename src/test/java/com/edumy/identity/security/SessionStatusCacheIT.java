package com.edumy.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.edumy.shared.config.CacheConfig;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-through and after-commit eviction of the two session caches (ADR-002, docs/ai/05 §5.3). */
@MySqlSpringBootTest
class SessionStatusCacheIT {

    @Autowired
    SessionStatusCache sessionStatus;
    @Autowired
    CacheManager cacheManager;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    TestUsers users;

    @AfterEach
    void cleanUp() {
        users.deleteAll();
    }

    @Test
    void isSessionActive_readThrough_cachedUntilEvicted() {
        Session session = tokens.newUserSession(RoleCode.STUDENT);
        assertThat(sessionStatus.isSessionActive(session.sid())).isTrue();
        assertThat(cache(CacheConfig.SESSION_STATUS).get(session.sid(), Boolean.class)).isTrue();

        tokens.revokeInDatabase(session.sid());
        assertThat(sessionStatus.isSessionActive(session.sid())).as("cached for up to 30 s").isTrue();

        sessionStatus.evictSession(session.sid());
        assertThat(sessionStatus.isSessionActive(session.sid())).isFalse();
    }

    @Test
    void isSessionActive_unknownSid_false() {
        assertThat(sessionStatus.isSessionActive(UUID.randomUUID().toString())).isFalse();
    }

    @Test
    void isUserActive_activeLockedUnknown() {
        Long userId = users.create(RoleCode.STUDENT).id();
        assertThat(sessionStatus.isUserActive(userId)).isTrue();
        assertThat(cache(CacheConfig.USER_STATUS).get(userId, Boolean.class)).isTrue();

        users.lock(userId);
        sessionStatus.evictUser(userId);
        assertThat(sessionStatus.isUserActive(userId)).isFalse();

        assertThat(sessionStatus.isUserActive(Long.MAX_VALUE)).isFalse();
    }

    @Test
    void evictSession_insideTransaction_appliedOnlyAfterCommit() {
        Session session = tokens.newUserSession(RoleCode.STUDENT);
        assertThat(sessionStatus.isSessionActive(session.sid())).isTrue();

        transactions.executeWithoutResult(tx -> {
            tokens.revokeInDatabase(session.sid());
            sessionStatus.evictSession(session.sid());
            assertThat(cache(CacheConfig.SESSION_STATUS).get(session.sid())).as("not evicted before commit")
                    .isNotNull();
        });

        assertThat(cache(CacheConfig.SESSION_STATUS).get(session.sid())).as("evicted after commit").isNull();
        assertThat(sessionStatus.isSessionActive(session.sid())).isFalse();
    }

    @Test
    void evictUser_transactionRolledBack_notEvicted() {
        Long userId = users.create(RoleCode.STUDENT).id();
        assertThat(sessionStatus.isUserActive(userId)).isTrue();

        transactions.executeWithoutResult(tx -> {
            users.lock(userId);
            sessionStatus.evictUser(userId);
            tx.setRollbackOnly();
        });

        assertThat(cache(CacheConfig.USER_STATUS).get(userId, Boolean.class)).isTrue();
        sessionStatus.evictUser(userId);
        assertThat(sessionStatus.isUserActive(userId)).as("lock was rolled back").isTrue();
    }

    @Test
    void caches_registeredWithTheirSpecs() {
        for (String name : new String[] {CacheConfig.USER_STATUS, CacheConfig.SESSION_STATUS,
                CacheConfig.ACTIVE_ROLES}) {
            Object nativeCache = cache(name).getNativeCache();
            assertThat(nativeCache).isInstanceOf(com.github.benmanes.caffeine.cache.Cache.class);
            var caffeine = (com.github.benmanes.caffeine.cache.Cache<?, ?>) nativeCache;
            assertThat(caffeine.policy().isRecordingStats()).as("recordStats of %s", name).isTrue();
            long ttlSeconds = caffeine.policy().expireAfterWrite().orElseThrow().getExpiresAfter().toSeconds();
            assertThat(ttlSeconds).isEqualTo(name.equals(CacheConfig.ACTIVE_ROLES) ? 60 : 30);
        }
    }

    private Cache cache(String name) {
        return cacheManager.getCache(name);
    }
}
