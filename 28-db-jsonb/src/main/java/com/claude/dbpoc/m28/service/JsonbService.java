package com.claude.dbpoc.m28.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The "JSONB vs normalized columns" lab.
 *
 * The two tables ({@code product_normalized}, {@code product_doc}) hold
 * the same domain. Every demo here is about answering the same question
 * two ways and comparing what you got.
 *
 * The four things this service is trying to teach:
 *
 *   1. **JSONB is fast and flexible**, but only when you index it.
 *      A GIN index makes containment ({@code @>}) queries plan-friendly.
 *   2. **GIN flavor matters.** Default {@code gin(data)} indexes more
 *      operators (existence, key search) but is larger and slower to
 *      build. {@code jsonb_path_ops} indexes only containment, but the
 *      index is ~half the size and faster to query.
 *   3. **Functional indexes** turn a specific JSON path into a
 *      column-shaped lookup — when you know you'll always filter on
 *      {@code data->>'sku'}, indexing the expression is cheaper than
 *      a full GIN.
 *   4. **Schemaless is a cost, not a feature.** Constraints, types,
 *      and statistics are all weaker on JSONB. The CHECK constraints
 *      on this table are a token gesture — real shape validation
 *      either uses {@code pg_jsonschema} or lives in the application,
 *      and either way you've moved the problem out of the database.
 */
