package com.edumy.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.edumy.commerce.integration.FakeQrPaymentGateway.Hold;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Forced interleavings of two <b>application</b> flows (review P11-R-03): the first flow stops at a {@link Hold} inside
 * its transaction (fake provider call or the in-transaction {@code OrderPaid} listener), so it holds its real locks; the
 * second flow starts and must either wait for a row lock ({@link #secondWaitsForTheFirst}, proved by
 * {@code information_schema.innodb_trx} showing {@code LOCK WAIT}) or finish while the first is still held
 * ({@link #secondFinishesWhileFirstHeld}, e.g. SKIP LOCKED). Never a sleep; every wait is bounded.
 */
public final class Interleave {

    private Interleave() {
    }

    /** Results of the two flows, in start order. */
    public record Results<A, B>(A first, B second) {
    }

    /**
     * {@code first} runs until it stops at {@code hold}; {@code second} starts and must be seen in {@code LOCK WAIT}
     * (else the test fails: the flows were not serialised); then the hold is released and both results are returned.
     */
    public static <A, B> Results<A, B> secondWaitsForTheFirst(JdbcClient jdbc, Hold hold, Callable<A> first,
                                                              Callable<B> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<A> a = pool.submit(first);
            if (!hold.awaitReached()) {
                throw new AssertionError("the first flow never reached its hold");
            }
            Future<B> b = pool.submit(second);
            try {
                await().atMost(Duration.ofSeconds(15)).until(() -> LockWaits.lockWaits(jdbc) > 0);
            } catch (RuntimeException e) {
                hold.release();
                throw new AssertionError("the second flow did not wait for a lock held by the first one", e);
            }
            hold.release();
            return new Results<>(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            hold.release();
            pool.shutdownNow();
        }
    }

    /**
     * {@code first} runs until it stops at {@code hold}; {@code second} must complete within 10 s while the first is
     * still held (it was neither blocked nor serialised behind it); then the hold is released.
     */
    public static <A, B> Results<A, B> secondFinishesWhileFirstHeld(Hold hold, Callable<A> first, Callable<B> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<A> a = pool.submit(first);
            if (!hold.awaitReached()) {
                throw new AssertionError("the first flow never reached its hold");
            }
            B secondResult;
            try {
                secondResult = pool.submit(second).get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                throw new AssertionError("the second flow was blocked by the first one", e);
            }
            hold.release();
            return new Results<>(a.get(30, TimeUnit.SECONDS), secondResult);
        } finally {
            hold.release();
            pool.shutdownNow();
        }
    }
}
