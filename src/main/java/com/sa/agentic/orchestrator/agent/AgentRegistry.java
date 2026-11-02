package com.sa.agentic.orchestrator.agent;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class AgentRegistry {

    private final Map<String, Agent> byName;

    public AgentRegistry(List<Agent> agents) {
        this.byName = agents.stream().collect(Collectors.toUnmodifiableMap(Agent::name, Function.identity()));
    }

    public Agent get(String name) {
        Agent a = byName.get(name);
        if (a == null) {
            throw new IllegalArgumentException("no agent registered with name '" + name + "'");
        }
        return a;
    }

    public boolean has(String name) {
        return byName.containsKey(name);
    }

    public List<String> names() {
        return byName.keySet().stream().sorted().toList();
    }
}
