package com.edumy.catalog.service;

import com.edumy.shared.config.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

/**
 * Evictions of the catalog caches (docs/ai/05 §5.3), called after commit by
 * {@code listener.CatalogCacheEvictionListener}. Annotation-driven, so it must be called from another bean (the cache
 * proxy does not apply to a self-invocation). {@code catalog.course-list} expires after 60 s; it is emptied at once
 * only when a course enters or leaves the public catalog (approve, hide, unhide — rare admin events; P10 Q-a: UC-A12
 * step 3 / AC-28 "khóa biến khỏi tìm kiếm"). Content edits of a listed course stay TTL-only (accepted staleness).
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

    /**
     * A course entered or left the public catalog (UC-A10 approve, UC-A12 hide, UC-A16 unhide): its page and every
     * cached list page (the course may be on any of them).
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.CATALOG_COURSE_DETAIL, key = "#slug"),
            @CacheEvict(cacheNames = CacheConfig.CATALOG_COURSE_LIST, allEntries = true)})
    public void evictPublication(String slug) {
        // the annotations do the work
    }

    /** The visible reviews of the course changed: its rating and the page that shows the rating. */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.CATALOG_RATING, key = "#courseId"),
            @CacheEvict(cacheNames = CacheConfig.CATALOG_COURSE_DETAIL, key = "#slug")})
    public void evictRating(Long courseId, String slug) {
        // the annotations do the work
    }
}
