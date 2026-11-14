package com.edumy.learning.controller;

import static com.edumy.support.IdorAssertions.assertSameNotFound;
import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.edumy.learning.api.QuestionAnsweredByInstructor;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * UC-I21 instructor answer, UC-S19 the asker's follow-up and the author's own edit / delete through the real filter
 * chain and MySQL: BR-38 matrix (asker and course instructors only, 403 for others who see the thread, 404 for the
 * rest), {@code author_type} snapshot (D-43), {@code QuestionAnsweredByInstructor} only for instructor answers (D-42),
 * hidden / deleted questions take no answer (E2), D-44 limit, own answer only (IDOR), BR-41 hidden → 409, and the
 * instructor queue (UC-I22) follows the instructor answers.
 */
@MySqlSpringBootTest
@RecordApplicationEvents
class AnswerWriteIT {

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
    ApplicationEvents events;

    CourseFixtures fixtures;
    Session admin;
    Session student;
    Session classmate;
    Session stranger;
    Session instructor;
    Session coInstructor;
    Session pendingCo;
    Session otherInstructor;
    long course;
    long enrollment;
    long question;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        admin = tokens.newUserSession(RoleCode.ADMIN);
        student = tokens.newUserSession(RoleCode.STUDENT);
        classmate = tokens.newUserSession(RoleCode.STUDENT);
        stranger = tokens.newUserSession(RoleCode.STUDENT);
        instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        coInstructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        pendingCo = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        otherInstructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        course = fixtures.draftCourse(instructor.userId(), fixtures.category(true));
        fixtures.lesson(fixtures.chapter(course));
        fixtures.setStatus(course, "published", false);
        fixtures.coInstructor(course, coInstructor.userId(), instructor.userId(), "accepted");
        fixtures.coInstructor(course, pendingCo.userId(), instructor.userId(), "pending");
        enrollment = fixtures.enrollment(student.userId(), course);
        fixtures.enrollment(classmate.userId(), course);
        question = fixtures.question(enrollment);
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    // ------------------------------------------------------------------------------------------------------ UC-I21

