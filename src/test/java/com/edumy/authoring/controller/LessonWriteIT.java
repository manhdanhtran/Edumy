package com.edumy.authoring.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.edumy.authoring.api.CourseContentChanged;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.IdorAssertions;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.LockWaits;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Lesson writes (UC-I09 create, I10 update, I11 delete, I12 reorder) through the real filter chain and MySQL: D-22
 * (content matches the type — a mismatch is 400, never the 500 of ck_lessons_content_match), append at the end,
 * positions kept 1..n, two-phase reorder, D-14 (the last lesson of a published / hidden course stays, events only for
 * those courses), UC-I11 (progress deleted, questions become course-level), BR-04/26/33, IDOR (NFR-01).
 */
@MySqlSpringBootTest
@RecordApplicationEvents
class LessonWriteIT {

    static final String BASE = "/api/v1/instructor";
    static final String VIDEO = "https://cdn.edumy.vn/v.mp4";

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
    PlatformTransactionManager txManager;
    @Autowired
    SqlStatementCounter sql;

    CourseFixtures fixtures;
    Session instructor;
    long course;
    long chapter;
    final List<Long> enrollments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        course = fixtures.draftCourse(instructor.userId());
        chapter = fixtures.chapter(course, 1);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : enrollments) { // fk_lesson_progresses_enrollment_id is RESTRICT
            jdbc.sql("DELETE FROM lesson_progresses WHERE enrollment_id = ?").param(id).update();
        }
        fixtures.deleteAll();
        users.deleteAll();
    }

    // ------------------------------------------------------------------------------------------------ UC-I09 create

    @Test
    void create_video_appendsAtEnd_201_UCI09() throws Exception {
        long first = fixtures.lesson(chapter, 1);

        sql.reset();
        MvcResult result = mvc.perform(json(post(lessonsOf(chapter)), instructor, """
                        {"title": "  Cài đặt JDK  ", "contentType": "video", "videoUrl": "%s", \
                        "durationSeconds": 600, "isPreview": true}""".formatted(VIDEO)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Cài đặt JDK"))
                .andExpect(jsonPath("$.contentType").value("video"))
                .andExpect(jsonPath("$.videoUrl").value(VIDEO))
                .andExpect(jsonPath("$.articleContent").value(nullValue()))
                .andExpect(jsonPath("$.durationSeconds").value(600))
                .andExpect(jsonPath("$.isPreview").value(true))
                .andExpect(jsonPath("$.sortOrder").value(2))
                .andReturn();
        assertThat(sql.statements()).anyMatch(statement -> statement.toLowerCase().contains("for update"));
        long id = idOf(result);

        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo(BASE + "/lessons/" + id);
        assertThat(lessonIds(chapter)).containsExactly(first, id);
        Map<String, Object> row = jdbc.sql("SELECT is_preview, duration_seconds FROM lessons WHERE id = ?")
                .param(id).query().singleRow();
        assertThat(row.get("is_preview")).isEqualTo(true);
        assertThat(events.stream(CourseContentChanged.class)).isEmpty();
    }

    @Test
    void create_article_previewDefaultsFalse_firstLessonPosition1_UCI09() throws Exception {
        long empty = fixtures.chapter(course, 2);

        mvc.perform(json(post(lessonsOf(empty)), instructor,
                        "{\"title\": \"Đọc thêm\", \"contentType\": \"article\", \"articleContent\": \"<p>Nội dung</p>\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("article"))
                .andExpect(jsonPath("$.articleContent").value("<p>Nội dung</p>"))
                .andExpect(jsonPath("$.videoUrl").value(nullValue()))
                .andExpect(jsonPath("$.durationSeconds").value(nullValue()))
                .andExpect(jsonPath("$.isPreview").value(false))
                .andExpect(jsonPath("$.sortOrder").value(1));
    }

    @Test
    void create_withoutContentYet_201_contentCheckedAtSubmit_BR07() throws Exception {
        mvc.perform(json(post(lessonsOf(chapter)), instructor, "{\"title\": \"Sẽ quay sau\", \"contentType\": \"video\", "
                        + "\"videoUrl\": \"  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.videoUrl").value(nullValue()));
    }

    /**
     * P10 DT-B1 (lost update): a PATCH waiting for the course lock held by another committed edit must apply its own
     * field on top of that edit (READ COMMITTED, ADR-010), never write back the lesson as it was before the wait.
     */
    @Test
    void patch_racingAnotherEditThatHoldsTheCourseLock_keepsTheOtherEdit_noLostUpdate() throws Exception {
        long a = fixtures.lesson(chapter, 1);

        MvcResult result = LockWaits.whileLockedThenCommit(txManager, jdbc, db -> {
            db.sql("SELECT id FROM courses WHERE id = ? FOR UPDATE").param(course).query(Long.class).single();
            db.sql("UPDATE lessons SET title = 'Sửa bởi đồng giảng viên' WHERE id = ?").param(a).update();
        }, () -> mvc.perform(json(patch(BASE + "/lessons/" + a), instructor, "{\"isPreview\": true}")).andReturn());

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT CONCAT(title, '|', is_preview) FROM lessons WHERE id = ?").param(a)
                .query(String.class).single()).isEqualTo("Sửa bởi đồng giảng viên|1");
    }

    /** P10 Q-h: a published / hidden course changes at once (D-14), so its lessons always have content (BR-07). */
    @ParameterizedTest
    @ValueSource(strings = {"published", "hidden"})
    void create_withoutContent_onPublicCourse_422LessonContentRequired_nothingWritten_Qh(String courseStatus)
            throws Exception {
        long a = fixtures.lesson(chapter, 1);
        fixtures.setStatus(course, courseStatus, true);

        expectContentRequired(mvc.perform(json(post(lessonsOf(chapter)), instructor,
                "{\"title\": \"Sẽ quay sau\", \"contentType\": \"video\", \"videoUrl\": \"  \"}")), "videoUrl");
        expectContentRequired(mvc.perform(json(post(lessonsOf(chapter)), instructor,
                "{\"title\": \"Bài viết\", \"contentType\": \"article\"}")), "articleContent");
        // update: clearing the content, or switching the type without the new type's content
        expectContentRequired(mvc.perform(json(patch(BASE + "/lessons/" + a), instructor, "{\"videoUrl\": \"\"}")),
                "videoUrl");
        expectContentRequired(mvc.perform(json(patch(BASE + "/lessons/" + a), instructor,
                "{\"contentType\": \"article\"}")), "articleContent");

        assertThat(lessonIds(chapter)).containsExactly(a);
        assertThat(jdbc.sql("SELECT CONCAT(content_type, '|', video_url) FROM lessons WHERE id = ?").param(a)
                .query(String.class).single()).isEqualTo("video|" + VIDEO);
        assertThat(events.stream(CourseContentChanged.class)).isEmpty();
        // with content it works, and a type switch carrying the new content too
        mvc.perform(json(post(lessonsOf(chapter)), instructor,
                "{\"title\": \"Bài viết\", \"contentType\": \"article\", \"articleContent\": \"Nội dung\"}"))
                .andExpect(status().isCreated());
        mvc.perform(json(patch(BASE + "/lessons/" + a), instructor,
                "{\"contentType\": \"article\", \"articleContent\": \"Nội dung mới\"}")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "rejected"})
    void updateClearingContent_onDraftOrRejectedCourse_allowed_checkedAtSubmit_Qh(String courseStatus)
            throws Exception {
        long a = fixtures.lesson(chapter, 1);
        fixtures.setStatus(course, courseStatus, false);

        mvc.perform(json(patch(BASE + "/lessons/" + a), instructor, "{\"videoUrl\": \"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoUrl").value(nullValue()));
    }

    private static void expectContentRequired(ResultActions actions, String field) throws Exception {
        MvcResult result = expectProblem(actions, 422, "LESSON_CONTENT_REQUIRED");
        jsonPath("$.errors[*].field", hasItem(field)).match(result);
    }

    @Test
    void create_contentOfTheOtherType_400_never500_D22() throws Exception {
        expectField(mvc.perform(json(post(lessonsOf(chapter)), instructor,
                "{\"title\": \"T\", \"contentType\": \"video\", \"videoUrl\": \"%s\", \"articleContent\": \"x\"}"
                        .formatted(VIDEO))), "articleContent");
        expectField(mvc.perform(json(post(lessonsOf(chapter)), instructor,
                "{\"title\": \"T\", \"contentType\": \"article\", \"videoUrl\": \"%s\"}".formatted(VIDEO))),
                "videoUrl");
        assertThat(count("lessons", "chapter_id", chapter)).isZero();
    }

    @Test
    void create_invalidInput_400() throws Exception {
        String url = lessonsOf(chapter);
        expectField(mvc.perform(json(post(url), instructor, "{\"contentType\": \"video\"}")), "title");
        expectField(mvc.perform(json(post(url), instructor, "{\"title\": \"%s\", \"contentType\": \"video\"}"
                .formatted("a".repeat(201)))), "title");
        expectField(mvc.perform(json(post(url), instructor, "{\"title\": \"T\"}")), "contentType");
        expectField(mvc.perform(json(post(url), instructor, "{\"title\": \"T\", \"contentType\": \"audio\"}")),
                "contentType");
        expectField(mvc.perform(json(post(url), instructor, "{\"title\": \"T\", \"contentType\": \"VIDEO\"}")),
                "contentType");
        expectField(mvc.perform(json(post(url), instructor,
                "{\"title\": \"T\", \"contentType\": \"video\", \"videoUrl\": \"ftp://x\"}")), "videoUrl");
        expectField(mvc.perform(json(post(url), instructor,
                "{\"title\": \"T\", \"contentType\": \"video\", \"durationSeconds\": -1}")), "durationSeconds");
        assertThat(count("lessons", "chapter_id", chapter)).isZero();
    }

    // ------------------------------------------------------------------------------------------------ UC-I10 update

    @Test
    void update_onlySentFieldsChange_UCI10() throws Exception {
        long lesson = fixtures.lesson(chapter, 1);

        sql.reset();
        mvc.perform(json(patch(BASE + "/lessons/" + lesson), instructor, "{\"title\": \"Tiêu đề mới\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(lesson))
                .andExpect(jsonPath("$.title").value("Tiêu đề mới"))
                .andExpect(jsonPath("$.contentType").value("video"))
                .andExpect(jsonPath("$.videoUrl").value(VIDEO))
                .andExpect(jsonPath("$.isPreview").value(false))
                .andExpect(jsonPath("$.sortOrder").value(1));
        assertThat(sql.statements()).anyMatch(statement -> statement.toLowerCase().contains("for update"));

        mvc.perform(json(patch(BASE + "/lessons/" + lesson), instructor,
                        "{\"durationSeconds\": 0, \"isPreview\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Tiêu đề mới"))
                .andExpect(jsonPath("$.durationSeconds").value(0))
                .andExpect(jsonPath("$.isPreview").value(true));
        mvc.perform(json(patch(BASE + "/lessons/" + lesson), instructor, "{\"videoUrl\": \"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoUrl").value(nullValue()));
        assertThat(events.stream(CourseContentChanged.class)).isEmpty();
    }

    @Test
    void update_typeChange_dropsOldContent_D22() throws Exception {
        long lesson = fixtures.lesson(chapter, 1);

        mvc.perform(json(patch(BASE + "/lessons/" + lesson), instructor,
                        "{\"contentType\": \"article\", \"articleContent\": \"Bài viết\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("article"))
                .andExpect(jsonPath("$.videoUrl").value(nullValue()))
                .andExpect(jsonPath("$.articleContent").value("Bài viết"));

        mvc.perform(json(patch(BASE + "/lessons/" + lesson), instructor, "{\"contentType\": \"video\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("video"))
                .andExpect(jsonPath("$.articleContent").value(nullValue()))
                .andExpect(jsonPath("$.videoUrl").value(nullValue()));
    }

    @Test
    void update_mismatchOrInvalid_400_never500_lessonUnchanged_D22() throws Exception {
        long lesson = fixtures.lesson(chapter, 1);
        String url = BASE + "/lessons/" + lesson;

        expectField(mvc.perform(json(patch(url), instructor, "{\"articleContent\": \"x\"}")), "articleContent");
        expectField(mvc.perform(json(patch(url), instructor, "{\"contentType\": \"article\", \"videoUrl\": \"%s\"}"
                .formatted(VIDEO))), "videoUrl");
        expectField(mvc.perform(json(patch(url), instructor, "{\"title\": \"   \"}")), "title");
        expectField(mvc.perform(json(patch(url), instructor, "{\"contentType\": \"quiz\"}")), "contentType");
        expectField(mvc.perform(json(patch(url), instructor, "{\"durationSeconds\": -5}")), "durationSeconds");

        Map<String, Object> row = jdbc.sql("SELECT title, content_type, video_url FROM lessons WHERE id = ?")
                .param(lesson).query().singleRow();
        assertThat(row).containsEntry("title", "Bài 1").containsEntry("content_type", "video")
                .containsEntry("video_url", VIDEO);
    }

    // ------------------------------------------------------------------------------------------------ UC-I11 delete

    @Test
    void delete_204_followingMoveUp_progressGoes_questionBecomesCourseLevel_UCI11() throws Exception {
        long l1 = fixtures.lesson(chapter, 1);
        long l2 = fixtures.lesson(chapter, 2);
        long l3 = fixtures.lesson(chapter, 3);
        Session student = tokens.newUserSession(RoleCode.STUDENT);
        long enrollment = fixtures.enrollment(student.userId(), course);
        enrollments.add(enrollment);
        jdbc.sql("INSERT INTO lesson_progresses (enrollment_id, lesson_id) VALUES (?, ?)")
                .params(enrollment, l2).update();
        long question = fixtures.question(enrollment);
        jdbc.sql("UPDATE course_questions SET lesson_id = ? WHERE id = ?").params(l2, question).update();

        mvc.perform(delete(BASE + "/lessons/" + l2).header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(status().isNoContent());

        assertThat(count("lessons", "id", l2)).isZero();
        assertThat(lessonIds(chapter)).containsExactly(l1, l3);
        assertThat(positions(chapter)).containsExactly(1, 2);
        assertThat(count("lesson_progresses", "lesson_id", l2)).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM course_questions WHERE id = ? AND lesson_id IS NULL").param(question)
                .query(Long.class).single()).as("the question stays, as a course-level question").isOne();
        assertThat(events.stream(CourseContentChanged.class)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"published", "hidden"})
    void delete_onlyLessonOfPublishedOrHiddenCourse_409_D14(String courseStatus) throws Exception {
        long only = fixtures.lesson(chapter, 1);
        fixtures.setStatus(course, courseStatus, true);

        expectProblem(mvc.perform(delete(BASE + "/lessons/" + only)
                .header(HttpHeaders.AUTHORIZATION, instructor.bearer())), 409, "COURSE_LAST_LESSON_DELETE_FORBIDDEN");

        assertThat(count("lessons", "id", only)).isOne();
        assertThat(events.stream(CourseContentChanged.class)).isEmpty();
    }

    @Test
    void delete_publishedWithAnotherLesson_204_event_D14() throws Exception {
        long l1 = fixtures.lesson(chapter, 1);
        long other = fixtures.chapter(course, 2);
        long l2 = fixtures.lesson(other, 1);
        fixtures.setStatus(course, "published", true);

        mvc.perform(delete(BASE + "/lessons/" + l1).header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(status().isNoContent());
        expectProblem(mvc.perform(delete(BASE + "/lessons/" + l2)
                .header(HttpHeaders.AUTHORIZATION, instructor.bearer())), 409, "COURSE_LAST_LESSON_DELETE_FORBIDDEN");

        assertThat(events.stream(CourseContentChanged.class)).containsExactly(
                new CourseContentChanged(course, slugOf(course)));
    }

    @Test
    void delete_lastLessonOfDraftCourse_204() throws Exception {
        long only = fixtures.lesson(chapter, 1);

        mvc.perform(delete(BASE + "/lessons/" + only).header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(status().isNoContent());
        assertThat(count("lessons", "chapter_id", chapter)).isZero();
    }

    // ------------------------------------------------------------------------------------------------ UC-I12 reorder

    @Test
    void reorder_validPermutation_200_positions1toN_UCI12() throws Exception {
        long a = fixtures.lesson(chapter, 1);
        long b = fixtures.lesson(chapter, 2);
        long c = fixtures.lesson(chapter, 3);

        sql.reset();
        mvc.perform(json(put(lessonsOf(chapter) + "/order"), instructor, ids(b, c, a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(chapter))
                .andExpect(jsonPath("$.lessons[0].id").value(b))
                .andExpect(jsonPath("$.lessons[0].sortOrder").value(1))
                .andExpect(jsonPath("$.lessons[1].id").value(c))
                .andExpect(jsonPath("$.lessons[1].sortOrder").value(2))
                .andExpect(jsonPath("$.lessons[2].id").value(a))
                .andExpect(jsonPath("$.lessons[2].sortOrder").value(3));
        assertThat(sql.statements()).anyMatch(statement -> statement.toLowerCase().contains("for update"));

        assertThat(position(b)).isEqualTo(1);
        assertThat(position(c)).isEqualTo(2);
        assertThat(position(a)).isEqualTo(3);
        assertThat(events.stream(CourseContentChanged.class)).isEmpty();
    }

    @Test
    void reorder_notExactlyTheChaptersLessons_400_nothingChanges_UCI12() throws Exception {
        long a = fixtures.lesson(chapter, 1);
        long b = fixtures.lesson(chapter, 2);
        long sibling = fixtures.chapter(course, 2);
        long foreign = fixtures.lesson(sibling, 1); // same course, other chapter
        String order = lessonsOf(chapter) + "/order";

        expectField(mvc.perform(json(put(order), instructor, ids(b))), "ids");
        expectField(mvc.perform(json(put(order), instructor, ids(b, a, foreign))), "ids");
        expectField(mvc.perform(json(put(order), instructor, ids(b, b, a))), "ids");
        expectField(mvc.perform(json(put(order), instructor, "{\"ids\": [1, null]}")), "ids[1]");

        assertThat(lessonIds(chapter)).containsExactly(a, b);
        assertThat(position(foreign)).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------------ D-14 events

    @ParameterizedTest
    @ValueSource(strings = {"published", "hidden"})
    void everyChange_ofPublishedOrHiddenCourse_publishesCourseContentChanged_D14(String courseStatus)
            throws Exception {
        long a = fixtures.lesson(chapter, 1);
        fixtures.setStatus(course, courseStatus, true);

        long created = idOf(mvc.perform(json(post(lessonsOf(chapter)), instructor,
                        "{\"title\": \"Mới\", \"contentType\": \"video\", \"videoUrl\": \"%s\"}".formatted(VIDEO)))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(json(patch(BASE + "/lessons/" + a), instructor, "{\"isPreview\": true}"))
                .andExpect(status().isOk());
        mvc.perform(json(put(lessonsOf(chapter) + "/order"), instructor, ids(created, a)))
                .andExpect(status().isOk());
        mvc.perform(delete(BASE + "/lessons/" + a).header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(status().isNoContent());

        assertThat(events.stream(CourseContentChanged.class)).hasSize(4)
                .containsOnly(new CourseContentChanged(course, slugOf(course)));
    }

    @Test
    void rejectedCourse_editable_noEvent() throws Exception {
        long a = fixtures.lesson(chapter, 1);
        fixtures.setStatus(course, "rejected", false);

        mvc.perform(json(patch(BASE + "/lessons/" + a), instructor, "{\"title\": \"Sửa\"}"))
                .andExpect(status().isOk());

        assertThat(events.stream(CourseContentChanged.class)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ access

    @Test
    void acceptedCoInstructor_canEditEverything_BR04() throws Exception {
        Session co = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        fixtures.coInstructor(course, co.userId(), instructor.userId(), "accepted");
        long a = fixtures.lesson(chapter, 1);

        long created = idOf(mvc.perform(json(post(lessonsOf(chapter)), co,
                        "{\"title\": \"Của đồng GV\", \"contentType\": \"article\"}"))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(json(patch(BASE + "/lessons/" + a), co, "{\"title\": \"Sửa\"}")).andExpect(status().isOk());
        mvc.perform(json(put(lessonsOf(chapter) + "/order"), co, ids(created, a))).andExpect(status().isOk());
        mvc.perform(delete(BASE + "/lessons/" + a).header(HttpHeaders.AUTHORIZATION, co.bearer()))
                .andExpect(status().isNoContent());

        assertThat(lessonIds(chapter)).containsExactly(created);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "declined", "removed"})
    void coInstructorNotAccepted_404OnEveryEndpoint_BR04(String invitation) throws Exception {
        Session co = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        fixtures.coInstructor(course, co.userId(), instructor.userId(), invitation);
        long a = fixtures.lesson(chapter, 1);

        for (MockHttpServletRequestBuilder request : everyEndpoint(co, chapter, a)) {
            expectProblem(mvc.perform(request), 404, "RESOURCE_NOT_FOUND");
        }
        assertThat(lessonIds(chapter)).containsExactly(a);
    }

    @Test
    void otherInstructor_404IdenticalToMissing_onEveryEndpoint_NFR01() throws Exception {
        long a = fixtures.lesson(chapter, 1);
        Session other = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        List<MockHttpServletRequestBuilder> others = everyEndpoint(other, chapter, a);
        List<MockHttpServletRequestBuilder> missing = everyEndpoint(other, Long.MAX_VALUE, Long.MAX_VALUE);

        for (int i = 0; i < others.size(); i++) {
            IdorAssertions.assertSameNotFound(mvc, others.get(i), missing.get(i));
        }
        assertThat(lessonIds(chapter)).containsExactly(a);
    }

    @Test
    void revokedInstructor_403OnEveryEndpoint_BR26() throws Exception {
        TestUser revoked = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        long own = fixtures.draftCourse(revoked.id());
        long ownChapter = fixtures.chapter(own, 1);
        long a = fixtures.lesson(ownChapter, 1);
        users.revokeRole(revoked.id(), RoleCode.INSTRUCTOR);
        Session stale = tokens.session(revoked);

        for (MockHttpServletRequestBuilder request : everyEndpoint(stale, ownChapter, a)) {
            expectProblem(mvc.perform(request), 403, "ACCESS_DENIED");
        }
        assertThat(lessonIds(ownChapter)).containsExactly(a);
    }

    @Test
    void studentOnly_403_withoutToken_401() throws Exception {
        Session student = tokens.newUserSession(RoleCode.STUDENT);
        long a = fixtures.lesson(chapter, 1);

        for (MockHttpServletRequestBuilder request : everyEndpoint(student, chapter, a)) {
            expectProblem(mvc.perform(request), 403, "ACCESS_DENIED");
        }
        expectProblem(mvc.perform(delete(BASE + "/lessons/" + a)), 401, "UNAUTHENTICATED");
    }

    @Test
    void pendingReview_409CourseNotEditable_onEveryEndpoint_BR33() throws Exception {
        long a = fixtures.lesson(chapter, 1);
        fixtures.markPendingReview(course);

        for (MockHttpServletRequestBuilder request : everyEndpoint(instructor, chapter, a)) {
            expectProblem(mvc.perform(request), 409, "COURSE_NOT_EDITABLE");
        }
        assertThat(jdbc.sql("SELECT title FROM lessons WHERE id = ?").param(a).query(String.class).single())
                .isEqualTo("Bài 1");
        assertThat(lessonIds(chapter)).containsExactly(a);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** POST, PATCH, DELETE, PUT order — with valid bodies — as {@code caller}. */
    private List<MockHttpServletRequestBuilder> everyEndpoint(Session caller, long chapterId, long lessonId) {
        return List.of(
                json(post(lessonsOf(chapterId)), caller, "{\"title\": \"X\", \"contentType\": \"video\"}"),
                json(patch(BASE + "/lessons/" + lessonId), caller, "{\"title\": \"X\"}"),
                delete(BASE + "/lessons/" + lessonId).header(HttpHeaders.AUTHORIZATION, caller.bearer()),
                json(put(lessonsOf(chapterId) + "/order"), caller, ids(lessonId)));
    }

    private static String lessonsOf(long chapterId) {
        return BASE + "/chapters/" + chapterId + "/lessons";
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

    /** Lesson ids of the chapter in display order. */
    private List<Long> lessonIds(long chapterId) {
        return jdbc.sql("SELECT id FROM lessons WHERE chapter_id = ? ORDER BY sort_order").param(chapterId)
                .query(Long.class).list();
    }

    private List<Integer> positions(long chapterId) {
        return jdbc.sql("SELECT sort_order FROM lessons WHERE chapter_id = ? ORDER BY sort_order").param(chapterId)
                .query(Integer.class).list();
    }

    private int position(long lessonId) {
        return jdbc.sql("SELECT sort_order FROM lessons WHERE id = ?").param(lessonId).query(Integer.class).single();
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
