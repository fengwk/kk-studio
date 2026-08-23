package fun.fengwk.kkstudio.harness.plugins.goal;

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
import java.util.List;

/** 读取当前 branch 最新 Goal 快照。 */
public final class GetGoalTool implements PluginTool {

  public static final String NAME = "get_goal";
  public static final String VERSION = "2";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
          ToolType.PLATFORM,
          GoalPrompts.text("get-goal.md"),
          NAME,
          GoalPrompts.schema("get-goal.schema.json"),
          ToolSideEffect.READ_ONLY,
          Duration.ZERO);

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public List<PluginStateDeclaration> stateAccesses() {
    return List.of(new PluginStateDeclaration(GoalPlugin.STATE_TYPE, PluginStateMode.READ));
  }

  @Override
  public PluginToolResult execute(PluginToolContext context, ToolCall call) {
    try {
      GoalToolSupport.arguments(call, DESCRIPTOR);
      GoalState state = GoalToolSupport.latest(context.branch()).orElse(null);
      String prefix =
          state == null
              ? "There is no current branch goal."
              : "This is the current branch goal. Use it to advance or verify the objective.";
      return GoalToolSupport.success(call, prefix + "\n\n" + GoalToolSupport.envelope(state));
    } catch (RuntimeException error) {
      return GoalToolSupport.error(call, error);
    }
  }
}
