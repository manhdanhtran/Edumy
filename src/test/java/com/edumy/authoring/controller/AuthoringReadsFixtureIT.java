package com.edumy.authoring.controller;

import static com.edumy.support.IdorAssertions.assertSameNotFound;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.SqlStatementCounter;
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
 * Authoring reads on cases the dev seed cannot show (committed fixtures on database {@code edumy}, removed after each
 * test): a <b>removed</b> co-instructor who still holds an active instructor role (seed user 5 lost the role, so the
 * seed only shows 403), and the UC-A09 queue with several main instructors (the seed has one pending course).
 */
@MySqlSpringBootTest
class AuthoringReadsFixtureIT {

    static final String COURSES = "/api/v1/instructor/courses";
    static final String PENDING = "/api/v1/admin/courses/pending";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SqlStatementCounter sql;

    CourseFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    private static MockHttpServletRequestBuilder as(Session session, String path) {
        return get(path).header(HttpHeaders.AUTHORIZATION, session.bearer());
    }

    @Test
    void removedCoInstructor_withActiveRole_404LikeMissing_notListed_BR04() throws Exception {
        Session owner = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        Session removed = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        long course = fixtures.draftCourse(owner.userId());
        fixtures.coInstructor(course, removed.userId(), owner.userId(), "removed");

        for (String suffix : List.of("", "/review-status")) {
            assertSameNotFound(mvc, as(removed, COURSES + "/" + course + suffix),
                    as(removed, COURSES + "/" + Long.MAX_VALUE + suffix));
        }
        mvc.perform(as(removed, COURSES)).andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
        // the main instructor still sees the removed invitation
        mvc.perform(as(owner, COURSES + "/" + course)).andExpect(status().isOk())
                .andExpect(jsonPath("$.coInstructors[0].id").value(removed.userId()))
                .andExpect(jsonPath("$.coInstructors[0].invitationStatus").value("removed"));
    }

    @Test
    void pendingQueue_severalInstructors_oldestFirst_statementCountIndependentOfPageSize_UCA09() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.ADMIN);
        List<Long> queue = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            Session instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
            long course = fixtures.draftCourse(instructor.userId());
            fixtures.markPendingReview(course);
            // deterministic queue order, before anything else in the database
            jdbc.sql("UPDATE courses SET submitted_at = ? WHERE id = ?")
                    .params("2000-01-0" + (5 - i) + " 00:00:00", course)
                    .update();
            queue.add(0, course);
        }

        mvc.perform(as(admin, PENDING + "?size=4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(queue.stream().map(Long::intValue).toArray())));

        sql.reset();
        mvc.perform(as(admin, PENDING + "?size=2")).andExpect(jsonPath("$.content", hasSize(2)));
        int small = sql.count();
        String statements = sql.statements().toString();

        sql.reset();
        mvc.perform(as(admin, PENDING + "?size=4")).andExpect(jsonPath("$.content", hasSize(4)))
                .andExpect(jsonPath("$.content[3].mainInstructor.fullName").value("Người dùng kiểm thử"));
        int large = sql.count();

        // page + count + names of every main instructor of the page in one query
        assertThat(small).as(statements).isEqualTo(3);
        assertThat(large).as(sql.statements().toString()).isEqualTo(small);
    }
}
