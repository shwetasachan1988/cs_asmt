package com.sa.agentic.orchestrator.persistence;

import com.sa.agentic.orchestrator.model.Artifact;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Artifacts are immutable; a re-run produces a new version rather than overwriting. */
@Repository
public class ArtifactRepository {

    private final JdbcClient jdbc;

    public ArtifactRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int nextVersion(String runId, String nodeId, String type, String name) {
        // MAX() over zero rows yields NULL, which JdbcClient.single() refuses - read it as 0 explicitly
        int max = jdbc.sql("SELECT MAX(version) FROM artifact WHERE run_id = ? AND node_id = ? AND type = ? AND name = ?")
                .params(runId, nodeId, type, name)
                .query((rs, n) -> rs.getInt(1)).single();
        return max + 1;
    }

    public void insert(Artifact a) {
        jdbc.sql("INSERT INTO artifact (id, run_id, node_id, type, name, version, sha256, content, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(a.id(), a.runId(), a.nodeId(), a.type(), a.name(), a.version(), a.sha256(), a.content(),
                        Timestamp.from(a.createdAt()))
                .update();
    }

    public Optional<Artifact> find(String id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(MAPPER).optional();
    }

    public List<Artifact> findByRun(String runId) {
        return jdbc.sql(SELECT + " WHERE run_id = ? ORDER BY created_at, id").param(runId).query(MAPPER).list();
    }

    /** Latest version of every (type, name) a node has produced. */
    public List<Artifact> latestByNode(String runId, String nodeId) {
        return findByRun(runId).stream()
                .filter(a -> a.nodeId().equals(nodeId))
                .collect(java.util.stream.Collectors.toMap(a -> a.type() + "/" + a.name(), a -> a,
                        (x, y) -> x.version() >= y.version() ? x : y, java.util.LinkedHashMap::new))
                .values().stream().toList();
    }

    private static final String SELECT =
            "SELECT id, run_id, node_id, type, name, version, sha256, content, created_at FROM artifact";

    private static final org.springframework.jdbc.core.RowMapper<Artifact> MAPPER = (rs, n) -> new Artifact(
            rs.getString("id"), rs.getString("run_id"), rs.getString("node_id"), rs.getString("type"),
            rs.getString("name"), rs.getInt("version"), rs.getString("sha256"), rs.getString("content"),
            rs.getTimestamp("created_at").toInstant());
}
