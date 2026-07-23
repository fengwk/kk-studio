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
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** PLATFORM tool: read the durable Thread goal. */
public final class GetGoalTool implements Tool {
  public static final String NAME = "get_goal";
  public static final String VERSION = "1";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
          GoalToolPrompts.load("goal-get-tool.md"),
          NAME,
          new ToolParamsSchema("Read the current Thread goal.", Map.of(), Set.of(), false),
          ToolExecutionLocation.PLATFORM,
          ToolSideEffect.READ_ONLY,
          Duration.ZERO);

  private final GoalStore store;

  public GetGoalTool(GoalStore store) {
    this.store = Objects.requireNonNull(store, "store");
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
    PlatformToolSupport.rejectUnknownFields(args, Set.of());
    ThreadGoal goal = store.find(request.context().threadId()).orElse(null);
    return PlatformToolSupport.success(
        request.call().id(), PlatformToolSupport.formatGetGoal(goal));
  }
}
