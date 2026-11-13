package com.edumy.learning.controller;

import static com.edumy.support.IdorAssertions.assertSameNotFound;
import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edumy.shared.security.RoleCode;
import com.edumy.support.MySqlSeedSpringBootTest;
import com.edumy.support.SeedUserTokens;
import com.edumy.support.SqlStatementCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * UC-I19 on the dev seed (read-only). Course 2 (instructor 2): enrollments 17, 19, 14, 12, 11 newest first, progress
 * 0, 0, 0, 25 (1 of 4), 100. Course 1 (instructor 2, co-instructor 3): enrollments 9 (student 14), 8 (student 4),
 * 1 (student 6) with 0, 11 (1 of 9) and 44 (4 of 9) percent.
 */
@MySqlSeedSpringBootTest
class InstructorStudentControllerIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    SeedUserTokens tokens;
    @Autowired
    SqlStatementCounter sql;

    private MockHttpServletRequestBuilder req(long userId, String path) {
        return get(path).header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId));
    }

    private ResultActions as(long userId, String path) throws Exception {
        return mvc.perform(req(userId, path));
    }

    @Test
    void students_withDerivedProgress_noEmail_UCI19() throws Exception {
        as(2, "/api/v1/instructor/courses/2/students")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.content[*].enrollmentId", contains(17, 19, 14, 12, 11)))
                .andExpect(jsonPath("$.content[3].student.id").value(7))
                .andExpect(jsonPath("$.content[3].student.fullName").value("Hoàng Minh Tuấn"))
                .andExpect(jsonPath("$.content[3].enrollSource").value("free"))
                .andExpect(jsonPath("$.content[3].enrolledAt").value("2026-08-04T09:00:00Z"))
                .andExpect(jsonPath("$.content[3].completedLessons").value(1))
                .andExpect(jsonPath("$.content[3].totalLessons").value(4))
                .andExpect(jsonPath("$.content[3].progressPercent").value(25))
                .andExpect(jsonPath("$.content[3].lastAccessedAt").value("2026-08-05T21:00:00Z"))
                .andExpect(jsonPath("$.content[4].progressPercent").value(100))
                .andExpect(jsonPath("$.content[0].progressPercent").value(0))
                .andExpect(jsonPath("$.content[0].lastAccessedAt").value(nullValue()))
                .andExpect(jsonPath("$.content[0].student.email").doesNotExist());
    }

    @Test
    void students_purchasedCourse_progressFloor_andCoInstructorMayRead_UCI19() throws Exception {
        for (long instructor : new long[] {2, 3}) {
            as(instructor, "/api/v1/instructor/courses/1/students")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].enrollmentId", contains(9, 8, 1)))
                    .andExpect(jsonPath("$.content[*].enrollSource", contains("purchase", "purchase", "purchase")))
                    .andExpect(jsonPath("$.content[*].progressPercent", contains(0, 11, 44)))
                    .andExpect(jsonPath("$.content[2].student.fullName").value("Đỗ Thị Hương"));
        }
    }

    @Test
    void students_idor_unrelatedCourse_404IdenticalToUnknown() throws Exception {
        assertSameNotFound(mvc, req(2, "/api/v1/instructor/courses/5/students"),
                req(2, "/api/v1/instructor/courses/" + Long.MAX_VALUE + "/students"));
        // instructor 3 instructs course 1 only as an accepted co-instructor, not course 2
        assertSameNotFound(mvc, req(3, "/api/v1/instructor/courses/2/students"),
                req(3, "/api/v1/instructor/courses/" + Long.MAX_VALUE + "/students"));
        // pending invitation (course 5, instructor 4) is no relation yet
        assertSameNotFound(mvc, req(4, "/api/v1/instructor/courses/5/students"),
                req(4, "/api/v1/instructor/courses/" + Long.MAX_VALUE + "/students"));
    }

    @Test
    void students_noActiveInstructorRole_403() throws Exception {
        expectProblem(as(6, "/api/v1/instructor/courses/2/students"), 403, "ACCESS_DENIED");
        expectProblem(as(SeedUserTokens.ADMIN, "/api/v1/instructor/courses/2/students"), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(get("/api/v1/instructor/courses/3/students").header(HttpHeaders.AUTHORIZATION,
                tokens.bearer(SeedUserTokens.REVOKED_INSTRUCTOR, RoleCode.STUDENT, RoleCode.INSTRUCTOR))),
                403, "ACCESS_DENIED");
    }

    @Test
    void students_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get("/api/v1/instructor/courses/2/students")), 401, "UNAUTHENTICATED");
    }

    @Test
    void students_statementCount_constantAcrossPageSizes() throws Exception {
        as(2, "/api/v1/instructor/courses/2/students").andExpect(status().isOk()); // warm-up

        sql.reset();
        as(2, "/api/v1/instructor/courses/2/students?size=2").andExpect(jsonPath("$.content", hasSize(2)));
        // access check + page + count + names + lesson count
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(5);

        sql.reset();
        as(2, "/api/v1/instructor/courses/2/students?size=5").andExpect(jsonPath("$.content", hasSize(5)));
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(5);
    }
}
