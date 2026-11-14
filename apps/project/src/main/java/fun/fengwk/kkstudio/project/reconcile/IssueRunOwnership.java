package fun.fengwk.kkstudio.project.reconcile;

import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;

import java.util.Objects;

/**
 * 活动 Run 是否仍拥有当前职责：稳定 Thread 归属、阶段 Agent 与 Issue 当前状态三者同时匹配才算当前。
 *
 * <p>任一维度失效都意味着迟到的旧 Run：它可以被安全收尾，但绝不能提交交接目标或刷新当前职责。
 */
record IssueRunOwnership(
    IssueAgentThread binding,
    boolean bindingValid,
    boolean stageStillOwned,
    boolean stateStillCurrent) {

  static IssueRunOwnership assess(
      Issue issue, IssueRun run, IssueAgentThread binding, ProjectWorkflowState stage) {
    Objects.requireNonNull(issue, "issue");
    Objects.requireNonNull(run, "run");
    boolean validBinding = binding != null && binding.issueId().equals(issue.getId());
    boolean ownedStage =
        stage != null
            && stage.enabled()
            && stage.hasAgent()
            && validBinding
            && stage.agent().equals(binding.agentName());
    boolean currentState =
        run.getState().equals(issue.getState())
            || (issue.getBlockedFromState() != null
                && run.getState().equals(issue.getBlockedFromState()));
    return new IssueRunOwnership(binding, validBinding, ownedStage, currentState);
  }

  /** 本 Run 仍是当前职责；只有此时才允许提交交接目标。 */
  boolean current() {
    return bindingValid && stageStillOwned && stateStillCurrent;
  }
}
