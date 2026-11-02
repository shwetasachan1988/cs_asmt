package com.sa.agentic.orchestrator.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared JSON helpers for the shipped (deterministic) agents. */
abstract class BaseAgent implements Agent {

    protected final ObjectMapper json;

    protected BaseAgent(ObjectMapper json) {
        this.json = json;
    }

    protected String toJson(Object value) {
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise agent output", e);
        }
    }

    protected JsonNode parse(String content) {
        try {
            return json.readTree(content);
        } catch (JsonProcessingException e) {
            throw new NonRetryableAgentException("upstream artifact is not valid JSON: " + e.getOriginalMessage());
        }
    }

    protected static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
