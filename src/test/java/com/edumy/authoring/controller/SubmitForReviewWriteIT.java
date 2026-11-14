package com.edumy.authoring.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.edumy.authoring.api.CourseApproved;
import com.edumy.authoring.api.CourseContentChanged;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.IdorAssertions;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MutableClock;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.SqlStatementCounter;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
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
 * UC-I17 submit for review through the real filter chain and MySQL: precondition draft / rejected (else 409
 * {@code COURSE_INVALID_TRANSITION}), the whole BR-07 checklist reported at once (E1, AC-20), BR-06 main instructor
 * only (E2, co-instructor → 404 like a stranger, NFR-01), BR-26 active instructor role, BR-33 read-only afterwards
 * (AC-27), BR-05 pending invitations do not matter (E3).
 */
@MySqlSpringBootTest
@RecordApplicationEvents
class SubmitForReviewWriteIT {

    static final String COURSES = "/api/v1/instructor/courses/";
    static final Instant NOW = Instant.parse("2026-11-14T03:15:00Z");

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
    Session main;
    long course;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        main = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        course = completeCourse(main.userId());
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    // ---------------------------------------------------------------------------------------------------- happy path

    @Test
    void submit_completeDraft_200PendingWithSubmittedAt_inAdminQueue_UCI17() throws Exception {
        clock.setInstant(NOW);
        TestUser invitee = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        fixtures.coInstructor(course, invitee.id(), main.userId(), "pending"); // E3 / BR-05: does not matter

        sql.reset();
        mvc.perform(submit(course, main))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(course))
                .andExpect(jsonPath("$.status").value("pending_review"))
                .andExpect(jsonPath("$.submittedAt").value(startsWith("2026-11-14T03:15:")))
                .andExpect(jsonPath("$.reviewedAt").value(nullValue()))
                .andExpect(jsonPath("$.reviewNote").value(nullValue()));
        assertThat(sql.statements()).as("the course row is locked")
                .anyMatch(statement -> statement.toLowerCase().contains("for update"));

        assertThat(courseStatus(course)).isEqualTo("pending_review");
        assertThat(events.stream(CourseContentChanged.class)).isEmpty();
        assertThat(events.stream(CourseApproved.class)).isEmpty();

