package com.edumy.shared.config;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.cache.autoconfigure.CacheManagerCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Cache + Caffeine with one spec per cache (docs/ai/05 §5.3). Cache only immutable values (Boolean, records),
 * never entities or {@code Optional}; null values are not cached. Evictions that follow a data change run after
 * commit. The catalog caches are added in P08.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig {

    /** userId → Boolean "status = active" (JWT check on every request, ADR-002). Evicted on lock/unlock. */
    public static final String USER_STATUS = "identity.user-status";
    /** sid → Boolean "family still has a non-revoked refresh token" (ADR-002). Evicted on any revoke. */
    public static final String SESSION_STATUS = "identity.session-status";
    /** userId → active role codes (BR-26). Evicted on grant/revoke role, self-registration as instructor. */
    public static final String ACTIVE_ROLES = "identity.active-roles";

    private static final long MAX_ENTRIES = 10_000;

    @Bean
    CacheManagerCustomizer<CaffeineCacheManager> edumyCacheSpecs() {
        return manager -> {
            manager.setAllowNullValues(false);
            manager.registerCustomCache(USER_STATUS, spec(Duration.ofSeconds(30)));
            manager.registerCustomCache(SESSION_STATUS, spec(Duration.ofSeconds(30)));
            manager.registerCustomCache(ACTIVE_ROLES, spec(Duration.ofSeconds(60)));
        };
    }

    private static Cache<Object, Object> spec(Duration ttl) {
        return Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(MAX_ENTRIES)
                .recordStats()
                .build();
    }
}
