package com.sa.agentic.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.sa.agentic.orchestrator.agent.ClaudeLlmAdvisor;
import org.junit.jupiter.api.Test;

class LlmAdvisorTest {

    @Test
    void isOffWithoutAKeyAndNeverCallsOut() {
        ClaudeLlmAdvisor advisor = new ClaudeLlmAdvisor("  ", "claude-opus-5-5");
        assertThat(advisor.enabled()).isFalse();
        assertThat(advisor.advise("system", "user")).isEmpty();
    }

    @Test
    void isOnWhenAKeyIsConfigured() {
        assertThat(new ClaudeLlmAdvisor("sk-test", "claude-opus-5-5").enabled()).isTrue();
    }
}
