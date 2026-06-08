package com.claude.dbpoc.common;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Tiny benchmark helper — every POC compares "before vs after" timings, so we
 * standardise the shape of the measurement (warm-up + runs + nanoTime).
 *
 * Not a replacement for JMH; it's deliberately simple so each POC stays
 * self-contained and the numbers in the REST responses are reproducible.
 */
public final class Timing {

    private Timing() {}

    public record Result(String label, long iterations, long totalNanos, long minNanos, long maxNanos) {
        public double avgMicros() {
            return iterations == 0 ? 0 : (totalNanos / 1_000.0) / iterations;
        }
        public double totalMillis() {
            return totalNanos / 1_000_000.0;
        }
        public String summary() {
            return "%-40s | %6d runs | total=%8.2f ms | avg=%9.2f us | min=%6d us | max=%6d us"
                .formatted(label, iterations, totalMillis(), avgMicros(),
                           minNanos / 1_000, maxNanos / 1_000);
        }
    }

    public static <T> Result measure(String label, int iterations, Supplier<T> body) {
        // Light warm-up to let JIT settle.
        for (int i = 0; i < Math.min(iterations, 3); i++) body.get();

        long total = 0, min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (int i = 0; i < iterations; i++) {
            long t = System.nanoTime();
            body.get();
            long elapsed = System.nanoTime() - t;
            total += elapsed;
            if (elapsed < min) min = elapsed;
            if (elapsed > max) max = elapsed;
        }
        return new Result(label, iterations, total, min, max);
    }

    public static Result measureVoid(String label, int iterations, Runnable body) {
        return measure(label, iterations, () -> { body.run(); return null; });
    }

    public static <T> T timed(String label, Callable<T> body) {
        long t = System.nanoTime();
        try {
            T out = body.call();
            System.out.printf("  [timed] %-40s %.2f ms%n", label, (System.nanoTime() - t) / 1_000_000.0);
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
