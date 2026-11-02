package com.sa.agentic.shortener.infra;

import com.sa.agentic.shortener.ShortenerProperties;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "shortener.cache", havingValue = "memory")
public class InMemoryRateLimiter implements RateLimiter {

    private record Window(long startEpoch, AtomicInteger count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final ShortenerProperties props;

    public InMemoryRateLimiter(ShortenerProperties props) {
        this.props = props;
    }

    @Override
    public boolean tryAcquire(String key) {
        long now = Instant.now().getEpochSecond();
        int windowSeconds = props.rateLimit().windowSeconds();
        Window w = windows.compute(key, (k, old) ->
                old == null || now - old.startEpoch() >= windowSeconds ? new Window(now, new AtomicInteger()) : old);
        return w.count().incrementAndGet() <= props.rateLimit().maxRequests();
    }
}
