package com.edumy.commerce.controller;

import static com.edumy.support.IdorAssertions.assertSameNotFound;
import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;


import com.edumy.support.MutableClock;
import com.edumy.support.MySqlSeedSpringBootTest;
import com.edumy.support.SeedUserTokens;
import com.edumy.support.SqlStatementCounter;
import com.edumy.shared.security.RoleCode;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The remaining phase-1 commerce stub (ADR-005, P07-commerce) on the dev seed, read-only:
 * POST /admin/payments/{id}/resolve (UC-A18); POST /orders, cancel (P11-b, {@code OrderWriteIT}) and create QR (P11-c,
 * {@code PaymentQrIT}) are real, the webhook since P11-a ({@code PaymentWebhookIT}). Every call that reaches a stub goes through {@link #stub}: it proves that the
 * row counts of carts, cart_items, orders, order_items, payment_transactions and enrollments are unchanged and that no
 * INSERT / UPDATE / DELETE / locking statement ran.
 * <p>Seed: cart 4 = student 16 (course 1, 799 000), cart 2 = student 11 (hidden course 10 + course 3), cart 5 = student
 * 17 (courses 4, 8); order 4 = student 8 (pending, attempts 5 expired + 6 pending until 2026-11-01 08:35), order 3 =
 * student 8 (cancelled), order 1 = student 6 (paid), order 10 = student 12 (pending, attempt 11 needs_review); payment
 * 11 = needs_review, unhandled (order 10, student 12 owns only course 6, the order is for course 9), 10 = needs_review
 * handled, 6 = pending, 1 = success.
 */
@MySqlSeedSpringBootTest
class CommerceStubsIT {

    static final String ORDER_1 = "ORD-20260905-0001"; // student 6, paid
    static final String ORDER_3 = "ORD-20260915-0003"; // student 8, cancelled
    static final String ORDER_4 = "ORD-20261101-0004"; // student 8, pending, attempt 6 pending
    static final String ORDER_10 = "ORD-20261031-0010"; // student 12, pending, no pending attempt
    static final String UNKNOWN_ORDER = "ORD-20260101-9999";
    static final Pattern WRITE = Pattern.compile("^\\s*(insert|update|delete|replace|alter|truncate|create|drop)\\b",
            Pattern.CASE_INSENSITIVE);
    static final List<String> TABLES = List.of("carts", "cart_items", "orders", "order_items",
            "payment_transactions", "enrollments");

    @Autowired
    MockMvc mvc;
    @Autowired
    SeedUserTokens tokens;
    @Autowired
    SqlStatementCounter sql;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MutableClock clock;

    // ---- helpers

    private Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        TABLES.forEach(t -> counts.put(t, jdbc.sql("select count(*) from " + t).query(Long.class).single()));
        return counts;
    }

    /** Performs {@code request}, then asserts that nothing was written (rows and statements). */
    private ResultActions stub(RequestBuilder request) throws Exception {
        Map<String, Long> before = counts();
        sql.reset();
        ResultActions result = mvc.perform(request);
        List<String> statements = sql.statements();
        assertThat(statements).as("no write statement: " + statements)
                .noneMatch(s -> WRITE.matcher(s).find() || s.toLowerCase().contains(" for update"));
        assertThat(counts()).as("row counts unchanged").isEqualTo(before);
        return result;
    }

    private MockHttpServletRequestBuilder asUser(long userId, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions stubbedOrderPost(long userId, String path) throws Exception {
        return stub(asUser(userId, post(path)));
    }

    // ---- POST /admin/payments/{id}/resolve (UC-A18)

    private ResultActions resolve(long userId, long paymentId, String body) throws Exception {
        return stub(json(asUser(userId, post("/api/v1/admin/payments/" + paymentId + "/resolve")), body));
    }

    @Test
    void resolve_activated_returnsThePaymentAsHandled_200_UCA18() throws Exception {
        resolve(SeedUserTokens.ADMIN, 11, "{\"action\":\"activated\",\"note\":\"  Đã đối chiếu sao kê ngân hàng  \"}")
                .andExpect(status().isOk())
                .andExpect(header().string("X-Edumy-Stub", "true"))
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.orderCode").value(ORDER_10))
                .andExpect(jsonPath("$.student.id").value(12))
                .andExpect(jsonPath("$.student.email").isNotEmpty())
                .andExpect(jsonPath("$.transactionRef").value("FAKEPAY-20261031-0011"))
                .andExpect(jsonPath("$.amount").value(450000))
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.confirmedAt").value(notNullValue()))
                .andExpect(jsonPath("$.handledBy.id").value(1))
                .andExpect(jsonPath("$.handledBy.fullName").isNotEmpty())
                .andExpect(jsonPath("$.handledAction").value("activated"))
                .andExpect(jsonPath("$.handledNote").value("Đã đối chiếu sao kê ngân hàng"))
                .andExpect(jsonPath("$.handledAt").value(notNullValue()));
        // nothing saved: the queue still holds payment 11, unhandled
        mvc.perform(asUser(SeedUserTokens.ADMIN, get("/api/v1/admin/payments?awaitingReview=true")))
                .andExpect(jsonPath("$.content[*].id", contains(11)))
                .andExpect(jsonPath("$.content[0].handledAt").value(nullValue()));
    }

    @Test
    void resolve_refundOutside_keepsTheAttemptResult_200_UCA18() throws Exception {
        resolve(SeedUserTokens.ADMIN, 11, "{\"action\":\"refund_outside\",\"note\":\"Đã hoàn tiền qua chuyển khoản\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("needs_review"))
                .andExpect(jsonPath("$.confirmedAt").value(nullValue()))
                .andExpect(jsonPath("$.handledAction").value("refund_outside"))
                .andExpect(jsonPath("$.handledBy.id").value(1))
                .andExpect(jsonPath("$.handledAt").value(notNullValue()));
    }

    @Test
    void resolve_alreadyHandledOrNotNeedsReview_409_UCA18_E1() throws Exception {
        String body = "{\"action\":\"activated\",\"note\":\"x\"}";
        expectProblem(resolve(SeedUserTokens.ADMIN, 10, body), 409, "DATA_CONFLICT"); // needs_review, handled
        expectProblem(resolve(SeedUserTokens.ADMIN, 6, body), 409, "DATA_CONFLICT"); // pending
        expectProblem(resolve(SeedUserTokens.ADMIN, 1, body), 409, "DATA_CONFLICT"); // success
    }

    @Test
    void resolve_unknownPayment_404_UCA18() throws Exception {
        expectProblem(resolve(SeedUserTokens.ADMIN, 999_999, "{\"action\":\"activated\",\"note\":\"x\"}"), 404,
                "RESOURCE_NOT_FOUND");
    }

    @Test
    void resolve_invalidBody_400_UCA18_E3() throws Exception {
        expectProblem(resolve(SeedUserTokens.ADMIN, 11, "{\"action\":\"activated\"}"), 400, "VALIDATION_FAILED");
        expectProblem(resolve(SeedUserTokens.ADMIN, 11, "{\"action\":\"activated\",\"note\":\"   \"}"), 400,
                "VALIDATION_FAILED");
        expectProblem(resolve(SeedUserTokens.ADMIN, 11, "{\"action\":\"delete\",\"note\":\"x\"}"), 400,
                "VALIDATION_FAILED");
        expectProblem(resolve(SeedUserTokens.ADMIN, 11, "{\"action\":\"activated\",\"note\":\""
                + "x".repeat(16_384) + "\"}"), 400, "VALIDATION_FAILED");
        expectProblem(stub(asUser(SeedUserTokens.ADMIN, post("/api/v1/admin/payments/11/resolve"))), 400,
                "MALFORMED_REQUEST");
    }

    @Test
    void resolve_nonAdmin_403_andStaleAdminToken_403_BR26() throws Exception {
        String body = "{\"action\":\"activated\",\"note\":\"x\"}";
        expectProblem(resolve(SeedUserTokens.STUDENT, 11, body), 403, "ACCESS_DENIED");
        expectProblem(resolve(SeedUserTokens.INSTRUCTOR, 11, body), 403, "ACCESS_DENIED");
        // the JWT still claims admin but the database says student: the service re-check refuses
        expectProblem(stub(json(post("/api/v1/admin/payments/11/resolve")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUserTokens.STUDENT, RoleCode.ADMIN)), body)),
                403, "ACCESS_DENIED");
    }

    @Test
    void resolve_withoutToken_401() throws Exception {
        expectProblem(stub(json(post("/api/v1/admin/payments/11/resolve"), "{\"action\":\"activated\",\"note\":\"x\"}")),
                401, "UNAUTHENTICATED");
    }
}
