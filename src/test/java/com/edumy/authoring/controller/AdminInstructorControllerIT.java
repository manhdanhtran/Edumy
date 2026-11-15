package com.edumy.authoring.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * UC-A05 step 1 (P10, additive): {@code GET /admin/users/{userId}/instructor-revocation-impact} — the counts the admin
 * sees before confirming the revocation. Counted from authoring's own tables.
 */
@MySqlSpringBootTest
class AdminInstructorControllerIT {

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

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        admin = tokens.newUserSession(RoleCode.ADMIN);
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    @Test
    void impact_countsMainCoTaughtAndPublished_UCA05step1() throws Exception {
        TestUser target = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        TestUser other = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        fixtures.draftCourse(target.id());
        long published = fixtures.draftCourse(target.id());
        fixtures.setStatus(published, "published", true);
        long hidden = fixtures.draftCourse(target.id());
        fixtures.setStatus(hidden, "hidden", true);
        long coPublished = fixtures.draftCourse(other.id());
        fixtures.setStatus(coPublished, "published", true);
        fixtures.coInstructor(coPublished, target.id(), other.id(), "accepted");
        long coDraft = fixtures.draftCourse(other.id());
        fixtures.coInstructor(coDraft, target.id(), other.id(), "accepted");
        long pendingInvite = fixtures.draftCourse(other.id());
        fixtures.setStatus(pendingInvite, "published", true);
        fixtures.coInstructor(pendingInvite, target.id(), other.id(), "pending"); // not a co-instructor (BR-04)
        long removed = fixtures.draftCourse(other.id());
        fixtures.coInstructor(removed, target.id(), other.id(), "removed");

        mvc.perform(impact(target.id(), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mainCourseCount").value(3))        // draft, published, hidden
                .andExpect(jsonPath("$.coInstructorCourseCount").value(2)) // coPublished, coDraft
                .andExpect(jsonPath("$.publishedCourseCount").value(2));   // published, coPublished
    }

    @Test
    void impact_instructorWithoutCourses_allZero() throws Exception {
        TestUser target = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);

        mvc.perform(impact(target.id(), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mainCourseCount").value(0))
                .andExpect(jsonPath("$.coInstructorCourseCount").value(0))
                .andExpect(jsonPath("$.publishedCourseCount").value(0));
    }

    @Test
    void impact_noActiveInstructorRole_409_unknownUser_404_UCA05E1() throws Exception {
        TestUser student = users.create(RoleCode.STUDENT);
        TestUser revoked = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        users.revokeRole(revoked.id(), RoleCode.INSTRUCTOR);

        expectProblem(mvc.perform(impact(student.id(), admin)), 409, "INSTRUCTOR_ROLE_NOT_ACTIVE");
        expectProblem(mvc.perform(impact(revoked.id(), admin)), 409, "INSTRUCTOR_ROLE_NOT_ACTIVE");
        expectProblem(mvc.perform(impact(Long.MAX_VALUE, admin)), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void impact_nonAdmin_403_noToken_401() throws Exception {
        TestUser target = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        Session instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);

        expectProblem(mvc.perform(impact(target.id(), instructor)), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(get(path(target.id()))), 401, "UNAUTHENTICATED");
    }

    private static MockHttpServletRequestBuilder impact(long userId, Session caller) {
        return get(path(userId)).header(HttpHeaders.AUTHORIZATION, caller.bearer());
    }

    private static String path(long userId) {
        return "/api/v1/admin/users/" + userId + "/instructor-revocation-impact";
    }
}
