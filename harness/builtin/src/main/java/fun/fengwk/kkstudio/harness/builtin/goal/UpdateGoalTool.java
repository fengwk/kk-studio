package fun.fengwk.kkstudio.harness.builtin.goal;

import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.StateMode;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 把当前 branch Goal 更新为 complete 或 blocked 全量快照。 */
public final class UpdateGoalTool implements Tool {

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

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    if (request.context() == null) {
      listener.onComplete(
          GoalToolSupport.error(
              request.call(),
              new IllegalArgumentException("update_goal requires durable ToolExecutionContext")));
      return new CompletedHandle();
    }
    try {
      ObjectNode arguments = GoalToolSupport.arguments(request.call(), DESCRIPTOR);
      GoalStatus status =
          GoalStatus.parseTerminal(GoalToolSupport.requiredNonBlankText(arguments, "status"));
      String reason = GoalToolSupport.requiredNonBlankText(arguments, "reason");
      GoalState current =
          GoalToolSupport.latest(request.context().branch())
              .orElseThrow(() -> new IllegalStateException("No goal is set on this branch."));
      if (current.status() != GoalStatus.ACTIVE) {
        throw new IllegalStateException(
            "Goal status is "
                + current.status().wireValue()
                + "; it cannot be updated by the model.");
      }
      Instant now = GoalToolSupport.timestamp(request.context().executedAt());
      GoalState state =
          new GoalState(
              current.objective(), current.tokenBudget(), status, reason, current.createdAt(), now);
      listener.onComplete(GoalToolSupport.stateChange(request.call(), state));
    } catch (RuntimeException error) {
      listener.onComplete(GoalToolSupport.error(request.call(), error));
    }
    return new CompletedHandle();
  }

  private static final class CompletedHandle implements ToolExecutionHandle {
    @Override
    public void cancel() {}

    @Override
    public boolean isCancelled() {
      return false;
    }
  }
}
