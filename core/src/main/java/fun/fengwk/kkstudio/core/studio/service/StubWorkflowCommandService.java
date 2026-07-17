package fun.fengwk.kkstudio.core.studio.service;

import fun.fengwk.kkstudio.studio.StudioFeatureNotReadyException;
import fun.fengwk.kkstudio.studio.StudioWorkspaces;
import fun.fengwk.kkstudio.studio.workflow.WorkflowCommandService;
import fun.fengwk.kkstudio.studio.workflow.WorkflowDocument;
import fun.fengwk.kkstudio.studio.workflow.WorkflowVersion;

/** TODO: implement draft AST persistence, compiler and publish-to-Function. */
public class StubWorkflowCommandService implements WorkflowCommandService {

  @Override
  public WorkflowDocument createWorkflow(long workspaceId, String name) {
    StudioWorkspaces.requireDefault(workspaceId);
    throw new StudioFeatureNotReadyException("WorkflowCommandService.createWorkflow");
  }

  @Override
  public WorkflowVersion saveDraft(long workflowId, long baseRevision, String astJson) {
    throw new StudioFeatureNotReadyException("WorkflowCommandService.saveDraft");
  }

  @Override
  public WorkflowVersion publish(long workflowId, long baseRevision, long draftVersionId) {
    throw new StudioFeatureNotReadyException("WorkflowCommandService.publish");
  }
}
