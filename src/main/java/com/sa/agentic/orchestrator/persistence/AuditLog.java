package com.sa.agentic.orchestrator.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.orchestrator.model.AuditEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Append-only audit trail. Each event embeds the previous event's hash, forming a per-run chain:
 * editing or deleting any row is detectable via {@link #verify(String)}.
 */
@Repository
public class AuditLog {

    public static final String GENESIS = "0".repeat(64);

    public record VerifyResult(boolean valid, int events, String firstBrokenAt) {
    }

    private record Tip(int seq, String hash) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Map<String, Tip> tips = new ConcurrentHashMap<>();

    public AuditLog(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public AuditEvent append(String runId, String actor, String type, String nodeId, Map<String, ?> detail) {
        synchronized (runId.intern()) {
            Tip tip = tips.computeIfAbsent(runId, this::loadTip);
            int seq = tip.seq() + 1;
            Instant ts = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            String body = toJson(detail);
            String hash = hash(tip.hash(), runId, seq, ts, actor, type, nodeId, body);
            jdbc.sql("INSERT INTO audit_event (run_id, seq, ts, actor, type, node_id, detail, prev_hash, hash) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(runId, seq, Timestamp.from(ts), actor, type, nodeId, body, tip.hash(), hash)
                    .update();
            tips.put(runId, new Tip(seq, hash));
            return new AuditEvent(0, runId, seq, ts, actor, type, nodeId, body, tip.hash(), hash);
        }
    }

    public List<AuditEvent> events(String runId) {
        return jdbc.sql("SELECT id, run_id, seq, ts, actor, type, node_id, detail, prev_hash, hash "
                        + "FROM audit_event WHERE run_id = ? ORDER BY seq")
                .param(runId)
                .query((rs, n) -> new AuditEvent(rs.getLong("id"), rs.getString("run_id"), rs.getInt("seq"),
                        rs.getTimestamp("ts").toInstant(), rs.getString("actor"), rs.getString("type"),
                        rs.getString("node_id"), rs.getString("detail"), rs.getString("prev_hash"),
                        rs.getString("hash")))
                .list();
    }

    public VerifyResult verify(String runId) {
        String prev = GENESIS;
        int expectedSeq = 1;
        List<AuditEvent> events = events(runId);
        for (AuditEvent e : events) {
            String recomputed = hash(prev, e.runId(), e.seq(), e.ts(), e.actor(), e.type(), e.nodeId(), e.detail());
            if (e.seq() != expectedSeq || !e.prevHash().equals(prev) || !e.hash().equals(recomputed)) {
                return new VerifyResult(false, events.size(), "seq " + e.seq());
            }
            prev = e.hash();
            expectedSeq++;
        }
        return new VerifyResult(true, events.size(), null);
    }

    private Tip loadTip(String runId) {
        return jdbc.sql("SELECT seq, hash FROM audit_event WHERE run_id = ? ORDER BY seq DESC LIMIT 1")
                .param(runId).query((rs, n) -> new Tip(rs.getInt("seq"), rs.getString("hash"))).optional()
                .orElse(new Tip(0, GENESIS));
    }

    private String toJson(Map<String, ?> detail) {
        try {
            return json.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"unserialisable detail\"}";
        }
    }

    static String hash(String prev, String runId, int seq, Instant ts, String actor, String type, String nodeId,
                       String detail) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String material = String.join("|", prev, runId, String.valueOf(seq), ts.toString(), actor, type,
                    nodeId == null ? "" : nodeId, detail);
            return HexFormat.of().formatHex(md.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
