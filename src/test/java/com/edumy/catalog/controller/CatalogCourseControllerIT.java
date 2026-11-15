package com.edumy.catalog.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;

import com.edumy.support.CatalogCaches;
import com.edumy.support.MySqlSeedSpringBootTest;
import com.edumy.support.SeedUserTokens;
import com.edumy.support.SqlStatementCounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * UC-G01–G03 {@code GET /api/v1/catalog/courses} on the dev seed (read-only). Published courses 1–9 (free 2, 6);
 * hidden 10, draft 11 and 14, pending 12, rejected 13 are never listed (D-08).
 * <p>Newest first (published_at desc): 9, 8, 7, 4, 3, 6, 5, 2, 1. Prices: 1 799 000 · 2 0 · 3 599 000 · 4 499 000 ·
 * 5 699 000 · 6 0 · 7 399 000 · 8 299 000 · 9 450 000. Visible reviews: course 1 → 4 (the hidden 1-star review 5 is
 * not counted), course 2 → 5 and 4, course 5 → 5, course 3 → only a withdrawn one (no rating).
 */
@MySqlSeedSpringBootTest
class CatalogCourseControllerIT {

    static final String COURSES = "/api/v1/catalog/courses";
    static final Integer[] NEWEST = {9, 8, 7, 4, 3, 6, 5, 2, 1};

    @Autowired
    CacheManager cacheManager;
    @Autowired
    MockMvc mvc;
    @Autowired
    SeedUserTokens tokens;
    @Autowired
    SqlStatementCounter sql;

    private ResultActions list(String query) throws Exception {
        return mvc.perform(get(COURSES + query));
    }

    private static Integer[] ids(String pipeSeparated) {
        return Arrays.stream(pipeSeparated.split("\\|")).map(String::strip).map(Integer::valueOf)
                .toArray(Integer[]::new);
    }

    // ------------------------------------------------------------------------------------------------- UC-G01

