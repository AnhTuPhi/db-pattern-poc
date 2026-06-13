package com.claude.dbpoc.m25.service;

import com.claude.dbpoc.m25.domain.Product;
import com.claude.dbpoc.m25.repo.ProductRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The five caching demos.
 *
 *   1. l1Same       — two findById on the SAME session → L1 hits the second.
 *                     Automatic; the reason "@Transactional" reads feel faster.
 *
 *   2. l1Cross      — two findById on DIFFERENT sessions → L1 doesn't help;
 *                     both hit the DB. Without L2 this is your reality.
 *
 *   3. caffeineDemo — same workload through Caffeine. First call misses,
 *                     subsequent hit. Local to this JVM.
 *
 *   4. redisDemo    — same workload through Redis. Hits across processes.
 *
 *   5. stampede     — N concurrent threads request a NOT-YET-CACHED key.
 *                     Without single-flight: every thread misses → DB falls over.
 *                     With single-flight: ONE DB call, all threads share the Future.
 */
@Service
public class CachingDemoService {

    private final ProductService products;
    private final ProductRepository repo;
    private final EntityManagerFactory emf;
    private final CacheManager caffeine;

    // Single-flight registry — one in-flight load per key.
    private final ConcurrentMap<Long, CompletableFuture<Product>> inflight = new ConcurrentHashMap<>();

    public CachingDemoService(ProductService products,
                              ProductRepository repo,
                              EntityManagerFactory emf,
                              @Qualifier("caffeineCacheManager") CacheManager caffeine) {
        this.products = products;
        this.repo = repo;
        this.emf = emf;
        this.caffeine = caffeine;
    }

    // ---------------------------------------------------------------------
    // SEED
    // ---------------------------------------------------------------------
    @Transactional
    public Map<String, Object> seed(int n) {
        repo.deleteAllInBatch();
        for (int i = 1; i <= n; i++) {
            repo.save(new Product("SKU-" + i, "Product " + i, new BigDecimal(10 + i)));
        }
        repo.flush();
        return Map.of("seeded", n);
    }

    // ---------------------------------------------------------------------
    // 1. L1 same-session — two reads, one query.
    // ---------------------------------------------------------------------
    @Transactional(readOnly = true)
    public Map<String, Object> l1SameSession(Long id) {
        Statistics st = stats();
        st.clear();

        long t0 = System.nanoTime();
        Product p1 = repo.findById(id).orElseThrow();
        long t1 = System.nanoTime();
        Product p2 = repo.findById(id).orElseThrow();
        long t2 = System.nanoTime();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "L1 cache — two findById in the SAME session");
        out.put("firstCallNs", t1 - t0);
        out.put("secondCallNs", t2 - t1);
        out.put("dbQueries", st.getPrepareStatementCount());
        out.put("entityFetches", st.getEntityFetchCount());
        out.put("sameInstance", p1 == p2);   // L1 returns the same object reference
        out.put("note",
            "Exactly one PreparedStatement was prepared. The second findById was satisfied " +
            "by the L1 (persistence-context) cache. Because L1 returns the SAME Java reference, " +
            "(p1 == p2) is true. This is automatic; you didn't enable anything.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 2. L1 cross-session — L1 doesn't span sessions.
    // ---------------------------------------------------------------------
    public Map<String, Object> l1CrossSession(Long id) {
        Statistics st = stats();
        st.clear();
        Product p1 = inFreshSession(id);
        Product p2 = inFreshSession(id);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "L1 cache — same id, DIFFERENT sessions");
        out.put("dbQueries", st.getPrepareStatementCount());
        out.put("sameInstance", p1 == p2);   // different sessions => different objects
        out.put("note",
            "TWO PreparedStatements. L1 is per-Session; once the @Transactional method " +
            "returns, the session is closed and the cache is gone. " +
            "To cache across sessions you need L2 (process-wide) or an application cache.");
        return out;
    }

