package fun.fengwk.kkstudio.project.reconcile;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.model.Project;

/** 只读已持久化 workflow 的 stage 查找：损坏的配置或状态编码不参与重建，也不向调用方暴露。 */
final class IssueReconcileStages {

  private IssueReconcileStages() {}

  static ProjectWorkflowState find(
      ProjectWorkflowJsonCodec workflowCodec, Project project, String state) {
    try {
      ProjectWorkflow workflow = workflowCodec.decode(project.getWorkflowJson());
      return workflow.find(ProjectStateCode.of(state)).orElse(null);
    } catch (IllegalArgumentException | DomainException error) {
      return null;
    }
  }
}
