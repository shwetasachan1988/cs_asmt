package com.sa.agentic.shortener.infra;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "shortener.cache", havingValue = "redis", matchIfMissing = true)
public class RedisCacheStore implements CacheStore {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheStore.class);
    private final StringRedisTemplate redis;

    public RedisCacheStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key));
        } catch (RuntimeException e) {
            log.warn("cache get failed, falling back to DB: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            log.warn("cache put failed: {}", e.toString());
        }
    }

    @Override
    public void evict(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            log.warn("cache evict failed: {}", e.toString());
        }
    }
}
