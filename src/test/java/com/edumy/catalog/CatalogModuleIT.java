package com.edumy.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Objects;

import com.edumy.authoring.api.CategoryChanged;
import com.edumy.authoring.api.CourseApproved;
import com.edumy.authoring.api.CourseContentChanged;
import com.edumy.authoring.api.CourseHidden;
import com.edumy.authoring.api.CourseRejected;
import com.edumy.authoring.api.CourseUnhidden;
import com.edumy.catalog.dto.response.CategoryResponse;
import com.edumy.catalog.dto.response.CourseDetailResponse;
import com.edumy.catalog.service.CachedCourseQueryService;
import com.edumy.catalog.service.CategoryCatalogQueryService;
import com.edumy.catalog.service.CourseCatalogQueryService;
import com.edumy.shared.error.BusinessException;
import com.edumy.learning.api.ReviewChanged;
import com.edumy.shared.config.CacheConfig;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.MySqlModuleTest;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Module test of {@code catalog}: the listener contract of {@code CatalogCacheEvictionListener} (docs/ai/01 §1.10,
 * docs/ai/05 §5.3) driven by events published through {@link Scenario} — which key is evicted by which event, nothing
 * before commit or after a rollback, {@code CourseRejected} evicts nothing — and the read services on the real
 * database. Catalog has no other module's bean (ADR-003: reads are SQL), so nothing is mocked. The HTTP-level cache
 * behaviour (hit counters, statements, endpoints) stays in {@code CatalogCacheIT}.
 */
@ApplicationModuleTest(extraIncludes = "shared")
@MySqlModuleTest
class CatalogModuleIT {

    static final String OTHER_SLUG = "khoa-hoc-khac";

    @Autowired
    CacheManager caches;
    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    CategoryCatalogQueryService categories;
    @Autowired
    CachedCourseQueryService courses;
    @Autowired
    CourseCatalogQueryService detailService;
    @Autowired
    TestUsers users;
    @Autowired
    CourseFixtures fixtures;
    @Autowired
    JdbcClient jdbc;

    long course;
    String slug;

    @BeforeEach
    void setUp() {
        for (String name : new String[] {CacheConfig.CATALOG_CATEGORIES, CacheConfig.CATALOG_COURSE_DETAIL,
                CacheConfig.CATALOG_RATING}) {
            cache(name).clear();
        }
        TestUser instructor = users.create(RoleCode.INSTRUCTOR);
        course = fixtures.draftCourse(instructor.id());
        fixtures.lesson(fixtures.chapter(course));
        fixtures.setStatus(course, "published", false);
        slug = jdbc.sql("SELECT slug FROM courses WHERE id = ?").param(course).query(String.class).single();
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    // ------------------------------------------------------------------------------------------ listener contract

    @Test
    void courseEvents_evictThePageOfTheirSlugOnly_P08(Scenario scenario) {
        Object[] events = {new CourseApproved(course, slug), new CourseContentChanged(course, slug),
                new CourseHidden(course, slug, "Lý do"), new CourseUnhidden(course, slug)};
        for (Object event : events) {
            putDetails();

            scenario.publish(event)
                    .andWaitForStateChange(() -> cache(CacheConfig.CATALOG_COURSE_DETAIL).get(slug) == null, Boolean::booleanValue)
                    .andVerify(gone -> assertThat(cache(CacheConfig.CATALOG_COURSE_DETAIL).get(OTHER_SLUG))
                            .as("%s must not evict another course", event.getClass().getSimpleName())
                            .isNotNull());
        }
    }

    @Test
    void reviewChanged_evictsRatingOfTheCourseAndItsPage_P08(Scenario scenario) {
        putDetails();
        cache(CacheConfig.CATALOG_RATING).put(course, "rating");
        cache(CacheConfig.CATALOG_RATING).put(course + 1_000_000, "rating of another course");

        scenario.publish(new ReviewChanged(course, slug))
                .andWaitForStateChange(() -> cache(CacheConfig.CATALOG_RATING).get(course) == null, Boolean::booleanValue)
                .andVerify(gone -> {
                    assertThat(cache(CacheConfig.CATALOG_COURSE_DETAIL).get(slug)).isNull();
                    assertThat(cache(CacheConfig.CATALOG_COURSE_DETAIL).get(OTHER_SLUG)).isNotNull();
                    assertThat(cache(CacheConfig.CATALOG_RATING).get(course + 1_000_000)).isNotNull();
                });
    }

    @Test
    void categoryChanged_evictsTheCategoryList_P08(Scenario scenario) {
        cache(CacheConfig.CATALOG_CATEGORIES).put("active", "cached list");
        putDetails();

        scenario.publish(new CategoryChanged(1L))
                .andWaitForStateChange(() -> cache(CacheConfig.CATALOG_CATEGORIES).get("active") == null, Boolean::booleanValue)
                .andVerify(gone -> assertThat(cache(CacheConfig.CATALOG_COURSE_DETAIL).get(slug)).isNotNull());
    }

    @Test
    void courseRejected_evictsNothing_neverPublishedSoNeverCached_P08(Scenario scenario) {
        putDetails();
        cache(CacheConfig.CATALOG_CATEGORIES).put("active", "cached list");

        scenario.publish(new CourseRejected(course, slug, "Thiếu nội dung"))
                .andWaitForStateChange(() -> Boolean.TRUE)
                .andVerify(ok -> {
                    assertThat(cache(CacheConfig.CATALOG_COURSE_DETAIL).get(slug)).isNotNull();
                    assertThat(cache(CacheConfig.CATALOG_CATEGORIES).get("active")).isNotNull();
                });
    }

    @Test
    void eviction_runsAfterCommitOnly_notBeforeCommitNorAfterRollback_P08() {
        putDetails();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            publisher.publishEvent(new CourseHidden(course, slug, "Lý do"));
            assertThat(cache(CacheConfig.CATALOG_COURSE_DETAIL).get(slug)).as("before commit").isNotNull();
        });
        assertThat(cache(CacheConfig.CATALOG_COURSE_DETAIL).get(slug)).as("after commit").isNull();

