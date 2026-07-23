package fun.fengwk.kkstudio.harness.runtime.goal;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
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

/** PLATFORM tool: mark the durable Thread goal complete or blocked. */
public final class UpdateGoalTool implements Tool {
  public static final String NAME = "update_goal";
  public static final String VERSION = "1";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
          GoalToolPrompts.load("goal-update-tool.md"),
          NAME,
          new ToolParamsSchema(
              "Update the current Thread goal to a terminal status.",
              Map.of(
                  "status", new ToolEnumSchema("Terminal status.", List.of("complete", "blocked")),
                  "reason",
                      new ToolStringSchema(
                          "Detailed rationale and concrete evidence supporting this completion or blocked status.")),
              Set.of("status", "reason"),
              false),
          ToolExecutionLocation.PLATFORM,
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
    return PlatformToolSupport.complete(request, listener, this::run);
  }

  private ToolResult run(ToolExecutionRequest request) {
    JsonNode args = PlatformToolSupport.requireObjectArgs(request.call().argumentsJson());
    PlatformToolSupport.rejectUnknownFields(args, Set.of("status", "reason"));
    GoalStatus status =
        GoalStatus.parseUpdateStatus(PlatformToolSupport.requireNonBlankString(args, "status"));
    String reason = PlatformToolSupport.requireNonBlankString(args, "reason");
    try {
      ThreadGoal goal =
          store.updateTerminal(request.context().threadId(), status, reason, clock.instant());
      return PlatformToolSupport.success(
          request.call().id(), PlatformToolSupport.formatGoalJson(goal));
    } catch (IllegalStateException error) {
      return PlatformToolSupport.error(request.call().id(), PlatformToolSupport.message(error));
    }
  }
}
