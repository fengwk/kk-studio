package fun.fengwk.kkstudio.harness.builtin.goal;

import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeTool;
import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeToolContext;
import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeToolResult;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.StateMode;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.List;

/** 读取当前 branch 最新 Goal 快照。 */
public final class GetGoalTool implements DeclarativeTool {

  public static final String NAME = "get_goal";
  public static final String VERSION = "2";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
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
  public List<StateDeclaration> stateAccesses() {
    return List.of(new StateDeclaration(GoalFeature.STATE_TYPE, StateMode.READ));
  }

  @Override
  public DeclarativeToolResult execute(DeclarativeToolContext context, ToolCall call) {
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
