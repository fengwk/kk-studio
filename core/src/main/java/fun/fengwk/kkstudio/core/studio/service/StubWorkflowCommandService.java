package fun.fengwk.kkstudio.core.studio.service;

import fun.fengwk.kkstudio.studio.workflow.WorkflowCommandService;
import fun.fengwk.kkstudio.studio.workflow.WorkflowDocument;
import fun.fengwk.kkstudio.studio.workflow.WorkflowVersion;

/** TODO: implement draft AST persistence, compiler and publish-to-Function. */
public class StubWorkflowCommandService implements WorkflowCommandService {

  @Override
  public WorkflowDocument createWorkflow(long workspaceId, String name) {
    throw new StudioNotImplementedException("WorkflowCommandService.createWorkflow");
  }

  @Override
  public WorkflowVersion saveDraft(long workflowId, long baseRevision, String astJson) {
    throw new StudioNotImplementedException("WorkflowCommandService.saveDraft");
  }

  @Override
  public WorkflowVersion publish(long workflowId, long baseRevision, long draftVersionId) {
    throw new StudioNotImplementedException("WorkflowCommandService.publish");
  }
}
