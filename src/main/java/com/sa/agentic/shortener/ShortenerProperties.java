package com.sa.agentic.shortener;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        String baseUrl,
        int codeLength,
        int maxUrlLength,
        long cacheTtlSeconds,
        boolean allowPrivateTargets,
        RateLimit rateLimit,
        Analytics analytics) {

    public record RateLimit(boolean enabled, int maxRequests, int windowSeconds) {
    }

    public record Analytics(int queueCapacity, int workers) {
    }
}
