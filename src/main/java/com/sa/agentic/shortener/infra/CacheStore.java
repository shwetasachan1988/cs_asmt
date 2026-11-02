package com.sa.agentic.shortener.infra;

import java.time.Duration;
import java.util.Optional;

/** Port for the redirect cache. Implementations must never throw: a cache outage degrades to DB reads. */
public interface CacheStore {

    Optional<String> get(String key);

    void put(String key, String value, Duration ttl);

    void evict(String key);
}
