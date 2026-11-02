package com.sa.agentic.orchestrator.agent;

/**
 * Source the codegen agents emit. Each module is pure computation (restricted JDK subset, enforced by
 * UnsafeCodePolicy) plus a {@code *Check} class exposing {@code run()} that returns the list of failed expectations -
 * which the unit-test agent compiles and executes for real.
 */
final class CodeTemplates {

    private CodeTemplates() {
    }

    static final String BULK_PLANNER = """
            package generated.bulk;

            import java.util.ArrayList;
            import java.util.LinkedHashMap;
            import java.util.List;
            import java.util.Map;
            import java.util.function.Predicate;

            /** Planning logic for POST /api/v1/links/bulk. Pure and side-effect free, so it is trivially testable. */
            public final class BulkLinkPlanner {

                public static final int MAX_BATCH = 100;

                public enum Status { CREATE, DUPLICATE, REJECTED }

                public record Item(String url, Status status, String reason, int duplicateOf) {
                }

                private BulkLinkPlanner() {
                }

                /** One result per input item, in input order; a bad item never fails its neighbours. */
                public static List<Item> plan(List<String> urls, Predicate<String> isValid) {
                    if (urls == null || urls.isEmpty()) {
                        throw new IllegalArgumentException("batch must not be empty");
                    }
                    if (urls.size() > MAX_BATCH) {
                        throw new IllegalArgumentException("batch exceeds " + MAX_BATCH + " items");
                    }
                    List<Item> out = new ArrayList<>(urls.size());
                    Map<String, Integer> firstSeen = new LinkedHashMap<>();
                    for (int i = 0; i < urls.size(); i++) {
                        String raw = urls.get(i);
                        String url = raw == null ? "" : raw.trim();
                        if (url.isEmpty() || !isValid.test(url)) {
                            out.add(new Item(raw, Status.REJECTED, "invalid url", -1));
                            continue;
                        }
                        Integer first = firstSeen.putIfAbsent(url, i);
                        out.add(first != null
                                ? new Item(url, Status.DUPLICATE, "same as item " + first, first)
                                : new Item(url, Status.CREATE, null, -1));
                    }
                    return out;
                }
            }
            """;

    static final String BULK_CHECK = """
            package generated.bulk;

            import java.util.ArrayList;
            import java.util.List;
            import java.util.function.Predicate;

            public final class BulkLinkPlannerCheck {

                private BulkLinkPlannerCheck() {
                }

                public static List<String> run() {
                    List<String> f = new ArrayList<>();
                    Predicate<String> valid = u -> u.startsWith("https://");

                    var plan = BulkLinkPlanner.plan(List.of("https://a.com", "bad", "https://a.com", " https://b.com "), valid);
                    expect(f, plan.size() == 4, "one result per input item");
                    expect(f, plan.get(0).status() == BulkLinkPlanner.Status.CREATE, "first valid url is created");
                    expect(f, plan.get(1).status() == BulkLinkPlanner.Status.REJECTED, "a bad item is rejected on its own");
                    expect(f, plan.get(2).status() == BulkLinkPlanner.Status.DUPLICATE, "repeat url is flagged duplicate");
                    expect(f, plan.get(2).duplicateOf() == 0, "duplicate points at the first occurrence");
                    expect(f, plan.get(3).status() == BulkLinkPlanner.Status.CREATE, "whitespace is trimmed");

                    expectThrows(f, () -> BulkLinkPlanner.plan(List.of(), valid), "empty batch must be rejected");
                    expectThrows(f, () -> BulkLinkPlanner.plan(null, valid), "null batch must be rejected");

                    List<String> max = new ArrayList<>();
                    for (int i = 0; i < 100; i++) {
                        max.add("https://example.com/" + i);
                    }
                    expect(f, BulkLinkPlanner.plan(max, valid).size() == 100, "exactly 100 items is allowed");
                    max.add("https://example.com/101");
                    expectThrows(f, () -> BulkLinkPlanner.plan(max, valid), "101 items must be rejected");
                    return f;
                }

                private static void expect(List<String> failures, boolean ok, String message) {
                    if (!ok) {
                        failures.add(message);
                    }
                }

                private static void expectThrows(List<String> failures, Runnable r, String message) {
                    try {
                        r.run();
                        failures.add(message);
                    } catch (IllegalArgumentException expected) {
                        // ok
                    }
                }
            }
            """;

