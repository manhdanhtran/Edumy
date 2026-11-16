package com.edumy.learning.controller;

import static com.edumy.support.IdorAssertions.assertEnrollmentRequired;
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
 * UC-S17 ask, UC-S19 edit / delete / resolve of the asker's own question through the real filter chain and MySQL:
 * enrolled students only (BR-37, true-or-404), lesson of the same course (422), D-44 limits, own question only (IDOR),
 * BR-41 hidden → 409, deleted = final and the whole thread vanishes, and the live reads (course list, instructor queue)
 * follow the writes.
 */
@MySqlSpringBootTest
@RecordApplicationEvents
class QuestionWriteIT {

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
    com.edumy.support.MutableClock clock;
    @Autowired
    ApplicationEvents events;

    CourseFixtures fixtures;
    Session admin;
    Session student;
    Session classmate;
    Session instructor;
    Session stranger;
    long course;
    long lesson;
    long foreignLesson;
    long enrollment;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        admin = tokens.newUserSession(RoleCode.ADMIN);
        student = tokens.newUserSession(RoleCode.STUDENT);
        classmate = tokens.newUserSession(RoleCode.STUDENT);
        stranger = tokens.newUserSession(RoleCode.STUDENT);
        instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        long category = fixtures.category(true);
        course = fixtures.draftCourse(instructor.userId(), category);
        lesson = fixtures.lesson(fixtures.chapter(course));
        fixtures.setStatus(course, "published", false);
        long other = fixtures.draftCourse(instructor.userId(), category);
        foreignLesson = fixtures.lesson(fixtures.chapter(other));
        enrollment = fixtures.enrollment(student.userId(), course);
        fixtures.enrollment(classmate.userId(), course);
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    // ------------------------------------------------------------------------------------------------------ UC-S17

    @Test
    void ask_courseLevel_201_visibleUnresolved_locationAndThread_UCS17() throws Exception {
        mvc.perform(ask(student, course, "{\"title\": \"  Lỗi cài JDK  \", \"content\": \" Em không cài được \"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/api/v1/questions/" + onlyQuestionId())))
                .andExpect(jsonPath("$.id").value(onlyQuestionId()))
                .andExpect(jsonPath("$.courseId").value(course))
                .andExpect(jsonPath("$.lessonId").doesNotExist())
                .andExpect(jsonPath("$.title").value("Lỗi cài JDK"))
                .andExpect(jsonPath("$.content").value("Em không cài được"))
                .andExpect(jsonPath("$.resolved").value(false))
                .andExpect(jsonPath("$.status").value("visible"))
                .andExpect(jsonPath("$.asker.id").value(student.userId()))
                .andExpect(jsonPath("$.instructorReplied").value(false))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.answers", hasSize(0)));

