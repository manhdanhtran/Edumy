package com.edumy.authoring.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.edumy.authoring.api.CourseApproved;
import com.edumy.authoring.api.CourseHidden;
import com.edumy.authoring.api.CourseRejected;
import com.edumy.authoring.api.CourseUnhidden;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MutableClock;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.SqlStatementCounter;
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
 * Admin moderation through the real filter chain and MySQL: UC-A10 approve (first publication date kept), UC-A11
 * reject and UC-A12 hide (reason required, BR-08), UC-A16 unhide, each from its single starting status (else 409
 * {@code COURSE_INVALID_TRANSITION}, UC-A10 E1), admin only (JWT role and the active admin role in the database,
 * BR-26 / UC-A10 E2), the catalog reflecting the change at once (UC-A10 step 4, UC-A12 step 3, D-20), enrolled
 * students keeping access on a hidden course (BR-31). The mails: {@code AuthoringMailListenerIT}.
 */
@MySqlSpringBootTest
@RecordApplicationEvents
class AdminCourseReviewWriteIT {

    static final String ADMIN_COURSES = "/api/v1/admin/courses/";
    static final String CATALOG = "/api/v1/catalog/courses";
    static final List<String> ACTIONS = List.of("approve", "reject", "hide", "unhide");

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
    @Autowired
    SqlStatementCounter sql;

    CourseFixtures fixtures;
    Session admin;
    Session main;
    long category;
    long course;
    String slug;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        admin = tokens.newUserSession(RoleCode.ADMIN);
        main = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        category = fixtures.category(true);
        course = fixtures.draftCourse(main.userId(), category);
        fixtures.lesson(fixtures.chapter(course));
        slug = jdbc.sql("SELECT slug FROM courses WHERE id = ?").param(course).query(String.class).single();
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    // ------------------------------------------------------------------------------------------------ UC-A10 approve

    @Test
    void approve_pending_200Published_reviewerAndFirstPublication_eventAndCatalog_UCA10() throws Exception {
        fixtures.markPendingReview(course);
        catalogHasNot(course);
        clock.setInstant(Instant.parse("2026-11-14T04:00:00Z"));

        sql.reset();
        mvc.perform(action(course, "approve", admin, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(course))
                .andExpect(jsonPath("$.status").value("published"))
                .andExpect(jsonPath("$.reviewedAt").value(startsWith("2026-11-14T04:00:")));
        assertThat(sql.statements()).as("the course row is locked")
                .anyMatch(statement -> statement.toLowerCase().contains("for update"));

        Map<String, Object> row = row(course);
        assertThat(row.get("status")).isEqualTo("published");
        assertThat(((Number) row.get("reviewed_by")).longValue()).isEqualTo(admin.userId());
        assertThat(row.get("published_at")).isNotNull().isEqualTo(row.get("reviewed_at"));
        assertThat(events.stream(CourseApproved.class)).containsExactly(new CourseApproved(course, slug));
        mvc.perform(get("/api/v1/instructor/courses/" + course).header(HttpHeaders.AUTHORIZATION, main.bearer()))
                .andExpect(jsonPath("$.publishedAt").value(startsWith("2026-11-14T04:00:")));

        // UC-A10 step 4: in the catalog at once (live SQL, no cache yet)
        mvc.perform(get(CATALOG).param("categoryId", String.valueOf(category)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(Math.toIntExact(course))));
    }

    /** "thời điểm công khai lần đầu (nếu chưa có)": a course published once keeps its first publication date. */
    @Test
    void approve_publishedOnceBefore_publishedAtKept_UCA10() throws Exception {
        fixtures.setStatus(course, "rejected", true);
        Timestamp first = Timestamp.from(Instant.parse("2025-01-02T03:04:05Z"));
        jdbc.sql("UPDATE courses SET published_at = ? WHERE id = ?").params(first, course).update();
        fixtures.markPendingReview(course);
        Timestamp before = publishedAt(course);

        mvc.perform(action(course, "approve", admin, null)).andExpect(status().isOk());

        assertThat(publishedAt(course)).isEqualTo(before);
        assertThat(row(course).get("status")).isEqualTo("published");
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "rejected", "published", "hidden"})
    void approve_notPending_409_unchanged_noEvent_UCA10E1(String state) throws Exception {
        setState(state);

        expectProblem(mvc.perform(action(course, "approve", admin, null)), 409, "COURSE_INVALID_TRANSITION");

        assertThat(row(course).get("status")).isEqualTo(state);
        assertThat(events.stream(CourseApproved.class)).isEmpty();
    }

