package fun.fengwk.kkstudio.harness.builtin.goal;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHistoryRenderers;
import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.GoalSnapshot;
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

/** 只读当前 branch 的用户 Goal 及其 Agent 进度声明。Agent 不能通过本工具创建或改写 Goal 正文。 */
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
          EnvironmentSupport.NONE,
          List.of(new StateDeclaration(GoalFeature.PROGRESS_TYPE, StateMode.READ)));

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
      Optional<GoalSnapshot> goal = GoalToolSupport.goal(request.context().branch());
      GoalProgress progress =
          goal.map(value -> GoalToolSupport.progress(request.context().branch(), value.id()))
              .orElse(Optional.empty())
              .orElse(null);
      String prefix =
          goal.isEmpty()
              ? "There is no user-set goal on this branch."
              : "This is the current branch goal. It is owned by the user and cannot be created or"
                  + " changed by the agent; progress entries are agent reports, not system"
                  + " verification.";
      outcome =
          GoalToolSupport.success(
              request.call(),
              prefix + "\n\n" + GoalToolSupport.envelope(goal.orElse(null), progress));
    } catch (RuntimeException error) {
      outcome = GoalToolSupport.error(request.call(), error);
    }
    listener.onComplete(outcome);
    return CompletedToolExecutionHandle.INSTANCE;
  }
}
