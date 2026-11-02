package com.sa.agentic.orchestrator.engine;

import com.sa.agentic.orchestrator.OrchestratorProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * The only place orchestrated output touches the filesystem: {workspace}/{runId}/{taskId}/{artifact name}.
 * Paths are re-validated here (defence in depth beside AutonomyBoundaryPolicy).
 */
@Component
public class WorkspaceService {

    private final Path root;

    public WorkspaceService(OrchestratorProperties props) {
        this.root = Path.of(props.workspaceRoot()).toAbsolutePath().normalize();
    }

    public void write(String runId, String taskId, String name, String content) {
        Path target = taskDir(runId, taskId).resolve(name).normalize();
        if (!target.startsWith(taskDir(runId, taskId))) {
            throw new IllegalArgumentException("path escapes workspace: " + name);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Rollback of a task's file output. */
    public void deleteTask(String runId, String taskId) {
        Path dir = taskDir(runId, taskId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path taskDir(String runId, String taskId) {
        Path dir = root.resolve(runId).resolve(taskId).normalize();
        if (!dir.startsWith(root)) {
            throw new IllegalArgumentException("illegal run/task id");
        }
        return dir;
    }
}
