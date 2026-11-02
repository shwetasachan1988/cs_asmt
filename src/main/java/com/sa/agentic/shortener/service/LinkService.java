package com.sa.agentic.shortener.service;

import com.sa.agentic.shortener.ShortenerProperties;
import com.sa.agentic.shortener.domain.LinkException;
import com.sa.agentic.shortener.domain.LinkException.Kind;
import com.sa.agentic.shortener.domain.ResolvedLink;
import com.sa.agentic.shortener.domain.ShortLink;
import com.sa.agentic.shortener.infra.CacheStore;
import com.sa.agentic.shortener.infra.LinkRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class LinkService {

    private static final String CACHE_PREFIX = "link:";
    /** Negative-cache marker; short TTL stops unknown codes from hammering the DB (cache penetration). */
    private static final String NOT_FOUND = "!";
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(30);

    private final LinkRepository links;
    private final CacheStore cache;
    private final CodeGenerator codes;
    private final UrlValidator validator;
    private final ShortenerProperties props;
    private final MeterRegistry metrics;

    public LinkService(LinkRepository links, CacheStore cache, CodeGenerator codes, UrlValidator validator,
                       ShortenerProperties props, MeterRegistry metrics) {
        this.links = links;
        this.cache = cache;
        this.codes = codes;
        this.validator = validator;
        this.props = props;
        this.metrics = metrics;
    }

    public ShortLink create(String rawUrl) {
        String url = validator.validate(rawUrl);
        long id = links.nextId();
        ShortLink link = new ShortLink(id, codes.fromId(id), url, Instant.now().truncatedTo(ChronoUnit.MILLIS), true);
        links.insert(link);
        cache.put(CACHE_PREFIX + link.code(), new ResolvedLink(id, url).serialize(), Duration.ofSeconds(props.cacheTtlSeconds()));
        metrics.counter("shortener.links.created").increment();
        return link;
    }

    public ShortLink get(String code) {
        return links.findByCode(code).filter(ShortLink::active)
                .orElseThrow(() -> new LinkException(Kind.NOT_FOUND, "short link not found: " + code));
    }

    /** Redirect hot path: cache first, DB on miss, negative-cache unknown codes. */
    public ResolvedLink resolve(String code) {
        Optional<String> cached = cache.get(CACHE_PREFIX + code);
        if (cached.isPresent()) {
            if (NOT_FOUND.equals(cached.get())) {
                metrics.counter("shortener.redirect", "result", "negative_hit").increment();
                throw notFound(code);
            }
            try {
                ResolvedLink hit = ResolvedLink.parse(cached.get());
                metrics.counter("shortener.redirect", "result", "cache_hit").increment();
                return hit;
            } catch (RuntimeException corrupt) {
                cache.evict(CACHE_PREFIX + code);
            }
        }
        Optional<ShortLink> found = links.findByCode(code).filter(ShortLink::active);
        if (found.isEmpty()) {
            cache.put(CACHE_PREFIX + code, NOT_FOUND, NEGATIVE_TTL);
            metrics.counter("shortener.redirect", "result", "not_found").increment();
            throw notFound(code);
        }
        ResolvedLink resolved = new ResolvedLink(found.get().id(), found.get().targetUrl());
        cache.put(CACHE_PREFIX + code, resolved.serialize(), Duration.ofSeconds(props.cacheTtlSeconds()));
        metrics.counter("shortener.redirect", "result", "db_hit").increment();
        return resolved;
    }

    public void delete(String code) {
        if (!links.deactivate(code)) {
            throw notFound(code);
        }
        cache.evict(CACHE_PREFIX + code);
    }

    private static LinkException notFound(String code) {
        return new LinkException(Kind.NOT_FOUND, "short link not found: " + code);
    }
}
