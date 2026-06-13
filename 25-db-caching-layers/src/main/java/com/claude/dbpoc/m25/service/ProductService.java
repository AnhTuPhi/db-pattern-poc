package com.claude.dbpoc.m25.service;

import com.claude.dbpoc.m25.domain.Product;
import com.claude.dbpoc.m25.repo.ProductRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The repository-level service. We deliberately bypass {@code @Cacheable}
 * annotations and drive the caches manually so it's obvious WHICH cache
 * served the hit. The five demos in {@link CachingDemoService} call into
 * here.
 */
@Service
public class ProductService {

    private final ProductRepository repo;
    private final CacheManager caffeine;
    private final CacheManager redis;

    public ProductService(ProductRepository repo,
                          @Qualifier("caffeineCacheManager") CacheManager caffeine,
                          @Qualifier("redisCacheManager")   CacheManager redis) {
        this.repo = repo;
        this.caffeine = caffeine;
        this.redis = redis;
    }

    /** Goes straight to the DB. The thing every cache is trying to avoid. */
    @Transactional(readOnly = true)
    public Product loadFromDb(Long id) {
        return repo.findById(id).orElseThrow();
    }

    /** Find through Caffeine. Returns whether it was a hit and the value. */
    public CacheResult viaCaffeine(Long id) {
        var cache = caffeine.getCache("products");
        var hit = cache.get(id);
        if (hit != null) return new CacheResult(true, (Product) hit.get());
        Product fresh = loadFromDb(id);
        cache.put(id, fresh);
        return new CacheResult(false, fresh);
    }

    /** Find through Redis. */
    public CacheResult viaRedis(Long id) {
        var cache = redis.getCache("products");
        var hit = cache.get(id);
        if (hit != null) return new CacheResult(true, (Product) hit.get());
        Product fresh = loadFromDb(id);
        cache.put(id, fresh);
        return new CacheResult(false, fresh);
    }

    public void invalidateAll() {
        Optional.ofNullable(caffeine.getCache("products")).ifPresent(c -> c.clear());
        Optional.ofNullable(redis.getCache("products")).ifPresent(c -> c.clear());
    }

    public record CacheResult(boolean hit, Product product) {}
}
