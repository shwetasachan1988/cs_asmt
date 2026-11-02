package com.sa.agentic.shortener.domain;

import java.time.Instant;

/** One redirect. The visitor is stored only as a salted daily hash - never the raw IP address. */
public record ClickEvent(long linkId, Instant clickedAt, String referrer, String userAgent, String visitorHash) {
}