    static final String EXPIRY_POLICY = """
            package generated.expiry;

            import java.time.Duration;
            import java.time.Instant;

            /** Decides whether a link is expired and whether a requested expiry is acceptable. */
            public final class ExpiryPolicy {

                public static final Duration MAX_LIFETIME = Duration.ofDays(5 * 365);

                private ExpiryPolicy() {
                }

                /** NULL means the link never expires; the expiry instant itself already counts as expired. */
                public static boolean isExpired(Instant expiresAt, Instant now) {
                    return expiresAt != null && !now.isBefore(expiresAt);
                }

                public static void validateRequested(Instant requested, Instant now) {
                    if (requested == null) {
                        return;
                    }
                    if (!requested.isAfter(now)) {
                        throw new IllegalArgumentException("expiresAt must be in the future");
                    }
                    if (requested.isAfter(now.plus(MAX_LIFETIME))) {
                        throw new IllegalArgumentException("expiresAt must be within 5 years");
                    }
                }
            }
            """;

    static final String EXPIRY_CHECK = """
            package generated.expiry;

            import java.time.Duration;
            import java.time.Instant;
            import java.util.ArrayList;
            import java.util.List;

            public final class ExpiryPolicyCheck {

                private ExpiryPolicyCheck() {
                }

                public static List<String> run() {
                    List<String> f = new ArrayList<>();
                    Instant now = Instant.parse("2030-01-01T00:00:00Z");
                    expect(f, !ExpiryPolicy.isExpired(null, now), "a link without expiry never expires");
                    expect(f, !ExpiryPolicy.isExpired(now.plusSeconds(1), now), "a link is valid before its expiry");
                    expect(f, ExpiryPolicy.isExpired(now, now), "a link is expired at the exact expiry instant");
                    expect(f, ExpiryPolicy.isExpired(now.minusSeconds(1), now), "a link is expired after its expiry");
                    expectThrows(f, () -> ExpiryPolicy.validateRequested(now, now), "expiry equal to now is rejected");
                    expectThrows(f, () -> ExpiryPolicy.validateRequested(now.minusSeconds(60), now), "past expiry is rejected");
                    expectThrows(f, () -> ExpiryPolicy.validateRequested(now.plus(Duration.ofDays(5 * 365 + 1)), now),
                            "expiry beyond 5 years is rejected");
                    try {
                        ExpiryPolicy.validateRequested(null, now);
                        ExpiryPolicy.validateRequested(now.plus(Duration.ofDays(30)), now);
                    } catch (RuntimeException e) {
                        f.add("null and reasonable expiries must be accepted: " + e.getMessage());
                    }
                    return f;
                }

                private static void expect(List<String> failures, boolean ok, String message) {
                    if (!ok) {
                        failures.add(message);
                    }
                }

                private static void expectThrows(List<String> failures, Runnable r, String message) {
                    try {
                        r.run();
                        failures.add(message);
                    } catch (IllegalArgumentException expected) {
                        // ok
                    }
                }
            }
            """;

    static final String CACHE_TTL = """
            package generated.expiry;

            import java.time.Duration;
            import java.time.Instant;

            /** A cached redirect must never outlive the link's expiry. */
            public final class CacheTtlCalculator {

                private CacheTtlCalculator() {
                }

                public static Duration ttlFor(Instant expiresAt, Instant now, Duration defaultTtl) {
                    if (expiresAt == null) {
                        return defaultTtl;
                    }
                    Duration remaining = Duration.between(now, expiresAt);
                    if (remaining.isNegative() || remaining.isZero()) {
                        return Duration.ZERO;
                    }
                    return remaining.compareTo(defaultTtl) < 0 ? remaining : defaultTtl;
                }
            }
            """;

    static final String CACHE_TTL_CHECK = """
            package generated.expiry;

            import java.time.Duration;
            import java.time.Instant;
            import java.util.ArrayList;
            import java.util.List;

            public final class CacheTtlCalculatorCheck {

                private CacheTtlCalculatorCheck() {
                }

                public static List<String> run() {
                    List<String> f = new ArrayList<>();
                    Instant now = Instant.parse("2030-01-01T00:00:00Z");
                    Duration hour = Duration.ofHours(1);
                    expect(f, CacheTtlCalculator.ttlFor(null, now, hour).equals(hour), "no expiry -> default TTL");
                    expect(f, CacheTtlCalculator.ttlFor(now.plus(Duration.ofDays(2)), now, hour).equals(hour),
                            "far expiry -> default TTL");
                    expect(f, CacheTtlCalculator.ttlFor(now.plusSeconds(10), now, hour).equals(Duration.ofSeconds(10)),
                            "near expiry -> TTL capped to the remaining time");
                    expect(f, CacheTtlCalculator.ttlFor(now, now, hour).isZero(), "expiring now -> do not cache");
                    expect(f, CacheTtlCalculator.ttlFor(now.minusSeconds(5), now, hour).isZero(), "already expired -> do not cache");
                    return f;
                }

                private static void expect(List<String> failures, boolean ok, String message) {
                    if (!ok) {
                        failures.add(message);
                    }
                }
            }
            """;

