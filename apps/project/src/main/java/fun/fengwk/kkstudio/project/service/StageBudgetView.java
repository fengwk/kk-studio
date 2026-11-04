package fun.fengwk.kkstudio.project.service;

import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;

import java.util.Objects;

/** 阶段额度视图：从 Run 历史实时计算的已消耗与剩余次数。 */
public record StageBudgetView(
    String state, int maxRuns, long budgetAfterOrdinal, long usedRuns, long remainingRuns) {
  public static StageBudgetView budget(ProjectStateCode state, int maxRuns, long after) {
    Objects.requireNonNull(state, "state");
    if (ProjectWorkflowReservedState.isReserved(state)) {
      throw new IllegalArgumentException("stage budget must target a work stage: " + state);
    }
    if (maxRuns <= 0) {
      throw new IllegalArgumentException("maxRuns must be > 0");
    }
    if (after < 0) {
      throw new IllegalArgumentException("budgetAfterOrdinal must be >= 0");
    }
    return new StageBudgetView(state.value(), maxRuns, after, 0, maxRuns);
  }

  public static StageBudgetView authorize(
      ProjectWorkflow workflow, ProjectStateCode state, int maxRuns) {
    requireEnabledAgentStage(workflow, state);
    return budget(state, maxRuns, 0);
  }

  public StageBudgetView reset(ProjectWorkflow workflow, int maxRuns, long nextRunOrdinal) {
    ProjectStateCode code = ProjectStateCode.of(state);
    requireEnabledAgentStage(workflow, code);
    if (nextRunOrdinal < 1) {
      throw new IllegalArgumentException("nextRunOrdinal must be >= 1");
    }
    long highWaterOrdinal = nextRunOrdinal - 1;
    if (highWaterOrdinal < budgetAfterOrdinal) {
      throw new IllegalArgumentException("budget high-water mark must not move backwards");
    }
    return budget(code, maxRuns, highWaterOrdinal);
  }

  private static void requireEnabledAgentStage(ProjectWorkflow workflow, ProjectStateCode state) {
    Objects.requireNonNull(workflow, "workflow");
    ProjectWorkflowState stage = workflow.require(state);
    if (!stage.enabled() || !stage.hasAgent()) {
      throw new IllegalArgumentException(
          "stage budget requires an enabled agent stage, but " + state + " is not one");
    }
  }
}
