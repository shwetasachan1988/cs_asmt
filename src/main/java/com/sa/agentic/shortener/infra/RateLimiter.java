package com.sa.agentic.shortener.infra;

/** Fixed-window limiter. Fails open on infrastructure errors: availability beats strictness for create calls. */
public interface RateLimiter {

    boolean tryAcquire(String key);
}
