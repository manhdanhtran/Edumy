package com.edumy.learning.controller;

import static com.edumy.support.IdorAssertions.assertSameNotFound;
import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.edumy.learning.api.ReviewChanged;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MutableClock;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * UC-S12 / S13 / S14 student reviews and UC-A15 / A17 admin hide / restore through the real filter chain and MySQL:
 * enrolled students only (BR-25), one review per enrollment (BR-18, also under concurrency), withdrawn → the same row
 * re-rated, hidden → only an admin restores (BR-35/BR-41), rating / comment limits (D-35), IDOR, admin role re-checked
 * in the database (BR-26), {@code ReviewChanged} per change, and the live catalog reads (list + average) follow.
 */
@MySqlSpringBootTest
@RecordApplicationEvents
class ReviewWriteIT {

    static final String API = "/api/v1";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MutableClock clock;
    @Autowired
    ApplicationEvents events;

    CourseFixtures fixtures;
    Session admin;
    Session student;
    Session instructor;
    long category;
    long course;
    String slug;
    long enrollment;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        admin = tokens.newUserSession(RoleCode.ADMIN);
        student = tokens.newUserSession(RoleCode.STUDENT);
        instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        category = fixtures.category(true);
        course = fixtures.draftCourse(instructor.userId(), category);
        fixtures.lesson(fixtures.chapter(course));
        fixtures.setStatus(course, "published", false);
        slug = jdbc.sql("SELECT slug FROM courses WHERE id = ?").param(course).query(String.class).single();
        enrollment = fixtures.enrollment(student.userId(), course);
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    // ------------------------------------------------------------------------------------------------------ UC-S12

    @Test
    void create_enrolled_201_visible_ratingAndCommentStored_eventWithSlug_UCS12() throws Exception {
        clock.setInstant(Instant.parse("2026-11-14T03:00:00Z"));

        mvc.perform(create(student, course, "{\"rating\": 4, \"comment\": \"  Khóa học rất hay  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.courseId").value(course))
                .andExpect(jsonPath("$.rating").value(4))
                .andExpect(jsonPath("$.comment").value("Khóa học rất hay"))
                .andExpect(jsonPath("$.status").value("visible"))
                .andExpect(jsonPath("$.createdAt").value(startsWith("2026-11-14T03:00:00")))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        Map<String, Object> row = onlyReview();
        assertThat(((Number) row.get("rating")).intValue()).isEqualTo(4);
        assertThat(row.get("visibility_status")).isEqualTo("visible");
        assertThat(row.get("moderated_by")).isNull();
        assertThat(changed()).containsExactly(new ReviewChanged(course, slug));
    }

    @Test
    void create_commentOptional_blankStoredAsNull_D35() throws Exception {
        mvc.perform(create(student, course, "{\"rating\": 5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.comment").doesNotExist());

        assertThat(onlyReview().get("comment")).isNull();
    }

    @Test
    void create_commentOf2000Characters_ok_D35() throws Exception {
        mvc.perform(create(student, course, "{\"rating\": 3, \"comment\": \"" + "á".repeat(2000) + "\"}"))
                .andExpect(status().isCreated());

        assertThat((String) onlyReview().get("comment")).hasSize(2000);
    }

    @Test
    void create_notEnrolled_403_enrollmentRequired_nothingWritten_E1() throws Exception {
        Session other = tokens.newUserSession(RoleCode.STUDENT);

        expectProblem(mvc.perform(create(other, course, "{\"rating\": 5}")), 403, "ENROLLMENT_REQUIRED");

        assertThat(reviewCount()).isZero();
        assertThat(changed()).isEmpty();
    }

    @Test
    void create_instructorWithoutEnrollment_403() throws Exception {
        expectProblem(mvc.perform(create(instructor, course, "{\"rating\": 5}")), 403, "ENROLLMENT_REQUIRED");
    }

