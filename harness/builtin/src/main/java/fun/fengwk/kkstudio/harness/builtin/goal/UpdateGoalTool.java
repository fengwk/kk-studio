package fun.fengwk.kkstudio.harness.builtin.goal;

import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeTool;
import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeToolContext;
import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeToolResult;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.StateMode;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 把当前 branch Goal 更新为 complete 或 blocked 全量快照。 */
public final class UpdateGoalTool implements DeclarativeTool {

  public static final String NAME = "update_goal";
  public static final String VERSION = "2";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
          GoalPrompts.text("update-goal.md"),
          NAME,
          GoalPrompts.schema("update-goal.schema.json"),
          ToolSideEffect.IDEMPOTENT,
          Duration.ZERO);

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public List<StateDeclaration> stateAccesses() {
    return List.of(new StateDeclaration(GoalFeature.STATE_TYPE, StateMode.WRITE));
  }

  @Override
  public DeclarativeToolResult execute(DeclarativeToolContext context, ToolCall call) {
    try {
      ObjectNode arguments = GoalToolSupport.arguments(call, DESCRIPTOR);
      GoalStatus status =
          GoalStatus.parseTerminal(GoalToolSupport.requiredNonBlankText(arguments, "status"));
      String reason = GoalToolSupport.requiredNonBlankText(arguments, "reason");
      GoalState current =
          GoalToolSupport.latest(context.branch())
              .orElseThrow(() -> new IllegalStateException("No goal is set on this branch."));
      if (current.status() != GoalStatus.ACTIVE) {
        throw new IllegalStateException(
            "Goal status is "
                + current.status().wireValue()
                + "; it cannot be updated by the model.");
      }
      Instant now = GoalToolSupport.timestamp(context.executedAt());
      GoalState state =
          new GoalState(
              current.objective(), current.tokenBudget(), status, reason, current.createdAt(), now);
      return GoalToolSupport.stateChange(call, state);
    } catch (RuntimeException error) {
      return GoalToolSupport.error(call, error);
    }
  }
}
