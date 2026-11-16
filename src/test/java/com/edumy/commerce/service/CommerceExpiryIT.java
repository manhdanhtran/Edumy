package com.edumy.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.edumy.commerce.integration.FakeQrPaymentGateway;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CommerceFixtures;
import com.edumy.support.CommerceFixtures.FixtureOrder;
import com.edumy.support.CourseFixtures;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The commerce timers on MySQL (P11-e, NFR-09), called directly (the scheduler is off in tests): AC-22 a pending order
 * older than 24 h is cancelled as {@code expired} after the lookup (paid → recorded instead; provider down → kept and
 * retried), UC-S05 A1 expired QR attempts closed after the lookup (AC-02), two runs at the same time never handle one
 * order twice (one lookup per order), and a row another transaction holds is skipped, not waited for (SKIP LOCKED).
 */
@MySqlSpringBootTest
class CommerceExpiryIT {

    @Autowired
    CommerceExpiryService expiry;
    @Autowired
    FakeQrPaymentGateway gateway;
    @Autowired
    TestUsers users;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    com.edumy.support.PaymentTestHooks hooks;

    CourseFixtures courses;
    CommerceFixtures commerce;
    TestUser student;
    long course;

    @BeforeEach
    void setUp() {
        courses = new CourseFixtures(jdbc);
        commerce = new CommerceFixtures(jdbc);
        TestUser instructor = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        student = users.create(RoleCode.STUDENT);
        course = courses.draftCourse(instructor.id());
        courses.setStatus(course, "published", false);
        jdbc.sql("UPDATE courses SET price = 300000 WHERE id = ?").param(course).update();
    }

    @AfterEach
    void cleanUp() {
        commerce.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    FixtureOrder overdueOrder(TestUser who, String ref) {
        Instant created = Instant.now().minus(25, ChronoUnit.HOURS);
        FixtureOrder order = commerce.order(who.id()).line(course, 300_000).createdAt(created).insert();
        if (ref != null) {
            commerce.payment(order, ref, "pending", created, created.plus(15, ChronoUnit.MINUTES));
        }
        return order;
    }

    String status(FixtureOrder order) {
        return commerce.orderColumn(order.code(), "status", String.class);
    }

    // ---- AC-22 (BR-29, UC-S16 by the timer)

    @Test
    void overdueOrder_lookedUpNotPaid_cancelledAsExpired_attemptExpired_AC22() {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = overdueOrder(student, ref);
        commerce.cart(student.id(), course);

        CommerceExpiryService.RunResult result = expiry.cancelOverdueOrders();

        assertThat(result.processed()).isGreaterThanOrEqualTo(1);
        assertThat(status(order)).isEqualTo("cancelled");
        assertThat(commerce.orderColumn(order.code(), "cancel_reason", String.class)).isEqualTo("expired");
        assertThat(commerce.paymentColumn(ref, "status", String.class)).isEqualTo("expired");
        assertThat(gateway.queryCount(ref)).isEqualTo(1);
        assertThat(commerce.cartCourseIds(student.id())).containsExactly(course); // BR-15: the cart is kept
    }

    @Test
    void overdueOrderWithoutAttempt_cancelled_noLookup_andAYoungerOrderIsLeftAlone() {
        FixtureOrder overdue = overdueOrder(student, null);
        TestUser other = users.create(RoleCode.STUDENT);
        FixtureOrder young = commerce.order(other.id()).line(course, 300_000)
                .createdAt(Instant.now().minus(23, ChronoUnit.HOURS)).insert();

        expiry.cancelOverdueOrders();

        assertThat(status(overdue)).isEqualTo("cancelled");
        assertThat(status(young)).isEqualTo("pending");
        assertThat(gateway.totalQueries()).isZero();
    }

    @Test
    void overdueOrderFoundPaid_paidInsteadOfCancelled_enrolled_AC22() {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = overdueOrder(student, ref);
        gateway.simulatePaid(ref, 300_000);

        expiry.cancelOverdueOrders();

        assertThat(status(order)).isEqualTo("paid");
        assertThat(commerce.orderColumn(order.code(), "cancelled_at", java.sql.Timestamp.class)).isNull();
        assertThat(commerce.enrollments(student.id())).containsOnlyKeys(course);
    }

    @Test
    void providerDown_orderKept_retriedAtTheNextRun_UCS16E2() {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = overdueOrder(student, ref);
        gateway.setUnavailable(true);

        CommerceExpiryService.RunResult down = expiry.cancelOverdueOrders();

        assertThat(down.failed()).isGreaterThanOrEqualTo(1);
        assertThat(status(order)).isEqualTo("pending");
        assertThat(gateway.queryCount(ref)).isEqualTo(1); // not retried in a loop within the run

        gateway.setUnavailable(false);
        expiry.cancelOverdueOrders();
        assertThat(status(order)).isEqualTo("cancelled");
    }

    // ---- UC-S05 A1 (AC-02)

    @Test
    void expiredQr_lookedUpNotPaid_attemptExpired_orderStaysPending_validQrUntouched_AC02() {
        FixtureOrder order = commerce.order(student.id()).line(course, 300_000).insert();
        String expired = CommerceFixtures.newReference();
        Instant created = Instant.now().minus(20, ChronoUnit.MINUTES);
        commerce.payment(order, expired, "pending", created, created.plus(15, ChronoUnit.MINUTES));
        TestUser other = users.create(RoleCode.STUDENT);
        commerce.track(other.id());
        FixtureOrder otherOrder = commerce.order(other.id()).line(course, 300_000).insert();
        String valid = CommerceFixtures.newReference();
        commerce.pendingPayment(otherOrder, valid);

        expiry.expireQrAttempts();

        assertThat(commerce.paymentColumn(expired, "status", String.class)).isEqualTo("expired");
        assertThat(status(order)).isEqualTo("pending"); // a new QR can be made (AC-02)
        assertThat(commerce.paymentColumn(valid, "status", String.class)).isEqualTo("pending");
        assertThat(gateway.queryCount(valid)).isZero();
        assertThat(commerce.enrollments(student.id())).isEmpty();
    }

    @Test
    void expiredQrFoundPaid_orderPaid() {
        FixtureOrder order = commerce.order(student.id()).line(course, 300_000).insert();
        String ref = CommerceFixtures.newReference();
        Instant created = Instant.now().minus(20, ChronoUnit.MINUTES);
        commerce.payment(order, ref, "pending", created, created.plus(15, ChronoUnit.MINUTES));
        gateway.simulatePaid(ref, 300_000);

        expiry.expireQrAttempts();

        assertThat(status(order)).isEqualTo("paid");
        assertThat(commerce.paymentColumn(ref, "status", String.class)).isEqualTo("success");
    }

    // ---- NFR-09: several instances

    /**
     * NFR-09 forced overlap (review P11-R-03): run A stops inside the lookup of its first order; run B, started while A
     * holds it, must finish on its own, skipping that order. Every order is handled exactly once (one lookup each), both
     * runs did work, and the counts add up to exactly 10.
     */
    @Test
    void twoRunsOverlapping_bothWork_everyOrderHandledOnce_oneLookupPerOrder_NFR09() throws Exception {
        List<FixtureOrder> orders = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            TestUser buyer = users.create(RoleCode.STUDENT); // one pending order per student (BR-29)
            commerce.track(buyer.id());
            String ref = CommerceFixtures.newReference();
            refs.add(ref);
            orders.add(overdueOrder(buyer, ref));
        }

        com.edumy.support.Interleave.Results<CommerceExpiryService.RunResult, CommerceExpiryService.RunResult> runs =
                com.edumy.support.Interleave.secondFinishesWhileFirstHeld(gateway.holdNextQuery(),
                        expiry::cancelOverdueOrders, expiry::cancelOverdueOrders);

        assertThat(runs.first().processed()).isGreaterThanOrEqualTo(1);
        assertThat(runs.second().processed()).isGreaterThanOrEqualTo(1);
        assertThat(runs.first().processed() + runs.second().processed()).isEqualTo(10);
        assertThat(runs.first().failed() + runs.second().failed()).isZero();
        for (int i = 0; i < 10; i++) {
            assertThat(status(orders.get(i))).isEqualTo("cancelled");
            assertThat(gateway.queryCount(refs.get(i))).as(refs.get(i)).isEqualTo(1);
        }
    }

