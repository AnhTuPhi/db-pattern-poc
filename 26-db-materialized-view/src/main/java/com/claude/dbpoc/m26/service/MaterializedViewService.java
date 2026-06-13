package com.claude.dbpoc.m26.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Eight demos:
 *
 *   1. seed              — orders + order_items + product_categories,
 *                          enough volume to make the expensive query
 *                          obviously slow.
 *   2. createMv          — `CREATE MATERIALIZED VIEW monthly_sales AS …`
 *                          with a unique index, so we can REFRESH CONCURRENTLY.
 *   3. timeExpensive     — time the raw analytical query against the base tables.
 *   4. timeMv            — same query result via the MV.
 *   5. refreshBlocking   — `REFRESH MATERIALIZED VIEW` — locks the MV
 *                          AccessExclusive; reads block.
 *   6. refreshConcurrent — `REFRESH MATERIALIZED VIEW CONCURRENTLY` —
 *                          builds the new contents in a side table and
 *                          swaps via diff; reads don't block, but it
 *                          requires a UNIQUE index on the MV and it's
 *                          slower per refresh.
 *   7. mutateAndCompare  — INSERT a new order, then run the MV query
 *                          and the base-table query — show the MV is
 *                          STALE until refreshed.
 *   8. computedColumnDemo— a "computed column" alternative: instead of
 *                          a MV that aggregates, denormalize the total
 *                          onto orders via a trigger. Always fresh;
 *                          pay the cost on every write.
 *
 * The service is otherwise a thin wrapper around JdbcTemplate and DDL.
 */
@Service
public class MaterializedViewService {

    private final JdbcTemplate jdbc;

    public MaterializedViewService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ---------------------------------------------------------------------
    // 1. SEED
    // ---------------------------------------------------------------------
    @Transactional
    public Map<String, Object> seed(int orders) {
        jdbc.execute("drop materialized view if exists monthly_sales");
        jdbc.execute("drop table if exists order_items cascade");
        jdbc.execute("drop table if exists orders      cascade");
        jdbc.execute("drop table if exists products    cascade");

        jdbc.execute(
            "create table products (" +
            "  id bigserial primary key, " +
            "  category text not null, " +
            "  name text not null, " +
            "  price numeric(18,2) not null" +
            ")");
        jdbc.update(
            "insert into products(category, name, price) " +
            "select (array['electronics','grocery','apparel','home','toys'])[(g % 5) + 1], " +
            "       'p-' || g, (10 + random() * 990)::numeric(18,2) " +
            "from generate_series(1, 200) g");

        jdbc.execute(
            "create table orders (" +
            "  id bigserial primary key, " +
            "  customer_id bigint not null, " +
            "  created_at timestamptz not null default now(), " +
            "  total numeric(18,2) not null default 0" +
            ")");
        jdbc.update(
            "insert into orders(customer_id, created_at) " +
            "select (random() * 10000)::bigint, " +
            "       now() - (random() * interval '365 days') " +
            "from generate_series(1, ?) g", orders);

        jdbc.execute(
            "create table order_items (" +
            "  id bigserial primary key, " +
            "  order_id bigint not null references orders(id), " +
            "  product_id bigint not null references products(id), " +
            "  qty int not null, " +
            "  line_total numeric(18,2) not null" +
            ")");
        // ~5 items per order.
        jdbc.update(
            "insert into order_items(order_id, product_id, qty, line_total) " +
            "select o.id, " +
            "       (random() * 199 + 1)::bigint, " +
            "       (random() * 4 + 1)::int, " +
            "       (10 + random() * 1000)::numeric(18,2) " +
            "from orders o, generate_series(1, 5)");

        jdbc.execute("create index orders_created_at_idx on orders(created_at)");
        jdbc.execute("create index oi_order_idx on order_items(order_id)");
        jdbc.execute("create index oi_product_idx on order_items(product_id)");
        jdbc.execute("analyze products");
        jdbc.execute("analyze orders");
        jdbc.execute("analyze order_items");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orders", orders);
        out.put("orderItems", jdbc.queryForObject("select count(*) from order_items", Long.class));
        return out;
    }

