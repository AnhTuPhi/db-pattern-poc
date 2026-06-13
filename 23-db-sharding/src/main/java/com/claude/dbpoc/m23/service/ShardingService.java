package com.claude.dbpoc.m23.service;

import com.claude.dbpoc.m23.routing.ShardRouter;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;

/**
 * Five demos:
 *
 *   1. seed         — create per-tenant orders on the right shard.
 *                     Confirms the router is symmetric: same tenantId
 *                     always lands on the same shard.
 *
 *   2. getForTenant — one tenant, one shard. The 99% case.
 *
 *   3. scatterGather— "give me the total order count across all tenants"
 *                     — requires querying EVERY shard in parallel and
 *                     merging. This is the cost of cross-shard queries
 *                     and the reason app-level sharding rules out joins
 *                     between tenants.
 *
 *   4. distribution — what fraction of keys land on each shard? Show
 *                     CONSISTENT_HASH vs MODULO.
 *
 *   5. resharding   — simulate a capacity add (s0,s1,s2 → s0,s1,s2,s3).
 *                     Report how many keys MOVE under MODULO (~75%) vs
 *                     CONSISTENT_HASH (~25%). This is the property that
 *                     determines whether online resharding is operationally
 *                     feasible.
 *
 *   6. dualWrite    — the actual resharding pattern: while the old and
 *                     new routing both exist, writes go to BOTH shards
 *                     for affected keys. Backfill copies the rest. After
 *                     verification, reads cut over and the old shard's
 *                     copy is dropped. This endpoint shows a single
 *                     dual-write call so the SQL is visible.
 */
@Service
public class ShardingService {

    private final ShardRouter router;

    public ShardingService(ShardRouter router) { this.router = router; }

