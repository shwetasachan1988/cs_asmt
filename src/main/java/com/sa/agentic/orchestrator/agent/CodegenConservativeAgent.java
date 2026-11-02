package com.sa.agentic.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Fallback implementation agent: same modules, but never external SDKs and never personal data. */
@Component
public class CodegenConservativeAgent extends CodegenAgent {

    public CodegenConservativeAgent(ObjectMapper json) {
        super(json);
    }

    @Override
    public String name() {
        return "codegen-conservative";
    }

    @Override
    protected boolean conservative() {
        return true;
    }
}
