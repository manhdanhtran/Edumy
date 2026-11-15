package com.edumy.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.edumy.commerce.integration.FakeQrPaymentGateway;
import com.edumy.commerce.repository.OrderRepository;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CommerceFixtures;
import com.edumy.support.CommerceFixtures.FixtureOrder;
import com.edumy.support.CourseFixtures;
import com.edumy.support.Interleave;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Review P11-R-01 (P11-fix-b): the timers' claim queries walk their index and stop at the first free row — no filesort
 * (EXPLAIN of the exact SQL of {@link OrderRepository}), so a claim locks <b>one</b> order: while instance A is inside
 * the lookup of its order, instance B claims another expired order at once and a student's request on a third order is
 * not blocked.
 */
@MySqlSpringBootTest
class ClaimQueryPlanIT {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    TestUsers users;
    @Autowired
    CommerceExpiryService expiry;
    @Autowired
    PaymentService payments;
    @Autowired
    FakeQrPaymentGateway gateway;

    CourseFixtures courses;
    CommerceFixtures commerce;
    long course;
    final List<TestUser> students = new ArrayList<>();
    final List<FixtureOrder> orders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        courses = new CourseFixtures(jdbc);
        commerce = new CommerceFixtures(jdbc);
        TestUser instructor = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        course = courses.draftCourse(instructor.id());
        courses.setStatus(course, "published", false);
        jdbc.sql("UPDATE courses SET price = 300000 WHERE id = ?").param(course).update();
        Instant created = Instant.now().minus(30, ChronoUnit.MINUTES);
        for (int i = 0; i < 4; i++) { // four students, each with one pending order whose QR expired
            TestUser student = users.create(RoleCode.STUDENT);
            commerce.track(student.id());
            students.add(student);
            FixtureOrder order = commerce.order(student.id()).line(course, 300_000).insert();
            commerce.payment(order, CommerceFixtures.newReference(), "pending", created.plusSeconds(i),
                    created.plusSeconds(60 + i));
            orders.add(order);
        }
    }

    @AfterEach
    void cleanUp() {
        commerce.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    /** The query text of a repository method with its parameters inlined (for EXPLAIN). */
    static String sql(String method, Class<?>... types) throws Exception {
        return OrderRepository.class.getMethod(method, types).getAnnotation(Query.class).value()
                .replace(":now", "CURRENT_TIMESTAMP(6)").replace(":cutoff", "CURRENT_TIMESTAMP(6)")
                .replace(":seen", "-1");
    }

    List<Map<String, Object>> explain(String sql) {
        List<Map<String, Object>> plan = jdbc.sql("EXPLAIN " + sql).query().listOfRows();
        org.slf4j.LoggerFactory.getLogger(ClaimQueryPlanIT.class).info("EXPLAIN plan: {}", plan);
        return plan;
    }

    @Test
    void expiredQrClaim_walksIxPaymentPendingExpiry_noFilesort_R01() throws Exception {
        List<Map<String, Object>> plan = explain(sql("claimOrderWithExpiredQr", Instant.class, Collection.class));

        assertThat(plan).as(plan.toString()).noneMatch(row -> String.valueOf(row.get("Extra")).contains("filesort"));
        assertThat(plan).as(plan.toString()).anyMatch(row -> "p".equals(row.get("table"))
                && "ix_payment_pending_expiry".equals(row.get("key")));
    }

    @Test
    void overdueOrderClaim_walksIxOrdersPendingCreated_noFilesort() throws Exception {
        List<Map<String, Object>> plan = explain(sql("claimOverdueOrder", Instant.class, Collection.class));

        assertThat(plan).as(plan.toString()).noneMatch(row -> String.valueOf(row.get("Extra")).contains("filesort"));
        assertThat(plan).as(plan.toString()).anyMatch(row -> "ix_orders_pending_created".equals(row.get("key")));
    }

    @Test
    void whileInstanceAHoldsOneOrder_instanceBClaimsAnotherAtOnce_R01() throws Exception {
        FakeQrPaymentGateway.Hold aLookup = gateway.holdNextQuery();

        Interleave.Results<CommerceExpiryService.RunResult, CommerceExpiryService.RunResult> results =
                Interleave.secondFinishesWhileFirstHeld(aLookup, expiry::expireQrAttempts, expiry::expireQrAttempts);

        assertThat(results.second().processed()).isEqualTo(3); // B took the three orders A did not hold
        assertThat(results.first().processed()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM payment_transactions p JOIN orders o ON o.id = p.order_id
                        WHERE o.student_id IN (?, ?, ?, ?) AND p.status = 'expired'""")
                .params(students.get(0).id(), students.get(1).id(), students.get(2).id(), students.get(3).id())
                .query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void whileATimerHoldsOneOrder_anotherStudentsRequestOnAnUnclaimedOrderIsNotBlocked_R01() throws Exception {
        FakeQrPaymentGateway.Hold timerLookup = gateway.holdNextQuery();
        FixtureOrder last = orders.get(3);
        String lastRef = jdbc.sql("SELECT transaction_ref FROM payment_transactions WHERE order_id = ?")
                .param(last.id()).query(String.class).single();

        // the timer stops inside the lookup of the oldest expired order; a webhook for the newest one goes through
        Interleave.secondFinishesWhileFirstHeld(timerLookup, expiry::expireQrAttempts, () -> {
            payments.applyProviderResult(new com.edumy.commerce.integration.PaymentResult(lastRef, null, 300_000,
                    true, Instant.now()));
            return null;
        });

        assertThat(commerce.orderColumn(last.code(), "status", String.class)).isEqualTo("paid");
    }
}
