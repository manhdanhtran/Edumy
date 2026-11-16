package com.edumy.authoring.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * UC-I02 "số học viên" (P13): each row of {@code GET /instructor/courses} carries the number of enrolled students, read
 * through {@code authoring::api.EnrollmentCountPort} implemented by learning (free and purchase enrollments; 0 when
 * none). Main and accepted co-instructor see the same count.
 */
@MySqlSpringBootTest
class InstructorCourseStudentCountIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    CourseFixtures fixtures;

    @Test
    void list_rowsCarryTheNumberOfEnrolledStudents_UCI02() throws Exception {
        fixtures = new CourseFixtures(jdbc);
        Session main = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        Session co = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        long busy = fixtures.draftCourse(main.userId());
        long empty = fixtures.draftCourse(main.userId());
        fixtures.setStatus(busy, "published", false);
        fixtures.coInstructor(busy, co.userId(), main.userId(), "accepted");
        fixtures.enrollment(tokens.newUserSession(RoleCode.STUDENT).userId(), busy); // free
        fixtures.purchaseEnrollment(tokens.newUserSession(RoleCode.STUDENT).userId(), busy); // purchase

        mvc.perform(get("/api/v1/instructor/courses").header(HttpHeaders.AUTHORIZATION, main.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + busy + ")].studentCount").value(2))
                .andExpect(jsonPath("$.content[?(@.id == " + empty + ")].studentCount").value(0));
        mvc.perform(get("/api/v1/instructor/courses").header(HttpHeaders.AUTHORIZATION, co.bearer()))
                .andExpect(jsonPath("$.content[?(@.id == " + busy + ")].studentCount").value(2));
    }
}
