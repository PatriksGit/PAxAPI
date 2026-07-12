package hu.patriksgit.paxapi.concurrent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Named, daemon-thread executor construction + a shared graceful-shutdown helper. Replaces the
 * hand-written "named daemon pool + shutdown/awaitTermination/shutdownNow" pattern that would
 * otherwise be duplicated at every call site. The library never spawns threads on its own —
 * every method here only creates a pool/thread when the caller explicitly invokes it, and the
 * caller keeps holding a plain JDK {@link ExecutorService}/{@link ScheduledExecutorService}
 * (no wrapper type), so existing interop such as
 * {@link hu.patriksgit.paxapi.database.Database#watchConnection} is unaffected.
 */
public final class ManagedExecutors {
    private ManagedExecutors() {}

    /**
     * Fixed-size pool of daemon threads named {@code namePrefix + "-" + N} (N starting at 1).
     * No automatic sizing heuristic — {@code poolSize} must be a positive number the caller
     * chose based on their own workload (CPU-bound vs. I/O-bound).
     */
    public static ExecutorService fixed(String namePrefix, int poolSize) {
        requireNonBlank(namePrefix, "namePrefix");
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be positive, got " + poolSize);
        }
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, namePrefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(poolSize, tf);
    }

    /** Single daemon thread named exactly {@code name} (no sequence suffix). */
    public static ExecutorService singleThread(String name) {
        requireNonBlank(name, "name");
        return Executors.newSingleThreadExecutor(namedDaemonFactory(name));
    }

    /** Single daemon thread named exactly {@code name}, as a {@link ScheduledExecutorService}. */
    public static ScheduledExecutorService singleThreadScheduled(String name) {
        requireNonBlank(name, "name");
        return Executors.newSingleThreadScheduledExecutor(namedDaemonFactory(name));
    }

    /**
     * Drains {@code executor}: {@code shutdown()}, then waits up to {@code timeout} for it to
     * terminate. If it doesn't drain in time — OR if this thread is interrupted while waiting —
     * calls {@code shutdownNow()} and (if non-null) invokes {@code onForcedShutdown}. Restores
     * this thread's interrupt status if the wait was interrupted. Never throws out of this
     * method itself (beyond the null-check below): a throwing {@code onForcedShutdown} is
     * swallowed so it cannot block the rest of a caller's shutdown sequence.
     *
     * @param onForcedShutdown may be {@code null} — {@code null} means "stay silent on forced
     *                         shutdown", matching call sites that don't want any callback at all.
     */
    public static void shutdownGracefully(ExecutorService executor, Duration timeout, Runnable onForcedShutdown) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(timeout, "timeout");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                runForcedShutdownCallback(onForcedShutdown);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            runForcedShutdownCallback(onForcedShutdown);
        }
    }

    private static void runForcedShutdownCallback(Runnable onForcedShutdown) {
        if (onForcedShutdown == null) return;
        try { onForcedShutdown.run(); } catch (Throwable ignored) { }
    }

    private static ThreadFactory namedDaemonFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private static void requireNonBlank(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(paramName + " must not be null or blank, got '" + value + "'");
        }
    }
}
