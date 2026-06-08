package com.claude.dbpoc.m08;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The headline endpoint. GET /bench?n=10000 runs every variant in turn and
 * returns a sorted table. The same JSON includes the active batch settings
 * so the reader can see WHY a number is what it is.
 *
 * Individual variants are also exposed under /bench/{variant} if you only
 * want to look at one.
 */
@RestController
@RequestMapping("/bench")
public class BenchController {

    private final BenchService bench;
    private final BatchSettings batchSettings;

    public BenchController(BenchService bench, BatchSettings batchSettings) {
        this.bench = bench;
        this.batchSettings = batchSettings;
    }

    @GetMapping
    public Map<String, Object> runAll(@RequestParam(defaultValue = "10000") int n) {
        List<Map<String, Object>> rows = new ArrayList<>();
        // Order is meaningful — start with the floor (jdbc), then the anti-pattern
        // (identity), then climb to the recommended shape.
        rows.add(bench.jdbcBaseline(n));
        rows.add(bench.identityVariant(n));
        rows.add(bench.sequenceUnbatched(n));
        rows.add(bench.sequenceVariant(n));
        rows.add(bench.sequence100Variant(n));
        rows.add(bench.assignedVariant(n));
        rows.add(bench.orderedInsertsVariant(n));

        // Sort by elapsed for the headline ranking; the verdict explains why.
        rows.sort((a, b) -> Double.compare((double) a.get("elapsedMs"), (double) b.get("elapsedMs")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rowsPerVariant", n);
        out.put("settings", describeSettings());
        out.put("results", rows);
        out.put("howToRead",
            "Sorted by elapsedMs ascending. Compare 'identity' vs 'sequence-batch50' — same " +
            "row count, often 50-100x latency delta. The whole module is the explanation for " +
            "that gap.");
        return out;
    }

    @GetMapping("/jdbc")              public Map<String, Object> jdbc(@RequestParam(defaultValue = "10000") int n) { return bench.jdbcBaseline(n); }
    @GetMapping("/identity")          public Map<String, Object> identity(@RequestParam(defaultValue = "10000") int n) { return bench.identityVariant(n); }
    @GetMapping("/sequence")          public Map<String, Object> sequence(@RequestParam(defaultValue = "10000") int n) { return bench.sequenceVariant(n); }
    @GetMapping("/sequence-100")      public Map<String, Object> sequence100(@RequestParam(defaultValue = "10000") int n) { return bench.sequence100Variant(n); }
    @GetMapping("/assigned")          public Map<String, Object> assigned(@RequestParam(defaultValue = "10000") int n) { return bench.assignedVariant(n); }
    @GetMapping("/sequence-unbatched") public Map<String, Object> sequenceUnbatched(@RequestParam(defaultValue = "10000") int n) { return bench.sequenceUnbatched(n); }
    @GetMapping("/ordered-inserts")    public Map<String, Object> orderedInserts(@RequestParam(defaultValue = "5000") int n) { return bench.orderedInsertsVariant(n); }

    private Map<String, Object> describeSettings() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("profile", batchSettings.getActiveProfile());
        s.put("hibernate.jdbc.batch_size", batchSettings.getBatchSize());
        s.put("hibernate.order_inserts", batchSettings.isOrderInserts());
        s.put("hibernate.order_updates", batchSettings.isOrderUpdates());
        s.put("hibernate.jdbc.batch_versioned_data", batchSettings.isBatchVersionedData());
        s.put("pg.reWriteBatchedInserts", batchSettings.isPgReWriteBatchedInserts());
        return s;
    }
}