    // ---------------------------------------------------------------------
    // 2. CREATE MV
    // ---------------------------------------------------------------------
    @Transactional
    public Map<String, Object> createMv() {
        jdbc.execute("drop materialized view if exists monthly_sales");
        jdbc.execute(
            "create materialized view monthly_sales as " +
            "select date_trunc('month', o.created_at)::date as month, " +
            "       p.category, " +
            "       count(*)            as line_count, " +
            "       sum(oi.qty)         as units, " +
            "       sum(oi.line_total)  as revenue " +
            "from orders o " +
            "join order_items oi on oi.order_id = o.id " +
            "join products    p  on p.id        = oi.product_id " +
            "group by 1, 2 " +
            "with data");

        // REFRESH CONCURRENTLY requires a UNIQUE index. Cover the
        // grouping columns.
        jdbc.execute(
            "create unique index monthly_sales_pk " +
            "on monthly_sales (month, category)");
        jdbc.execute("analyze monthly_sales");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mv", "monthly_sales");
        out.put("rowCount", jdbc.queryForObject("select count(*) from monthly_sales", Long.class));
        out.put("note",
            "The MV stores the aggregate result as a real relation. Queries against it " +
            "are O(rows-in-mv), not O(orders × items). The trade-off: it's a SNAPSHOT — " +
            "stale until REFRESH.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 3. EXPENSIVE QUERY against base tables
    // ---------------------------------------------------------------------
    public Map<String, Object> timeExpensive() {
        String sql =
            "select date_trunc('month', o.created_at)::date as month, " +
            "       p.category, " +
            "       sum(oi.line_total) as revenue " +
            "from orders o " +
            "join order_items oi on oi.order_id = o.id " +
            "join products    p  on p.id        = oi.product_id " +
            "group by 1, 2 order by 1 desc, 2";

        long t0 = System.nanoTime();
        List<Map<String, Object>> rows = jdbc.queryForList(sql);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "BASE TABLES — 3-way join + group by");
        out.put("elapsedMs", elapsedMs);
        out.put("resultRows", rows.size());
        out.put("plan", jdbc.queryForList("explain (analyze, buffers) " + sql, String.class));
        return out;
    }

    // ---------------------------------------------------------------------
    // 4. SAME QUERY via MV
    // ---------------------------------------------------------------------
    public Map<String, Object> timeMv() {
        String sql = "select month, category, revenue " +
                     "from monthly_sales order by month desc, category";

        long t0 = System.nanoTime();
        List<Map<String, Object>> rows = jdbc.queryForList(sql);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "MATERIALIZED VIEW monthly_sales");
        out.put("elapsedMs", elapsedMs);
        out.put("resultRows", rows.size());
        out.put("plan", jdbc.queryForList("explain (analyze, buffers) " + sql, String.class));
        return out;
    }

