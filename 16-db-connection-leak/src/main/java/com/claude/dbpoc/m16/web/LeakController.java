package com.claude.dbpoc.m16.web;

import com.claude.dbpoc.m16.service.LeakService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Three endpoints, three steps:
 *
 *   POST /leak/leak?count=5    — leak `count` connections deliberately.
 *   GET  /leak/stats           — show current pool state.
 *   POST /leak/recover         — close every leaked connection.
 *
 * The demo flow:
 *   1. GET /leak/stats           → baseline.
 *   2. POST /leak/leak?count=5   → watch active climb by 5.
 *   3. Wait > leakDetectionThreshold (set to 4s in yml) → app logs scream.
 *   4. POST /leak/leak?count=20  → eventually exhausts the pool, callers
 *                                  time out at connection-timeout.
 *   5. POST /leak/recover        → pool returns to baseline.
 */
@RestController
@RequestMapping("/leak")
public class LeakController {

    private final LeakService leak;

    public LeakController(LeakService leak) {
        this.leak = leak;
    }

    @PostMapping("/leak")
    public Map<String, Object> leak(@RequestParam(defaultValue = "5") int count) {
        return leak.leak(count);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return leak.stats();
    }

    @PostMapping("/recover")
    public Map<String, Object> recover() {
        return leak.recover();
    }
}
