package com.claude.dbpoc.m13.web;

import com.claude.dbpoc.m13.service.BenchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Driver for the three-strategy benchmark.
 *
 *   /bench/optimistic   — @Version + retry
 *   /bench/pessimistic  — SELECT ... FOR UPDATE
 *   /bench/cas          — UPDATE ... SET balance = balance + ?
 *   /bench/all          — run all three back-to-back, return a stacked table.
 *
 * Parameters (same on every endpoint):
 *   threads             — concurrent workers
 *   iterations          — +$1 increments per worker
 *   hot                 — true: all workers hit id=1 (worst-case contention)
 *                         false: workers spread across accountCount rows
 *   accounts            — number of distinct ids to rotate over in dispersed mode
 *
 * Always call POST /seed before running. The pessimistic + cas variants
 * mutate the balance; without re-seeding, the "expected" final balance
 * drifts and the result map's verdict becomes misleading.
 */
@RestController
@RequestMapping("/bench")
public class BenchController {

    private final BenchService bench;

    public BenchController(BenchService bench) {
        this.bench = bench;
    }

    @GetMapping("/optimistic")
    public Map<String, Object> optimistic(
            @RequestParam(defaultValue = "16") int threads,
            @RequestParam(defaultValue = "200") int iterations,
            @RequestParam(defaultValue = "true") boolean hot,
            @RequestParam(defaultValue = "32") int accounts) {
        return bench.runOptimistic(threads, iterations, hot, accounts);
    }

    @GetMapping("/pessimistic")
    public Map<String, Object> pessimistic(
            @RequestParam(defaultValue = "16") int threads,
            @RequestParam(defaultValue = "200") int iterations,
            @RequestParam(defaultValue = "true") boolean hot,
            @RequestParam(defaultValue = "32") int accounts) {
        return bench.runPessimistic(threads, iterations, hot, accounts);
    }

    @GetMapping("/cas")
    public Map<String, Object> cas(
            @RequestParam(defaultValue = "16") int threads,
            @RequestParam(defaultValue = "200") int iterations,
            @RequestParam(defaultValue = "true") boolean hot,
            @RequestParam(defaultValue = "32") int accounts) {
        return bench.runCas(threads, iterations, hot, accounts);
    }

    /**
     * Headline endpoint. Runs all three strategies back-to-back with the
     * same input parameters. The response is a single JSON object with a
     * `results` list ordered by elapsedMs ascending — the cheapest strategy
     * sits at the top.
     *
     * NB: each strategy mutates the balance, so we run them in series with
     * no re-seed between. The TOTAL ops per row will be wrong by a factor
     * of 3 at the end; for production-grade numbers, seed → optimistic →
     * seed → pessimistic → seed → cas. The combined /all endpoint is a
     * convenience for "give me the verdict in one call".
     */
    @GetMapping("/all")
    public Map<String, Object> all(
            @RequestParam(defaultValue = "16") int threads,
            @RequestParam(defaultValue = "200") int iterations,
            @RequestParam(defaultValue = "true") boolean hot,
            @RequestParam(defaultValue = "32") int accounts) {
        List<Map<String, Object>> results = List.of(
            bench.runOptimistic(threads, iterations, hot, accounts),
            bench.runPessimistic(threads, iterations, hot, accounts),
            bench.runCas(threads, iterations, hot, accounts)
        );

        // Find the fastest for the verdict — by elapsed wall-clock ms.
        Map<String, Object> fastest = results.stream()
                .min((a, b) -> Long.compare(
                    ((Number) a.get("elapsedMs")).longValue(),
                    ((Number) b.get("elapsedMs")).longValue()))
                .orElseThrow();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("note",
            "Each strategy ran back-to-back with the SAME input. " +
            "Balance state was not reset between runs — see /seed.");
        out.put("threads", threads);
        out.put("iterations", iterations);
        out.put("hotRow", hot);
        out.put("accountCount", accounts);
        out.put("results", results);
        out.put("fastestStrategy", fastest.get("strategy"));
        out.put("verdict",
            "Expected shape on Postgres localhost: hot row → pessimistic " +
            "fastest (no retries; FIFO queue); dispersed → cas fastest (no " +
            "read, no contention). If optimistic wins, your conflict rate " +
            "is low enough that the retry budget never triggers.");
        return out;
    }
}
