package com.edumy.commerce.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static com.edumy.support.ProblemAssertions.withoutTraceAndInstance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.edumy.commerce.integration.FakeQrPaymentGateway;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CommerceFixtures;
import com.edumy.support.CommerceFixtures.FixtureOrder;
import com.edumy.support.Concurrently;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MutableClock;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * UC-S05 steps 1–2 create QR through HTTP on MySQL (P11-c, ADR-011): a new attempt (reference = 15-digit transaction
 * reference, amount = total, 15 min — BR-13 / BR-14 / D-27), the still-valid QR shown again (200), AC-10 a new QR after
 * the expired one was looked up (two attempts, one order), the expired attempt found paid, AC-02 a new QR after a
 * failed result, E1 provider down (503, nothing changed), P11 R3 (order older than 24 h; order holding an owned
 * course), one pending attempt under concurrency (BR-16), NFR-01 identical 404.
 */
@MySqlSpringBootTest
class PaymentQrIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    FakeQrPaymentGateway gateway;
    @Autowired
    MutableClock clock;

    CourseFixtures courses;
    CommerceFixtures commerce;
    Session student;
    long courseA;
    FixtureOrder order;

    @BeforeEach
    void setUp() {
        courses = new CourseFixtures(jdbc);
        commerce = new CommerceFixtures(jdbc);
        Session instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        student = tokens.newUserSession(RoleCode.STUDENT);
        courseA = courses.draftCourse(instructor.userId());
        courses.setStatus(courseA, "published", false);
        jdbc.sql("UPDATE courses SET price = 450000 WHERE id = ?").param(courseA).update();
        commerce.cart(student.userId(), courseA);
        order = commerce.order(student.userId()).line(courseA, 450_000).insert();
    }

    @AfterEach
    void cleanUp() {
        commerce.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    ResultActions qr(Session who, String code) throws Exception {
        return mvc.perform(post("/api/v1/orders/" + code + "/payments").header(HttpHeaders.AUTHORIZATION,
                who.bearer()));
    }

    static String ref(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.transactionRef");
    }

    long attempts() {
        return jdbc.sql("SELECT COUNT(*) FROM payment_transactions WHERE order_id = ?").param(order.id())
                .query(Long.class).single();
    }

    @Test
    void firstQr_201_pendingAttempt_15DigitReference_amountIsTheTotal_valid15Minutes_BR13_BR14() throws Exception {
        Instant before = clock.instant();

        MvcResult result = qr(student, order.code())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderCode").value(order.code()))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.amount").value(450_000))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String reference = JsonPath.read(body, "$.transactionRef");
        assertThat(reference).matches("[1-9]\\d{14}");
        assertThat((String) JsonPath.read(body, "$.qrData")).isEqualTo("FAKEQR|EDUMY|" + reference + "|450000");
        Instant created = Instant.parse(JsonPath.read(body, "$.createdAt"));
        Instant expires = Instant.parse(JsonPath.read(body, "$.qrExpiresAt"));
        assertThat(created).isAfterOrEqualTo(before.truncatedTo(ChronoUnit.MILLIS));
        assertThat(Duration.between(created, expires)).isEqualTo(Duration.ofMinutes(15));
        assertThat(commerce.paymentColumn(reference, "status", String.class)).isEqualTo("pending");
        assertThat(attempts()).isEqualTo(1);
        // the polling endpoint shows it with its QR
        mvc.perform(get("/api/v1/orders/" + order.code() + "/payments/current")
                        .header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionRef").value(reference))
                .andExpect(jsonPath("$.qrData").value("FAKEQR|EDUMY|" + reference + "|450000"));
    }

    @Test
    void stillValidQr_shownAgain_200_noNewAttempt_noLookup() throws Exception {
        String first = ref(qr(student, order.code()).andExpect(status().isCreated()));
        clock.advance(Duration.ofMinutes(14));

        String again = ref(qr(student, order.code()).andExpect(status().isOk()));

        assertThat(again).isEqualTo(first);
        assertThat(attempts()).isEqualTo(1);
        assertThat(gateway.totalQueries()).isZero();
    }

    @Test
    void expiredQr_lookedUpNotPaid_closed_newQrForTheSameOrder_twoAttemptsOneOrder_AC10() throws Exception {
        String first = ref(qr(student, order.code()).andExpect(status().isCreated()));
        expireQr(first);

        String second = ref(qr(student, order.code()).andExpect(status().isCreated()));

        assertThat(second).isNotEqualTo(first);
        assertThat(gateway.queryCount(first)).isEqualTo(1);
        assertThat(commerce.paymentColumn(first, "status", String.class)).isEqualTo("expired");
        assertThat(commerce.paymentColumn(second, "status", String.class)).isEqualTo("pending");
        assertThat(attempts()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM orders WHERE student_id = ?").param(student.userId())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void expiredQr_lookupFindsItPaid_recorded_409_noNewAttempt() throws Exception {
        String first = ref(qr(student, order.code()).andExpect(status().isCreated()));
        expireQr(first);
        gateway.simulatePaid(first, 450_000);

        expectProblem(qr(student, order.code()), 409, "ORDER_ALREADY_PAID");

        assertThat(commerce.orderColumn(order.code(), "status", String.class)).isEqualTo("paid");
        assertThat(commerce.enrollments(student.userId())).containsOnlyKeys(courseA);
        assertThat(commerce.cartCourseIds(student.userId())).isEmpty();
        assertThat(attempts()).isEqualTo(1);
    }

    @Test
    void afterAFailedResult_aNewQr_201_failedAttemptKept_AC02() throws Exception {
        String reference = CommerceFixtures.newReference();
        Instant created = Instant.now().minusSeconds(120);
        commerce.payment(order, reference, "failed", created, created.plus(15, ChronoUnit.MINUTES));

        String second = ref(qr(student, order.code()).andExpect(status().isCreated()));

        assertThat(second).isNotEqualTo(reference);
        assertThat(commerce.paymentColumn(reference, "status", String.class)).isEqualTo("failed");
        assertThat(attempts()).isEqualTo(2);
        assertThat(commerce.cartCourseIds(student.userId())).containsExactly(courseA); // BR-15
    }

    @Test
    void providerDown_503_noAttempt_orderAndCartKept_E1() throws Exception {
        gateway.setUnavailable(true);

        expectProblem(qr(student, order.code()), 503, "PAYMENT_PROVIDER_UNAVAILABLE");

        assertThat(attempts()).isZero();
        assertThat(commerce.orderColumn(order.code(), "status", String.class)).isEqualTo("pending");
        assertThat(commerce.cartCourseIds(student.userId())).containsExactly(courseA);
    }

    @Test
    void providerDownWhileTheExpiredQrIsLookedUp_503_theOldAttemptStaysPending() throws Exception {
        String first = ref(qr(student, order.code()).andExpect(status().isCreated()));
        expireQr(first);
        gateway.setUnavailable(true);

        expectProblem(qr(student, order.code()), 503, "PAYMENT_PROVIDER_UNAVAILABLE");

        assertThat(commerce.paymentColumn(first, "status", String.class)).isEqualTo("pending");
        assertThat(attempts()).isEqualTo(1);
    }

    @Test
    void orderOlderThan24Hours_lookedUp_cancelledAsExpired_409_R3a() throws Exception {
        FixtureOrder stale = staleOrder();

        expectProblem(qr(student, stale.code()), 409, "DATA_CONFLICT");

        assertThat(commerce.orderColumn(stale.code(), "status", String.class)).isEqualTo("cancelled"); // committed
        assertThat(commerce.orderColumn(stale.code(), "cancel_reason", String.class)).isEqualTo("expired");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payment_transactions WHERE order_id = ?").param(stale.id())
                .query(Long.class).single()).isZero();
    }

    @Test
    void orderOlderThan24Hours_pendingAttemptFoundPaid_409AlreadyPaid_R3a() throws Exception {
        FixtureOrder stale = staleOrder();
        String reference = CommerceFixtures.newReference();
        Instant created = Instant.now().minus(25, ChronoUnit.HOURS);
        commerce.payment(stale, reference, "pending", created, created.plus(15, ChronoUnit.MINUTES));
        gateway.simulatePaid(reference, 450_000);

        expectProblem(qr(student, stale.code()), 409, "ORDER_ALREADY_PAID");

        assertThat(commerce.orderColumn(stale.code(), "status", String.class)).isEqualTo("paid");
    }

    @Test
    void orderHoldingACourseOwnedNow_cancelledAsReplaced_409OrderCartChanged_noQr_R3b() throws Exception {
        courses.purchaseEnrollment(student.userId(), courseA); // owned through another order meanwhile

        expectProblem(qr(student, order.code()), 409, "ORDER_CART_CHANGED");

        assertThat(commerce.orderColumn(order.code(), "status", String.class)).isEqualTo("cancelled");
        assertThat(commerce.orderColumn(order.code(), "cancel_reason", String.class)).isEqualTo("replaced");
        assertThat(attempts()).isZero();
    }

    @Test
    void twoConcurrentRequests_onePendingAttempt_201And200_sameReference_BR16() throws Exception {
        List<MvcResult> results = Concurrently.times(2, () -> qr(student, order.code()).andReturn());

        assertThat(results).extracting(r -> r.getResponse().getStatus()).containsExactlyInAnyOrder(201, 200);
        List<String> references = results.stream().map(r -> {
            try {
                return (String) JsonPath.read(r.getResponse().getContentAsString(StandardCharsets.UTF_8),
                        "$.transactionRef");
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
        assertThat(references.get(0)).isEqualTo(references.get(1));
        assertThat(attempts()).isEqualTo(1);
    }

    @Test
    void notPending_409_foreign404IdenticalToUnknown_noToken401() throws Exception {
        FixtureOrder paid = commerce.order(student.userId()).line(courseA, 450_000).paid().insert();
        expectProblem(qr(student, paid.code()), 409, "DATA_CONFLICT");

        Session other = tokens.newUserSession(RoleCode.STUDENT);
        MvcResult foreign = expectProblem(qr(other, order.code()), 404, "RESOURCE_NOT_FOUND");
        MvcResult unknown = expectProblem(qr(other, "ORD-20260101-UNKNOWN0"), 404, "RESOURCE_NOT_FOUND");
        assertThat(withoutTraceAndInstance(foreign)).isEqualTo(withoutTraceAndInstance(unknown));
        assertThat(attempts()).isZero();

        expectProblem(mvc.perform(post("/api/v1/orders/" + order.code() + "/payments")), 401, "UNAUTHENTICATED");
    }

    /** The attempt's QR expired 5 minutes ago (the clock is not moved: the access token would expire with it). */
    private void expireQr(String reference) {
        jdbc.sql("""
                UPDATE payment_transactions SET created_at = CURRENT_TIMESTAMP(6) - INTERVAL 20 MINUTE,
                       qr_expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 5 MINUTE
                WHERE transaction_ref = ?""").param(reference).update();
    }

    /** A second, 25-hour-old pending order needs the first one gone (BR-29): the setUp order is cancelled. */
    private FixtureOrder staleOrder() {
        jdbc.sql("UPDATE orders SET status = 'cancelled', cancel_reason = 'user', cancelled_at = CURRENT_TIMESTAMP(6) "
                + "WHERE id = ?").param(order.id()).update();
        return commerce.order(student.userId()).line(courseA, 450_000)
                .createdAt(Instant.now().minus(25, ChronoUnit.HOURS)).insert();
    }
}
