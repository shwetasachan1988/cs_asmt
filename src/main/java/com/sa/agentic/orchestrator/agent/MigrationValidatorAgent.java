package com.sa.agentic.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.orchestrator.OrchestratorProperties;
import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.Artifact;
import com.sa.agentic.orchestrator.model.Check;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * Executes the candidate migration for real, on the live database engine but in a throw-away schema:
 * existing migrations -> candidate -> assert column -> rollback script -> assert column gone -> re-apply.
 * The scratch schema is always dropped; {@link #rollback} drops it again if the run is unwound.
 */
@Component
public class MigrationValidatorAgent extends BaseAgent {

    private static final Pattern VERSIONED = Pattern.compile("V(\\d+)__.*\\.sql");

    private final DataSource dataSource;
    private final OrchestratorProperties props;

    public MigrationValidatorAgent(ObjectMapper json, DataSource dataSource, OrchestratorProperties props) {
        super(json);
        this.dataSource = dataSource;
        this.props = props;
    }

    @Override
    public String name() {
        return "migration-validator";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws Exception {
        Artifact up = ctx.upstreamArtifact("sql").orElseThrow(() -> new NonRetryableAgentException("migration missing"));
        Artifact down = ctx.upstreamArtifact("rollback-script")
                .orElseThrow(() -> new NonRetryableAgentException("rollback script missing"));
        String[] target = ctx.param("expectColumn", "short_link.expires_at").split("\\.");
        int candidateVersion = Integer.parseInt(up.name().replaceAll("^V(\\d+)__.*$", "$1"));
        String schema = scratchSchema(ctx.runId());

        List<Check> checks = new ArrayList<>();
        List<Path> baseline = existingMigrations(candidateVersion);
        try (Connection c = dataSource.getConnection()) {
            String original = c.getSchema();
            try {
                run(c, "CREATE SCHEMA " + schema);
                c.setSchema(schema);
                for (Path p : baseline) {
                    runScript(c, Files.readString(p, StandardCharsets.UTF_8));
                }
                checks.add(Check.pass("baseline-applied", baseline.size() + " existing migration(s) applied to scratch schema"));

                runScript(c, up.content());
                checks.add(hasColumn(c, schema, target[0], target[1])
                        ? Check.pass("migration-applies", "column " + String.join(".", target) + " exists after " + up.name())
                        : Check.fail("migration-applies", "column missing after migration"));

                runScript(c, down.content());
                checks.add(!hasColumn(c, schema, target[0], target[1])
                        ? Check.pass("rollback-restores-schema", "column removed again by " + down.name())
                        : Check.fail("rollback-restores-schema", "column still present after rollback"));

                runScript(c, up.content());
                checks.add(hasColumn(c, schema, target[0], target[1])
                        ? Check.pass("migration-repeatable", "re-applying after rollback succeeds")
                        : Check.fail("migration-repeatable", "re-apply failed"));
            } catch (SQLException e) {
                checks.add(Check.fail("sql-execution", e.getMessage()));
            } finally {
                try {
                    c.setSchema(original);
                    run(c, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                } catch (SQLException ignored) {
                    // surfaced by rollback(): scratch schema clean-up is retried there
                }
            }
        }
        AgentResult.Builder out = AgentResult.builder("Migration verified on the live DB engine in scratch schema " + schema)
                .rationale("Executing the SQL (apply, assert, rollback, assert, re-apply) proves the rollback plan works "
                        + "before anyone relies on it; the real schema is never touched.");
        checks.forEach(out::check);
        out.artifact("test-report", "migration-validation.md", report(checks, up.name(), down.name(), schema));
        return out.build();
    }

    @Override
    public void rollback(AgentContext ctx) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            run(c, "DROP SCHEMA IF EXISTS " + scratchSchema(ctx.runId()) + " CASCADE");
        }
    }

    private List<Path> existingMigrations(int beforeVersion) throws IOException {
        Path dir = Path.of(props.codebaseRoot()).resolve("resources/db/migration");
        if (!Files.isDirectory(dir)) {
            throw new NonRetryableAgentException("migration directory not found: " + dir.toAbsolutePath());
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> {
                        Matcher m = VERSIONED.matcher(p.getFileName().toString());
                        return m.matches() && Integer.parseInt(m.group(1)) < beforeVersion;
                    })
                    .sorted(Comparator.comparingInt(p -> {
                        Matcher m = VERSIONED.matcher(p.getFileName().toString());
                        m.matches();
                        return Integer.parseInt(m.group(1));
                    })).toList();
        }
    }

    private static String scratchSchema(String runId) {
        return "scratch_" + runId.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static void runScript(Connection c, String script) throws SQLException {
        StringBuilder sb = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (!line.trim().startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        for (String stmt : sb.toString().split(";")) {
            if (!stmt.isBlank()) {
                run(c, stmt.trim());
            }
        }
    }

    private static void run(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static boolean hasColumn(Connection c, String schema, String table, String column) throws SQLException {
        for (String[] candidate : new String[][]{{schema, table, column},
                {schema.toUpperCase(), table.toUpperCase(), column.toUpperCase()}}) {
            try (ResultSet rs = c.getMetaData().getColumns(null, candidate[0], candidate[1], candidate[2])) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String report(List<Check> checks, String up, String down, String schema) {
        StringBuilder sb = new StringBuilder("# Migration validation\n\n- migration: `").append(up)
                .append("`\n- rollback: `").append(down).append("`\n- scratch schema: `").append(schema)
                .append("` (dropped afterwards)\n\n| Check | Result | Detail |\n|---|---|---|\n");
        checks.forEach(c -> sb.append("| ").append(c.name()).append(" | ").append(c.passed() ? "PASS" : "FAIL")
                .append(" | ").append(c.detail()).append(" |\n"));
        return sb.toString();
    }
}
