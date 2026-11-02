package com.sa.agentic.shortener.domain;

import java.time.Instant;

public record ShortLink(long id, String code, String targetUrl, Instant createdAt, boolean active) {
}
