package com.edumy.catalog.service;

import com.edumy.shared.config.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

/**
 * Evictions of the catalog caches (docs/ai/05 §5.3), called after commit by
 * {@code listener.CatalogCacheEvictionListener}. Annotation-driven, so it must be called from another bean (the cache
 * proxy does not apply to a self-invocation). {@code catalog.course-list} has no eviction on purpose: it expires after
 * 60 s (accepted staleness).
 */
@Service
public class CatalogCacheEvictor {

    /** Category created / renamed / (de)activated / deleted. */
    @CacheEvict(cacheNames = CacheConfig.CATALOG_CATEGORIES, allEntries = true)
    public void evictCategories() {
        // the annotation does the work
    }

    /** Content, publication state of the course page {@code slug} changed. */
    @CacheEvict(cacheNames = CacheConfig.CATALOG_COURSE_DETAIL, key = "#slug")
    public void evictCourseDetail(String slug) {
        // the annotation does the work
    }

    /** The visible reviews of the course changed: its rating and the page that shows the rating. */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.CATALOG_RATING, key = "#courseId"),
            @CacheEvict(cacheNames = CacheConfig.CATALOG_COURSE_DETAIL, key = "#slug")})
    public void evictRating(Long courseId, String slug) {
        // the annotations do the work
    }
}
