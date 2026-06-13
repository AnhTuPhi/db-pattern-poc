package com.claude.dbpoc.m14.web;

import com.claude.dbpoc.m14.repo.WidgetRepository;
import com.claude.dbpoc.m14.service.LongTxService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seed N widgets. The long-tx demos all target widget#1, but the bloat
 * counters in pg_stat_user_tables only show signal if the table is
 * non-trivially sized — default count=64 is enough to make n_dead_tup
 * move visibly.
 *
 *   POST /seed?count=64
 */
@RestController
@RequestMapping("/seed")
public class SeedController {

    private final LongTxService longtx;
    private final WidgetRepository widgetRepo;

    public SeedController(LongTxService longtx, WidgetRepository widgetRepo) {
        this.longtx = longtx;
        this.widgetRepo = widgetRepo;
    }

    @PostMapping
    public Map<String, Object> seed(@RequestParam(defaultValue = "64") int count) {
        longtx.seed(count);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seeded", count);
        out.put("firstWidgetId", widgetRepo.findAll().stream()
            .findFirst().map(w -> w.getId()).orElse(null));
        out.put("note", "Use the returned firstWidgetId as the ?widgetId= param on /longtx/*.");
        return out;
    }
}
