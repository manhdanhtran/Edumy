package com.edumy.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.edumy.authoring.api.CategoryChanged;
import com.edumy.authoring.api.CourseApproved;
import com.edumy.authoring.api.CourseContentChanged;
import com.edumy.authoring.api.CourseHidden;
import com.edumy.authoring.api.CourseRejected;
import com.edumy.authoring.api.CourseUnhidden;
import com.edumy.learning.api.ReviewChanged;
import com.edumy.shared.config.CacheConfig;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CatalogCaches;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.SqlStatementCounter;
import com.edumy.support.TestUsers;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Catalog caches (docs/ai/05 §5.3, P08) through the real filter chain, MySQL and the real Caffeine caches: second call
 * served from the cache (statement counter + Caffeine hit statistics), eviction after commit for every event (real
 * endpoints), no viewer data in the cache, key design, not-found never cached, the seven documented caches, their
 * TTL and size. {@code CatalogCaches} empties the caches before every test; this class never clears them between a
 * warm-up and the check of an eviction.
 */
@MySqlSpringBootTest
class CatalogCacheIT {

    static final String API = "/api/v1";
    static final String CATALOG = API + "/catalog/courses";
    static final String CATEGORIES = API + "/catalog/categories";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SqlStatementCounter sql;
    @Autowired
    CacheManager cacheManager;
    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    PlatformTransactionManager transactionManager;

    CourseFixtures fixtures;
    Session admin;
    Session instructor;
    Session student;
    long category;
    long course;
    String slug;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        admin = tokens.newUserSession(RoleCode.ADMIN);
        instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        student = tokens.newUserSession(RoleCode.STUDENT);
        category = fixtures.category(true);
        course = fixtures.draftCourse(instructor.userId(), category);
        fixtures.lesson(fixtures.chapter(course));
        fixtures.setStatus(course, "published", false);
        slug = slugOf(course);
        fixtures.enrollment(student.userId(), course);
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    // ------------------------------------------------------------------------------------- served from the cache

    @Test
    void categories_secondCall_noStatement_cacheHit() throws Exception {
        long hits = stats(CacheConfig.CATALOG_CATEGORIES);
        mvc.perform(get(CATEGORIES)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(Math.toIntExact(category))));

