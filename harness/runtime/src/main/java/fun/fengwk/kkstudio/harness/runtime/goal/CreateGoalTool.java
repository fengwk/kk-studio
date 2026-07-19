package fun.fengwk.kkstudio.harness.runtime.goal;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolNumberSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** CONTROL tool: create or replace the durable Thread goal. */
public final class CreateGoalTool implements Tool {
  public static final String NAME = "create_goal";
  public static final String VERSION = "1";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
          GoalToolPrompts.load("goal-create-tool.md"),
          NAME,
          new ToolParamsSchema(
              "Create or replace the Thread goal.",
              Map.of(
                  "objective",
                      new ToolStringSchema(
                          "Durable, evidence-checkable objective covering the outcome, verification surface, constraints, boundaries, iteration policy, and blocked stop condition."),
                  "tokenBudget",
                      new ToolNumberSchema(
                          "Optional positive token budget, only when explicitly requested.")),
              Set.of("objective"),
              false),
          ToolExecutionMode.CONTROL,
          ToolSideEffect.IDEMPOTENT,
          Duration.ZERO);

  private final GoalStore store;
  private final Clock clock;

  public CreateGoalTool(GoalStore store, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    return ControlToolSupport.complete(request, listener, this::run);
  }

  private ToolResult run(ToolExecutionRequest request) {
    JsonNode args = ControlToolSupport.requireObjectArgs(request.call().argumentsJson());
    ControlToolSupport.rejectUnknownFields(args, Set.of("objective", "tokenBudget"));
    String objective = ControlToolSupport.requireNonBlankString(args, "objective");
    Long tokenBudget = ControlToolSupport.optionalPositiveLong(args, "tokenBudget");
    ThreadGoal goal =
        store.createOrReplace(
            request.context().threadId(), objective, tokenBudget, clock.instant());
    return ControlToolSupport.success(request.call().id(), ControlToolSupport.formatGoalJson(goal));
  }
}
