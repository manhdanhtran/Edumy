package com.edumy.support;

import java.util.List;

import com.edumy.shared.config.CacheConfig;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Test support for the four {@code catalog.*} caches (docs/ai/05 §5.3, P08). As a JUnit extension (part of
 * {@link MySqlSpringBootTest} and {@link MySqlSeedSpringBootTest}) it empties them <b>before every test</b>, so a test
 * never sees what an earlier test cached (the context and its Caffeine caches are shared by the whole suite; the
 * {@code course-list} cache even has no eviction, only a 60 s TTL). A test that writes through the API and then asserts a
 * fresh list calls {@link #clear(CacheManager)} after the write; a test that proves an eviction listener must
 * <b>not</b> call it between the warm-up and the check.
 */
public class CatalogCaches implements BeforeEachCallback {

    public static final List<String> NAMES = List.of(CacheConfig.CATALOG_CATEGORIES, CacheConfig.CATALOG_COURSE_DETAIL,
            CacheConfig.CATALOG_COURSE_LIST, CacheConfig.CATALOG_RATING);

    /** Empties the four catalog caches (their statistics are kept). */
    public static void clear(CacheManager cacheManager) {
        for (String name : NAMES) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        clear(SpringExtension.getApplicationContext(context).getBean(CacheManager.class));
    }
}
