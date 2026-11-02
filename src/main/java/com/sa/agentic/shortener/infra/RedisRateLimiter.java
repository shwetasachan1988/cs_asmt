package com.sa.agentic.shortener.infra;

import com.sa.agentic.shortener.ShortenerProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "shortener.cache", havingValue = "redis", matchIfMissing = true)
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private final StringRedisTemplate redis;
    private final ShortenerProperties props;

    public RedisRateLimiter(StringRedisTemplate redis, ShortenerProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public boolean tryAcquire(String key) {
        try {
            String k = "rl:" + key;
            Long count = redis.opsForValue().increment(k);
            if (count != null && count == 1L) {
                redis.expire(k, Duration.ofSeconds(props.rateLimit().windowSeconds()));
            }
            return count == null || count <= props.rateLimit().maxRequests();
        } catch (RuntimeException e) {
            log.warn("rate limiter unavailable, failing open: {}", e.toString());
            return true;
        }
    }
}
