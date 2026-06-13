package com.claude.dbpoc.m29.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The hybrid relational+document pattern lab.
 *
 * <p>Each method here is meant to surface ONE of the trade-offs you
 * have to think about when you put both shapes on the same table.
 * The structure is intentional:
 * <ul>
 *   <li>"Spine" queries (find customer by email, order by id) go
 *       through real columns — fast, indexed, FK-protected.</li>
 *   <li>"Leaf" queries (filter customers by a profile tag, find
 *       orders containing a SKU) use JSONB operators with GIN
 *       indexes.</li>
 *   <li>"Reporting" queries (aggregate revenue across the embedded
 *       items array) use {@code jsonb_array_elements} to materialize
 *       the array into relational rows on the fly — the moment a
 *       document store can't help you.</li>
 *   <li>"Anti-pattern" demos show what you lose when you collapse
 *       everything into one big document.</li>
 * </ul>
 */
@Service
public class HybridService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    private static final String[] CHANNELS = {"email", "sms", "push"};
    private static final String[] TAGS = {"vip", "beta", "early-access", "newsletter", "lapsed"};
    private static final String[] PRODUCT_TYPES = {"book", "electronic", "digital"};

    public HybridService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ─── seed ────────────────────────────────────────────────────────────

    /**
     * Seeds tenants × customers × orders, each customer with a varied
     * {@code profile} JSONB and each order with a heterogeneous
     * {@code items} array.
     */
    @Transactional
    public Map<String, Object> seed(int tenants, int customersPerTenant, int ordersPerCustomer) {
        jdbc.update("delete from customer_order");
        jdbc.update("delete from customer");
        jdbc.update("alter sequence customer_id_seq restart with 1");
        jdbc.update("alter sequence customer_order_id_seq restart with 1");

        int customers = 0;
        int orders = 0;
        try {
            for (int t = 1; t <= tenants; t++) {
                List<Object[]> custRows = new ArrayList<>(customersPerTenant);
                for (int c = 1; c <= customersPerTenant; c++) {
                    Map<String, Object> profile = new LinkedHashMap<>();
                    profile.put("preferredChannel", CHANNELS[c % CHANNELS.length]);
                    profile.put("marketingConsent", c % 3 != 0);
                    // sparsely add tags — half of customers have any tags at all
                    if (c % 2 == 0) {
                        List<String> tags = new ArrayList<>();
                        tags.add(TAGS[c % TAGS.length]);
                        if (c % 5 == 0) tags.add("vip");
                        profile.put("tags", tags);
                    }
                    // very sparsely add a third-party integration id
                    if (c % 7 == 0) {
                        profile.put("thirdParty", Map.of("stripe", "cus_" + t + "_" + c));
                    }
                    custRows.add(new Object[]{
                        (long) t,
                        "u" + c + "@tenant" + t + ".test",
                        mapper.writeValueAsString(profile)});
                }
                jdbc.batchUpdate(
                    "insert into customer(tenant_id,email,profile) values (?,?,?::jsonb)",
                    custRows);
                customers += custRows.size();
            }

            // fetch the assigned customer ids so we can place orders against them
            List<Long> customerIds = jdbc.queryForList(
                "select id from customer order by id", Long.class);

            List<Object[]> orderRows = new ArrayList<>();
            for (Long cid : customerIds) {
                for (int o = 0; o < ordersPerCustomer; o++) {
                    List<Map<String, Object>> items = new ArrayList<>();
                    int nItems = 1 + ThreadLocalRandom.current().nextInt(3);
                    BigDecimal total = BigDecimal.ZERO;
                    for (int i = 0; i < nItems; i++) {
                        String type = PRODUCT_TYPES[ThreadLocalRandom.current().nextInt(PRODUCT_TYPES.length)];
                        Map<String, Object> li = new LinkedHashMap<>();
                        li.put("type", type);
                        li.put("sku", type.substring(0, 1).toUpperCase() + "-"
                            + (1000 + ThreadLocalRandom.current().nextInt(50)));
                        li.put("qty", 1 + ThreadLocalRandom.current().nextInt(3));
                        BigDecimal price = BigDecimal.valueOf(
                            500 + ThreadLocalRandom.current().nextInt(9500)).movePointLeft(2);
                        li.put("price", price);
                        switch (type) {
                            case "book" -> li.put("isbn", "978-" + (1_000_000 + ThreadLocalRandom.current().nextInt(8_999_999)));
                            case "electronic" -> {
                                li.put("serial", "SN-" + ThreadLocalRandom.current().nextInt(1_000_000));
                                li.put("warrantyMonths", 12 + 12 * ThreadLocalRandom.current().nextInt(3));
                            }
                            case "digital" -> {
                                li.put("license", "LIC-" + ThreadLocalRandom.current().nextInt(100_000));
                                li.put("expires", "2030-01-01");
                            }
                        }
                        items.add(li);
                        total = total.add(price.multiply(BigDecimal.valueOf((int) li.get("qty"))));
                    }
                    String status = (o % 10 == 0) ? "CANCELLED" : "PLACED";
                    orderRows.add(new Object[]{
                        cid,
                        status,
                        total,
                        mapper.writeValueAsString(items)});
                }
            }
            jdbc.batchUpdate(
                "insert into customer_order(customer_id,status,total,items) " +
                "values (?,?,?,?::jsonb)",
                orderRows);
            orders = orderRows.size();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenants", tenants);
        out.put("customers", customers);
        out.put("orders", orders);
        out.put("customerSize", sizeOf("customer"));
        out.put("orderSize", sizeOf("customer_order"));
        out.put("note",
            "Both tables have GIN indexes on their JSONB columns AND " +
            "B-tree indexes on their spine columns (tenant_id, customer_id, status). " +
            "Now look at how the same domain answers different questions different ways.");
        return out;
    }

    // ─── spine queries: relational columns ───────────────────────────────

    /**
     * Lookup by email is the canonical "spine" query: the
     * {@code (tenant_id, email)} unique B-tree handles it, full
     * compile-time type-safety, planner has stats.
     */
    public Map<String, Object> findCustomer(Long tenantId, String email) {
        long t0 = System.nanoTime();
        Map<String, Object> row = jdbc.queryForMap(
            "select id, tenant_id, email, created_at, profile " +
            "from customer where tenant_id = ? and email = ?",
            tenantId, email);
        long t1 = System.nanoTime();
        List<String> plan = jdbc.queryForList(
            "explain (analyze, buffers, format text) " +
            "select * from customer where tenant_id = ? and email = ?",
            String.class, tenantId, email);
        return Map.of(
            "row", row,
            "elapsedUs", (t1 - t0) / 1_000.0,
            "plan", plan,
            "note",
                "Index Scan on the (tenant_id,email) unique index. This is the " +
                "shape of every authenticated request and it's column-fast.");
    }

    // ─── leaf queries: jsonb operators ───────────────────────────────────

    /**
     * Find every customer whose profile carries a given tag.
     * Containment ({@code @>}) over the profile JSONB, GIN-indexed.
     */
    public Map<String, Object> findCustomersWithTag(String tag) {
        try {
            String filter = mapper.writeValueAsString(Map.of("tags", List.of(tag)));
            long t0 = System.nanoTime();
            Integer count = jdbc.queryForObject(
                "select count(*) from customer where profile @> ?::jsonb",
                Integer.class, filter);
            long t1 = System.nanoTime();
            List<String> plan = jdbc.queryForList(
                "explain (analyze, buffers, format text) " +
                "select id from customer where profile @> ?::jsonb",
                String.class, filter);
            return Map.of(
                "tag", tag,
                "filter", filter,
                "matchCount", count,
                "elapsedMs", (t1 - t0) / 1_000_000.0,
                "plan", plan,
                "note",
                    "Bitmap Index Scan on customer_profile_gin. JSONB containment " +
                    "handles array membership: {tags:[\"vip\"]} matches any profile " +
                    "with \"vip\" in its tags array.");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Find orders containing a given SKU anywhere in the items array.
     * JSONB containment handles "array of objects contains an object
     * with this key/value" out of the box.
     */
    public Map<String, Object> findOrdersContainingSku(String sku) {
        try {
            // [{"sku":"X-..."}] — the @> operator pattern-matches into
            // arrays of objects in JSONB.
            String filter = mapper.writeValueAsString(List.of(Map.of("sku", sku)));
            long t0 = System.nanoTime();
            Integer count = jdbc.queryForObject(
                "select count(*) from customer_order where items @> ?::jsonb",
                Integer.class, filter);
            long t1 = System.nanoTime();
            List<String> plan = jdbc.queryForList(
                "explain (analyze, buffers, format text) " +
                "select id from customer_order where items @> ?::jsonb",
                String.class, filter);
            return Map.of(
                "sku", sku,
                "filter", filter,
                "matchCount", count,
                "elapsedMs", (t1 - t0) / 1_000_000.0,
                "plan", plan,
                "note",
                    "Bitmap Index Scan on order_items_gin. In Mongo this is " +
                    "db.orders.find({'items.sku':'X-...'}) — same idea, same speed class.");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─── reporting: unnest the array ─────────────────────────────────────

    /**
     * Materialize the embedded items array as relational rows on the
     * fly. This is the move that pure document stores struggle with —
     * once you need a SQL aggregate over the embedded items, you wish
     * you had a {@code GROUP BY}.
     *
     * <pre>{@code
     *   SELECT item->>'sku'        AS sku,
     *          SUM((item->>'qty')::int)                     AS units,
     *          SUM((item->>'qty')::int * (item->>'price')::numeric) AS revenue
     *   FROM customer_order o,
     *        LATERAL jsonb_array_elements(o.items) AS item
     *   WHERE o.status = 'PLACED'
     *   GROUP BY 1
     *   ORDER BY 3 DESC
     *   LIMIT ?;
     * }</pre>
     *
     * The lateral join expands each order's items array into N rows;
     * the {@code GROUP BY} then sums across orders AND items in one
     * statement. In Mongo you reach for an aggregation pipeline
     * with {@code $unwind} and {@code $group}; this is the SQL
     * shape of the same thing.
     */
    public List<Map<String, Object>> topSellingSkus(int limit) {
        return jdbc.queryForList(
            "select item->>'sku' as sku, " +
            "       sum((item->>'qty')::int) as units, " +
            "       sum((item->>'qty')::int * (item->>'price')::numeric) as revenue " +
            "from customer_order o, " +
            "     lateral jsonb_array_elements(o.items) as item " +
            "where o.status = 'PLACED' " +
            "group by 1 " +
            "order by 3 desc " +
            "limit ?",
            limit);
    }

    /**
     * Same lateral-expansion trick but per tenant — joins the order
     * back to its customer to recover the tenant_id, then aggregates.
     * This is the kind of query the order documents alone could not
     * answer; the {@code customer.tenant_id} FK is what carries the
     * tenancy.
     */
    public List<Map<String, Object>> revenueByTenant() {
        return jdbc.queryForList(
            "select c.tenant_id, " +
            "       count(distinct o.id) as orders, " +
            "       sum((item->>'qty')::int * (item->>'price')::numeric) as revenue " +
            "from customer_order o " +
            "join customer c on c.id = o.customer_id " +
            "cross join lateral jsonb_array_elements(o.items) as item " +
            "where o.status = 'PLACED' " +
            "group by c.tenant_id " +
            "order by 1");
    }

    // ─── profile updates ────────────────────────────────────────────────

    /**
     * Patch one key into the profile JSONB without round-tripping the
     * whole document. {@code profile || jsonb_build_object(...)} is
     * the JSONB equivalent of MongoDB's {@code $set}.
     */
    public Map<String, Object> patchProfile(Long customerId, String key, Object value) {
        try {
            int rows = jdbc.update(
                "update customer " +
                "set profile = profile || jsonb_build_object(?, ?::jsonb) " +
                "where id = ?",
                key, mapper.writeValueAsString(value), customerId);
            return Map.of(
                "customerId", customerId,
                "rowsUpdated", rows,
                "key", key,
                "value", value,
                "note",
                    "Single UPDATE, no read-modify-write round trip. The `||` " +
                    "operator merges right-side keys into the existing profile. " +
                    "Equivalent to Mongo's `$set: { key: value }`.");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─── the anti-pattern ────────────────────────────────────────────────

    /**
     * What "go full-document" looks like in this domain and why you
     * usually don't want it.
     *
     * <p>The single-document shape would be:
     * <pre>{@code
     *   { customer: {...},
     *     orders: [ {...}, {...}, ... ] }
     * }</pre>
     * Every customer is one row. Every order lives inside that row.
     * MongoDB's "embed the children" pattern.
     *
     * <p>What you LOSE going that way (we demonstrate via explicit
     * counter-examples):
     * <ol>
     *   <li>No FK from order to customer — orphan rows possible.</li>
     *   <li>No per-order index — finding "order 12345" needs a Seq Scan
     *       OR a custom GIN expression you have to maintain.</li>
     *   <li>Updating one order = rewriting the WHOLE customer doc.
     *       Hot customers (many orders) become contention hot spots.</li>
     *   <li>Reporting "revenue by tenant" requires unnest of EVERY
     *       customer's full embedded order list, vs. an indexed scan
     *       over the orders table.</li>
     *   <li>Atomic write across orders for different customers becomes
     *       cross-document and loses transactional guarantees in
     *       a typical document store (Postgres still has it because
     *       the rows live in Postgres, but the shape is fighting it).</li>
     * </ol>
     */
    public Map<String, Object> antiPatternComparison() {
        Map<String, Object> out = new LinkedHashMap<>();

        // Demonstrating the embedded shape vs the split shape on the
        // same domain. We'll do this without actually creating the
        // embedded table — just showing the queries you'd be forced to
        // write and why they're worse.

        // 1. find an order by id — split shape (current m29) vs embedded.
        long t0 = System.nanoTime();
        Map<String, Object> orderRow = jdbc.queryForMap(
            "select * from customer_order where id = (select min(id) from customer_order)");
        long t1 = System.nanoTime();
        out.put("split_findOrderById_us", (t1 - t0) / 1_000.0);
        out.put("embedded_findOrderById_sql",
            "SELECT (jsonb_path_query_first(doc, '$.orders[*] ? (@.id == 123)')) " +
            "FROM customer_doc;  -- no index, full scan over every doc");

        // 2. update one order — split (one row) vs embedded (one whole doc).
        long u0 = System.nanoTime();
        jdbc.update(
            "update customer_order set status = status where id = ?",
            orderRow.get("id"));
        long u1 = System.nanoTime();
        out.put("split_updateOneOrder_us", (u1 - u0) / 1_000.0);
        out.put("embedded_updateOneOrder_cost",
            "Rewrites the ENTIRE customer document, including every other " +
            "order they have. O(n_orders_per_customer) write amplification " +
            "for a single update. Hot customers contend.");

        // 3. revenue by tenant — split with FK join vs embedded recursion.
        long r0 = System.nanoTime();
        List<Map<String, Object>> rev = revenueByTenant();
        long r1 = System.nanoTime();
        out.put("split_revenueByTenant_ms", (r1 - r0) / 1_000_000.0);
        out.put("split_revenueByTenant_rows", rev.size());
        out.put("embedded_revenueByTenant_cost",
            "Must unnest customer_doc.orders[] for EVERY customer; tenant_id " +
            "is on the parent doc not the embedded order, so the GROUP BY is " +
            "ok but you're scanning every customer document instead of an " +
            "indexed orders table.");

        out.put("moral",
            "Split the spine from the leaves: structured/queried fields as " +
            "columns and FKs, sparse/polymorphic fields as JSONB on the same " +
            "row. Don't go full-document just because Postgres can store one.");
        return out;
    }

    // ─── topology / diagnostics ──────────────────────────────────────────

    public Map<String, Object> topology() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customers", jdbc.queryForObject("select count(*) from customer", Long.class));
        out.put("orders", jdbc.queryForObject("select count(*) from customer_order", Long.class));
        out.put("customerSize", sizeOf("customer"));
        out.put("orderSize", sizeOf("customer_order"));
        out.put("indexes", jdbc.queryForList(
            "select indexrelname as name, pg_size_pretty(pg_relation_size(indexrelid)) as size " +
            "from pg_stat_user_indexes where schemaname = 'm29_hybrid' order by indexrelname"));
        return out;
    }

    private String sizeOf(String table) {
        return jdbc.queryForObject(
            "select pg_size_pretty(pg_total_relation_size(?))",
            String.class, "m29_hybrid." + table);
    }
}
