package com.edumy.commerce.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
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
import com.edumy.notification.integration.MailMessage;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CapturingMailGateway;
import com.edumy.support.CommerceFixtures;
import com.edumy.support.CommerceFixtures.FixtureOrder;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * UC-A18 reconciliation through HTTP on MySQL (P11-d): (a) activated — attempt success, order paid, enrollments for the
 * courses not owned, cart lines removed, (b) refund outside — no access; who / when / action / note stored (step 5,
 * D-29), the result mail (step 6), E1 handled once (also two admins at once), E2 every course owned, E3 note required,
 * BR-26 role re-checked in the database, AC-24 end to end (late webhook → needs_review → refund outside), and the
 * step-1 detail ({@code GET /admin/payments/{id}}, decision Q4).
 */
@MySqlSpringBootTest
class ReconciliationIT {

    static final String PAYMENTS = "/api/v1/admin/payments/";

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
    CapturingMailGateway mails;
    @Autowired
    com.edumy.support.PaymentTestHooks hooks;
    @Value("${edumy.payment.webhook-secret}")
    String secret;

    CourseFixtures courses;
    CommerceFixtures commerce;
    Session admin;
    Session student;
    long courseA;
    long courseB;
    long courseC;

    @BeforeEach
    void setUp() {
        courses = new CourseFixtures(jdbc);
        commerce = new CommerceFixtures(jdbc);
        admin = tokens.newUserSession(RoleCode.ADMIN);
        Session instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        student = tokens.newUserSession(RoleCode.STUDENT);
        commerce.track(student.userId());
        courseA = published(instructor.userId(), 300_000);
        courseB = published(instructor.userId(), 200_000);
        courseC = published(instructor.userId(), 100_000);
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

    /** An order {A, B} cancelled as replaced, whose attempt {@code ref} is an unhandled needs_review. */
    long needsReview(String ref) {
        FixtureOrder order = commerce.order(student.userId()).line(courseA, 300_000).line(courseB, 200_000)
                .cancelled("replaced").insert();
        Instant created = Instant.now().minus(2, ChronoUnit.HOURS);
        return commerce.payment(order, ref, "needs_review", created, created.plus(15, ChronoUnit.MINUTES));
    }

    ResultActions resolve(Session who, long paymentId, String action, String note) throws Exception {
        return mvc.perform(post(PAYMENTS + paymentId + "/resolve").header(HttpHeaders.AUTHORIZATION, who.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"" + action + "\",\"note\":\"" + note + "\"}"));
    }

    // ---- (a) activated

    @Test
    void activated_attemptSuccess_orderPaid_notOwnedCoursesGranted_cartLinesRemoved_handlerStored_mailed_UCA18a()
            throws Exception {
        courses.purchaseEnrollment(student.userId(), courseA); // A owned meanwhile, B not
        commerce.cart(student.userId(), courseB, courseC);
        String ref = CommerceFixtures.newReference();
        long payment = needsReview(ref);

        resolve(admin, payment, "activated", "  Đã đối chiếu sao kê ngân hàng  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payment))
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.handledBy.id").value(admin.userId()))
                .andExpect(jsonPath("$.handledAction").value("activated"))
                .andExpect(jsonPath("$.handledNote").value("Đã đối chiếu sao kê ngân hàng"))
                .andExpect(jsonPath("$.handledAt").isNotEmpty())
                .andExpect(jsonPath("$.student.id").value(student.userId()));

        String code = jdbc.sql("SELECT o.order_code FROM orders o JOIN payment_transactions p ON p.order_id = o.id "
                + "WHERE p.id = ?").param(payment).query(String.class).single();
        assertThat(commerce.orderColumn(code, "status", String.class)).isEqualTo("paid");
        assertThat(commerce.enrollments(student.userId()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(courseA, "purchase", courseB, "purchase"));
        assertThat(jdbc.sql("SELECT order_id FROM enrollments WHERE student_id = ? AND course_id = ?")
                .params(student.userId(), courseB).query(Long.class).single())
                .isEqualTo(commerce.orderColumn(code, "id", Long.class));
        assertThat(commerce.cartCourseIds(student.userId())).containsExactly(courseC);
        // left the queue
        mvc.perform(get("/api/v1/admin/payments?awaitingReview=true").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(jsonPath("$.content[?(@.id == " + payment + ")]", empty()));
        // step 6: result mail, the note stays internal
        MailMessage mail = mails.awaitMail(student.user().email(), "payment-reconciled");
        assertThat(mail.htmlBody()).contains("Thanh toán của bạn đã được xác nhận").contains(code)
                .doesNotContain("sao kê");
    }

    @Test
    void activated_everyCourseAlreadyOwned_409_E2_refundOutsideStillAllowed() throws Exception {
        courses.purchaseEnrollment(student.userId(), courseA);
        courses.purchaseEnrollment(student.userId(), courseB);
        long payment = needsReview(CommerceFixtures.newReference());

        expectProblem(resolve(admin, payment, "activated", "Kích hoạt"), 409, "DATA_CONFLICT");
        assertThat(jdbc.sql("SELECT handled_at FROM payment_transactions WHERE id = ?").param(payment)
                .query((rs, n) -> rs.getTimestamp(1)).list().getFirst()).isNull();

        resolve(admin, payment, "refund_outside", "Hoàn tiền").andExpect(status().isOk());
    }

    // ---- (b) refund outside, AC-24 end to end

    @Test
    void lateResultWithOwnershipConflict_needsReview_thenRefundOutside_noAccess_decisionStored_AC24()
            throws Exception {
        courses.purchaseEnrollment(student.userId(), courseA); // owned through another order
        String ref = CommerceFixtures.newReference();
        FixtureOrder old = commerce.order(student.userId()).line(courseA, 300_000).cancelled("replaced").insert();
        Instant created = Instant.now().minus(1, ChronoUnit.HOURS);
        long payment = commerce.payment(old, ref, "expired", created, created.plus(15, ChronoUnit.MINUTES));
        String body = "{\"transactionRef\":\"" + ref + "\",\"orderCode\":\"" + old.code()
                + "\",\"amount\":300000,\"status\":\"success\"}";
        mvc.perform(post("/api/v1/webhooks/payments/fake").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Signature", sign(body))).andExpect(status().isOk());
        assertThat(commerce.paymentColumn(ref, "status", String.class)).isEqualTo("needs_review");

        resolve(admin, payment, "refund_outside", "Đã hoàn tiền qua chuyển khoản")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("needs_review"))
                .andExpect(jsonPath("$.confirmedAt").value(nullValue()))
                .andExpect(jsonPath("$.handledAction").value("refund_outside"))
                .andExpect(jsonPath("$.handledNote").value("Đã hoàn tiền qua chuyển khoản"));

        assertThat(jdbc.sql("SELECT handled_by FROM payment_transactions WHERE id = ?").param(payment)
                .query(Long.class).single()).isEqualTo(admin.userId());
        assertThat(commerce.paymentColumn(ref, "handled_at", Timestamp.class)).isNotNull();
        assertThat(commerce.paymentColumn(ref, "handled_action", String.class)).isEqualTo("refund_outside");
        assertThat(commerce.orderColumn(old.code(), "status", String.class)).isEqualTo("cancelled");
        assertThat(commerce.enrollments(student.userId())).containsOnlyKeys(courseA); // no new access
        assertThat(mails.awaitMail(student.user().email(), "payment-reconciled").htmlBody())
                .contains("Giao dịch của bạn đã được hoàn tiền");
    }

    // ---- E1, E3, BR-26, concurrency

    @Test
    void alreadyHandled_orNotNeedsReview_409_E1() throws Exception {
        long payment = needsReview(CommerceFixtures.newReference());
        resolve(admin, payment, "refund_outside", "Hoàn tiền").andExpect(status().isOk());

        expectProblem(resolve(admin, payment, "activated", "Lần hai"), 409, "DATA_CONFLICT");

        FixtureOrder pending = commerce.order(student.userId()).line(courseC, 100_000).insert();
        long pendingPayment = commerce.pendingPayment(pending, CommerceFixtures.newReference());
        expectProblem(resolve(admin, pendingPayment, "activated", "Không phải đối soát"), 409, "DATA_CONFLICT");
    }

    /**
     * E1 forced (review P11-R-03): admin 1 activates and stops at the end of the decision holding the locks; admin 2's
     * refund must wait, then sees the attempt handled → 409. The stored decision, the order, the enrollments and the
     * single mail are admin 1's.
     */
    @Test
    void secondAdminWaitsForTheFirstDecision_thenGets409_theFirstDecisionStands_E1() throws Exception {
        long payment = needsReview(CommerceFixtures.newReference());
        Session second = tokens.newUserSession(RoleCode.ADMIN);

        com.edumy.support.Interleave.Results<Integer, Integer> results =
                com.edumy.support.Interleave.secondWaitsForTheFirst(jdbc, hooks.holdNextReconciliation(),
                        () -> resolve(admin, payment, "activated", "Admin 1").andReturn().getResponse().getStatus(),
                        () -> resolve(second, payment, "refund_outside", "Admin 2").andReturn().getResponse()
                                .getStatus());

        assertThat(results.first()).isEqualTo(200);
        assertThat(results.second()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT handled_by FROM payment_transactions WHERE id = ?").param(payment)
                .query(Long.class).single()).isEqualTo(admin.userId());
        assertThat(jdbc.sql("SELECT handled_action FROM payment_transactions WHERE id = ?").param(payment)
                .query(String.class).single()).isEqualTo("activated");
        assertThat(jdbc.sql("SELECT handled_note FROM payment_transactions WHERE id = ?").param(payment)
                .query(String.class).single()).isEqualTo("Admin 1");
        assertThat(jdbc.sql("SELECT o.status FROM orders o JOIN payment_transactions p ON p.order_id = o.id "
                + "WHERE p.id = ?").param(payment).query(String.class).single()).isEqualTo("paid");
        assertThat(commerce.enrollments(student.userId())).containsOnlyKeys(courseA, courseB);
        mails.awaitMail(student.user().email(), "payment-reconciled");
        assertThat(mails.sentTo(student.user().email())).hasSize(1);
    }
    @Test
    void blankNoteOrBadAction_400_nothingStored_E3() throws Exception {
        long payment = needsReview(CommerceFixtures.newReference());

        expectProblem(resolve(admin, payment, "activated", "   "), 400, "VALIDATION_FAILED");
        expectProblem(resolve(admin, payment, "refund", "Ghi chú"), 400, "VALIDATION_FAILED");

        assertThat(jdbc.sql("SELECT handled_at FROM payment_transactions WHERE id = ?").param(payment)
                .query((rs, n) -> rs.getTimestamp(1)).list().getFirst()).isNull();
    }

    @Test
    void adminRoleRevokedInTheDatabase_403_nonAdmin403_unknown404_noToken401_BR26() throws Exception {
        long payment = needsReview(CommerceFixtures.newReference());
        Session revoked = tokens.newUserSession(RoleCode.ADMIN);
        users.revokeRole(revoked.userId(), RoleCode.ADMIN); // JWT still says admin

        expectProblem(resolve(revoked, payment, "activated", "Ghi chú"), 403, "ACCESS_DENIED");
        expectProblem(resolve(student, payment, "activated", "Ghi chú"), 403, "ACCESS_DENIED");
        expectProblem(resolve(admin, 999_999_999L, "activated", "Ghi chú"), 404, "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(post(PAYMENTS + payment + "/resolve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"activated\",\"note\":\"x\"}")), 401, "UNAUTHENTICATED");
        assertThat(commerce.orderColumn(jdbc.sql("SELECT o.order_code FROM orders o JOIN payment_transactions p "
                + "ON p.order_id = o.id WHERE p.id = ?").param(payment).query(String.class).single(), "status",
                String.class)).isEqualTo("cancelled");
    }

    // ---- step 1 detail (Q4)

    @Test
    void detail_linesOwnedCoursesAndTheAmountTheProviderReports_Q4() throws Exception {
        courses.purchaseEnrollment(student.userId(), courseA);
        String ref = CommerceFixtures.newReference();
        long payment = needsReview(ref);

        mvc.perform(get(PAYMENTS + payment).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.id").value(payment))
                .andExpect(jsonPath("$.payment.status").value("needs_review"))
                .andExpect(jsonPath("$.items[*].courseId", contains((int) courseA, (int) courseB)))
                .andExpect(jsonPath("$.items[*].unitPrice", contains(300_000, 200_000)))
                .andExpect(jsonPath("$.items[0].title").isNotEmpty())
                .andExpect(jsonPath("$.ownedCourseIds", contains((int) courseA)))
                .andExpect(jsonPath("$.receivedAmount").value(nullValue()));

        gateway.simulatePaid(ref, 50_000);
        mvc.perform(get(PAYMENTS + payment).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(jsonPath("$.receivedAmount").value(50_000));

        gateway.setUnavailable(true);
        mvc.perform(get(PAYMENTS + payment).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedAmount").value(nullValue()));

        expectProblem(mvc.perform(get(PAYMENTS + payment).header(HttpHeaders.AUTHORIZATION, student.bearer())), 403,
                "ACCESS_DENIED");
        expectProblem(mvc.perform(get(PAYMENTS + 999_999_999L).header(HttpHeaders.AUTHORIZATION, admin.bearer())),
                404, "RESOURCE_NOT_FOUND");
    }

    String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
