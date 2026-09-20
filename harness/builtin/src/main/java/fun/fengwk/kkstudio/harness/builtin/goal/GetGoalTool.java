package fun.fengwk.kkstudio.harness.builtin.goal;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHistoryRenderers;
import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.StateMode;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 读取当前 branch 最新 Goal 快照。 */
public final class GetGoalTool implements Tool {

  public static final String NAME = "get_goal";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          GoalPrompts.text("get-goal.md"),
          NAME,
          GoalPrompts.schema("get-goal.schema.json"),
          ToolSideEffect.READ_ONLY,
          Duration.ZERO);

  private static final ToolRequirements REQUIREMENTS =
      new ToolRequirements(
          false, List.of(new StateDeclaration(GoalFeature.STATE_TYPE, StateMode.READ)));

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public ToolRequirements requirements() {
    return REQUIREMENTS;
  }

  /** 读取 Goal 的历史动作：无参数，动作恒定。 */
  @Override
  public Optional<ToolHistoryRenderer> historyRenderer() {
    return Optional.of(BuiltinHistoryRenderers.getGoal());
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    if (request.context() == null) {
      listener.onComplete(
          GoalToolSupport.error(
              request.call(),
              new IllegalArgumentException("get_goal requires durable ToolExecutionContext")));
      return CompletedToolExecutionHandle.INSTANCE;
    }
    ToolOutcome outcome;
    try {
      GoalToolSupport.arguments(request.call(), DESCRIPTOR);
      GoalState state = GoalToolSupport.latest(request.context().branch()).orElse(null);
      String prefix =
          state == null
              ? "There is no current branch goal."
              : "This is the current branch goal. Use it to advance or verify the objective.";
      outcome =
          GoalToolSupport.success(
              request.call(), prefix + "\n\n" + GoalToolSupport.envelope(state));
    } catch (RuntimeException error) {
      outcome = GoalToolSupport.error(request.call(), error);
    }
    listener.onComplete(outcome);
    return CompletedToolExecutionHandle.INSTANCE;
  }
}
