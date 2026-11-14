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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.edumy.support.MutableClock;
import com.edumy.support.MySqlSeedSpringBootTest;
import com.edumy.support.SeedUserTokens;
import com.edumy.support.SqlStatementCounter;
import com.edumy.shared.security.RoleCode;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The five phase-1 commerce stubs (ADR-005, P07-commerce) on the dev seed, read-only: POST /orders (UC-S04), POST
 * /orders/{code}/cancel (UC-S16), POST /orders/{code}/payments (UC-S05), POST /webhooks/payments/fake (UC-S05) and POST
 * /admin/payments/{id}/resolve (UC-A18). Every call that reaches a stub goes through {@link #stub}: it proves that the
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
    @Value("${edumy.payment.webhook-secret}")
    String webhookSecret;

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

    // ---- POST /orders (UC-S04)

    @Test
    void createOrder_previewFromTheCart_201_UCS04() throws Exception {
        stubbedOrderPost(16, "/api/v1/orders")
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Edumy-Stub", "true"))
                .andExpect(jsonPath("$.orderCode", matchesPattern("ORD-\\d{8}-STUB")))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.totalAmount").value(799000))
                .andExpect(jsonPath("$.cancelReason").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").value(notNullValue()))
                .andExpect(jsonPath("$.paidAt").value(nullValue()))
                .andExpect(jsonPath("$.cancelledAt").value(nullValue()))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].courseId").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Lập trình Java Spring Boot từ cơ bản đến nâng cao"))
                .andExpect(jsonPath("$.items[0].slug").isNotEmpty())
                .andExpect(jsonPath("$.items[0].thumbnailUrl").value("https://cdn.edumy.vn/thumbnails/course-1.jpg"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(799000)) // current price (BR-12 snapshot-to-be)
                .andExpect(jsonPath("$.payments", empty()));
    }

    @Test
    void createOrder_leavesOutUnavailableLines_andTotalsTheRest_UCS04() throws Exception {
        // cart 2: hidden course 10 (left out, D-26) + course 3 at 599 000
        stubbedOrderPost(11, "/api/v1/orders")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].courseId").value(3))
                .andExpect(jsonPath("$.totalAmount").value(599000));
    }

    @Test
    void createOrder_total_isTheSumOfTheLineCurrentPrices_UCS04() throws Exception {
        long cartTotal = ((Number) JsonPath.read(mvc.perform(asUser(17, get("/api/v1/cart")))
                .andReturn().getResponse().getContentAsString(), "$.totalAmount")).longValue();
        stubbedOrderPost(17, "/api/v1/orders")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalAmount").value(cartTotal))
                .andExpect(jsonPath("$.items[*].courseId", contains(4, 8)));
    }

    @Test
    void createOrder_acceptsTheQrMethodBody_andRejectsAnotherOne_UCS04() throws Exception {
        stub(json(asUser(16, post("/api/v1/orders")), "{\"paymentMethod\":\"qr\"}"))
                .andExpect(status().isCreated());
        expectProblem(stub(json(asUser(16, post("/api/v1/orders")), "{\"paymentMethod\":\"card\"}")),
                400, "VALIDATION_FAILED");
    }

    @Test
    void createOrder_emptyCart_422_UCS04_E1() throws Exception {
        expectProblem(stubbedOrderPost(6, "/api/v1/orders"), 422, "CART_EMPTY"); // student 6 has no cart
    }

    @Test
    void createOrder_unverifiedEmail_403_D02() throws Exception {
        expectProblem(stubbedOrderPost(SeedUserTokens.UNVERIFIED, "/api/v1/orders"), 403, "EMAIL_NOT_VERIFIED");
    }

    @Test
    void createOrder_withoutToken_401() throws Exception {
        expectProblem(stub(post("/api/v1/orders")), 401, "UNAUTHENTICATED");
    }

    // ---- POST /orders/{code}/cancel (UC-S16)

    @Test
    void cancelOrder_returnsTheOrderAsCancelled_200_UCS16() throws Exception {
        stubbedOrderPost(8, "/api/v1/orders/" + ORDER_4 + "/cancel")
                .andExpect(status().isOk())
                .andExpect(header().string("X-Edumy-Stub", "true"))
                .andExpect(jsonPath("$.orderCode").value(ORDER_4))
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.cancelReason").value("user"))
                .andExpect(jsonPath("$.cancelledAt").value(notNullValue()))
                .andExpect(jsonPath("$.paidAt").value(nullValue()))
                .andExpect(jsonPath("$.totalAmount").value(849000))
                .andExpect(jsonPath("$.items", hasSize(2)))
                // UC-S16 step 2: the pending QR attempt (6) is closed too
                .andExpect(jsonPath("$.payments[*].transactionRef",
                        contains("FAKEPAY-20261101-0006", "FAKEPAY-20261101-0005")))
                .andExpect(jsonPath("$.payments[*].status", contains("expired", "expired")));
        // nothing was saved: the real order is still pending
        mvc.perform(asUser(8, get("/api/v1/orders/" + ORDER_4)))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.cancelReason").value(nullValue()));
    }

    @Test
    void cancelOrder_acceptsAnEmptyJsonBody_UCS16() throws Exception {
        stub(json(asUser(8, post("/api/v1/orders/" + ORDER_4 + "/cancel")), "{}")).andExpect(status().isOk());
    }

    @Test
    void cancelOrder_notPending_409_UCS16() throws Exception {
        expectProblem(stubbedOrderPost(8, "/api/v1/orders/" + ORDER_3 + "/cancel"), 409, "DATA_CONFLICT");
        expectProblem(stubbedOrderPost(6, "/api/v1/orders/" + ORDER_1 + "/cancel"), 409, "DATA_CONFLICT");
    }

    @Test
    void cancelOrder_otherStudentsOrder_isTheSame404AsAnUnknownOne_UCS16() throws Exception {
        Map<String, Long> before = counts();
        assertSameNotFound(mvc, asUser(7, post("/api/v1/orders/" + ORDER_4 + "/cancel")),
                asUser(7, post("/api/v1/orders/" + UNKNOWN_ORDER + "/cancel")));
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void cancelOrder_withoutToken_401() throws Exception {
        expectProblem(stub(post("/api/v1/orders/" + ORDER_4 + "/cancel")), 401, "UNAUTHENTICATED");
    }

    // ---- POST /orders/{code}/payments (UC-S05 create QR)

    @Test
    void createQr_sample_201_UCS05() throws Exception {
        clock.setInstant(Instant.parse("2026-11-20T10:00:00Z")); // the seed attempt 6 expired on 2026-11-01
        MvcResult result = stubbedOrderPost(8, "/api/v1/orders/" + ORDER_4 + "/payments")
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Edumy-Stub", "true"))
                .andExpect(jsonPath("$.orderCode").value(ORDER_4))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.amount").value(849000)) // = order total (BR-14)
                .andExpect(jsonPath("$.transactionRef", matchesPattern("FAKEPAY-20261120-STUB")))
                .andExpect(jsonPath("$.qrData").value("FAKEQR|EDUMY|" + ORDER_4 + "|849000"))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Instant created = Instant.parse(JsonPath.read(body, "$.createdAt"));
        Instant expires = Instant.parse(JsonPath.read(body, "$.qrExpiresAt"));
        assertThat(Duration.between(created, expires)).isEqualTo(Duration.ofMinutes(15)); // BR-13
        assertThat(created).isBetween(Instant.parse("2026-11-20T10:00:00Z"), Instant.parse("2026-11-20T10:05:00Z"));
    }

    @Test
    void createQr_stillValidPendingQr_isShownAgain_200_UCS05() throws Exception {
        clock.setInstant(Instant.parse("2026-11-01T08:30:00Z")); // attempt 6 is valid until 08:35
        stubbedOrderPost(8, "/api/v1/orders/" + ORDER_4 + "/payments")
                .andExpect(status().isOk())
                .andExpect(header().string("X-Edumy-Stub", "true"))
                .andExpect(jsonPath("$.transactionRef").value("FAKEPAY-20261101-0006"))
                .andExpect(jsonPath("$.qrExpiresAt").value("2026-11-01T08:35:00Z"))
                .andExpect(jsonPath("$.qrData").value("FAKEQR|EDUMY|ORD-20261101-0004|849000"))
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void createQr_orderWithoutPendingAttempt_201_UCS05() throws Exception {
        stubbedOrderPost(12, "/api/v1/orders/" + ORDER_10 + "/payments")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(450000));
    }

    @Test
    void createQr_orderNotPending_409_UCS05() throws Exception {
        expectProblem(stubbedOrderPost(8, "/api/v1/orders/" + ORDER_3 + "/payments"), 409, "DATA_CONFLICT");
        expectProblem(stubbedOrderPost(6, "/api/v1/orders/" + ORDER_1 + "/payments"), 409, "DATA_CONFLICT");
    }

    @Test
    void createQr_otherStudentsOrder_isTheSame404AsAnUnknownOne_UCS05() throws Exception {
        assertSameNotFound(mvc, asUser(7, post("/api/v1/orders/" + ORDER_4 + "/payments")),
                asUser(7, post("/api/v1/orders/" + UNKNOWN_ORDER + "/payments")));
    }

    @Test
    void createQr_withoutToken_401() throws Exception {
        expectProblem(stub(post("/api/v1/orders/" + ORDER_4 + "/payments")), 401, "UNAUTHENTICATED");
    }

    // ---- POST /webhooks/payments/fake (UC-S05, dev/test only, HMAC)

    static final String WEBHOOK = "/api/v1/webhooks/payments/fake";
    static final String WEBHOOK_BODY =
            "{\"transactionRef\":\"FAKEPAY-20261101-0006\",\"orderCode\":\"ORD-20261101-0004\","
                    + "\"amount\":849000,\"status\":\"success\",\"paidAt\":\"2026-11-01T08:10:00Z\"}";

    private String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private MockHttpServletRequestBuilder webhook(String body, String signature) {
        MockHttpServletRequestBuilder request = json(post(WEBHOOK), body);
        return signature == null ? request : request.header("X-Signature", signature);
    }

    @Test
    void webhook_validSignature_acknowledges_200_noStateChange() throws Exception {
        stub(webhook(WEBHOOK_BODY, sign(webhookSecret, WEBHOOK_BODY)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Edumy-Stub", "true"))
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.transactionRef").value("FAKEPAY-20261101-0006"));
        // the real attempt is still pending: the stub applied nothing
        assertThat(jdbc.sql("select status from payment_transactions where id = 6").query(String.class).single())
                .isEqualTo("pending");
    }

    @Test
    void webhook_runsNoStatementAtAll() throws Exception {
        mvc.perform(webhook(WEBHOOK_BODY, sign(webhookSecret, WEBHOOK_BODY))).andExpect(status().isOk()); // warm-up
        sql.reset();
        mvc.perform(webhook(WEBHOOK_BODY, sign(webhookSecret, WEBHOOK_BODY))).andExpect(status().isOk());
        assertThat(sql.count()).as(sql.statements().toString()).isZero();
    }

    @Test
    void webhook_uppercaseHexSignature_isAccepted() throws Exception {
        stub(webhook(WEBHOOK_BODY, sign(webhookSecret, WEBHOOK_BODY).toUpperCase())).andExpect(status().isOk());
    }

    @Test
    void webhook_wrongSignature_401() throws Exception {
        expectProblem(stub(webhook(WEBHOOK_BODY, sign("another-secret", WEBHOOK_BODY))), 401, "UNAUTHENTICATED");
    }

    @Test
    void webhook_missingOrMalformedSignature_401() throws Exception {
        expectProblem(stub(webhook(WEBHOOK_BODY, null)), 401, "UNAUTHENTICATED");
        expectProblem(stub(webhook(WEBHOOK_BODY, "")), 401, "UNAUTHENTICATED");
        expectProblem(stub(webhook(WEBHOOK_BODY, "not-hex")), 401, "UNAUTHENTICATED");
        expectProblem(stub(webhook(WEBHOOK_BODY, "sha256=" + sign(webhookSecret, WEBHOOK_BODY))), 401,
                "UNAUTHENTICATED");
    }

    @Test
    void webhook_tamperedBody_401() throws Exception {
        String signature = sign(webhookSecret, WEBHOOK_BODY);
        String tampered = WEBHOOK_BODY.replace("849000", "1");
        expectProblem(stub(webhook(tampered, signature)), 401, "UNAUTHENTICATED");
    }

    @Test
    void webhook_signedButInvalidBody_400() throws Exception {
        String invalid = "{\"transactionRef\":\"\",\"orderCode\":\"ORD-1\",\"amount\":-5,\"status\":\"maybe\"}";
        MvcResult result = expectProblem(stub(webhook(invalid, sign(webhookSecret, invalid))), 400,
                "VALIDATION_FAILED");
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> fields = JsonPath.read(body, "$.errors[*].field");
        assertThat(fields).containsExactlyInAnyOrder("transactionRef", "amount", "status");
        String notJson = "this is not json";
        expectProblem(stub(webhook(notJson, sign(webhookSecret, notJson))), 400, "MALFORMED_REQUEST");
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
