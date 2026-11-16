package com.edumy.commerce.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.edumy.commerce.integration.FakeQrPaymentGateway;
import com.edumy.commerce.service.CommerceExpiryService;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CommerceFixtures;
import com.edumy.support.CommerceFixtures.FixtureOrder;
import com.edumy.support.CourseFixtures;
import com.edumy.support.Interleave;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.PaymentTestHooks;
import com.edumy.support.TestUsers;
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

/**
 * Forced interleavings of real flows of one student (P11-fix-a, review P11-R-02 / R-04; ADR-011 per-student lock):
 * the first flow stops inside its transaction ({@link Interleave}), the second must wait for the student's cart row,
 * and the outcome follows the T2 decision table as if the two had run one after the other.
 */
@MySqlSpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class PaymentRaceIT {

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
    CommerceExpiryService expiry;
    @Value("${edumy.payment.webhook-secret}")
    String secret;

    CourseFixtures courses;
    CommerceFixtures commerce;
    Session student;
    long courseA;
    long courseB;

    @BeforeEach
    void setUp() {
        courses = new CourseFixtures(jdbc);
        commerce = new CommerceFixtures(jdbc);
        Session instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        student = tokens.newUserSession(RoleCode.STUDENT);
        commerce.track(student.userId());
        courseA = published(instructor.userId(), 300_000);
        courseB = published(instructor.userId(), 200_000);
    }

    @AfterEach
    void cleanUp() {
        commerce.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    long published(long main, long price) {
        long id = courses.draftCourse(main);
        courses.setStatus(id, "published", false);
        jdbc.sql("UPDATE courses SET price = ? WHERE id = ?").params(price, id).update();
        return id;
    }

    /** Cancelled (replaced) order {A} whose attempt {@code ref} expired: its success result is late (A3). */
    FixtureOrder lateOrderA(String ref) {
        FixtureOrder order = commerce.order(student.userId()).line(courseA, 300_000).cancelled("replaced").insert();
        Instant created = Instant.now().minus(1, ChronoUnit.HOURS);
        commerce.payment(order, ref, "expired", created, created.plus(15, ChronoUnit.MINUTES));
        return order;
    }

    int deliver(String ref, String orderCode, long amount) throws Exception {
        String body = "{\"transactionRef\":\"" + ref + "\",\"orderCode\":\"" + orderCode + "\",\"amount\":" + amount
                + ",\"status\":\"success\"}";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mvc.perform(post("/api/v1/webhooks/payments/fake").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Signature", HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)))))
                .andReturn().getResponse().getStatus();
    }

    String paymentStatus(String ref) {
        return commerce.paymentColumn(ref, "status", String.class);
    }

    String orderStatus(FixtureOrder order) {
        return commerce.orderColumn(order.code(), "status", String.class);
    }

    @Test
    void lateResultOfTheOldOrderFirst_thenOnTimeResultOfTheNewOne_sameCourse_serialised_secondNeedsReview_Q3()
            throws Exception {
        String late = CommerceFixtures.newReference();
        FixtureOrder p1 = lateOrderA(late);
        String onTime = CommerceFixtures.newReference();
        FixtureOrder p2 = commerce.order(student.userId()).line(courseA, 300_000).insert();
        commerce.pendingPayment(p2, onTime);

        Interleave.Results<Integer, Integer> results = Interleave.secondWaitsForTheFirst(jdbc,
                hooks.holdNextOrderPaid(), () -> deliver(late, p1.code(), 300_000),
                () -> deliver(onTime, p2.code(), 300_000));

        assertThat(results.first()).isEqualTo(200);
        assertThat(results.second()).isEqualTo(200);
        assertThat(orderStatus(p1)).isEqualTo("paid"); // AC-23: late, nothing owned yet
        assertThat(paymentStatus(late)).isEqualTo("success");
        assertThat(paymentStatus(onTime)).isEqualTo("needs_review"); // Q3: every course owned meanwhile
        assertThat(orderStatus(p2)).isEqualTo("pending");
        assertThat(commerce.enrollments(student.userId())).containsExactlyEntriesOf(Map.of(courseA, "purchase"));
        assertThat(hooks.published()).hasSize(1);
    }

    @Test
    void onTimeResultFirst_thenLateResultOfTheOldOrder_sameCourse_serialised_lateNeedsReview_AC24() throws Exception {
        String late = CommerceFixtures.newReference();
        FixtureOrder p1 = lateOrderA(late);
        String onTime = CommerceFixtures.newReference();
        FixtureOrder p2 = commerce.order(student.userId()).line(courseA, 300_000).insert();
        commerce.pendingPayment(p2, onTime);

        Interleave.secondWaitsForTheFirst(jdbc, hooks.holdNextOrderPaid(),
                () -> deliver(onTime, p2.code(), 300_000), () -> deliver(late, p1.code(), 300_000));

        assertThat(orderStatus(p2)).isEqualTo("paid");
        assertThat(paymentStatus(late)).isEqualTo("needs_review"); // BR-30: A owned since the first result
        assertThat(orderStatus(p1)).isEqualTo("cancelled");
        assertThat(commerce.enrollments(student.userId())).containsOnlyKeys(courseA);
        assertThat(jdbc.sql("SELECT order_id FROM enrollments WHERE student_id = ? AND course_id = ?")
                .params(student.userId(), courseA).query(Long.class).single()).isEqualTo(p2.id());
    }

    @Test
    void lateResultFirst_thenOnTimeResultOfAnOrderWithAMoreCourse_paidForTheRest_warned_SRSStep7(
            CapturedOutput output) throws Exception {
        String late = CommerceFixtures.newReference();
        FixtureOrder p1 = lateOrderA(late);
        String onTime = CommerceFixtures.newReference();
        FixtureOrder p2 = commerce.order(student.userId()).line(courseA, 300_000).line(courseB, 200_000).insert();
        commerce.pendingPayment(p2, onTime);

        Interleave.secondWaitsForTheFirst(jdbc, hooks.holdNextOrderPaid(),
                () -> deliver(late, p1.code(), 300_000), () -> deliver(onTime, p2.code(), 500_000));

        assertThat(orderStatus(p1)).isEqualTo("paid");
        assertThat(orderStatus(p2)).isEqualTo("paid");
        assertThat(jdbc.sql("SELECT order_id FROM enrollments WHERE student_id = ? AND course_id = ?")
                .params(student.userId(), courseB).query(Long.class).single()).isEqualTo(p2.id());
        assertThat(output.getOut()).contains("already owned courses").contains(p2.code());
    }

    @Test
    void timerFindsPaid_whileTheStudentsCreateOrderHoldsTheCart_noDeadlock_paidOnce() throws Exception {
        String ref = CommerceFixtures.newReference();
        Instant created = Instant.now().minus(25, ChronoUnit.HOURS);
        FixtureOrder overdue = commerce.order(student.userId()).line(courseA, 300_000).createdAt(created).insert();
        commerce.payment(overdue, ref, "pending", created, created.plus(15, ChronoUnit.MINUTES));
        commerce.cart(student.userId(), courseA, courseB); // T1 would replace the overdue order
        gateway.simulatePaid(ref, 300_000);
        FakeQrPaymentGateway.Hold timerLookup = gateway.holdNextQuery();

        // the timer holds the overdue order inside its lookup; T1 locks the cart and waits for that order
        Interleave.Results<CommerceExpiryService.RunResult, Integer> results = Interleave.secondWaitsForTheFirst(
                jdbc, timerLookup, expiry::cancelOverdueOrders,
                () -> mvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, student.bearer()))
                        .andReturn().getResponse().getStatus());

        // T1 got the order after the claim ended, found it paid and stopped (409); the timer's T2 then waited for the
        // cart and saw the payment already applied — no deadlock, one effect
        assertThat(results.second()).isEqualTo(409);
        assertThat(results.first().failed()).isZero();
        assertThat(orderStatus(overdue)).isEqualTo("paid");
        assertThat(paymentStatus(ref)).isEqualTo("success");
        assertThat(commerce.enrollments(student.userId())).containsOnlyKeys(courseA);
        assertThat(hooks.published()).hasSize(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM orders WHERE student_id = ?").param(student.userId())
                .query(Long.class).single()).isEqualTo(1);
    }

    /**
     * Review scenario (d): cancel stops inside its lookup; the webhook (verified, so the provider now reports the payment)
     * waits for the student's cart. Released, the lookup sees the payment: cancel records it and answers 409 (UC-S16 E1);
     * the webhook then finds it applied. One effect.
     */
    @Test
    void cancelHoldsTheOrderInItsLookup_webhookWaits_cancelFindsItPaid409_webhookIgnored_oneEffect_UCS16E1()
            throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = commerce.order(student.userId()).line(courseA, 300_000).insert();
        commerce.pendingPayment(order, ref);

        Interleave.Results<Integer, Integer> results = Interleave.secondWaitsForTheFirst(jdbc, gateway.holdNextQuery(),
                () -> mvc.perform(post("/api/v1/orders/" + order.code() + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, student.bearer())).andReturn().getResponse().getStatus(),
                () -> deliver(ref, order.code(), 300_000));

        assertThat(results.first()).isEqualTo(409); // ORDER_ALREADY_PAID
        assertThat(results.second()).isEqualTo(200);
        assertThat(orderStatus(order)).isEqualTo("paid");
        assertThat(commerce.orderColumn(order.code(), "cancel_reason", String.class)).isNull();
        assertThat(paymentStatus(ref)).isEqualTo("success");
        assertThat(commerce.enrollments(student.userId())).containsOnlyKeys(courseA);
        assertThat(hooks.published()).hasSize(1);
    }

    /** Review scenario (g): the timer stops inside its lookup; the webhook waits for the order, then is applied. */
    @Test
    void timerHoldsTheOrderInItsLookup_webhookWaits_thenApplied_oneEffect() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = commerce.order(student.userId()).line(courseA, 300_000).insert();
        Instant created = Instant.now().minus(20, ChronoUnit.MINUTES);
        commerce.payment(order, ref, "pending", created, created.plus(15, ChronoUnit.MINUTES));

        Interleave.Results<CommerceExpiryService.RunResult, Integer> results = Interleave.secondWaitsForTheFirst(jdbc,
                gateway.holdNextQuery(), expiry::expireQrAttempts, () -> deliver(ref, order.code(), 300_000));

        assertThat(results.first().failed()).isZero();
        assertThat(results.second()).isEqualTo(200);
        assertThat(orderStatus(order)).isEqualTo("paid"); // the lookup saw it paid: T2 after the claim, webhook ignored
        assertThat(paymentStatus(ref)).isEqualTo("success");
        assertThat(hooks.published()).hasSize(1);
    }
}