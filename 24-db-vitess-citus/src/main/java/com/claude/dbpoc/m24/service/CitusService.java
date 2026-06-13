package com.claude.dbpoc.m24.service;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Six demos against a real Citus 12 cluster:
 *
 *   1. setupCluster — install the extension, add the two workers as
 *      coordinator nodes. Idempotent.
 *
 *   2. seed — create a distributed `orders` table sharded by tenant_id,
 *      a co-located `order_items` table (so PK joins stay local on each
 *      worker), and a `regions` REFERENCE table (replicated to every
 *      worker). Then insert ~N tenants of data.
 *
 *   3. singleTenantQuery — query for ONE tenant. The coordinator
 *      knows the shard for that tenant_id and routes to a single
 *      worker. EXPLAIN shows "Task Count: 1".
 *
 *   4. crossTenantQuery — aggregate across all tenants. The coordinator
 *      scatter/gathers across all 32 shards across the 2 workers.
 *      EXPLAIN shows "Task Count: 32" and a Custom Scan (Citus Adaptive)
 *      node. This is the function you used to write by hand in m23.
 *
 *   5. colocatedJoin — orders ⨝ order_items, both sharded by tenant_id.
 *      Citus does the join LOCALLY on each worker — no cross-worker
 *      shuffle. Fast.
 *
 *   6. referenceTableJoin — orders ⨝ regions. Regions is a reference
 *      table (replicated to every worker), so the join is also LOCAL
 *      on each worker.
 *
 * Every demo also dumps EXPLAIN so you can see Citus's "Custom Scan
 * (Citus Adaptive)" node and the per-shard task fan-out.
 */
@Service
public class CitusService {

    private final JdbcTemplate jdbc;

    public CitusService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ---------------------------------------------------------------------
    // 1. Cluster setup — install extension, register workers.
    // ---------------------------------------------------------------------
    @Transactional
    public Map<String, Object> setupCluster() {
        Map<String, Object> out = new LinkedHashMap<>();
        jdbc.execute("create extension if not exists citus");

        // Register workers by hostname (docker-compose service names).
        // Idempotent — citus_set_coordinator_host + citus_add_node both no-op
        // if already set.
        jdbc.execute("select citus_set_coordinator_host('citus-coordinator', 5432)");
        addWorkerIfMissing("citus-worker-1");
        addWorkerIfMissing("citus-worker-2");

        out.put("citusVersion", jdbc.queryForObject("select citus_version()", String.class));
        out.put("activeNodes", jdbc.queryForList(
            "select nodename, nodeport, noderole, isactive from pg_dist_node order by nodeid"));
        out.put("note",
            "The coordinator holds the catalog and the query planner. Workers hold the data. " +
            "Every shard is a regular Postgres table on a worker, named like " +
            "orders_102008 (table name + shard id). You can connect to a worker directly and " +
            "see them, but you'd never query them from the app — the coordinator is the only " +
            "endpoint.");
        return out;
    }

    private void addWorkerIfMissing(String host) {
        Integer existing = jdbc.queryForObject(
            "select count(*) from pg_dist_node where nodename = ?",
            Integer.class, host);
        if (existing == null || existing == 0) {
            jdbc.execute("select citus_add_node('" + host + "', 5432)");
        }
    }

