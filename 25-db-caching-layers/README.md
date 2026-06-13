# 25 - DB Caching Layers (L1 / L2 / Caffeine / Redis + the stampede fix)

> A cache hides latency from your service.
> A cache stampede hides it until the hot key expires, at which point
> you blow up the database.

## Thesis

There isn't "a cache" — there's a stack of them, each with its own
scope, lifetime, and failure mode. The Spring/Hibernate-flavored stack:

| Layer            | Scope               | Default? | Lifetime           | Hit cost  |
|------------------|---------------------|----------|--------------------|-----------|
| Hibernate **L1** | Per JPA Session     | on       | until tx ends      | ~10ns     |
| Hibernate **L2** | Per JVM (process)   | off      | TTL / size cap     | ~100ns    |
| **Query cache**  | Per JVM (results)   | off      | invalidate on write| ~100ns    |
| **Caffeine**     | Per JVM (manual)    | off      | TTL / size cap     | ~100ns    |
| **Redis**        | Distributed         | off      | TTL / size cap     | ~1ms      |
| **DB**           | the truth           | —        | forever            | ms – s    |

Each layer answers a different question. L1 makes repeated reads in
one transaction free. L2 makes them free across the whole JVM. Redis
makes them free across the cluster. None of them help if you forgot
to think about the **stampede**: N concurrent requests for an expired
hot key, all of them miss, all of them hammer the DB.

## Setup

```bash
cd ..
# Core profile already includes Redis.
docker compose --profile core up -d
./mvnw -pl 25-db-caching-layers -am spring-boot:run
```

App boots on **:8225**. Schema `m25_cache`. Redis on `:6379`.

## Run order

```bash
# 0. Seed 100 products.
curl -X POST 'localhost:8225/cache/seed?n=100' | jq

# 1. L1 cache — two findById in the SAME session → 1 DB query.
curl 'localhost:8225/cache/l1-same/42' | jq

# 2. L1 across sessions → 2 DB queries. L1 doesn't span sessions.
curl 'localhost:8225/cache/l1-cross/42' | jq

# 3. Caffeine — in-process cache. First call misses, second hits.
curl 'localhost:8225/cache/caffeine/42' | jq

# 4. Redis — distributed cache.
curl 'localhost:8225/cache/redis/42'    | jq

# 5. STAMPEDE — 50 concurrent requests on a cold key, NO protection.
curl -X POST 'localhost:8225/cache/stampede/42?concurrency=50&singleFlight=false' | jq
#   → "dbHits": ~50

# 6. STAMPEDE — same scenario, single-flight on.
curl -X POST 'localhost:8225/cache/stampede/42?concurrency=50&singleFlight=true'  | jq
#   → "dbHits": 1
```

## What each demo proves

### L1 — same session, free repeats

```json
{ "scenario": "L1 cache — two findById in the SAME session",
  "dbQueries": 1,
  "sameInstance": true }
```

Hibernate's per-session cache returns the SAME object reference on the
second `findById`. No DB. No copy. This is automatic — you didn't
configure anything, and you can't accidentally turn it off short of
calling `entityManager.clear()`. **The persistence context IS the L1
cache.**

### L1 — cross-session, no help

```json
{ "scenario": "L1 cache — same id, DIFFERENT sessions",
  "dbQueries": 2,
  "sameInstance": false }
```

Once the transaction ends, the session closes and the L1 cache is
gone. The next call rebuilds it from scratch.

To cache across sessions you need either Hibernate's **L2 cache**
(set up via `@Cacheable` on the entity + a JCache/Hazelcast/Caffeine
backend) or, more commonly, an **application-level cache** (Caffeine
or Redis) that the service explicitly populates.

### Caffeine — in-process

```json
{ "firstHit": false, "secondHit": true }
```

Configured in `CacheConfig`:

```java
Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(30))
        .maximumSize(10_000)
        .recordStats();
```

`expireAfterWrite` is the TTL. `maximumSize` caps memory. `recordStats`
lets you wire up a metrics endpoint (look at `Cache.stats()`).

Caffeine lives in this JVM only. With 4 app instances you have 4
caches, each warming up independently. Rolling deploys re-warm
everything. That's the trade-off vs Redis.

### Redis — distributed

```json
{ "firstHit": false, "secondHit": true }
```

Same shape, different reach. A second app instance hitting Redis sees
the same cached value. The cost is ~1ms per get and a Redis to
operate. And Redis brings its own failure modes:
- network blip → fall back to DB or fail open?
- OOM eviction → mass cold-cache, possible stampede
- replication lag during failover → stale reads

### Stampede — the lesson that costs the most

The fundamental cache stampede pattern:

```
T+0    100 requests for /product/42, all miss the cache.
T+0    All 100 query the DB. DB CPU spikes.
T+50ms First request finishes, populates the cache.
T+50ms The other 99 — already in their own DB query — finish too.
       99 DB calls thrown away.
```

This is the **thundering herd / dogpile / stampede**. Real example:
your `getProduct(42)` is cached with TTL=60s. Every minute when it
expires, every concurrent reader misses, hammers the DB, and the DB's
connection pool empties for a second. Latency spikes appear in your
P99 every 60s with no obvious cause.

Single-flight (a.k.a. request coalescing) fixes it:

