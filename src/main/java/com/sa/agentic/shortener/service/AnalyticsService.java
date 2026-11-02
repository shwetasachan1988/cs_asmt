package com.sa.agentic.shortener.service;

import com.sa.agentic.shortener.ShortenerProperties;
import com.sa.agentic.shortener.domain.ClickEvent;
import com.sa.agentic.shortener.infra.ClickRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Click capture is off the redirect path: a bounded queue + small worker pool. When the queue is
 * full the event is dropped and counted - analytics is best-effort, redirects are not.
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    public record Stats(String code, long totalClicks, long uniqueVisitors,
                        Map<String, Long> clicksPerDayLast7, Map<String, Long> topReferrers) {
    }

    private final ClickRepository clicks;
    private final MeterRegistry metrics;
    private final ThreadPoolExecutor pool;

    public AnalyticsService(ClickRepository clicks, ShortenerProperties props, MeterRegistry metrics) {
        this.clicks = clicks;
        this.metrics = metrics;
        int workers = Math.max(1, props.analytics().workers());
        this.pool = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(props.analytics().queueCapacity()),
                r -> {
                    Thread t = new Thread(r, "click-writer");
                    t.setDaemon(true);
                    return t;
                },
                (r, ex) -> metrics.counter("shortener.clicks.dropped").increment());
    }

    public void record(long linkId, String referrer, String userAgent, String clientIp) {
        ClickEvent event = new ClickEvent(linkId, Instant.now(), truncate(referrer, 512), truncate(userAgent, 256),
                visitorHash(clientIp));
        pool.execute(() -> {
            try {
                clicks.insert(event);
                metrics.counter("shortener.clicks.recorded").increment();
            } catch (RuntimeException e) {
                metrics.counter("shortener.clicks.failed").increment();
                log.warn("click write failed: {}", e.toString());
            }
        });
    }

    public Stats stats(String code, long linkId) {
        Instant from = Instant.now().minus(7, ChronoUnit.DAYS);
        return new Stats(code, clicks.total(linkId), clicks.uniqueVisitors(linkId),
                clicks.clicksPerDay(linkId, from), clicks.topReferrers(linkId, 5));
    }

    /** Blocks until queued writes are flushed; used by tests and graceful shutdown. */
    public boolean drain(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!pool.getQueue().isEmpty() || pool.getActiveCount() > 0) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    /** Salted with the UTC date so a visitor cannot be tracked across days; raw IPs are never persisted. */
    static String visitorHash(String ip) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String salted = LocalDate.now(ZoneOffset.UTC) + "|" + (ip == null ? "unknown" : ip);
            return HexFormat.of().formatHex(md.digest(salted.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
