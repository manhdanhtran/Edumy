package com.edumy.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Starts tasks at the same moment on separate threads (race tests, P11); never hangs (30 s per task). */
public final class Concurrently {

    private Concurrently() {
    }

    /** Runs every task at once; results in task order. */
    public static <T> List<T> run(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** {@code times} copies of {@code task} at once. */
    public static <T> List<T> times(int times, Callable<T> task) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            tasks.add(task);
        }
        return run(tasks);
    }
}
