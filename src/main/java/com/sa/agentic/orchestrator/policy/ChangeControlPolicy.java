package com.sa.agentic.orchestrator.policy;

import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.ApprovalMode;
import com.sa.agentic.orchestrator.model.Risk;
import com.sa.agentic.orchestrator.model.Stage;
import com.sa.agentic.orchestrator.model.TaskSpec;
import com.sa.agentic.orchestrator.model.TaskState;
import com.sa.agentic.orchestrator.model.TaskStatus;
import com.sa.agentic.orchestrator.model.Violation;
import com.sa.agentic.orchestrator.model.Violation.Severity;
import com.sa.agentic.orchestrator.model.WorkflowRun;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Change control:
 * <ul>
 *   <li>plan time - every HIGH-risk task (including ones an agent adds mid-run) must carry a human approval checkpoint;</li>
 *   <li>entry - a BEFORE-approval task may only start with a recorded approval from a human;</li>
 *   <li>entry - release work needs every other task succeeded and nothing stale;</li>
 *   <li>exit - schema changes must ship with a rollback script.</li>
 * </ul>
 */
@Component
public class ChangeControlPolicy implements Policy {

    @Override
    public String id() {
        return "CHG-001-change-control";
    }

    @Override
    public List<Violation> checkPlan(WorkflowRun run, TaskSpec spec) {
        if (spec.risk() == Risk.HIGH && spec.approval() == ApprovalMode.NONE) {
            return List.of(new Violation(id(), Severity.BLOCK,
                    "high-risk task '" + spec.id() + "' must declare a human approval checkpoint"));
        }
        return List.of();
    }

    @Override
    public List<Violation> checkEntry(PolicyContext ctx) {
        List<Violation> out = new ArrayList<>();
        TaskSpec spec = ctx.spec();
        TaskState st = ctx.state();
        if (spec.approval() == ApprovalMode.BEFORE) {
            var a = st.approval;
            if (a == null || !a.approved() || a.approver() == null || a.approver().isBlank()
                    || a.approver().toLowerCase().startsWith("agent")) {
                out.add(new Violation(id(), Severity.BLOCK,
                        "task '" + spec.id() + "' requires prior approval by a human, none recorded"));
            }
        }
        if (spec.stage() == Stage.RELEASE) {
            for (TaskSpec other : ctx.run().plan) {
                if (other.stage() == Stage.RELEASE) {
                    continue;
                }
                TaskState os = ctx.run().tasks.get(other.id());
                if (os.status != TaskStatus.SUCCEEDED && os.status != TaskStatus.CANCELLED) {
                    out.add(new Violation(id(), Severity.BLOCK,
                            "release blocked: '" + other.id() + "' is " + os.status));
                }
            }
        }
        return out;
    }

    @Override
    public List<Violation> checkExit(PolicyContext ctx, AgentResult result) {
        if ("true".equals(ctx.spec().params().get("schemaChange"))
                && result.artifacts().stream().noneMatch(a -> a.type().equals("rollback-script"))) {
            return List.of(new Violation(id(), Severity.BLOCK,
                    "schema change '" + ctx.spec().id() + "' did not ship a rollback script"));
        }
        return List.of();
    }
}