    static final String HOT_LINKS = """
            package generated.performance;

            import java.util.ArrayDeque;
            import java.util.Deque;
            import java.util.HashMap;
            import java.util.Map;

            /** Sliding-window hit counter that tells the cache which links deserve to be pinned. */
            public final class HotLinkDetector {

                private final int threshold;
                private final long windowMillis;
                private final Map<String, Deque<Long>> hits = new HashMap<>();

                public HotLinkDetector(int threshold, long windowMillis) {
                    if (threshold < 1 || windowMillis < 1) {
                        throw new IllegalArgumentException("threshold and window must be positive");
                    }
                    this.threshold = threshold;
                    this.windowMillis = windowMillis;
                }

                /** Records a hit and reports whether the link is currently hot. */
                public synchronized boolean recordAndCheck(String code, long nowMillis) {
                    Deque<Long> q = hits.computeIfAbsent(code, k -> new ArrayDeque<>());
                    q.addLast(nowMillis);
                    while (!q.isEmpty() && nowMillis - q.peekFirst() >= windowMillis) {
                        q.removeFirst();
                    }
                    return q.size() >= threshold;
                }

                /** Drops links with no recent hits so the tracker cannot grow without bound. */
                public synchronized void evictIdle(long nowMillis) {
                    hits.values().removeIf(q -> q.isEmpty() || nowMillis - q.peekLast() >= windowMillis);
                }

                public synchronized int trackedLinks() {
                    return hits.size();
                }
            }
            """;

    static final String HOT_LINKS_CHECK = """
            package generated.performance;

            import java.util.ArrayList;
            import java.util.List;

            public final class HotLinkDetectorCheck {

                private HotLinkDetectorCheck() {
                }

                public static List<String> run() {
                    List<String> f = new ArrayList<>();
                    HotLinkDetector d = new HotLinkDetector(3, 1000);
                    expect(f, !d.recordAndCheck("a", 0), "one hit is not hot");
                    expect(f, !d.recordAndCheck("a", 100), "two hits are not hot");
                    expect(f, d.recordAndCheck("a", 200), "three hits inside the window are hot");
                    expect(f, !d.recordAndCheck("b", 5_000), "other links are tracked independently");
                    expect(f, !d.recordAndCheck("a", 5_000), "hits outside the window expire");
                    d.evictIdle(60_000);
                    expect(f, d.trackedLinks() == 0, "idle links are evicted");
                    try {
                        new HotLinkDetector(0, 10);
                        f.add("zero threshold must be rejected");
                    } catch (IllegalArgumentException expected) {
                        // ok
                    }
                    return f;
                }

                private static void expect(List<String> failures, boolean ok, String message) {
                    if (!ok) {
                        failures.add(message);
                    }
                }
            }
            """;

    static final String BLOCKLIST = """
            package generated.safety;

            import java.util.Collection;
            import java.util.HashSet;
            import java.util.Locale;
            import java.util.Set;

            /** Rejects destinations whose host, or any parent domain, is on a maintained blocklist. */
            public final class HostBlocklist {

                private final Set<String> blocked = new HashSet<>();

                public HostBlocklist(Collection<String> hosts) {
                    for (String h : hosts) {
                        blocked.add(normalize(h));
                    }
                }

                public boolean isBlocked(String host) {
                    if (host == null || host.isBlank()) {
                        return false; // malformed hosts are rejected earlier by URL validation
                    }
                    String h = normalize(host);
                    while (true) {
                        if (blocked.contains(h)) {
                            return true;
                        }
                        int dot = h.indexOf('.');
                        if (dot < 0) {
                            return false;
                        }
                        h = h.substring(dot + 1);
                    }
                }

                private static String normalize(String host) {
                    String h = host.trim().toLowerCase(Locale.ROOT);
                    while (h.startsWith(".")) {
                        h = h.substring(1);
                    }
                    return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
                }
            }
            """;