        // UC-I17 step 3: the course is in the admin queue
        Session admin = tokens.newUserSession(RoleCode.ADMIN);
        mvc.perform(get("/api/v1/admin/courses/pending").param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + course + ")].submittedAt",
                        contains(startsWith("2026-11-14T03:15:"))));
    }

    @Test
    void submit_rejected_200Pending_lastReasonKept_UCA11ResubmitUCI17() throws Exception {
        fixtures.setStatus(course, "rejected", false);

        mvc.perform(submit(course, main))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending_review"))
                .andExpect(jsonPath("$.reviewNote").value("Lý do"));

        assertThat(courseStatus(course)).isEqualTo("pending_review");
    }

    @Test
    void submit_articleLessonWithContent_andPaidPrice_passes_BR07() throws Exception {
        jdbc.sql("UPDATE courses SET price = 499000 WHERE id = ?").param(course).update();
        long chapter = fixtures.chapter(course, 2);
        jdbc.sql("""
                        INSERT INTO lessons (chapter_id, title, content_type, article_content, sort_order)
                        VALUES (?, 'Bài viết', 'article', 'Nội dung bài viết', 1)""")
                .param(chapter).update();

        mvc.perform(submit(course, main)).andExpect(status().isOk());
    }

    /** BR-33 / AC-27: after the submission every edit is refused until the admin decides. */
    @Test
    void afterSubmit_courseIsReadOnly_409_BR33() throws Exception {
        mvc.perform(submit(course, main)).andExpect(status().isOk());

        expectProblem(mvc.perform(patch(COURSES + course).header(HttpHeaders.AUTHORIZATION, main.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"Mới\"}")), 409, "COURSE_NOT_EDITABLE");
    }

    // ------------------------------------------------------------------------------------------- checklist (BR-07)

    /** E1 / AC-20: every failed item at once, in a stable order; the status does not change. */
    @Test
    void submit_severalItemsFail_422ListsEveryItem_statusUnchanged_BR07_AC20() throws Exception {
        jdbc.sql("UPDATE courses SET title = '   ', description = NULL WHERE id = ?").param(course).update();
        deactivateCategory(course);
        long empty = fixtures.chapter(course, 2);
        long lessonWithoutContent = fixtures.lesson(fixtures.chapter(course, 3));
        jdbc.sql("UPDATE lessons SET video_url = NULL WHERE id = ?").param(lessonWithoutContent).update();
        assertThat(empty).isPositive();

        mvc.perform(submit(course, main))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("COURSE_SUBMIT_CHECKLIST_FAILED"))
                .andExpect(jsonPath("$.errors[*].field",
                        contains("title", "description", "categoryId", "emptyChapters", "lessonContent")))
                .andExpect(jsonPath("$.errors[3].message").value("Có 1 chương chưa có bài học"))
                .andExpect(jsonPath("$.errors[4].message").value(startsWith(
                        "Có 1 bài học chưa có nội dung")));

        assertThat(courseStatus(course)).isEqualTo("draft");
        assertThat(jdbc.sql("SELECT submitted_at IS NULL FROM courses WHERE id = ?").param(course)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void submit_noChapterNoDescriptionInactiveCategory_422_chaptersReportedAlone_BR07() throws Exception {
        long bare = fixtures.draftCourse(main.userId());
        deactivateCategory(bare);

        mvc.perform(submit(bare, main))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("COURSE_SUBMIT_CHECKLIST_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", contains("description", "categoryId", "chapters")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "description", "categoryId", "chapters", "emptyChapters", "lessonContent",
            "articleContent"})
    void submit_oneItemFails_422WithThatItemOnly_BR07(String item) throws Exception {
        long target = course;
        String expected = item;
        switch (item) {
            case "title" -> jdbc.sql("UPDATE courses SET title = ' ' WHERE id = ?").param(course).update();
            case "description" -> jdbc.sql("UPDATE courses SET description = NULL WHERE id = ?").param(course).update();
            case "categoryId" -> deactivateCategory(course);
            case "chapters" -> {
                target = fixtures.draftCourse(main.userId());
                describe(target);
            }
            case "emptyChapters" -> fixtures.chapter(course, 2);
            case "lessonContent" -> jdbc.sql("""
                            UPDATE lessons SET video_url = NULL
                            WHERE chapter_id IN (SELECT id FROM chapters WHERE course_id = ?)""")
                    .param(course).update();
            case "articleContent" -> {
                expected = "lessonContent";
                jdbc.sql("""
                                INSERT INTO lessons (chapter_id, title, content_type, sort_order)
                                VALUES ((SELECT id FROM chapters WHERE course_id = ?), 'Bài viết trống', 'article', 2)""")
                        .param(course).update();
            }
            default -> throw new IllegalArgumentException(item);
        }

        mvc.perform(submit(target, main))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("COURSE_SUBMIT_CHECKLIST_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", contains(expected)))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
        assertThat(courseStatus(target)).isEqualTo("draft");
    }

    // ----------------------------------------------------------------------------------------------- wrong states

    @ParameterizedTest
    @ValueSource(strings = {"pending_review", "published", "hidden"})
    void submit_notDraftOrRejected_409_statusUnchanged_UCI17(String state) throws Exception {
        fixtures.setStatus(course, state, false);

        expectProblem(mvc.perform(submit(course, main)), 409, "COURSE_INVALID_TRANSITION");

        assertThat(courseStatus(course)).isEqualTo(state);
    }

    /** The state is checked before the checklist: an incomplete published course still gets 409, not 422. */
    @Test
    void submit_incompletePublished_409NotChecklist() throws Exception {
        jdbc.sql("UPDATE courses SET description = NULL WHERE id = ?").param(course).update();
        fixtures.setStatus(course, "published", false);

        expectProblem(mvc.perform(submit(course, main)), 409, "COURSE_INVALID_TRANSITION");
    }

    // ------------------------------------------------------------------------------------------------ authorization

    /** E2 / BR-06: an accepted co-instructor gets the same 404 as a stranger and an unknown id (NFR-01). */
    @Test
    void submit_coInstructorOrStranger_404Identical_statusUnchanged_BR06() throws Exception {
        Session co = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        fixtures.coInstructor(course, co.userId(), main.userId(), "accepted");
        Session stranger = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);

        IdorAssertions.assertSameNotFound(mvc, submit(course, co), submit(Long.MAX_VALUE, co));
        IdorAssertions.assertSameNotFound(mvc, submit(course, stranger), submit(Long.MAX_VALUE, stranger));
        assertThat(courseStatus(course)).isEqualTo("draft");
    }

    @Test
    void submit_revokedMainWithStaleJwt_403_student_403_withoutToken_401_BR26() throws Exception {
        TestUser revoked = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        long own = completeCourse(revoked.id());
        users.revokeRole(revoked.id(), RoleCode.INSTRUCTOR);
        Session student = tokens.newUserSession(RoleCode.STUDENT);

        expectProblem(mvc.perform(submit(own, tokens.session(revoked))), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(submit(course, student)), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(post(COURSES + course + "/submit")), 401, "UNAUTHENTICATED");
        assertThat(courseStatus(own)).isEqualTo("draft");
        assertThat(courseStatus(course)).isEqualTo("draft");
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /** A draft course passing BR-07: description, active category, free, one chapter with one video lesson. */
    private long completeCourse(long mainInstructorId) {
        long id = fixtures.draftCourse(mainInstructorId);
        describe(id);
        fixtures.lesson(fixtures.chapter(id));
        return id;
    }

    private void describe(long courseId) {
        jdbc.sql("UPDATE courses SET description = 'Mô tả khóa học' WHERE id = ?").param(courseId).update();
    }

    private void deactivateCategory(long courseId) {
        jdbc.sql("UPDATE categories SET is_active = FALSE WHERE id = (SELECT category_id FROM courses WHERE id = ?)")
                .param(courseId).update();
    }

    private String courseStatus(long courseId) {
        return jdbc.sql("SELECT status FROM courses WHERE id = ?").param(courseId).query(String.class).single();
    }

    private static MockHttpServletRequestBuilder submit(long courseId, Session session) {
        return post(COURSES + courseId + "/submit").header(HttpHeaders.AUTHORIZATION, session.bearer());
    }
}
