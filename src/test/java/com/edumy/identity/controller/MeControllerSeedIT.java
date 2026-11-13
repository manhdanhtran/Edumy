package com.edumy.identity.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edumy.shared.security.RoleCode;
import com.edumy.support.MySqlSeedSpringBootTest;
import com.edumy.support.SeedUserTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/** UC-G09 {@code GET /api/v1/me} on the dev seed (read-only); writes of /me are tested in {@link MeControllerIT}. */
@MySqlSeedSpringBootTest
class MeControllerSeedIT {

    static final String ME = "/api/v1/me";

    @Autowired
    MockMvc mvc;
    @Autowired
    SeedUserTokens tokens;

    @Test
    void getMe_instructor_profileAndBothRoles_UCG09() throws Exception {
        mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUserTokens.INSTRUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.email").value("hung.nguyen@example.com"))
                .andExpect(jsonPath("$.fullName").value("Nguyễn Văn Hùng"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.roles", contains("student", "instructor")))
                .andExpect(jsonPath("$.createdAt").value("2026-06-02T09:00:00Z"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void getMe_revokedInstructor_onlyStudent_evenIfJwtClaimsInstructor_D04_BR26() throws Exception {
        String staleClaim = tokens.bearer(SeedUserTokens.REVOKED_INSTRUCTOR, RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, staleClaim))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.roles", contains("student")));
    }

    @Test
    void getMe_adminAndUnverified_UCG09() throws Exception {
        mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUserTokens.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@edumy.vn"))
                .andExpect(jsonPath("$.roles", contains("admin")));
        mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUserTokens.UNVERIFIED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(false));
    }

    @Test
    void getMe_lockedUser_401() throws Exception {
        expectProblem(mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUserTokens.LOCKED))),
                401, "UNAUTHENTICATED");
    }
}