    /** UC-A10 E1: the second of two decisions on the same submission is refused, the first one stands. */
    @Test
    void approveThenReject_secondDecision409_firstStands_UCA10E1() throws Exception {
        fixtures.markPendingReview(course);
        Session otherAdmin = tokens.newUserSession(RoleCode.ADMIN);
        mvc.perform(action(course, "approve", admin, null)).andExpect(status().isOk());

        expectProblem(mvc.perform(action(course, "reject", otherAdmin, "{\"reason\": \"Trễ\"}")), 409,
                "COURSE_INVALID_TRANSITION");

        assertThat(row(course).get("status")).isEqualTo("published");
        assertThat(((Number) row(course).get("reviewed_by")).longValue()).isEqualTo(admin.userId());
    }

    // ------------------------------------------------------------------------------------------------- UC-A11 reject

    @Test
    void reject_pendingWithReason_200Rejected_trimmedNote_event_UCA11() throws Exception {
        fixtures.markPendingReview(course);

        mvc.perform(action(course, "reject", admin, "{\"reason\": \"  Thiếu mô tả chi tiết  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.reviewNote").value("Thiếu mô tả chi tiết"));

        Map<String, Object> row = row(course);
        assertThat(row.get("status")).isEqualTo("rejected");
        assertThat(row.get("review_note")).isEqualTo("Thiếu mô tả chi tiết");
        assertThat(((Number) row.get("reviewed_by")).longValue()).isEqualTo(admin.userId());
        assertThat(row.get("published_at")).isNull();
        assertThat(events.stream(CourseRejected.class))
                .containsExactly(new CourseRejected(course, slug, "Thiếu mô tả chi tiết"));
        // UC-I18: the instructor sees the reason
        mvc.perform(get("/api/v1/instructor/courses/" + course + "/review-status")
                        .header(HttpHeaders.AUTHORIZATION, main.bearer()))
                .andExpect(jsonPath("$.reviewNote").value("Thiếu mô tả chi tiết"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"reason\": null}", "{\"reason\": \"\"}", "{\"reason\": \"   \"}"})
    void rejectOrHide_missingOrBlankReason_400_unchanged_BR08(String body) throws Exception {
        fixtures.markPendingReview(course);
        long published = fixtures.draftCourse(main.userId());
        fixtures.setStatus(published, "published", false);

        mvc.perform(action(course, "reject", admin, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", contains("reason")));
        mvc.perform(action(published, "hide", admin, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", contains("reason")));

        assertThat(row(course).get("status")).isEqualTo("pending_review");
        assertThat(row(published).get("status")).isEqualTo("published");
        assertThat(events.stream(CourseRejected.class)).isEmpty();
        assertThat(events.stream(CourseHidden.class)).isEmpty();
    }

    @Test
    void rejectOrHide_noBodyOrTooLongReason_400() throws Exception {
        fixtures.markPendingReview(course);

        expectProblem(mvc.perform(post(ADMIN_COURSES + course + "/reject")
                .header(HttpHeaders.AUTHORIZATION, admin.bearer())), 400, "MALFORMED_REQUEST");
        mvc.perform(action(course, "reject", admin, "{\"reason\": \"" + "a".repeat(16_384) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("reason")));
        assertThat(row(course).get("status")).isEqualTo("pending_review");
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "rejected", "published", "hidden"})
    void reject_notPending_409_unchanged_UCA11(String state) throws Exception {
        setState(state);

        expectProblem(mvc.perform(action(course, "reject", admin, "{\"reason\": \"Lý do mới\"}")), 409,
                "COURSE_INVALID_TRANSITION");

        assertThat(row(course).get("status")).isEqualTo(state);
        assertThat(row(course).get("review_note")).isNotEqualTo("Lý do mới");
    }

    // --------------------------------------------------------------------------------------------------- UC-A12 hide

    @Test
    void hide_published_200Hidden_event_leavesCatalog_enrolledKeepsAccess_UCA12_BR31() throws Exception {
        fixtures.setStatus(course, "published", false);
        Session student = tokens.newUserSession(RoleCode.STUDENT);
        fixtures.enrollment(student.userId(), course);
        Timestamp publishedAt = publishedAt(course);
        catalogHas(course);

        mvc.perform(action(course, "hide", admin, "{\"reason\": \"Vi phạm bản quyền\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("hidden"))
                .andExpect(jsonPath("$.reviewNote").value("Vi phạm bản quyền"));

        Map<String, Object> row = row(course);
        assertThat(row.get("status")).isEqualTo("hidden");
        assertThat(row.get("review_note")).isEqualTo("Vi phạm bản quyền");
        assertThat(((Number) row.get("reviewed_by")).longValue()).isEqualTo(admin.userId());
        assertThat(publishedAt(course)).isEqualTo(publishedAt);
        assertThat(events.stream(CourseHidden.class))
                .containsExactly(new CourseHidden(course, slug, "Vi phạm bản quyền"));
        catalogHasNot(course); // step 3
        mvc.perform(get("/api/v1/catalog/courses/" + slug)).andExpect(status().isNotFound());
        // step 5 / BR-31: the enrolled student keeps learning
        mvc.perform(get("/api/v1/learn/courses/" + course).header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "pending_review", "rejected", "hidden"})
    void hide_notPublished_409_unchanged_UCA12(String state) throws Exception {
        setState(state);

        expectProblem(mvc.perform(action(course, "hide", admin, "{\"reason\": \"Lý do\"}")), 409,
                "COURSE_INVALID_TRANSITION");

        assertThat(row(course).get("status")).isEqualTo(state);
        assertThat(events.stream(CourseHidden.class)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------- UC-A16 unhide

    @Test
    void unhide_hidden_200Published_lastReasonKept_event_backInCatalog_UCA16_D20() throws Exception {
        fixtures.setStatus(course, "published", false);
        mvc.perform(action(course, "hide", admin, "{\"reason\": \"Tạm ẩn\"}")).andExpect(status().isOk());
        Timestamp publishedAt = publishedAt(course);

        mvc.perform(action(course, "unhide", admin, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"))
                .andExpect(jsonPath("$.reviewNote").value("Tạm ẩn"));

        assertThat(row(course).get("status")).isEqualTo("published");
        assertThat(publishedAt(course)).isEqualTo(publishedAt);
        assertThat(events.stream(CourseUnhidden.class)).containsExactly(new CourseUnhidden(course, slug));
        catalogHas(course);
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "pending_review", "rejected", "published"})
    void unhide_notHidden_409_unchanged_UCA16(String state) throws Exception {
        setState(state);

        expectProblem(mvc.perform(action(course, "unhide", admin, null)), 409, "COURSE_INVALID_TRANSITION");

        assertThat(row(course).get("status")).isEqualTo(state);
        assertThat(events.stream(CourseUnhidden.class)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ authorization

    @Test
    void everyAction_unknownCourse_404() throws Exception {
        for (String action : ACTIONS) {
            expectProblem(mvc.perform(action(Long.MAX_VALUE, action, admin, body(action))), 404,
                    "RESOURCE_NOT_FOUND");
        }
    }

    @Test
    void everyAction_instructorOrStudent_403_withoutToken_401_unchanged() throws Exception {
        fixtures.markPendingReview(course);
        Session student = tokens.newUserSession(RoleCode.STUDENT);

        for (String action : ACTIONS) {
            expectProblem(mvc.perform(action(course, action, main, body(action))), 403, "ACCESS_DENIED");
            expectProblem(mvc.perform(action(course, action, student, body(action))), 403, "ACCESS_DENIED");
            expectProblem(mvc.perform(post(ADMIN_COURSES + course + "/" + action)
                    .contentType(MediaType.APPLICATION_JSON).content(body(action) == null ? "" : body(action))),
                    401, "UNAUTHENTICATED");
        }
        assertThat(row(course).get("status")).isEqualTo("pending_review");
    }

    /** UC-A10 E2 / BR-26: an admin whose role was revoked in the database is refused despite the JWT claim. */
    @Test
    void everyAction_adminRoleRevokedInDatabase_staleJwt_403_unchanged_BR26() throws Exception {
        fixtures.markPendingReview(course);
        Session stale = tokens.newUserSession(RoleCode.ADMIN);
        users.revokeRole(stale.userId(), RoleCode.ADMIN);

        for (String action : ACTIONS) {
            expectProblem(mvc.perform(action(course, action, stale, body(action))), 403, "ACCESS_DENIED");
        }
        assertThat(row(course).get("status")).isEqualTo("pending_review");
        assertThat(events.stream(CourseApproved.class)).isEmpty();
        assertThat(events.stream(CourseRejected.class)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private void setState(String state) {
        if ("pending_review".equals(state)) {
            fixtures.markPendingReview(course);
        } else if (!"draft".equals(state)) {
            fixtures.setStatus(course, state, false);
        }
    }

    private void catalogHas(long courseId) throws Exception {
        mvc.perform(get(CATALOG).param("categoryId", String.valueOf(category)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(Math.toIntExact(courseId))));
    }

    private void catalogHasNot(long courseId) throws Exception {
        assertThat(courseId).isPositive();
        mvc.perform(get(CATALOG).param("categoryId", String.valueOf(category)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()));
    }

    private Map<String, Object> row(long courseId) {
        return jdbc.sql("SELECT status, review_note, reviewed_by, reviewed_at, published_at FROM courses WHERE id = ?")
                .param(courseId).query().singleRow();
    }

    private Timestamp publishedAt(long courseId) {
        return jdbc.sql("SELECT published_at FROM courses WHERE id = ?").param(courseId).query(Timestamp.class)
                .single();
    }

    private static String body(String action) {
        return switch (action) {
            case "reject", "hide" -> "{\"reason\": \"Lý do\"}";
            default -> null;
        };
    }

    private static MockHttpServletRequestBuilder action(long courseId, String action, Session session, String body) {
        MockHttpServletRequestBuilder request = post(ADMIN_COURSES + courseId + "/" + action)
                .header(HttpHeaders.AUTHORIZATION, session.bearer());
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
