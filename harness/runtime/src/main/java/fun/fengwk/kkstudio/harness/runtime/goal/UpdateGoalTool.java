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
import fun.fengwk.kkstudio.harness.tool.schema.ToolEnumSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** PLATFORM tool：将 durable Thread goal 标记为 complete 或 blocked。 */
public final class UpdateGoalTool implements Tool {
  public static final String NAME = "update_goal";
  public static final String VERSION = "1";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
          ToolType.PLATFORM,
          GoalToolPrompts.load("goal-update-tool.md"),
          NAME,
          new ToolParamsSchema(
              "将当前 Thread 的 goal 更新为终态。",
              Map.of(
                  "status", new ToolEnumSchema("终态状态。", List.of("complete", "blocked")),
                  "reason", new ToolStringSchema("支持完成或阻塞状态的详细依据和具体证据。")),
              Set.of("status", "reason"),
              false),
          ToolSideEffect.IDEMPOTENT,
          Duration.ZERO);

  private final GoalStore store;
  private final Clock clock;

  public UpdateGoalTool(GoalStore store, Clock clock) {
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
    RuntimeToolSupport.rejectUnknownFields(args, Set.of("status", "reason"));
    GoalStatus status =
        GoalStatus.parseUpdateStatus(RuntimeToolSupport.requireNonBlankString(args, "status"));
    String reason = RuntimeToolSupport.requireNonBlankString(args, "reason");
    try {
      ThreadGoal goal =
          store.updateTerminal(request.context().threadId(), status, reason, clock.instant());
      return RuntimeToolSupport.success(
          request.call().id(), RuntimeToolSupport.formatGoalJson(goal));
    } catch (IllegalStateException error) {
      return RuntimeToolSupport.error(request.call().id(), RuntimeToolSupport.message(error));
    }
  }
}
