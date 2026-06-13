package com.claude.dbpoc.m14.web;

import com.claude.dbpoc.m14.repo.WidgetRepository;
import com.claude.dbpoc.m14.service.LongTxService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The four demos, each addressing one symptom of a long-running tx:
 *
 *   GET /longtx/lock-hold?holdMs=5000             — T1 holds FOR UPDATE; T2 waits.
 *   GET /longtx/bloat?updates=200                  — long tx pins xmin, VACUUM can't reap.
 *   GET /longtx/idle-in-transaction?idleMs=3000    — show 'idle in transaction' rows.
 *   GET /longtx/observability?minTxAgeMs=1000      — list tx older than threshold.
 *
 * widgetId defaults to the first widget seeded by POST /seed.
 */
@RestController
@RequestMapping("/longtx")
public class LongTxController {

    private final LongTxService longtx;
    private final WidgetRepository widgetRepo;

    public LongTxController(LongTxService longtx, WidgetRepository widgetRepo) {
        this.longtx = longtx;
        this.widgetRepo = widgetRepo;
    }

    @GetMapping("/lock-hold")
    public Map<String, Object> lockHold(
            @RequestParam(required = false) Long widgetId,
            @RequestParam(defaultValue = "5000") long holdMs) {
        return longtx.lockHold(resolveWidget(widgetId), holdMs);
    }

    @GetMapping("/bloat")
    public Map<String, Object> bloat(
            @RequestParam(required = false) Long widgetId,
            @RequestParam(defaultValue = "200") int updates) {
        return longtx.bloat(resolveWidget(widgetId), updates);
    }

    @GetMapping("/idle-in-transaction")
    public Map<String, Object> idleInTransaction(
            @RequestParam(defaultValue = "3000") long idleMs) {
        return longtx.idleInTransaction(idleMs);
    }

    @GetMapping("/observability")
    public Map<String, Object> observability(
            @RequestParam(defaultValue = "1000") long minTxAgeMs) {
        return longtx.observability(minTxAgeMs);
    }

    private long resolveWidget(Long explicit) {
        if (explicit != null) return explicit;
        return widgetRepo.findAll().stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("call POST /seed first"))
            .getId();
    }
}
