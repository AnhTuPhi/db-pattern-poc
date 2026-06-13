package com.claude.dbpoc.m22.web;

import com.claude.dbpoc.m22.service.PartitionService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoints (port 8222):
 *
 *   --- RANGE (time-series) ---
 *   POST /partition/range/seed?rowsPerMonth=100000
 *   GET  /partition/range/prune              — query with partition pruning
 *   GET  /partition/range/no-prune           — query that DEFEATS pruning
 *   POST /partition/range/slide?dropOlderThanMonths=6&attachNext=true
 *
 *   --- LIST (categorical) ---
 *   POST /partition/list/seed?rowsPerRegion=50000
 *   GET  /partition/list/prune?region=us-east
 *
 *   --- HASH (high-cardinality) ---
 *   POST /partition/hash/seed?rows=400000
 *   GET  /partition/hash/prune?userId=42
 */
@RestController
@RequestMapping("/partition")
public class PartitionController {

    private final PartitionService svc;

    public PartitionController(PartitionService svc) { this.svc = svc; }

    // ---- RANGE -----------------------------------------------------------

    @PostMapping("/range/seed")
    public Map<String, Object> seedRange(@RequestParam(defaultValue = "100000") int rowsPerMonth) {
        return svc.seedRange(rowsPerMonth);
    }

    @GetMapping("/range/prune")
    public Map<String, Object> rangePrune() { return svc.rangePrune(); }

    @GetMapping("/range/no-prune")
    public Map<String, Object> rangeNoPrune() { return svc.rangeNoPrune(); }

    @PostMapping("/range/slide")
    public Map<String, Object> slide(@RequestParam(defaultValue = "6") int dropOlderThanMonths,
                                     @RequestParam(defaultValue = "true") boolean attachNext) {
        return svc.slideWindow(dropOlderThanMonths, attachNext);
    }

    // ---- LIST ------------------------------------------------------------

    @PostMapping("/list/seed")
    public Map<String, Object> seedList(@RequestParam(defaultValue = "50000") int rowsPerRegion) {
        return svc.seedList(rowsPerRegion);
    }

    @GetMapping("/list/prune")
    public Map<String, Object> listPrune(@RequestParam(defaultValue = "us-east") String region) {
        return svc.listPrune(region);
    }

    // ---- HASH ------------------------------------------------------------

    @PostMapping("/hash/seed")
    public Map<String, Object> seedHash(@RequestParam(defaultValue = "400000") int rows) {
        return svc.seedHash(rows);
    }

    @GetMapping("/hash/prune")
    public Map<String, Object> hashPrune(@RequestParam(defaultValue = "42") long userId) {
        return svc.hashPrune(userId);
    }
}
