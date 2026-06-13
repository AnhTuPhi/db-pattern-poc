package com.claude.dbpoc.m11.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Tiny two-thread coordination helper used by every demo. The pattern is
 * always: start tx A, hand off to tx B, let them race deterministically,
 * collect results.
 *
 * Why CountDownLatch and not a higher-level abstraction: every demo wants
 * a slightly different sync point (sometimes A reads first, sometimes B
 * commits first), and the latch is small enough to inline the choreography
 * in the demo method. Anything more abstract hides the race.
 *
 * (Copied verbatim from module 10 — same shape, package m11.)
 */
public final class Concurrency {

    private Concurrency() {}

    /**
     * Run two suppliers concurrently on a fresh executor; block this thread
     * until both complete or the timeout fires. Returns both results in
     * order.
     *
     * Important: the suppliers must NOT share JPA EntityManagers across
     * threads. Each thread should call a @Transactional method on a Spring
     * service so it gets its own session and connection.
     */
    public static <A, B> Pair<A, B> runBoth(Supplier<A> a, Supplier<B> b, long timeoutSeconds) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<A> fa = pool.submit(a::get);
            Future<B> fb = pool.submit(b::get);
            A av = fa.get(timeoutSeconds, TimeUnit.SECONDS);
            B bv = fb.get(timeoutSeconds, TimeUnit.SECONDS);
            return new Pair<>(av, bv);
        } catch (Exception e) {
            throw new RuntimeException("concurrent demo failed: " + e.getMessage(), e);
        } finally {
            pool.shutdownNow();
        }
    }

    public record Pair<A, B>(A first, B second) {}

    /**
     * Sleep without checked exception. Used in demos to widen the race
     * window so the anomaly is reliably observable. NEVER do this in
     * production code — these are stress-test holds, not idiomatic waits.
     */
    public static void quiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
