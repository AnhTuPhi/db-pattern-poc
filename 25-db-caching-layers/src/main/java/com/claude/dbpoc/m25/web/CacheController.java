package com.claude.dbpoc.m25.web;

import com.claude.dbpoc.m25.service.CachingDemoService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoints (port 8225):
 *
 *   POST /cache/seed?n=100
 *   GET  /cache/l1-same/{id}            — two findById in one tx → 1 query
 *   GET  /cache/l1-cross/{id}           — two findById in two tx  → 2 queries
 *   GET  /cache/caffeine/{id}           — in-process cache demo
 *   GET  /cache/redis/{id}              — distributed cache demo (needs Redis)
 *   POST /cache/stampede/{id}?concurrency=50&singleFlight=false
 *   POST /cache/stampede/{id}?concurrency=50&singleFlight=true
 */
@RestController
@RequestMapping("/cache")
public class CacheController {

    private final CachingDemoService svc;

    public CacheController(CachingDemoService svc) { this.svc = svc; }

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "100") int n) {
        return svc.seed(n);
    }

    @GetMapping("/l1-same/{id}")
    public Map<String, Object> l1Same(@PathVariable Long id) { return svc.l1SameSession(id); }

    @GetMapping("/l1-cross/{id}")
    public Map<String, Object> l1Cross(@PathVariable Long id) { return svc.l1CrossSession(id); }

    @GetMapping("/caffeine/{id}")
    public Map<String, Object> caffeine(@PathVariable Long id) { return svc.caffeine(id); }

    @GetMapping("/redis/{id}")
    public Map<String, Object> redis(@PathVariable Long id) { return svc.redis(id); }

    @PostMapping("/stampede/{id}")
    public Map<String, Object> stampede(@PathVariable Long id,
                                        @RequestParam(defaultValue = "50") int concurrency,
                                        @RequestParam(defaultValue = "false") boolean singleFlight) throws Exception {
        return svc.stampede(id, concurrency, singleFlight);
    }
}
