package com.sa.agentic.orchestrator.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.orchestrator.model.WorkflowRun;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stores each run as a versioned JSON snapshot; the version column gives optimistic locking. */
@Repository
public class RunRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public RunRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Persists the run and bumps {@code run.version}. Throws if another writer got there first. */
    public void save(WorkflowRun run) {
        String state = write(run);
        Timestamp now = Timestamp.from(Instant.now());
        if (run.version == 0) {
            jdbc.sql("INSERT INTO workflow_run (id, scenario, status, version, state_json, created_at, updated_at) "
                            + "VALUES (?, ?, ?, 1, ?, ?, ?)")
                    .params(run.id, run.scenario, run.status.name(), state, Timestamp.from(run.createdAt), now)
                    .update();
        } else {
            int updated = jdbc.sql("UPDATE workflow_run SET status = ?, version = version + 1, state_json = ?, "
                            + "updated_at = ? WHERE id = ? AND version = ?")
                    .params(run.status.name(), state, now, run.id, run.version)
                    .update();
            if (updated == 0) {
                throw new IllegalStateException("concurrent modification of run " + run.id);
            }
        }
        run.version++;
    }

    public Optional<WorkflowRun> find(String id) {
        return jdbc.sql("SELECT state_json, version FROM workflow_run WHERE id = ?").param(id)
                .query((rs, n) -> read(rs.getString("state_json"), rs.getLong("version"))).optional();
    }

    public List<WorkflowRun> findAll() {
        return jdbc.sql("SELECT state_json, version FROM workflow_run ORDER BY created_at DESC")
                .query((rs, n) -> read(rs.getString("state_json"), rs.getLong("version"))).list();
    }

    public List<String> findIdsByStatus(String... statuses) {
        String in = String.join(",", java.util.Collections.nCopies(statuses.length, "?"));
        return jdbc.sql("SELECT id FROM workflow_run WHERE status IN (" + in + ")")
                .params((Object[]) statuses).query(String.class).list();
    }

    private String write(WorkflowRun run) {
        try {
            return json.writeValueAsString(run);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise run " + run.id, e);
        }
    }

    private WorkflowRun read(String state, long version) {
        try {
            WorkflowRun run = json.readValue(state, WorkflowRun.class);
            run.version = version;
            return run;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read run snapshot", e);
        }
    }
}