    // ---------------------------------------------------------------------
    // 2. Seed — distributed table + co-located table + reference table.
    // ---------------------------------------------------------------------
    @Transactional
    public Map<String, Object> seed(int tenants, int ordersPerTenant) {
        jdbc.execute("drop table if exists order_items cascade");
        jdbc.execute("drop table if exists orders cascade");
        jdbc.execute("drop table if exists regions cascade");

        // Reference table — small, replicated to every worker. Joins to
        // it are LOCAL on each worker.
        jdbc.execute(
            "create table regions (" +
            "  code text primary key, " +
            "  name text not null" +
            ")");
        jdbc.execute("select create_reference_table('regions')");
        jdbc.update("insert into regions(code, name) values ('us', 'United States'), " +
                    "('eu', 'Europe'), ('ap', 'Asia Pacific')");

        // Distributed table — sharded by tenant_id. Citus's default
        // shard_count is 32 (per worker config), so we get 32 shards
        // spread across the 2 workers = 16 per worker.
        // Notice the PK includes tenant_id — Citus requires the
        // distribution column in every unique constraint.
        jdbc.execute(
            "create table orders (" +
            "  id bigserial, " +
            "  tenant_id bigint not null, " +
            "  region_code text not null, " +
            "  amount numeric(18,2) not null, " +
            "  created_at timestamptz not null default now(), " +
            "  primary key (id, tenant_id)" +
            ")");
        jdbc.execute("select create_distributed_table('orders', 'tenant_id')");

        // Co-located distributed table — same distribution column, same
        // shard count, same hash function. Citus places matching shards
        // on the same worker, so joins on tenant_id stay LOCAL.
        jdbc.execute(
            "create table order_items (" +
            "  id bigserial, " +
            "  order_id bigint not null, " +
            "  tenant_id bigint not null, " +
            "  sku text not null, " +
            "  qty int not null, " +
            "  primary key (id, tenant_id)" +
            ")");
        jdbc.execute(
            "select create_distributed_table('order_items', 'tenant_id', " +
            "                                colocate_with => 'orders')");

        // Seed.
        jdbc.update(
            "insert into orders(tenant_id, region_code, amount) " +
            "select t, " +
            "       (array['us', 'eu', 'ap'])[(t % 3) + 1], " +
            "       (random() * 1000)::numeric(18,2) " +
            "from generate_series(1, ?) t, generate_series(1, ?) k",
            tenants, ordersPerTenant);

        jdbc.update(
            "insert into order_items(order_id, tenant_id, sku, qty) " +
            "select o.id, o.tenant_id, " +
            "       'sku-' || (random() * 1000)::int, " +
            "       (random() * 5)::int + 1 " +
            "from orders o");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenants", tenants);
        out.put("ordersPerTenant", ordersPerTenant);
        out.put("shards", jdbc.queryForList(
            "select logicalrelid::regclass::text as table, count(*) as shards " +
            "from pg_dist_shard group by logicalrelid"));
        out.put("perWorkerShardCount", jdbc.queryForList(
            "select nodename, count(*) as shards " +
            "from pg_dist_shard_placement p " +
            "join pg_dist_node n on n.nodeid = p.groupid " +
            "group by nodename order by nodename"));
        out.put("note",
            "orders + order_items are CO-LOCATED — Citus places matching shards on the " +
            "same worker. A join on tenant_id stays local. regions is a REFERENCE table — " +
            "fully replicated to every worker, so it can be joined freely.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 3. Single-tenant query — Task Count = 1.
    // ---------------------------------------------------------------------
    public Map<String, Object> singleTenant(long tenantId) {
        String sql = "select count(*), coalesce(sum(amount), 0) " +
                     "from orders where tenant_id = " + tenantId;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", sql);
        out.put("plan", explain(sql));
        out.put("note",
            "Look for 'Task Count: 1' in the plan. The coordinator hashes the tenant_id, " +
            "finds the owning shard, and sends the query to exactly ONE worker. " +
            "This is the OLTP path — fast, scales with workers.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 4. Cross-tenant aggregate — Task Count = N shards.
    // ---------------------------------------------------------------------
    public Map<String, Object> crossTenant() {
        String sql = "select count(*), coalesce(sum(amount), 0) from orders";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", sql);
        out.put("plan", explain(sql));
        out.put("note",
            "Custom Scan (Citus Adaptive) → 'Task Count: 32' (or however many shards). " +
            "The coordinator runs the partial aggregate on every shard in parallel, then " +
            "combines the partial results. You did NOT write the scatter/gather — Citus did. " +
            "This is the m23 scatter/gather, but as a query planner feature.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 5. Co-located join — orders ⨝ order_items on (tenant_id).
    // ---------------------------------------------------------------------
    public Map<String, Object> colocatedJoin() {
        String sql =
            "select o.tenant_id, count(*), sum(oi.qty) " +
            "from orders o " +
            "join order_items oi on oi.tenant_id = o.tenant_id and oi.order_id = o.id " +
            "group by o.tenant_id " +
            "order by o.tenant_id " +
            "limit 5";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", sql);
        out.put("plan", explain(sql));
        out.put("rows", jdbc.queryForList(sql));
        out.put("note",
            "Both tables hash tenant_id identically AND Citus put matching shards on the same " +
            "worker → the join runs LOCALLY on each worker. No cross-worker shuffle. This is " +
            "the magic word: CO-LOCATION. Pick your distribution column for the joins you can't " +
            "live without.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 6. Reference-table join.
    // ---------------------------------------------------------------------
    public Map<String, Object> referenceTableJoin() {
        String sql =
            "select r.name, count(o.*), coalesce(sum(o.amount), 0) " +
            "from orders o join regions r on r.code = o.region_code " +
            "group by r.name order by r.name";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", sql);
        out.put("plan", explain(sql));
        out.put("rows", jdbc.queryForList(sql));
        out.put("note",
            "regions is a REFERENCE table → a complete copy lives on every worker. The join " +
            "is local on each worker. Use reference tables for small slowly-changing dimensions " +
            "(countries, currencies, regions). Bad fit for large or write-heavy tables — every " +
            "write goes to every worker.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 7. Show distribution metadata — what's a distributed table, what's
    //    on which worker, how big.
    // ---------------------------------------------------------------------
    public Map<String, Object> topology() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", jdbc.queryForList(
            "select nodename, nodeport, noderole, isactive from pg_dist_node"));
        out.put("distributedTables", jdbc.queryForList(
            "select logicalrelid::regclass::text as table, " +
            "       column_to_column_name(logicalrelid, partkey) as dist_column, " +
            "       colocationid, " +
            "       (select count(*) from pg_dist_shard where logicalrelid = d.logicalrelid) as shards " +
            "from pg_dist_partition d order by 1"));
        out.put("note", "Co-located tables share the same colocationid.");
        return out;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private List<String> explain(String sql) {
        try {
            return jdbc.queryForList("explain (verbose, analyze, format text) " + sql, String.class);
        } catch (DataAccessException e) {
            return List.of("EXPLAIN failed: " + e.getMessage());
        }
    }
}