    @Test
    void create_unknownCourse_404() throws Exception {
        expectProblem(mvc.perform(create(student, Long.MAX_VALUE, "{\"rating\": 5}")), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void create_hiddenCourse_enrolledStudentCanStillReview_BR31() throws Exception {
        fixtures.setStatus(course, "hidden", true);

        mvc.perform(create(student, course, "{\"rating\": 5}")).andExpect(status().isCreated());
    }

    @Test
    void create_secondTime_visible_409_oneRowUnchanged_BR18() throws Exception {
        mvc.perform(create(student, course, "{\"rating\": 4, \"comment\": \"Một\"}")).andExpect(status().isCreated());

        expectProblem(mvc.perform(create(student, course, "{\"rating\": 1, \"comment\": \"Hai\"}")), 409,
                "DATA_CONFLICT");

        Map<String, Object> row = onlyReview();
        assertThat(((Number) row.get("rating")).intValue()).isEqualTo(4);
        assertThat(row.get("comment")).isEqualTo("Một");
        assertThat(changed()).hasSize(1);
    }

    @Test
    void create_withdrawn_reRatesTheSameRow_200_visible_UCS12_step3() throws Exception {
        long id = fixtures.review(enrollment, 2, "withdrawn", null);

        mvc.perform(create(student, course, "{\"rating\": 5, \"comment\": \"Đánh giá lại\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.status").value("visible"));

        Map<String, Object> row = onlyReview();
        assertThat(((Number) row.get("id")).longValue()).isEqualTo(id);
        assertThat(row.get("comment")).isEqualTo("Đánh giá lại");
        assertThat(row.get("visibility_status")).isEqualTo("visible");
        assertThat(changed()).containsExactly(new ReviewChanged(course, slug));
    }

    @Test
    void create_hiddenByAdmin_409_reviewHiddenByAdmin_unchanged_E2_BR35() throws Exception {
        fixtures.review(enrollment, 1, "hidden", admin.userId());

        expectProblem(mvc.perform(create(student, course, "{\"rating\": 5}")), 409, "REVIEW_HIDDEN_BY_ADMIN");

        Map<String, Object> row = onlyReview();
        assertThat(row.get("visibility_status")).isEqualTo("hidden");
        assertThat(((Number) row.get("rating")).intValue()).isEqualTo(1);
        assertThat(changed()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"rating\": 0}", "{\"rating\": 6}", "{\"rating\": -1}", "{}", "{\"rating\": null}",
            "{\"rating\": 3, \"comment\": \"COMMENT_2001\"}"})
    void create_invalidInput_400_validationFailed_noRowNoEvent_E3_D35(String body) throws Exception {
        String json = body.replace("COMMENT_2001", "x".repeat(2001));

        expectProblem(mvc.perform(create(student, course, json)), 400, "VALIDATION_FAILED");

        assertThat(reviewCount()).isZero();
        assertThat(changed()).isEmpty();
    }

    @Test
    void create_malformedBody_400() throws Exception {
        expectProblem(mvc.perform(create(student, course, "{\"rating\": \"abc\"}")), 400, "MALFORMED_REQUEST");
        expectProblem(mvc.perform(post(API + "/courses/" + course + "/reviews")
                .header(HttpHeaders.AUTHORIZATION, student.bearer())), 400, "MALFORMED_REQUEST");
    }

    @Test
    void create_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(post(API + "/courses/" + course + "/reviews")
                .contentType(MediaType.APPLICATION_JSON).content("{\"rating\": 5}")), 401, "UNAUTHENTICATED");
    }

    @Test
    void create_concurrentDoublePost_oneRow_oneCreated_oneConflict_BR18() throws Exception {
        List<Integer> statuses = parallel(
                () -> statusOf(create(student, course, "{\"rating\": 5, \"comment\": \"A\"}")),
                () -> statusOf(create(student, course, "{\"rating\": 4, \"comment\": \"B\"}")));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(reviewCount()).isOne();
    }

    // ------------------------------------------------------------------------------------------------------ UC-S13

    @Test
    void update_ownVisible_200_ratingAndCommentReplaced_sameRow_event_UCS13() throws Exception {
        long id = fixtures.review(enrollment, 4, "visible", null);

        mvc.perform(update(student, id, "{\"rating\": 2, \"comment\": \"  Đã đổi ý  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.rating").value(2))
                .andExpect(jsonPath("$.comment").value("Đã đổi ý"))
                .andExpect(jsonPath("$.status").value("visible"));

        Map<String, Object> row = onlyReview();
        assertThat(((Number) row.get("rating")).intValue()).isEqualTo(2);
        assertThat(changed()).containsExactly(new ReviewChanged(course, slug));
    }

    @Test
    void update_withoutComment_clearsTheComment() throws Exception {
        long id = fixtures.review(enrollment, 4, "visible", null);

        mvc.perform(update(student, id, "{\"rating\": 4}")).andExpect(status().isOk());

        assertThat(onlyReview().get("comment")).isNull();
    }

