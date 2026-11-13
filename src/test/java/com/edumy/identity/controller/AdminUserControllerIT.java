package com.edumy.identity.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;

import com.edumy.shared.security.RoleCode;
import com.edumy.support.MySqlSeedSpringBootTest;
import com.edumy.support.SeedUserTokens;
import com.edumy.support.SqlStatementCounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * UC-A01 {@code GET /api/v1/admin/users} on the dev seed (read-only). Seed users: 20, created between 2026-06-01 (1)
 * and 2026-10-22 (19); newest first = 19, 18, 17, 20, 16, 15, 14, … 6, 5, 4, 3, 2, 1.
 */
@MySqlSeedSpringBootTest
class AdminUserControllerIT {

    static final String USERS = "/api/v1/admin/users";

    @Autowired
    MockMvc mvc;
    @Autowired
    SeedUserTokens tokens;
    @Autowired
    SqlStatementCounter sql;

    private ResultActions asAdmin(String query) throws Exception {
        return mvc.perform(get(USERS + query).header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUserTokens.ADMIN)));
    }

    // ------------------------------------------------------------------------------------------------------ 200

    @Test
    void list_noFilter_allSeedUsersNewestFirst_pagedModel_UCA01() throws Exception {
        asAdmin("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(20))
                .andExpect(jsonPath("$.page.totalPages").value(1))
                .andExpect(jsonPath("$.content", hasSize(20)))
                .andExpect(jsonPath("$.content[0].id").value(19))
                .andExpect(jsonPath("$.content[3].id").value(20))
                .andExpect(jsonPath("$.content[19].id").value(1));
    }

    @Test
    void list_row_showsVerificationStatusAndActiveRoles_UCA01() throws Exception {
        asAdmin("?q=hung.nguyen@example.com")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(2))
                .andExpect(jsonPath("$.content[0].email").value("hung.nguyen@example.com"))
                .andExpect(jsonPath("$.content[0].fullName").value("Nguyễn Văn Hùng"))
                .andExpect(jsonPath("$.content[0].emailVerified").value(true))
                .andExpect(jsonPath("$.content[0].status").value("active"))
                .andExpect(jsonPath("$.content[0].roles", contains("student", "instructor")))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-06-02T09:00:00Z"))
                .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist());
        // revoked instructor role is not shown (D-04); unverified; locked
        asAdmin("?q=tam.vo").andExpect(jsonPath("$.content[0].roles", contains("student")));
        asAdmin("?q=khai.ta").andExpect(jsonPath("$.content[0].emailVerified").value(false));
        asAdmin("?q=phuc.lam").andExpect(jsonPath("$.content[0].status").value("locked"));
        asAdmin("?q=admin@").andExpect(jsonPath("$.content[0].roles", contains("admin")));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', textBlock = """
            ?q=hung;                          2
            ?q=  Nguyen Van HUNG  ;           2
            ?q=ADMIN@EDUMY;                   1
            ?q=@edumy.vn;                     1
            ?q=thi;                           19|17|14|12|6|3
            ?status=locked;                   20
            ?role=instructor;                 4|3|2
            ?role=admin;                      1
            ?status=locked&role=student;      20
            ?q=mai&role=instructor;           3
            """)
    void list_filters_qStatusRole_UCA01(String query, String expectedIds) throws Exception {
        Integer[] ids = Arrays.stream(expectedIds.split("\\|")).map(Integer::valueOf).toArray(Integer[]::new);
        asAdmin(query)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(ids.length))
                .andExpect(jsonPath("$.content[*].id", contains(ids)));
    }

    @Test
    void list_statusAndRole_combined_UCA01() throws Exception {
        // students = users 2–20 (19); 20 is locked
        asAdmin("?status=active&role=student").andExpect(jsonPath("$.page.totalElements").value(18));
        asAdmin("?role=student").andExpect(jsonPath("$.page.totalElements").value(19));
        // blank parameters = no filter (an SPA "all" option)
        asAdmin("?q=&status=&role=").andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(20));
    }

    @Test
    void list_noMatch_emptyPage_UCA01() throws Exception {
        asAdmin("?q=khong-ton-tai")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void list_pagination_pageSizeAndMax100_sortIgnored() throws Exception {
        asAdmin("?page=1&size=5")
                .andExpect(jsonPath("$.content[*].id", contains(15, 14, 13, 12, 11)))
                .andExpect(jsonPath("$.page.size").value(5))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(20))
                .andExpect(jsonPath("$.page.totalPages").value(4));
        asAdmin("?page=9&size=5").andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.totalElements").value(20));
        // docs/ai/05 §5.1: size max 100 (clamped)
        asAdmin("?size=500").andExpect(status().isOk()).andExpect(jsonPath("$.page.size").value(100));
        // fixed order (newest first): a client sort does not change it
        asAdmin("?sort=email,asc&size=1").andExpect(jsonPath("$.content[0].id").value(19));
    }

    // ------------------------------------------------------------------------------------------------- no N+1

    @Test
    void list_statementCount_independentOfPageSize_noNPlusOne() throws Exception {
        asAdmin("").andExpect(status().isOk()); // warm-up: user-status cache of the admin

        sql.reset();
        asAdmin("?size=5").andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(5)));
        int small = sql.count();
        String smallStatements = sql.statements().toString();

        sql.reset();
        asAdmin("?size=20").andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(20)));
        int large = sql.count();

        // page + count + roles of the page, whatever the number of rows
        assertThat(small).as(smallStatements).isEqualTo(3);
        assertThat(large).as(sql.statements().toString()).isEqualTo(small);

        sql.reset();
        asAdmin("?q=khong-ton-tai").andExpect(status().isOk());
        assertThat(sql.count()).as("empty first page: no count, no roles query " + sql.statements()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------- 400 / 401 / 403

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', textBlock = """
            ?status=banned;    status
            ?status=ACTIVE;    status
            ?role=teacher;     role
            """)
    void list_unknownStatusOrRole_400ValidationFailed(String query, String field) throws Exception {
        expectProblem(asAdmin(query), 400, "VALIDATION_FAILED");
        asAdmin(query).andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void list_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get(USERS)), 401, "UNAUTHENTICATED");
    }

    @Test
    void list_lockedAdminToken_401() throws Exception {
        // user 20 is locked in the seed: the token is rejected whatever its roles claim
        expectProblem(mvc.perform(get(USERS).header(HttpHeaders.AUTHORIZATION,
                tokens.bearer(SeedUserTokens.LOCKED, RoleCode.ADMIN))), 401,
                "UNAUTHENTICATED");
    }

    @ParameterizedTest(name = "user {0}")
    @CsvSource({"6", "2", "5"})
    void list_nonAdmin_403(long userId) throws Exception {
        expectProblem(mvc.perform(get(USERS).header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId))), 403,
                "ACCESS_DENIED");
    }
}
