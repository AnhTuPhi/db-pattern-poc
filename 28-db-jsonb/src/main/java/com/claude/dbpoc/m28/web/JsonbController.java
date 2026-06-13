package com.claude.dbpoc.m28.web;

import com.claude.dbpoc.m28.service.JsonbService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoints (port 8228):
 *
 *   POST /jsonb/seed?n=10000           — fills both tables with N rows
 *
 *   --- indexes ---
 *   POST /jsonb/gin                    — build gin(data) + gin(data jsonb_path_ops)
 *   POST /jsonb/functional             — build expression index on (data->>'sku')
 *   POST /jsonb/drop-indexes           — drop the three jsonb indexes (for re-bench)
 *
 *   --- queries (return EXPLAIN ANALYZE) ---
 *   GET  /jsonb/by-brand/{brand}       — JSONB containment: data @> {"brand":"..."}
 *   GET  /jsonb/by-brand-norm/{brand}  — same query on the normalized table
 *   GET  /jsonb/by-sku/{sku}           — path query: data->>'sku' = ?
 *
 *   --- benchmark / anti-pattern ---
 *   GET  /jsonb/bench?brand=acme&iters=1000  — normalized vs doc timing
 *   POST /jsonb/anti-pattern           — show what constraints JSONB lets through
 */
@RestController
@RequestMapping("/jsonb")
public class JsonbController {

    private final JsonbService svc;

    public JsonbController(JsonbService svc) { this.svc = svc; }

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "10000") int n) {
        return svc.seed(n);
    }

    @PostMapping("/gin")
    public Map<String, Object> gin() { return svc.createGinIndexes(); }

    @PostMapping("/functional")
    public Map<String, Object> functional() { return svc.createFunctionalIndex(); }

    @PostMapping("/drop-indexes")
    public Map<String, Object> dropIndexes() { return svc.dropAllJsonbIndexes(); }

    @GetMapping("/by-brand/{brand}")
    public Map<String, Object> byBrand(@PathVariable String brand) {
        return svc.queryByBrand(brand);
    }

    @GetMapping("/by-brand-norm/{brand}")
    public Map<String, Object> byBrandNorm(@PathVariable String brand) {
        return svc.queryByBrandNormalized(brand);
    }

    @GetMapping("/by-sku/{sku}")
    public Map<String, Object> bySku(@PathVariable String sku) {
        return svc.queryBySku(sku);
    }

    @GetMapping("/bench")
    public Map<String, Object> bench(
            @RequestParam(defaultValue = "acme") String brand,
            @RequestParam(defaultValue = "1000") int iters) {
        return svc.benchmark(brand, iters);
    }

    @PostMapping("/anti-pattern")
    public Map<String, Object> antiPattern() { return svc.antiPatternDemo(); }
}
