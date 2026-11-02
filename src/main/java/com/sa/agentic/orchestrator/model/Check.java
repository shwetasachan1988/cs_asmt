package com.sa.agentic.orchestrator.model;

/** A validation an agent actually performed (compile, test run, schema check ...) and its outcome. */
public record Check(String name, boolean passed, String detail) {
    public static Check pass(String name, String detail) {
        return new Check(name, true, detail);
    }

    public static Check fail(String name, String detail) {
        return new Check(name, false, detail);
    }
}