    /** Review scenario (j): one broken order is rolled back and counted as failed; the others of the run go through. */
    @Test
    void oneOrderFails_theOthersOfTheRunAreProcessed_theBrokenOneStaysPending() {
        List<FixtureOrder> orders = new ArrayList<>();
        String broken = null;
        for (int i = 0; i < 3; i++) {
            TestUser buyer = users.create(RoleCode.STUDENT);
            commerce.track(buyer.id());
            String ref = CommerceFixtures.newReference();
            orders.add(overdueOrder(buyer, ref));
            if (i == 1) {
                broken = ref;
            }
        }
        gateway.failQueriesFor(broken);

        CommerceExpiryService.RunResult result = expiry.cancelOverdueOrders();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.processed()).isGreaterThanOrEqualTo(2);
        assertThat(status(orders.get(0))).isEqualTo("cancelled");
        assertThat(status(orders.get(1))).isEqualTo("pending");
        assertThat(status(orders.get(2))).isEqualTo("cancelled");
        assertThat(gateway.queryCount(broken)).isEqualTo(1);
    }

    /** "Found paid" whose T2 fails afterwards: counted as failed, nothing kept, retried at the next run. */
    @Test
    void foundPaid_butTheT2Fails_nothingKept_retriedNextRun() {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = overdueOrder(student, ref);
        gateway.simulatePaid(ref, 300_000);
        hooks.failNextOrderPaid();

        CommerceExpiryService.RunResult first = expiry.cancelOverdueOrders();

        assertThat(first.failed()).isGreaterThanOrEqualTo(1);
        assertThat(status(order)).isEqualTo("pending");
        assertThat(commerce.paymentColumn(ref, "status", String.class)).isEqualTo("pending");
        assertThat(commerce.enrollments(student.id())).isEmpty();

        expiry.cancelOverdueOrders();
        assertThat(status(order)).isEqualTo("paid");
    }
    @Test
    void anOrderLockedElsewhere_isSkippedNotWaitedFor_SKIP_LOCKED() throws Exception {
        FixtureOrder held = overdueOrder(student, null);
        TestUser other = users.create(RoleCode.STUDENT);
        commerce.track(other.id());
        FixtureOrder free = overdueOrder(other, null);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
                jdbc.sql("SELECT id FROM orders WHERE id = ? FOR UPDATE").param(held.id()).query(Long.class).single();
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            expiry.cancelOverdueOrders();
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(took).isLessThan(Duration.ofSeconds(10)); // innodb_lock_wait_timeout would be 50 s
            assertThat(status(free)).isEqualTo("cancelled");
            assertThat(status(held)).isEqualTo("pending");
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        expiry.cancelOverdueOrders(); // the next run takes it
        assertThat(status(held)).isEqualTo("cancelled");
    }

    // ---- sentinel (no IN ())

    @Test
    void seenOrSentinel_neverEmpty() {
        assertThat(CommerceExpiryService.seenOrSentinel(List.of())).containsExactly(-1L);
        assertThat(CommerceExpiryService.seenOrSentinel(List.of(7L, 9L))).containsExactly(7L, 9L);
    }
}
