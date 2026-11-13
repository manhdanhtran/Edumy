package com.edumy.commerce.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.equalTo;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * UC-A13 {@code GET /admin/orders} and UC-A14 {@code GET /admin/payments} on the dev seed (read-only): 14 orders
 * (pending 4, 10; paid 1, 2, 7, 8, 11-14; cancelled 3, 5, 6, 9) and 15 attempts (pending 6; success 1, 3, 8, 9, 12-15;
 * failed 2; expired 4, 5, 7; needs_review 10 (handled, refund_outside by admin 1) and 11 (unhandled)).
 */
@MySqlSeedSpringBootTest
class AdminOrderControllerIT {

    static final String ORDERS = "/api/v1/admin/orders";
    static final String PAYMENTS = "/api/v1/admin/payments";

    @Autowired
    MockMvc mvc;
    @Autowired
    SeedUserTokens tokens;
    @Autowired
    SqlStatementCounter sql;

    private ResultActions admin(String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUserTokens.ADMIN)));
    }

    // ---- UC-A13

    @Test
    void orders_newestFirstWithStudent_UCA13() throws Exception {
        admin(ORDERS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(14))
                .andExpect(jsonPath("$.content", hasSize(14)))
                .andExpect(jsonPath("$.content[0].orderCode").value("ORD-20261101-0004"))
                .andExpect(jsonPath("$.content[0].status").value("pending"))
                .andExpect(jsonPath("$.content[0].student.id").value(8))
                .andExpect(jsonPath("$.content[0].student.fullName").value("Bùi Thu Trang"))
                .andExpect(jsonPath("$.content[0].student.email").value("trang.bui@example.com"))
                .andExpect(jsonPath("$.content[0].totalAmount").value(849000))
                .andExpect(jsonPath("$.content[0].itemCount").value(2))
                .andExpect(jsonPath("$.content[13].orderCode").value("ORD-20260905-0001"));
    }

    @Test
    void orders_filters_UCA13() throws Exception {
        admin(ORDERS + "?status=pending")
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].status", everyItem(equalTo("pending"))));
        admin(ORDERS + "?status=cancelled")
                .andExpect(jsonPath("$.page.totalElements").value(4));
        admin(ORDERS + "?orderCode=ord-20261010-0007")
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].student.id").value(9));
        admin(ORDERS + "?email=Huong.Do@example.com")
                .andExpect(jsonPath("$.content[*].orderCode", contains("ORD-20260905-0001")));
        admin(ORDERS + "?email=nobody@example.com")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.totalElements").value(0));
        // [from, to): the orders of 10 October only (6 and 7)
        admin(ORDERS + "?createdFrom=2026-10-10T00:00:00Z&createdTo=2026-10-11T00:00:00Z")
                .andExpect(jsonPath("$.content[*].orderCode", contains("ORD-20261010-0007", "ORD-20261010-0006")));
        admin(ORDERS + "?status=paid&email=viet.dang@example.com")
                .andExpect(jsonPath("$.content[*].orderCode", contains("ORD-20261010-0007")));
    }

    @Test
    void orders_blankFiltersAreIgnored_UCA13() throws Exception {
        admin(ORDERS + "?status=&orderCode=&email=")
                .andExpect(jsonPath("$.page.totalElements").value(14));
    }

    @Test
    void orders_badFilters_400() throws Exception {
        expectProblem(admin(ORDERS + "?status=shipped"), 400, "VALIDATION_FAILED");
        expectProblem(admin(ORDERS + "?createdFrom=yesterday"), 400, "VALIDATION_FAILED");
    }

    @Test
    void orders_statementCount_constantAcrossPageSizes() throws Exception {
        admin(ORDERS).andExpect(status().isOk()); // warm-up

        sql.reset();
        admin(ORDERS + "?size=5").andExpect(jsonPath("$.content", hasSize(5)));
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(3); // page + count + names

        sql.reset();
        admin(ORDERS + "?size=14").andExpect(jsonPath("$.content", hasSize(14)));
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(3);
    }

    @Test
    void orders_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get(ORDERS)), 401, "UNAUTHENTICATED");
    }

    @ParameterizedTest(name = "user {0}")
    @ValueSource(longs = {SeedUserTokens.INSTRUCTOR, SeedUserTokens.STUDENT})
    void nonAdmin_403(long userId) throws Exception {
        expectProblem(mvc.perform(get(ORDERS).header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId))),
                403, "ACCESS_DENIED");
        expectProblem(mvc.perform(get(PAYMENTS).header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId))),
                403, "ACCESS_DENIED");
    }

    @Test
    void forgedAdminClaimOfARevokedInstructor_stillNotAdmin_403() throws Exception {
        expectProblem(mvc.perform(get(ORDERS).header(HttpHeaders.AUTHORIZATION,
                tokens.bearer(SeedUserTokens.REVOKED_INSTRUCTOR, RoleCode.STUDENT))), 403, "ACCESS_DENIED");
    }

    // ---- UC-A14

    @Test
    void payments_newestFirst_UCA14() throws Exception {
        admin(PAYMENTS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(15))
                .andExpect(jsonPath("$.content", hasSize(15)))
                .andExpect(jsonPath("$.content[0].transactionRef").value("FAKEPAY-20261101-0006"))
                .andExpect(jsonPath("$.content[0].status").value("pending"))
                .andExpect(jsonPath("$.content[0].orderCode").value("ORD-20261101-0004"))
                .andExpect(jsonPath("$.content[0].student.fullName").value("Bùi Thu Trang"))
                .andExpect(jsonPath("$.content[0].amount").value(849000))
                .andExpect(jsonPath("$.content[0].qrExpiresAt").value("2026-11-01T08:35:00Z"))
                .andExpect(jsonPath("$.content[0].handledBy").value(nullValue()))
                .andExpect(jsonPath("$.content[0].qrData").doesNotExist());
    }

    @Test
    void payments_statusFilter_UCA14() throws Exception {
        admin(PAYMENTS + "?status=needs_review")
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].id", contains(11, 10)));
        admin(PAYMENTS + "?status=failed")
                .andExpect(jsonPath("$.content[*].id", contains(2)));
        admin(PAYMENTS + "?status=expired")
                .andExpect(jsonPath("$.page.totalElements").value(3));
        admin(PAYMENTS + "?status=")
                .andExpect(jsonPath("$.page.totalElements").value(15));
    }

    @Test
    void payments_reconciliationFields_UCA14() throws Exception {
        admin(PAYMENTS + "?status=needs_review")
                // attempt 10: handled by admin 1 as refund_outside
                .andExpect(jsonPath("$.content[1].id").value(10))
                .andExpect(jsonPath("$.content[1].handledBy.id").value(1))
                .andExpect(jsonPath("$.content[1].handledBy.fullName").value("Trần Minh Quân"))
                .andExpect(jsonPath("$.content[1].handledAction").value("refund_outside"))
                .andExpect(jsonPath("$.content[1].handledNote").isNotEmpty())
                .andExpect(jsonPath("$.content[1].handledAt").value("2026-10-14T10:00:00Z"))
                // attempt 11: not handled yet
                .andExpect(jsonPath("$.content[0].id").value(11))
                .andExpect(jsonPath("$.content[0].handledBy").value(nullValue()))
                .andExpect(jsonPath("$.content[0].handledAction").value(nullValue()))
                .andExpect(jsonPath("$.content[0].handledNote").value(nullValue()));
        // activated attempt 12 (status success)
        admin(PAYMENTS + "?status=success")
                .andExpect(jsonPath("$.content[?(@.id == 12)].handledAction").value(contains("activated")));
    }

    @Test
    void payments_awaitingReviewQueue_UCA14() throws Exception {
        admin(PAYMENTS + "?awaitingReview=true")
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(11))
                .andExpect(jsonPath("$.content[0].status").value("needs_review"))
                .andExpect(jsonPath("$.content[0].orderCode").value("ORD-20261031-0010"))
                .andExpect(jsonPath("$.content[0].student.id").value(12));
        admin(PAYMENTS + "?awaitingReview=false")
                .andExpect(jsonPath("$.page.totalElements").value(15));
    }

    @Test
    void payments_badStatus_400() throws Exception {
        expectProblem(admin(PAYMENTS + "?status=done"), 400, "VALIDATION_FAILED");
    }

    @Test
    void payments_statementCount_constantAcrossPageSizes() throws Exception {
        admin(PAYMENTS).andExpect(status().isOk()); // warm-up

        sql.reset();
        admin(PAYMENTS + "?size=5").andExpect(jsonPath("$.content", hasSize(5)));
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(3); // page + count + names

        sql.reset();
        admin(PAYMENTS + "?size=15").andExpect(jsonPath("$.content", hasSize(15)));
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(3);
    }

    @Test
    void payments_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get(PAYMENTS)), 401, "UNAUTHENTICATED");
    }
}
