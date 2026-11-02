package com.sa.agentic.shortener.infra;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Test/dev adapter ({@code shortener.cache=memory}); also documents the CacheStore contract. */
@Component
@ConditionalOnProperty(name = "shortener.cache", havingValue = "memory")
public class InMemoryCacheStore implements CacheStore {

    private record Entry(String value, Instant expiresAt) {
    }

    private final Map<String, Entry> map = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String key) {
        Entry e = map.get(key);
        if (e == null) {
            return Optional.empty();
        }
        if (e.expiresAt().isBefore(Instant.now())) {
            map.remove(key);
            return Optional.empty();
        }
        return Optional.of(e.value());
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        map.put(key, new Entry(value, Instant.now().plus(ttl)));
    }

    @Override
    public void evict(String key) {
        map.remove(key);
    }
}
