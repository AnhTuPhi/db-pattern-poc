package com.claude.dbpoc.m14.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Two-thread coordinator (copy of m10/m11/m12 — kept per-module so each
 * module is self-contained).
 */
public final class Concurrency {

    private Concurrency() {}

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

    public static void quiet(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static void await(CountDownLatch latch) {
        try { latch.await(30, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
