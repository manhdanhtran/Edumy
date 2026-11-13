package com.edumy.commerce.controller;

import static com.edumy.support.IdorAssertions.assertSameNotFound;
import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * UC-S02 / UC-S05 polling / UC-S06 / UC-S07 on the dev seed (read-only). Seed: cart 2 = student 11 (hidden course 10 +
 * course 3); order 1 = student 6 (course 1 frozen at 699 000, now 799 000); order 4 = student 8 (pending, pending
 * attempt 6); order 3 = student 8 (cancelled, expired attempt 4); order 6 = student 9 (cancelled, no attempt);
 * student 9 has orders 5, 6, 7.
 */
@MySqlSeedSpringBootTest
class StudentCommerceReadsIT {

    static final String ORDER_1 = "ORD-20260905-0001"; // student 6
    static final String ORDER_2 = "ORD-20260910-0002"; // student 7: failed attempt 2, then success attempt 3
    static final String ORDER_3 = "ORD-20260915-0003"; // student 8: cancelled (user), expired attempt
    static final String ORDER_4 = "ORD-20261101-0004"; // student 8: pending, pending attempt 6
    static final String ORDER_6 = "ORD-20261010-0006"; // student 9: cancelled (replaced), no attempt
    static final long STUDENT_A = 6;
    static final long STUDENT_B = 7;

    @Autowired
    MockMvc mvc;
    @Autowired
    SeedUserTokens tokens;
    @Autowired
    SqlStatementCounter sql;

