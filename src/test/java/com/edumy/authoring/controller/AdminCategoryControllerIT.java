package com.edumy.authoring.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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
 * UC-A06…A08 {@code GET /api/v1/admin/categories} on the dev seed (read-only): 8 categories, 8 inactive and used by
 * draft course 14 (D-21); courses per category 1 → 4 (1, 2, 3, 11), 2 → 1, 3 → 3 (5, 6, 12), 4 → 2 (7, 13), 5–8 → 1.
 */
@MySqlSeedSpringBootTest
class AdminCategoryControllerIT {

    static final String CATEGORIES = "/api/v1/admin/categories";

    @Autowired
    MockMvc mvc;
    @Autowired
    SeedUserTokens tokens;
    @Autowired
    SqlStatementCounter sql;

    private ResultActions as(long userId) throws Exception {
        return mvc.perform(get(CATEGORIES).header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId)));
    }

    @Test
    void list_allCategoriesByName_inactiveToo_withCourseCount_UCA08_D21() throws Exception {
        as(SeedUserTokens.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)))
                // utf8mb4_0900_ai_ci order: Khoa < Kinh < Lập trình di động < Lập trình web < Marketing < Ngoại < Nhiếp < Thiết
                .andExpect(jsonPath("$[*].id", contains(3, 5, 2, 1, 7, 6, 8, 4)))
                .andExpect(jsonPath("$[*].courseCount", contains(3, 1, 1, 4, 1, 1, 1, 2)))
                .andExpect(jsonPath("$[3].name").value("Lập trình web"))
                .andExpect(jsonPath("$[3].slug").value("lap-trinh-web"))
                .andExpect(jsonPath("$[3].active").value(true))
                .andExpect(jsonPath("$[3].createdAt").value("2026-06-01T08:30:00Z"))
                .andExpect(jsonPath("$[3].updatedAt").value("2026-06-01T08:30:00Z"))
                .andExpect(jsonPath("$[6].name").value("Nhiếp ảnh"))
                .andExpect(jsonPath("$[6].active").value(false))
                .andExpect(jsonPath("$[6].updatedAt").value("2026-10-25T09:00:00Z"));
    }

    @Test
    void list_oneStatement_noNPlusOne() throws Exception {
        as(SeedUserTokens.ADMIN).andExpect(status().isOk()); // warm-up

        sql.reset();
        as(SeedUserTokens.ADMIN).andExpect(jsonPath("$", hasSize(8)));
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(1);
    }

    @Test
    void withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get(CATEGORIES)), 401, "UNAUTHENTICATED");
    }

    @ParameterizedTest(name = "user {0}")
    @ValueSource(longs = {SeedUserTokens.INSTRUCTOR, SeedUserTokens.STUDENT})
    void nonAdmin_403(long userId) throws Exception {
        expectProblem(as(userId), 403, "ACCESS_DENIED");
    }

    @Test
    void revokedInstructor_forgedClaim_403() throws Exception {
        expectProblem(mvc.perform(get(CATEGORIES).header(HttpHeaders.AUTHORIZATION,
                tokens.bearer(SeedUserTokens.REVOKED_INSTRUCTOR, RoleCode.STUDENT, RoleCode.INSTRUCTOR))),
                403, "ACCESS_DENIED");
    }
}
