package com.sa.agentic.orchestrator.scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ScenarioCatalog {

    private final Map<String, ScenarioDefinition> byId = new LinkedHashMap<>();

    public ScenarioCatalog(List<ScenarioDefinition> scenarios) {
        scenarios.stream().sorted(java.util.Comparator.comparing(ScenarioDefinition::kind))
                .forEach(s -> byId.put(s.id(), s));
    }

    public ScenarioDefinition get(String id) {
        ScenarioDefinition s = byId.get(id);
        if (s == null) {
            throw new IllegalArgumentException("unknown scenario '" + id + "'; available: " + byId.keySet());
        }
        return s;
    }

    public List<ScenarioDefinition> all() {
        return List.copyOf(byId.values());
    }
}
