package com.edumy.learning.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * UC-A19 admin hide / restore of questions and answers through the real filter chain and MySQL: only an admin (JWT
 * role and role re-checked in the database, BR-26), {@code moderated_by} / {@code moderated_at} recorded (BR-41),
 * wrong state → 409, {@code deleted} is final → 404, and the effect on the live reads: hidden content stays visible
 * only to its author and admins (P05 rules), leaves the lists and the instructor queue, and only an admin brings it
 * back (the author's edit stays refused until then, BR-41).
 */
@MySqlSpringBootTest
class QaModerationIT {

    static final String API = "/api/v1";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;

    CourseFixtures fixtures;
    Session admin;
    Session student;
    Session classmate;
    Session instructor;
    long course;
    long question;
    long answer;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        admin = tokens.newUserSession(RoleCode.ADMIN);
        student = tokens.newUserSession(RoleCode.STUDENT);
        classmate = tokens.newUserSession(RoleCode.STUDENT);
        instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        course = fixtures.draftCourse(instructor.userId(), fixtures.category(true));
        fixtures.lesson(fixtures.chapter(course));
        fixtures.setStatus(course, "published", false);
        long enrollment = fixtures.enrollment(student.userId(), course);
        fixtures.enrollment(classmate.userId(), course);
        question = fixtures.question(enrollment);
        answer = fixtures.answer(question, instructor.userId(), "instructor");
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    // -------------------------------------------------------------------------------------------- questions

    @Test
    void hideQuestion_200_hiddenWithModerator_UCA19() throws Exception {
        mvc.perform(moderate(admin, "questions", question, "hide", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(question))
                .andExpect(jsonPath("$.status").value("hidden"));

        Map<String, Object> row = questionRow();
        assertThat(row.get("visibility_status")).isEqualTo("hidden");
        assertThat(((Number) row.get("moderated_by")).longValue()).isEqualTo(admin.userId());
        assertThat(row.get("moderated_at")).isNotNull();
    }

    @Test
    void hideQuestion_effectOnReads_onlyAskerAndAdminSeeIt_leavesQueue_BR41() throws Exception {
        mvc.perform(moderate(admin, "questions", question, "hide", "{\"reason\": \"Spam\"}"))
                .andExpect(status().isOk());

        expectProblem(mvc.perform(get(API + "/questions/" + question)
                .header(HttpHeaders.AUTHORIZATION, classmate.bearer())), 404, "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(get(API + "/questions/" + question)
                .header(HttpHeaders.AUTHORIZATION, instructor.bearer())), 404, "RESOURCE_NOT_FOUND");
        mvc.perform(get(API + "/questions/" + question).header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("hidden"));
        mvc.perform(get(API + "/questions/" + question).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("hidden"));
        mvc.perform(get(API + "/courses/" + course + "/questions").header(HttpHeaders.AUTHORIZATION, classmate.bearer()))
                .andExpect(jsonPath("$.content", hasSize(0)));
        mvc.perform(get(API + "/courses/" + course + "/questions").header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].status").value("hidden"));
        mvc.perform(get(API + "/courses/" + course + "/questions").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(jsonPath("$.content", hasSize(1)));
        mvc.perform(get(API + "/instructor/questions").header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void restoreQuestion_200_visibleAgain_restoringAdminRecorded_authorEditsAgain_BR41() throws Exception {
        fixtures.questionVisibility(question, "hidden", admin.userId());
        Session second = tokens.newUserSession(RoleCode.ADMIN);
        expectProblem(mvc.perform(patch(API + "/questions/" + question)
                .header(HttpHeaders.AUTHORIZATION, student.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"X\", \"content\": \"Y\"}")), 409, "QA_CONTENT_HIDDEN_BY_ADMIN");

        mvc.perform(moderate(second, "questions", question, "restore", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("visible"));

        Map<String, Object> row = questionRow();
        assertThat(row.get("visibility_status")).isEqualTo("visible");
        assertThat(((Number) row.get("moderated_by")).longValue()).isEqualTo(second.userId());
        mvc.perform(get(API + "/courses/" + course + "/questions").header(HttpHeaders.AUTHORIZATION, classmate.bearer()))
                .andExpect(jsonPath("$.content", hasSize(1)));
        mvc.perform(patch(API + "/questions/" + question).header(HttpHeaders.AUTHORIZATION, student.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"X\", \"content\": \"Y\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void question_wrongState_409_dataConflict_unchanged() throws Exception {
        expectProblem(mvc.perform(moderate(admin, "questions", question, "restore", null)), 409, "DATA_CONFLICT");
        mvc.perform(moderate(admin, "questions", question, "hide", null)).andExpect(status().isOk());
        expectProblem(mvc.perform(moderate(admin, "questions", question, "hide", null)), 409, "DATA_CONFLICT");

        assertThat(questionRow().get("visibility_status")).isEqualTo("hidden");
    }

    @Test
    void question_deleted_404_finalEvenForAdmin_unknown404() throws Exception {
        fixtures.questionVisibility(question, "deleted", null);

        expectProblem(mvc.perform(moderate(admin, "questions", question, "hide", null)), 404, "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(moderate(admin, "questions", question, "restore", null)), 404,
                "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(moderate(admin, "questions", Long.MAX_VALUE, "hide", null)), 404,
                "RESOURCE_NOT_FOUND");

        assertThat(questionRow().get("visibility_status")).isEqualTo("deleted");
    }

    // ---------------------------------------------------------------------------------------------- answers

    @Test
    void hideAnswer_200_hiddenWithModerator_effectOnThreadAndQueue_UCA19_BR41() throws Exception {
        mvc.perform(moderate(admin, "answers", answer, "hide", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(answer))
                .andExpect(jsonPath("$.status").value("hidden"))
                .andExpect(jsonPath("$.authorType").value("instructor"));

        Map<String, Object> row = answerRow();
        assertThat(row.get("visibility_status")).isEqualTo("hidden");
        assertThat(((Number) row.get("moderated_by")).longValue()).isEqualTo(admin.userId());
        assertThat(row.get("moderated_at")).isNotNull();
        // the others no longer see it and the question counts as unanswered again (D-39)
        mvc.perform(get(API + "/questions/" + question).header(HttpHeaders.AUTHORIZATION, classmate.bearer()))
                .andExpect(jsonPath("$.answers", hasSize(0)))
                .andExpect(jsonPath("$.instructorReplied").value(false));
        mvc.perform(get(API + "/questions/" + question).header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.answers", hasSize(0)));
        // its author and an admin still do
        mvc.perform(get(API + "/questions/" + question).header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(jsonPath("$.answers", hasSize(1)))
                .andExpect(jsonPath("$.answers[0].status").value("hidden"));
        mvc.perform(get(API + "/questions/" + question).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(jsonPath("$.answers", hasSize(1)));
        mvc.perform(get(API + "/instructor/questions?status=unanswered")
                        .header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void restoreAnswer_200_visibleAgain_authorEditsAgain_BR41() throws Exception {
        mvc.perform(moderate(admin, "answers", answer, "hide", null)).andExpect(status().isOk());
        expectProblem(mvc.perform(patch(API + "/answers/" + answer)
                .header(HttpHeaders.AUTHORIZATION, instructor.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"Sửa\"}")), 409, "QA_CONTENT_HIDDEN_BY_ADMIN");

        mvc.perform(moderate(admin, "answers", answer, "restore", "{\"reason\": \"Nhầm\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("visible"));

        assertThat(answerRow().get("visibility_status")).isEqualTo("visible");
        mvc.perform(get(API + "/questions/" + question).header(HttpHeaders.AUTHORIZATION, classmate.bearer()))
                .andExpect(jsonPath("$.answers", hasSize(1)))
                .andExpect(jsonPath("$.instructorReplied").value(true));
        mvc.perform(patch(API + "/answers/" + answer).header(HttpHeaders.AUTHORIZATION, instructor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\": \"Sửa\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void answer_wrongState_409_dataConflict_unchanged() throws Exception {
        expectProblem(mvc.perform(moderate(admin, "answers", answer, "restore", null)), 409, "DATA_CONFLICT");
        mvc.perform(moderate(admin, "answers", answer, "hide", null)).andExpect(status().isOk());
        expectProblem(mvc.perform(moderate(admin, "answers", answer, "hide", null)), 409, "DATA_CONFLICT");

        assertThat(answerRow().get("visibility_status")).isEqualTo("hidden");
    }

    @Test
    void answer_deletedOrOfADeletedQuestion_404_unknown404() throws Exception {
        long other = fixtures.answer(question, student.userId(), "student");
        jdbc.sql("UPDATE question_answers SET visibility_status = 'deleted' WHERE id = ?").param(other).update();

        expectProblem(mvc.perform(moderate(admin, "answers", other, "hide", null)), 404, "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(moderate(admin, "answers", Long.MAX_VALUE, "hide", null)), 404,
                "RESOURCE_NOT_FOUND");

        fixtures.questionVisibility(question, "deleted", null);
        expectProblem(mvc.perform(moderate(admin, "answers", answer, "hide", null)), 404, "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(moderate(admin, "answers", answer, "restore", null)), 404, "RESOURCE_NOT_FOUND");
        assertThat(answerRow().get("visibility_status")).isEqualTo("visible");
    }

    // ------------------------------------------------------------------------------------------- security

    @ParameterizedTest
    @ValueSource(strings = {"questions", "answers"})
    void nonAdmin_403_accessDenied_withoutToken_401_unchanged(String kind) throws Exception {
        long id = kind.equals("questions") ? question : answer;
        for (String action : new String[] {"hide", "restore"}) {
            expectProblem(mvc.perform(moderate(student, kind, id, action, null)), 403, "ACCESS_DENIED");
            expectProblem(mvc.perform(moderate(instructor, kind, id, action, null)), 403, "ACCESS_DENIED");
            expectProblem(mvc.perform(post(API + "/admin/" + kind + "/" + id + "/" + action)), 401,
                    "UNAUTHENTICATED");
        }

        assertThat(questionRow().get("visibility_status")).isEqualTo("visible");
        assertThat(answerRow().get("visibility_status")).isEqualTo("visible");
    }

    /** BR-26: an admin whose role was revoked in the database is refused despite the JWT claim. */
    @ParameterizedTest
    @ValueSource(strings = {"questions", "answers"})
    void roleRevokedInDatabase_staleJwt_403_unchanged_BR26(String kind) throws Exception {
        long id = kind.equals("questions") ? question : answer;
        Session stale = tokens.newUserSession(RoleCode.ADMIN);
        users.revokeRole(stale.userId(), RoleCode.ADMIN);

        expectProblem(mvc.perform(moderate(stale, kind, id, "hide", null)), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(moderate(stale, kind, id, "restore", null)), 403, "ACCESS_DENIED");

        assertThat(questionRow().get("visibility_status")).isEqualTo("visible");
        assertThat(answerRow().get("visibility_status")).isEqualTo("visible");
    }

    @Test
    void reasonTooLong_400() throws Exception {
        expectProblem(mvc.perform(moderate(admin, "questions", question, "hide",
                "{\"reason\": \"" + "r".repeat(501) + "\"}")), 400, "VALIDATION_FAILED");

        assertThat(questionRow().get("visibility_status")).isEqualTo("visible");
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private Map<String, Object> questionRow() {
        return jdbc.sql("SELECT visibility_status, moderated_by, moderated_at FROM course_questions WHERE id = ?")
                .param(question).query().singleRow();
    }

    private Map<String, Object> answerRow() {
        return jdbc.sql("SELECT visibility_status, moderated_by, moderated_at FROM question_answers WHERE id = ?")
                .param(answer).query().singleRow();
    }

    private static MockHttpServletRequestBuilder moderate(Session who, String kind, long id, String action,
                                                          String body) {
        MockHttpServletRequestBuilder request = post(API + "/admin/" + kind + "/" + id + "/" + action)
                .header(HttpHeaders.AUTHORIZATION, who.bearer());
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