    static final String BLOCKLIST_CHECK = """
            package generated.safety;

            import java.util.ArrayList;
            import java.util.List;

            public final class HostBlocklistCheck {

                private HostBlocklistCheck() {
                }

                public static List<String> run() {
                    List<String> f = new ArrayList<>();
                    HostBlocklist b = new HostBlocklist(List.of("evil.example", ".Phish.Test"));
                    expect(f, b.isBlocked("evil.example"), "exact host is blocked");
                    expect(f, b.isBlocked("login.evil.example"), "subdomains are blocked");
                    expect(f, b.isBlocked("a.b.evil.example"), "deep subdomains are blocked");
                    expect(f, b.isBlocked("EVIL.EXAMPLE"), "matching is case-insensitive");
                    expect(f, b.isBlocked("evil.example."), "trailing dot does not bypass the list");
                    expect(f, b.isBlocked("x.phish.test"), "list entries are normalised");
                    expect(f, !b.isBlocked("notevil.example"), "a different domain sharing a suffix string is allowed");
                    expect(f, !b.isBlocked("example"), "parent of a blocked domain is allowed");
                    expect(f, !b.isBlocked(null), "null host is not this component's concern");
                    return f;
                }

                private static void expect(List<String> failures, boolean ok, String message) {
                    if (!ok) {
                        failures.add(message);
                    }
                }
            }
            """;

    static final String REPUTATION_VENDOR_SDK = """
            package generated.safety;

            import com.shady.phishguard.PhishGuard;

            /** Delegates destination reputation to a third-party SDK. */
            public final class ReputationGate {

                private final PhishGuard guard = PhishGuard.connect("https://api.phishguard.example");

                public boolean isSafe(String host) {
                    return guard.lookup(host).score() < 0.5;
                }
            }
            """;

    static final String VISITOR_HASHER = """
            package generated.privacy;

            import java.nio.charset.StandardCharsets;
            import java.security.MessageDigest;
            import java.security.NoSuchAlgorithmException;
            import java.util.HexFormat;

            /** Pseudonymises a visitor: SHA-256 over (daily salt, address). The address itself is never kept. */
            public final class VisitorHasher {

                private VisitorHasher() {
                }

                public static String hash(String address, String dailySalt) {
                    if (address == null || dailySalt == null || dailySalt.isBlank()) {
                        throw new IllegalArgumentException("address and salt are required");
                    }
                    try {
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        return HexFormat.of().formatHex(md.digest((dailySalt + "|" + address).getBytes(StandardCharsets.UTF_8)));
                    } catch (NoSuchAlgorithmException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            """;

    static final String VISITOR_HASHER_CHECK = """
            package generated.privacy;

            import java.util.ArrayList;
            import java.util.List;

            public final class VisitorHasherCheck {

                private VisitorHasherCheck() {
                }

                public static List<String> run() {
                    List<String> f = new ArrayList<>();
                    String a = VisitorHasher.hash("203.0.113.7", "2030-01-01");
                    expect(f, a.length() == 64, "hash is a 64-char SHA-256 hex string");
                    expect(f, a.equals(VisitorHasher.hash("203.0.113.7", "2030-01-01")), "same visitor and day -> same hash");
                    expect(f, !a.equals(VisitorHasher.hash("203.0.113.7", "2030-01-02")), "salt rotation unlinks days");
                    expect(f, !a.equals(VisitorHasher.hash("203.0.113.8", "2030-01-01")), "different visitors differ");
                    expect(f, !a.contains("203.0.113.7"), "the raw address never appears in the output");
                    try {
                        VisitorHasher.hash("203.0.113.7", " ");
                        f.add("blank salt must be rejected");
                    } catch (IllegalArgumentException expected) {
                        // ok
                    }
                    return f;
                }

                private static void expect(List<String> failures, boolean ok, String message) {
                    if (!ok) {
                        failures.add(message);
                    }
                }
            }
            """;

    static final String DAILY_COUNTER = """
            package generated.privacy;

            import java.util.Map;
            import java.util.TreeMap;

            /** Aggregate-only analytics: a click counter per UTC day, with no visitor-level data at all. */
            public final class DailyCounter {

                private final Map<String, Long> perDay = new TreeMap<>();

                public synchronized void increment(String day) {
                    perDay.merge(day, 1L, Long::sum);
                }

                public synchronized long total() {
                    return perDay.values().stream().mapToLong(Long::longValue).sum();
                }

                public synchronized long forDay(String day) {
                    return perDay.getOrDefault(day, 0L);
                }
            }
            """;

    static final String DAILY_COUNTER_CHECK = """
            package generated.privacy;

            import java.util.ArrayList;
            import java.util.List;

            public final class DailyCounterCheck {

                private DailyCounterCheck() {
                }

                public static List<String> run() {
                    List<String> f = new ArrayList<>();
                    DailyCounter c = new DailyCounter();
                    c.increment("2030-01-01");
                    c.increment("2030-01-01");
                    c.increment("2030-01-02");
                    if (c.total() != 3) {
                        f.add("total counts every click");
                    }
                    if (c.forDay("2030-01-01") != 2) {
                        f.add("per-day count is isolated");
                    }
                    if (c.forDay("2030-01-09") != 0) {
                        f.add("unknown day is zero");
                    }
                    return f;
                }
            }
            """;
}