    @Test
    void update_hiddenByAdmin_409_reviewHiddenByAdmin_unchanged_BR35_BR41() throws Exception {
        long id = fixtures.review(enrollment, 4, "hidden", admin.userId());

        expectProblem(mvc.perform(update(student, id, "{\"rating\": 5}")), 409, "REVIEW_HIDDEN_BY_ADMIN");

        assertThat(((Number) onlyReview().get("rating")).intValue()).isEqualTo(4);
        assertThat(changed()).isEmpty();
    }

    @Test
    void update_withdrawn_409_useUCS12ToReRate() throws Exception {
        long id = fixtures.review(enrollment, 4, "withdrawn", null);

        expectProblem(mvc.perform(update(student, id, "{\"rating\": 5}")), 409, "DATA_CONFLICT");

        assertThat(onlyReview().get("visibility_status")).isEqualTo("withdrawn");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"rating\": 0}", "{\"rating\": 6}", "{}", "{\"rating\": 3, \"comment\": \"COMMENT_2001\"}"})
    void update_invalidInput_400_unchanged(String body) throws Exception {
        long id = fixtures.review(enrollment, 4, "visible", null);

        expectProblem(mvc.perform(update(student, id, body.replace("COMMENT_2001", "x".repeat(2001)))), 400,
                "VALIDATION_FAILED");

        assertThat(((Number) onlyReview().get("rating")).intValue()).isEqualTo(4);
    }

    @Test
    void update_someoneElsesReview_404_identicalToUnknown_IDOR_NFR01() throws Exception {
        long id = fixtures.review(enrollment, 4, "visible", null);
        Session other = tokens.newUserSession(RoleCode.STUDENT);

        assertSameNotFound(mvc, update(other, id, "{\"rating\": 1}"), update(other, Long.MAX_VALUE, "{\"rating\": 1}"));
        assertSameNotFound(mvc, update(instructor, id, "{\"rating\": 1}"),
                update(instructor, Long.MAX_VALUE, "{\"rating\": 1}"));

        assertThat(((Number) onlyReview().get("rating")).intValue()).isEqualTo(4);
        assertThat(changed()).isEmpty();
    }

    @Test
    void update_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(patch(API + "/reviews/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\": 5}")), 401, "UNAUTHENTICATED");
    }

    // ------------------------------------------------------------------------------------------------------ UC-S14

    @Test
    void delete_ownVisible_204_withdrawnNotDeleted_event_UCS14() throws Exception {
        long id = fixtures.review(enrollment, 4, "visible", null);

        mvc.perform(withdraw(student, id)).andExpect(status().isNoContent());

        assertThat(onlyReview().get("visibility_status")).isEqualTo("withdrawn");
        assertThat(changed()).containsExactly(new ReviewChanged(course, slug));
    }

    @Test
    void delete_hiddenByAdmin_409_reviewHiddenByAdmin_unchanged_BR35() throws Exception {
        long id = fixtures.review(enrollment, 4, "hidden", admin.userId());

        expectProblem(mvc.perform(withdraw(student, id)), 409, "REVIEW_HIDDEN_BY_ADMIN");

        assertThat(onlyReview().get("visibility_status")).isEqualTo("hidden");
        assertThat(changed()).isEmpty();
    }

    @Test
    void delete_alreadyWithdrawn_409() throws Exception {
        long id = fixtures.review(enrollment, 4, "withdrawn", null);

        expectProblem(mvc.perform(withdraw(student, id)), 409, "DATA_CONFLICT");
    }

    @Test
    void delete_someoneElsesReview_404_identicalToUnknown_IDOR_NFR01() throws Exception {
        long id = fixtures.review(enrollment, 4, "visible", null);
        Session other = tokens.newUserSession(RoleCode.STUDENT);

        assertSameNotFound(mvc, withdraw(other, id), withdraw(other, Long.MAX_VALUE));

        assertThat(onlyReview().get("visibility_status")).isEqualTo("visible");
    }

    @Test
    void delete_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(delete(API + "/reviews/1")), 401, "UNAUTHENTICATED");
    }

    @Test
    void delete_thenPostAgain_reRatesTheSameRow_catalogFollowsLive() throws Exception {
        Session second = tokens.newUserSession(RoleCode.STUDENT);
        fixtures.enrollment(second.userId(), course);
        mvc.perform(create(student, course, "{\"rating\": 5}")).andExpect(status().isCreated());
        mvc.perform(create(second, course, "{\"rating\": 3}")).andExpect(status().isCreated());
        long id = jdbc.sql("SELECT id FROM course_reviews WHERE enrollment_id = ?").param(enrollment)
                .query(Long.class).single();
        catalogShows(2, 4.0, 2);

        mvc.perform(withdraw(student, id)).andExpect(status().isNoContent());
        catalogShows(1, 3.0, 1);

        mvc.perform(create(student, course, "{\"rating\": 1}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        catalogShows(2, 2.0, 2);
    }

    // ------------------------------------------------------------------------------------------- UC-A15 / UC-A17

    @Test
    void hide_visible_200_hidden_moderatorAndTimeRecorded_leavesCatalog_event_UCA15() throws Exception {
        long id = fixtures.review(enrollment, 4, "visible", null);
        clock.setInstant(Instant.parse("2026-11-14T04:00:00Z"));
        catalogShows(1, 4.0, 1);

        mvc.perform(hide(admin, id, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("hidden"));

        Map<String, Object> row = onlyReview();
        assertThat(row.get("visibility_status")).isEqualTo("hidden");
        assertThat(((Number) row.get("moderated_by")).longValue()).isEqualTo(admin.userId());
        assertThat(row.get("moderated_at")).isNotNull();
        assertThat(changed()).containsExactly(new ReviewChanged(course, slug));
        catalogShows(0, null, 0);
    }

    @Test
    void hide_withdrawn_200_hidden_UCA15() throws Exception {
        long id = fixtures.review(enrollment, 4, "withdrawn", null);

        mvc.perform(hide(admin, id, "{\"reason\": \"Vi phạm\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("hidden"));

        assertThat(onlyReview().get("visibility_status")).isEqualTo("hidden");
    }

    @Test
    void hide_alreadyHidden_409_unchanged() throws Exception {
        long id = fixtures.review(enrollment, 4, "hidden", admin.userId());

        expectProblem(mvc.perform(hide(admin, id, null)), 409, "DATA_CONFLICT");

        assertThat(changed()).isEmpty();
    }

    @Test
    void hide_tooLongNote_400() throws Exception {
        long id = fixtures.review(enrollment, 4, "visible", null);

        expectProblem(mvc.perform(hide(admin, id, "{\"reason\": \"" + "x".repeat(501) + "\"}")), 400,
                "VALIDATION_FAILED");

        assertThat(onlyReview().get("visibility_status")).isEqualTo("visible");
    }

    @Test
    void restore_hidden_200_visible_countsAgain_authorStillMayEdit_UCA17() throws Exception {
        long id = fixtures.review(enrollment, 4, "hidden", admin.userId());
        catalogShows(0, null, 0);

        mvc.perform(restore(admin, id, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("visible"));

        Map<String, Object> row = onlyReview();
        assertThat(row.get("visibility_status")).isEqualTo("visible");
        assertThat(((Number) row.get("moderated_by")).longValue()).isEqualTo(admin.userId()); // kept as history
        assertThat(changed()).containsExactly(new ReviewChanged(course, slug));
        catalogShows(1, 4.0, 1);
        mvc.perform(update(student, id, "{\"rating\": 5}")).andExpect(status().isOk());
    }

    @Test
    void restore_notHidden_409_unchanged() throws Exception {
        long visible = fixtures.review(enrollment, 4, "visible", null);

        expectProblem(mvc.perform(restore(admin, visible, null)), 409, "DATA_CONFLICT");

        assertThat(onlyReview().get("visibility_status")).isEqualTo("visible");
        assertThat(changed()).isEmpty();
    }

    @Test
    void restore_withdrawn_409() throws Exception {
        long id = fixtures.review(enrollment, 4, "withdrawn", null);

        expectProblem(mvc.perform(restore(admin, id, null)), 409, "DATA_CONFLICT");
    }

    @Test
    void adminActions_unknownReview_404() throws Exception {
        expectProblem(mvc.perform(hide(admin, Long.MAX_VALUE, null)), 404, "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(restore(admin, Long.MAX_VALUE, null)), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void adminActions_studentOrInstructor_403_withoutToken_401_unchanged() throws Exception {
        long id = fixtures.review(enrollment, 4, "visible", null);

        for (String action : List.of("hide", "restore")) {
            expectProblem(mvc.perform(admin(student, id, action)), 403, "ACCESS_DENIED");
            expectProblem(mvc.perform(admin(instructor, id, action)), 403, "ACCESS_DENIED");
            expectProblem(mvc.perform(post(API + "/admin/reviews/" + id + "/" + action)), 401, "UNAUTHENTICATED");
        }
        assertThat(onlyReview().get("visibility_status")).isEqualTo("visible");
        assertThat(changed()).isEmpty();
    }

    /** BR-26: an admin whose role was revoked in the database is refused despite the JWT claim. */
    @Test
    void adminActions_roleRevokedInDatabase_staleJwt_403_unchanged_BR26() throws Exception {
        long visible = fixtures.review(enrollment, 4, "visible", null);
        Session stale = tokens.newUserSession(RoleCode.ADMIN);
        users.revokeRole(stale.userId(), RoleCode.ADMIN);

        expectProblem(mvc.perform(admin(stale, visible, "hide")), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(admin(stale, visible, "restore")), 403, "ACCESS_DENIED");

        assertThat(onlyReview().get("visibility_status")).isEqualTo("visible");
        assertThat(changed()).isEmpty();
    }

    @Test
    void hide_thenEveryStudentWrite_409_reviewHiddenByAdmin_wholeCycle_BR35() throws Exception {
        mvc.perform(create(student, course, "{\"rating\": 5}")).andExpect(status().isCreated());
        long id = jdbc.sql("SELECT id FROM course_reviews").query(Long.class).single();

        mvc.perform(hide(admin, id, null)).andExpect(status().isOk());

        expectProblem(mvc.perform(update(student, id, "{\"rating\": 1}")), 409, "REVIEW_HIDDEN_BY_ADMIN");
        expectProblem(mvc.perform(withdraw(student, id)), 409, "REVIEW_HIDDEN_BY_ADMIN");
        expectProblem(mvc.perform(create(student, course, "{\"rating\": 1}")), 409, "REVIEW_HIDDEN_BY_ADMIN");

        mvc.perform(restore(admin, id, null)).andExpect(status().isOk());
        mvc.perform(withdraw(student, id)).andExpect(status().isNoContent());
        assertThat(changed()).hasSize(4); // create, hide, restore, withdraw
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private void catalogShows(int count, Double average, int ratingCount) throws Exception {
        mvc.perform(get(API + "/catalog/courses/" + slug + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(count)));
        var detail = mvc.perform(get(API + "/catalog/courses/" + slug)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ratingCount").value(ratingCount));
        if (average == null) {
            detail.andExpect(jsonPath("$.averageRating").value(org.hamcrest.Matchers.nullValue()));
        } else {
            detail.andExpect(jsonPath("$.averageRating").value(average));
        }
    }

    private List<ReviewChanged> changed() {
        return events.stream(ReviewChanged.class).toList();
    }

    private long reviewCount() {
        return jdbc.sql("SELECT COUNT(*) FROM course_reviews").query(Long.class).single();
    }

    /** The only review row of the test (all fixtures create at most one per enrollment; a single enrollment reviews). */
    private Map<String, Object> onlyReview() {
        return jdbc.sql("""
                        SELECT id, rating, comment, visibility_status, moderated_by, moderated_at
                        FROM course_reviews WHERE enrollment_id = ?""")
                .param(enrollment).query().singleRow();
    }

    private static MockHttpServletRequestBuilder create(Session who, long courseId, String body) {
        return json(post(API + "/courses/" + courseId + "/reviews"), who, body);
    }

    private static MockHttpServletRequestBuilder update(Session who, long id, String body) {
        return json(patch(API + "/reviews/" + id), who, body);
    }

    private static MockHttpServletRequestBuilder withdraw(Session who, long id) {
        return delete(API + "/reviews/" + id).header(HttpHeaders.AUTHORIZATION, who.bearer());
    }

    private static MockHttpServletRequestBuilder hide(Session who, long id, String body) {
        return admin(who, id, "hide", body);
    }

    private static MockHttpServletRequestBuilder restore(Session who, long id, String body) {
        return admin(who, id, "restore", body);
    }

    private static MockHttpServletRequestBuilder admin(Session who, long id, String action) {
        return admin(who, id, action, null);
    }

    private static MockHttpServletRequestBuilder admin(Session who, long id, String action, String body) {
        MockHttpServletRequestBuilder request = post(API + "/admin/reviews/" + id + "/" + action)
                .header(HttpHeaders.AUTHORIZATION, who.bearer());
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Session who,
                                                      String body) {
        return request.header(HttpHeaders.AUTHORIZATION, who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    /** Runs the tasks at the same moment on separate threads; results in task order. */
    @SafeVarargs
    private static List<Integer> parallel(Callable<Integer>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
