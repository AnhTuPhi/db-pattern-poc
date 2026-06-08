package com.claude.dbpoc.m05.controller;

import com.claude.dbpoc.m05.dto.DemoResult;
import com.claude.dbpoc.m05.service.DemoService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The headline endpoint. Runs every variant once, builds a side-by-side
 * JSON table sorted by SQL count. This is what makes the POC click for
 * a reviewer: 101 vs 1 vs 1 vs 6 vs 1 in one screenful.
 */
@RestController
@RequestMapping("/compare")
public class CompareController {

    @Autowired
    private DemoService demo;

    /**
     * GET /compare/all?orders=100&batchSize=20
     *
     * Each variant runs in its own transaction (the DemoService methods are
     * @Transactional) so the persistence context, statistics, and SqlCounter
     * are clean per row.
     *
     * The L2 cache row reports pass 2 (the warm one) — that's the number
     * that demonstrates the cache is doing real work. Pass 1 by definition
     * matches "naive".
     */
    @GetMapping("/all")
    public Map<String, Object> compareAll(@RequestParam(defaultValue = "100") int orders,
                                          @RequestParam(defaultValue = "20") int batchSize) {
        List<DemoResult> table = new ArrayList<>();

        table.add(demo.naive());
        table.add(demo.joinFetch());
        table.add(demo.entityGraph());
        table.add(demo.batchSize(batchSize));
        table.add(demo.dtoProjection());

        // L2 cache: warm it then measure the hot path. The cold pass equals
        // naive so we don't duplicate that row.
        demo.secondLevelCachePassOne();
        var warm = demo.secondLevelCachePassTwo();
        table.add(new DemoResult(
            "second-level-cache (warm)",
            warm.ordersFetched(), warm.itemsTotal(),
            warm.sqlStatements(), warm.elapsedMs(),
            "After L2 warm-up: items served from cache region. Cold pass = naive."));

        return Map.of(
            "ordersRequested", orders,
            "headline", "compare sqlStatements column — that's the whole point",
            "results", table);
    }
}
