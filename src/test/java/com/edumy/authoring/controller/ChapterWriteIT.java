package com.edumy.authoring.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.edumy.authoring.api.CourseContentChanged;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.IdorAssertions;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.SqlStatementCounter;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Chapter writes (UC-I05 create, I06 rename, I07 delete, I08 reorder) through the real filter chain and MySQL: BR-04
 * (main or accepted co-instructor), BR-26 (active role in the DB), BR-33 (pending_review → 409), append at the end,
 * positions kept 1..n, two-phase reorder (docs/ai/03 §3.4), D-14 (≥1 lesson on a published / hidden course and
 * {@link CourseContentChanged} only for them), IDOR (NFR-01) on every path id.
 */
@MySqlSpringBootTest
@RecordApplicationEvents
class ChapterWriteIT {

    static final String BASE = "/api/v1/instructor";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ApplicationEvents events;
    @Autowired
    SqlStatementCounter sql;

    CourseFixtures fixtures;
    Session instructor;
    long course;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        course = fixtures.draftCourse(instructor.userId());
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    // ------------------------------------------------------------------------------------------------ UC-I05 create

    @Test
    void create_appendsAtEnd_201_noEventForDraft_UCI05() throws Exception {
        sql.reset();
        long first = idOf(mvc.perform(json(post(chaptersOf(course)), instructor, "{\"title\": \"  Mở đầu  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Mở đầu"))
                .andExpect(jsonPath("$.sortOrder").value(1))
                .andExpect(jsonPath("$.lessons").isEmpty())
                .andReturn());
        assertThat(sql.statements()).as("the course row is locked")
                .anyMatch(statement -> statement.toLowerCase().contains("for update"));

        MvcResult second = mvc.perform(json(post(chaptersOf(course)), instructor, "{\"title\": \"Phần 2\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sortOrder").value(2))
                .andReturn();
        long secondId = idOf(second);

        assertThat(second.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo(BASE + "/chapters/" + secondId);
        assertThat(sortOrders(course)).containsExactly(first, secondId);
        assertThat(events.stream(CourseContentChanged.class)).isEmpty();
    }

    @Test
    void create_afterExistingChapters_maxPlusOne_UCI05() throws Exception {
        fixtures.chapter(course, 1);
        fixtures.chapter(course, 2);

        mvc.perform(json(post(chaptersOf(course)), instructor, "{\"title\": \"Cuối\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sortOrder").value(3));
    }

    @Test
    void create_invalidTitle_400() throws Exception {
        expectField(mvc.perform(json(post(chaptersOf(course)), instructor, "{\"title\": \"   \"}")), "title");
        expectField(mvc.perform(json(post(chaptersOf(course)), instructor, "{}")), "title");
        expectField(mvc.perform(json(post(chaptersOf(course)), instructor,
                "{\"title\": \"%s\"}".formatted("a".repeat(201)))), "title");
        assertThat(count("chapters", "course_id", course)).isZero();
    }

    // ------------------------------------------------------------------------------------------------ UC-I06 rename

    @Test
    void rename_200_withLessons_UCI06() throws Exception {
        long chapter = fixtures.chapter(course, 1);
        long lesson = fixtures.lesson(chapter, 1);

        mvc.perform(json(patch(BASE + "/chapters/" + chapter), instructor, "{\"title\": \" Tên mới \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(chapter))
                .andExpect(jsonPath("$.title").value("Tên mới"))
                .andExpect(jsonPath("$.sortOrder").value(1))
                .andExpect(jsonPath("$.lessons[0].id").value(lesson))
                .andExpect(jsonPath("$.lessons[0].videoUrl").value("https://cdn.edumy.vn/v.mp4"));

        assertThat(jdbc.sql("SELECT title FROM chapters WHERE id = ?").param(chapter).query(String.class).single())
                .isEqualTo("Tên mới");
        expectField(mvc.perform(json(patch(BASE + "/chapters/" + chapter), instructor, "{\"title\": \"\"}")), "title");
    }

    // ------------------------------------------------------------------------------------------------ UC-I07 delete

    @Test
    void delete_draft_204_lessonsGo_followingChaptersMoveUp_UCI07() throws Exception {
        long c1 = fixtures.chapter(course, 1);
        long c2 = fixtures.chapter(course, 2);
        long c3 = fixtures.chapter(course, 3);
        long lesson = fixtures.lesson(c2, 1);

        mvc.perform(delete(BASE + "/chapters/" + c2).header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(status().isNoContent());

        assertThat(count("chapters", "id", c2)).isZero();
        assertThat(count("lessons", "id", lesson)).isZero();
        assertThat(sortOrders(course)).containsExactly(c1, c3);
        assertThat(positions("chapters", "course_id", course)).containsExactly(1, 2);
        assertThat(events.stream(CourseContentChanged.class)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"published", "hidden"})
    void delete_onlyChapterHoldingTheLessons_ofPublishedOrHiddenCourse_409_D14(String courseStatus) throws Exception {
        long chapter = fixtures.chapter(course, 1);
        fixtures.lesson(chapter, 1);
        fixtures.lesson(chapter, 2);
        long empty = fixtures.chapter(course, 2);
        fixtures.setStatus(course, courseStatus, true);

        expectProblem(mvc.perform(delete(BASE + "/chapters/" + chapter)
                .header(HttpHeaders.AUTHORIZATION, instructor.bearer())), 409, "COURSE_LAST_LESSON_DELETE_FORBIDDEN");
        assertThat(count("chapters", "id", chapter)).isOne();
        assertThat(count("lessons", "chapter_id", chapter)).isEqualTo(2);
        assertThat(events.stream(CourseContentChanged.class)).isEmpty();

        // an empty chapter of the same course may go
        mvc.perform(delete(BASE + "/chapters/" + empty).header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(status().isNoContent());
        assertThat(events.stream(CourseContentChanged.class)).extracting(CourseContentChanged::courseId)
                .containsExactly(course);
    }

    @Test
    void delete_published_lessonsRemainElsewhere_204_event_D14() throws Exception {
        long c1 = fixtures.chapter(course, 1);
        fixtures.lesson(c1, 1);
        long c2 = fixtures.chapter(course, 2);
        fixtures.lesson(c2, 1);
        fixtures.setStatus(course, "published", true);

        mvc.perform(delete(BASE + "/chapters/" + c1).header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(status().isNoContent());

        assertThat(sortOrders(course)).containsExactly(c2);
        assertThat(positions("chapters", "course_id", course)).containsExactly(1);
        assertThat(events.stream(CourseContentChanged.class)).containsExactly(
                new CourseContentChanged(course, slugOf(course)));
    }

    // ------------------------------------------------------------------------------------------------ UC-I08 reorder

    @Test
    void reorder_validPermutation_200_positions1toN_UCI08() throws Exception {
        long a = fixtures.chapter(course, 1);
        long b = fixtures.chapter(course, 2);
        long c = fixtures.chapter(course, 3);
        long lesson = fixtures.lesson(a, 1);

        sql.reset();
        mvc.perform(json(put(chaptersOf(course) + "/order"), instructor, ids(c, a, b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(c))
                .andExpect(jsonPath("$[0].sortOrder").value(1))
                .andExpect(jsonPath("$[1].id").value(a))
                .andExpect(jsonPath("$[1].sortOrder").value(2))
                .andExpect(jsonPath("$[1].lessons[0].id").value(lesson))
                .andExpect(jsonPath("$[2].id").value(b))
                .andExpect(jsonPath("$[2].sortOrder").value(3));
        assertThat(sql.statements()).anyMatch(statement -> statement.toLowerCase().contains("for update"));

        assertThat(position("chapters", c)).isEqualTo(1);
        assertThat(position("chapters", a)).isEqualTo(2);
        assertThat(position("chapters", b)).isEqualTo(3);
        assertThat(events.stream(CourseContentChanged.class)).isEmpty();

        // again, back to the first order (the offset phase leaves no trace)
        mvc.perform(json(put(chaptersOf(course) + "/order"), instructor, ids(a, b, c))).andExpect(status().isOk());
        assertThat(sortOrders(course)).containsExactly(a, b, c);
        assertThat(positions("chapters", "course_id", course)).containsExactly(1, 2, 3);
    }

    @Test
    void reorder_notExactlyTheCoursesChapters_400_nothingChanges_UCI08() throws Exception {
        long a = fixtures.chapter(course, 1);
        long b = fixtures.chapter(course, 2);
        long otherCourse = fixtures.draftCourse(instructor.userId());
        long foreign = fixtures.chapter(otherCourse, 1);
        String order = chaptersOf(course) + "/order";

        expectField(mvc.perform(json(put(order), instructor, ids(b))), "ids"); // missing
        expectField(mvc.perform(json(put(order), instructor, ids(b, a, foreign))), "ids"); // extra, another course
        expectField(mvc.perform(json(put(order), instructor, ids(b, a, a))), "ids"); // duplicate
        expectField(mvc.perform(json(put(order), instructor, ids(b, a, Long.MAX_VALUE))), "ids"); // unknown
        expectField(mvc.perform(json(put(order), instructor, "{\"ids\": []}")), "ids");
        expectField(mvc.perform(json(put(order), instructor, "{}")), "ids");

        assertThat(sortOrders(course)).containsExactly(a, b);
        assertThat(position("chapters", foreign)).isEqualTo(1);
    }

    /** P10 MINOR-5: at most 500 ids, refused by Bean Validation before any query or lock. */
    @Test
    void reorder_moreThan500Ids_400_beforeAnyLookup() throws Exception {
        long a = fixtures.chapter(course, 1);
        long[] many = java.util.stream.LongStream.rangeClosed(1, 501).toArray();

        MvcResult result = expectProblem(mvc.perform(json(put(chaptersOf(course) + "/order"), instructor,
                ids(many))), 400, "VALIDATION_FAILED");
        jsonPath("$.errors[*].field", org.hamcrest.Matchers.hasItem("ids")).match(result);
        jsonPath("$.errors[*].message", org.hamcrest.Matchers.hasItem(
                "Danh sách sắp xếp tối đa 500 phần tử")).match(result);
        // exactly 500 distinct ids pass validation (then fail the permutation check of the service)
        expectField(mvc.perform(json(put(chaptersOf(course) + "/order"), instructor,
                ids(java.util.stream.LongStream.rangeClosed(1, 500).toArray()))), "ids");
        assertThat(sortOrders(course)).containsExactly(a);
    }

    // ------------------------------------------------------------------------------------------------ D-14 events

    @ParameterizedTest
    @ValueSource(strings = {"published", "hidden"})
    void everyChange_ofPublishedOrHiddenCourse_publishesCourseContentChanged_D14(String courseStatus)
            throws Exception {
        long a = fixtures.chapter(course, 1);
        fixtures.lesson(a, 1);
        long b = fixtures.chapter(course, 2);
        fixtures.setStatus(course, courseStatus, true);

        long created = idOf(mvc.perform(json(post(chaptersOf(course)), instructor, "{\"title\": \"Mới\"}"))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(json(patch(BASE + "/chapters/" + b), instructor, "{\"title\": \"Đổi tên\"}"))
                .andExpect(status().isOk());
        mvc.perform(json(put(chaptersOf(course) + "/order"), instructor, ids(created, b, a)))
                .andExpect(status().isOk());
        mvc.perform(delete(BASE + "/chapters/" + b).header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(status().isNoContent());

        assertThat(events.stream(CourseContentChanged.class)).hasSize(4)
                .containsOnly(new CourseContentChanged(course, slugOf(course)));
    }

    @Test
    void rejectedCourse_editable_noEvent() throws Exception {
        long a = fixtures.chapter(course, 1);
        fixtures.setStatus(course, "rejected", false);

        mvc.perform(json(post(chaptersOf(course)), instructor, "{\"title\": \"Mới\"}")).andExpect(status().isCreated());
        mvc.perform(json(patch(BASE + "/chapters/" + a), instructor, "{\"title\": \"Sửa\"}")).andExpect(status().isOk());

        assertThat(events.stream(CourseContentChanged.class)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ access

    @Test
    void acceptedCoInstructor_canEditEverything_BR04() throws Exception {
        Session co = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        fixtures.coInstructor(course, co.userId(), instructor.userId(), "accepted");
        long a = fixtures.chapter(course, 1);

        long created = idOf(mvc.perform(json(post(chaptersOf(course)), co, "{\"title\": \"Của đồng GV\"}"))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(json(patch(BASE + "/chapters/" + a), co, "{\"title\": \"Sửa\"}")).andExpect(status().isOk());
        mvc.perform(json(put(chaptersOf(course) + "/order"), co, ids(created, a))).andExpect(status().isOk());
        mvc.perform(delete(BASE + "/chapters/" + a).header(HttpHeaders.AUTHORIZATION, co.bearer()))
                .andExpect(status().isNoContent());

        assertThat(sortOrders(course)).containsExactly(created);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "declined", "removed"})
    void coInstructorNotAccepted_404OnEveryEndpoint_BR04(String invitation) throws Exception {
        Session co = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        fixtures.coInstructor(course, co.userId(), instructor.userId(), invitation);
        long a = fixtures.chapter(course, 1);

        for (MockHttpServletRequestBuilder request : everyEndpoint(co, course, a)) {
            expectProblem(mvc.perform(request), 404, "RESOURCE_NOT_FOUND");
        }
        assertThat(jdbc.sql("SELECT title FROM chapters WHERE id = ?").param(a).query(String.class).single())
                .isEqualTo("Chương 1");
    }

    @Test
    void otherInstructor_404IdenticalToMissing_onEveryEndpoint_NFR01() throws Exception {
        long a = fixtures.chapter(course, 1);
        Session other = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        List<MockHttpServletRequestBuilder> others = everyEndpoint(other, course, a);
        List<MockHttpServletRequestBuilder> missing = everyEndpoint(other, Long.MAX_VALUE, Long.MAX_VALUE);

        for (int i = 0; i < others.size(); i++) {
            IdorAssertions.assertSameNotFound(mvc, others.get(i), missing.get(i));
        }
        assertThat(sortOrders(course)).containsExactly(a);
    }

    @Test
    void revokedInstructor_403OnEveryEndpoint_BR26() throws Exception {
        // a fresh user: no request before the revoke, so the active-roles cache is cold
        TestUser revoked = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        long own = fixtures.draftCourse(revoked.id());
        long a = fixtures.chapter(own, 1);
        users.revokeRole(revoked.id(), RoleCode.INSTRUCTOR);
        Session stale = tokens.session(revoked); // the JWT still claims instructor

        for (MockHttpServletRequestBuilder request : everyEndpoint(stale, own, a)) {
            expectProblem(mvc.perform(request), 403, "ACCESS_DENIED");
        }
        assertThat(count("chapters", "course_id", own)).isOne();
    }

    @Test
    void studentOnly_403_withoutToken_401() throws Exception {
        Session student = tokens.newUserSession(RoleCode.STUDENT);
        long a = fixtures.chapter(course, 1);

        for (MockHttpServletRequestBuilder request : everyEndpoint(student, course, a)) {
            expectProblem(mvc.perform(request), 403, "ACCESS_DENIED");
        }
        expectProblem(mvc.perform(post(chaptersOf(course)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"X\"}")), 401, "UNAUTHENTICATED");
    }

    @Test
    void pendingReview_409CourseNotEditable_onEveryEndpoint_BR33() throws Exception {
        long a = fixtures.chapter(course, 1);
        fixtures.lesson(a, 1);
        fixtures.markPendingReview(course);

        for (MockHttpServletRequestBuilder request : everyEndpoint(instructor, course, a)) {
            expectProblem(mvc.perform(request), 409, "COURSE_NOT_EDITABLE");
        }
        assertThat(sortOrders(course)).containsExactly(a);
        assertThat(jdbc.sql("SELECT title FROM chapters WHERE id = ?").param(a).query(String.class).single())
                .isEqualTo("Chương 1");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** POST, PATCH, DELETE, PUT order — with valid bodies — as {@code caller}. */
    private List<MockHttpServletRequestBuilder> everyEndpoint(Session caller, long courseId, long chapterId) {
        return List.of(
                json(post(chaptersOf(courseId)), caller, "{\"title\": \"X\"}"),
                json(patch(BASE + "/chapters/" + chapterId), caller, "{\"title\": \"X\"}"),
                delete(BASE + "/chapters/" + chapterId).header(HttpHeaders.AUTHORIZATION, caller.bearer()),
                json(put(chaptersOf(courseId) + "/order"), caller, ids(chapterId)));
    }

    private static String chaptersOf(long courseId) {
        return BASE + "/courses/" + courseId + "/chapters";
    }

    private static String ids(long... ids) {
        StringBuilder body = new StringBuilder("{\"ids\": [");
        for (int i = 0; i < ids.length; i++) {
            body.append(i == 0 ? "" : ", ").append(ids[i]);
        }
        return body.append("]}").toString();
    }

    private static long idOf(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id"))
                .longValue();
    }

    /** Chapter ids of the course in display order. */
    private List<Long> sortOrders(long courseId) {
        return jdbc.sql("SELECT id FROM chapters WHERE course_id = ? ORDER BY sort_order").param(courseId)
                .query(Long.class).list();
    }

    private List<Integer> positions(String table, String parentColumn, long parentId) {
        return jdbc.sql("SELECT sort_order FROM " + table + " WHERE " + parentColumn + " = ? ORDER BY sort_order")
                .param(parentId).query(Integer.class).list();
    }

    private int position(String table, long id) {
        return jdbc.sql("SELECT sort_order FROM " + table + " WHERE id = ?").param(id).query(Integer.class).single();
    }

    private String slugOf(long courseId) {
        return jdbc.sql("SELECT slug FROM courses WHERE id = ?").param(courseId).query(String.class).single();
    }

    private long count(String table, String column, long value) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?").param(value)
                .query(Long.class).single();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Session caller,
                                                      String body) {
        return request.header(HttpHeaders.AUTHORIZATION, caller.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static MvcResult expectField(ResultActions actions, String field) throws Exception {
        MvcResult result = expectProblem(actions, 400, "VALIDATION_FAILED");
        jsonPath("$.errors[*].field", hasItem(field)).match(result);
        return result;
    }
}