    // ---------------------------------------------------------------------
    // 5. REFRESH (BLOCKING)
    // ---------------------------------------------------------------------
    public Map<String, Object> refreshBlocking() {
        long t0 = System.nanoTime();
        jdbc.execute("refresh materialized view monthly_sales");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "REFRESH MATERIALIZED VIEW (blocking)");
        out.put("elapsedMs", elapsedMs);
        out.put("warning",
            "Takes AccessExclusiveLock on the MV — SELECTs against it BLOCK until the " +
            "refresh finishes. On a large MV that's measured in seconds-to-minutes. " +
            "Acceptable for off-hours batches; lethal on a hot dashboard.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 6. REFRESH CONCURRENTLY (non-blocking)
    // ---------------------------------------------------------------------
    public Map<String, Object> refreshConcurrent() {
        long t0 = System.nanoTime();
        jdbc.execute("refresh materialized view concurrently monthly_sales");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "REFRESH MATERIALIZED VIEW CONCURRENTLY");
        out.put("elapsedMs", elapsedMs);
        out.put("note",
            "Builds a side table, computes the diff, and applies UPDATE/INSERT/DELETE " +
            "while the old MV stays readable. Requires a UNIQUE index on the MV. " +
            "Roughly 2-3× slower than a blocking refresh (it does extra work) but " +
            "doesn't block readers. Always prefer this for user-facing MVs.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 7. STALENESS DEMO
    // ---------------------------------------------------------------------
    public Map<String, Object> mutateAndCompare(java.math.BigDecimal amount) {
        // Add ONE big-ticket sale this minute.
        jdbc.update(
            "insert into orders(customer_id, created_at) values (99999, now())");
        Long orderId = jdbc.queryForObject(
            "select id from orders order by id desc limit 1", Long.class);
        jdbc.update(
            "insert into order_items(order_id, product_id, qty, line_total) " +
            "values (?, 1, 1, ?)",
            orderId, amount);

        var baseNow = jdbc.queryForObject(
            "select coalesce(sum(line_total), 0) " +
            "from order_items oi join orders o on o.id = oi.order_id " +
            "where date_trunc('month', o.created_at) = date_trunc('month', now())",
            java.math.BigDecimal.class);
        var mvNow = jdbc.queryForObject(
            "select coalesce(sum(revenue), 0) from monthly_sales " +
            "where month = date_trunc('month', now())::date",
            java.math.BigDecimal.class);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "Inserted a new sale; comparing base-table truth vs MV snapshot");
        out.put("newSale", amount);
        out.put("baseTableRevenueThisMonth", baseNow);
        out.put("mvRevenueThisMonth",        mvNow);
        out.put("staleByAmount", baseNow.subtract(mvNow == null ? java.math.BigDecimal.ZERO : mvNow));
        out.put("note",
            "The MV doesn't know about the new sale until you REFRESH. The size of " +
            "your acceptable staleness window IS your refresh frequency.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 8. COMPUTED COLUMN alternative
    //
    // Instead of an MV that aggregates, we keep `orders.total` always
    // accurate via a trigger on order_items. The price: every insert/
    // update/delete on order_items pays an extra UPDATE on orders.
    // ---------------------------------------------------------------------
    @Transactional
    public Map<String, Object> computedColumnDemo() {
        jdbc.execute(
            "create or replace function recalc_order_total() returns trigger language plpgsql as $$ " +
            "begin " +
            "  update orders o set total = (select coalesce(sum(line_total), 0) " +
            "                               from order_items where order_id = coalesce(new.order_id, old.order_id)) " +
            "  where o.id = coalesce(new.order_id, old.order_id); " +
            "  return null; " +
            "end$$");
        jdbc.execute("drop trigger if exists trg_recalc_total on order_items");
        jdbc.execute(
            "create trigger trg_recalc_total " +
            "after insert or update or delete on order_items " +
            "for each row execute function recalc_order_total()");

        // Backfill totals once so the column reflects existing data.
        jdbc.execute(
            "update orders o set total = sub.s " +
            "from (select order_id, sum(line_total) s from order_items group by order_id) sub " +
            "where sub.order_id = o.id");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("denormalizedColumn", "orders.total");
        out.put("triggerInstalled", "trg_recalc_total on order_items");
        out.put("sample", jdbc.queryForList(
            "select id, total from orders order by id desc limit 5"));
        out.put("tradeoff",
            "Always fresh (no MV refresh). Cost: every write to order_items now writes to " +
            "orders too. Concurrent inserts on the same order may contend on the orders row " +
            "lock — fine for low-write workloads, not for high-write fan-out.");
        return out;
    }

    // ---------------------------------------------------------------------
    // Helper — MV size + last-refresh metadata.
    // ---------------------------------------------------------------------
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mvSize", jdbc.queryForObject(
            "select pg_size_pretty(pg_total_relation_size('monthly_sales'::regclass))",
            String.class));
        out.put("rowCount", jdbc.queryForObject(
            "select count(*) from monthly_sales", Long.class));
        out.put("indexes", jdbc.queryForList(
            "select indexname, indexdef from pg_indexes " +
            "where schemaname = current_schema() and tablename = 'monthly_sales'"));
        return out;
    }
}