        sql.reset();
        mvc.perform(get(CATEGORIES)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(Math.toIntExact(category))));

        assertThat(sql.count()).as(sql.statements().toString()).isZero();
        assertThat(stats(CacheConfig.CATALOG_CATEGORIES)).isEqualTo(hits + 1);
        assertThat(nativeCache(CacheConfig.CATALOG_CATEGORIES).getIfPresent("active")).isNotNull();
    }

    @Test
    void courseList_secondCall_noStatement_cacheHit() throws Exception {
        long hits = stats(CacheConfig.CATALOG_COURSE_LIST);
        mvc.perform(list("categoryId", String.valueOf(category))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].slug").value(slug));

        sql.reset();
        mvc.perform(list("categoryId", String.valueOf(category))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].slug").value(slug));

        assertThat(sql.count()).as(sql.statements().toString()).isZero();
        assertThat(stats(CacheConfig.CATALOG_COURSE_LIST)).isEqualTo(hits + 1);
    }

    @Test
    void courseDetail_anonymousSecondCall_noStatement_ratingCachedToo() throws Exception {
        mvc.perform(detail()).andExpect(status().isOk()).andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.ratingCount").value(0));
        assertThat(nativeCache(CacheConfig.CATALOG_COURSE_DETAIL).getIfPresent(slug)).isNotNull();
        assertThat(nativeCache(CacheConfig.CATALOG_RATING).getIfPresent(course)).isNotNull();
        long detailHits = stats(CacheConfig.CATALOG_COURSE_DETAIL); // after the probes above: getIfPresent counts as a hit

        sql.reset();
        mvc.perform(detail()).andExpect(status().isOk()).andExpect(jsonPath("$.viewer").value(nullValue()));

        assertThat(sql.count()).as(sql.statements().toString()).isZero();
        assertThat(stats(CacheConfig.CATALOG_COURSE_DETAIL)).isEqualTo(detailHits + 1);
    }

    // ---------------------------------------------------------------------------------------- viewer never cached

    @Test
    void courseDetail_viewerContextNeverCached_twoViewersAndAnonymousOnTheSameSlug() throws Exception {
        Session other = tokens.newUserSession(RoleCode.STUDENT);

        // the enrolled student warms the cache first
        mvc.perform(detail(student)).andExpect(status().isOk())
                .andExpect(jsonPath("$.viewer.owned").value(true))
                .andExpect(jsonPath("$.viewer.isInstructor").value(false));
        // a student without enrollment, the main instructor and an anonymous caller each get their own context
        mvc.perform(detail(other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.viewer.owned").value(false))
                .andExpect(jsonPath("$.viewer.inCart").value(false));
        mvc.perform(detail(instructor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.viewer.isInstructor").value(true))
                .andExpect(jsonPath("$.viewer.owned").value(false));
        mvc.perform(detail()).andExpect(status().isOk()).andExpect(jsonPath("$.viewer").value(nullValue()));
        mvc.perform(detail(student)).andExpect(status().isOk()).andExpect(jsonPath("$.viewer.owned").value(true));

        // in the cart now (a write the cache knows nothing about): the viewer part is read live every time
        jdbc.sql("INSERT INTO carts (student_id) VALUES (?)").param(other.userId()).update();
        long cart = jdbc.sql("SELECT id FROM carts WHERE student_id = ?").param(other.userId())
                .query(Long.class).single();
        jdbc.sql("INSERT INTO cart_items (cart_id, course_id) VALUES (?, ?)").params(cart, course).update();
        mvc.perform(detail(other)).andExpect(status().isOk()).andExpect(jsonPath("$.viewer.inCart").value(true));
        jdbc.sql("DELETE FROM cart_items WHERE cart_id = ?").param(cart).update();
        jdbc.sql("DELETE FROM carts WHERE id = ?").param(cart).update();

        // the cached value itself carries no viewer
        Object cached = nativeCache(CacheConfig.CATALOG_COURSE_DETAIL).getIfPresent(slug);
        assertThat(cached.toString()).endsWith("viewer=null]");
    }

    @Test
    void courseDetail_viewerStatementsStillRun_thePageIsServedFromTheCache() throws Exception {
        mvc.perform(detail(student)).andExpect(status().isOk()); // warms the page and the session caches

        sql.reset();
        mvc.perform(detail(student)).andExpect(status().isOk()).andExpect(jsonPath("$.viewer.owned").value(true));

        assertThat(sql.statements()).as("only the viewer relation").hasSize(1);
    }

    // --------------------------------------------------------------------------------------- not found, hidden

    @Test
    void courseDetail_notFoundIsNeverCached_publishedLaterShowsAtOnce() throws Exception {
        fixtures.setStatus(course, "draft", false);
        mvc.perform(detail()).andExpect(status().isNotFound());
        assertThat(nativeCache(CacheConfig.CATALOG_COURSE_DETAIL).getIfPresent(slug)).isNull();

        fixtures.setStatus(course, "published", false); // no event: only "not cached" can explain the fresh read

        mvc.perform(detail()).andExpect(status().isOk()).andExpect(jsonPath("$.slug").value(slug));
    }

    @Test
    void courseDetail_hiddenPageIsNeverCached_onlyPublishedData() throws Exception {
        fixtures.setStatus(course, "hidden", true);

        mvc.perform(detail(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("hidden"));
        mvc.perform(detail()).andExpect(status().isNotFound());

        assertThat(nativeCache(CacheConfig.CATALOG_COURSE_DETAIL).estimatedSize()).isZero();
    }

    // ------------------------------------------------------------------------------------------------ list key

    @Test
    void courseList_keyIsTheNormalizedQueryAndEveryParameter() throws Exception {
        Cache<Object, Object> list = nativeCache(CacheConfig.CATALOG_COURSE_LIST);
        String unique = slug.substring("khoa-hoc-".length(), "khoa-hoc-".length() + 8);

        mvc.perform(list("q", "Khóa học " + unique)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].slug").value(slug));
        assertThat(list.estimatedSize()).isEqualTo(1);

        // accent / case variants of q normalize to the same key: a hit, no statement
        long hits = stats(CacheConfig.CATALOG_COURSE_LIST);
        sql.reset();
        mvc.perform(list("q", "  KHOA HOC " + unique + " ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].slug").value(slug));
        assertThat(sql.count()).as(sql.statements().toString()).isZero();
        assertThat(stats(CacheConfig.CATALOG_COURSE_LIST)).isEqualTo(hits + 1);
        assertThat(list.estimatedSize()).isEqualTo(1);

        // any other parameter is another entry
        mvc.perform(list("q", "Khóa học " + unique, "sort", "rating")).andExpect(status().isOk());
        mvc.perform(list("q", "Khóa học " + unique, "size", "5")).andExpect(status().isOk());
        mvc.perform(list("q", "Khóa học " + unique, "page", "1")).andExpect(status().isOk());
        mvc.perform(list("q", "Khóa học " + unique, "price", "free")).andExpect(status().isOk());
        mvc.perform(list("q", "Khóa học " + unique, "categoryId", String.valueOf(category))).andExpect(status().isOk());
        mvc.perform(list("sort", "rating")).andExpect(status().isOk()); // no keyword at all
        assertThat(list.estimatedSize()).isEqualTo(7);

        // the same parameters again: all hits
        long before = stats(CacheConfig.CATALOG_COURSE_LIST);
        sql.reset();
        mvc.perform(list("q", "Khóa học " + unique, "sort", "rating")).andExpect(status().isOk());
        mvc.perform(list("sort", "rating")).andExpect(status().isOk());
        assertThat(sql.count()).isZero();
        assertThat(stats(CacheConfig.CATALOG_COURSE_LIST)).isEqualTo(before + 2);
        assertThat(list.estimatedSize()).isEqualTo(7);
    }

    /**
     * P10 Q-a (UC-A12 step 3, AC-28, D-08): hiding a course takes it out of the list and the search at once — every
     * cached list page is emptied on hide / unhide / approve; unhide brings it back at once.
     */
    @Test
    void courseList_hideAndUnhide_listAndSearchFreshAtOnce_Qa_AC28() throws Exception {
        String keyword = jdbc.sql("SELECT title FROM courses WHERE id = ?").param(course).query(String.class).single();
        mvc.perform(list("categoryId", String.valueOf(category))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].slug").value(slug));
        mvc.perform(list("q", keyword)).andExpect(jsonPath("$.content[*].slug", hasItem(slug)));

        mvc.perform(admin(post(API + "/admin/courses/" + course + "/hide"), "{\"reason\": \"Lý do\"}"))
                .andExpect(status().isOk());

        assertThat(nativeCache(CacheConfig.CATALOG_COURSE_LIST).estimatedSize()).isZero();
        mvc.perform(list("categoryId", String.valueOf(category))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(list("q", keyword)).andExpect(jsonPath("$.content[*].slug", not(hasItem(slug))));

        mvc.perform(admin(post(API + "/admin/courses/" + course + "/unhide"), null)).andExpect(status().isOk());

        mvc.perform(list("categoryId", String.valueOf(category))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].slug").value(slug));
    }

    /** A content edit of a listed course is TTL-only on the list (accepted staleness, docs/ai/05 §5.3). */
    @Test
    void courseList_contentEdit_ttlOnly_staleUpTo60Seconds() throws Exception {
        mvc.perform(list("categoryId", String.valueOf(category))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].slug").value(slug));
        long price = jdbc.sql("SELECT price FROM courses WHERE id = ?").param(course).query(Long.class).single();

        mvc.perform(json(patch(API + "/instructor/courses/" + course), instructor, "{\"price\": 199000}"))
                .andExpect(status().isOk());

        mvc.perform(list("categoryId", String.valueOf(category))).andExpect(jsonPath("$.content[0].price")
                .value(Math.toIntExact(price)));
        CatalogCaches.clear(cacheManager);
        mvc.perform(list("categoryId", String.valueOf(category))).andExpect(jsonPath("$.content[0].price")
                .value(199000));
    }

    // ---------------------------------------------------------------- eviction through the real endpoints

    @Test
    void categoryChanged_patchCategory_categoriesEvicted_nextReadIsFresh() throws Exception {
        mvc.perform(get(CATEGORIES)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(Math.toIntExact(category))));
        assertThat(nativeCache(CacheConfig.CATALOG_CATEGORIES).getIfPresent("active")).isNotNull();

        mvc.perform(admin(patch(API + "/admin/categories/" + category), "{\"active\": false}"))
                .andExpect(status().isOk());

        assertThat(nativeCache(CacheConfig.CATALOG_CATEGORIES).getIfPresent("active")).isNull();
        sql.reset();
        mvc.perform(get(CATEGORIES)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem(Math.toIntExact(category)))));
        assertThat(sql.count()).as(sql.statements().toString()).isPositive();
    }

    @Test
    void courseContentChanged_patchPublishedCourse_detailEvicted_nextReadIsFresh() throws Exception {
        mvc.perform(detail()).andExpect(status().isOk());
        assertThat(nativeCache(CacheConfig.CATALOG_COURSE_DETAIL).getIfPresent(slug)).isNotNull();

        mvc.perform(json(patch(API + "/instructor/courses/" + course), instructor, "{\"price\": 199000}"))
                .andExpect(status().isOk());

        assertThat(nativeCache(CacheConfig.CATALOG_COURSE_DETAIL).getIfPresent(slug)).isNull();
        sql.reset();
        mvc.perform(detail()).andExpect(status().isOk()).andExpect(jsonPath("$.price").value(199000));
        assertThat(sql.count()).as(sql.statements().toString()).isPositive();
    }

    @Test
    void courseHiddenAndUnhidden_adminEndpoints_detailEvicted_hiddenAtOnce_visibleAgain() throws Exception {
        mvc.perform(detail()).andExpect(status().isOk());

        mvc.perform(admin(post(API + "/admin/courses/" + course + "/hide"), "{\"reason\": \"Vi phạm\"}"))
                .andExpect(status().isOk());

        assertThat(nativeCache(CacheConfig.CATALOG_COURSE_DETAIL).getIfPresent(slug)).isNull();
        mvc.perform(detail()).andExpect(status().isNotFound());

        mvc.perform(admin(post(API + "/admin/courses/" + course + "/unhide"), null)).andExpect(status().isOk());

        sql.reset();
        mvc.perform(detail()).andExpect(status().isOk());
        assertThat(sql.count()).isPositive();
        assertThat(nativeCache(CacheConfig.CATALOG_COURSE_DETAIL).getIfPresent(slug)).isNotNull();
    }

    @Test
    void courseApproved_adminApprove_staleEntryEvicted_publishedAtOnce() throws Exception {
        fixtures.markPendingReview(course);
        mvc.perform(detail()).andExpect(status().isNotFound());
        // an entry that must not survive the approval (what a previous publication of the slug could have left)
        nativeCache(CacheConfig.CATALOG_COURSE_DETAIL).put(slug, "stale");

        mvc.perform(admin(post(API + "/admin/courses/" + course + "/approve"), null)).andExpect(status().isOk());

        assertThat(nativeCache(CacheConfig.CATALOG_COURSE_DETAIL).getIfPresent(slug)).isNull();
        mvc.perform(detail()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("published"));
    }

    @Test
    void reviewChanged_postReview_ratingAndDetailEvicted_nextReadIsFresh() throws Exception {
        mvc.perform(detail()).andExpect(status().isOk()).andExpect(jsonPath("$.ratingCount").value(0))
                .andExpect(jsonPath("$.averageRating").value(nullValue()));
        assertThat(nativeCache(CacheConfig.CATALOG_RATING).getIfPresent(course)).isNotNull();
        assertThat(nativeCache(CacheConfig.CATALOG_COURSE_DETAIL).getIfPresent(slug)).isNotNull();

        mvc.perform(json(post(API + "/courses/" + course + "/reviews"), student, "{\"rating\": 4}"))
                .andExpect(status().isCreated());

        assertThat(nativeCache(CacheConfig.CATALOG_RATING).getIfPresent(course)).isNull();
        assertThat(nativeCache(CacheConfig.CATALOG_COURSE_DETAIL).getIfPresent(slug)).isNull();
        sql.reset();
        mvc.perform(detail()).andExpect(status().isOk()).andExpect(jsonPath("$.ratingCount").value(1))
                .andExpect(jsonPath("$.averageRating").value(4.0));
        assertThat(sql.count()).as(sql.statements().toString()).isPositive();
    }

    // ------------------------------------------------------ the listener itself, one transaction per event

    @ParameterizedTest
    @ValueSource(strings = {"content", "approved", "hidden", "unhidden", "review"})
    void listener_courseEvents_evictDetailAfterCommit_notBefore(String event) {
        Cache<Object, Object> detail = nativeCache(CacheConfig.CATALOG_COURSE_DETAIL);
        detail.put(slug, "cached");
        detail.put("other-slug", "cached");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishEvent(switch (event) {
                case "content" -> new CourseContentChanged(course, slug);
                case "approved" -> new CourseApproved(course, slug);
                case "hidden" -> new CourseHidden(course, slug, "Lý do");
                case "unhidden" -> new CourseUnhidden(course, slug);
                default -> new ReviewChanged(course, slug);
            });
            assertThat(detail.getIfPresent(slug)).as("not evicted before the commit").isNotNull();
        });

        assertThat(detail.getIfPresent(slug)).as("evicted after the commit").isNull();
        assertThat(detail.getIfPresent("other-slug")).as("only that slug").isNotNull();
    }

    @Test
    void listener_reviewChanged_alsoEvictsTheRatingOfThatCourseOnly() {
        Cache<Object, Object> rating = nativeCache(CacheConfig.CATALOG_RATING);
        rating.put(course, "cached");
        rating.put(course + 1, "cached");

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> publisher.publishEvent(new ReviewChanged(course, slug)));

        assertThat(rating.getIfPresent(course)).isNull();
        assertThat(rating.getIfPresent(course + 1)).isNotNull();
    }

    @Test
    void listener_categoryChanged_evictsCategories_courseRejectedAndRollbackEvictNothing() {
        Cache<Object, Object> categories = nativeCache(CacheConfig.CATALOG_CATEGORIES);
        Cache<Object, Object> detail = nativeCache(CacheConfig.CATALOG_COURSE_DETAIL);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        categories.put("active", "cached");
        detail.put(slug, "cached");

        // a rejected course was never published: the catalog has nothing to forget
        tx.executeWithoutResult(status -> publisher.publishEvent(new CourseRejected(course, slug, "Lý do")));
        assertThat(detail.getIfPresent(slug)).isNotNull();
        // a rolled-back write evicts nothing (AFTER_COMMIT)
        tx.executeWithoutResult(status -> {
            publisher.publishEvent(new CategoryChanged(category));
            publisher.publishEvent(new CourseHidden(course, slug, "Lý do"));
            status.setRollbackOnly();
        });
        assertThat(categories.getIfPresent("active")).isNotNull();
        assertThat(detail.getIfPresent(slug)).isNotNull();

        tx.executeWithoutResult(status -> publisher.publishEvent(new CategoryChanged(category)));
        assertThat(categories.getIfPresent("active")).isNull();
        assertThat(detail.getIfPresent(slug)).as("a category change leaves the course pages alone").isNotNull();
    }

    // ----------------------------------------------------------------------------------- configuration

    @Test
    void cacheNames_areExactlyTheSevenDocumentedOnes_nothingOfCommerceOrLearning() {
        assertThat(cacheManager.getCacheNames()).containsExactlyInAnyOrder(
                "catalog.categories", "catalog.course-detail", "catalog.course-list", "catalog.rating",
                "identity.user-status", "identity.session-status", "identity.active-roles");
    }

    @Test
    void cacheSpecs_ttlAndMaximumSize_perDocs_5_3() {
        assertThat(ttl(CacheConfig.CATALOG_CATEGORIES)).isEqualTo(Duration.ofHours(1));
        assertThat(ttl(CacheConfig.CATALOG_COURSE_DETAIL)).isEqualTo(Duration.ofMinutes(10));
        assertThat(ttl(CacheConfig.CATALOG_COURSE_LIST)).isEqualTo(Duration.ofSeconds(60));
        assertThat(ttl(CacheConfig.CATALOG_RATING)).isEqualTo(Duration.ofMinutes(10));
        assertThat(ttl(CacheConfig.USER_STATUS)).isEqualTo(Duration.ofSeconds(30));
        assertThat(ttl(CacheConfig.SESSION_STATUS)).isEqualTo(Duration.ofSeconds(30));
        assertThat(ttl(CacheConfig.ACTIVE_ROLES)).isEqualTo(Duration.ofSeconds(60));
        for (String name : cacheManager.getCacheNames()) {
            long max = nativeCache(name).policy().eviction().orElseThrow().getMaximum();
            assertThat(max).as("maximumSize of " + name).isBetween(1L, 10_000L);
        }
    }

    @Test
    void cacheStatistics_areRecorded() {
        for (String name : cacheManager.getCacheNames()) {
            nativeCache(name).put("probe", "x");
            nativeCache(name).getIfPresent("probe");
            assertThat(nativeCache(name).stats().hitCount()).as("recordStats() on " + name).isPositive();
            nativeCache(name).invalidate("probe");
        }
    }

    // ----------------------------------------------------------------------------------------------- helpers

    private Duration ttl(String name) {
        return Duration.ofNanos(nativeCache(name).policy().expireAfterWrite().orElseThrow()
                .getExpiresAfter(TimeUnit.NANOSECONDS));
    }

    @SuppressWarnings("unchecked")
    private Cache<Object, Object> nativeCache(String name) {
        return ((CaffeineCache) cacheManager.getCache(name)).getNativeCache();
    }

    private long stats(String name) {
        return nativeCache(name).stats().hitCount();
    }

    private String slugOf(long courseId) {
        return jdbc.sql("SELECT slug FROM courses WHERE id = ?").param(courseId).query(String.class).single();
    }

    private MockHttpServletRequestBuilder detail() {
        return get(CATALOG + "/" + slug);
    }

    private MockHttpServletRequestBuilder detail(Session who) {
        return get(CATALOG + "/" + slug).header(HttpHeaders.AUTHORIZATION, who.bearer());
    }

    /** {@code GET /catalog/courses} with the given name/value pairs as query parameters. */
    private static MockHttpServletRequestBuilder list(String... nameValues) {
        MockHttpServletRequestBuilder request = get(CATALOG);
        for (int i = 0; i < nameValues.length; i += 2) {
            request.param(nameValues[i], nameValues[i + 1]);
        }
        return request;
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request, String body) {
        return json(request, admin, body);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Session who,
                                                       String body) {
        request.header(HttpHeaders.AUTHORIZATION, who.bearer());
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
