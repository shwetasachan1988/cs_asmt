package com.sa.agentic.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sa.agentic.orchestrator.engine.DagValidator;
import com.sa.agentic.orchestrator.model.Stage;
import com.sa.agentic.orchestrator.model.TaskSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class DagValidatorTest {

    private static TaskSpec t(String id, String... deps) {
        return TaskSpec.of(id, Stage.IMPLEMENTATION, "codegen").after(deps).build();
    }

    @Test
    void acceptsDiamondShapedGraph() {
        DagValidator.validate(List.of(t("a"), t("b", "a"), t("c", "a"), t("d", "b", "c")));
    }

    @Test
    void rejectsCycles() {
        assertThatThrownBy(() -> DagValidator.validate(List.of(t("a", "c"), t("b", "a"), t("c", "b"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle");
    }

    @Test
    void rejectsSelfDependencyUnknownDependencyAndDuplicates() {
        assertThatThrownBy(() -> DagValidator.validate(List.of(t("a", "a")))).hasMessageContaining("itself");
        assertThatThrownBy(() -> DagValidator.validate(List.of(t("a", "zzz")))).hasMessageContaining("unknown");
        assertThatThrownBy(() -> DagValidator.validate(List.of(t("a"), t("a")))).hasMessageContaining("duplicate");
    }

    @Test
    void downstreamIsTransitiveAndOrdered() {
        List<TaskSpec> plan = List.of(t("a"), t("b", "a"), t("c", "b"), t("x"), t("d", "c", "x"));
        assertThat(DagValidator.downstreamOf(plan, "a")).containsExactly("b", "c", "d");
        assertThat(DagValidator.downstreamOf(plan, "x")).containsExactly("d");
        assertThat(DagValidator.downstreamOf(plan, "d")).isEmpty();
    }
}
