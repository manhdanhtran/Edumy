package com.edumy.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deterministic interleaving for lost-update tests (P10 DT-B1): a "holder" transaction takes a row lock and changes
 * data, the request under test starts and blocks on that lock, and only once InnoDB reports it as waiting
 * ({@code information_schema.innodb_trx}, {@code LOCK WAIT}; the test user has {@code PROCESS}, see {@link SharedMySql})
 * does the holder commit. The request then proves whether it reads the committed data or a stale snapshot. No sleep.
 */
public final class LockWaits {

    private LockWaits() {
    }

    /**
     * Runs {@code holderWork} in its own committed transaction on a separate thread; while its locks are held, starts
     * {@code request} on another thread, waits until some transaction is in {@code LOCK WAIT}, lets the holder commit
     * and returns the request's result.
     */
    public static <T> T whileLockedThenCommit(PlatformTransactionManager txManager, JdbcClient jdbc,
                                              Consumer<JdbcClient> holderWork, Callable<T> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> holder = pool.submit(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
                holderWork.accept(jdbc);
                locked.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("holder was never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            if (!locked.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("holder did not take its locks");
            }
            Future<T> result = pool.submit(request);
            await().atMost(Duration.ofSeconds(20)).until(() -> lockWaits(jdbc) > 0);
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            return result.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Transactions currently waiting for a row lock. */
    public static long lockWaits(JdbcClient jdbc) {
        return jdbc.sql("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state = 'LOCK WAIT'")
                .query(Long.class).single();
    }
}