        Map<String, Object> row = onlyQuestion();
        assertThat(row.get("visibility_status")).isEqualTo("visible");
        assertThat(row.get("lesson_id")).isNull();
        assertThat(((Number) row.get("enrollment_id")).longValue()).isEqualTo(enrollment);
        assertThat(row.get("moderated_by")).isNull();
    }

    @Test
    void ask_lessonLevel_201_lessonKept_UCS17() throws Exception {
        mvc.perform(ask(student, course, "{\"title\": \"T\", \"content\": \"C\", \"lessonId\": " + lesson + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lessonId").value(lesson));

        assertThat(((Number) onlyQuestion().get("lesson_id")).longValue()).isEqualTo(lesson);
    }

    @Test
    void ask_lessonOfAnotherCourse_422_mismatch_nothingWritten_BR37() throws Exception {
        expectProblem(mvc.perform(ask(student, course,
                "{\"title\": \"T\", \"content\": \"C\", \"lessonId\": " + foreignLesson + "}")), 422,
                "LESSON_COURSE_MISMATCH");
        expectProblem(mvc.perform(ask(student, course,
                "{\"title\": \"T\", \"content\": \"C\", \"lessonId\": " + Long.MAX_VALUE + "}")), 422,
                "LESSON_COURSE_MISMATCH");

        assertThat(questionCount()).isZero();
    }

    /** The trigger of BR-37 is the backstop of the service check and maps to the same code. */
    @Test
    void ask_triggerBackstop_blocksForeignLesson_BR37() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO course_questions (enrollment_id, lesson_id, title, content)
                        VALUES (?, ?, 'T', 'C')""").params(enrollment, foreignLesson).update())
                .hasMessageContaining("BR-37");
    }

    /**
     * E1 / BR-37 + 403/404 principle: no enrollment in a <b>published</b> course → 403 {@code ENROLLMENT_REQUIRED}
     * (instructor and admin too: only students ask); a hidden course → the 404 of an unknown id.
     */
    @Test
    void ask_notEnrolled_publishedCourse403_hiddenCourse404_instructorAndAdminToo_E1_BR37() throws Exception {
        String body = "{\"title\": \"T\", \"content\": \"C\"}";

        assertEnrollmentRequired(mvc, ask(stranger, course, body), ask(instructor, course, body),
                ask(admin, course, body));
        fixtures.setStatus(course, "hidden", true);
        assertSameNotFound(mvc, ask(stranger, course, body), ask(stranger, Long.MAX_VALUE, body));

        assertThat(questionCount()).isZero();
    }

    @Test
    void ask_hiddenCourse_enrolledStudentCanStillAsk_D43() throws Exception {
        fixtures.setStatus(course, "hidden", true);

        mvc.perform(ask(student, course, "{\"title\": \"T\", \"content\": \"C\"}")).andExpect(status().isCreated());
    }

    @Test
    void ask_limitsAreInclusive_200_and_5000_D44() throws Exception {
        mvc.perform(ask(student, course, "{\"title\": \"" + "t".repeat(200) + "\", \"content\": \""
                + "c".repeat(5000) + "\"}")).andExpect(status().isCreated());

        Map<String, Object> row = onlyQuestion();
        assertThat((String) row.get("title")).hasSize(200);
        assertThat((String) row.get("content")).hasSize(5000);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"title\": \"TITLE_201\", \"content\": \"C\"}",
            "{\"title\": \"T\", \"content\": \"CONTENT_5001\"}",
            "{\"title\": \"   \", \"content\": \"C\"}",
            "{\"title\": \"T\", \"content\": \"   \"}",
            "{\"title\": \"\", \"content\": \"C\"}",
            "{\"content\": \"C\"}",
            "{\"title\": \"T\"}",
            "{}",
            "{\"title\": \"T\", \"content\": \"C\", \"lessonId\": 0}"})
    void ask_invalidInput_400_validationFailed_noRow_E2_D44(String body) throws Exception {
        String json = body.replace("TITLE_201", "t".repeat(201)).replace("CONTENT_5001", "c".repeat(5001));

        expectProblem(mvc.perform(ask(student, course, json)), 400, "VALIDATION_FAILED");

        assertThat(questionCount()).isZero();
    }

    @Test
    void ask_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(post(API + "/courses/" + course + "/questions")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"T\", \"content\": \"C\"}")), 401,
                "UNAUTHENTICATED");
    }

    /** UC-S17 step 4: the new question tops the course list, unanswered, and is in the instructor queue (UC-I22). */
    @Test
    void ask_appearsInCourseListAndInstructorQueue_UCS17_step4() throws Exception {
        fixtures.question(enrollment);
        mvc.perform(ask(classmate, course, "{\"title\": \"Mới nhất\", \"content\": \"C\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get(API + "/courses/" + course + "/questions").header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].title").value("Mới nhất"))
                .andExpect(jsonPath("$.content[0].instructorReplied").value(false));
        mvc.perform(get(API + "/instructor/questions?status=unanswered")
                        .header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[1].title").value("Mới nhất")); // queue: oldest first (P10 Q-b)
        assertThat(events.stream(QuestionAnsweredByInstructor.class).toList()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------------ UC-S19

    @Test
    void edit_own_200_replaced_trimmed_sameRow_UCS19() throws Exception {
        long id = fixtures.question(enrollment);

        mvc.perform(edit(student, id, "{\"title\": \" Tiêu đề mới \", \"content\": \" Nội dung mới \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value("Tiêu đề mới"))
                .andExpect(jsonPath("$.content").value("Nội dung mới"))
                .andExpect(jsonPath("$.status").value("visible"));

        Map<String, Object> row = onlyQuestion();
        assertThat(row.get("title")).isEqualTo("Tiêu đề mới");
        assertThat(row.get("content")).isEqualTo("Nội dung mới");
        assertThat(questionCount()).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"title\": \"TITLE_201\", \"content\": \"C\"}",
            "{\"title\": \"T\", \"content\": \"CONTENT_5001\"}",
            "{\"title\": \" \", \"content\": \"C\"}",
            "{\"title\": \"T\", \"content\": \" \"}",
            "{\"title\": \"T\"}"})
    void edit_invalidInput_400_unchanged_D44(String body) throws Exception {
        long id = fixtures.question(enrollment);
        String json = body.replace("TITLE_201", "t".repeat(201)).replace("CONTENT_5001", "c".repeat(5001));

        expectProblem(mvc.perform(edit(student, id, json)), 400, "VALIDATION_FAILED");

        assertThat(onlyQuestion().get("title")).isEqualTo("Câu hỏi");
    }

    @Test
    void edit_someoneElsesQuestion_404_identicalToUnknown_IDOR_NFR01() throws Exception {
        long id = fixtures.question(enrollment);
        String body = "{\"title\": \"X\", \"content\": \"Y\"}";

        assertSameNotFound(mvc, edit(classmate, id, body), edit(classmate, Long.MAX_VALUE, body));
        assertSameNotFound(mvc, edit(instructor, id, body), edit(instructor, Long.MAX_VALUE, body));
        assertSameNotFound(mvc, edit(admin, id, body), edit(admin, Long.MAX_VALUE, body));
        assertSameNotFound(mvc, edit(stranger, id, body), edit(stranger, Long.MAX_VALUE, body));

        assertThat(onlyQuestion().get("title")).isEqualTo("Câu hỏi");
    }

    @Test
    void edit_hiddenByAdmin_409_qaContentHiddenByAdmin_unchanged_BR41() throws Exception {
        long id = fixtures.question(enrollment);
        fixtures.questionVisibility(id, "hidden", admin.userId());

        expectProblem(mvc.perform(edit(student, id, "{\"title\": \"X\", \"content\": \"Y\"}")), 409,
                "QA_CONTENT_HIDDEN_BY_ADMIN");

        assertThat(onlyQuestion().get("title")).isEqualTo("Câu hỏi");
    }

    @Test
    void edit_deleted_404_noOneSeesIt() throws Exception {
        long id = fixtures.question(enrollment);
        fixtures.questionVisibility(id, "deleted", null);

        expectProblem(mvc.perform(edit(student, id, "{\"title\": \"X\", \"content\": \"Y\"}")), 404,
                "RESOURCE_NOT_FOUND");
    }

    @Test
    void edit_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(patch(API + "/questions/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"X\", \"content\": \"Y\"}")), 401, "UNAUTHENTICATED");
    }

    @Test
    void delete_own_204_deletedFinal_wholeThreadGoneForEveryone_UCS19() throws Exception {
        long id = fixtures.question(enrollment);
        fixtures.answer(id, instructor.userId(), "instructor");
        fixtures.answer(id, student.userId(), "student");

        mvc.perform(remove(student, id)).andExpect(status().isNoContent());

        assertThat(onlyQuestion().get("visibility_status")).isEqualTo("deleted");
        for (Session who : new Session[] {student, classmate, instructor, admin, stranger}) {
            expectProblem(mvc.perform(get(API + "/questions/" + id).header(HttpHeaders.AUTHORIZATION, who.bearer())),
                    404, "RESOURCE_NOT_FOUND");
        }
        mvc.perform(get(API + "/courses/" + course + "/questions").header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.content", hasSize(0)));
        mvc.perform(get(API + "/instructor/questions").header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void delete_deletedQuestionTakesNoAnswerAndNoSecondDelete_404_UCS19() throws Exception {
        long id = fixtures.question(enrollment);
        mvc.perform(remove(student, id)).andExpect(status().isNoContent());

        expectProblem(mvc.perform(remove(student, id)), 404, "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(post(API + "/questions/" + id + "/answers")
                .header(HttpHeaders.AUTHORIZATION, instructor.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"Trả lời\"}")), 404, "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(post(API + "/questions/" + id + "/answers")
                .header(HttpHeaders.AUTHORIZATION, student.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"Thêm\"}")), 404, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM question_answers WHERE question_id = ?").param(id)
                .query(Long.class).single()).isZero();
    }

    @Test
    void delete_someoneElsesQuestion_404_identicalToUnknown_IDOR_NFR01() throws Exception {
        long id = fixtures.question(enrollment);

        assertSameNotFound(mvc, remove(classmate, id), remove(classmate, Long.MAX_VALUE));
        assertSameNotFound(mvc, remove(instructor, id), remove(instructor, Long.MAX_VALUE));
        assertSameNotFound(mvc, remove(admin, id), remove(admin, Long.MAX_VALUE));

        assertThat(onlyQuestion().get("visibility_status")).isEqualTo("visible");
    }

    @Test
    void delete_hiddenByAdmin_409_unchanged_BR41() throws Exception {
        long id = fixtures.question(enrollment);
        fixtures.questionVisibility(id, "hidden", admin.userId());

        expectProblem(mvc.perform(remove(student, id)), 409, "QA_CONTENT_HIDDEN_BY_ADMIN");

        assertThat(onlyQuestion().get("visibility_status")).isEqualTo("hidden");
    }

    @Test
    void resolve_default_marksResolved_idempotent_listShowsIt_UCS19_D39() throws Exception {
        long id = fixtures.question(enrollment);

        mvc.perform(resolve(student, id, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(true));
        mvc.perform(resolve(student, id, "{}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(true));

        assertThat(onlyQuestion().get("is_resolved")).isEqualTo(true);
        mvc.perform(get(API + "/courses/" + course + "/questions").header(HttpHeaders.AUTHORIZATION, classmate.bearer()))
                .andExpect(jsonPath("$.content[0].resolved").value(true));
    }

    @Test
    void resolve_falseTakesTheMarkBack_UCS19() throws Exception {
        long id = fixtures.question(enrollment);
        mvc.perform(resolve(student, id, null)).andExpect(status().isOk());

        mvc.perform(resolve(student, id, "{\"resolved\": false}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(false));

        assertThat(onlyQuestion().get("is_resolved")).isEqualTo(false);
    }

    @Test
    void resolve_notTheAsker_404_identical_IDOR_NFR01() throws Exception {
        long id = fixtures.question(enrollment);

        assertSameNotFound(mvc, resolve(classmate, id, null), resolve(classmate, Long.MAX_VALUE, null));
        assertSameNotFound(mvc, resolve(instructor, id, null), resolve(instructor, Long.MAX_VALUE, null));
        assertSameNotFound(mvc, resolve(admin, id, null), resolve(admin, Long.MAX_VALUE, null));

        assertThat(onlyQuestion().get("is_resolved")).isEqualTo(false);
    }

    @Test
    void resolve_hiddenByAdmin_409_BR41() throws Exception {
        long id = fixtures.question(enrollment);
        fixtures.questionVisibility(id, "hidden", admin.userId());

        expectProblem(mvc.perform(resolve(student, id, null)), 409, "QA_CONTENT_HIDDEN_BY_ADMIN");
        expectProblem(mvc.perform(resolve(student, id, "{\"resolved\": false}")), 409, "QA_CONTENT_HIDDEN_BY_ADMIN");
    }

    @Test
    void resolve_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(post(API + "/questions/1/resolve")), 401, "UNAUTHENTICATED");
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    // ---- UC-S17 E3 (P13): repeated submission

    @Test
    void ask_sameTitleAndContentWithin60s_409Duplicate_oneRow_otherContentOrLaterAllowed_UCS17E3() throws Exception {
        String body = "{\"title\": \"Lỗi cài JDK\", \"content\": \"Em không cài được\"}";
        mvc.perform(ask(student, course, body)).andExpect(status().isCreated());

        expectProblem(mvc.perform(ask(student, course,
                "{\"title\": \"  Lỗi cài JDK \", \"content\": \"Em không cài được  \"}")), 409, "QA_QUESTION_DUPLICATE");
        assertThat(questionCount()).isEqualTo(1);

        mvc.perform(ask(student, course, "{\"title\": \"Lỗi cài JDK\", \"content\": \"Em cài lại vẫn lỗi\"}"))
                .andExpect(status().isCreated()); // other content
        mvc.perform(ask(student, course, "{\"title\": \"lỗi cài jdk\", \"content\": \"Em không cài được\"}"))
                .andExpect(status().isCreated()); // other title (exact comparison, not the ai_ci collation)
        clock.advance(java.time.Duration.ofSeconds(61));
        mvc.perform(ask(student, course, body)).andExpect(status().isCreated()); // the window has passed
        assertThat(questionCount()).isEqualTo(4);
    }

    @Test
    void ask_twoIdenticalSubmissionsAtOnce_201And409_oneRow_UCS17E3() throws Exception {
        String body = "{\"title\": \"Bấm gửi hai lần\", \"content\": \"Nội dung\"}";
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    return mvc.perform(ask(student, course, body)).andReturn().getResponse().getStatus();
                }));
            }
            java.util.List<Integer> statuses = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Integer> future : futures) {
                statuses.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(questionCount()).isEqualTo(1);
    }
    private long questionCount() {
        return jdbc.sql("SELECT COUNT(*) FROM course_questions WHERE enrollment_id = ?").param(enrollment)
                .query(Long.class).single();
    }

    private long onlyQuestionId() {
        return ((Number) onlyQuestion().get("id")).longValue();
    }

    private Map<String, Object> onlyQuestion() {
        return jdbc.sql("""
                        SELECT id, enrollment_id, lesson_id, title, content, is_resolved, visibility_status, moderated_by
                        FROM course_questions WHERE enrollment_id = ?""")
                .param(enrollment).query().singleRow();
    }

    private static MockHttpServletRequestBuilder ask(Session who, long courseId, String body) {
        return json(post(API + "/courses/" + courseId + "/questions"), who, body);
    }

    private static MockHttpServletRequestBuilder edit(Session who, long id, String body) {
        return json(patch(API + "/questions/" + id), who, body);
    }

    private static MockHttpServletRequestBuilder remove(Session who, long id) {
        return delete(API + "/questions/" + id).header(HttpHeaders.AUTHORIZATION, who.bearer());
    }

    private static MockHttpServletRequestBuilder resolve(Session who, long id, String body) {
        MockHttpServletRequestBuilder request = post(API + "/questions/" + id + "/resolve")
                .header(HttpHeaders.AUTHORIZATION, who.bearer());
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Session who,
                                                      String body) {
        return request.header(HttpHeaders.AUTHORIZATION, who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
