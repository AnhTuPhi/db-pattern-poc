package com.claude.dbpoc.m17.web;

import com.claude.dbpoc.m17.service.ExhaustionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The three lessons:
 *
 *   GET /pool/cascade?slowCount=10&slowMs=4000&fastCount=20
 *      — slow path eats the main pool, fast requests cascade-timeout.
 *
 *   GET /pool/retry-storm?slowCount=10&slowMs=4000&fastCount=20
 *      — naive retries amplify the pressure ~3x.
 *
 *   GET /pool/bulkhead?slowCount=10&slowMs=4000&fastCount=20
 *      — slow path on its own pool; main stays healthy.
 *
 *   GET /pool/stats
 *      — live snapshot of both pools.
 */
@RestController
@RequestMapping("/pool")
public class PoolController {

    private final ExhaustionService svc;

    public PoolController(ExhaustionService svc) {
        this.svc = svc;
    }

    @GetMapping("/cascade")
    public Map<String, Object> cascade(
            @RequestParam(defaultValue = "10") int slowCount,
            @RequestParam(defaultValue = "4000") long slowMs,
            @RequestParam(defaultValue = "20") int fastCount) {
        return svc.cascade(slowCount, slowMs, fastCount);
    }

    @GetMapping("/retry-storm")
    public Map<String, Object> retryStorm(
            @RequestParam(defaultValue = "10") int slowCount,
            @RequestParam(defaultValue = "4000") long slowMs,
            @RequestParam(defaultValue = "20") int fastCount) {
        return svc.retryStorm(slowCount, slowMs, fastCount);
    }

    @GetMapping("/bulkhead")
    public Map<String, Object> bulkhead(
            @RequestParam(defaultValue = "10") int slowCount,
            @RequestParam(defaultValue = "4000") long slowMs,
            @RequestParam(defaultValue = "20") int fastCount) {
        return svc.bulkhead(slowCount, slowMs, fastCount);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return svc.stats();
    }
}
