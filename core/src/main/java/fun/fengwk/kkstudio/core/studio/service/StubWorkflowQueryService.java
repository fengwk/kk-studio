package fun.fengwk.kkstudio.core.studio.service;

import fun.fengwk.kkstudio.studio.StudioWorkspaces;
import fun.fengwk.kkstudio.studio.workflow.WorkflowDocument;
import fun.fengwk.kkstudio.studio.workflow.WorkflowQueryService;
import fun.fengwk.kkstudio.studio.workflow.WorkflowVersion;

import java.util.List;
import java.util.Optional;

/** TODO: implement workflow persistence queries. */
public class StubWorkflowQueryService implements WorkflowQueryService {

  @Override
  public Optional<WorkflowDocument> findDocument(long workflowId) {
    return Optional.empty();
  }

  @Override
  public List<WorkflowDocument> listDocuments(long workspaceId) {
    StudioWorkspaces.requireDefault(workspaceId);
    return List.of();
  }

  @Override
  public Optional<WorkflowVersion> findVersion(long workflowVersionId) {
    return Optional.empty();
  }
}
