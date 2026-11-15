package com.edumy.commerce.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.edumy.commerce.integration.FakeQrPaymentGateway;
import com.edumy.commerce.integration.PaymentLookup;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CommerceFixtures;
import com.edumy.support.CommerceFixtures.FixtureOrder;
import com.edumy.support.Concurrently;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.LockWaits;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.PaymentTestHooks;
import com.edumy.support.SqlStatementCounter;
import com.edumy.support.TestUsers;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * UC-S05 steps 5–7 through {@code POST /webhooks/payments/fake} and T2 ({@code PaymentResultApplier}) on MySQL
 * (P11-a): AC-01 paid + purchase enrollments + bought lines leave the cart, AC-02 failed, AC-11 / E3 idempotent by
 * reference (also under concurrency and for a result that waited for the order lock — ADR-011 R1), AC-19 amount / order
 * mismatch → needs_review, AC-23 late valid result, AC-24 late result with an ownership conflict, decision Q3, NFR-04
 * one transaction (a failure after the grant rolls everything back), and the signature on the raw body before anything
 * is read (401 without any SQL statement).
 */
@MySqlSpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class PaymentWebhookIT {

    static final String WEBHOOK = "/api/v1/webhooks/payments/fake";

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
    PaymentTestHooks hooks;
    @Autowired
    SqlStatementCounter sql;
    @Autowired
    PlatformTransactionManager txManager;
    @Value("${edumy.payment.webhook-secret}")
    String secret;

    CourseFixtures courses;
    CommerceFixtures commerce;
    Session student;
    long courseA;
    long courseB;
    long courseC;

    @BeforeEach
    void setUp() {
        courses = new CourseFixtures(jdbc);
        commerce = new CommerceFixtures(jdbc);
        Session instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        student = tokens.newUserSession(RoleCode.STUDENT);
        commerce.track(student.userId());
        courseA = publishedCourse(instructor.userId(), 300_000);
        courseB = publishedCourse(instructor.userId(), 200_000);
        courseC = publishedCourse(instructor.userId(), 150_000);
    }

    @AfterEach
    void cleanUp() {
        commerce.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    long publishedCourse(long mainInstructorId, long price) {
        long id = courses.draftCourse(mainInstructorId);
        courses.setStatus(id, "published", false);
        jdbc.sql("UPDATE courses SET price = ? WHERE id = ?").params(price, id).update();
        return id;
    }

    /** Pending order {A 300 000, B 200 000} with a pending attempt {@code ref}; the cart holds A, B and C. */
    FixtureOrder pendingOrderAB(String ref) {
        commerce.cart(student.userId(), courseA, courseB, courseC);
        FixtureOrder order = commerce.order(student.userId()).line(courseA, 300_000).line(courseB, 200_000).insert();
        commerce.pendingPayment(order, ref);
        return order;
    }

    static String body(String ref, String orderCode, long amount, String status) {
        return "{\"transactionRef\":\"" + ref + "\",\"orderCode\":\"" + orderCode + "\",\"amount\":" + amount
                + ",\"status\":\"" + status + "\"}";
    }

    String sign(String key, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    ResultActions deliver(String body, String signature) throws Exception {
        var request = post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(signature == null ? request : request.header("X-Signature", signature));
    }

    ResultActions deliver(String body) throws Exception {
        return deliver(body, sign(secret, body));
    }

    String paymentStatus(String ref) {
        return commerce.paymentColumn(ref, "status", String.class);
    }

    String orderStatus(FixtureOrder order) {
        return commerce.orderColumn(order.code(), "status", String.class);
    }

    // ---- AC-01 / AC-11 / E3

    @Test
    void success_onTime_paysTheOrder_grantsPurchaseEnrollments_boughtLinesLeaveTheCart_AC01() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = pendingOrderAB(ref);

        deliver(body(ref, order.code(), 500_000, "success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.transactionRef").value(ref));

        assertThat(paymentStatus(ref)).isEqualTo("success");
        assertThat(commerce.paymentColumn(ref, "confirmed_at", Timestamp.class)).isNotNull();
        assertThat(orderStatus(order)).isEqualTo("paid");
        assertThat(commerce.orderColumn(order.code(), "paid_at", Timestamp.class)).isNotNull();
        assertThat(commerce.enrollments(student.userId()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(courseA, "purchase", courseB, "purchase"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM enrollments WHERE order_id = ?").param(order.id())
                .query(Long.class).single()).isEqualTo(2);
        assertThat(commerce.cartCourseIds(student.userId())).containsExactly(courseC); // the other line is kept
        assertThat(hooks.published()).singleElement()
                .satisfies(event -> {
                    assertThat(event.orderCode()).isEqualTo(order.code());
                    assertThat(event.studentId()).isEqualTo(student.userId());
                    assertThat(event.totalAmount()).isEqualTo(500_000);
                    assertThat(event.courseIds()).containsExactly(courseA, courseB);
                });
        // the student sees it (UC-S07)
        mvc.perform(get("/api/v1/orders/" + order.code()).header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paid"))
                .andExpect(jsonPath("$.payments[0].status").value("success"));
        // the fake provider now answers a lookup with the same result
        assertThat(gateway.query(ref)).isInstanceOf(PaymentLookup.Paid.class);
    }

    @Test
    void sameResultTenTimes_oneEffect_AC11_E3() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = pendingOrderAB(ref);
        String body = body(ref, order.code(), 500_000, "success");
        deliver(body).andExpect(status().isOk());
        Timestamp confirmedAt = commerce.paymentColumn(ref, "confirmed_at", Timestamp.class);
        Timestamp paidAt = commerce.orderColumn(order.code(), "paid_at", Timestamp.class);

        for (int i = 0; i < 9; i++) {
            deliver(body).andExpect(status().isOk()).andExpect(jsonPath("$.received").value(true));
        }

        assertThat(commerce.enrollments(student.userId())).hasSize(2);
        assertThat(commerce.paymentColumn(ref, "confirmed_at", Timestamp.class)).isEqualTo(confirmedAt);
        assertThat(commerce.orderColumn(order.code(), "paid_at", Timestamp.class)).isEqualTo(paidAt);
        assertThat(paymentStatus(ref)).isEqualTo("success");
        assertThat(hooks.published()).hasSize(1);
    }

    @Test
    void twoConcurrentDeliveries_oneEffect_AC11() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = pendingOrderAB(ref);
        String body = body(ref, order.code(), 500_000, "success");
        String signature = sign(secret, body);

        List<Integer> statuses = Concurrently.times(4,
                () -> deliver(body, signature).andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(200);
        assertThat(commerce.enrollments(student.userId())).hasSize(2);
        assertThat(orderStatus(order)).isEqualTo("paid");
        assertThat(hooks.published()).hasSize(1);
    }

    /**
     * ADR-011 R1: the delivery reads the reference (scalar) while another transaction holds the order lock and pays the
     * order; once it gets the lock it loads the attempt for the first time and sees {@code success} — no second
     * effect, the holder's confirmation time is kept.
     */
    @Test
    void deliveryWaitingForTheOrderLock_seesTheCommittedSuccess_noSecondEffect_R1() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = pendingOrderAB(ref);
        Instant holderTime = Instant.parse("2026-11-14T10:00:00Z");
        String body = body(ref, order.code(), 500_000, "success");
        String signature = sign(secret, body);

        int status = LockWaits.whileLockedThenCommit(txManager, jdbc, j -> {
            j.sql("SELECT id FROM orders WHERE id = ? FOR UPDATE").param(order.id()).query(Long.class).single();
            j.sql("UPDATE payment_transactions SET status = 'success', confirmed_at = ? WHERE transaction_ref = ?")
                    .params(Timestamp.from(holderTime), ref).update();
            j.sql("UPDATE orders SET status = 'paid', paid_at = ? WHERE id = ?")
                    .params(Timestamp.from(holderTime), order.id()).update();
            j.sql("""
                    INSERT INTO enrollments (student_id, course_id, order_id, enroll_source) VALUES (?, ?, ?, 'purchase')""")
                    .params(student.userId(), courseA, order.id()).update();
        }, () -> deliver(body, signature).andReturn().getResponse().getStatus());

        assertThat(status).isEqualTo(200);
        assertThat(commerce.paymentColumn(ref, "confirmed_at", Timestamp.class).toInstant()).isEqualTo(holderTime);
        assertThat(commerce.orderColumn(order.code(), "paid_at", Timestamp.class).toInstant()).isEqualTo(holderTime);
        assertThat(commerce.enrollments(student.userId())).containsOnlyKeys(courseA); // nothing granted by the delivery
        assertThat(hooks.published()).isEmpty();
    }

    // ---- AC-19 / E2

    @Test
    void wrongAmount_needsReview_noEnrollment_orderStaysPending_AC19() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = pendingOrderAB(ref);

        deliver(body(ref, order.code(), 50_000, "success")).andExpect(status().isOk());

        assertThat(paymentStatus(ref)).isEqualTo("needs_review");
        assertThat(commerce.paymentColumn(ref, "confirmed_at", Timestamp.class)).isNull();
        assertThat(orderStatus(order)).isEqualTo("pending");
        assertThat(commerce.enrollments(student.userId())).isEmpty();
        assertThat(commerce.cartCourseIds(student.userId())).containsExactly(courseA, courseB, courseC);
        assertThat(hooks.published()).isEmpty();
    }

    @Test
    void wrongOrderCode_needsReview_noEnrollment_AC19() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = pendingOrderAB(ref);

        deliver(body(ref, "ORD-20260101-OTHER", 500_000, "success")).andExpect(status().isOk());

        assertThat(paymentStatus(ref)).isEqualTo("needs_review");
        assertThat(orderStatus(order)).isEqualTo("pending");
        assertThat(commerce.enrollments(student.userId())).isEmpty();
    }

    // ---- AC-02 / A2

    @Test
    void failedResult_attemptFailed_orderPending_noEnrollment_cartKept_AC02() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = pendingOrderAB(ref);

        deliver(body(ref, order.code(), 500_000, "failed")).andExpect(status().isOk());

        assertThat(paymentStatus(ref)).isEqualTo("failed");
        assertThat(orderStatus(order)).isEqualTo("pending");
        assertThat(commerce.enrollments(student.userId())).isEmpty();
        assertThat(commerce.cartCourseIds(student.userId())).containsExactly(courseA, courseB, courseC);
    }

    @Test
    void failedResultAfterSuccess_ignored() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = pendingOrderAB(ref);
        deliver(body(ref, order.code(), 500_000, "success")).andExpect(status().isOk());

        deliver(body(ref, order.code(), 500_000, "failed")).andExpect(status().isOk());

        assertThat(paymentStatus(ref)).isEqualTo("success");
        assertThat(orderStatus(order)).isEqualTo("paid");
        assertThat(commerce.enrollments(student.userId())).hasSize(2);
    }

    // ---- AC-23 / AC-24 / A3 (late results, BR-30)

    @Test
    void lateValidResult_orderCancelledAsExpired_nothingOwned_orderPaid_enrollmentsCreated_AC23() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = commerce.order(student.userId()).line(courseA, 300_000).cancelled("expired").insert();
        Instant created = Instant.now().minus(2, ChronoUnit.DAYS);
        commerce.payment(order, ref, "expired", created, created.plus(15, ChronoUnit.MINUTES));

        deliver(body(ref, order.code(), 300_000, "success")).andExpect(status().isOk());

        assertThat(paymentStatus(ref)).isEqualTo("success");
        assertThat(orderStatus(order)).isEqualTo("paid");
        assertThat(commerce.orderColumn(order.code(), "cancel_reason", String.class)).isEqualTo("expired"); // history
        assertThat(commerce.orderColumn(order.code(), "cancelled_at", Timestamp.class)).isNotNull();
        assertThat(commerce.enrollments(student.userId())).containsExactlyEntriesOf(Map.of(courseA, "purchase"));
        assertThat(hooks.published()).hasSize(1);
    }

    @Test
    void lateResult_courseAlreadyOwnedThroughAnotherOrder_needsReview_noAccessChange_AC24() throws Exception {
        courses.purchaseEnrollment(student.userId(), courseA); // A owned through another (paid) order
        String ref = CommerceFixtures.newReference();
        FixtureOrder old = commerce.order(student.userId()).line(courseA, 300_000).line(courseB, 200_000)
                .cancelled("replaced").insert();
        Instant created = Instant.now().minus(1, ChronoUnit.HOURS);
        commerce.payment(old, ref, "expired", created, created.plus(15, ChronoUnit.MINUTES));

        deliver(body(ref, old.code(), 500_000, "success")).andExpect(status().isOk());

        assertThat(paymentStatus(ref)).isEqualTo("needs_review");
        assertThat(orderStatus(old)).isEqualTo("cancelled");
        assertThat(commerce.enrollments(student.userId())).containsOnlyKeys(courseA); // B not granted
        assertThat(hooks.published()).isEmpty();
    }

    @Test
    void lateResultForAnOrderAlreadyPaidByAnotherAttempt_needsReview() throws Exception {
        String first = CommerceFixtures.newReference();
        String second = CommerceFixtures.newReference();
        FixtureOrder order = commerce.order(student.userId()).line(courseA, 300_000).insert();
        Instant created = Instant.now().minus(1, ChronoUnit.HOURS);
        commerce.payment(order, first, "expired", created, created.plus(15, ChronoUnit.MINUTES));
        commerce.pendingPayment(order, second);
        deliver(body(second, order.code(), 300_000, "success")).andExpect(status().isOk());

        deliver(body(first, order.code(), 300_000, "success")).andExpect(status().isOk()); // paid twice

        assertThat(paymentStatus(first)).isEqualTo("needs_review");
        assertThat(paymentStatus(second)).isEqualTo("success");
        assertThat(commerce.enrollments(student.userId())).hasSize(1);
    }

    // ---- decision Q3 (on-time result, courses already owned)

    @Test
    void onTime_everyCourseAlreadyOwned_needsReview_Q3() throws Exception {
        courses.purchaseEnrollment(student.userId(), courseA);
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = commerce.order(student.userId()).line(courseA, 300_000).insert();
        commerce.pendingPayment(order, ref);

        deliver(body(ref, order.code(), 300_000, "success")).andExpect(status().isOk());

        assertThat(paymentStatus(ref)).isEqualTo("needs_review");
        assertThat(orderStatus(order)).isEqualTo("pending");
        assertThat(hooks.published()).isEmpty();
    }

    @Test
    void onTime_someCoursesOwned_paid_grantsTheOthers_loggedAsWarning_Q3(CapturedOutput output) throws Exception {
        courses.purchaseEnrollment(student.userId(), courseA);
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = pendingOrderAB(ref);

        deliver(body(ref, order.code(), 500_000, "success")).andExpect(status().isOk());

        assertThat(orderStatus(order)).isEqualTo("paid");
        assertThat(commerce.enrollments(student.userId())).containsOnlyKeys(courseA, courseB);
        assertThat(jdbc.sql("SELECT order_id FROM enrollments WHERE student_id = ? AND course_id = ?")
                .params(student.userId(), courseB).query(Long.class).single()).isEqualTo(order.id());
        assertThat(output.getOut()).contains("already owned courses").contains(order.code());
    }

    // ---- unknown reference, NFR-04

    @Test
    void unknownReference_acknowledged_nothingWritten(CapturedOutput output) throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = pendingOrderAB(CommerceFixtures.newReference());

        deliver(body(ref, order.code(), 500_000, "success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        assertThat(orderStatus(order)).isEqualTo("pending");
        assertThat(commerce.enrollments(student.userId())).isEmpty();
        assertThat(output.getOut()).contains("unknown reference");
    }

    @Test
    void failureAfterTheGrant_rollsBackPaymentOrderEnrollmentsAndCart_NFR04() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = pendingOrderAB(ref);
        hooks.failNextOrderPaid();

        expectProblem(deliver(body(ref, order.code(), 500_000, "success")), 500, "INTERNAL_ERROR");

        assertThat(paymentStatus(ref)).isEqualTo("pending");
        assertThat(orderStatus(order)).isEqualTo("pending");
        assertThat(commerce.enrollments(student.userId())).isEmpty();
        assertThat(commerce.cartCourseIds(student.userId())).containsExactly(courseA, courseB, courseC);

        deliver(body(ref, order.code(), 500_000, "success")).andExpect(status().isOk()); // the provider retries
        assertThat(orderStatus(order)).isEqualTo("paid");
        assertThat(commerce.enrollments(student.userId())).hasSize(2);
    }

    // ---- signature on the raw body, before anything is read

    @Test
    void wrongMissingMalformedSignatureOrTamperedBody_401_withoutAnySqlStatement() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = pendingOrderAB(ref);
        String body = body(ref, order.code(), 500_000, "success");
        String tampered = body.replace("500000", "1");
        List<ResultActionsSupplier> calls = List.of(
                () -> deliver(body, sign("another-secret", body)),
                () -> deliver(body, null),
                () -> deliver(body, ""),
                () -> deliver(body, "not-hex"),
                () -> deliver(body, "sha256=" + sign(secret, body)),
                () -> deliver(tampered, sign(secret, body)));
        for (ResultActionsSupplier call : calls) {
            sql.reset();
            expectProblem(call.get(), 401, "UNAUTHENTICATED");
            assertThat(sql.count()).as(sql.statements().toString()).isZero();
        }
        assertThat(paymentStatus(ref)).isEqualTo("pending");
    }

    @Test
    void uppercaseHexSignature_accepted() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = pendingOrderAB(ref);
        String body = body(ref, order.code(), 500_000, "failed");

        deliver(body, sign(secret, body).toUpperCase()).andExpect(status().isOk());

        assertThat(paymentStatus(ref)).isEqualTo("failed");
    }

    @Test
    void signedButInvalidOrUnreadableBody_400() throws Exception {
        String invalid = "{\"transactionRef\":\"\",\"orderCode\":\"ORD-1\",\"amount\":-5,\"status\":\"maybe\"}";
        MvcResult result = expectProblem(deliver(invalid), 400, "VALIDATION_FAILED");
        List<String> fields = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.errors[*].field");
        assertThat(fields).containsExactlyInAnyOrder("transactionRef", "amount", "status");

        expectProblem(deliver("this is not json"), 400, "MALFORMED_REQUEST");
    }

    @FunctionalInterface
    interface ResultActionsSupplier {
        ResultActions get() throws Exception;
    }
}
