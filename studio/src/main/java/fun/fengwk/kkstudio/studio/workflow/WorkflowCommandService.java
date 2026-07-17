package fun.fengwk.kkstudio.studio.workflow;

/**
 * Write port for Workflow draft/publish.
 *
 * <p>AST compilation and runtime scheduling remain TODO until the first real workflow slice.
 */
public interface WorkflowCommandService {

  WorkflowDocument createWorkflow(long workspaceId, String name);

  WorkflowVersion saveDraft(long workflowId, long baseRevision, String astJson);

  WorkflowVersion publish(long workflowId, long baseRevision, long draftVersionId);
}
