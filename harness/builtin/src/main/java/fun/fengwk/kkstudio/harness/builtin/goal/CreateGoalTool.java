package fun.fengwk.kkstudio.harness.builtin.goal;

import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 创建或替换当前 branch Goal 全量快照。 */
public final class CreateGoalTool implements Tool {

  public static final String NAME = "create_goal";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          GoalPrompts.text("create-goal.md"),
          NAME,
          GoalPrompts.schema("create-goal.schema.json"),
          ToolSideEffect.IDEMPOTENT,
          Duration.ZERO);

  private static final ToolRequirements REQUIREMENTS =
      new ToolRequirements(
          false, List.of(new StateDeclaration(GoalFeature.STATE_TYPE, StateMode.WRITE)));

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public ToolRequirements requirements() {
    return REQUIREMENTS;
  }

  /** 创建/替换 Goal 的历史动作：设置的目标文本。 */
  @Override
  public Optional<ToolHistoryRenderer> historyRenderer() {
    return Optional.of(BuiltinHistoryRenderers.createGoal());
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    if (request.context() == null) {
      listener.onComplete(
          GoalToolSupport.error(
              request.call(),
              new IllegalArgumentException("create_goal requires durable ToolExecutionContext")));
      return CompletedToolExecutionHandle.INSTANCE;
    }
    ToolOutcome outcome;
    try {
      ObjectNode arguments = GoalToolSupport.arguments(request.call(), DESCRIPTOR);
      String objective = GoalToolSupport.requiredNonBlankText(arguments, "objective");
      Long tokenBudget = GoalToolSupport.optionalPositiveLong(arguments, "tokenBudget");
      GoalState current = GoalToolSupport.latest(request.context().branch()).orElse(null);
      Instant now = GoalToolSupport.timestamp(request.context().executedAt());
      GoalState state =
          new GoalState(
              objective,
              tokenBudget,
              GoalStatus.ACTIVE,
              null,
              current == null ? now : current.createdAt(),
              now);
      outcome = GoalToolSupport.stateChange(request.call(), state);
    } catch (RuntimeException error) {
      outcome = GoalToolSupport.error(request.call(), error);
    }
    listener.onComplete(outcome);
    return CompletedToolExecutionHandle.INSTANCE;
  }
}
