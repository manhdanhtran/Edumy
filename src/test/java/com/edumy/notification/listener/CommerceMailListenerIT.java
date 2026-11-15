package com.edumy.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.edumy.notification.integration.MailMessage;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CapturingMailGateway;
import com.edumy.support.CommerceFixtures;
import com.edumy.support.CommerceFixtures.FixtureOrder;
import com.edumy.support.CourseFixtures;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * "Xác nhận mua hàng" end to end (UC-S05 step 9, SRS §7, BR-20): signed webhook → T2 → {@code OrderPaid} →
 * {@link CommerceMailListener} (async, after commit) → names / titles through the owners' {@code api/} → real
 * {@code MailComposer} → {@link CapturingMailGateway}. AC-03: a mail failure leaves the order paid and the enrollments in
 * place; the log carries the order code, never the address (NFR-08). Nothing is mailed for a result that is not applied.
 */
@MySqlSpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class CommerceMailListenerIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    CapturingMailGateway mails;
    @Autowired
    PaymentTestHooks hooks;
    @Value("${edumy.payment.webhook-secret}")
    String secret;

    CourseFixtures courses;
    CommerceFixtures commerce;
    Session student;
    long course;
    String courseTitle;
    FixtureOrder order;
    String ref;

    @BeforeEach
    void setUp() {
        courses = new CourseFixtures(jdbc);
        commerce = new CommerceFixtures(jdbc);
        Session instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        student = tokens.newUserSession(RoleCode.STUDENT);
        jdbc.sql("UPDATE users SET full_name = 'Trần Thị Mua' WHERE id = ?").param(student.userId()).update();
        course = courses.draftCourse(instructor.userId());
        courses.setStatus(course, "published", false);
        jdbc.sql("UPDATE courses SET price = 1250000 WHERE id = ?").param(course).update();
        courseTitle = jdbc.sql("SELECT title FROM courses WHERE id = ?").param(course).query(String.class).single();
        order = commerce.order(student.userId()).line(course, 1_250_000).insert();
        ref = CommerceFixtures.newReference();
        commerce.pendingPayment(order, ref);
    }

    @AfterEach
    void cleanUp() {
        commerce.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    void deliver(long amount) throws Exception {
        String body = "{\"transactionRef\":\"" + ref + "\",\"orderCode\":\"" + order.code() + "\",\"amount\":" + amount
                + ",\"status\":\"success\"}";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mvc.perform(post("/api/v1/webhooks/payments/fake").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Signature",
                                HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)))))
                .andExpect(status().isOk());
    }

    @Test
    void paid_purchaseConfirmationToTheBuyer_codeTotalTitleAndOrderLink_UCS05Step9() throws Exception {
        deliver(1_250_000);

        MailMessage mail = mails.awaitMail(student.user().email(), "purchase-confirmed");

        assertThat(mail.subject()).isEqualTo("Xác nhận thanh toán đơn hàng " + order.code() + " trên Edumy");
        assertThat(mail.htmlBody())
                .contains("Xin chào Trần Thị Mua,")
                .contains("<strong>1.250.000 đ</strong>")
                .contains("“" + courseTitle + "”")
                .contains("href=\"http://localhost:5173/orders/" + order.code() + "\"");
    }

    @Test
    void mailFails_orderStaysPaid_enrollmentKept_logNamesTheOrderNotTheAddress_AC03_BR20(CapturedOutput output)
            throws Exception {
        mails.failNextSendsTo(student.user().email(), 1);

        deliver(1_250_000);
        mails.awaitFailuresConsumed(student.user().email());

        assertThat(commerce.orderColumn(order.code(), "status", String.class)).isEqualTo("paid");
        assertThat(commerce.enrollments(student.userId())).containsOnlyKeys(course);
        await(() -> output.getOut().contains("Mail not sent: event=OrderPaid, orderCode=" + order.code()));
        assertThat(output.getOut()).doesNotContain(student.user().email());
    }

    @Test
    void resultNotApplied_noMail() throws Exception {
        deliver(1); // wrong amount → needs_review, no OrderPaid

        assertThat(commerce.paymentColumn(ref, "status", String.class)).isEqualTo("needs_review");
        assertThat(hooks.published()).isEmpty(); // published synchronously in T2: no event, hence no mail
        assertThat(mails.sentTo(student.user().email())).isEmpty();
    }

    private static void await(java.util.concurrent.Callable<Boolean> condition) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(condition);
    }
}
