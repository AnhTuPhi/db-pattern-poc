package com.claude.dbpoc.m15.web;

import com.claude.dbpoc.m15.service.PoolStressService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The driver for module 15's four demos.
 *
 *   GET /pool/inspect            — live pool state + the static knobs.
 *   GET /pool/stress             — synthetic load; shows borrow timeouts.
 *   GET /pool/sizing-math        — Wooldridge formula + opinion.
 *   GET /pool/lifetime-rotation  — 12s sampling window over the MXBean.
 *
 * All endpoints return JSON. Use jq when reading them; the response shape
 * is shallow and stable so you can pipe into curl-based scripts.
 */
@RestController
@RequestMapping("/pool")
@RequiredArgsConstructor
public class PoolController {

    private final PoolStressService stress;

    @GetMapping("/inspect")
    public Map<String, Object> inspect() {
        return stress.inspect();
    }

    /**
     * Spawn `threads` parallel borrowers. Each holds a connection for
     * `holdMs` via pg_sleep, then releases.
     *
     * Suggested calls:
     *   /pool/stress?threads=5&holdMs=200    — well below pool (no timeouts)
     *   /pool/stress?threads=10&holdMs=500   — at the cap (no timeouts but
     *                                          all in-flight)
     *   /pool/stress?threads=20&holdMs=2000  — > pool size AND > timeout,
     *                                          so half time out at the
     *                                          borrow boundary. THIS is
     *                                          the failure mode the rest
     *                                          of the module is about.
     */
    @GetMapping("/stress")
    public Map<String, Object> stress(
            @RequestParam(defaultValue = "20") int threads,
            @RequestParam(defaultValue = "500") long holdMs) {
        return stress.stress(threads, holdMs);
    }

    /**
     * No DB, no allocation — just the math. cores=8, spindles=2 mirrors
     * Wooldridge's original Oracle paper example. workloadFactor=2 is a
     * common adjustment for I/O-heavy workloads.
     */
    @GetMapping("/sizing-math")
    public Map<String, Object> sizingMath(
            @RequestParam(defaultValue = "8")  int cores,
            @RequestParam(defaultValue = "2")  int spindles,
            @RequestParam(defaultValue = "2.0") double workloadFactor) {
        return stress.sizingMath(cores, spindles, workloadFactor);
    }

    /**
     * Takes ~12 seconds because we sample the MXBean every 2s for 6 samples.
     * The result is mostly useful AFTER you've fired /pool/stress at the
     * same instance — you'll see total drift down toward minimum-idle as
     * idle-timeout retires the extras.
     */
    @GetMapping("/lifetime-rotation")
    public Map<String, Object> lifetimeRotation() throws InterruptedException {
        return stress.lifetimeRotation();
    }
}
