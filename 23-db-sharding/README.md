# 23 - DB Sharding (app-level, consistent hashing, online reshard)

> Sharding is a one-way door.
> Get the key wrong and your only fix is to reshard, which is the
> hardest thing you'll do.

## Thesis

Horizontal sharding splits one logical table across N physical
databases by some key — usually `tenant_id` or `user_id`. The app
maintains the routing: given a key, which shard owns it?

Two routing strategies. Pick wrong and you trap your future self:

| Strategy          | Distribution | Adding a shard moves… |
|-------------------|--------------|------------------------|
| `MODULO`          | perfectly even | ~all keys             |
| `CONSISTENT_HASH` | ±10%         | ~1/N of keys          |

Modulo is the textbook example because it's three lines of code. It's
also unusable in production: any capacity change is a full data
shuffle. **Consistent hashing** spreads tokens around a ring; adding a
shard only moves the keys in the wedges its tokens land in.

The actual hard parts of sharding aren't the math — they're:

1. **Cross-shard queries** are scatter/gather. Joins between tenants
   are impossible without it. P99 = MAX(per-shard P99).
2. **Online resharding** requires dual-write windows, backfill, and a
   reconciliation step. None of it is atomic.
3. **Schema migrations** now run N times, and you've signed up for
   N×failure-modes.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 23-db-sharding -am spring-boot:run
```

App boots on **:8223**. Four Hikari pools point at schemas
`m23_s0`–`m23_s3` — the demo simulates a four-server cluster with one
Postgres instance. The routing code is identical to the real thing.

## Run order

```bash
# 0. Seed 200 tenants × 10 orders each. Each tenant lands on exactly
#    one shard — routed by ShardRouter.
curl -X POST 'localhost:8223/shard/seed?tenants=200&ordersPerTenant=10' | jq

# 1. Single-shard read — the 99% case. Notice "routedTo": exactly one shard.
curl 'localhost:8223/shard/tenant/42' | jq

# 2. Cross-shard aggregate — scatter/gather. ALL shards in parallel.
curl 'localhost:8223/shard/scatter-gather' | jq

# 3. Distribution check. With CONSISTENT_HASH it's within ±10% per shard.
curl 'localhost:8223/shard/distribution?sample=10000' | jq

# 4. The punchline: resharding s0,s1,s2 → s0,s1,s2,s3.
#    Reports MOVEMENT FRACTION under each strategy.
curl 'localhost:8223/shard/reshard-simulation?sample=10000' | jq

# 5. Compare strategies. Switch to MODULO and re-run #4.
curl -X POST 'localhost:8223/shard/strategy?value=MODULO'
curl 'localhost:8223/shard/reshard-simulation?sample=10000' | jq
curl -X POST 'localhost:8223/shard/strategy?value=CONSISTENT_HASH'

# 6. Dual-write — the actual resharding mechanic.
curl -X POST 'localhost:8223/shard/dual-write?tenantId=1&amount=99&oldShard=s0&newShard=s2' | jq
```

## What you see

### Distribution

```
CONSISTENT_HASH, 10000 tenants → { s0: 2510, s1: 2496, s2: 2487, s3: 2507 }
MODULO,          10000 tenants → { s0: 2500, s1: 2500, s2: 2500, s3: 2500 }
```

Modulo is mathematically perfect; consistent hash is ±0.5% with 150
vnodes/shard. Both are fine for distribution — distribution is not the
problem.

### Resharding 3 → 4 shards

```
CONSISTENT_HASH movedFraction = 0.249       (~1/N = 0.25)
MODULO          movedFraction = 0.750       (3/4 of keys move)
```

MODULO moves 75% of keys when you go 3→4. That's "rebuild the database"
work. CONSISTENT_HASH moves 25%, and you can do those rebalances one
key at a time. **This single number is why nobody serious uses MODULO**.

## How the router works

```
                tenantId
                   │
                hash()           ── MD5-based 32-bit hash
                   ▼
       ┌──────── ring ────────┐
       │ s0#0  → 0x04A1...     │   ── TreeMap<Integer,String>
       │ s1#42 → 0x0F2B...     │      150 vnodes per real shard
       │ s2#7  → 0x11C9...     │      ceilingEntry(h) = next clockwise vnode
       │ ...                   │
       └───────────────────────┘
                   │
            shardId ("s2")
                   │
                   ▼
     dataSourceMap.get(shardId) → HikariDataSource
```

The router is a `@Component`; it's the single source of truth for "key
→ shard". Build it once on startup, share it across the app. Every
write and every read goes through it.

## The constraints

### No cross-shard ACID

Two writes to different shards = two transactions. Not one. No 2PC.
If you need atomic cross-tenant writes, you don't want app-level
sharding — see m24 (Vitess/Citus) or rethink the schema.

### No cross-shard JOIN, no cross-shard ORDER BY

```sql
-- impossible in app-level sharding
SELECT u.*, c.* FROM users u JOIN companies c ON u.company_id = c.id
WHERE u.tenant_id = ?
```

If `u` and `c` live on different shards, the database can't help you.
The fix is denormalization (copy the company name onto the user row),
or co-locating: put company AND its users on the same shard, keyed by
`company_id`. **Choose your shard key around the JOINS you can't
afford to lose**.

### Scatter/gather latency

```
P99(query) = MAX_i(P99(shard_i))
```

One slow shard kills every cross-shard query. Set per-shard timeouts.
Return partial results + a "shard X timed out" flag rather than
hanging the request.

### Schema migrations × N

Every DDL runs N times. If one shard fails halfway you have a
multi-version schema. The mitigations are: deploy DDL via a job that
runs serially and tracks per-shard state, keep migrations backward-
compatible for two releases (m18), and never run anything that takes
an `AccessExclusiveLock` (m19).

## Online resharding — the dance

You can't take downtime for the move. The accepted pattern:

```
1. Stand up the new shard (s3). Empty.

