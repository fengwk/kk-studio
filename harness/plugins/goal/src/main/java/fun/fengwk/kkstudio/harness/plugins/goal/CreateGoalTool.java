package fun.fengwk.kkstudio.harness.plugins.goal;

import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.plugin.api.PluginStateDeclaration;
import fun.fengwk.kkstudio.harness.plugin.api.PluginStateMode;
import fun.fengwk.kkstudio.harness.plugin.api.PluginTool;
import fun.fengwk.kkstudio.harness.plugin.api.PluginToolContext;
import fun.fengwk.kkstudio.harness.plugin.api.PluginToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 创建或替换当前 branch Goal 全量快照。 */
public final class CreateGoalTool implements PluginTool {

  public static final String NAME = "create_goal";
  public static final String VERSION = "2";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
          ToolType.PLATFORM,
          GoalPrompts.text("create-goal.md"),
          NAME,
          GoalPrompts.schema("create-goal.schema.json"),
          ToolSideEffect.IDEMPOTENT,
          Duration.ZERO);

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public List<PluginStateDeclaration> stateAccesses() {
    return List.of(new PluginStateDeclaration(GoalPlugin.STATE_TYPE, PluginStateMode.WRITE));
  }

  @Override
  public PluginToolResult execute(PluginToolContext context, ToolCall call) {
    try {
      ObjectNode arguments = GoalToolSupport.arguments(call, DESCRIPTOR);
      String objective = GoalToolSupport.requiredNonBlankText(arguments, "objective");
      Long tokenBudget = GoalToolSupport.optionalPositiveLong(arguments, "tokenBudget");
      GoalState current = GoalToolSupport.latest(context.branch()).orElse(null);
      Instant now = GoalToolSupport.timestamp(context.executedAt());
      GoalState state =
          new GoalState(
              objective,
              tokenBudget,
              GoalStatus.ACTIVE,
              null,
              current == null ? now : current.createdAt(),
              now);
      return GoalToolSupport.stateChange(call, state);
    } catch (RuntimeException error) {
      return GoalToolSupport.error(call, error);
    }
  }
}
