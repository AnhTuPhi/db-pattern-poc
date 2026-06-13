package com.claude.dbpoc.m23.routing;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * The router maps a tenantId → exactly one shard.
 *
 * Two strategies, both reachable, so the demo can show the difference:
 *
 *   MODULO:
 *     shard = tenantId % shardCount
 *     Simple, fast, perfectly balanced. But if you change shardCount
 *     from N to N+1 to add capacity, almost EVERY key moves. That's
 *     the entire dataset shuffled across the wire. Practically
 *     unusable for any production resharding.
 *
 *   CONSISTENT_HASH:
 *     N "virtual nodes" per shard are placed around a hash ring
 *     (md5 → int). A key hashes to a point on the ring and is owned
 *     by the next vnode clockwise. When you add a shard, only the
 *     keys between the new vnodes and their predecessors move —
 *     ~1/N of the dataset, not all of it. This is the property that
 *     makes online resharding possible.
 *
 * Both implementations are deterministic — same key, same routing —
 * so an app on host A and an app on host B route the same tenant to
 * the same shard. That's the entire correctness contract.
 */
@Component
public class ShardRouter {

    public enum Strategy { MODULO, CONSISTENT_HASH }

    /** Virtual nodes per real shard. 100–200 is the usual range. */
    private static final int VNODES_PER_SHARD = 150;

    private final Map<String, HikariDataSource> shards;
    /** TreeMap is the natural data structure for a hash ring: ceilingKey() = "next clockwise". */
    private final NavigableMap<Integer, String> ring = new TreeMap<>();
    private final List<String> orderedShardIds;

    private volatile Strategy strategy = Strategy.CONSISTENT_HASH;

    public ShardRouter(Map<String, HikariDataSource> shards) {
        this.shards = shards;
        this.orderedShardIds = new ArrayList<>(shards.keySet());
        rebuildRing(orderedShardIds);
    }

    // ---------------- public surface ----------------

    public void setStrategy(Strategy s) { this.strategy = s; }
    public Strategy getStrategy()       { return strategy; }
    public List<String> shardIds()      { return List.copyOf(orderedShardIds); }

    /** Route a tenantId to a shard id (e.g. "s2"). */
    public String shardFor(long tenantId) {
        return switch (strategy) {
            case MODULO          -> orderedShardIds.get(Math.floorMod(tenantId, orderedShardIds.size()));
            case CONSISTENT_HASH -> consistentHash(tenantId);
        };
    }

    /** Resolve a tenantId straight to its Hikari pool. */
    public HikariDataSource dsFor(long tenantId) {
        return shards.get(shardFor(tenantId));
    }

    /** All shards — used for scatter/gather and admin endpoints. */
    public Map<String, HikariDataSource> all() { return shards; }

    /**
     * Test "what would change if we added these shards?" — used by the
     * resharding demo. Does NOT mutate the router.
     */
    public Map<String, Integer> projectedDistribution(int sampleTenants, List<String> shardIds) {
        NavigableMap<Integer, String> hypothetical = buildRing(shardIds);
        Map<String, Integer> counts = new TreeMap<>();
        for (String s : shardIds) counts.put(s, 0);
        for (long t = 1; t <= sampleTenants; t++) {
            int h = hash(Long.toString(t));
            Map.Entry<Integer, String> e = hypothetical.ceilingEntry(h);
            if (e == null) e = hypothetical.firstEntry();
            counts.merge(e.getValue(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * What fraction of keys MOVE if we change the shard set from `from`
     * to `to`? This is the killer demonstration: for CONSISTENT_HASH it's
     * ~1/N. For MODULO with shardCount changes, it's near 1.0.
     */
    public double movementFraction(int sampleTenants, List<String> from, List<String> to) {
        NavigableMap<Integer, String> ringFrom = buildRing(from);
        NavigableMap<Integer, String> ringTo   = buildRing(to);
        int moved = 0;
        for (long t = 1; t <= sampleTenants; t++) {
            String a = lookup(ringFrom, t);
            String b = lookup(ringTo, t);
            if (!a.equals(b)) moved++;
        }
        return (double) moved / sampleTenants;
    }

    /** Equivalent of `movementFraction` for the MODULO strategy. */
    public double moduloMovementFraction(int sampleTenants, int fromN, int toN) {
        int moved = 0;
        for (long t = 1; t <= sampleTenants; t++) {
            if (Math.floorMod(t, fromN) != Math.floorMod(t, toN)) moved++;
        }
        return (double) moved / sampleTenants;
    }

    // ---------------- ring internals ----------------

    private void rebuildRing(List<String> shardIds) {
        ring.clear();
        ring.putAll(buildRing(shardIds));
    }

    private NavigableMap<Integer, String> buildRing(List<String> shardIds) {
        NavigableMap<Integer, String> r = new TreeMap<>();
        for (String shard : shardIds) {
            for (int v = 0; v < VNODES_PER_SHARD; v++) {
                r.put(hash(shard + "#" + v), shard);
            }
        }
        return r;
    }

    private String consistentHash(long tenantId) {
        int h = hash(Long.toString(tenantId));
        Map.Entry<Integer, String> e = ring.ceilingEntry(h);
        // wrap-around — clockwise past the largest vnode lands on the smallest
        if (e == null) e = ring.firstEntry();
        return e.getValue();
    }

    private String lookup(NavigableMap<Integer, String> r, long tenantId) {
        int h = hash(Long.toString(tenantId));
        Map.Entry<Integer, String> e = r.ceilingEntry(h);
        if (e == null) e = r.firstEntry();
        return e.getValue();
    }

    /** MD5-based 32-bit hash. Fine for a sharding ring; not for security. */
    private static int hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return ((d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16)
                 | ((d[2] & 0xff) << 8)  |  (d[3] & 0xff);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