2. Update the router to know about the NEW topology, BUT keep
   reads routed to the old. The router now has two methods:
       oldShard(tenantId)  → in {s0, s1, s2}
       newShard(tenantId)  → in {s0, s1, s2, s3}

3. DUAL-WRITE: every write goes to BOTH oldShard AND newShard.
   This bounds the divergence — the new shard has all writes
   from "T+0" forward.

4. BACKFILL: a job copies rows from oldShard to newShard for
   keys where they differ. Run in chunks, idempotent on
   (tenant_id, source_row_id). Use cursor-based pagination
   (m08) to avoid lock contention.

5. VERIFY: once backfill is done, dual-read briefly — compare
   results from old and new; alert on mismatch. Fix data
   drift if any (usually the result of step 3 bugs).

6. CUTOVER: switch READS to newShard. Writes still dual-write
   so we can roll back quickly.

7. DRAIN: stop writes to the old copy. Wait until any in-flight
   reads from oldShard finish.

8. DROP: remove the old data. Reclaim the disk.

Total online time: hours to days, depending on volume and
backfill speed. Done correctly = zero downtime.
```

The `/shard/dual-write` endpoint shows step 3 in isolation so you can
see the two writes. The big honest detail it surfaces: **the two
writes are NOT atomic**. You need a reconciliation job that finds
mismatches (rows only on old, rows only on new) and fixes them.
Idempotency keys (`tenant_id` + `client_request_id`) make this safe to
retry.

## When app-level sharding is the right call

- Tenants are independent — no joins between them.
- The hot query path is "things for one tenant".
- One tenant comfortably fits on one node (no super-tenant problem).
- You can afford to operate N databases instead of one (backups,
  monitoring, alerts × N).

## When it isn't

- You have global queries you can't denormalize. → m26 (mat views),
  m27 (CQRS), or m24 (Vitess/Citus).
- Tenants are very uneven (one tenant is 80% of traffic). → super-
  tenants get their own shard; small tenants share. This is "tenant
  isolation tiers" and is fine — but you're now operating a tiered
  cluster.
- You haven't actually hit the limit of a single Postgres yet.
  Postgres can serve TB-scale workloads. Don't shard early.

## Production checklist

| Symptom                                          | Likely cause                                    | Fix                                                            |
|--------------------------------------------------|-------------------------------------------------|----------------------------------------------------------------|
| Resharding job moves 70%+ of keys                | MODULO routing                                  | Switch to consistent hashing BEFORE you need to reshard.       |
| One shard always hottest                         | Skewed key distribution (e.g. tenant_id=1)      | Salt the key; or move super-tenants to dedicated shards.       |
| Cross-shard query P99 explodes intermittently    | One slow shard                                  | Per-shard timeouts; partial results + flag missing shards.     |
| Dual-write succeeded on old, failed on new       | No idempotency / no recon                       | Idempotency keys; recon job that finds + replays orphans.      |
| App writes wrong shard after deploy              | Router/topology drift between hosts             | Topology in central config (DB, ZK, etcd). Don't hard-code.    |
| Schema migration partially applied across shards | No per-shard state tracking                     | Migration runner that records per-shard version; resumable.    |
| FK violation crossing shards                     | You can't have cross-shard FKs                  | Denormalize or co-locate via the same shard key.               |

## Files

```
src/main/java/com/claude/dbpoc/m23/
├── Application.java
├── DataSourceConfig.java                   # 4 HikariCP pools + map
├── routing/
│   └── ShardRouter.java                    # MODULO + CONSISTENT_HASH
├── service/
│   └── ShardingService.java                # seed, get, scatter/gather, reshard sim, dual-write
└── web/
    └── ShardingController.java             # /shard/{seed,tenant,scatter-gather,reshard-simulation,dual-write,strategy}
src/main/resources/
├── application.yml                         # 4 pools, port 8223
└── schema.sql                              # m23_s0..m23_s3 schemas
```

## Related modules

- **[m21 - read-replica](../21-db-read-replica/)** — every shard
  probably has its own replica. Now you have N×2 nodes to operate.
- **[m22 - table-partitioning](../22-db-table-partitioning/)** —
  partition a single table within a shard. Sometimes that's enough
  and you don't need sharding at all.
- **[m24 - vitess-citus](../24-db-vitess-citus/)** — when app-level
  sharding outgrows its usefulness.
- **[m26 - materialized-view](../26-db-materialized-view/)** — the
  cross-shard query problem partially solved by precomputing the
  cross-shard result.
- **[m27 - cqrs](../27-db-cqrs/)** — different read shape, often the
  right answer for cross-shard analytical queries.
- **[m30 - multi-tenancy](../30-db-multi-tenancy/)** — sharded
  multi-tenant is one of three multi-tenancy strategies.