@Service
public class JsonbService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    private static final String[] BRANDS = {"acme", "globex", "initech", "umbrella", "vandelay"};
    private static final String[] CATEGORIES = {"books", "electronics", "clothing", "food", "tools"};

    public JsonbService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ─── seed ────────────────────────────────────────────────────────────

    /**
     * Fills both tables with the same N rows so later benchmarks are
     * comparing apples to apples.
     */
    @Transactional
    public Map<String, Object> seed(int n) {
        jdbc.update("truncate product_normalized restart identity");
        jdbc.update("truncate product_doc        restart identity");

        List<Object[]> normRows = new ArrayList<>(n);
        List<Object[]> docRows = new ArrayList<>(n);

        for (int i = 1; i <= n; i++) {
            String sku = "SKU-" + String.format("%07d", i);
            String name = "Product " + i;
            String brand = BRANDS[ThreadLocalRandom.current().nextInt(BRANDS.length)];
            BigDecimal price = BigDecimal.valueOf(
                ThreadLocalRandom.current().nextInt(500, 50_000) / 100.0);
            int stock = ThreadLocalRandom.current().nextInt(0, 1000);
            String category = CATEGORIES[ThreadLocalRandom.current().nextInt(CATEGORIES.length)];

            normRows.add(new Object[]{sku, name, brand, price, stock, category});

            try {
                String json = mapper.writeValueAsString(Map.of(
                    "sku", sku,
                    "name", name,
                    "brand", brand,
                    "price", price,
                    "stock", stock,
                    "category", category,
                    // extra fields that ONLY exist in the doc form —
                    // some products have warranty info, some don't.
                    // Adding this column to product_normalized would
                    // require a migration; in JSONB it's free.
                    "warranty", Map.of(
                        "months", 12 + (i % 4) * 12,
                        "type", i % 3 == 0 ? "limited" : "standard")));
                docRows.add(new Object[]{json});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        jdbc.batchUpdate(
            "insert into product_normalized(sku,name,brand,price,stock,category) " +
            "values (?,?,?,?,?,?)", normRows);
        jdbc.batchUpdate(
            "insert into product_doc(data) values (?::jsonb)", docRows);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inserted", n);
        out.put("normalizedSize", sizeOf("product_normalized"));
        out.put("docSize", sizeOf("product_doc"));
        out.put("note",
            "Notice the doc table is bigger on disk. JSONB stores keys per row, " +
            "the relational form stores keys once in the catalog. For wide rows " +
            "with many repeated keys, the gap can be 2-4x.");
        return out;
    }

    // ─── GIN index management ────────────────────────────────────────────

    /**
     * Builds the two flavors of GIN on the JSONB column. Run this
     * after {@link #seed(int)} to make containment queries fast.
     *
     * <p>Why both flavors?
     * <ul>
     *   <li>{@code gin(data)} — supports {@code @>}, {@code ?}, {@code ?|},
     *       {@code ?&}, plus key/value lookups. Larger, slower build.
     *   <li>{@code gin(data jsonb_path_ops)} — supports ONLY {@code @>}
     *       but the index is ~half the size and queries are typically
     *       faster. The right default when all you need is containment.
     * </ul>
     */
    public Map<String, Object> createGinIndexes() {
        long t0 = System.nanoTime();
        jdbc.execute("create index if not exists pd_data_gin on product_doc using gin (data)");
        long t1 = System.nanoTime();
        jdbc.execute(
            "create index if not exists pd_data_path_gin " +
            "on product_doc using gin (data jsonb_path_ops)");
        long t2 = System.nanoTime();

        return Map.of(
            "gin_data_ms",          (t1 - t0) / 1_000_000.0,
            "gin_path_ops_ms",      (t2 - t1) / 1_000_000.0,
            "gin_data_size",        indexSize("pd_data_gin"),
            "gin_path_ops_size",    indexSize("pd_data_path_gin"),
            "note",
                "jsonb_path_ops typically half the size of the default gin(data). " +
                "If your only operator is @>, prefer jsonb_path_ops.");
    }

    /**
     * Functional index on {@code data->>'sku'} — when you KNOW the
     * lookup is always by sku, this is cheaper and B-tree-shaped, so
     * range queries and ORDER BY work too. GIN doesn't help with
     * range or sort.
     */
    public Map<String, Object> createFunctionalIndex() {
        long t0 = System.nanoTime();
        jdbc.execute(
            "create index if not exists pd_sku_fn on product_doc ((data->>'sku'))");
        long t1 = System.nanoTime();
        return Map.of(
            "build_ms", (t1 - t0) / 1_000_000.0,
            "size",     indexSize("pd_sku_fn"),
            "note",
                "Functional / expression index. The expression must match " +
                "the WHERE clause exactly — `data->>'sku'` indexed means " +
                "`where data->>'sku' = ?` plans on the index. " +
                "`where data->'sku' = '\"X\"'` does NOT.");
    }

    public Map<String, Object> dropAllJsonbIndexes() {
        jdbc.execute("drop index if exists pd_data_gin");
        jdbc.execute("drop index if exists pd_data_path_gin");
        jdbc.execute("drop index if exists pd_sku_fn");
        return Map.of("dropped", List.of("pd_data_gin", "pd_data_path_gin", "pd_sku_fn"));
    }

    // ─── queries ─────────────────────────────────────────────────────────

    /**
     * Containment query: "find every doc whose data CONTAINS
     * {brand: X}". GIN-indexed and idiomatic for JSONB.
     */
    public Map<String, Object> queryByBrand(String brand) {
        try {
            String filter = mapper.writeValueAsString(Map.of("brand", brand));

            long t0 = System.nanoTime();
            Integer count = jdbc.queryForObject(
                "select count(*) from product_doc where data @> ?::jsonb",
                Integer.class, filter);
            long t1 = System.nanoTime();

            List<String> plan = jdbc.queryForList(
                "explain (analyze, buffers, format text) " +
                "select * from product_doc where data @> ?::jsonb",
                String.class, filter);

            return Map.of(
                "brand", brand,
                "filter", filter,
                "matchCount", count,
                "elapsedMs", (t1 - t0) / 1_000_000.0,
                "plan", plan,
                "note",
                    "Look for `Bitmap Index Scan on pd_data_gin` (or pd_data_path_gin). " +
                    "If you see Seq Scan, the GIN index isn't being used — " +
                    "either it doesn't exist yet (POST /jsonb/gin) or the planner " +
                    "thinks a Seq Scan is cheaper because the table is tiny.");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Same logical question against the normalized table. The brand
     * column has its own B-tree, so this is an Index Scan, not a Bitmap.
     */
    public Map<String, Object> queryByBrandNormalized(String brand) {
        long t0 = System.nanoTime();
        Integer count = jdbc.queryForObject(
            "select count(*) from product_normalized where brand = ?",
            Integer.class, brand);
        long t1 = System.nanoTime();
        List<String> plan = jdbc.queryForList(
            "explain (analyze, buffers, format text) " +
            "select * from product_normalized where brand = ?",
            String.class, brand);
        return Map.of(
            "brand", brand,
            "matchCount", count,
            "elapsedMs", (t1 - t0) / 1_000_000.0,
            "plan", plan,
            "note",
                "B-tree Index Scan. The normalized form's index is tighter and " +
                "the WHERE clause is shape-checked at compile time by Hibernate. " +
                "JSONB can't catch a typo in a key name until runtime.");
    }

    /**
     * Path query that uses the functional index (if you've built it).
     */
    public Map<String, Object> queryBySku(String sku) {
        long t0 = System.nanoTime();
        Integer count = jdbc.queryForObject(
            "select count(*) from product_doc where data->>'sku' = ?",
            Integer.class, sku);
        long t1 = System.nanoTime();
        List<String> plan = jdbc.queryForList(
            "explain (analyze, buffers, format text) " +
            "select * from product_doc where data->>'sku' = ?",
            String.class, sku);
        return Map.of(
            "sku", sku,
            "matchCount", count,
            "elapsedMs", (t1 - t0) / 1_000_000.0,
            "plan", plan,
            "note",
                "If pd_sku_fn exists, this is an Index Scan on it. " +
                "Otherwise it's a Seq Scan — the GIN index over `data` " +
                "doesn't help `->>` extraction queries.");
    }

    // ─── normalized vs doc benchmark ─────────────────────────────────────

    /**
     * Apples-to-apples timing on the same logical query. Run after
     * {@link #seed(int)} and {@link #createGinIndexes()} for a fair
     * comparison.
     */
    public Map<String, Object> benchmark(String brand, int iters) {
        // Warm up — JIT + caches matter.
        for (int i = 0; i < 5; i++) {
            jdbc.queryForObject("select count(*) from product_normalized where brand=?",
                Integer.class, brand);
        }
        try {
            String filter = mapper.writeValueAsString(Map.of("brand", brand));
            for (int i = 0; i < 5; i++) {
                jdbc.queryForObject(
                    "select count(*) from product_doc where data @> ?::jsonb",
                    Integer.class, filter);
            }

            long n0 = System.nanoTime();
            for (int i = 0; i < iters; i++) {
                jdbc.queryForObject("select count(*) from product_normalized where brand=?",
                    Integer.class, brand);
            }
            long n1 = System.nanoTime();

            long d0 = System.nanoTime();
            for (int i = 0; i < iters; i++) {
                jdbc.queryForObject(
                    "select count(*) from product_doc where data @> ?::jsonb",
                    Integer.class, filter);
            }
            long d1 = System.nanoTime();

            double normMs = (n1 - n0) / 1_000_000.0;
            double docMs  = (d1 - d0) / 1_000_000.0;
            return Map.of(
                "brand", brand,
                "iterations", iters,
                "normalizedTotalMs", normMs,
                "normalizedPerCallUs", normMs * 1000 / iters,
                "docTotalMs", docMs,
                "docPerCallUs", docMs * 1000 / iters,
                "ratioDocOverNormalized", docMs / normMs,
                "note",
                    "On equal data with an indexed brand on both sides, " +
                    "normalized usually wins by 1.5-3x. JSONB pays for parsing " +
                    "the binary doc and re-checking the WHERE on each match. " +
                    "Without the GIN index, the doc side is 10-100x slower.");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─── the anti-pattern ────────────────────────────────────────────────

    /**
     * Demonstrates what the relational engine catches that JSONB does
     * not. We try a few invalid writes against both tables:
     *
     * <ol>
     *   <li>{@code stock = -1} — normalized: blocked by CHECK,
     *       doc: silently accepted (we don't have a check for stock).</li>
     *   <li>misspelled "skk" instead of "sku" — normalized: column
     *       doesn't exist, compile error;
     *       doc: silently accepted, the row is "valid" JSONB.</li>
     *   <li>price as string "free" instead of a number — normalized:
     *       wrong type, fails; doc: blocked by the one CHECK we wrote
     *       for jsonb_typeof.</li>
     * </ol>
     *
     * The point: in JSONB land, every constraint you want is one you
     * have to remember to add. The normalized form gives them to you.
     */
    public Map<String, Object> antiPatternDemo() {
        Map<String, Object> results = new LinkedHashMap<>();

        // 1. negative stock
        try {
            jdbc.update(
                "insert into product_normalized(sku,name,brand,price,stock,category) " +
                "values ('BAD-001','x','acme',1.00,-1,'books')");
            results.put("normalized_negative_stock", "ACCEPTED (this should never happen)");
        } catch (DataAccessException e) {
            results.put("normalized_negative_stock", "blocked: " + rootMessage(e));
        }
        try {
            jdbc.update("insert into product_doc(data) values (?::jsonb)",
                "{\"sku\":\"BAD-001\",\"price\":1.00,\"stock\":-1}");
            results.put("doc_negative_stock",
                "ACCEPTED — no check on stock in JSON. Bug ships to prod.");
        } catch (DataAccessException e) {
            results.put("doc_negative_stock", "blocked: " + rootMessage(e));
        }

        // 2. misspelled key
        results.put("normalized_misspelled_key",
            "Cannot demo — column 'skk' doesn't exist; INSERT wouldn't compile.");
        try {
            jdbc.update("insert into product_doc(data) values (?::jsonb)",
                "{\"skk\":\"BAD-002\",\"sku\":\"GOOD-002\",\"price\":1.00}");
            results.put("doc_misspelled_key",
                "ACCEPTED — JSON doesn't know which keys you intended. " +
                "Your app code that reads data->>'sku' just got a NULL it didn't expect.");
        } catch (DataAccessException e) {
            results.put("doc_misspelled_key", "blocked: " + rootMessage(e));
        }

        // 3. price as a string
        try {
            jdbc.update(
                "insert into product_normalized(sku,name,brand,price,stock,category) " +
                "values ('BAD-003','x','acme','free',1,'books')");
            results.put("normalized_price_as_string", "ACCEPTED (should not happen)");
        } catch (DataAccessException e) {
            results.put("normalized_price_as_string", "blocked: " + rootMessage(e));
        }
        try {
            jdbc.update("insert into product_doc(data) values (?::jsonb)",
                "{\"sku\":\"BAD-003\",\"price\":\"free\"}");
            results.put("doc_price_as_string",
                "ACCEPTED — and we wrote a CHECK for this case! Did it fire?");
        } catch (DataAccessException e) {
            results.put("doc_price_as_string", "blocked: " + rootMessage(e));
        }

        // clean up the bad-data rows so subsequent demos aren't poisoned
        jdbc.update("delete from product_normalized where sku like 'BAD-%'");
        jdbc.update("delete from product_doc where data->>'sku' like 'BAD-%' " +
            "or data->>'sku' is null");

        results.put("moral",
            "JSONB is great for fields you genuinely don't know in advance " +
            "(extension data, polymorphic payloads, snapshots). It's a " +
            "footgun for fields you DO know in advance (sku, price, stock). " +
            "The relational engine catches mistakes at write time; JSONB " +
            "defers them to whoever reads the row next.");
        return results;
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private String sizeOf(String table) {
        return jdbc.queryForObject(
            "select pg_size_pretty(pg_total_relation_size(?))",
            String.class, "m28_jsonb." + table);
    }

    private String indexSize(String indexName) {
        try {
            return jdbc.queryForObject(
                "select pg_size_pretty(pg_relation_size(?))",
                String.class, "m28_jsonb." + indexName);
        } catch (DataAccessException e) {
            return "missing";
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        if (m == null) return c.getClass().getSimpleName();
        // trim the noisy hint/details to the first newline
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }
}
