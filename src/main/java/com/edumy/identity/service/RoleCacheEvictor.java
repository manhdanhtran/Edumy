package com.edumy.identity.service;

import java.util.Objects;

import com.edumy.shared.config.CacheConfig;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Evicts the {@value CacheConfig#ACTIVE_ROLES} entry of a user (key = user id, as read by {@link RoleQueryApiImpl}).
 * Must be called by every change of a user's roles: self-registration as instructor (UC-G13), admin grant / revoke
 * (UC-A04 / UC-A05). Inside a transaction the eviction runs after commit: evicting earlier would let a concurrent
 * request re-cache the old, still committed roles for up to 60 s; after a rollback nothing changed, so nothing is
 * evicted. Without a transaction the eviction is immediate.
 */
@Component
public class RoleCacheEvictor {

    private final Cache activeRoles;

    public RoleCacheEvictor(CacheManager cacheManager) {
        this.activeRoles = RoleQueryApiImpl.requireCache(cacheManager);
    }

    /** Forget the cached roles of {@code userId} (after commit when a transaction is active). */
    public void evict(Long userId) {
        Objects.requireNonNull(userId, "userId");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    activeRoles.evict(userId);
                }
            });
        } else {
            activeRoles.evict(userId);
        }
    }
}