    private ResultActions as(long userId, String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId)));
    }

    // ---- UC-S02 cart

    @Test
    void cart_withHiddenCourse_flagsItAndLeavesItOutOfTheTotal_UCS02() throws Exception {
        as(11, "/api/v1/cart")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.purchasableCount").value(1))
                .andExpect(jsonPath("$.totalAmount").value(599000))
                .andExpect(jsonPath("$.items[0].courseId").value(10)) // added first
                .andExpect(jsonPath("$.items[0].purchasable").value(false))
                .andExpect(jsonPath("$.items[0].unavailableReason").value("course_unavailable"))
                .andExpect(jsonPath("$.items[0].title").isNotEmpty())
                .andExpect(jsonPath("$.items[1].courseId").value(3))
                .andExpect(jsonPath("$.items[1].purchasable").value(true))
                .andExpect(jsonPath("$.items[1].unavailableReason").value(nullValue()))
                .andExpect(jsonPath("$.items[1].title").value("ReactJS thực chiến: Xây dựng website thương mại điện tử"))
                .andExpect(jsonPath("$.items[1].slug").value("reactjs-thuc-chien-xay-dung-website-thuong-mai-dien-tu"))
                .andExpect(jsonPath("$.items[1].thumbnailUrl").value("https://cdn.edumy.vn/thumbnails/course-3.jpg"))
                .andExpect(jsonPath("$.items[1].price").value(599000))
                .andExpect(jsonPath("$.items[1].isFree").value(false))
                .andExpect(jsonPath("$.items[1].instructor.id").value(2))
                .andExpect(jsonPath("$.items[1].instructor.fullName").value("Nguyễn Văn Hùng"))
                .andExpect(jsonPath("$.items[1].addedAt").value("2026-10-06T08:00:00Z"));
    }

    @Test
    void cart_allPurchasable_totalIsTheSumOfCurrentPrices_UCS02() throws Exception {
        as(16, "/api/v1/cart") // cart 4: course 1 at its current price 799 000
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].price").value(799000))
                .andExpect(jsonPath("$.totalAmount").value(799000))
                .andExpect(jsonPath("$.purchasableCount").value(1));
    }

    @Test
    void cart_withoutCart_isEmptyAndWritesNothing_UCS02() throws Exception {
        as(STUDENT_A, "/api/v1/cart")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.itemCount").value(0))
                .andExpect(jsonPath("$.totalAmount").value(0));
        // reading again: still no cart row was created (a second read costs the same single statement)
        as(STUDENT_A, "/api/v1/cart").andExpect(status().isOk());
        sql.reset();
        as(STUDENT_A, "/api/v1/cart").andExpect(status().isOk());
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(1);
    }

    @Test
    void cart_statementCount_constant() throws Exception {
        for (long student : new long[] {8, 11, 12, 17}) {
            as(student, "/api/v1/cart").andExpect(status().isOk()); // warm-up (user-status cache per student)
            sql.reset();
            as(student, "/api/v1/cart").andExpect(jsonPath("$.items", hasSize(2)));
            // cart + items, courses, owned courses, instructor names
            assertThat(sql.count()).as("student " + student + " " + sql.statements()).isEqualTo(4);
        }
    }

    @Test
    void cart_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get("/api/v1/cart")), 401, "UNAUTHENTICATED");
    }

    // ---- UC-S06 order history

    @Test
    void orders_newestFirst_withStatusAndItemCount_UCS06() throws Exception {
        as(9, "/api/v1/orders")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].orderCode",
                        contains("ORD-20261010-0007", ORDER_6, "ORD-20260920-0005")))
                .andExpect(jsonPath("$.content[0].status").value("paid"))
                .andExpect(jsonPath("$.content[0].totalAmount").value(1049000))
                .andExpect(jsonPath("$.content[0].itemCount").value(2))
                .andExpect(jsonPath("$.content[0].paidAt").value("2026-10-10T10:06:00Z"))
                .andExpect(jsonPath("$.content[0].cancelReason").value(nullValue()))
                .andExpect(jsonPath("$.content[1].status").value("cancelled"))
                .andExpect(jsonPath("$.content[1].cancelReason").value("replaced"))
                .andExpect(jsonPath("$.content[1].itemCount").value(1))
                .andExpect(jsonPath("$.content[1].cancelledAt").value("2026-10-10T10:00:00Z"))
                .andExpect(jsonPath("$.content[2].cancelReason").value("expired"))
                .andExpect(jsonPath("$.content[0].id").doesNotExist());
    }

    @Test
    void orders_onlyOwn_andEmptyForStudentWithoutOrders_UCS06() throws Exception {
        as(STUDENT_A, "/api/v1/orders")
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].orderCode").value(ORDER_1));
        as(16, "/api/v1/orders").andExpect(status().isOk()).andExpect(jsonPath("$.content", empty()));
    }

    @Test
    void orders_statementCount_constantAcrossPageSizes() throws Exception {
        as(9, "/api/v1/orders").andExpect(status().isOk()); // warm-up

        sql.reset();
        as(9, "/api/v1/orders?size=1").andExpect(jsonPath("$.content", hasSize(1)));
        int small = sql.count();
        assertThat(small).as(sql.statements().toString()).isEqualTo(2); // page + count

        sql.reset();
        as(9, "/api/v1/orders?size=3").andExpect(jsonPath("$.content", hasSize(3)));
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(2);
    }

    @Test
    void orders_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get("/api/v1/orders")), 401, "UNAUTHENTICATED");
    }

    // ---- UC-S07 order detail

    @Test
    void orderDetail_showsPriceSnapshotNotCurrentPrice_UCS07() throws Exception {
        as(STUDENT_A, "/api/v1/orders/" + ORDER_1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCode").value(ORDER_1))
                .andExpect(jsonPath("$.status").value("paid"))
                .andExpect(jsonPath("$.totalAmount").value(1298000))
                .andExpect(jsonPath("$.items", hasSize(2)))
                // BR-12: course 1 was bought at 699 000, its current price is 799 000
                .andExpect(jsonPath("$.items[?(@.courseId == 1)].unitPrice").value(contains(699000)))
                .andExpect(jsonPath("$.items[?(@.courseId == 3)].unitPrice").value(contains(599000)))
                .andExpect(jsonPath("$.items[?(@.courseId == 1)].title")
                        .value(contains("Lập trình Java Spring Boot từ cơ bản đến nâng cao")))
                .andExpect(jsonPath("$.payments", hasSize(1)))
                .andExpect(jsonPath("$.payments[0].transactionRef").value("FAKEPAY-20260905-0001"))
                .andExpect(jsonPath("$.payments[0].status").value("success"))
                .andExpect(jsonPath("$.payments[0].amount").value(1298000))
                .andExpect(jsonPath("$.payments[0].confirmedAt").value("2026-09-05T10:03:00Z"))
                .andExpect(jsonPath("$.payments[0].qrData").doesNotExist());
        // the current price of course 1 really differs from the snapshot
        as(16, "/api/v1/cart").andExpect(jsonPath("$.items[0].price").value(799000));
    }

    @Test
    void orderDetail_listsEveryAttemptNewestFirst_UCS07() throws Exception {
        as(STUDENT_B, "/api/v1/orders/" + ORDER_2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[*].transactionRef",
                        contains("FAKEPAY-20260910-0003", "FAKEPAY-20260910-0002")))
                .andExpect(jsonPath("$.payments[*].status", contains("success", "failed")));
    }

    @Test
    void orderDetail_cancelledOrderKeepsReason_UCS07() throws Exception {
        as(8, "/api/v1/orders/" + ORDER_3)
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.cancelReason").value("user"))
                .andExpect(jsonPath("$.cancelledAt").value("2026-09-15T20:30:00Z"));
    }

    @Test
    void orderDetail_otherStudentsOrder_sameNotFoundAsUnknown_IDOR() throws Exception {
        assertSameNotFound(mvc,
                get("/api/v1/orders/" + ORDER_1).header(HttpHeaders.AUTHORIZATION, tokens.bearer(STUDENT_B)),
                get("/api/v1/orders/ORD-20990101-9999").header(HttpHeaders.AUTHORIZATION, tokens.bearer(STUDENT_B)));
    }

    @Test
    void orderDetail_adminIsNotTheOwnerEither_404() throws Exception {
        expectProblem(as(SeedUserTokens.ADMIN, "/api/v1/orders/" + ORDER_1), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void orderDetail_statementCount() throws Exception {
        as(STUDENT_A, "/api/v1/orders/" + ORDER_1).andExpect(status().isOk()); // warm-up
        sql.reset();
        as(STUDENT_A, "/api/v1/orders/" + ORDER_1).andExpect(jsonPath("$.items", hasSize(2)));
        // order with lines, courses, attempts
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(3);
    }

    @Test
    void orderDetail_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get("/api/v1/orders/" + ORDER_1)), 401, "UNAUTHENTICATED");
    }

    // ---- UC-S05 polling

    @Test
    void currentPayment_pendingAttemptWithQr_UCS05() throws Exception {
        as(8, "/api/v1/orders/" + ORDER_4 + "/payments/current")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCode").value(ORDER_4))
                .andExpect(jsonPath("$.orderStatus").value("pending"))
                .andExpect(jsonPath("$.transactionRef").value("FAKEPAY-20261101-0006")) // not expired attempt 5
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.amount").value(849000))
                .andExpect(jsonPath("$.qrData").value("FAKEQR|EDUMY|ORD-20261101-0004|849000"))
                .andExpect(jsonPath("$.qrExpiresAt").value("2026-11-01T08:35:00Z"));
    }

    @Test
    void currentPayment_noPendingAttempt_returnsLatestWithoutQr_UCS05() throws Exception {
        as(8, "/api/v1/orders/" + ORDER_3 + "/payments/current")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("cancelled"))
                .andExpect(jsonPath("$.status").value("expired"))
                .andExpect(jsonPath("$.qrData").value(nullValue()));
        as(STUDENT_B, "/api/v1/orders/" + ORDER_2 + "/payments/current")
                .andExpect(jsonPath("$.orderStatus").value("paid"))
                .andExpect(jsonPath("$.transactionRef").value("FAKEPAY-20260910-0003"))
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.confirmedAt").value("2026-09-10T14:25:00Z"))
                .andExpect(jsonPath("$.qrData").value(nullValue()));
    }

    @Test
    void currentPayment_orderWithoutAttempt_404() throws Exception {
        expectProblem(as(9, "/api/v1/orders/" + ORDER_6 + "/payments/current"), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void currentPayment_otherStudentsOrder_sameNotFoundAsUnknown_IDOR() throws Exception {
        assertSameNotFound(mvc,
                get("/api/v1/orders/" + ORDER_4 + "/payments/current")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(STUDENT_B)),
                get("/api/v1/orders/ORD-20990101-9999/payments/current")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(STUDENT_B)));
    }

    @Test
    void currentPayment_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get("/api/v1/orders/" + ORDER_4 + "/payments/current")), 401, "UNAUTHENTICATED");
    }

    @ParameterizedTest(name = "caller {0}")
    @ValueSource(longs = {SeedUserTokens.INSTRUCTOR, SeedUserTokens.STUDENT})
    void secretsNeverInTheResponses(long userId) throws Exception {
        String body = as(userId, "/api/v1/orders").andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("qrData", "handledNote", "password");
    }
}