    // ---------------------------------------------------------------------
    // 1. SEED — create the orders table on every shard, then populate with
    //          deterministic per-tenant rows.
    // ---------------------------------------------------------------------
    public Map<String, Object> seed(int tenants, int ordersPerTenant) {
        // Create table on every shard.
        for (HikariDataSource ds : router.all().values()) {
            execute(ds, "drop table if exists orders");
            execute(ds,
                "create table orders (" +
                "  id bigserial primary key, " +
                "  tenant_id bigint not null, " +
                "  amount numeric(18,2) not null, " +
                "  created_at timestamptz not null default now()" +
                ")");
            execute(ds, "create index orders_tenant_idx on orders(tenant_id)");
        }
        // Insert per-tenant rows, routed by the router.
        Map<String, Integer> perShardCount = new TreeMap<>();
        for (long t = 1; t <= tenants; t++) {
            String shard = router.shardFor(t);
            HikariDataSource ds = router.dsFor(t);
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "insert into orders(tenant_id, amount) values (?, ?)")) {
                for (int k = 0; k < ordersPerTenant; k++) {
                    ps.setLong(1, t);
                    ps.setBigDecimal(2, new java.math.BigDecimal(10 + (t + k) % 990));
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (Exception e) {
                throw new RuntimeException("seed failed on " + shard + ": " + e.getMessage(), e);
            }
            perShardCount.merge(shard, ordersPerTenant, Integer::sum);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenants", tenants);
        out.put("ordersPerTenant", ordersPerTenant);
        out.put("routingStrategy", router.getStrategy().toString());
        out.put("perShard", perShardCount);
        return out;
    }

    // ---------------------------------------------------------------------
    // 2. SINGLE-SHARD READ — the 99% case. One tenant, one query, one shard.
    // ---------------------------------------------------------------------
    public Map<String, Object> getForTenant(long tenantId) {
        String shard = router.shardFor(tenantId);
        HikariDataSource ds = router.dsFor(tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId);
        out.put("routedTo", shard);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "select count(*), coalesce(sum(amount), 0) " +
                 "from orders where tenant_id = ?")) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                out.put("orderCount", rs.getLong(1));
                out.put("totalAmount", rs.getBigDecimal(2));
            }
        } catch (Exception e) {
            throw new RuntimeException("get failed: " + e.getMessage(), e);
        }
        out.put("note",
            "Single shard read. This is the path you want every hot query " +
            "to take — it scales linearly with shard count.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 3. SCATTER/GATHER — cross-shard query. Run the same query on every
    //    shard in PARALLEL, merge in app code. P99 latency = slowest shard.
    // ---------------------------------------------------------------------
    public Map<String, Object> scatterGather() {
        ExecutorService ex = Executors.newFixedThreadPool(router.all().size());
        try {
            List<Future<long[]>> futures = new ArrayList<>();
            long t0 = System.nanoTime();
            for (HikariDataSource ds : router.all().values()) {
                futures.add(ex.submit(() -> {
                    try (Connection c = ds.getConnection();
                         Statement s = c.createStatement();
                         ResultSet rs = s.executeQuery(
                             "select count(*)::bigint, coalesce(sum(amount), 0)::bigint from orders")) {
                        rs.next();
                        return new long[]{rs.getLong(1), rs.getLong(2)};
                    }
                }));
            }
            long count = 0, sum = 0;
            for (Future<long[]> f : futures) {
                long[] r = f.get();
                count += r[0];
                sum   += r[1];
            }
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("scenario", "Cross-shard aggregate — count(*) + sum(amount) over EVERY shard.");
            out.put("shardsQueried", router.all().size());
            out.put("totalOrders", count);
            out.put("totalAmount", sum);
            out.put("elapsedMs", elapsedMs);
            out.put("note",
                "Latency = MAX(shard latency), not avg. One slow shard kills the whole " +
                "query — set per-shard timeouts and a fallback (return partial result + " +
                "flag a missing shard) rather than letting the whole call hang. Joins " +
                "between tenants are impossible without doing this for every join.");
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            ex.shutdown();
        }
    }

    // ---------------------------------------------------------------------
    // 4. DISTRIBUTION — quick sanity check that the router is fair.
    // ---------------------------------------------------------------------
    public Map<String, Object> distribution(int sampleTenants) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String s : router.shardIds()) counts.put(s, 0);
        for (long t = 1; t <= sampleTenants; t++) {
            counts.merge(router.shardFor(t), 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", router.getStrategy().toString());
        out.put("sampleTenants", sampleTenants);
        out.put("perShard", counts);
        out.put("note", "Under CONSISTENT_HASH with 150 vnodes/shard, distribution should be " +
            "within ~±10% per shard. Under MODULO it's exactly even (because every shard gets " +
            "exactly 1/N of integers).");
        return out;
    }

    // ---------------------------------------------------------------------
    // 5. RESHARDING SIMULATION — the punchline. Add a shard. Report how
    //    many keys MOVE under each strategy. CONSISTENT_HASH ≈ 1/N keys
    //    move; MODULO ≈ all keys move.
    // ---------------------------------------------------------------------
    public Map<String, Object> reshardSimulation(int sampleTenants) {
        List<String> from = List.of("s0", "s1", "s2");
        List<String> to   = List.of("s0", "s1", "s2", "s3");

        double chMove  = router.movementFraction(sampleTenants, from, to);
        double modMove = router.moduloMovementFraction(sampleTenants, from.size(), to.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "Adding shard s3 to {s0, s1, s2}");
        out.put("sampleTenants", sampleTenants);
        out.put("consistentHashMovedFraction", String.format("%.3f", chMove));
        out.put("moduloMovedFraction",        String.format("%.3f", modMove));
        out.put("note",
            "Under CONSISTENT_HASH ~1/N keys move when you add a shard — only the " +
            "keys that fell in the wedges newly owned by s3's vnodes. Under MODULO " +
            "almost every key moves because the residue mod 4 != residue mod 3. This " +
            "is the property that makes consistent hashing viable for online resharding.");
        out.put("projectedPostReshard",
            router.projectedDistribution(sampleTenants, to));
        return out;
    }

    // ---------------------------------------------------------------------
    // 6. DUAL-WRITE — the actual online-resharding mechanic.
    //
    // For a tenant whose ownership is moving from shard A to shard B, the
    // app writes to BOTH A and B during the migration window. Reads can
    // come from either; the operator chooses A until backfill finishes, then
    // flips reads to B, then drops A's copy.
    //
    // This endpoint shows ONE such dual-write so the SQL is visible.
    // ---------------------------------------------------------------------
    public Map<String, Object> dualWrite(long tenantId, java.math.BigDecimal amount,
                                         String oldShard, String newShard) {
        HikariDataSource a = router.all().get(oldShard);
        HikariDataSource b = router.all().get(newShard);
        if (a == null || b == null) {
            throw new IllegalArgumentException("Unknown shard: oldShard=" + oldShard + " newShard=" + newShard);
        }

        // Step 1: write to old shard.
        Long idOld = insertOrder(a, tenantId, amount);
        // Step 2: write to new shard.
        Long idNew = insertOrder(b, tenantId, amount);
        // NOTE: in real life these two writes are NOT atomic. The two
        // failure modes:
        //   A succeeds, B fails  → old shard ahead; backfill must reconcile.
        //   A fails after B      → orphan row on B; cleanup job must reconcile.
        // Idempotency + reconciliation is the price of this pattern.

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId);
        out.put("amount", amount);
        out.put("wroteToOldShard", Map.of("shard", oldShard, "id", idOld));
        out.put("wroteToNewShard", Map.of("shard", newShard, "id", idNew));
        out.put("note",
            "Both writes succeeded INDEPENDENTLY. They are NOT atomic — no 2PC. " +
            "If the old write succeeded and the new failed, the resharding job must " +
            "find the orphan and replay. If the new succeeded and the old failed, " +
            "the OLD copy is missing and the user might see a torn read at cutover. " +
            "Run with idempotent keys (tenant_id + client_request_id) so retries work.");
        return out;
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private Long insertOrder(HikariDataSource ds, long tenantId, java.math.BigDecimal amount) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "insert into orders(tenant_id, amount) values (?, ?) returning id")) {
            ps.setLong(1, tenantId);
            ps.setBigDecimal(2, amount);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new RuntimeException("insertOrder failed: " + e.getMessage(), e);
        }
    }

    private void execute(HikariDataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException("execute failed: " + e.getMessage() + " — " + sql, e);
        }
    }
}
