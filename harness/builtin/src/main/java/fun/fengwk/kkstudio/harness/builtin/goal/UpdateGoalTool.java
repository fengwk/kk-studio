package fun.fengwk.kkstudio.harness.builtin.goal;

import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 把当前用户 Goal 的 Agent 声明（complete | blocked + reason）记成 goal-id-bound 的 {@code goal.progress}。
 *
 * <p>不复制或改写目标正文，不删除 Goal，也不推进任何业务状态；没有用户 Goal 或当前 Goal 已有终态声明时拒绝，避免迟到/重复的 陈旧报告被当成当前目标的进度。
 */
public final class UpdateGoalTool implements Tool {

  public static final String NAME = "update_goal";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          GoalPrompts.text("update-goal.md"),
          NAME,
          GoalPrompts.schema("update-goal.schema.json"),
          ToolSideEffect.IDEMPOTENT,
          Duration.ZERO);

  private static final ToolRequirements REQUIREMENTS =
      new ToolRequirements(
          EnvironmentSupport.NONE,
          List.of(new StateDeclaration(GoalFeature.PROGRESS_TYPE, StateMode.WRITE)));

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public ToolRequirements requirements() {
    return REQUIREMENTS;
  }

  /** 更新 Goal 的历史动作：终态与原因。 */
  @Override
  public Optional<ToolHistoryRenderer> historyRenderer() {
    return Optional.of(BuiltinHistoryRenderers.updateGoal());
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    if (request.context() == null) {
      listener.onComplete(
          GoalToolSupport.error(
              request.call(),
              new IllegalArgumentException("update_goal requires durable ToolExecutionContext")));
      return CompletedToolExecutionHandle.INSTANCE;
    }
    ToolOutcome outcome;
    try {
      ObjectNode arguments = GoalToolSupport.arguments(request.call(), DESCRIPTOR);
      GoalStatus status =
          GoalStatus.parseTerminal(GoalToolSupport.requiredNonBlankText(arguments, "status"));
      String reason = GoalToolSupport.requiredNonBlankText(arguments, "reason");
      GoalSnapshot goal =
          GoalToolSupport.goal(request.context().branch())
              .orElseThrow(() -> new IllegalStateException("No goal is set on this branch."));
      GoalProgress reported =
          GoalToolSupport.progress(request.context().branch(), goal.id()).orElse(null);
      if (reported != null) {
        throw new IllegalStateException(
            "The current goal is already reported "
                + reported.status().wireValue()
                + "; wait for the user to change or clear the goal.");
      }
      Instant now = GoalToolSupport.timestamp(request.context().executedAt());
      outcome =
          GoalToolSupport.progressChange(
              request.call(), new GoalProgress(goal.id(), status, reason, now));
    } catch (RuntimeException error) {
      outcome = GoalToolSupport.error(request.call(), error);
    }
    listener.onComplete(outcome);
    return CompletedToolExecutionHandle.INSTANCE;
  }
}