    @Test
    void answer_byMainInstructor_201_instructorType_eventForTheAsker_UCI21_D42() throws Exception {
        mvc.perform(answer(instructor, question, "{\"content\": \"  Cài JDK 21 nhé  \"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/api/v1/questions/" + question)))
                .andExpect(jsonPath("$.id").value(onlyAnswerId()))
                .andExpect(jsonPath("$.author.id").value(instructor.userId()))
                .andExpect(jsonPath("$.authorType").value("instructor"))
                .andExpect(jsonPath("$.content").value("Cài JDK 21 nhé"))
                .andExpect(jsonPath("$.status").value("visible"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        Map<String, Object> row = onlyAnswer();
        assertThat(row.get("author_type")).isEqualTo("instructor");
        assertThat(row.get("visibility_status")).isEqualTo("visible");
        assertThat(((Number) row.get("author_id")).longValue()).isEqualTo(instructor.userId());
        assertThat(row.get("moderated_by")).isNull();
        assertThat(answered()).containsExactly(new QuestionAnsweredByInstructor(question, onlyAnswerId(), course,
                student.userId(), instructor.userId(), "Câu hỏi"));
    }

    @Test
    void answer_byAcceptedCoInstructor_201_instructorType_event_UCI21() throws Exception {
        mvc.perform(answer(coInstructor, question, "{\"content\": \"Em thử lại xem\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorType").value("instructor"));

        assertThat(answered()).hasSize(1);
        assertThat(answered().get(0).instructorId()).isEqualTo(coInstructor.userId());
    }

    @Test
    void answer_byTheAsker_201_studentType_noEvent_UCS19() throws Exception {
        mvc.perform(answer(student, question, "{\"content\": \"Em đã thử nhưng vẫn lỗi\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorType").value("student"))
                .andExpect(jsonPath("$.author.id").value(student.userId()));

        assertThat(onlyAnswer().get("author_type")).isEqualTo("student");
        assertThat(answered()).isEmpty();
    }

    /** An asker who was later made an accepted co-instructor of the course answers as instructor (relation now). */
    @Test
    void answer_askerWhoIsAlsoCoInstructor_instructorType_BR38() throws Exception {
        Session dual = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        long dualEnrollment = fixtures.enrollment(dual.userId(), course);
        long own = fixtures.question(dualEnrollment);
        fixtures.coInstructor(course, dual.userId(), instructor.userId(), "accepted");

        mvc.perform(answer(dual, own, "{\"content\": \"Tự trả lời\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorType").value("instructor"));
    }

    @Test
    void answer_otherEnrolledStudentOrAdmin_403_qaReplyNotAllowed_nothingWritten_BR38_D38() throws Exception {
        expectProblem(mvc.perform(answer(classmate, question, "{\"content\": \"Mình biết\"}")), 403,
                "QA_REPLY_NOT_ALLOWED");
        expectProblem(mvc.perform(answer(admin, question, "{\"content\": \"Admin\"}")), 403,
                "QA_REPLY_NOT_ALLOWED");

        assertThat(answerCount()).isZero();
        assertThat(answered()).isEmpty();
    }

    @Test
    void answer_callerWhoCannotSeeTheQuestion_404_identicalToUnknown_BR38() throws Exception {
        String body = "{\"content\": \"Xin chào\"}";

        assertSameNotFound(mvc, answer(stranger, question, body), answer(stranger, Long.MAX_VALUE, body));
        assertSameNotFound(mvc, answer(otherInstructor, question, body), answer(otherInstructor, Long.MAX_VALUE, body));
        assertSameNotFound(mvc, answer(pendingCo, question, body), answer(pendingCo, Long.MAX_VALUE, body));

        assertThat(answerCount()).isZero();
    }

    @Test
    void answer_instructorRoleRevokedInDatabase_staleJwt_404_E1() throws Exception {
        users.revokeRole(instructor.userId(), RoleCode.INSTRUCTOR);

        expectProblem(mvc.perform(answer(instructor, question, "{\"content\": \"Còn quyền?\"}")), 404,
                "RESOURCE_NOT_FOUND");

        assertThat(answerCount()).isZero();
    }

    @Test
    void answer_removedCoInstructor_404_oldAnswerKeepsInstructorLabel_E1_D43() throws Exception {
        mvc.perform(answer(coInstructor, question, "{\"content\": \"Trả lời cũ\"}")).andExpect(status().isCreated());
        jdbc.sql("UPDATE course_co_instructors SET invitation_status = 'removed' WHERE course_id = ? "
                + "AND instructor_id = ?").params(course, coInstructor.userId()).update();

        expectProblem(mvc.perform(answer(coInstructor, question, "{\"content\": \"Mới\"}")), 404,
                "RESOURCE_NOT_FOUND");

        mvc.perform(get(API + "/questions/" + question).header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.answers", hasSize(1)))
                .andExpect(jsonPath("$.answers[0].authorType").value("instructor"));
    }

    @Test
    void answer_hiddenQuestion_asker409_instructor404_admin403_E2() throws Exception {
        fixtures.questionVisibility(question, "hidden", admin.userId());

        expectProblem(mvc.perform(answer(student, question, "{\"content\": \"Thêm\"}")), 409,
                "QA_CONTENT_HIDDEN_BY_ADMIN");
        expectProblem(mvc.perform(answer(instructor, question, "{\"content\": \"GV\"}")), 404, "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(answer(admin, question, "{\"content\": \"Admin\"}")), 403, "QA_REPLY_NOT_ALLOWED");

        assertThat(answerCount()).isZero();
        assertThat(answered()).isEmpty();
    }

    @Test
    void answer_limit5000_ok_D44() throws Exception {
        mvc.perform(answer(instructor, question, "{\"content\": \"" + "c".repeat(5000) + "\"}"))
                .andExpect(status().isCreated());

        assertThat((String) onlyAnswer().get("content")).hasSize(5000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"content\": \"CONTENT_5001\"}", "{\"content\": \"   \"}", "{\"content\": \"\"}", "{}",
            "{\"content\": null}"})
    void answer_invalidContent_400_noRowNoEvent_D44(String body) throws Exception {
        expectProblem(mvc.perform(answer(instructor, question, body.replace("CONTENT_5001", "c".repeat(5001)))), 400,
                "VALIDATION_FAILED");

        assertThat(answerCount()).isZero();
        assertThat(answered()).isEmpty();
    }

    @Test
    void answer_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(post(API + "/questions/" + question + "/answers")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\": \"x\"}")), 401, "UNAUTHENTICATED");
    }

    @Test
    void thread_showsAnswersOldestFirst_withAuthorTypeSnapshot_UCS18() throws Exception {
        jdbc.sql("UPDATE users SET full_name = 'Giảng Viên A' WHERE id = ?").param(instructor.userId()).update();
        mvc.perform(answer(instructor, question, "{\"content\": \"Một\"}")).andExpect(status().isCreated());
        mvc.perform(answer(student, question, "{\"content\": \"Hai\"}")).andExpect(status().isCreated());

        mvc.perform(get(API + "/questions/" + question).header(HttpHeaders.AUTHORIZATION, classmate.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instructorReplied").value(true))
                .andExpect(jsonPath("$.answers", hasSize(2)))
                .andExpect(jsonPath("$.answers[0].content").value("Một"))
                .andExpect(jsonPath("$.answers[0].authorType").value("instructor"))
                .andExpect(jsonPath("$.answers[0].author.fullName").value("Giảng Viên A"))
                .andExpect(jsonPath("$.answers[1].content").value("Hai"))
                .andExpect(jsonPath("$.answers[1].authorType").value("student"));
    }

    /** UC-I21 step 4: the question leaves the unanswered queue after an instructor answer only (D-39, D-42). */
    @Test
    void unansweredQueue_followsInstructorAnswers_notStudentFollowUps_UCI22() throws Exception {
        assertThat(queueSize()).isEqualTo(1);

        mvc.perform(answer(student, question, "{\"content\": \"Có ai không?\"}")).andExpect(status().isCreated());
        assertThat(queueSize()).as("a student follow-up does not answer").isEqualTo(1);

        mvc.perform(answer(instructor, question, "{\"content\": \"Có mình\"}")).andExpect(status().isCreated());
        assertThat(queueSize()).as("an instructor answer does").isZero();
        mvc.perform(get(API + "/courses/" + course + "/questions?unanswered=true")
                        .header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.content", hasSize(0)));

        long id = onlyInstructorAnswerId();
        mvc.perform(remove(instructor, id)).andExpect(status().isNoContent());
        assertThat(queueSize()).as("deleting the instructor answer reopens it").isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------- edit / delete (own)

    @Test
    void edit_ownAnswer_200_contentReplaced_noEvent_UCI21_A1() throws Exception {
        long id = fixtures.answer(question, instructor.userId(), "instructor");

        mvc.perform(edit(instructor, id, "{\"content\": \" Sửa lại \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.content").value("Sửa lại"))
                .andExpect(jsonPath("$.authorType").value("instructor"))
                .andExpect(jsonPath("$.status").value("visible"));

        assertThat(onlyAnswer().get("content")).isEqualTo("Sửa lại");
        assertThat(answered()).isEmpty();
    }

    @Test
    void edit_askersFollowUp_200_UCS19() throws Exception {
        long id = fixtures.answer(question, student.userId(), "student");

        mvc.perform(edit(student, id, "{\"content\": \"Đã tự giải quyết\"}")).andExpect(status().isOk());

        assertThat(onlyAnswer().get("content")).isEqualTo("Đã tự giải quyết");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"content\": \"CONTENT_5001\"}", "{\"content\": \" \"}", "{}"})
    void edit_invalid_400_unchanged_D44(String body) throws Exception {
        long id = fixtures.answer(question, instructor.userId(), "instructor");

        expectProblem(mvc.perform(edit(instructor, id, body.replace("CONTENT_5001", "c".repeat(5001)))), 400,
                "VALIDATION_FAILED");

        assertThat(onlyAnswer().get("content")).isEqualTo("Trả lời");
    }

    @Test
    void editAndDelete_someoneElsesAnswer_404_identicalToUnknown_IDOR_NFR01() throws Exception {
        long id = fixtures.answer(question, instructor.userId(), "instructor");
        String body = "{\"content\": \"Chiếm quyền\"}";

        for (Session other : new Session[] {student, classmate, coInstructor, admin, stranger}) {
            assertSameNotFound(mvc, edit(other, id, body), edit(other, Long.MAX_VALUE, body));
            assertSameNotFound(mvc, remove(other, id), remove(other, Long.MAX_VALUE));
        }

        assertThat(onlyAnswer().get("content")).isEqualTo("Trả lời");
        assertThat(onlyAnswer().get("visibility_status")).isEqualTo("visible");
    }

    @Test
    void editAndDelete_hiddenByAdmin_409_unchanged_BR41() throws Exception {
        long id = fixtures.answer(question, instructor.userId(), "instructor");
        jdbc.sql("""
                UPDATE question_answers SET visibility_status = 'hidden', moderated_by = ?,
                       moderated_at = CURRENT_TIMESTAMP(6) WHERE id = ?""").params(admin.userId(), id).update();

        expectProblem(mvc.perform(edit(instructor, id, "{\"content\": \"Sửa\"}")), 409, "QA_CONTENT_HIDDEN_BY_ADMIN");
        expectProblem(mvc.perform(remove(instructor, id)), 409, "QA_CONTENT_HIDDEN_BY_ADMIN");

        assertThat(onlyAnswer().get("visibility_status")).isEqualTo("hidden");
        assertThat(onlyAnswer().get("content")).isEqualTo("Trả lời");
    }

    @Test
    void delete_ownAnswer_204_deletedFinal_leavesTheThread_UCI21_A1_D40() throws Exception {
        long id = fixtures.answer(question, instructor.userId(), "instructor");

        mvc.perform(remove(instructor, id)).andExpect(status().isNoContent());

        assertThat(onlyAnswer().get("visibility_status")).isEqualTo("deleted");
        mvc.perform(get(API + "/questions/" + question).header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.answers", hasSize(0)))
                .andExpect(jsonPath("$.instructorReplied").value(false));
        expectProblem(mvc.perform(remove(instructor, id)), 404, "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(edit(instructor, id, "{\"content\": \"Sống lại\"}")), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void editAndDelete_answerOfADeletedQuestion_404() throws Exception {
        long id = fixtures.answer(question, instructor.userId(), "instructor");
        fixtures.questionVisibility(question, "deleted", null);

        expectProblem(mvc.perform(edit(instructor, id, "{\"content\": \"Sửa\"}")), 404, "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(remove(instructor, id)), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void editAndDelete_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(patch(API + "/answers/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"x\"}")), 401, "UNAUTHENTICATED");
        expectProblem(mvc.perform(delete(API + "/answers/1")), 401, "UNAUTHENTICATED");
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private List<QuestionAnsweredByInstructor> answered() {
        return events.stream(QuestionAnsweredByInstructor.class).toList();
    }

    private int queueSize() throws Exception {
        String json = mvc.perform(get(API + "/instructor/questions?status=unanswered&courseId=" + course)
                        .header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.<List<?>>read(json, "$.content").size();
    }

    private long answerCount() {
        return jdbc.sql("SELECT COUNT(*) FROM question_answers WHERE question_id = ?").param(question)
                .query(Long.class).single();
    }

    private long onlyAnswerId() {
        return ((Number) onlyAnswer().get("id")).longValue();
    }

    private long onlyInstructorAnswerId() {
        return jdbc.sql("SELECT id FROM question_answers WHERE question_id = ? AND author_type = 'instructor'")
                .param(question).query(Long.class).single();
    }

    private Map<String, Object> onlyAnswer() {
        return jdbc.sql("""
                        SELECT id, author_id, author_type, content, visibility_status, moderated_by
                        FROM question_answers WHERE question_id = ?""")
                .param(question).query().singleRow();
    }

    private static MockHttpServletRequestBuilder answer(Session who, long questionId, String body) {
        return json(post(API + "/questions/" + questionId + "/answers"), who, body);
    }

    private static MockHttpServletRequestBuilder edit(Session who, long id, String body) {
        return json(patch(API + "/answers/" + id), who, body);
    }

    private static MockHttpServletRequestBuilder remove(Session who, long id) {
        return delete(API + "/answers/" + id).header(HttpHeaders.AUTHORIZATION, who.bearer());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Session who,
                                                      String body) {
        return request.header(HttpHeaders.AUTHORIZATION, who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
