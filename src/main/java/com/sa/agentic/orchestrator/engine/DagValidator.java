package com.sa.agentic.orchestrator.engine;

import com.sa.agentic.orchestrator.model.TaskSpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structural checks on a plan: unique ids, known dependencies, no cycles. Also graph helpers for the engine. */
public final class DagValidator {

    private DagValidator() {
    }

    public static void validate(List<TaskSpec> plan) {
        Map<String, TaskSpec> byId = new HashMap<>();
        for (TaskSpec t : plan) {
            if (t.id() == null || t.id().isBlank()) {
                throw new IllegalArgumentException("task id must not be blank");
            }
            if (byId.put(t.id(), t) != null) {
                throw new IllegalArgumentException("duplicate task id " + t.id());
            }
        }
        for (TaskSpec t : plan) {
            for (String d : t.dependsOn()) {
                if (!byId.containsKey(d)) {
                    throw new IllegalArgumentException("task " + t.id() + " depends on unknown task " + d);
                }
                if (d.equals(t.id())) {
                    throw new IllegalArgumentException("task " + t.id() + " depends on itself");
                }
            }
        }
        // Kahn's algorithm
        Map<String, Integer> indegree = new HashMap<>();
        plan.forEach(t -> indegree.put(t.id(), t.dependsOn().size()));
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, n) -> {
            if (n == 0) {
                queue.add(id);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String id = queue.poll();
            visited++;
            for (TaskSpec t : plan) {
                if (t.dependsOn().contains(id) && indegree.merge(t.id(), -1, Integer::sum) == 0) {
                    queue.add(t.id());
                }
            }
        }
        if (visited != plan.size()) {
            throw new IllegalArgumentException("plan contains a dependency cycle");
        }
    }

    /** All tasks that (transitively) depend on {@code taskId}, in plan order. */
    public static Set<String> downstreamOf(List<TaskSpec> plan, String taskId) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String> work = new ArrayDeque<>(List.of(taskId));
        Set<String> seen = new HashSet<>();
        while (!work.isEmpty()) {
            String cur = work.poll();
            if (!seen.add(cur)) {
                continue;
            }
            for (TaskSpec t : plan) {
                if (t.dependsOn().contains(cur)) {
                    out.add(t.id());
                    work.add(t.id());
                }
            }
        }
        List<String> ordered = new ArrayList<>();
        plan.stream().map(TaskSpec::id).filter(out::contains).forEach(ordered::add);
        return new LinkedHashSet<>(ordered);
    }
}