        putDetails();
        tx.executeWithoutResult(status -> {
            publisher.publishEvent(new CourseHidden(course, slug, "Lý do"));
            status.setRollbackOnly();
        });
        assertThat(cache(CacheConfig.CATALOG_COURSE_DETAIL).get(slug)).as("after rollback").isNotNull();
    }

    // ------------------------------------------------------------------------------------------ read services

    @Test
    void publishedDetail_realRow_cachedUntilTheCourseEventEvictsIt_P08(Scenario scenario) {
        CourseDetailResponse detail = courses.publishedDetail(slug);

        assertThat(detail).isNotNull();
        assertThat(detail.id()).isEqualTo(course);
        assertThat(cache(CacheConfig.CATALOG_COURSE_DETAIL).get(slug)).isNotNull();

        scenario.publish(new CourseHidden(course, slug, "Lý do"))
                .andWaitForStateChange(() -> cache(CacheConfig.CATALOG_COURSE_DETAIL).get(slug) == null, Boolean::booleanValue)
                .andVerify(gone -> assertThat(courses.publishedDetail(slug)).isNotNull()); // reloaded from MySQL
    }

    @Test
    void detail_hiddenOrUnknownSlug_404ForAnonymous_neverCached_BR31() {
        fixtures.setStatus(course, "hidden", true);

        assertThat(courses.publishedDetail(slug).status()).isEqualTo("hidden"); // the page itself is not cached
        assertThat(cache(CacheConfig.CATALOG_COURSE_DETAIL).get(slug)).isNull();
        assertThat(courses.publishedDetail("khong-ton-tai-" + course)).isNull();
        assertThatThrownBy(() -> detailService.detail(slug, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> detailService.detail("khong-ton-tai-" + course, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void activeCategories_onlyActiveOnes_cachedUnderActive_UCG03() {
        long active = fixtures.category(true);
        long inactive = fixtures.category(false);

        assertThat(categories.activeCategories()).extracting(CategoryResponse::id)
                .contains(active).doesNotContain(inactive);
        assertThat(cache(CacheConfig.CATALOG_CATEGORIES).get("active")).isNotNull();
    }

    private void putDetails() {
        cache(CacheConfig.CATALOG_COURSE_DETAIL).put(slug, "page of " + slug);
        cache(CacheConfig.CATALOG_COURSE_DETAIL).put(OTHER_SLUG, "page of " + OTHER_SLUG);
    }

    private Cache cache(String name) {
        return Objects.requireNonNull(caches.getCache(name), name);
    }
}
