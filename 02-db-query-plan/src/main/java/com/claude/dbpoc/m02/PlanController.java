package com.claude.dbpoc.m02;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The heart of module 02.
 *
 * Each endpoint runs ONE query and returns:
 *   - the SQL
 *   - the raw EXPLAIN ANALYZE text (paste into chat / wiki / ticket)
 *   - a structured parse of the top node
 *   - a human-readable VERDICT explaining WHY the planner picked that shape
 *
 * The verdicts are the teaching part — they tie the cost numbers back to the
 * cardinality story so readers learn to predict plan choice, not just observe it.
 */
@RestController
@RequestMapping("/plans")
public class PlanController {

    private final ExplainPg explain;

    public PlanController(ExplainPg explain) {
        this.explain = explain;
    }

    // ----- 1. Seq Scan -----------------------------------------------------
    //
    // Predicate: status = 'PAID'  (~70% of the table)
    //
    // Why Seq Scan? Reading 70% of a heap via an index means random I/O for
    // each tuple PLUS the index pages. Postgres compares the cost of:
    //    seq cost   = pages * seq_page_cost
    //    index cost = (matching_rows * random_page_cost) + index_pages
    // and the index loses by miles once selectivity drops below ~5-15%.
    // ------------------------------------------------------------------------
    @GetMapping("/seq-scan")
    public Map<String, Object> seqScan() {
        String sql = "SELECT * FROM orders WHERE status = 'PAID'";
        Map<String, Object> plan = explain.explain(sql);
        return decorate(plan,
                "Seq Scan",
                "Predicate matches ~70% of rows. Reading that many tuples via " +
                "an index would cost more in random I/O than just walking the " +
                "heap sequentially. The planner correctly chooses Seq Scan. " +
                "Adding an index on `status` would NOT fix this — selectivity " +
                "is the problem, not the lack of an index.");
    }

    // ----- 2. Index Scan ---------------------------------------------------
    //
    // Predicate: customer_id = ?  (single distinct value → ~0.15% of rows)
    //
    // Why Index Scan? Selectivity is tiny, customer_id is indexed. Cost of
    // a few hundred random heap reads << cost of scanning 1M rows.
    // ------------------------------------------------------------------------
    @GetMapping("/index-scan")
    public Map<String, Object> indexScan(@RequestParam(defaultValue = "42") long customerId) {
        String sql = "SELECT * FROM orders WHERE customer_id = ?";
        Map<String, Object> plan = explain.explain(sql, customerId);
        return decorate(plan,
                "Index Scan",
                "Single customer_id matches ~0.15% of the table. The B-tree " +
                "lookup is O(log n) and the resulting heap fetches (one per " +
                "matching row) are far cheaper than reading 1M tuples. " +
                "Watch the 'Buffers: shared hit' number — that's how many " +
                "8KB pages the executor actually touched.");
    }

    // ----- 3. Bitmap Heap Scan --------------------------------------------
    //
    // Predicate: customer_id IN (id1, id2, ..., idN)  with N distinct values
    //
    // Why Bitmap? When the index will return many matching rowids that are
    // scattered across the heap, Postgres builds a bitmap of pages first
    // (sorted), then visits each page ONCE in physical order — turning
    // random I/O into sequential I/O. The crossover from Index Scan to
    // Bitmap Heap Scan happens around 5-20 keys depending on stats.
    // ------------------------------------------------------------------------
    @GetMapping("/bitmap-scan")
    public Map<String, Object> bitmapScan(@RequestParam(defaultValue = "1,2,3,4,5,6,7,8,9,10") String ids) {
        List<Long> idList = Arrays.stream(ids.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::parseLong).collect(Collectors.toList());

        // Build "?,?,?,..." for the IN clause. We pass actual params so we
        // get real selectivity estimates rather than the default fallback.
        String inClause = idList.stream().map(x -> "?").collect(Collectors.joining(","));
        String sql = "SELECT * FROM orders WHERE customer_id IN (" + inClause + ")";

        Map<String, Object> plan = explain.explain(sql, idList.toArray());
        return decorate(plan,
                "Bitmap Heap Scan",
                "IN-list with " + idList.size() + " customer ids → too many " +
                "matching rows for plain Index Scan (random I/O would dominate), " +
                "too few for Seq Scan. Postgres builds a bitmap of page IDs from " +
                "the index, then reads each heap page once in disk order. " +
                "Shrink the list (e.g. ?ids=1,2) to force Index Scan; grow it " +
                "(?ids=1..200) to force Seq Scan.");
    }

    // ----- 4. Index-Only Scan ---------------------------------------------
    //
    // Predicate: WHERE customer_id = ?   SELECT only (id, customer_id)
    //
    // Why Index Only? Both columns we need are IN the index (id is the heap
    // ctid plus the indexed column). If the visibility map says the page is
    // all-visible, Postgres skips the heap entirely. The headline metric is
    // "Heap Fetches: 0" — that means zero random reads beyond the index.
    //
    // GOTCHA: a fresh table after INSERT has Heap Fetches > 0 until VACUUM
    // updates the visibility map. We run ANALYZE in SeedController but
    // a manual `VACUUM orders` after seeding makes this endpoint pop.
    // ------------------------------------------------------------------------
    @GetMapping("/index-only-scan")
    public Map<String, Object> indexOnlyScan(@RequestParam(defaultValue = "42") long customerId) {
        String sql = "SELECT id, customer_id FROM orders WHERE customer_id = ?";
        Map<String, Object> plan = explain.explain(sql, customerId);
        return decorate(plan,
                "Index Only Scan",
                "All selected columns (id, customer_id) live in the index. " +
                "Postgres CAN serve this without touching the heap, but only " +
                "if the visibility map says the pages are all-visible. Look " +
                "for 'Heap Fetches: 0' — if it's > 0, run `VACUUM orders` " +
                "and try again.");
    }

    private Map<String, Object> decorate(Map<String, Object> plan, String expected, String verdict) {
        Map<String, Object> out = new LinkedHashMap<>(plan);
        out.put("expectedNode", expected);
        out.put("verdict", verdict);
        return out;
    }
}
