package com.sa.agentic.orchestrator.engine;

import java.util.Map;

/**
 * @param scenario     scenario id from the catalog
 * @param requirement  free-text requirement; blank = the scenario default
 * @param failureMode  none | transient (retry/fallback showcase) | permanent (retry exhaustion -> rollback showcase)
 * @param chaos        explicit failure injection (taskId -> primary attempts to fail, -1 = always); overrides failureMode
 * @param actor        who started the run (audit)
 */
public record StartRequest(String scenario, String requirement, String failureMode, Map<String, Integer> chaos,
                           String actor) {
}
