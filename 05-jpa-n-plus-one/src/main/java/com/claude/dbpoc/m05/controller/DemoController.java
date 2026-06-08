package com.claude.dbpoc.m05.controller;

import com.claude.dbpoc.m05.dto.DemoResult;
import com.claude.dbpoc.m05.service.DemoService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One endpoint per variant. All of them return the same JSON shape:
 *   {variant, ordersFetched, itemsTotal, sqlStatements, elapsedMs, verdict}
 *
 * That uniform shape is what makes the side-by-side comparison possible.
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    @Autowired
    private DemoService demo;

    /** GET /demo/naive — expect 1 + N statements (the disease). */
    @GetMapping("/naive")
    public DemoResult naive() {
        return demo.naive();
    }

    /** GET /demo/join-fetch — expect 1 statement. */
    @GetMapping("/join-fetch")
    public DemoResult joinFetch() {
        return demo.joinFetch();
    }

    /** GET /demo/entity-graph — expect 1 statement. */
    @GetMapping("/entity-graph")
    public DemoResult entityGraph() {
        return demo.entityGraph();
    }

    /**
     * GET /demo/batch-size?size=20
     * Expect 1 + ceil(N / size). size=20 with 100 parents → 1 + 5 = 6 statements.
     */
    @GetMapping("/batch-size")
    public DemoResult batchSize(@RequestParam(defaultValue = "20") int size) {
        return demo.batchSize(size);
    }

    /** GET /demo/dto-projection — expect 1 statement. */
    @GetMapping("/dto-projection")
    public DemoResult dtoProjection() {
        return demo.dtoProjection();
    }

    /**
     * GET /demo/second-level-cache
     *
     * Runs the same fetch twice and returns both passes:
     *   pass 1 (cold)  → ~1 + N statements
     *   pass 2 (warm)  → drops sharply (often just 1, the parent SELECT)
     *                    because Item is annotated @Cache.
     *
     * If pass 2 still equals pass 1, check ehcache is on the classpath and
     * hibernate.cache.use_second_level_cache=true.
     */
    @GetMapping("/second-level-cache")
    public Map<String, Object> secondLevelCache() {
        var pass1 = demo.secondLevelCachePassOne();
        var pass2 = demo.secondLevelCachePassTwo();
        return Map.of(
            "variant", "second-level-cache",
            "passes", List.of(pass1, pass2),
            "verdict", "L2 cache drops repeat-fetch SQL count to near zero. " +
                       "Right tool for reference data; wrong tool for hot-write entities.");
    }
}