    @Test
    void list_anonymous_publishedOnlyNewestFirst_20PerPage_UCG01_D08() throws Exception {
        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(NEWEST)))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(9))
                .andExpect(jsonPath("$.page.totalPages").value(1))
                .andExpect(jsonPath("$.suggestions", empty()));
    }

    @Test
    void list_card_fields_ratingFromVisibleReviewsOnly_isFreeDerived_UCG01() throws Exception {
        list("?q=java")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("lap-trinh-java-spring-boot-tu-co-ban-den-nang-cao"))
                .andExpect(jsonPath("$.content[0].title").value("Lập trình Java Spring Boot từ cơ bản đến nâng cao"))
                .andExpect(jsonPath("$.content[0].thumbnailUrl").value("https://cdn.edumy.vn/thumbnails/course-1.jpg"))
                .andExpect(jsonPath("$.content[0].price").value(799000))
                .andExpect(jsonPath("$.content[0].isFree").value(false))
                .andExpect(jsonPath("$.content[0].categoryId").value(1))
                .andExpect(jsonPath("$.content[0].categoryName").value("Lập trình web"))
                .andExpect(jsonPath("$.content[0].instructorName").value("Nguyễn Văn Hùng"))
                // visible review 2 (4 stars) only; hidden review 5 (1 star) would make it 2.5
                .andExpect(jsonPath("$.content[0].averageRating").value(4.0))
                .andExpect(jsonPath("$.content[0].ratingCount").value(1))
                .andExpect(jsonPath("$.content[0].publishedAt").value("2026-07-12T10:00:00Z"))
                .andExpect(jsonPath("$.content[0].description").doesNotExist());
        // free course with two visible reviews (5 and 4)
        list("?q=html")
                .andExpect(jsonPath("$.content[0].id").value(2))
                .andExpect(jsonPath("$.content[0].price").value(0))
                .andExpect(jsonPath("$.content[0].isFree").value(true))
                .andExpect(jsonPath("$.content[0].averageRating").value(4.5))
                .andExpect(jsonPath("$.content[0].ratingCount").value(2));
        // only a withdrawn review: no rating
        list("?q=reactjs")
                .andExpect(jsonPath("$.content[0].id").value(3))
                .andExpect(jsonPath("$.content[0].averageRating", nullValue()))
                .andExpect(jsonPath("$.content[0].ratingCount").value(0));
    }

    // ------------------------------------------------------------------------------------------------- UC-G02

    @ParameterizedTest(name = "q={0}")
    @CsvSource(delimiter = ';', textBlock = """
            lap trinh;               4|1
            lập trình;               4|1
            LẬP TRÌNH;               4|1
            '  Lập   Trình  ';       4|1
            dữ liệu;                 5
            DU LIEU;                 5
            spring;                  1
            SQL;                     6
            marketing so:;           9
            """)
    void search_partialTitle_caseAndAccentInsensitive_UCG02_D09(String q, String expected) throws Exception {
        Integer[] expectedIds = ids(expected);
        mvc.perform(get(COURSES).param("q", q))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(expectedIds)))
                .andExpect(jsonPath("$.page.totalElements").value(expectedIds.length))
                .andExpect(jsonPath("$.suggestions", empty()));
    }

    @ParameterizedTest(name = "q={0}")
    @CsvSource(delimiter = ';', textBlock = """
            khoi nghiep tinh gon;    hidden course 10
            microservices;           draft course 11
            deep learning;           pending_review course 12
            photoshop;               rejected course 13
            nhiep anh;               draft course 14
            khong ton tai;           no title
            %;                       LIKE wildcard escaped
            _;                       LIKE wildcard escaped
            !;                       escape character escaped
            """)
    void search_noResult_emptyContent_suggestsNewestPublished_UCG02(String q, String why) throws Exception {
        mvc.perform(get(COURSES).param("q", q))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0))
                .andExpect(jsonPath("$.suggestions[*].id", contains(9, 8, 7, 4, 3)))
                .andExpect(jsonPath("$.suggestions[0].instructorName").value("Phạm Quốc Bảo"));
    }

    @Test
    void search_noResultWithoutKeyword_noSuggestions_UCG03() throws Exception {
        // category 5 only holds the hidden course 10: a filter without keyword is not a search (UC-G02 hint not used)
        list("?categoryId=5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.suggestions", empty()));
        list("?categoryId=999999").andExpect(status().isOk()).andExpect(jsonPath("$.content", empty()));
    }

    // ------------------------------------------------------------------------------------------------- UC-G03

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', textBlock = """
            ?price=free;                          6|2
            ?price=paid;                          9|8|7|4|3|5|1
            ?categoryId=1;                        3|2|1
            ?categoryId=3;                        6|5
            ?categoryId=1&price=free;             2
            ?q=lap trinh&categoryId=2;            4
            ?q=lap trinh&price=paid&sort=price_desc; 1|4
            ?q=lap trinh&price=free;              -1
            ?price=&sort=&q=&categoryId=;         9|8|7|4|3|6|5|2|1
            """)
    void list_filters_categoryAndPrice_combineWithSearch_UCG03(String query, String expected) throws Exception {
        Integer[] expectedIds = "-1".equals(expected.strip()) ? new Integer[0] : ids(expected);
        list(query)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(expectedIds.length))
                .andExpect(expectedIds.length == 0 ? jsonPath("$.content", empty())
                        : jsonPath("$.content[*].id", contains(expectedIds)));
    }

    @ParameterizedTest(name = "sort={0}")
    @CsvSource(delimiter = ';', textBlock = """
            newest;       9|8|7|4|3|6|5|2|1
            price_asc;    6|2|8|7|9|4|3|5|1
            price_desc;   1|5|3|4|9|7|8|6|2
            rating;       5|2|1|9|8|7|4|3|6
            """)
    void list_sortOrders_deterministicTieBreaker_UCG03(String sort, String expected) throws Exception {
        // price ties (free 6 and 2) and courses without rating fall back to newest first
        list("?sort=" + sort)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(ids(expected))));
    }

    @Test
    void list_ratingSort_averageOfVisibleReviews_UCG03() throws Exception {
        list("?sort=rating&size=3")
                .andExpect(jsonPath("$.content[*].averageRating", contains(5.0, 4.5, 4.0)))
                .andExpect(jsonPath("$.content[*].ratingCount", contains(1, 2, 1)));
    }

    @Test
    void list_pagination_pageSizeAndMax100() throws Exception {
        list("?page=1&size=5")
                .andExpect(jsonPath("$.content[*].id", contains(6, 5, 2, 1)))
                .andExpect(jsonPath("$.page.size").value(5))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(9))
                .andExpect(jsonPath("$.page.totalPages").value(2));
        list("?page=7&size=5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.totalElements").value(9))
                .andExpect(jsonPath("$.suggestions", empty()));
        // docs/ai/05 §5.1: size max 100 (clamped)
        list("?size=500").andExpect(status().isOk()).andExpect(jsonPath("$.page.size").value(100));
    }

    @Test
    void list_authenticatedCaller_sameList() throws Exception {
        mvc.perform(get(COURSES).header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUserTokens.STUDENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(NEWEST)));
    }

    // ------------------------------------------------------------------------------------------------- no N+1

    @Test
    void list_statementCount_independentOfPageSize_noNPlusOne() throws Exception {
        list("?size=5").andExpect(status().isOk()); // warm-up

        CatalogCaches.clear(cacheManager); // the list cache would answer without a statement (CatalogCacheIT)
        sql.reset();
        list("?size=5").andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(5)));
        int small = sql.count();
        String smallStatements = sql.statements().toString();

        CatalogCaches.clear(cacheManager);
        sql.reset();
        list("?size=20&sort=rating").andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(9)));
        int large = sql.count();

        // cards with instructor, category and rating + total (window count) in one statement, whatever the rows
        assertThat(small).as(smallStatements).isEqualTo(1);
        assertThat(large).as(sql.statements().toString()).isEqualTo(small);

        CatalogCaches.clear(cacheManager);
        sql.reset();
        list("?q=khong-ton-tai").andExpect(status().isOk());
        assertThat(sql.count()).as("empty search: page + suggestions " + sql.statements()).isEqualTo(2);

        CatalogCaches.clear(cacheManager);
        sql.reset();
        list("?page=7&size=5").andExpect(status().isOk());
        assertThat(sql.count()).as("empty page past the end: page + count " + sql.statements()).isEqualTo(2);
    }

    // ------------------------------------------------------------------------------------------------------ 400

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', textBlock = """
            ?price=cheap;          price
            ?price=FREE;           price
            ?sort=popular;         sort
            ?sort=PRICE_ASC;       sort
            ?categoryId=0;         categoryId
            ?categoryId=abc;       categoryId
            """)
    void list_invalidParameter_400ValidationFailed(String query, String field) throws Exception {
        expectProblem(list(query), 400, "VALIDATION_FAILED");
        list(query).andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    /** P10 SC-M2: a value that cannot be converted gets a Vietnamese message, never Spring's English text. */
    @Test
    void list_typeMismatch_vietnameseMessage_noJavaClassNames() throws Exception {
        list("?categoryId=abc")
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"))
                .andExpect(jsonPath("$.errors[0].message").value("Giá trị không đúng định dạng"));
    }

    // ------------------------------------------------------------------------------------- optional token

    @Test
    void list_invalidOrLockedToken_401_anonymousStillWorks() throws Exception {
        // decision (docs/ai/05 catalog notes): a bad token is rejected like everywhere else; no token = anonymous
        expectProblem(mvc.perform(get(COURSES).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")), 401,
                "UNAUTHENTICATED");
        expectProblem(mvc.perform(get(COURSES).header(HttpHeaders.AUTHORIZATION,
                tokens.bearer(SeedUserTokens.LOCKED))), 401, "UNAUTHENTICATED");
        list("").andExpect(status().isOk());
    }
}
