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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * UC-I14 {@code GET /api/v1/instructor/invitations} on the dev seed (read-only). Invitations: 1 accepted (3 → course 1),
 * 2 pending (4 → course 5 of instructor 3), 3 declined (2 → course 9), 4 removed (5 → course 3).
 */
@MySqlSeedSpringBootTest
class InvitationControllerIT {

    static final String INVITATIONS = "/api/v1/instructor/invitations";

    @Autowired
    MockMvc mvc;
    @Autowired
    SeedUserTokens tokens;
    @Autowired
    SqlStatementCounter sql;

    private ResultActions as(long userId) throws Exception {
        return mvc.perform(get(INVITATIONS).header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId)));
    }

    @Test
    void pendingInvitation_courseAndMainInstructor_UCI14() throws Exception {
        as(SeedUserTokens.INSTRUCTOR_3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].courseId").value(5))
                .andExpect(jsonPath("$[0].courseTitle").value("Python cho phân tích dữ liệu"))
                .andExpect(jsonPath("$[0].courseSlug").value("python-cho-phan-tich-du-lieu"))
                .andExpect(jsonPath("$[0].mainInstructor.id").value(3))
                .andExpect(jsonPath("$[0].mainInstructor.fullName").value("Lê Thị Mai"))
                .andExpect(jsonPath("$[0].mainInstructor.email").doesNotExist())
                .andExpect(jsonPath("$[0].invitedAt").value("2026-10-25T09:00:00Z"));
    }

    @Test
    void acceptedOrDeclinedInvitations_notListed_UCI14() throws Exception {
        // instructor 2 declined course 9; instructor 3 accepted course 1
        as(SeedUserTokens.INSTRUCTOR).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
        as(SeedUserTokens.INSTRUCTOR_2).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
    }

    @Test
    void statementCount_invitationsPlusNames() throws Exception {
        as(SeedUserTokens.INSTRUCTOR_3).andExpect(status().isOk()); // warm-up
        as(SeedUserTokens.INSTRUCTOR).andExpect(status().isOk());

        sql.reset();
        as(SeedUserTokens.INSTRUCTOR_3).andExpect(jsonPath("$", hasSize(1)));
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(2);

        sql.reset();
        as(SeedUserTokens.INSTRUCTOR).andExpect(jsonPath("$", empty()));
        assertThat(sql.count()).as("no invitation: no names query " + sql.statements()).isEqualTo(1);
    }

    @Test
    void withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get(INVITATIONS)), 401, "UNAUTHENTICATED");
    }

    @Test
    void noActiveInstructorRole_403_UCI14() throws Exception {
        // UC-I14 precondition: an active instructor role in the database (BR-26)
        expectProblem(as(SeedUserTokens.STUDENT), 403, "ACCESS_DENIED");
        expectProblem(as(SeedUserTokens.ADMIN), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(get(INVITATIONS).header(HttpHeaders.AUTHORIZATION,
                tokens.bearer(SeedUserTokens.REVOKED_INSTRUCTOR, RoleCode.STUDENT, RoleCode.INSTRUCTOR))),
                403, "ACCESS_DENIED");
    }
}
