package com.edumy.learning.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edumy.shared.security.RoleCode;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * P10 business review BC-M3 (RBAC §3.3 "Tiến độ, đặt câu hỏi, đánh giá, chứng chỉ — GV chính —, Đồng GV —"; BR-32):
 * a student who enrolled first and became a co-instructor of the same course later keeps the enrollment row (BR-17)
 * but no student rights on that course — no review, no completion (so no certificate), no question — 422
 * {@code ENROLLMENT_OWN_COURSE}; the visit records nothing (D-32). The relation counts whatever the role state.
 */
@MySqlSpringBootTest
class OwnCourseGuardIT {

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
    Session coStudent;
    long course;
    long lesson;
    long enrollment;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        Session main = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        coStudent = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        course = fixtures.draftCourse(main.userId(), fixtures.category(true));
        lesson = fixtures.lesson(fixtures.chapter(course));
        fixtures.setStatus(course, "published", false);
        enrollment = fixtures.enrollment(coStudent.userId(), course); // bought / enrolled first …
        fixtures.coInstructor(course, coStudent.userId(), main.userId(), "accepted"); // … invited and accepted later
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    @Test
    void enrolledCoInstructor_noReviewNoCompletionNoQuestion_422_visitRecordsNothing_BR32() throws Exception {
        expectProblem(mvc.perform(json(post(API + "/courses/" + course + "/reviews"), "{\"rating\": 5}")), 422,
                "ENROLLMENT_OWN_COURSE");
        expectProblem(mvc.perform(json(put(API + "/learn/lessons/" + lesson + "/completion"),
                "{\"completed\": true}")), 422, "ENROLLMENT_OWN_COURSE");
        expectProblem(mvc.perform(json(post(API + "/courses/" + course + "/questions"),
                "{\"title\": \"Hỏi\", \"content\": \"Nội dung\"}")), 422, "ENROLLMENT_OWN_COURSE");
        mvc.perform(post(API + "/learn/lessons/" + lesson + "/visit")
                        .header(HttpHeaders.AUTHORIZATION, coStudent.bearer()))
                .andExpect(status().isNoContent());

        assertThat(count("course_reviews")).isZero();
        assertThat(count("lesson_progresses")).isZero();
        assertThat(count("course_questions")).isZero();
        assertThat(count("certificates")).isZero();
    }

    /** The relation counts whatever the role state: after a revoke the former co-instructor still cannot rate it. */
    @Test
    void enrolledCoInstructor_roleRevoked_stillRefused_BR32() throws Exception {
        users.revokeRole(coStudent.userId(), RoleCode.INSTRUCTOR);

        expectProblem(mvc.perform(json(post(API + "/courses/" + course + "/reviews"), "{\"rating\": 5}")), 422,
                "ENROLLMENT_OWN_COURSE");
        assertThat(count("course_reviews")).isZero();
    }

    /** Once removed from the course (UC-I16), the person is a plain enrolled student again and may review. */
    @Test
    void removedCoInstructor_isAStudentAgain_reviewAllowed() throws Exception {
        jdbc.sql("UPDATE course_co_instructors SET invitation_status = 'removed' WHERE course_id = ?").param(course)
                .update();

        mvc.perform(json(post(API + "/courses/" + course + "/reviews"), "{\"rating\": 4}"))
                .andExpect(status().isCreated());
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.header(HttpHeaders.AUTHORIZATION, coStudent.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /** Rows of the enrollment in {@code table} (all four learning tables used here carry {@code enrollment_id}). */
    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE enrollment_id = ?").param(enrollment)
                .query(Long.class).single();
    }
}
