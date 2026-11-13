package com.edumy.authoring.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edumy.shared.security.RoleCode;
import com.edumy.support.MySqlSeedSpringBootTest;
import com.edumy.support.SeedUserTokens;
import com.edumy.support.SqlStatementCounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * UC-A09 {@code GET /api/v1/admin/courses/pending} on the dev seed (read-only): one pending_review course (12, main
 * instructor 3). The statement count with several instructors is in {@code AuthoringReadsFixtureIT}.
 */
@MySqlSeedSpringBootTest
class AdminCourseReviewControllerIT {

    static final String PENDING = "/api/v1/admin/courses/pending";

    @Autowired
    MockMvc mvc;
    @Autowired
    SeedUserTokens tokens;
    @Autowired
    SqlStatementCounter sql;

    private ResultActions as(long userId, String query) throws Exception {
        return mvc.perform(get(PENDING + query).header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId)));
    }

    @Test
    void pending_queueWithInstructorAndCategory_UCA09() throws Exception {
        as(SeedUserTokens.ADMIN, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(12))
                .andExpect(jsonPath("$.content[0].title").value("Deep Learning với PyTorch"))
                .andExpect(jsonPath("$.content[0].slug").value("deep-learning-voi-pytorch"))
                .andExpect(jsonPath("$.content[0].price").value(899000))
                .andExpect(jsonPath("$.content[0].isFree").value(false))
                .andExpect(jsonPath("$.content[0].categoryId").value(3))
                .andExpect(jsonPath("$.content[0].categoryName").value("Khoa học dữ liệu"))
                .andExpect(jsonPath("$.content[0].mainInstructor.id").value(3))
                .andExpect(jsonPath("$.content[0].mainInstructor.fullName").value("Lê Thị Mai"))
                .andExpect(jsonPath("$.content[0].submittedAt").value("2026-10-28T09:00:00Z"));
        as(SeedUserTokens.ADMIN, "?page=1").andExpect(jsonPath("$.content", empty()));
    }

    @Test
    void pending_statementCount() throws Exception {
        as(SeedUserTokens.ADMIN, "").andExpect(status().isOk()); // warm-up

        sql.reset();
        as(SeedUserTokens.ADMIN, "?size=1").andExpect(jsonPath("$.content", hasSize(1)));
        // full first page: page + count + instructor names
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(3);

        sql.reset();
        as(SeedUserTokens.ADMIN, "?size=20").andExpect(jsonPath("$.content", hasSize(1)));
        assertThat(sql.count()).as("first page not full: no count " + sql.statements()).isEqualTo(2);
    }

    @Test
    void withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get(PENDING)), 401, "UNAUTHENTICATED");
    }

    @ParameterizedTest(name = "user {0}")
    @ValueSource(longs = {SeedUserTokens.INSTRUCTOR, SeedUserTokens.INSTRUCTOR_2, SeedUserTokens.STUDENT})
    void nonAdmin_403(long userId) throws Exception {
        expectProblem(as(userId, ""), 403, "ACCESS_DENIED");
    }

    @Test
    void revokedInstructor_forgedClaim_403() throws Exception {
        expectProblem(mvc.perform(get(PENDING).header(HttpHeaders.AUTHORIZATION,
                tokens.bearer(SeedUserTokens.REVOKED_INSTRUCTOR, RoleCode.STUDENT, RoleCode.INSTRUCTOR))),
                403, "ACCESS_DENIED");
    }
}
