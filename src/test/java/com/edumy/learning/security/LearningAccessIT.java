package com.edumy.learning.security;

import static com.edumy.support.IdorAssertions.assertSameNotFound;
import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edumy.shared.security.RoleCode;
import com.edumy.support.AccessProbeController;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code @enrollmentAccess} / {@code @qaAccess} end to end on MySQL through {@link AccessProbeController}: student A
 * enrolled with a question and an answer; student B is a stranger, or another enrolled student (D-32, BR-37, BR-38,
 * NFR-01).
 */
@MySqlSpringBootTest
class LearningAccessIT {

    static final String BASE = AccessProbeController.BASE;
    static final long MISSING = Long.MAX_VALUE;

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;

    CourseFixtures fixtures;
    Session instructor;
    Session studentA;
    Session studentB;
    long course;
    long lesson;
    long question;
    long answer;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        studentA = tokens.newUserSession(RoleCode.STUDENT);
        studentB = tokens.newUserSession(RoleCode.STUDENT);
        course = fixtures.draftCourse(instructor.userId()); // learning ignores the course status (BR-31)
        lesson = fixtures.lesson(fixtures.chapter(course));
        question = fixtures.question(fixtures.enrollment(studentA.userId(), course));
        answer = fixtures.answer(question, studentA.userId(), "student");
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    private MockHttpServletRequestBuilder probe(Session session, String path, long id) {
        return get(BASE + path, id).header(HttpHeaders.AUTHORIZATION, session.bearer());
    }

    private void expectOk(Session session, String path, long id) throws Exception {
        mvc.perform(probe(session, path, id)).andExpect(status().isOk()).andExpect(content().string("ok"));
    }

    private void expectSameNotFound(Session session, String path, long othersId) throws Exception {
        assertSameNotFound(mvc, probe(session, path, othersId), probe(session, path, MISSING));
    }

    @Test
    void enrolledAsker_everythingAllowed() throws Exception {
        expectOk(studentA, "/courses/{id}/enrolled", course);
        expectOk(studentA, "/courses/{id}/content", course);
        expectOk(studentA, "/lessons/{id}/content", lesson);
        expectOk(studentA, "/questions/{id}/reply", question);
        expectOk(studentA, "/questions/{id}/author", question);
        expectOk(studentA, "/answers/{id}/author", answer);
    }

    @Test
    void stranger_onAsResources_sameBodyAsMissing_404_NFR01() throws Exception {
        expectSameNotFound(studentB, "/courses/{id}/enrolled", course);
        expectSameNotFound(studentB, "/courses/{id}/content", course);
        expectSameNotFound(studentB, "/lessons/{id}/content", lesson);
        expectSameNotFound(studentB, "/questions/{id}/reply", question);
        expectSameNotFound(studentB, "/questions/{id}/author", question);
        expectSameNotFound(studentB, "/answers/{id}/author", answer);
    }

    @Test
    void otherEnrolledStudent_seesButCannotReply_403QaReplyNotAllowed_BR38() throws Exception {
        fixtures.enrollment(studentB.userId(), course);

        expectOk(studentB, "/courses/{id}/content", course);
        expectProblem(mvc.perform(probe(studentB, "/questions/{id}/reply", question)), 403, "QA_REPLY_NOT_ALLOWED");
        expectSameNotFound(studentB, "/questions/{id}/author", question);
        expectSameNotFound(studentB, "/answers/{id}/author", answer);
    }

    @Test
    void courseInstructor_viewsWithoutEnrollment_andReplies_D32_BR38() throws Exception {
        expectOk(instructor, "/courses/{id}/content", course);
        expectOk(instructor, "/lessons/{id}/content", lesson);
        expectOk(instructor, "/questions/{id}/reply", question);
        expectSameNotFound(instructor, "/courses/{id}/enrolled", course); // no progress without enrollment
    }

    @Test
    void revokedInstructor_losesContentAccess_404_D07() throws Exception {
        users.revokeRole(instructor.userId(), RoleCode.INSTRUCTOR); // before any request: nothing cached yet

        expectSameNotFound(instructor, "/courses/{id}/content", course);
        expectSameNotFound(instructor, "/questions/{id}/reply", question);
    }

    @Test
    void admin_viewsContentButCannotReply_D32_BR38() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.STUDENT, RoleCode.ADMIN);

        expectOk(admin, "/courses/{id}/content", course);
        expectOk(admin, "/lessons/{id}/content", lesson);
        expectProblem(mvc.perform(probe(admin, "/questions/{id}/reply", question)), 403, "QA_REPLY_NOT_ALLOWED");
        expectSameNotFound(admin, "/courses/{id}/enrolled", course);
    }

    @Test
    void hiddenQuestion_onlyAskerAndAdminSeeIt_BR41() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.STUDENT, RoleCode.ADMIN);
        fixtures.enrollment(studentB.userId(), course);
        fixtures.questionVisibility(question, "hidden", admin.userId());

        expectSameNotFound(studentB, "/questions/{id}/reply", question);
        expectSameNotFound(instructor, "/questions/{id}/reply", question);
        expectOk(studentA, "/questions/{id}/reply", question); // the service answers the BR-41 conflict (409)
        expectOk(studentA, "/questions/{id}/author", question);
        expectProblem(mvc.perform(probe(admin, "/questions/{id}/reply", question)), 403, "QA_REPLY_NOT_ALLOWED");
    }

    @Test
    void deletedQuestion_goneForEveryone_404() throws Exception {
        fixtures.questionVisibility(question, "deleted", null);

        expectSameNotFound(studentA, "/questions/{id}/reply", question);
        expectSameNotFound(studentA, "/questions/{id}/author", question);
        expectSameNotFound(studentA, "/answers/{id}/author", answer);
        expectSameNotFound(instructor, "/questions/{id}/reply", question);
    }
}
