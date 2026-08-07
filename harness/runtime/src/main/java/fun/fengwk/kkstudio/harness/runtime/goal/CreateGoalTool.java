package fun.fengwk.kkstudio.harness.runtime.goal;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
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

/** PLATFORM tool：创建或替换 durable Thread goal。 */
public final class CreateGoalTool implements Tool {
  public static final String NAME = "create_goal";
  public static final String VERSION = "1";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
          ToolType.PLATFORM,
          GoalToolPrompts.load("goal-create-tool.md"),
          NAME,
          new ToolParamsSchema(
              "创建或替换当前 Thread 的 goal。",
              Map.of(
                  "objective",
                      new ToolStringSchema("持久化且可由证据核验的 objective，必须涵盖预期结果、验证面、约束、边界、迭代策略和阻塞停止条件。"),
                  "tokenBudget", new ToolNumberSchema("仅在用户明确要求时提供的可选正数 token 预算。")),
              Set.of("objective"),
              false),
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
    return RuntimeToolSupport.complete(request, listener, this::run);
  }

  private ToolResult run(ToolExecutionRequest request) {
    JsonNode args = RuntimeToolSupport.requireObjectArgs(request.call().argumentsJson());
    RuntimeToolSupport.rejectUnknownFields(args, Set.of("objective", "tokenBudget"));
    String objective = RuntimeToolSupport.requireNonBlankString(args, "objective");
    Long tokenBudget = RuntimeToolSupport.optionalPositiveLong(args, "tokenBudget");
    ThreadGoal goal =
        store.createOrReplace(
            request.context().threadId(), objective, tokenBudget, clock.instant());
    return RuntimeToolSupport.success(request.call().id(), RuntimeToolSupport.formatGoalJson(goal));
  }
}