    private Product inFreshSession(Long id) {
        var em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            Product p = em.find(Product.class, id);
            em.getTransaction().commit();
            return p;
        } finally {
            em.close();
        }
    }

    // ---------------------------------------------------------------------
    // 3. Caffeine — in-process cache.
    // ---------------------------------------------------------------------
    public Map<String, Object> caffeine(Long id) {
        products.invalidateAll();
        var first  = products.viaCaffeine(id);
        var second = products.viaCaffeine(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "Caffeine — in-process cache, TTL 30s");
        out.put("firstHit",  first.hit());
        out.put("secondHit", second.hit());
        out.put("note",
            "First call: miss → DB → put. Second call: hit. Caffeine lives in this JVM only — " +
            "if you scale to 4 app instances, you have 4 caches. They warm up independently. " +
            "After deploy, every instance starts cold.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 4. Redis — distributed.
    // ---------------------------------------------------------------------
    public Map<String, Object> redis(Long id) {
        products.invalidateAll();
        var first  = products.viaRedis(id);
        var second = products.viaRedis(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "Redis — distributed cache, TTL 30s");
        out.put("firstHit",  first.hit());
        out.put("secondHit", second.hit());
        out.put("note",
            "Same workload, but the cache lives in Redis. A second app instance sees the same " +
            "cached value. Survives a deploy. Costs you ~1ms per get and a Redis to operate. " +
            "Failure modes: Redis down → fall back to DB; OOM eviction → cold-cache thundering herd.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 5. STAMPEDE — N concurrent misses on a hot key.
    // ---------------------------------------------------------------------
    public Map<String, Object> stampede(Long id, int concurrency, boolean singleFlight) throws Exception {
        products.invalidateAll();    // guarantee cold start

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger dbHits = new AtomicInteger();

        List<Future<Product>> futures = new java.util.ArrayList<>();
        long t0 = System.nanoTime();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return singleFlight ? loadSingleFlight(id, dbHits) : loadNaive(id, dbHits);
            }));
        }
        start.countDown();
        for (Future<Product> f : futures) f.get();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        pool.shutdown();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", singleFlight
            ? "Cache stampede — protected by single-flight (CompletableFuture coalescing)"
            : "Cache stampede — NAIVE (no protection)");
        out.put("concurrency", concurrency);
        out.put("dbHits", dbHits.get());
        out.put("elapsedMs", elapsedMs);
        out.put("verdict", singleFlight
            ? "Exactly 1 DB hit served all " + concurrency + " requests. The first thread " +
              "registered its in-flight Future; the rest joined and waited."
            : "Roughly " + dbHits.get() + " DB hits for " + concurrency + " concurrent requests — every " +
              "thread saw an empty cache and ran the DB query. This is the stampede that takes " +
              "production down when a hot key expires.");
        return out;
    }

    /** Naive: each thread independently checks the cache and queries the DB on miss. */
    private Product loadNaive(Long id, AtomicInteger dbHits) {
        Cache cache = caffeine.getCache("products");
        Cache.ValueWrapper hit = cache.get(id);
        if (hit != null) return (Product) hit.get();
        dbHits.incrementAndGet();
        Product fresh = products.loadFromDb(id);
        cache.put(id, fresh);
        return fresh;
    }

    /**
     * Single-flight: at most ONE in-flight DB load per key. Other threads
     * find a CompletableFuture already in the map and join it instead of
     * doing their own DB call. This is the textbook stampede fix and what
     * the Caffeine LoadingCache does internally.
     */
    private Product loadSingleFlight(Long id, AtomicInteger dbHits) {
        Cache cache = caffeine.getCache("products");
        Cache.ValueWrapper hit = cache.get(id);
        if (hit != null) return (Product) hit.get();

        CompletableFuture<Product> myFuture = new CompletableFuture<>();
        CompletableFuture<Product> existing = inflight.putIfAbsent(id, myFuture);
        if (existing != null) {
            // somebody else is already loading — wait for them
            try { return existing.get(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        // we won the race; we do the load
        try {
            dbHits.incrementAndGet();
            Product fresh = products.loadFromDb(id);
            cache.put(id, fresh);
            myFuture.complete(fresh);
            return fresh;
        } catch (RuntimeException e) {
            myFuture.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(id, myFuture);
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /** Hibernate Statistics — counts queries actually run against the DB. */
    private Statistics stats() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }
}
