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

import com.edumy.support.CatalogCaches;
import com.edumy.support.IdorAssertions;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * UC-G04 course page + reviews and UC-G11 lesson preview on the dev seed (read-only).
 * <p>Course 1 (published, main 2, accepted co-instructor 3; chapters 1–3 → lessons 1–9, lesson 1 preview) · course 2
 * (free, lessons 10–13, 10 preview) · course 10 hidden (main 4; student 10 enrolled; in student 11's cart) · 11 draft ·
 * 12 pending_review (lesson 49 preview) · 13 rejected. Enrollments: student 6 → courses 1, 3, 2; instructor 4 bought
 * course 1 (D-12). Cart of student 16 → course 1. Visible reviews: course 1 → 2; course 2 → 1, 6; course 10 → 7.
 */
@MySqlSeedSpringBootTest
class CatalogCourseDetailIT {

    static final String COURSES = "/api/v1/catalog/courses/";
    static final String JAVA = "lap-trinh-java-spring-boot-tu-co-ban-den-nang-cao";
    static final String HTML = "html-va-css-cho-nguoi-moi-bat-dau";
    static final String REACT = "reactjs-thuc-chien-xay-dung-website-thuong-mai-dien-tu";
    static final String HIDDEN = "khoi-nghiep-tinh-gon-tu-y-tuong-den-san-pham";
    static final String DRAFT = "microservices-voi-spring-cloud";
    static final String PENDING = "deep-learning-voi-pytorch";
    static final String REJECTED = "photoshop-can-ban-cho-nguoi-moi";
    static final String UNKNOWN = "khoa-hoc-khong-ton-tai";

    @Autowired
    CacheManager cacheManager;
    @Autowired
    MockMvc mvc;
    @Autowired
    SeedUserTokens tokens;
    @Autowired
    SqlStatementCounter sql;

    private MockHttpServletRequestBuilder as(Long userId, String path) {
        MockHttpServletRequestBuilder request = get(COURSES + path);
        return userId == null ? request : request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId));
    }

    private ResultActions detail(Long userId, String slug) throws Exception {
        return mvc.perform(as(userId, slug));
    }

    // ------------------------------------------------------------------------------------------- UC-G04 detail

    @Test
    void detail_anonymous_publishedCourse_infoInstructorsOutlineRating_viewerNull_UCG04() throws Exception {
        detail(null, JAVA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.slug").value(JAVA))
                .andExpect(jsonPath("$.title").value("Lập trình Java Spring Boot từ cơ bản đến nâng cao"))
                .andExpect(jsonPath("$.description").isNotEmpty())
                .andExpect(jsonPath("$.price").value(799000))
                .andExpect(jsonPath("$.isFree").value(false))
                .andExpect(jsonPath("$.status").value("published"))
                .andExpect(jsonPath("$.categoryId").value(1))
                .andExpect(jsonPath("$.categoryName").value("Lập trình web"))
                .andExpect(jsonPath("$.mainInstructor.id").value(2))
                .andExpect(jsonPath("$.mainInstructor.fullName").value("Nguyễn Văn Hùng"))
                .andExpect(jsonPath("$.coInstructors", hasSize(1)))
                .andExpect(jsonPath("$.coInstructors[0].id").value(3))
                .andExpect(jsonPath("$.coInstructors[0].fullName").value("Lê Thị Mai"))
                // D-10: visible reviews only (hidden 1-star review 5 excluded)
                .andExpect(jsonPath("$.averageRating").value(4.0))
                .andExpect(jsonPath("$.ratingCount").value(1))
                .andExpect(jsonPath("$.totalLessons").value(9))
                .andExpect(jsonPath("$.totalDurationSeconds").value(6962))
                .andExpect(jsonPath("$.publishedAt").value("2026-07-12T10:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-09-15T08:00:00Z"))
                .andExpect(jsonPath("$.chapters[*].id", contains(1, 2, 3)))
                .andExpect(jsonPath("$.chapters[0].title").value("Giới thiệu và cài đặt môi trường"))
                .andExpect(jsonPath("$.chapters[0].lessons[*].id", contains(1, 2, 3)))
                .andExpect(jsonPath("$.chapters[2].lessons[*].id", contains(7, 8, 9)))
                .andExpect(jsonPath("$.chapters[0].lessons[0].title").value("Giới thiệu khóa học"))
                .andExpect(jsonPath("$.chapters[0].lessons[0].contentType").value("video"))
                .andExpect(jsonPath("$.chapters[0].lessons[0].durationSeconds").value(312))
                .andExpect(jsonPath("$.chapters[0].lessons[0].isPreview").value(true))
                .andExpect(jsonPath("$.chapters[0].lessons[1].isPreview").value(false))
                .andExpect(jsonPath("$.chapters[0].lessons[2].contentType").value("article"))
                .andExpect(jsonPath("$.chapters[0].lessons[2].durationSeconds", nullValue()))
                // never the lesson content on the public page
                .andExpect(jsonPath("$..videoUrl").isEmpty())
                .andExpect(jsonPath("$..articleContent").isEmpty())
                .andExpect(jsonPath("$.viewer", nullValue()));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', textBlock = """
            reactjs-thuc-chien-xay-dung-website-thuong-mai-dien-tu;  co-instructor 5 removed
            python-cho-phan-tich-du-lieu;                            invitation of 4 pending
            marketing-so-quang-cao-facebook-va-google;               invitation of 2 declined
            """)
    void detail_onlyAcceptedCoInstructorsShown_UCG04(String slug, String why) throws Exception {
        detail(null, slug).andExpect(status().isOk()).andExpect(jsonPath("$.coInstructors", empty()));
    }

    @Test
    void detail_freeCourse_isFree_ratingOfTwoVisibleReviews_UCG04() throws Exception {
        detail(null, HTML)
                .andExpect(jsonPath("$.isFree").value(true))
                .andExpect(jsonPath("$.price").value(0))
                .andExpect(jsonPath("$.averageRating").value(4.5))
                .andExpect(jsonPath("$.ratingCount").value(2))
                .andExpect(jsonPath("$.totalLessons").value(4));
        detail(null, REACT).andExpect(jsonPath("$.averageRating", nullValue())).andExpect(jsonPath("$.ratingCount")
                .value(0));
    }

    @ParameterizedTest(name = "user {0} on {1}: owned={2} inCart={3} isInstructor={4}")
    @CsvSource(delimiter = ';', textBlock = """
            6;  lap-trinh-java-spring-boot-tu-co-ban-den-nang-cao;  true;  false; false
            16; lap-trinh-java-spring-boot-tu-co-ban-den-nang-cao;  false; true;  false
            2;  lap-trinh-java-spring-boot-tu-co-ban-den-nang-cao;  false; false; true
            3;  lap-trinh-java-spring-boot-tu-co-ban-den-nang-cao;  false; false; true
            4;  lap-trinh-java-spring-boot-tu-co-ban-den-nang-cao;  true;  false; false
            1;  lap-trinh-java-spring-boot-tu-co-ban-den-nang-cao;  false; false; false
            7;  lap-trinh-java-spring-boot-tu-co-ban-den-nang-cao;  false; false; false
            5;  reactjs-thuc-chien-xay-dung-website-thuong-mai-dien-tu; false; false; false
            8;  thiet-ke-giao-dien-ung-dung-voi-figma;              false; true;  false
            8;  nhap-mon-sql-cho-nguoi-di-lam;                      true;  false; false
            """)
    void detail_authenticated_viewerContext_D12(long userId, String slug, boolean owned, boolean inCart,
                                               boolean isInstructor) throws Exception {
        detail(userId, slug)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewer.owned").value(owned))
                .andExpect(jsonPath("$.viewer.inCart").value(inCart))
                .andExpect(jsonPath("$.viewer.isInstructor").value(isInstructor));
    }

    @Test
    void detail_hiddenCourse_openForEnrolledInstructorAndAdmin_UCG04_D08_BR31() throws Exception {
        // student 10 bought course 10 before it was hidden
        detail(10L, HIDDEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.status").value("hidden"))
                .andExpect(jsonPath("$.averageRating").value(4.0))
                .andExpect(jsonPath("$.viewer.owned").value(true))
                .andExpect(jsonPath("$.viewer.isInstructor").value(false));
        // main instructor 4
        detail(SeedUserTokens.INSTRUCTOR_3, HIDDEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewer.isInstructor").value(true));
        detail(SeedUserTokens.ADMIN, HIDDEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewer.owned").value(false))
                .andExpect(jsonPath("$.viewer.isInstructor").value(false));
    }

    @Test
    void detail_hiddenCourse_anonymousOrUnrelated_404SameAsUnknown_UCG04E1_NFR01() throws Exception {
        expectProblem(detail(null, HIDDEN), 404, "RESOURCE_NOT_FOUND");
        // student 11 still has course 10 in the cart but never bought it
        IdorAssertions.assertSameNotFound(mvc, as(11L, HIDDEN), as(11L, UNKNOWN));
        IdorAssertions.assertSameNotFound(mvc, as(SeedUserTokens.STUDENT, HIDDEN), as(SeedUserTokens.STUDENT, UNKNOWN));
    }

    @ParameterizedTest(name = "{0} as user {1}")
    @CsvSource(delimiter = ';', textBlock = """
            microservices-voi-spring-cloud;          2
            deep-learning-voi-pytorch;               3
            photoshop-can-ban-cho-nguoi-moi;         3
            nhiep-anh-duong-pho-bang-dien-thoai;     4
            microservices-voi-spring-cloud;          1
            khoa-hoc-khong-ton-tai;                  6
            """)
    void detail_notPublished_404EvenForItsInstructor_D08(String slug, long userId) throws Exception {
        expectProblem(detail(null, slug), 404, "RESOURCE_NOT_FOUND");
        IdorAssertions.assertSameNotFound(mvc, as(userId, slug), as(userId, UNKNOWN));
    }

    @Test
    void detail_statementCount_constant_noNPlusOne() throws Exception {
        detail(null, JAVA).andExpect(status().isOk()); // warm-up
        detail(SeedUserTokens.STUDENT, JAVA).andExpect(status().isOk());

        CatalogCaches.clear(cacheManager); // uncached statement counts (the cached page: CatalogCacheIT)
        sql.reset();
        detail(null, JAVA).andExpect(status().isOk());
        int nineLessons = sql.count();
        String statements = sql.statements().toString();

        CatalogCaches.clear(cacheManager);
        sql.reset();
        detail(null, HTML).andExpect(status().isOk());
        int fourLessons = sql.count();

        // course (+ category, instructor), co-instructors, outline, rating (P08: own statement, cache catalog.rating)
        assertThat(nineLessons).as(statements).isEqualTo(4);
        assertThat(fourLessons).as(sql.statements().toString()).isEqualTo(nineLessons);

        CatalogCaches.clear(cacheManager);
        sql.reset();
        detail(SeedUserTokens.STUDENT, JAVA).andExpect(status().isOk());
        assertThat(sql.count()).as("+ viewer context " + sql.statements()).isEqualTo(5);
    }

    // ------------------------------------------------------------------------------------------ UC-G04 reviews

    @Test
    void reviews_visibleOnly_newestFirst_reviewerName_UCG04() throws Exception {
        mvc.perform(get(COURSES + HTML + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(1, 6)))
                .andExpect(jsonPath("$.content[0].rating").value(5))
                .andExpect(jsonPath("$.content[0].comment")
                        .value("Khóa học rất dễ hiểu, phù hợp cho người mới bắt đầu. Cảm ơn thầy Hùng!"))
                .andExpect(jsonPath("$.content[0].reviewerName").value("Đỗ Thị Hương"))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-08-07T09:00:00Z"))
                .andExpect(jsonPath("$.content[1].reviewerName").value("Hoàng Minh Tuấn"))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(2));
        // hidden review 5 (course 1) and withdrawn review 4 (course 3) are never shown
        mvc.perform(get(COURSES + JAVA + "/reviews"))
                .andExpect(jsonPath("$.content[*].id", contains(2)))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mvc.perform(get(COURSES + REACT + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.totalElements").value(0));
        mvc.perform(get(COURSES + HTML + "/reviews?page=1&size=1"))
                .andExpect(jsonPath("$.content[*].id", contains(6)))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(2));
    }

    @Test
    void reviews_hiddenCourse_sameVisibilityAsPage_UCG04() throws Exception {
        mvc.perform(as(10L, HIDDEN + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(7)));
        expectProblem(mvc.perform(get(COURSES + HIDDEN + "/reviews")), 404, "RESOURCE_NOT_FOUND");
        IdorAssertions.assertSameNotFound(mvc, as(SeedUserTokens.STUDENT, HIDDEN + "/reviews"),
                as(SeedUserTokens.STUDENT, UNKNOWN + "/reviews"));
        IdorAssertions.assertSameNotFound(mvc, get(COURSES + DRAFT + "/reviews"), get(COURSES + UNKNOWN + "/reviews"));
    }

    @Test
    void reviews_statementCount_independentOfPageSize_noNPlusOne() throws Exception {
        mvc.perform(get(COURSES + HTML + "/reviews")).andExpect(status().isOk()); // warm-up

        sql.reset();
        mvc.perform(get(COURSES + HTML + "/reviews?size=1")).andExpect(jsonPath("$.content", hasSize(1)));
        int fullPage = sql.count();
        String statements = sql.statements().toString();

        sql.reset();
        mvc.perform(get(COURSES + HTML + "/reviews?size=5")).andExpect(jsonPath("$.content", hasSize(2)));
        int small = sql.count();

        sql.reset();
        mvc.perform(get(COURSES + HTML + "/reviews?size=20")).andExpect(jsonPath("$.content", hasSize(2)));
        int large = sql.count();

        // course by slug + page with its total (window count)
        assertThat(fullPage).as(statements).isEqualTo(2);
        assertThat(small).isEqualTo(fullPage);
        assertThat(large).as(sql.statements().toString()).isEqualTo(fullPage);
    }

    // ------------------------------------------------------------------------------------------ UC-G11 preview

    @Test
    void preview_previewLessonOfPublishedCourse_anonymous_UCG11() throws Exception {
        mvc.perform(get(COURSES + JAVA + "/lessons/1/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.courseSlug").value(JAVA))
                .andExpect(jsonPath("$.courseTitle").value("Lập trình Java Spring Boot từ cơ bản đến nâng cao"))
                .andExpect(jsonPath("$.chapterTitle").value("Giới thiệu và cài đặt môi trường"))
                .andExpect(jsonPath("$.title").value("Giới thiệu khóa học"))
                .andExpect(jsonPath("$.contentType").value("video"))
                .andExpect(jsonPath("$.videoUrl").value("https://cdn.edumy.vn/videos/c1/l1.mp4"))
                .andExpect(jsonPath("$.articleContent", nullValue()))
                .andExpect(jsonPath("$.durationSeconds").value(312));
        mvc.perform(as(SeedUserTokens.STUDENT, HTML + "/lessons/10/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10));
    }

    @ParameterizedTest(name = "{0} lesson {1}: {2}")
    @CsvSource(delimiter = ';', textBlock = """
            lap-trinh-java-spring-boot-tu-co-ban-den-nang-cao;  2;   not a preview lesson
            lap-trinh-java-spring-boot-tu-co-ban-den-nang-cao;  10;  preview lesson of another course
            khoi-nghiep-tinh-gon-tu-y-tuong-den-san-pham;       45;  preview lesson of a hidden course
            deep-learning-voi-pytorch;                          49;  preview lesson of a pending course
            khoa-hoc-khong-ton-tai;                             1;   unknown course
            """)
    void preview_notAvailable_404SameAsUnknownLesson_UCG11(String slug, long lessonId, String why)
            throws Exception {
        IdorAssertions.assertSameNotFound(mvc, get(COURSES + slug + "/lessons/" + lessonId + "/preview"),
                get(COURSES + JAVA + "/lessons/" + Long.MAX_VALUE + "/preview"));
        // enrolled student 6 (course 1) gets no content through the preview endpoint either
        IdorAssertions.assertSameNotFound(mvc, as(SeedUserTokens.STUDENT, slug + "/lessons/" + lessonId + "/preview"),
                as(SeedUserTokens.STUDENT, JAVA + "/lessons/" + Long.MAX_VALUE + "/preview"));
    }
}
