package com.claude.dbpoc.m26.web;

import com.claude.dbpoc.m26.service.MaterializedViewService;
import com.claude.dbpoc.m26.service.ScheduledRefresher;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoints (port 8226):
 *
 *   POST /mv/seed?orders=100000             — build base tables
 *   POST /mv/create                         — create the MV + unique index
 *   GET  /mv/time-expensive                 — time the raw query
 *   GET  /mv/time-mv                        — time the MV query
 *   POST /mv/refresh-blocking               — REFRESH (locks)
 *   POST /mv/refresh-concurrent             — REFRESH CONCURRENTLY (no-lock)
 *   POST /mv/mutate?amount=42               — insert a sale; show staleness
 *   POST /mv/computed-column                — install trigger that keeps orders.total fresh
 *   GET  /mv/status                         — last refresh metadata
 *   GET  /mv/describe                       — MV size + indexes
 */
@RestController
@RequestMapping("/mv")
public class MaterializedViewController {

    private final MaterializedViewService svc;
    private final ScheduledRefresher refresher;

    public MaterializedViewController(MaterializedViewService svc, ScheduledRefresher refresher) {
        this.svc = svc;
        this.refresher = refresher;
    }

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "100000") int orders) {
        return svc.seed(orders);
    }

    @PostMapping("/create")
    public Map<String, Object> createMv() { return svc.createMv(); }

    @GetMapping("/time-expensive")
    public Map<String, Object> timeExpensive() { return svc.timeExpensive(); }

    @GetMapping("/time-mv")
    public Map<String, Object> timeMv() { return svc.timeMv(); }

    @PostMapping("/refresh-blocking")
    public Map<String, Object> refreshBlocking() { return svc.refreshBlocking(); }

    @PostMapping("/refresh-concurrent")
    public Map<String, Object> refreshConcurrent() { return svc.refreshConcurrent(); }

    @PostMapping("/mutate")
    public Map<String, Object> mutate(@RequestParam(defaultValue = "999.99") BigDecimal amount) {
        return svc.mutateAndCompare(amount);
    }

    @PostMapping("/computed-column")
    public Map<String, Object> computedColumn() { return svc.computedColumnDemo(); }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scheduledRefreshLastSuccessEpochMs", refresher.getLastSuccessMs());
        out.put("scheduledRefreshLastDurationMs", refresher.getLastDurationMs());
        out.put("scheduledRefreshLastError", refresher.getLastError());
        out.put("staleness",
            refresher.getLastSuccessMs() == 0
                ? "MV never refreshed by the scheduler yet"
                : (System.currentTimeMillis() - refresher.getLastSuccessMs()) + "ms ago");
        return out;
    }

    @GetMapping("/describe")
    public Map<String, Object> describe() { return svc.describe(); }
}
