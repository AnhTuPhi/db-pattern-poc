package com.claude.dbpoc.m25;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;

/**
 * Two cache managers, side by side, picked by name in the service:
 *
 *   caffeineCacheManager — local, in-process. TTL 30s, max 10k entries.
 *                          Fast (~100ns/get), but each app instance has
 *                          its own cache. Cold starts and rolling deploys
 *                          warm them up independently.
 *
 *   redisCacheManager    — distributed. All app instances share. ~1ms/get
 *                          locally, more across regions. Survives deploys.
 *                          Has its own failure modes: stampede, eviction,
 *                          Redis unavailability.
 */
@Configuration
public class CacheConfig {

    @Bean @Primary
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager("products");
        mgr.setCaffeine(Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .recordStats());
        return mgr;
    }

    @Bean
    public CacheManager redisCacheManager(RedisConnectionFactory cf) {
        RedisCacheConfiguration cfg = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofSeconds(30))
            .disableCachingNullValues();
        return RedisCacheManager.builder(cf).cacheDefaults(cfg).build();
    }
}
