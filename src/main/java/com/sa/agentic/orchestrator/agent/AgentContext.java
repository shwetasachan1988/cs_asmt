package com.sa.agentic.orchestrator.agent;

import com.sa.agentic.orchestrator.model.Artifact;
import com.sa.agentic.orchestrator.model.TaskSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything an agent may see: its task, the latest upstream artifacts (by dependency), a read-only copy of the
 * shared context and - on a retry - the previous failure as feedback to repair.
 */
public record AgentContext(
        String runId,
        TaskSpec task,
        int attempt,
        boolean onFallback,
        String requirement,
        Map<String, Object> shared,
        Map<String, List<Artifact>> upstream,
        String feedback) {

    public String param(String key, String defaultValue) {
        return task.params().getOrDefault(key, defaultValue);
    }

    /** Latest artifact of the given type produced by any dependency. */
    public Optional<Artifact> upstreamArtifact(String type) {
        return upstream.values().stream().flatMap(List::stream)
                .filter(a -> a.type().equals(type))
                .max(java.util.Comparator.comparingInt(Artifact::version));
    }

    public List<Artifact> upstreamArtifacts(String type) {
        return upstream.values().stream().flatMap(List::stream).filter(a -> a.type().equals(type)).toList();
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> shared(String key, Class<T> type) {
        Object v = shared.get(key);
        return type.isInstance(v) ? Optional.of((T) v) : Optional.empty();
    }
}
