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
 * commit. Never cache carts, orders, payments, progress or Q&A (staleness there affects money or access).
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

    /** {@code 'active'} → the active categories (UC-G03). Evicted on every category change. */
    public static final String CATALOG_CATEGORIES = "catalog.categories";
    /** slug → viewer-independent course page of a published course (UC-G04). Evicted per slug on content/moderation/review events. */
    public static final String CATALOG_COURSE_DETAIL = "catalog.course-detail";
    /** (q-normalized, categoryId, price, sort, page, size) → one page of the course list (UC-G01–G03). TTL only. */
    public static final String CATALOG_COURSE_LIST = "catalog.course-list";
    /** courseId → average rating + number of visible reviews. Evicted on every review change. */
    public static final String CATALOG_RATING = "catalog.rating";

    private static final long MAX_ENTRIES = 10_000;

    @Bean
    CacheManagerCustomizer<CaffeineCacheManager> edumyCacheSpecs() {
        return manager -> {
            manager.setAllowNullValues(false);
            manager.registerCustomCache(USER_STATUS, spec(Duration.ofSeconds(30)));
            manager.registerCustomCache(SESSION_STATUS, spec(Duration.ofSeconds(30)));
            manager.registerCustomCache(ACTIVE_ROLES, spec(Duration.ofSeconds(60)));
            manager.registerCustomCache(CATALOG_CATEGORIES, spec(Duration.ofHours(1), 10));
            manager.registerCustomCache(CATALOG_COURSE_DETAIL, spec(Duration.ofMinutes(10), 5_000));
            manager.registerCustomCache(CATALOG_COURSE_LIST, spec(Duration.ofSeconds(60), 2_000));
            manager.registerCustomCache(CATALOG_RATING, spec(Duration.ofMinutes(10), 10_000));
        };
    }

    private static Cache<Object, Object> spec(Duration ttl) {
        return spec(ttl, MAX_ENTRIES);
    }

    private static Cache<Object, Object> spec(Duration ttl, long maximumSize) {
        return Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maximumSize)
                .recordStats()
                .build();
    }
}
