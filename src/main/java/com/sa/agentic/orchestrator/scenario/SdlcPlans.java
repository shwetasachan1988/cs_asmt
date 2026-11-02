package com.sa.agentic.orchestrator.scenario;

import com.sa.agentic.orchestrator.model.ApprovalMode;
import com.sa.agentic.orchestrator.model.Risk;
import com.sa.agentic.orchestrator.model.Stage;
import com.sa.agentic.orchestrator.model.TaskSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable sub-graphs. The verification/release tail is the same shape for every change, but its wiring is
 * not linear: test tracks fan out from implementation, documentation runs beside implementation, and release
 * readiness is a join over all evidence.
 */
public final class SdlcPlans {

    private SdlcPlans() {
    }

    /**
     * unit-tests, contract-tests, security-review, integration-tests, docs  ->  release-readiness  ->  release-package.
     *
     * @param implIds       implementation tasks the verification tracks fan out from
     * @param extraEvidence additional tasks release readiness must wait for (e.g. migration validation)
     */
    public static List<TaskSpec> verificationAndRelease(String feature, String designId, List<String> implIds,
                                                        List<String> extraEvidence) {
        List<TaskSpec> out = new ArrayList<>();
        out.add(TaskSpec.of("unit-tests", Stage.TESTING, "unit-test-runner")
                .title("Compile generated code and execute its checks")
                .after(implIds.toArray(String[]::new)).produces("test-report").retries(2).build());
        out.add(TaskSpec.of("contract-tests", Stage.TESTING, "contract-tester")
                .title("Validate the OpenAPI contract")
                .after(designId).produces("test-report").build());
        List<String> secDeps = new ArrayList<>(implIds);
        secDeps.add(designId);
        out.add(TaskSpec.of("security-review", Stage.TESTING, "security-reviewer")
                .title("Security review and threat model")
                .after(secDeps.toArray(String[]::new)).param("feature", feature).produces("security-report").build());
        out.add(TaskSpec.of("integration-tests", Stage.TESTING, "smoke-tester")
                .title("End-to-end regression against the running service")
                .after(designId).param("focus", "baseline").produces("test-report").retries(2).build());
        out.add(TaskSpec.of("docs", Stage.DOCUMENTATION, "doc-writer")
                .title("API reference, runbook and changelog")
                .after(designId).param("feature", feature).produces("docs").build());

        List<String> evidence = new ArrayList<>(List.of("unit-tests", "contract-tests", "security-review",
                "integration-tests", "docs"));
        evidence.addAll(extraEvidence);
        out.add(TaskSpec.of("release-readiness", Stage.RELEASE, "release-manager")
                .title("Release readiness assessment (join over all evidence)")
                .after(evidence.toArray(String[]::new)).risk(Risk.MEDIUM).produces("release-readiness").build());
        out.add(TaskSpec.of("release-package", Stage.RELEASE, "release-packager")
                .title("Assemble release bundle (dry run - nothing is deployed)")
                .after("release-readiness").risk(Risk.HIGH).approval(ApprovalMode.BEFORE)
                .produces("release-bundle").retries(1).build());
        return out;
    }
}
