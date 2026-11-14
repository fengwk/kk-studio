package fun.fengwk.kkstudio.project.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.IssueStageBudgetRepository;

/**
 * Run 接受前的领域门禁：归档、Issue 门禁、可执行阶段、唯一活动 Run 与阶段额度授权。
 *
 * <p>只做判定与额度授权的写入，不碰 Harness、不写 Run；调用方必须在自己的 {@code @Transactional} 边界与 owner 锁内使用，判定失败即整笔回滚。
 * 顺序固定为归档 → 门禁 → 阶段 → 活动 Run → 额度，与 Run 接受用例此前逐行一致。
 */
@Component
@AllArgsConstructor
final class IssueRunAdmission {

  private final IssueRunRepository issueRunRepository;
  private final IssueStageBudgetRepository stageBudgetRepository;
  private final ProjectWorkflowJsonCodec workflowCodec;

  /** 校验并授权本次接受；额度不存在时按阶段定义授权并落库。 */
  Admitted admit(Project project, Issue issue) {
    if (project.isArchived()) {
      throw DomainException.validation("project", "Cannot accept a run in an archived project");
    }
    if (issue.isGateClosed()) {
      throw DomainException.validation(
          "issue", "Cannot accept a run while the issue gate is closed");
    }
    ProjectWorkflow workflow = workflowCodec.decode(project.getWorkflowJson());
    ProjectStateCode stateCode = ProjectStateCode.of(issue.getState());
    if (ProjectWorkflowReservedState.isReserved(stateCode)) {
      throw DomainException.validation(
          "issue_run", "Issue is not in an executable work stage: " + issue.getState());
    }
    ProjectWorkflowState stage = workflow.require(stateCode);
    if (!stage.enabled() || !stage.hasAgent()) {
      throw DomainException.validation(
          "issue_run", "Stage " + issue.getState() + " has no enabled agent");
    }
    String agentName = stage.agent();
    if (issueRunRepository.lockActiveByIssueId(issue.getId()) != null) {
      throw DomainException.validation("issue_run", "Issue already has an active run");
    }
    StageBudgetView budgetRow = stageBudgetRepository.get(issue.getId(), issue.getState());
    if (budgetRow == null) {
      budgetRow = StageBudgetView.authorize(workflow, stateCode, stage.maxRuns());
      if (!stageBudgetRepository.insert(issue.getId(), budgetRow)) {
        throw new IllegalStateException("failed to authorize initial stage budget");
      }
    }
    if (budgetRow.usedRuns() >= budgetRow.maxRuns()) {
      throw DomainException.validation(
          "issue_run", "Stage budget is exhausted; a human must reset the stage budget");
    }
    return new Admitted(stage, agentName);
  }

  /** 通过门禁的阶段定义与本次 Run 使用的 Agent 名。 */
  record Admitted(ProjectWorkflowState stage, String agentName) {}
}