```java
// at most ONE in-flight load per key
ConcurrentMap<Long, CompletableFuture<Product>> inflight = new ConcurrentHashMap<>();

Product load(Long id) {
    if (cache.has(id)) return cache.get(id);
    var myFuture = new CompletableFuture<Product>();
    var existing = inflight.putIfAbsent(id, myFuture);
    if (existing != null) return existing.get();      // ⬅ join the leader
    try {
        Product fresh = db.load(id);
        cache.put(id, fresh);
        myFuture.complete(fresh);
        return fresh;
    } finally {
        inflight.remove(id, myFuture);
    }
}
```

Result: 1 DB hit for any number of concurrent misses on the same key.
The other threads await the first thread's `CompletableFuture` and
return its result.

**Caffeine's built-in `LoadingCache`** does this for you. So does
**Guava's `CacheLoader`**. The `@Cacheable` annotation does NOT by
default — you need either `sync = true` or a `LoadingCache` backend.

## Other stampede patterns (and when to pick which)

| Pattern             | Mechanism                              | When                                        |
|---------------------|----------------------------------------|---------------------------------------------|
| **Single-flight**   | In-flight Future per key               | Default — works for almost everything.      |
| **Probabilistic early refresh** | Refresh before TTL with probability(elapsed/TTL)^β | TTL too low to single-flight under high QPS. |
| **Refresh-ahead**   | Background thread refreshes hot keys before they expire | Cache misses are user-visible and slow.     |
| **Per-key mutex (Redis SET NX)** | Distributed lock; loser polls | Distributed cache + you want one DB load *across* the cluster. |
| **Stale-while-revalidate** | Serve stale until refresh finishes | Strong availability bias; some staleness OK. |

For a one-app-process cache, **single-flight is the answer**. For
Redis, layer it: a distributed `SET NX` lock or atomic `INCR` for the
key prevents N processes from all loading.

## L2 cache — why we didn't enable it by default

The Hibernate L2 cache is powerful — `@Cache(usage = READ_WRITE)` on
an entity makes it cluster-cacheable — but the operational footguns
are real:

- **Invalidation on writes is per-entity**, not per-query. A bulk
  `UPDATE` via JPQL doesn't invalidate the L2 — you have to
  `evict()` manually or use `@Modifying(clearAutomatically=true)`.
- **The query cache is a footgun**: it caches result sets keyed on
  the literal query text. Any insert/update/delete on a referenced
  table invalidates the whole query-cache region. On a write-heavy
  table the query cache is a slow leak.
- **Cluster-wide invalidation** needs a sync mechanism (Hazelcast,
  Infinispan, Redis-backed JCache). Now you have two distributed
  systems to operate.

**Prefer an explicit application cache** (Caffeine / Redis via
`@Cacheable` or manual `CacheManager` calls). It's easier to reason
about, easier to invalidate, and easier to debug.

## TTL is not the answer; "fresh enough" is

Tempting to set TTL=300s "to reduce DB load". The trap:

- TTL too short → stampedes every TTL window.
- TTL too long  → stale data the user notices.

Better questions:
1. **What's the freshness SLO** for this data? (Pricing page: seconds.
   Footer link list: minutes. Country list: hours.)
2. **What invalidates this data?** If you know on write, **evict on
   write** instead of relying on TTL.
3. **Is it hot enough to need a cache at all?** If a row is read 5×
   per minute, the DB's own buffer pool already caches it for free.
   Caching is for the read amplification, not the row.

## Production checklist

| Symptom                                              | Likely cause                          | Fix                                                         |
|------------------------------------------------------|---------------------------------------|-------------------------------------------------------------|
| P99 spikes every TTL interval                        | Stampede on hot key                   | Single-flight; or `Caffeine.refreshAfterWrite`.             |
| Random latency cliff right after a deploy            | Cold local cache                      | Move to Redis; or pre-warm on startup.                      |
| "Stale data" after a write                           | Cache populated before write commit   | Evict in the same tx (after commit, not before).            |
| OOM in the app process                               | Unbounded cache                       | `maximumSize` on Caffeine, or use Redis.                    |
| Redis blip → app errors                              | No fall-back                          | `CircuitBreaker(redis) → loadFromDb` on open circuit.       |
| Query cache invalidation storms                      | Bulk UPDATE/DELETE on cached table    | Drop the query cache; use explicit `@Cacheable` instead.    |
| L2 inconsistency across cluster                      | L2 + no distributed invalidation      | Pick a real distributed cache; don't half-do L2.            |

## Files

```
src/main/java/com/claude/dbpoc/m25/
├── Application.java                       # @EnableCaching
├── CacheConfig.java                       # Caffeine + Redis CacheManagers
├── domain/Product.java
├── repo/ProductRepository.java
├── service/
│   ├── ProductService.java                # DB + Caffeine + Redis paths
│   └── CachingDemoService.java            # L1, stampede demos
└── web/
    └── CacheController.java               # /cache/{l1-same,l1-cross,caffeine,redis,stampede}
src/main/resources/
├── application.yml                        # port 8225, redis localhost:6379
└── schema.sql                             # m25_cache.product
```

## Related modules

- **[m05 - jpa-n-plus-one](../05-jpa-n-plus-one/)** — the L1 cache
  is the reason `findById` in a loop is fast on hit and slow on miss.
- **[m21 - read-replica](../21-db-read-replica/)** — caching and
  read-replica solve overlapping problems. Pick the one that fits
  your freshness SLO.
- **[m26 - materialized-view](../26-db-materialized-view/)** — a
  cache that the DB maintains for you, with hard correctness
  guarantees on refresh.
- **[m27 - cqrs](../27-db-cqrs/)** — the "cache" pushed all the way
  into a separate read model.
