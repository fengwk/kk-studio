package fun.fengwk.kkstudio.project.domain;

import java.util.Collection;
import java.util.Objects;

/**
 * Issue+工作阶段的执行额度，不保存 Agent、Thread 或 Session。
 *
 * <p>{@code used = COUNT(Run WHERE issue_id = ? AND state = ? AND ordinal > budgetAfterOrdinal)}：不按
 * Agent、Thread、Session 或 Run 状态过滤，因此更换 Agent、返工与重开都不重置额度，新 Run 的失败、取消与 UNKNOWN 仍消耗一次。{@code
 * budgetAfterOrdinal} 是重置高水位，服务端取 {@code nextRunOrdinal - 1}，只能前进。
 */
public record IssueStageBudget(ProjectStateCode state, int maxRuns, long budgetAfterOrdinal) {

  public IssueStageBudget {
    Objects.requireNonNull(state, "state");
    if (ProjectWorkflowReservedState.isReserved(state)) {
      throw new IllegalArgumentException("stage budget must target a work stage: " + state);
    }
    if (maxRuns <= 0) {
      throw new IllegalArgumentException("maxRuns must be > 0");
    }
    if (budgetAfterOrdinal < 0) {
      throw new IllegalArgumentException("budgetAfterOrdinal must be >= 0");
    }
  }

  /** 首次自动执行接受时的初始授权：阶段必须是 workflow 中启用且有 Agent 的工作阶段。 */
  public static IssueStageBudget authorize(
      ProjectWorkflow workflow, ProjectStateCode state, int maxRuns) {
    requireEnabledAgentStage(workflow, state);
    return new IssueStageBudget(state, maxRuns, 0);
  }

  /** 重置后的新授权：高水位取服务端当前的 {@code nextRunOrdinal - 1}，不得回退而重新授权历史 Run。 */
  public IssueStageBudget reset(ProjectWorkflow workflow, int maxRuns, long nextRunOrdinal) {
    requireEnabledAgentStage(workflow, state);
    if (nextRunOrdinal < 1) {
      throw new IllegalArgumentException("nextRunOrdinal must be >= 1");
    }
    long highWaterOrdinal = nextRunOrdinal - 1;
    if (highWaterOrdinal < budgetAfterOrdinal) {
      throw new IllegalArgumentException("budget high-water mark must not move backwards");
    }
    return new IssueStageBudget(state, maxRuns, highWaterOrdinal);
  }

  /** 已消耗次数：本阶段所有 Run（不论状态与 Agent）中序号大于高水位的数量。 */
  public long usedRuns(Collection<IssueRun> issueRuns) {
    Objects.requireNonNull(issueRuns, "issueRuns");
    long used = 0;
    for (IssueRun run : issueRuns) {
      if (state.equals(run.state()) && run.ordinal() > budgetAfterOrdinal) {
        used++;
      }
    }
    return used;
  }

  /** 剩余额度：不小于 0，只限制新建 Run，不限制已接受 Run 的恢复。 */
  public long remainingRuns(Collection<IssueRun> issueRuns) {
    return Math.max(0L, maxRuns - usedRuns(issueRuns));
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
